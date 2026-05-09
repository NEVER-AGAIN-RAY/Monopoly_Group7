package com.monopoly.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.fx.presentation.CardDisplayData;
import com.monopoly.fx.ui.CardView;
import com.monopoly.model.rules.MonopolyDealRulesSummary;
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
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Toggle;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    private boolean quickStartAfterConnect;
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
    private Label phaseValueLabel;
    @FXML
    private Label currentPlayerValueLabel;
    @FXML
    private Label turnPhaseValueLabel;
    @FXML
    private Label drawPileValueLabel;
    @FXML
    private Label discardPileValueLabel;
    @FXML
    private Label drawPileCenterLabel;
    @FXML
    private Label discardPileCenterLabel;
    @FXML
    private Label lastActionLabel;

    @FXML
    private TextField playerIdField;
    @FXML
    private TextField sessionIdField;
    @FXML
    private Spinner<Integer> playerCountSpinner;
    @FXML
    private ComboBox<String> gameModeCombo;
    @FXML
    private Label aiDifficultyLabel;
    @FXML
    private ComboBox<String> aiDifficultyCombo;
    @FXML
    private CheckBox randomizeFirstCheck;
    @FXML
    private Label modeHintLabel;
    @FXML
    private Label summaryLabel;
    @FXML
    private Label errorLabel;

    @FXML
    private Label turnGuideLabel;
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
    private Label handCountLabel;
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
    private TextArea rulesTextArea;

    @FXML
    private void initialize() {
        wsUrlField.setText("ws://localhost:8025/ws");
        sessionIdField.setText("deal-room");
        playerCountSpinner.setValueFactory(new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(2, 5, 2));
        gameModeCombo.getItems().setAll("HVM", "PVP");
        gameModeCombo.getSelectionModel().selectFirst();

        aiDifficultyCombo.getItems().setAll("EASY", "NORMAL", "HARD");
        aiDifficultyCombo.getSelectionModel().select("NORMAL");

        playActionCombo.getItems().setAll("DEPOSIT", "DEPLOY", "ACTION", "DISCARD");
        playActionCombo.getSelectionModel().selectFirst();
        playCardIdField.setPromptText("选择手牌后自动填入");

        trafficArea.setEditable(false);

        if (rulesTextArea != null) {
            rulesTextArea.setEditable(false);
            rulesTextArea.setText(MonopolyDealRulesSummary.buildPlainTextChinese());
        }

        handToggleGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT instanceof CardView cv) {
                selectedCard = cv.getCardData();
                playCardIdField.setText(selectedCard.getId());
                selectedCardLabel.setText("已选：" + selectedCard.getTitleZh());
            } else {
                selectedCard = null;
                selectedCardLabel.setText("选择一张牌");
            }
            syncWizardButtons();
        });

        syncModeUi();
        gameModeCombo.valueProperty().addListener((obs, prev, mode) -> syncModeUi());

        randomizeFirstCheck.setSelected(false);
        statusLabel.setText("未入桌");
        summaryLabel.setText("选择房间设置后，进入牌桌并开局。");
        resetHud();
        handCountLabel.setText("0 张");
        hideError();
        syncWizardButtons();
        refreshButtons();
    }

    private void syncModeUi() {
        String mode = gameModeCombo.getSelectionModel().getSelectedItem();
        boolean hvm = "HVM".equals(mode);
        aiDifficultyCombo.setDisable(!hvm);
        aiDifficultyLabel.setDisable(!hvm);
        if (hvm) {
            playerIdField.setText("human-1");
            modeHintLabel.setText("人机模式：你坐在第一席，其余席位由 AI 接手。");
        } else {
            playerIdField.setText("pvp-1");
            modeHintLabel.setText("人人模式：每个客户端选择自己的席位，再加入同一个房间。");
        }
    }

    private void resetHud() {
        phaseValueLabel.setText("—");
        currentPlayerValueLabel.setText("—");
        turnPhaseValueLabel.setText("—");
        drawPileValueLabel.setText("0");
        discardPileValueLabel.setText("0");
        drawPileCenterLabel.setText("0");
        discardPileCenterLabel.setText("0");
        lastActionLabel.setText("开局后会显示最近一次操作。");
    }

    private void clearTableState() {
        lastStatePayload = null;
        playerBoardContainer.getChildren().clear();
        handStrip.getChildren().clear();
        for (Toggle t : new ArrayList<>(handToggleGroup.getToggles())) {
            handToggleGroup.getToggles().remove(t);
        }
        handToggleGroup.selectToggle(null);
        selectedCard = null;
        handCountLabel.setText("0 张");
        selectedCardLabel.setText("选择一张牌");
        resetHud();
        syncWizardButtons();
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
        if (!ws.isConnected()) {
            quickStartAfterConnect = true;
            onConnect();
            return;
        }
        quickStartAfterConnect = false;
        authAndStartSession();
    }

    private void authAndStartSession() {
        onAuth();
        onStartSession();
    }

    @FXML
    private void onConnect() {
        statusLabel.setText("入桌中...");
        ws.connect(wsUrlField.getText().trim(), new FxWebSocketClient.Listener() {
            @Override
            public void onOpen() {
                Platform.runLater(() -> {
                    statusLabel.setText("已入桌");
                    appendTraffic("« 系统» 已进入牌桌");
                    refreshButtons();
                    updateTurnGuide();
                    if (quickStartAfterConnect) {
                        quickStartAfterConnect = false;
                        authAndStartSession();
                    }
                });
            }

            @Override
            public void onMessage(String text) {
                Platform.runLater(() -> handleInbound(text));
            }

            @Override
            public void onError(Throwable error) {
                Platform.runLater(() -> {
                    statusLabel.setText("入桌失败");
                    quickStartAfterConnect = false;
                    showError("无法进入牌桌：" + error.getMessage());
                    appendTraffic("« 错误» " + error);
                    refreshButtons();
                });
            }

            @Override
            public void onClose(int code, String reason) {
                Platform.runLater(() -> {
                    statusLabel.setText("已离桌");
                    appendTraffic("« 系统» 离开牌桌: " + code + " " + reason);
                    clearTableState();
                    quickStartAfterConnect = false;
                    pendingOptionsResultHandler = null;
                    refreshButtons();
                    updateTurnGuide();
                });
            }
        });
    }

    @FXML
    private void onDisconnect() {
        ws.closeQuietly();
        statusLabel.setText("已离桌");
        clearTableState();
        quickStartAfterConnect = false;
        pendingOptionsResultHandler = null;
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
            showError("请先在设置里选择玩家并加入房间。");
            return;
        }
        if (pendingOptionsResultHandler != null) {
            showError("请等待上一条选项结果。");
            return;
        }
        pendingOptionsResultHandler = payload -> {
            boolean ok = payload.has("ok") && payload.get("ok").getAsBoolean();
            if (!ok) {
                showError(jsonString(payload, "error", "无法生成出牌选项"));
                return;
            }
            if (payload.has("truncated") && !payload.get("truncated").isJsonNull()
                    && payload.get("truncated").getAsBoolean()) {
                appendTraffic("« 提示» 强制交易组合过多，列表已截断。");
            }
            if (!payload.has("options") || !payload.get("options").isJsonArray()) {
                showError("当前没有可用目标。");
                return;
            }
            JsonArray opts = payload.getAsJsonArray("options");
            if (opts.size() == 0) {
                showError("没有可用选项。");
                return;
            }
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < opts.size(); i++) {
                JsonObject row = opts.get(i).getAsJsonObject();
                String lbl = jsonString(row, "labelZh", "");
                labels.add(lbl.isEmpty() ? ("选项 " + (i + 1)) : lbl);
            }
            ChoiceDialog<String> dlg = new ChoiceDialog<>(labels.get(0), labels);
            dlg.initOwner(dialogOwner());
            dlg.setTitle("选择目标");
            dlg.setHeaderText("请选择这张牌要影响的目标。");
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
            showError("请先选择一张手牌。");
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
            showError("请先选择一张手牌。");
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
            showError("选项结果解析失败：" + ex.getMessage());
        }
    }

    private void applyInboundError(String raw) {
        pendingOptionsResultHandler = null;
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject p = root.getAsJsonObject("payload");
            String code = jsonString(p, "code", "");
            String msg = jsonString(p, "message", "错误");
            if (!code.isEmpty()) {
                showError(code + "：" + msg);
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
                summaryLabel.setText("已坐下。准备好后可以开局。");
                hideError();
            } else {
                showError(jsonString(p, "error", "加入房间失败"));
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

            String phase = jsonString(p, "phase", "—");
            String current = jsonString(p, "currentPlayerId", "—");
            String turnPhase = jsonString(p, "turnPhase", "—");
            int draw = jsonInt(p, "drawPileCount", 0);
            int disc = jsonInt(p, "discardPileCount", 0);
            boolean over = p.has("gameOver") && p.get("gameOver").getAsBoolean();
            String lastSum = jsonString(p, "lastActionSummary", null);

            updateHud(phase, current, turnPhase, draw, disc, over, lastSum);

            StringBuilder line = new StringBuilder();
            line.append("局面 ").append(phaseText(phase));
            line.append(" ｜ 当前玩家 ").append(current);
            line.append(" ｜ 步骤 ").append(turnPhaseText(turnPhase));
            line.append(" ｜ 牌堆 ").append(draw).append(" ｜ 弃牌 ").append(disc);
            if (over) {
                line.append(" ｜ 对局已结束");
            }
            if (lastSum != null && !lastSum.isBlank()) {
                line.append("\n最近：").append(lastSum);
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
            summaryLabel.setText("状态解析失败：" + ex.getMessage());
        }
    }

    private void updateHud(String phase, String current, String turnPhase, int draw, int disc,
                           boolean gameOver, String lastSummary) {
        phaseValueLabel.setText(gameOver ? phaseText(phase) + " · 结束" : phaseText(phase));
        currentPlayerValueLabel.setText(current == null || current.isBlank() ? "—" : current);
        turnPhaseValueLabel.setText(turnPhaseText(turnPhase));
        String drawText = String.valueOf(Math.max(draw, 0));
        String discardText = String.valueOf(Math.max(disc, 0));
        drawPileValueLabel.setText(drawText);
        discardPileValueLabel.setText(discardText);
        drawPileCenterLabel.setText(drawText);
        discardPileCenterLabel.setText(discardText);
        if (lastSummary != null && !lastSummary.isBlank()) {
            lastActionLabel.setText(lastSummary);
        } else {
            lastActionLabel.setText("等待玩家行动。");
        }
    }

    private static String phaseText(String phase) {
        if (phase == null || phase.isBlank()) {
            return "—";
        }
        return switch (phase) {
            case "INIT" -> "开局";
            case "START_SESSION", "SESSION_STARTED" -> "已开局";
            case "DRAW" -> "摸牌";
            case "PLAY" -> "出牌";
            case "END_TURN" -> "回合结束";
            case "GAME_OVER" -> "游戏结束";
            case "GAME_FORCE_END" -> "强制结束";
            case "SAVE", "LOAD" -> "存档";
            default -> phase;
        };
    }

    private static String turnPhaseText(String turnPhase) {
        if (turnPhase == null || turnPhase.isBlank()) {
            return "—";
        }
        return switch (turnPhase) {
            case "DRAW" -> "摸牌";
            case "PLAY" -> "出牌";
            case "WAITING_FOR_RESPONSE" -> "等待响应";
            case "END_TURN" -> "结束回合";
            case "UNKNOWN" -> "—";
            default -> turnPhase;
        };
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
        rentPaymentHint.setText("你需要支付 " + due
                + "M。选择要交出的银行牌或地产牌；也可以让系统自动挑选。");
        rentPaymentPickPane.getChildren().clear();
        JsonObject self = findPlayerInState(p, local);
        if (self != null) {
            addRentPaymentChoices(self.getAsJsonArray("bankCards"), "银行");
            addRentPaymentChoices(self.getAsJsonArray("propertyZoneCards"), "财产");
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
        rentPaymentSumLabel.setText("已选合计 " + sel + "M ／ 应付 " + pendingRentPaymentM + "M");
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
            showError("请至少选择一张牌，或使用「自动支付」。");
            return;
        }
        if (computeSelectedPaymentSum() < pendingRentPaymentM) {
            showError("所选牌面值不足应付额 " + pendingRentPaymentM + "M。");
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
        if (turnGuideLabel == null) {
            return;
        }
        if (!ws.isConnected()) {
            turnGuideLabel.setText("先进入牌桌，然后开局。");
            turnGuideLabel.getStyleClass().setAll("turn-guide");
            return;
        }
        if (lastStatePayload == null) {
            turnGuideLabel.setText("已入桌。点击「开局」开始游戏。");
            turnGuideLabel.getStyleClass().setAll("turn-guide");
            return;
        }
        String local = playerIdField.getText().trim();
        String current = jsonString(lastStatePayload, "currentPlayerId", "");
        String tp = jsonString(lastStatePayload, "turnPhase", "");
        if (local.equals(current)) {
            turnGuideLabel.getStyleClass().setAll("turn-guide");
            if ("DRAW".equals(tp)) {
                turnGuideLabel.setText("轮到你：先摸牌。");
            } else if ("PLAY".equals(tp)) {
                turnGuideLabel.setText("轮到你：选择一张手牌，再决定存银行、放地产、打行动牌或弃牌。");
            } else if ("WAITING_FOR_RESPONSE".equals(tp)) {
                turnGuideLabel.setText(
                        "轮到你响应：可以打免租牌；如果要支付租金，请在上方选择要交出的牌。");
            } else {
                turnGuideLabel.setText("轮到你。可以结束回合，或按当前局面继续操作。");
            }
        } else {
            turnGuideLabel.getStyleClass().setAll("turn-guide", "turn-guide-wait");
            turnGuideLabel.setText("当前轮到 " + current + "。请稍候。");
        }
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
                selectedCardLabel.setText("当前没有手牌");
                handCountLabel.setText("0 张");
                return;
            }
            JsonArray cards = payload.getAsJsonArray("cards");
            int rendered = 0;
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
                rendered++;
            }
            handCountLabel.setText(rendered + " 张");
            if (rendered == 0) {
                selectedCardLabel.setText("当前没有手牌");
            }
            handStrip.layout();
            handScroll.layout();
        } catch (RuntimeException ex) {
            selectedCardLabel.setText("手牌显示失败");
            handCountLabel.setText("0 张");
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
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setText("");
    }

    private void sendEnvelope(String type, Map<String, Object> payload) {
        try {
            String json = WsJson.envelope(type, payload);
            ws.sendRaw(json);
            appendTraffic("← " + json);
        } catch (Exception ex) {
            showError("发送失败：" + ex.getMessage());
            appendTraffic("« 本地» 发送失败: " + ex.getMessage());
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
