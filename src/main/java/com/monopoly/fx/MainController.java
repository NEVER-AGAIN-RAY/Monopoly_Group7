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
        sessionIdField.setText("demo-pvp");
        playerCountSpinner.setValueFactory(new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(2, 6, 2));
        gameModeCombo.getItems().setAll("HVM", "PVP");
        gameModeCombo.getSelectionModel().selectFirst();

        aiDifficultyCombo.getItems().setAll("EASY", "NORMAL", "HARD");
        aiDifficultyCombo.getSelectionModel().select("NORMAL");

        playActionCombo.getItems().setAll("DEPOSIT", "DEPLOY", "ACTION", "DISCARD");
        playActionCombo.getSelectionModel().selectFirst();
        playCardIdField.setPromptText("点选手牌自动填入");

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
                selectedCardLabel.setText("点选一张牌后，用下方彩色按钮出牌");
            }
            syncWizardButtons();
        });

        syncModeUi();
        gameModeCombo.valueProperty().addListener((obs, prev, mode) -> syncModeUi());

        randomizeFirstCheck.setSelected(false);
        summaryLabel.setText("提示：连接 → 认证并开局 → 摸牌。");
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
            modeHintLabel.setText(
                    "人机：人类固定为 human-1，AI 自动出牌。点「认证并开局」即可单人游玩。");
        } else {
            playerIdField.setText("pvp-1");
            modeHintLabel.setText(
                    "人人：各客户端用 pvp-1、pvp-2… 分别认证后再开局。");
        }
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
        onAuth();
        onStartSession();
    }

    @FXML
    private void onConnect() {
        statusLabel.setText("连接中…");
        ws.connect(wsUrlField.getText().trim(), new FxWebSocketClient.Listener() {
            @Override
            public void onOpen() {
                Platform.runLater(() -> {
                    statusLabel.setText("已连接");
                    appendTraffic("« 系统» 已打开 WebSocket");
                    refreshButtons();
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
                    statusLabel.setText("错误: " + error.getMessage());
                    appendTraffic("« 错误» " + error);
                    refreshButtons();
                });
            }

            @Override
            public void onClose(int code, String reason) {
                Platform.runLater(() -> {
                    statusLabel.setText("已断开 (" + code + ")");
                    appendTraffic("« 系统» 连接关闭: " + code + " " + reason);
                    lastStatePayload = null;
                    playerBoardContainer.getChildren().clear();
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
        statusLabel.setText("已断开");
        lastStatePayload = null;
        playerBoardContainer.getChildren().clear();
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
            showError("请先在连接区填写 playerId 并认证。");
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
                showError("服务器未返回选项列表。");
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
            dlg.setTitle("选择出牌参数");
            dlg.setHeaderText("从服务器给出的合法参数中选一项；取消则不出牌。");
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
            showError("请先点选手牌或在高级里填写 cardId。");
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
            showError("请先点选手牌或在高级里填写 cardId。");
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
                summaryLabel.setText("认证成功。可点击「认证并开局」。");
                hideError();
            } else {
                showError(jsonString(p, "error", "认证失败"));
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

            StringBuilder line = new StringBuilder();
            line.append("阶段 ").append(phase);
            line.append(" ｜ 当前回合 ").append(current);
            line.append(" ｜ 回合 ").append(turnPhase);
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
        rentPaymentHint.setText("你需支付租金 " + due
                + "M。勾选银行/财产中的牌（合计须≥应付，多付不退）；也可不勾选并点「自动选牌」。");
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
            showError("请至少勾选一张牌，或使用「自动选牌」。");
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
            turnGuideLabel.setText("请先连接服务器。");
            turnGuideLabel.getStyleClass().setAll("turn-guide");
            return;
        }
        if (lastStatePayload == null) {
            turnGuideLabel.setText("已连接。点击「① 认证并开局」开始游戏。");
            turnGuideLabel.getStyleClass().setAll("turn-guide");
            return;
        }
        String local = playerIdField.getText().trim();
        String current = jsonString(lastStatePayload, "currentPlayerId", "");
        String tp = jsonString(lastStatePayload, "turnPhase", "");
        if (local.equals(current)) {
            turnGuideLabel.getStyleClass().setAll("turn-guide");
            if ("DRAW".equals(tp)) {
                turnGuideLabel.setText("轮到你：请先点「摸 2 张牌」。");
            } else if ("PLAY".equals(tp)) {
                turnGuideLabel.setText("轮到你：点选手牌，再选「存入银行 / 部署房产 / 打出行动牌 / 弃牌」。");
            } else if ("WAITING_FOR_RESPONSE".equals(tp)) {
                turnGuideLabel.setText(
                        "轮到你响应：可打免租牌；若放弃免租，用上方「租金支付」勾选要交的牌（或自动选牌）。");
            } else {
                turnGuideLabel.setText("轮到你（阶段 " + tp + "）。可结束回合或按摘要操作。");
            }
        } else {
            turnGuideLabel.getStyleClass().setAll("turn-guide", "turn-guide-wait");
            turnGuideLabel.setText("当前回合：" + current + "。你：" + local + " — 请稍候。");
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
                selectedCardLabel.setText("未收到手牌");
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
            selectedCardLabel.setText("手牌解析失败");
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
