package com.monopoly.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.fx.presentation.CardDisplayData;
import com.monopoly.fx.ui.CardView;
import com.monopoly.model.rules.MonopolyDealRulesSummary;
import com.monopoly.model.rules.MultiplayerGuideSummary;
import com.monopoly.fx.ui.PlayerBoardPanel;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Toggle;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 对局主控制器：可视化手牌、玩家桌面、向导式出牌与回合提示。
 */
public class MainController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final FxWebSocketClient ws = new FxWebSocketClient();
    private final javafx.scene.control.ToggleGroup handToggleGroup = new javafx.scene.control.ToggleGroup();

    private JsonObject lastStatePayload;
    private CardDisplayData selectedCard;
    private int pendingRentPaymentM;
    /** 连接成功后自动执行认证并开局。 */
    private boolean autoStartAfterConnect;
    /** 连接超时定时器。 */
    private PauseTransition connectionTimeout;
    /** 等待 {@code ACTION_OPTIONS_RESULT} / {@code PLAY_OPTIONS_RESULT} 时在 FX 线程上消费 payload。 */
    private Consumer<JsonObject> pendingOptionsResultHandler;

    @FXML
    private TextField wsUrlField;
    @FXML
    private Button connectButton;
    @FXML
    private Button disconnectButton;
    @FXML
    private Label statusLabel;

    @FXML
    private TextField playerIdField;
    @FXML
    private TextField sessionIdField;
    @FXML
    private Spinner<Integer> playerCountSpinner;
    @FXML
    private ComboBox<String> gameModeCombo;
    @FXML
    private Label gameModeLabel;
    @FXML
    private Label playerCountLabel;
    @FXML
    private Label aiDifficultyLabel;
    @FXML
    private ComboBox<String> aiDifficultyCombo;
    @FXML
    private Label languageLabel;
    @FXML
    private ComboBox<String> languageCombo;
    @FXML
    private CheckBox randomizeFirstCheck;
    @FXML
    private Label modeHintLabel;
    @FXML
    private Label summaryLabel;
    @FXML
    private Label errorLabel;
    @FXML
    private Label appTitleLabel;
    @FXML
    private Label appSubtitleLabel;
    @FXML
    private TitledPane advancedPane;
    @FXML
    private Label serverLabel;
    @FXML
    private Label playerIdLabel;
    @FXML
    private Label sessionIdLabel;
    @FXML
    private Button authOnlyButton;
    @FXML
    private Button startOnlyButton;
    @FXML
    private Label myHandLabel;
    @FXML
    private Button clearLogButton;
    @FXML
    private TitledPane debugTitledPane;
    @FXML
    private Label debugActionLabel;
    @FXML
    private Button wizardPlayButton;
    @FXML
    private Button directPlayButton;

    @FXML
    private VBox quickStartCard;
    @FXML
    private VBox aiDifficultyBox;
    @FXML
    private HBox gameStatusBar;
    @FXML
    private Label errorLabelInGame;
    @FXML
    private Label boardsTitle;
    @FXML
    private ScrollPane boardsScrollPane;
    @FXML
    private HBox handHeader;
    @FXML
    private VBox actionBar;

    @FXML
    private VBox actionGuidePanel;
    @FXML
    private Label actionGuideTitle;
    @FXML
    private Label actionGuideStep1;
    @FXML
    private VBox rentPaymentBox;
    @FXML
    private Label rentPaymentHint;
    @FXML
    private Label rentPaymentSumLabel;
    @FXML
    private FlowPane rentPaymentPickPane;
    @FXML
    private Button rentPaymentSubmitButton;
    @FXML
    private Button rentPaymentGreedyButton;
    @FXML
    private Button rentPaymentClearButton;
    @FXML
    private HBox playerBoardContainer;
    @FXML
    private ScrollPane handScroll;
    @FXML
    private HBox handStrip;
    @FXML
    private Label selectedCardLabel;

    @FXML
    private Button wizardDepositButton;
    @FXML
    private Button wizardDeployButton;
    @FXML
    private Button wizardActionButton;
    @FXML
    private Button wizardDiscardButton;
    @FXML
    private Button startGameButton;
    @FXML
    private Button drawButton;
    @FXML
    private Button endTurnButton;
    @FXML
    private Label playHintLabel;

    @FXML
    private ComboBox<String> playActionCombo;
    @FXML
    private TextField playCardIdField;
    @FXML
    private TextField targetPlayerField;
    @FXML
    private TextField targetColorField;
    @FXML
    private TextField targetCardField;
    @FXML
    private TextField actorCardField;
    @FXML
    private TextField targetZoneField;
    @FXML
    private TextField actingPlayerField;

    @FXML
    private TextArea trafficArea;
    @FXML
    private javafx.scene.web.WebView rulesWebView;
    @FXML
    private javafx.scene.control.TabPane rootTabPane;
    @FXML
    private javafx.scene.control.Tab gameTab;
    @FXML
    private javafx.scene.control.Tab rulesTab;
    @FXML
    private javafx.scene.control.Tab guideTab;
    @FXML
    private javafx.scene.web.WebView guideWebView;
    @FXML
    private Button showGuideButton;
    @FXML
    private javafx.scene.control.Tab debugTab;

    @FXML
    private void initialize() {
        wsUrlField.setText("ws://localhost:8025/ws");
        sessionIdField.setText("demo-pvp");
        playerCountSpinner.setValueFactory(new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(2, 6, 2));
        gameModeCombo.getItems().setAll("HVM", "PVP");
        gameModeCombo.getSelectionModel().selectFirst();

        aiDifficultyCombo.getItems().setAll("EASY", "NORMAL", "HARD");
        aiDifficultyCombo.getSelectionModel().select("NORMAL");

        languageCombo.getItems().setAll("中文", "English");
        languageCombo.getSelectionModel().selectFirst();
        languageCombo.valueProperty().addListener((obs, prev, val) -> {
            if ("English".equals(val)) {
                I18n.setLocale(Locale.ENGLISH);
            } else {
                I18n.setLocale(Locale.CHINESE);
            }
            applyI18n();
        });

        playActionCombo.getItems().setAll("DEPOSIT", "DEPLOY", "ACTION", "DISCARD");
        playActionCombo.getSelectionModel().selectFirst();
        playCardIdField.setPromptText(I18n.get("debug.cardIdHint"));

        trafficArea.setEditable(false);

        if (rulesWebView != null) {
            loadHtmlToWebView(rulesWebView, MonopolyDealRulesSummary.buildHtmlChinese());
        }

        handToggleGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT instanceof CardView cv) {
                selectedCard = cv.getCardData();
                playCardIdField.setText(selectedCard.getId());
                selectedCardLabel.setText(I18n.get("label.selected", selectedCard.getTitleZh()));
            } else {
                selectedCard = null;
                selectedCardLabel.setText(I18n.get("label.selectCardHint"));
            }
            syncWizardButtons();
        });

        syncModeUi();
        gameModeCombo.valueProperty().addListener((obs, prev, mode) -> syncModeUi());

        randomizeFirstCheck.setSelected(false);
        summaryLabel.setText("");
        hideError();
        syncWizardButtons();
        refreshButtons();
        applyI18n();
    }

    @FXML
    private void onShowGuide() {
        rootTabPane.getSelectionModel().select(guideTab);
    }

    private static void loadHtmlToWebView(javafx.scene.web.WebView webView, String html) {
        try {
            Path tmp = Files.createTempFile("monopoly-ui-", ".html");
            Files.writeString(tmp, html, StandardCharsets.UTF_8);
            tmp.toFile().deleteOnExit();
            webView.getEngine().load(tmp.toUri().toString());
        } catch (IOException e) {
            webView.getEngine().loadContent(html, "text/html");
        }
    }

    private void syncModeUi() {
        String mode = gameModeCombo.getSelectionModel().getSelectedItem();
        boolean hvm = "HVM".equals(mode);
        aiDifficultyBox.setVisible(hvm);
        aiDifficultyBox.setManaged(hvm);
        if (hvm) {
            playerIdField.setText("human-1");
            modeHintLabel.setText(I18n.get("hint.hvm"));
        } else {
            playerIdField.setText("pvp-1");
            modeHintLabel.setText(I18n.get("hint.pvp"));
        }
    }

    private void applyI18n() {
        gameTab.setText(I18n.get("tab.game"));
        rulesTab.setText(I18n.get("tab.rules"));
        debugTab.setText(I18n.get("tab.debug"));
        appTitleLabel.setText(I18n.get("app.title"));
        appSubtitleLabel.setText(I18n.get("app.subtitle"));
        gameModeLabel.setText(I18n.get("label.gameMode"));
        playerCountLabel.setText(I18n.get("label.playerCount"));
        aiDifficultyLabel.setText(I18n.get("label.aiDifficulty"));
        languageLabel.setText(I18n.get("label.language"));
        startGameButton.setText(I18n.get("btn.startGame"));
        advancedPane.setText(I18n.get("advanced.title"));
        serverLabel.setText(I18n.get("label.server"));
        connectButton.setText(I18n.get("btn.connect"));
        playerIdLabel.setText(I18n.get("label.playerId"));
        sessionIdLabel.setText(I18n.get("label.sessionId"));
        randomizeFirstCheck.setText(I18n.get("check.randomFirst"));
        authOnlyButton.setText(I18n.get("btn.authOnly"));
        startOnlyButton.setText(I18n.get("btn.startOnly"));
        disconnectButton.setText(I18n.get("btn.disconnect"));
        drawButton.setText(I18n.get("btn.draw"));
        endTurnButton.setText(I18n.get("btn.endTurn"));
        playHintLabel.setText(I18n.get("label.playHint"));
        wizardDepositButton.setText(I18n.get("btn.deposit"));
        wizardDeployButton.setText(I18n.get("btn.deploy"));
        wizardActionButton.setText(I18n.get("btn.action"));
        wizardDiscardButton.setText(I18n.get("btn.discard"));
        rentPaymentGreedyButton.setText(I18n.get("btn.autoSelect"));
        rentPaymentSubmitButton.setText(I18n.get("btn.confirmPay"));
        rentPaymentClearButton.setText(I18n.get("btn.clearSelection"));
        myHandLabel.setText(I18n.get("label.myHand"));
        selectedCardLabel.setText(I18n.get("label.selectCardHint"));
        clearLogButton.setText(I18n.get("btn.clearLog"));
        debugTitledPane.setText(I18n.get("debug.title"));
        debugActionLabel.setText(I18n.get("debug.action"));
        wizardPlayButton.setText(I18n.get("btn.wizardPlay"));
        directPlayButton.setText(I18n.get("btn.directPlay"));
        playCardIdField.setPromptText(I18n.get("debug.cardIdHint"));
        boardsTitle.setText(I18n.get("label.playerBoards"));
        if (rulesWebView != null) {
            loadHtmlToWebView(rulesWebView, I18n.isChinese()
                    ? MonopolyDealRulesSummary.buildHtmlChinese()
                    : MonopolyDealRulesSummary.buildHtmlEnglish());
        }
        if (guideTab != null) {
            guideTab.setText(I18n.get("tab.guide"));
        }
        if (showGuideButton != null) {
            showGuideButton.setText(I18n.get("btn.showGuide"));
        }
        if (guideWebView != null) {
            loadHtmlToWebView(guideWebView, I18n.isChinese()
                    ? MultiplayerGuideSummary.buildHtmlChinese()
                    : MultiplayerGuideSummary.buildHtmlEnglish());
        }
        if (!ws.isConnected()) {
            statusLabel.setText(I18n.get("label.notConnected"));
        }
        syncModeUi();
        updateTurnGuide();
    }

    private void syncWizardButtons() {
        boolean hasCard = selectedCard != null;
        boolean money = hasCard && "MONEY".equals(selectedCard.getKind());
        boolean actionBank = hasCard && "ACTION".equals(selectedCard.getKind());
        boolean prop = hasCard && ("PROPERTY".equals(selectedCard.getKind()) || "WILD".equals(selectedCard.getKind()));
        boolean act = hasCard && "ACTION".equals(selectedCard.getKind());
        wizardDepositButton.setDisable(!money && !actionBank);
        wizardDeployButton.setDisable(!prop);
        wizardActionButton.setDisable(!act);
        wizardDiscardButton.setDisable(!hasCard);
    }

    private Window dialogOwner() {
        if (summaryLabel == null || summaryLabel.getScene() == null) {
            return null;
        }
        return summaryLabel.getScene().getWindow();
    }

    private List<String> sessionPlayerIds() {
        List<String> ids = new ArrayList<>();
        if (lastStatePayload == null || !lastStatePayload.has("players")) {
            return ids;
        }
        JsonArray arr = lastStatePayload.getAsJsonArray("players");
        for (JsonElement el : arr) {
            if (el.isJsonObject()) {
                String pid = jsonString(el.getAsJsonObject(), "playerId", "");
                if (!pid.isEmpty()) {
                    ids.add(pid);
                }
            }
        }
        return ids;
    }

    @FXML
    private void onQuickAuthAndStart() {
        if (ws.isConnected()) {
            // 已连接，直接认证并开局
            onAuth();
            onStartSession();
            switchToGameView();
        } else {
            // 先连接，连接成功后自动认证并开局
            autoStartAfterConnect = true;
            updateTurnGuide();
            onConnect();
        }
    }

    @FXML
    private void onConnect() {
        statusLabel.setText(I18n.get("status.connecting"));
        cancelConnectionTimeout();
        ws.connect(wsUrlField.getText().trim(), new FxWebSocketClient.Listener() {
            @Override
            public void onOpen() {
                Platform.runLater(() -> {
                    cancelConnectionTimeout();
                    statusLabel.setText(I18n.get("status.connected"));
                    appendTraffic("« " + I18n.get("log.wsOpened") + "»");
                    refreshButtons();
                    if (autoStartAfterConnect) {
                        autoStartAfterConnect = false;
                        onAuth();
                        onStartSession();
                        switchToGameView();
                    }
                    updateTurnGuide();
                });
            }

            @Override
            public void onMessage(String text) {
                Platform.runLater(() -> handleInbound(text));
            }

            @Override
            public void onError(Throwable error) {
                Platform.runLater(() -> {
                    cancelConnectionTimeout();
                    autoStartAfterConnect = false;
                    statusLabel.setText(I18n.get("status.connectFailed", error.getMessage()));
                    appendTraffic("« " + I18n.get("log.error") + "» " + error);
                    refreshButtons();
                    updateTurnGuide();
                });
            }

            @Override
            public void onClose(int code, String reason) {
                Platform.runLater(() -> {
                    cancelConnectionTimeout();
                    statusLabel.setText(I18n.get("status.closed", code));
                    appendTraffic("« " + I18n.get("log.closed", code, reason) + "»");
                    lastStatePayload = null;
                    playerBoardContainer.getChildren().clear();
                    pendingOptionsResultHandler = null;
                    autoStartAfterConnect = false;
                    switchToPreGameView();
                    refreshButtons();
                    updateTurnGuide();
                });
            }
        });
        connectionTimeout = new PauseTransition(Duration.seconds(8));
        connectionTimeout.setOnFinished(e -> {
            if (!ws.isConnected() && autoStartAfterConnect) {
                autoStartAfterConnect = false;
                statusLabel.setText(I18n.get("status.timeout"));
                showError(I18n.get("error.connectHint", wsUrlField.getText().trim()));
                refreshButtons();
                updateTurnGuide();
            }
        });
        connectionTimeout.play();
    }

    private void cancelConnectionTimeout() {
        if (connectionTimeout != null) {
            connectionTimeout.stop();
            connectionTimeout = null;
        }
    }

    @FXML
    private void onDisconnect() {
        ws.closeQuietly();
        statusLabel.setText(I18n.get("status.disconnected"));
        lastStatePayload = null;
        playerBoardContainer.getChildren().clear();
        pendingOptionsResultHandler = null;
        switchToPreGameView();
        refreshButtons();
        updateTurnGuide();
    }

    @FXML
    private void onAuth() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("playerId", playerIdField.getText().trim());
        sendEnvelope("AUTH", p);
    }

    @FXML
    private void onStartSession() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sessionId", sessionIdField.getText().trim());
        p.put("playerCount", playerCountSpinner.getValue());
        String mode = gameModeCombo.getSelectionModel().getSelectedItem();
        p.put("gameMode", mode);
        p.put("randomizeFirstPlayer", randomizeFirstCheck.isSelected());
        if ("HVM".equals(mode)) {
            p.put("aiDifficulty", aiDifficultyCombo.getSelectionModel().getSelectedItem());
        }
        sendEnvelope("START_SESSION", p);
    }

    @FXML
    private void onPing() {
        sendEnvelope("PING", Map.of());
    }

    @FXML
    private void onDraw() {
        sendEnvelope("DRAW", Map.of("count", 2));
    }

    @FXML
    private void onEndTurn() {
        sendEnvelope("END_TURN", Map.of());
    }

    @FXML
    private void onWizardDeposit() {
        if (selectedCard == null) {
            return;
        }
        String k = selectedCard.getKind();
        if (!"MONEY".equals(k) && !"ACTION".equals(k)) {
            return;
        }
        hideError();
        requestPlayOptionsThenPlay(selectedCard.getId(), "DEPOSIT");
    }

    @FXML
    private void onWizardDeploy() {
        if (selectedCard == null) {
            return;
        }
        String k = selectedCard.getKind();
        if (!"PROPERTY".equals(k) && !"WILD".equals(k)) {
            return;
        }
        hideError();
        requestPlayOptionsThenPlay(selectedCard.getId(), "DEPLOY");
    }

    @FXML
    private void onWizardDiscard() {
        if (selectedCard == null) {
            return;
        }
        hideError();
        requestPlayOptionsThenPlay(selectedCard.getId(), "DISCARD");
    }

    @FXML
    private void onWizardAction() {
        if (selectedCard == null || !"ACTION".equals(selectedCard.getKind())) {
            return;
        }
        hideError();
        requestPlayOptionsThenPlay(selectedCard.getId(), "ACTION");
    }

    /**
     * 先发 {@code PLAY_OPTIONS}，在弹窗中选行后再 {@code PLAY}（与向导按钮、高级「按所选动作发送」共用）。
     */
    private void requestPlayOptionsThenPlay(String cardId, String actionType) {
        String me = playerIdField.getText().trim();
        if (me.isEmpty()) {
            showError(I18n.get("error.noPlayerId"));
            return;
        }
        if (pendingOptionsResultHandler != null) {
            showError(I18n.get("error.waitOption"));
            return;
        }
        pendingOptionsResultHandler = payload -> {
            boolean ok = payload.has("ok") && payload.get("ok").getAsBoolean();
            if (!ok) {
                showError(jsonString(payload, "error", I18n.get("error.noOptions")));
                return;
            }
            if (payload.has("truncated") && !payload.get("truncated").isJsonNull()
                    && payload.get("truncated").getAsBoolean()) {
                appendTraffic("« " + I18n.get("msg.optionsTruncated") + " »");
            }
            if (!payload.has("options") || !payload.get("options").isJsonArray()) {
                showError(I18n.get("error.noOptionList"));
                return;
            }
            JsonArray opts = payload.getAsJsonArray("options");
            if (opts.size() == 0) {
                showError(I18n.get("error.noOptions"));
                return;
            }
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < opts.size(); i++) {
                JsonObject row = opts.get(i).getAsJsonObject();
                String lbl = jsonString(row, "labelZh", "");
                labels.add(lbl.isEmpty() ? I18n.get("dialog.option", i + 1) : lbl);
            }
            ChoiceDialog<String> dlg = new ChoiceDialog<>(labels.get(0), labels);
            dlg.initOwner(dialogOwner());
            dlg.setTitle(I18n.get("dialog.chooseParam"));
            dlg.setHeaderText(I18n.get("dialog.chooseHint"));
            dlg.showAndWait().ifPresent(chosenLabel -> {
                int idx = labels.indexOf(chosenLabel);
                if (idx < 0 || idx >= opts.size()) {
                    return;
                }
                JsonObject row = opts.get(idx).getAsJsonObject();
                sendPlayFromOptionRow(actionType, cardId, row);
            });
        };
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("playerId", me);
        p.put("cardId", cardId);
        p.put("actionType", actionType);
        sendEnvelope("PLAY_OPTIONS", p);
    }

    private void sendPlayFromOptionRow(String actionType, String cardId, JsonObject row) {
        boolean allOthers = row.has("allOtherPlayers") && !row.get("allOtherPlayers").isJsonNull()
                && row.get("allOtherPlayers").getAsBoolean();
        String targetPlayerId = allOthers
                ? null
                : blankToNull(jsonString(row, "targetPlayerId", ""));
        sendPlay(
                actionType,
                cardId,
                targetPlayerId,
                blankToNull(jsonString(row, "targetColorKey", "")),
                blankToNull(jsonString(row, "targetCardId", "")),
                blankToNull(jsonString(row, "actorCardId", "")),
                blankToNull(jsonString(row, "targetZone", "")),
                null);
    }

    @FXML
    private void onPlay() {
        String action = playActionCombo.getSelectionModel().getSelectedItem();
        String cardId = playCardIdField.getText().trim();
        if (cardId.isEmpty() && selectedCard != null) {
            cardId = selectedCard.getId();
            playCardIdField.setText(cardId);
        }
        if (cardId.isEmpty()) {
            showError(I18n.get("error.noCardId"));
            return;
        }
        hideError();
        requestPlayOptionsThenPlay(cardId, action);
    }

    /** 调试：不经过 {@code PLAY_OPTIONS}，直接用下方文本框构造 {@code PLAY}。 */
    @FXML
    private void onPlayDirect() {
        String action = playActionCombo.getSelectionModel().getSelectedItem();
        String cardId = playCardIdField.getText().trim();
        if (cardId.isEmpty() && selectedCard != null) {
            cardId = selectedCard.getId();
            playCardIdField.setText(cardId);
        }
        if (cardId.isEmpty()) {
            showError(I18n.get("error.noCardId"));
            return;
        }
        hideError();
        Map<String, Object> payload = WsJson.playPayload(
                action,
                cardId,
                targetPlayerField.getText(),
                targetColorField.getText(),
                targetCardField.getText(),
                actorCardField.getText(),
                targetZoneField.getText(),
                actingPlayerField.getText());
        sendEnvelope("PLAY", payload);
    }

    @FXML
    private void onClearTraffic() {
        trafficArea.clear();
    }

    void shutdown() {
        ws.closeQuietly();
    }

    private void handleInbound(String raw) {
        appendTraffic("→ " + raw);
        String type = WsJson.typeOf(raw);
        switch (type) {
            case "STATE_UPDATE" -> applyStateUpdate(raw);
            case "MY_HAND" -> applyMyHand(raw);
            case "AUTH_RESULT" -> applyAuthResult(raw);
            case "ACTION_OPTIONS_RESULT", "PLAY_OPTIONS_RESULT" -> applyPendingOptionsResult(raw);
            case "ERROR" -> applyInboundError(raw);
            default -> {
            }
        }
    }

    private void applyPendingOptionsResult(String raw) {
        Consumer<JsonObject> handler = pendingOptionsResultHandler;
        pendingOptionsResultHandler = null;
        if (handler == null) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject p = root.getAsJsonObject("payload");
            handler.accept(p);
        } catch (RuntimeException ex) {
            showError(I18n.get("msg.stateParseError", ex.getMessage()));
        }
    }

    private void applyInboundError(String raw) {
        pendingOptionsResultHandler = null;
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject p = root.getAsJsonObject("payload");
            String code = jsonString(p, "code", "");
            String msg = jsonString(p, "message", I18n.get("log.error"));
            if (!code.isEmpty()) {
                showError(code + ": " + msg);
            } else {
                showError(msg);
            }
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    private void applyAuthResult(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject p = root.getAsJsonObject("payload");
            boolean ok = p.has("ok") && p.get("ok").getAsBoolean();
            if (ok) {
                summaryLabel.setText(I18n.get("msg.authSuccess"));
                hideError();
            } else {
                showError(jsonString(p, "error", I18n.get("error.authFailed")));
            }
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    private void applyStateUpdate(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject p = root.getAsJsonObject("payload");
            lastStatePayload = p;

            if (quickStartCard.isVisible()) {
                switchToGameView();
            }

            String phase = jsonString(p, "phase", "—");
            String current = jsonString(p, "currentPlayerId", "—");
            String turnPhase = jsonString(p, "turnPhase", "—");
            int draw = jsonInt(p, "drawPileCount", 0);
            int disc = jsonInt(p, "discardPileCount", 0);
            boolean over = p.has("gameOver") && p.get("gameOver").getAsBoolean();
            String lastSum = jsonString(p, "lastActionSummary", null);

            StringBuilder line = new StringBuilder();
            line.append(I18n.get("state.phase", phase));
            line.append(" | ").append(I18n.get("state.currentTurn", current));
            line.append(" | ").append(I18n.get("state.turnPhase", turnPhase));
            line.append(" | ").append(I18n.get("state.drawPile", draw));
            line.append(" | ").append(I18n.get("state.discardPile", disc));
            if (over) {
                line.append(" | ").append(I18n.get("state.gameOver"));
            }
            if (lastSum != null && !lastSum.isBlank()) {
                line.append("\n").append(I18n.get("state.recent", lastSum));
            }
            summaryLabel.setText(line.toString());

            String err = jsonString(p, "lastErrorMessage", null);
            if (err != null && !err.isBlank()) {
                showError(err);
            } else {
                hideError();
            }

            rebuildPlayerBoard(p);
            updateRentPaymentPanel(p);
            updateTurnGuide();
        } catch (RuntimeException ex) {
            summaryLabel.setText(I18n.get("msg.stateParseError", ex.getMessage()));
        }
    }

    private void updateRentPaymentPanel(JsonObject p) {
        if (rentPaymentBox == null || rentPaymentPickPane == null) {
            return;
        }
        String local = playerIdField.getText().trim();
        String tp = jsonString(p, "turnPhase", "");
        String pendingPid = jsonString(p, "pendingResponsePlayerId", "");
        String pendingRole = jsonString(p, "pendingResponseRole", "");
        Integer due = null;
        if (p.has("pendingPaymentAmountM") && !p.get("pendingPaymentAmountM").isJsonNull()) {
            try {
                due = p.get("pendingPaymentAmountM").getAsInt();
            } catch (RuntimeException ignored) {
                due = null;
            }
        }
        boolean show = "WAITING_FOR_RESPONSE".equals(tp)
                && local.equals(pendingPid)
                && "TENANT".equals(pendingRole)
                && due != null && due > 0;
        rentPaymentBox.setVisible(show);
        rentPaymentBox.setManaged(show);
        if (!show) {
            rentPaymentPickPane.getChildren().clear();
            pendingRentPaymentM = 0;
            return;
        }
        pendingRentPaymentM = due;
        rentPaymentHint.setText(I18n.get("rent.hint", due));
        rentPaymentPickPane.getChildren().clear();
        JsonObject self = findPlayerInState(p, local);
        if (self != null) {
            addRentPaymentChoices(self.getAsJsonArray("bankCards"), I18n.get("rent.zoneBank"));
            addRentPaymentChoices(self.getAsJsonArray("propertyZoneCards"), I18n.get("rent.zoneProperty"));
        }
        refreshRentPaymentSumLabel();
    }

    private JsonObject findPlayerInState(JsonObject payload, String playerId) {
        if (payload == null || !payload.has("players") || !payload.get("players").isJsonArray()) {
            return null;
        }
        for (JsonElement el : payload.getAsJsonArray("players")) {
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (playerId.equals(jsonString(o, "playerId", ""))) {
                    return o;
                }
            }
        }
        return null;
    }

    private void addRentPaymentChoices(JsonArray arr, String zoneLabel) {
        if (arr == null) {
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject c = el.getAsJsonObject();
            String id = jsonString(c, "id", "");
            if (id.isEmpty()) {
                continue;
            }
            String title = jsonString(c, "titleZh", id);
            int vm = jsonInt(c, "valueM", 0);
            CheckBox cb = new CheckBox(zoneLabel + "：" + title + " (" + vm + "M)");
            cb.setUserData(id);
            cb.selectedProperty().addListener((obs, o, n) -> refreshRentPaymentSumLabel());
            rentPaymentPickPane.getChildren().add(cb);
        }
    }

    private void refreshRentPaymentSumLabel() {
        if (rentPaymentSumLabel == null) {
            return;
        }
        int sel = computeSelectedPaymentSum();
        rentPaymentSumLabel.setText(I18n.get("rent.sumLabel", sel, pendingRentPaymentM));
    }

    private int computeSelectedPaymentSum() {
        if (lastStatePayload == null || rentPaymentPickPane == null) {
            return 0;
        }
        String local = playerIdField.getText().trim();
        JsonObject self = findPlayerInState(lastStatePayload, local);
        if (self == null) {
            return 0;
        }
        Map<String, Integer> values = new HashMap<>();
        accumulateCardValues(self.getAsJsonArray("bankCards"), values);
        accumulateCardValues(self.getAsJsonArray("propertyZoneCards"), values);
        int s = 0;
        for (Node n : rentPaymentPickPane.getChildren()) {
            if (n instanceof CheckBox cb && cb.isSelected()) {
                Object ud = cb.getUserData();
                if (ud instanceof String sid) {
                    s += values.getOrDefault(sid, 0);
                }
            }
        }
        return s;
    }

    private static void accumulateCardValues(JsonArray arr, Map<String, Integer> values) {
        if (arr == null) {
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject c = el.getAsJsonObject();
            String id = c.has("id") && !c.get("id").isJsonNull() ? c.get("id").getAsString() : "";
            if (id.isEmpty()) {
                continue;
            }
            int vm = 0;
            if (c.has("valueM") && !c.get("valueM").isJsonNull()) {
                try {
                    vm = c.get("valueM").getAsInt();
                } catch (RuntimeException ignored) {
                    vm = 0;
                }
            }
            values.put(id, vm);
        }
    }

    @FXML
    private void onRentPaymentSubmit() {
        if (rentPaymentPickPane == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Node n : rentPaymentPickPane.getChildren()) {
            if (n instanceof CheckBox cb && cb.isSelected()) {
                ids.add((String) cb.getUserData());
            }
        }
        if (ids.isEmpty()) {
            showError(I18n.get("error.selectAtLeast"));
            return;
        }
        if (computeSelectedPaymentSum() < pendingRentPaymentM) {
            showError(I18n.get("error.insufficientValue", pendingRentPaymentM));
            return;
        }
        hideError();
        sendEnvelope("PLAY", WsJson.playResponsePass(playerIdField.getText().trim(), ids));
    }

    @FXML
    private void onRentPaymentGreedy() {
        hideError();
        sendEnvelope("PLAY", WsJson.playResponsePass(playerIdField.getText().trim(), null));
    }

    @FXML
    private void onRentPaymentClear() {
        if (rentPaymentPickPane == null) {
            return;
        }
        for (Node n : rentPaymentPickPane.getChildren()) {
            if (n instanceof CheckBox cb) {
                cb.setSelected(false);
            }
        }
        refreshRentPaymentSumLabel();
    }

    private void rebuildPlayerBoard(JsonObject payload) {
        playerBoardContainer.getChildren().clear();
        if (!payload.has("players") || !payload.get("players").isJsonArray()) {
            return;
        }
        String current = jsonString(payload, "currentPlayerId", "");
        JsonArray arr = payload.getAsJsonArray("players");
        for (JsonElement el : arr) {
            if (el.isJsonObject()) {
                JsonObject po = el.getAsJsonObject();
                String pid = jsonString(po, "playerId", "");
                playerBoardContainer.getChildren().add(new PlayerBoardPanel(po, pid.equals(current)));
            }
        }
    }

    private void updateTurnGuide() {
        if (actionGuidePanel == null) {
            return;
        }
        if (!ws.isConnected()) {
            if (autoStartAfterConnect) {
                setGuideState(false,
                        I18n.get("guide.connecting"),
                        I18n.get("guide.pleaseWait"));
            } else {
                setGuideState(false,
                        I18n.get("guide.welcome"),
                        I18n.get("guide.selectMode"));
            }
            return;
        }
        if (lastStatePayload == null) {
            setGuideState(false,
                    I18n.get("guide.waitConnect"),
                    I18n.get("guide.waitStart"));
            return;
        }
        String local = playerIdField.getText().trim();
        String current = jsonString(lastStatePayload, "currentPlayerId", "");
        String tp = jsonString(lastStatePayload, "turnPhase", "");
        boolean over = lastStatePayload.has("gameOver")
                && lastStatePayload.get("gameOver").getAsBoolean();
        if (over) {
            setGuideState(false, I18n.get("guide.gameOver"), I18n.get("guide.waitNext"));
            return;
        }
        if (local.equals(current)) {
            switch (tp) {
                case "DRAW" -> setGuideState(false,
                        I18n.get("guide.yourTurnDraw"),
                        I18n.get("guide.clickDraw"));
                case "PLAY" -> setGuideState(false,
                        I18n.get("guide.yourTurnPlay"),
                        I18n.get("guide.playSteps"));
                case "WAITING_FOR_RESPONSE" -> setGuideState(false,
                        I18n.get("guide.yourTurnResponse"),
                        I18n.get("guide.responseSteps"));
                default -> setGuideState(false,
                        I18n.get("guide.yourTurn", tp),
                        I18n.get("guide.followHint"));
            }
        } else {
            setGuideState(true,
                    I18n.get("guide.waiting", current),
                    I18n.get("guide.youWait", local));
        }
    }

    private void setGuideState(boolean waiting, String title, String step) {
        actionGuidePanel.getStyleClass().setAll("action-guide");
        if (waiting) {
            actionGuidePanel.getStyleClass().add("action-guide-wait");
        }
        actionGuideTitle.setText(title);
        actionGuideStep1.setText(step);
    }

    private void applyMyHand(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject payload = root.getAsJsonObject("payload");
            handStrip.getChildren().clear();
            for (Toggle t : new ArrayList<>(handToggleGroup.getToggles())) {
                handToggleGroup.getToggles().remove(t);
            }
            handToggleGroup.selectToggle(null);
            selectedCard = null;
            syncWizardButtons();

            if (!payload.has("cards") || !payload.get("cards").isJsonArray()) {
                selectedCardLabel.setText(I18n.get("msg.noHand"));
                return;
            }
            JsonArray cards = payload.getAsJsonArray("cards");
            for (JsonElement el : cards) {
                if (!el.isJsonObject()) {
                    continue;
                }
                CardDisplayData data = CardDisplayData.fromHandCardJson(el.getAsJsonObject());
                String kindClass = switch (data.getKind()) {
                    case "MONEY" -> "card-money";
                    case "PROPERTY" -> "card-property";
                    case "WILD" -> "card-wild";
                    case "ACTION" -> "card-action";
                    default -> "card-unknown";
                };
                CardView cv = new CardView(data, kindClass);
                cv.setToggleGroup(handToggleGroup);
                handStrip.getChildren().add(cv);
            }
            handStrip.layout();
            handScroll.layout();
        } catch (RuntimeException ex) {
            selectedCardLabel.setText(I18n.get("msg.handParseError"));
        }
    }

    private void sendPlay(
            String actionType,
            String cardId,
            String targetPlayerId,
            String targetColorKey,
            String targetCardId,
            String actorCardId,
            String targetZone,
            String actingPlayerId) {
        Map<String, Object> payload = WsJson.playPayload(
                actionType,
                cardId,
                targetPlayerId,
                targetColorKey,
                targetCardId,
                actorCardId,
                targetZone,
                actingPlayerId);
        sendEnvelope("PLAY", payload);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s;
    }

    private static String jsonString(JsonObject o, String key, String defaultVal) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return defaultVal == null ? "" : defaultVal;
        }
        try {
            String s = o.get(key).getAsString();
            return s == null ? (defaultVal == null ? "" : defaultVal) : s;
        } catch (RuntimeException e) {
            return defaultVal == null ? "" : defaultVal;
        }
    }

    private static int jsonInt(JsonObject o, String key, int def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return def;
        }
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            return def;
        }
    }

    private void showError(String msg) {
        // Show error on whichever error label is currently visible
        if (gameStatusBar != null && gameStatusBar.isVisible()) {
            errorLabelInGame.setText(msg);
            errorLabelInGame.setVisible(true);
            errorLabelInGame.setManaged(true);
        } else {
            errorLabel.setText(msg);
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        }
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setText("");
        if (errorLabelInGame != null) {
            errorLabelInGame.setVisible(false);
            errorLabelInGame.setManaged(false);
            errorLabelInGame.setText("");
        }
    }

    /** 切换到游戏进行中的视图：隐藏快速开始卡片，显示游戏区域。 */
    private void switchToGameView() {
        quickStartCard.setVisible(false);
        quickStartCard.setManaged(false);
        gameStatusBar.setVisible(true);
        gameStatusBar.setManaged(true);
        boardsTitle.setVisible(true);
        boardsTitle.setManaged(true);
        boardsScrollPane.setVisible(true);
        boardsScrollPane.setManaged(true);
        handHeader.setVisible(true);
        handHeader.setManaged(true);
        handScroll.setVisible(true);
        handScroll.setManaged(true);
        actionBar.setVisible(true);
        actionBar.setManaged(true);
    }

    /** 切换回开始前视图：显示快速开始卡片，隐藏游戏区域。 */
    private void switchToPreGameView() {
        quickStartCard.setVisible(true);
        quickStartCard.setManaged(true);
        gameStatusBar.setVisible(false);
        gameStatusBar.setManaged(false);
        boardsTitle.setVisible(false);
        boardsTitle.setManaged(false);
        boardsScrollPane.setVisible(false);
        boardsScrollPane.setManaged(false);
        handHeader.setVisible(false);
        handHeader.setManaged(false);
        handScroll.setVisible(false);
        handScroll.setManaged(false);
        actionBar.setVisible(false);
        actionBar.setManaged(false);
        hideError();
    }

    private void sendEnvelope(String type, Map<String, Object> payload) {
        try {
            String json = WsJson.envelope(type, payload);
            ws.sendRaw(json);
            appendTraffic("← " + json);
        } catch (Exception ex) {
            appendTraffic("« " + I18n.get("log.sendFailed", ex.getMessage()) + " »");
        }
    }

    private void appendTraffic(String line) {
        String stamp = LocalTime.now().format(TIME);
        trafficArea.appendText("[" + stamp + "] " + line + "\n");
    }

    private void refreshButtons() {
        boolean on = ws.isConnected();
        connectButton.setDisable(on);
        disconnectButton.setDisable(!on);
    }
}
