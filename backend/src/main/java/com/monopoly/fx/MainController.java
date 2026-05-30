package com.monopoly.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.fx.presentation.CardDisplayData;
import com.monopoly.fx.ui.CardView;
import com.monopoly.fx.ui.PlayerBoardPanel;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * JavaFX table client rebuilt around the same state model as the web client.
 * Backend messages and game logic stay unchanged; this class only translates
 * snapshots into a cleaner desktop table.
 */
public class MainController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_TRAFFIC_LINES = 120;
    private final FxWebSocketClient ws = new FxWebSocketClient();
    private final ToggleGroup handToggleGroup = new ToggleGroup();
    private final Map<String, JsonObject> roomRowsByLabel = new LinkedHashMap<>();
    private final Set<String> selectedPaymentIds = new HashSet<>();

    private JsonObject lastStatePayload;
    private JsonArray latestHandCards = new JsonArray();
    private String latestHandSignature = "";
    private CardDisplayData selectedCard;
    private Consumer<JsonObject> pendingOptionsResultHandler;
    private Runnable postConnectAction;
    private PauseTransition connectionTimeout;
    private Timeline responseCountdownTimer;
    private JsonObject currentLobbyRoom;
    private String infoPanel = "intro";
    private int pendingRentPaymentM;
    private String lastAutoDrawKey = "";
    private String playedSessionKey = "";
    private String playedTurnKey = "";
    private long lastRecordedPlayedSequence;
    private int trafficLineCount;
    private boolean awaitingInitialState;
    private final List<PlayedEvent> playedEvents = new ArrayList<>();

    @FXML private StackPane root;
    @FXML private BorderPane gamePane;
    @FXML private ScrollPane startPane;

    @FXML private TextField wsUrlField;
    @FXML private ComboBox<String> languageCombo;
    @FXML private Button connectButton;
    @FXML private Label statusLabel;
    @FXML private Label connectionLabel;
    @FXML private Button disconnectButton;

    @FXML private Label brandKickerLabel;
    @FXML private Label appTitleLabel;
    @FXML private Label appSubtitleLabel;
    @FXML private Label serverLabel;
    @FXML private Label languageLabel;
    @FXML private Label gameModeLabel;
    @FXML private Label playerCountLabel;
    @FXML private Label aiDifficultyLabel;
    @FXML private Label customLineupLabel;
    @FXML private Label playerIdLabel;
    @FXML private Label sessionIdLabel;
    @FXML private ComboBox<String> gameModeCombo;
    @FXML private Spinner<Integer> playerCountSpinner;
    @FXML private ComboBox<String> aiDifficultyCombo;
    @FXML private VBox aiDifficultyBox;
    @FXML private HBox customLineupBox;
    @FXML private ComboBox<String> customLineupCombo;
    @FXML private TextField playerIdField;
    @FXML private TextField sessionIdField;
    @FXML private CheckBox randomizeFirstCheck;
    @FXML private Label modeHintLabel;
    @FXML private Button startGameButton;
    @FXML private Label setupErrorLabel;

    @FXML private Button infoIntroButton;
    @FXML private Button infoRulesButton;
    @FXML private Button infoGuideButton;
    @FXML private Label infoTitleLabel;
    @FXML private Label infoLine1Label;
    @FXML private Label infoLine2Label;
    @FXML private Label infoLine3Label;

    @FXML private Label roomListLabel;
    @FXML private ListView<String> roomListView;
    @FXML private Label nicknameLabel;
    @FXML private TextField nicknameField;
    @FXML private Button createRoomButton;
    @FXML private Button joinRoomButton;
    @FXML private Button refreshRoomsButton;
    @FXML private Button topRefreshRoomsButton;
    @FXML private Label waitingRoomLabel;
    @FXML private Label roomStatusLabel;
    @FXML private Button startRoomButton;
    @FXML private Button leaveRoomButton;
    @FXML private FlowPane roomMembersPane;
    @FXML private GridPane roomSeatsGrid;

    @FXML private Label tableStatusLabel;
    @FXML private Label eventLineLabel;
    @FXML private Label roundLabel;
    @FXML private Label phaseLabel;
    @FXML private Label actionCountLabel;
    @FXML private Label drawPileLabel;
    @FXML private Label discardPileLabel;
    @FXML private Label gameErrorLabel;
    @FXML private Label myBoardTitleLabel;
    @FXML private VBox centerNoticeBox;
    @FXML private Label centerTitleLabel;
    @FXML private Label centerSubtitleLabel;
    @FXML private Label drawPileNameLabel;
    @FXML private Label discardPileNameLabel;
    @FXML private HBox opponentsBox;
    @FXML private VBox myBoardBox;
    @FXML private FlowPane playedCardsPane;

    @FXML private VBox responseBox;
    @FXML private Label responseTitleLabel;
    @FXML private Label responseCountdownLabel;
    @FXML private Label responseContextLabel;
    @FXML private Button responseJsnButton;
    @FXML private Button responsePassButton;

    @FXML private VBox rentPaymentBox;
    @FXML private Label rentPaymentHint;
    @FXML private Label rentPaymentSumLabel;
    @FXML private FlowPane rentPaymentPickPane;
    @FXML private Button rentPaymentGreedyButton;
    @FXML private Button rentPaymentSubmitButton;
    @FXML private Button rentPaymentClearButton;

    @FXML private Label actionPadTitleLabel;
    @FXML private Label selectedCardLabel;
    @FXML private StackPane selectedPreviewPane;
    @FXML private Button drawButton;
    @FXML private Button endTurnButton;
    @FXML private Button depositButton;
    @FXML private Button deployButton;
    @FXML private Button actionButton;
    @FXML private Button discardButton;
    @FXML private Label handTitleLabel;
    @FXML private Label handHintLabel;
    @FXML private HBox handStrip;
    @FXML private HBox lowerTableBox;

    @FXML private TextArea trafficArea;

    @FXML
    private void initialize() {
        wsUrlField.setText("ws://localhost:8025/ws");
        sessionIdField.setText("web-demo");
        playerIdField.setText("human-1");
        nicknameField.setText("玩家");

        playerCountSpinner.setValueFactory(new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(2, 5, 2));
        gameModeCombo.getItems().setAll("HVM", "PVP", "LLM", "CUSTOM");
        gameModeCombo.getSelectionModel().select("HVM");
        aiDifficultyCombo.getItems().setAll("EASY", "NORMAL", "HARD", "STRONG");
        aiDifficultyCombo.getSelectionModel().select("NORMAL");
        customLineupCombo.getItems().setAll(
                "human,strong",
                "human,human,lookahead,lookahead",
                "human,human,hard,lookahead",
                "human,human,llm,llm",
                "hard,hard,lookahead,lookahead"
        );
        customLineupCombo.setEditable(true);
        customLineupCombo.getSelectionModel().selectFirst();

        languageCombo.getItems().setAll("中文", "English");
        languageCombo.getSelectionModel().selectFirst();
        languageCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            I18n.setLocale("English".equals(newValue) ? Locale.ENGLISH : Locale.CHINESE);
            applyI18n();
            rebuildAllFromState();
        });
        gameModeCombo.valueProperty().addListener((obs, oldValue, newValue) -> syncModeUi());
        customLineupCombo.valueProperty().addListener((obs, oldValue, newValue) -> syncCustomLineupCount());
        if (customLineupCombo.getEditor() != null) {
            customLineupCombo.getEditor().textProperty().addListener((obs, oldValue, newValue) -> syncCustomLineupCount());
        }
        roomListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, label) -> {
            JsonObject row = roomRowsByLabel.get(label);
            if (row != null) {
                sessionIdField.setText(jsonString(row, "sessionId", sessionIdField.getText()));
            }
        });
        handToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle instanceof CardView cardView) {
                selectedCard = cardView.getCardData();
                selectedCardLabel.setText(i18n("label.selected", selectedCard.getTitle()));
            } else {
                selectedCard = null;
                selectedCardLabel.setText(i18n("label.selectCardHint"));
            }
            updateSelectedPreview();
            syncActionButtons();
        });

        trafficArea.setEditable(false);
        randomizeFirstCheck.setSelected(false);
        syncModeUi();
        switchToStartView();
        applyI18n();
        refreshButtons();
        updateLobbyControls();
        syncActionButtons();
        updateSelectedPreview();
    }

    @FXML
    private void onInfoIntro() {
        infoPanel = "intro";
        updateInfoPanel();
    }

    @FXML
    private void onInfoRules() {
        infoPanel = "rules";
        updateInfoPanel();
    }

    @FXML
    private void onInfoGuide() {
        infoPanel = "guide";
        updateInfoPanel();
    }

    @FXML
    private void onConnect() {
        clearError();
        statusLabel.setText(i18n("status.connecting"));
        connectionLabel.setText(i18n("status.connecting"));
        cancelConnectionTimeout();
        ws.connect(wsUrlField.getText().trim(), new FxWebSocketClient.Listener() {
            @Override
            public void onOpen() {
                Platform.runLater(() -> {
                    cancelConnectionTimeout();
                    statusLabel.setText(i18n("status.connected"));
                    connectionLabel.setText(i18n("status.connected"));
                    appendTraffic("« " + i18n("log.wsOpened") + " »");
                    refreshButtons();
                    if (postConnectAction != null) {
                        Runnable action = postConnectAction;
                        postConnectAction = null;
                        action.run();
                    } else {
                        onRefreshRooms();
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
                    cancelConnectionTimeout();
                    postConnectAction = null;
                    statusLabel.setText(i18n("status.connectFailed", error.getMessage()));
                    connectionLabel.setText(i18n("status.disconnected"));
                    showError(i18n("error.connectHint", wsUrlField.getText().trim()));
                    appendTraffic("« " + i18n("log.error") + " » " + error);
                    refreshButtons();
                });
            }

            @Override
            public void onClose(int code, String reason) {
                Platform.runLater(() -> {
                    cancelConnectionTimeout();
                    statusLabel.setText(i18n("status.closed", code));
                    connectionLabel.setText(i18n("status.disconnected"));
                    appendTraffic("« " + i18n("log.closed", code, reason) + " »");
                    lastStatePayload = null;
                    clearPlayedEvents();
                    pendingOptionsResultHandler = null;
                    postConnectAction = null;
                    awaitingInitialState = false;
                    currentLobbyRoom = null;
                    switchToStartView();
                    refreshButtons();
                    updateLobbyControls();
                });
            }
        });
        connectionTimeout = new PauseTransition(Duration.seconds(8));
        connectionTimeout.setOnFinished(e -> {
            if (!ws.isConnected()) {
                postConnectAction = null;
                statusLabel.setText(i18n("status.timeout"));
                connectionLabel.setText(i18n("status.disconnected"));
                showError(i18n("error.connectHint", wsUrlField.getText().trim()));
                refreshButtons();
            }
        });
        connectionTimeout.play();
    }

    @FXML
    private void onDisconnect() {
        stopResponseCountdown();
        ws.closeQuietly();
        statusLabel.setText(i18n("status.disconnected"));
        connectionLabel.setText(i18n("status.disconnected"));
        lastStatePayload = null;
        latestHandCards = new JsonArray();
        latestHandSignature = "";
        clearPlayedEvents();
        currentLobbyRoom = null;
        selectedCard = null;
        pendingOptionsResultHandler = null;
        awaitingInitialState = false;
        roomRowsByLabel.clear();
        roomListView.getItems().clear();
        switchToStartView();
        refreshButtons();
        updateLobbyControls();
    }

    @FXML
    private void onStartGame() {
        runWhenConnected(() -> {
            awaitingInitialState = true;
            setupErrorLabel.setVisible(true);
            setupErrorLabel.setManaged(true);
            setupErrorLabel.setText(i18n("msg.startingSession"));
            onAuth();
            onStartSession();
        });
    }

    @FXML
    private void onRefreshRooms() {
        runWhenConnected(() -> sendEnvelope("ROOM_LIST", scopedPayload()));
    }

    @FXML
    private void onCreateRoom() {
        runWhenConnected(() -> {
            Map<String, Object> payload = scopedPayload();
            payload.put("nickname", nickname());
            sendEnvelope("CREATE_ROOM", payload);
        });
    }

    @FXML
    private void onJoinRoom() {
        String selected = roomListView.getSelectionModel().getSelectedItem();
        JsonObject row = roomRowsByLabel.get(selected);
        if (row != null) {
            sessionIdField.setText(jsonString(row, "sessionId", sessionIdField.getText()));
        }
        runWhenConnected(() -> {
            Map<String, Object> payload = scopedPayload();
            payload.put("nickname", nickname());
            sendEnvelope("JOIN_ROOM", payload);
        });
    }

    @FXML
    private void onStartRoom() {
        Map<String, Object> payload = scopedPayload();
        payload.put("randomizeFirstPlayer", randomizeFirstCheck.isSelected());
        sendEnvelope("START_ROOM", payload);
    }

    @FXML
    private void onLeaveRoom() {
        sendEnvelope("LEAVE_ROOM", scopedPayload());
        currentLobbyRoom = null;
        rebuildRoomState(null);
        updateLobbyControls();
    }

    @FXML
    private void onBackToSetup() {
        switchToStartView();
    }

    @FXML
    private void onDraw() {
        sendEnvelope("DRAW", Map.of("count", 2));
    }

    @FXML
    private void onEndTurn() {
        if (needsOverflowDiscard()) {
            showError(i18n("needDiscard", jsonInt(lastStatePayload, "overflowDiscardCount", 0)));
            return;
        }
        sendEnvelope("END_TURN", Map.of());
    }

    @FXML
    private void onDeposit() {
        playSelected("DEPOSIT");
    }

    @FXML
    private void onDeploy() {
        playSelected("DEPLOY");
    }

    @FXML
    private void onAction() {
        playSelected("ACTION");
    }

    @FXML
    private void onDiscard() {
        playSelected("DISCARD");
    }

    @FXML
    private void onResponsePass() {
        sendEnvelope("PLAY", WsJson.playResponsePass(playerId(), null));
    }

    @FXML
    private void onPlayJustSayNo() {
        JsonObject card = findJustSayNoCard();
        if (card == null) {
            showError(i18n("error.noJsn"));
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionType", "ACTION");
        payload.put("actingPlayerId", playerId());
        payload.put("cardId", jsonString(card, "id", ""));
        sendEnvelope("PLAY", payload);
    }

    @FXML
    private void onRentPaymentGreedy() {
        List<String> recommended = recommendedPaymentIds();
        selectedPaymentIds.clear();
        selectedPaymentIds.addAll(recommended);
        for (Node node : rentPaymentPickPane.getChildren()) {
            if (node instanceof CheckBox cb) {
                Object id = cb.getUserData();
                cb.setSelected(id instanceof String s && selectedPaymentIds.contains(s));
            }
        }
        if (!recommended.isEmpty()) {
            refreshRentPaymentSumLabel();
            sendEnvelope("PLAY", WsJson.playResponsePass(playerId(), recommended));
        } else {
            sendEnvelope("PLAY", WsJson.playResponsePass(playerId(), null));
        }
    }

    @FXML
    private void onRentPaymentSubmit() {
        if (selectedPaymentIds.isEmpty()) {
            showError(i18n("error.selectAtLeast"));
            return;
        }
        int selected = computeSelectedPaymentSum();
        int total = totalPayableValue();
        if (selected < pendingRentPaymentM && selected < total) {
            showError(i18n("error.insufficientValue", pendingRentPaymentM));
            return;
        }
        sendEnvelope("PLAY", WsJson.playResponsePass(playerId(), new ArrayList<>(selectedPaymentIds)));
    }

    @FXML
    private void onRentPaymentClear() {
        selectedPaymentIds.clear();
        for (Node node : rentPaymentPickPane.getChildren()) {
            if (node instanceof CheckBox cb) {
                cb.setSelected(false);
            }
        }
        refreshRentPaymentSumLabel();
    }

    void shutdown() {
        stopResponseCountdown();
        ws.closeQuietly();
    }

    private void onAuth() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerId", playerId());
        sendEnvelope("AUTH", payload);
    }

    private void onStartSession() {
        Map<String, Object> payload = new LinkedHashMap<>();
        String mode = gameModeCombo.getSelectionModel().getSelectedItem();
        List<String> customRoles = customLineupRoles();
        payload.put("sessionId", sessionId());
        payload.put("playerCount", "CUSTOM".equals(mode) && !customRoles.isEmpty()
                ? customRoles.size()
                : playerCountSpinner.getValue());
        payload.put("gameMode", mode);
        payload.put("randomizeFirstPlayer", randomizeFirstCheck.isSelected());
        if ("HVM".equals(mode)) {
            payload.put("aiDifficulty", aiDifficultyCombo.getSelectionModel().getSelectedItem());
        }
        if ("CUSTOM".equals(mode) && !customRoles.isEmpty()) {
            payload.put("customLineup", String.join(",", customRoles));
            payload.put("playerRoles", customRoles);
        }
        sendEnvelope("START_SESSION", payload);
    }

    private void playSelected(String actionType) {
        if (selectedCard == null) {
            showError(i18n("selectCardFirst"));
            return;
        }
        if (needsOverflowDiscard() && !"DISCARD".equals(actionType)) {
            showError(i18n("needDiscard", jsonInt(lastStatePayload, "overflowDiscardCount", 0)));
            return;
        }
        Map<String, Object> direct = directPlayPayload(selectedCard, actionType);
        if (direct != null) {
            sendEnvelope("PLAY", direct);
            return;
        }
        requestPlayOptionsThenPlay(selectedCard.getId(), actionType, shouldAutoChooseOption(selectedCard, actionType));
    }

    private void quickPlay(CardDisplayData card) {
        if (card == null) {
            return;
        }
        selectedCard = card;
        String actionType = defaultActionForCard(card);
        if (needsOverflowDiscard() && !"DISCARD".equals(actionType)) {
            showError(i18n("needDiscard", jsonInt(lastStatePayload, "overflowDiscardCount", 0)));
            return;
        }
        Map<String, Object> direct = directPlayPayload(card, actionType);
        if (direct != null) {
            sendEnvelope("PLAY", direct);
            return;
        }
        requestPlayOptionsThenPlay(card.getId(), actionType, shouldAutoChooseOption(card, actionType));
    }

    private Map<String, Object> directPlayPayload(CardDisplayData card, String actionType) {
        if (card == null || card.getId().isBlank()) {
            return null;
        }
        if ("DEPOSIT".equals(actionType) || "DISCARD".equals(actionType)) {
            return WsJson.playPayload(actionType, card.getId(), null, null, null, null, null, null);
        }
        if ("DEPLOY".equals(actionType) && "PROPERTY".equals(card.getKind())) {
            return WsJson.playPayload(actionType, card.getId(), null, null, null, null, null, null);
        }
        return null;
    }

    private boolean shouldAutoChooseOption(CardDisplayData card, String actionType) {
        if ("DEPLOY".equals(actionType) && "WILD".equals(card.getKind())) {
            return false;
        }
        if (!"ACTION".equals(actionType)) {
            return true;
        }
        String effect = safeUpper(card.getEffectCode());
        return !"RENT".equals(effect)
                && !"RENT_DUAL".equals(effect)
                && !"DEBT_COLLECTOR".equals(effect)
                && !"STEAL_PROPERTY".equals(effect)
                && !"FORCED_DEAL".equals(effect)
                && !"DEAL_BREAKER".equals(effect);
    }

    private void requestPlayOptionsThenPlay(String cardId, String actionType, boolean autoDefault) {
        if (pendingOptionsResultHandler != null) {
            showError(i18n("error.waitOption"));
            return;
        }
        pendingOptionsResultHandler = payload -> handlePlayOptions(payload, cardId, actionType, autoDefault);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("playerId", playerId());
        request.put("cardId", cardId);
        request.put("actionType", actionType);
        sendEnvelope("PLAY_OPTIONS", request);
    }

    private void handlePlayOptions(JsonObject payload, String cardId, String actionType, boolean autoDefault) {
        if (payload == null || !jsonBool(payload, "ok", false)) {
            showError(jsonString(payload, "error", i18n("error.noOptions")));
            return;
        }
        JsonArray options = payload.has("options") && payload.get("options").isJsonArray()
                ? payload.getAsJsonArray("options")
                : new JsonArray();
        boolean mustChoose = mustChooseOption(selectedCard, actionType, options);
        if (!mustChoose && (autoDefault || options.size() <= 1)) {
            JsonObject row = options.size() == 0 ? new JsonObject() : options.get(0).getAsJsonObject();
            sendPlayFromOptionRow(actionType, cardId, row);
            return;
        }
        showOptionDialog(actionType, cardId, options);
    }

    private boolean mustChooseOption(CardDisplayData card, String actionType, JsonArray options) {
        if ("DEPLOY".equals(actionType) && card != null && "WILD".equals(card.getKind())) {
            return true;
        }
        if (!"ACTION".equals(actionType) || card == null) {
            return false;
        }
        String effect = safeUpper(card.getEffectCode());
        if ("RENT".equals(effect) || "RENT_DUAL".equals(effect)) {
            return true;
        }
        return ("DEBT_COLLECTOR".equals(effect)
                || "STEAL_PROPERTY".equals(effect)
                || "FORCED_DEAL".equals(effect)
                || "DEAL_BREAKER".equals(effect))
                && options != null && options.size() > 1;
    }

    private void showOptionDialog(String actionType, String cardId, JsonArray options) {
        if (options == null || options.size() == 0) {
            sendPlay(cardId, actionType, null, null, null, null, null);
            return;
        }
        javafx.scene.control.Dialog<JsonObject> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle(i18n("dialog.chooseParam"));
        dialog.setHeaderText(i18n("dialog.chooseHint"));
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.CANCEL,
                javafx.scene.control.ButtonType.OK
        );
        ListView<JsonObject> list = new ListView<>();
        list.setPrefSize(520, Math.min(360, Math.max(160, options.size() * 42)));
        for (JsonElement el : options) {
            if (el.isJsonObject()) {
                list.getItems().add(el.getAsJsonObject());
            }
        }
        list.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(JsonObject item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : optionLabel(item, actionType));
            }
        });
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().selectFirst();
        }
        dialog.getDialogPane().setContent(list);
        dialog.setResultConverter(button -> button == javafx.scene.control.ButtonType.OK
                ? list.getSelectionModel().getSelectedItem()
                : null);
        if (root != null && root.getScene() != null) {
            dialog.initOwner(root.getScene().getWindow());
        }
        dialog.showAndWait().ifPresent(row -> sendPlayFromOptionRow(actionType, cardId, row));
    }

    private String optionLabel(JsonObject row, String actionType) {
        if ("DEPLOY".equals(actionType)) {
            String color = jsonString(row, "targetColorKey", "");
            if (!color.isBlank()) {
                return i18n("deployAsColor", colorName(color));
            }
        }
        String label = jsonString(row, I18n.isChinese() ? "labelZh" : "labelEn", "");
        if (!label.isBlank()) {
            return label;
        }
        String targetPlayer = jsonString(row, "targetPlayerId", "");
        String targetColor = jsonString(row, "targetColorKey", "");
        String targetCard = jsonString(row, "targetCardId", "");
        List<String> pieces = new ArrayList<>();
        if (!targetPlayer.isBlank()) {
            pieces.add(displayNameForPlayer(targetPlayer));
        }
        if (!targetColor.isBlank()) {
            pieces.add(colorName(targetColor));
        }
        if (!targetCard.isBlank()) {
            pieces.add(targetCard);
        }
        return pieces.isEmpty() ? i18n("directPlay") : String.join(" · ", pieces);
    }

    private void sendPlayFromOptionRow(String actionType, String cardId, JsonObject row) {
        boolean allOthers = row != null && jsonBool(row, "allOtherPlayers", false);
        sendPlay(
                cardId,
                actionType,
                allOthers ? null : blankToNull(jsonString(row, "targetPlayerId", "")),
                blankToNull(jsonString(row, "targetColorKey", "")),
                blankToNull(jsonString(row, "targetCardId", "")),
                blankToNull(jsonString(row, "actorCardId", "")),
                blankToNull(jsonString(row, "targetZone", ""))
        );
    }

    private void sendPlay(
            String cardId,
            String actionType,
            String targetPlayerId,
            String targetColorKey,
            String targetCardId,
            String actorCardId,
            String targetZone) {
        sendEnvelope("PLAY", WsJson.playPayload(
                actionType,
                cardId,
                targetPlayerId,
                targetColorKey,
                targetCardId,
                actorCardId,
                targetZone,
                null));
    }

    private void handleInbound(String raw) {
        String type = WsJson.typeOf(raw);
        appendTraffic("→ " + trafficSummary(type, raw));
        try {
            switch (type) {
                case "AUTH_RESULT" -> applyAuthResult(raw);
                case "STATE_UPDATE" -> applyStateUpdate(raw);
                case "MY_HAND" -> applyMyHand(raw);
                case "ROOM_LIST_RESULT" -> applyRoomListResult(raw);
                case "ROOM_STATE" -> applyRoomState(raw);
                case "ROOM_ERROR" -> applyRoomError(raw);
                case "PLAY_OPTIONS_RESULT", "ACTION_OPTIONS_RESULT" -> applyPendingOptionsResult(raw);
                case "ERROR" -> applyInboundError(raw);
                default -> {
                }
            }
        } catch (RuntimeException ex) {
            showError(i18n("msg.stateParseError", ex.getMessage()));
        }
    }

    private void applyAuthResult(String raw) {
        JsonObject payload = payload(raw);
        if (jsonBool(payload, "ok", false)) {
            clearError();
            eventLineLabel.setText(i18n("msg.authSuccess"));
        } else {
            showError(jsonString(payload, "error", i18n("error.authFailed")));
        }
    }

    private void applyStateUpdate(String raw) {
        lastStatePayload = payload(raw);
        awaitingInitialState = false;
        updatePlayedEvents(lastStatePayload);
        switchToGameView();
        clearError();
        rebuildAllFromState();
        maybeAutoDraw();
    }

    private void applyMyHand(String raw) {
        JsonObject payload = payload(raw);
        JsonArray cards = payload.has("cards") && payload.get("cards").isJsonArray()
                ? payload.getAsJsonArray("cards")
                : new JsonArray();
        String signature = handSignature(cards);
        latestHandCards = cards;
        if (!signature.equals(latestHandSignature)) {
            latestHandSignature = signature;
            rebuildHand();
        }
        updateResponsePanel();
        updateRentPaymentPanel();
    }

    private void applyRoomListResult(String raw) {
        JsonObject payload = payload(raw);
        roomRowsByLabel.clear();
        roomListView.getItems().clear();
        if (payload.has("rooms") && payload.get("rooms").isJsonArray()) {
            for (JsonElement el : payload.getAsJsonArray("rooms")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject room = el.getAsJsonObject();
                String label = roomListLabel(room);
                roomRowsByLabel.put(label, room);
                roomListView.getItems().add(label);
            }
        }
        if (!roomListView.getItems().isEmpty()) {
            roomListView.getSelectionModel().selectFirst();
        }
    }

    private void applyRoomState(String raw) {
        currentLobbyRoom = payload(raw);
        String sid = jsonString(currentLobbyRoom, "sessionId", "");
        if (!sid.isBlank()) {
            sessionIdField.setText(sid);
        }
        JsonObject mySeat = findMyLobbySeat(currentLobbyRoom);
        if (mySeat != null) {
            String id = jsonString(mySeat, "playerId", "");
            if (!id.isBlank()) {
                playerIdField.setText(id);
            }
        }
        rebuildRoomState(currentLobbyRoom);
        updateLobbyControls();
        if (jsonBool(currentLobbyRoom, "started", false)) {
            switchToGameView();
        }
    }

    private void applyRoomError(String raw) {
        showError(jsonString(payload(raw), "error", i18n("lobby.roomError")));
    }

    private void applyPendingOptionsResult(String raw) {
        Consumer<JsonObject> handler = pendingOptionsResultHandler;
        pendingOptionsResultHandler = null;
        if (handler != null) {
            handler.accept(payload(raw));
        }
    }

    private void applyInboundError(String raw) {
        pendingOptionsResultHandler = null;
        awaitingInitialState = false;
        JsonObject payload = payload(raw);
        showError(jsonString(payload, "message", jsonString(payload, "error", i18n("log.error"))));
    }

    private void rebuildAllFromState() {
        updateStatusTexts();
        rebuildPlayerBoards();
        updateResponsePanel();
        updateRentPaymentPanel();
        updateResponsiveTableLayout();
        syncActionButtons();
    }

    private void updateStatusTexts() {
        JsonObject p = lastStatePayload;
        if (p == null) {
            tableStatusLabel.setText(i18n("ready"));
            eventLineLabel.setText(i18n("brandIntro"));
            updateCenterNotice(false, i18n("ready"), i18n("brandIntro"), null);
            return;
        }
        int round = jsonInt(p, "roundNumber", 1);
        String phase = jsonString(p, "turnPhase", "-");
        int used = jsonInt(p, "actionsUsedThisTurn", 0);
        int left = jsonInt(p, "actionsRemainingThisTurn", Math.max(0, 3 - used));
        roundLabel.setText(i18n("round", round));
        phaseLabel.setText(i18n("phase", phase));
        actionCountLabel.setText(i18n("actions", used, left));
        drawPileLabel.setText(i18n("drawPile", jsonInt(p, "drawPileCount", 0)));
        discardPileLabel.setText(i18n("discardPile", jsonInt(p, "discardPileCount", 0)));
        drawPileNameLabel.setText(i18n("drawPileName"));
        discardPileNameLabel.setText(i18n("discardPileName"));

        String current = jsonString(p, "currentPlayerId", "");
        String decision = jsonString(p, "decisionPlayerId", current);
        boolean over = jsonBool(p, "gameOver", false);
        if (over) {
            tableStatusLabel.setText(i18n("gameOver"));
            String summary = jsonString(p, "lastActionSummary", i18n("gameOver"));
            updateCenterNotice(true, i18n("gameOver"), summary, "game-over");
        } else if (playerId().equals(decision)) {
            tableStatusLabel.setText(jsonString(p, "decisionLabel", i18n("yourDecision")));
            updateCenterNotice(true, jsonString(p, "decisionLabel", i18n("yourDecision")), centerNoticeText(p), "decision");
        } else if (!decision.isBlank()) {
            String name = displayNameForPlayer(decision);
            tableStatusLabel.setText(i18n("waitingFor", name));
            updateCenterNotice(true, i18n("waitingFor", name), centerNoticeText(p), "waiting");
        } else if (playerId().equals(current)) {
            tableStatusLabel.setText(i18n("yourTurn"));
            updateCenterNotice(true, i18n("yourTurn"), centerNoticeText(p), "decision");
        } else {
            tableStatusLabel.setText(i18n("ready"));
            updateCenterNotice(!centerNoticeText(p).isBlank(), i18n("recentAction"), centerNoticeText(p), "recent");
        }

        String line = waitingResponseText();
        if (line.isBlank()) {
            line = jsonString(p, "lastActionSummary", i18n("brandIntro"));
        }
        eventLineLabel.setText(line);
        connectionLabel.setText(ws.isConnected() ? i18n("status.connected") : i18n("status.disconnected"));
    }

    private void updateCenterNotice(boolean show, String title, String body, String styleClass) {
        if (centerNoticeBox == null) {
            return;
        }
        centerNoticeBox.setVisible(show);
        centerNoticeBox.setManaged(show);
        centerNoticeBox.getStyleClass().removeAll("game-over", "decision", "waiting", "recent");
        if (styleClass != null && !styleClass.isBlank()) {
            centerNoticeBox.getStyleClass().add(styleClass);
        }
        centerTitleLabel.setText(title == null ? "" : title);
        centerSubtitleLabel.setText(body == null ? "" : body);
        centerTitleLabel.setMaxWidth(720);
        centerSubtitleLabel.setMaxWidth(720);
    }

    private String centerNoticeText(JsonObject p) {
        if (p == null) {
            return "";
        }
        String waiting = waitingResponseText();
        if (!waiting.isBlank()) {
            return responseSummaryText(p, jsonInt(p, "pendingPaymentAmountM", 0));
        }
        if (p.has("lastPlayedCard") && p.get("lastPlayedCard").isJsonObject()) {
            String actor = displayNameForPlayer(jsonString(p, "lastPlayedPlayerId", ""));
            CardDisplayData card = CardDisplayData.fromHandCardJson(p.getAsJsonObject("lastPlayedCard"));
            String action = playActionLabel(jsonString(p, "lastPlayedActionType", ""));
            return i18n("notice.playedAction", actor, cardTitle(card), action,
                    jsonString(p, "lastActionSummary", ""));
        }
        return jsonString(p, "lastActionSummary", "");
    }

    private void updateResponsiveTableLayout() {
        if (lowerTableBox == null) {
            return;
        }
        boolean focusedResponse = lastStatePayload != null
                && "WAITING_FOR_RESPONSE".equals(jsonString(lastStatePayload, "turnPhase", ""))
                && playerId().equals(jsonString(lastStatePayload, "pendingResponsePlayerId", ""));
        lowerTableBox.setVisible(!focusedResponse);
        lowerTableBox.setManaged(!focusedResponse);
    }

    private String responseSummaryText(JsonObject state, int due) {
        JsonObject ctx = state != null
                && state.has("pendingResponseContext")
                && state.get("pendingResponseContext").isJsonObject()
                ? state.getAsJsonObject("pendingResponseContext")
                : null;
        if (ctx == null) {
            return due > 0 ? i18n("notice.payPrompt", due) : i18n("response.defaultBody");
        }
        String actor = responsePlayerName(jsonString(ctx, "actorName", ""), jsonString(ctx, "actorPlayerId", ""));
        String target = responsePlayerName(jsonString(ctx, "targetName", ""), jsonString(ctx, "targetPlayerId", ""));
        String action = responseActionTitle(ctx, "");
        int amount = jsonInt(ctx, "amountDueM", due);
        String line = i18n("notice.actionTarget",
                actor.isBlank() ? i18n("player.fallback") : actor,
                target.isBlank() ? i18n("player.fallback") : target,
                action);
        if (amount > 0) {
            line += "\n" + i18n("notice.payPrompt", amount);
        }
        String color = jsonString(ctx, "colorKey", "");
        if (!color.isBlank()) {
            line += " · " + colorName(color);
        }
        return line;
    }

    private String waitingResponseText() {
        JsonObject p = lastStatePayload;
        if (p == null || !"WAITING_FOR_RESPONSE".equals(jsonString(p, "turnPhase", ""))) {
            return "";
        }
        String pending = jsonString(p, "pendingResponsePlayerId", "");
        if (pending.isBlank()) {
            return "";
        }
        long deadline = jsonLong(p, "responseDeadlineEpochMs", 0L);
        String suffix = deadline > 0 ? " · " + Math.max(0L, (deadline - System.currentTimeMillis() + 999L) / 1000L) + "s" : "";
        return playerId().equals(pending)
                ? i18n("waitingYouResponse", suffix)
                : i18n("waitingResponse", displayNameForPlayer(pending), suffix);
    }

    private void rebuildPlayerBoards() {
        opponentsBox.getChildren().clear();
        myBoardBox.getChildren().clear();
        playedCardsPane.getChildren().clear();
        JsonObject p = lastStatePayload;
        if (p == null || !p.has("players") || !p.get("players").isJsonArray()) {
            showEmptyTableState();
            return;
        }
        if (p.getAsJsonArray("players").isEmpty()) {
            showEmptyTableState();
            return;
        }
        String current = jsonString(p, "currentPlayerId", "");
        String local = playerId();
        JsonObject localPlayer = null;
        for (JsonElement el : p.getAsJsonArray("players")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject player = el.getAsJsonObject();
            String pid = jsonString(player, "playerId", "");
            if (pid.equals(local)) {
                player.addProperty("_layout", "LOCAL");
            }
            PlayerBoardPanel panel = new PlayerBoardPanel(player, pid.equals(current));
            if (pid.equals(local)) {
                localPlayer = player;
                myBoardBox.getChildren().add(panel);
            } else {
                panel.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(panel, Priority.ALWAYS);
                opponentsBox.getChildren().add(panel);
            }
        }
        if (localPlayer == null) {
            Label empty = new Label(i18n("board.empty"));
            empty.getStyleClass().add("table-empty");
            myBoardBox.getChildren().add(empty);
        }
        if (opponentsBox.getChildren().isEmpty()) {
            Label empty = new Label(i18n("opponents.empty"));
            empty.getStyleClass().add("table-empty");
            opponentsBox.getChildren().add(empty);
        }
        rebuildPlayedCards();
    }

    private void showEmptyTableState() {
        Label emptyBoard = new Label(i18n("board.waitingState"));
        emptyBoard.getStyleClass().add("table-empty");
        myBoardBox.getChildren().add(emptyBoard);
        Label emptyOpponents = new Label(i18n("board.waitingState"));
        emptyOpponents.getStyleClass().add("table-empty");
        opponentsBox.getChildren().add(emptyOpponents);
        Label emptyPlayed = new Label(i18n("playedEmpty"));
        emptyPlayed.getStyleClass().add("played-empty");
        playedCardsPane.getChildren().add(emptyPlayed);
    }

    private void updatePlayedEvents(JsonObject p) {
        if (p == null) {
            return;
        }
        String phase = jsonString(p, "phase", "");
        String sessionKey = jsonString(p, "sessionId", sessionId());
        if (!sessionKey.equals(playedSessionKey)) {
            clearPlayedEvents();
            playedSessionKey = sessionKey;
        }
        if ("INIT".equals(phase)) {
            clearPlayedEvents();
            playedSessionKey = sessionKey;
            return;
        }
        if (jsonBool(p, "gameOver", false)) {
            playedEvents.clear();
            return;
        }
        if ("TURN_END".equals(phase)) {
            playedEvents.clear();
            playedTurnKey = playedTurnKey(p);
            lastRecordedPlayedSequence = Math.max(lastRecordedPlayedSequence, jsonLong(p, "lastPlayedSequence", 0L));
            return;
        }
        String turnKey = playedTurnKey(p);
        if (!turnKey.equals(playedTurnKey)) {
            playedTurnKey = turnKey;
            playedEvents.clear();
        }
        long sequence = jsonLong(p, "lastPlayedSequence", 0L);
        if (sequence <= 0 || !p.has("lastPlayedCard") || !p.get("lastPlayedCard").isJsonObject()) {
            return;
        }
        if (sequence <= lastRecordedPlayedSequence) {
            return;
        }
        JsonObject cardJson = p.getAsJsonObject("lastPlayedCard").deepCopy();
        String playerId = jsonString(p, "lastPlayedPlayerId", jsonString(p, "currentPlayerId", ""));
        String actionType = jsonString(p, "lastPlayedActionType", "");
        String summary = jsonString(p, "lastActionSummary", "");
        playedEvents.add(new PlayedEvent(sequence, playerId, actionType, cardJson, summary));
        lastRecordedPlayedSequence = sequence;
        if (playedEvents.size() > 6) {
            playedEvents.remove(0);
        }
    }

    private String playedTurnKey(JsonObject p) {
        return jsonString(p, "sessionId", sessionId()) + ":"
                + jsonInt(p, "roundNumber", 1) + ":"
                + jsonString(p, "currentPlayerId", "");
    }

    private void clearPlayedEvents() {
        playedEvents.clear();
        playedSessionKey = "";
        playedTurnKey = "";
        lastRecordedPlayedSequence = 0L;
    }

    private void rebuildPlayedCards() {
        if (playedEvents.isEmpty()) {
            Label empty = new Label(i18n("playedEmpty"));
            empty.getStyleClass().add("played-empty");
            playedCardsPane.getChildren().add(empty);
            return;
        }
        for (PlayedEvent event : playedEvents) {
            CardDisplayData data = CardDisplayData.fromHandCardJson(event.card());
            StackPane node = PlayerBoardPanel.smallCardNode(data, "played-card");
            VBox wrap = new VBox(4);
            wrap.setAlignment(Pos.CENTER);
            Label owner = new Label(displayNameForPlayer(event.playerId()));
            owner.getStyleClass().add("played-owner");
            Label action = new Label(playActionLabel(event.actionType()));
            action.getStyleClass().add("played-action");
            wrap.getChildren().addAll(owner, node, action);
            playedCardsPane.getChildren().add(wrap);
        }
    }

    private void rebuildHand() {
        handStrip.getChildren().clear();
        for (Toggle toggle : new ArrayList<>(handToggleGroup.getToggles())) {
            handToggleGroup.getToggles().remove(toggle);
        }
        selectedCard = null;
        if (latestHandCards == null || latestHandCards.isEmpty()) {
            selectedCardLabel.setText(i18n("emptyHand"));
            updateSelectedPreview();
            syncActionButtons();
            return;
        }
        int index = 0;
        int total = latestHandCards.size();
        for (JsonElement el : latestHandCards) {
            if (!el.isJsonObject()) {
                continue;
            }
            CardDisplayData data = CardDisplayData.fromHandCardJson(el.getAsJsonObject());
            CardView view = new CardView(data, cardKindClass(data));
            view.setToggleGroup(handToggleGroup);
            view.setRotate(Math.max(-8.0, Math.min(8.0, (index - (total - 1) / 2.0) * 1.45)));
            view.setOnMouseClicked(event -> {
                if (event.getClickCount() >= 2) {
                    quickPlay(data);
                }
            });
            handStrip.getChildren().add(view);
            index++;
        }
        selectedCardLabel.setText(i18n("label.selectCardHint"));
        updateSelectedPreview();
        syncActionButtons();
    }

    private static String handSignature(JsonArray cards) {
        if (cards == null || cards.isEmpty()) {
            return "";
        }
        List<String> ids = new ArrayList<>();
        for (JsonElement el : cards) {
            if (el.isJsonObject()) {
                ids.add(jsonString(el.getAsJsonObject(), "id", ""));
            }
        }
        return String.join("|", ids);
    }

    private void updateSelectedPreview() {
        selectedPreviewPane.getChildren().clear();
        if (selectedCard == null) {
            Label placeholder = new Label(i18n("preview.selectCard"));
            placeholder.setWrapText(true);
            placeholder.getStyleClass().add("selected-preview-empty");
            selectedPreviewPane.getChildren().add(placeholder);
            return;
        }
        CardView preview = new CardView(selectedCard, cardKindClass(selectedCard));
        preview.getStyleClass().add("selected-preview-card");
        preview.setMouseTransparent(true);
        selectedPreviewPane.getChildren().add(preview);
    }

    private void updateResponsePanel() {
        JsonObject p = lastStatePayload;
        boolean show = p != null
                && "WAITING_FOR_RESPONSE".equals(jsonString(p, "turnPhase", ""))
                && playerId().equals(jsonString(p, "pendingResponsePlayerId", ""));
        responseBox.setVisible(show);
        responseBox.setManaged(show);
        if (!show) {
            stopResponseCountdown();
            return;
        }
        int due = jsonInt(p, "pendingPaymentAmountM", 0);
        String role = jsonString(p, "pendingResponseRole", "");
        if ("LANDLORD_COUNTER".equals(role)) {
            responseTitleLabel.setText(i18n("response.counterTitle"));
        } else if (due > 0) {
            responseTitleLabel.setText(i18n("response.paymentTitle", due));
        } else {
            responseTitleLabel.setText(i18n("response.targetedTitle"));
        }
        responseContextLabel.setText(responseContextText(p, due));
        responseJsnButton.setDisable(findJustSayNoCard() == null);
        responsePassButton.setText(due > 0 ? i18n("btn.autoPay") : i18n("btn.passResponse"));
        startResponseCountdown(jsonLong(p, "responseDeadlineEpochMs", 0L));
    }

    private void updateRentPaymentPanel() {
        JsonObject p = lastStatePayload;
        boolean show = p != null
                && "WAITING_FOR_RESPONSE".equals(jsonString(p, "turnPhase", ""))
                && playerId().equals(jsonString(p, "pendingResponsePlayerId", ""))
                && "TENANT".equals(jsonString(p, "pendingResponseRole", ""))
                && jsonInt(p, "pendingPaymentAmountM", 0) > 0;
        rentPaymentBox.setVisible(show);
        rentPaymentBox.setManaged(show);
        if (!show) {
            selectedPaymentIds.clear();
            rentPaymentPickPane.getChildren().clear();
            pendingRentPaymentM = 0;
            return;
        }
        pendingRentPaymentM = jsonInt(p, "pendingPaymentAmountM", 0);
        rentPaymentHint.setText(i18n("rent.hint", pendingRentPaymentM));
        selectedPaymentIds.clear();
        rentPaymentPickPane.getChildren().clear();
        JsonObject self = findPlayerInState(p, playerId());
        if (self != null) {
            addRentPaymentChoices(self.getAsJsonArray("bankCards"), i18n("rent.zoneBank"));
            addRentPaymentChoices(self.getAsJsonArray("propertyZoneCards"), i18n("rent.zoneProperty"));
        }
        selectedPaymentIds.addAll(recommendedPaymentIds());
        for (Node node : rentPaymentPickPane.getChildren()) {
            if (node instanceof CheckBox cb) {
                Object id = cb.getUserData();
                cb.setSelected(id instanceof String s && selectedPaymentIds.contains(s));
            }
        }
        refreshRentPaymentSumLabel();
    }

    private void addRentPaymentChoices(JsonArray cards, String zone) {
        if (cards == null) {
            return;
        }
        for (JsonElement el : cards) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject card = el.getAsJsonObject();
            String id = jsonString(card, "id", "");
            if (id.isBlank()) {
                continue;
            }
            CardDisplayData data = CardDisplayData.fromHandCardJson(card);
            CheckBox checkBox = new CheckBox(zone + " · " + cardTitle(data) + " · " + jsonInt(card, "valueM", 0) + "M");
            checkBox.setUserData(id);
            checkBox.getStyleClass().add("payment-check");
            checkBox.selectedProperty().addListener((obs, oldValue, selected) -> {
                if (selected) {
                    selectedPaymentIds.add(id);
                } else {
                    selectedPaymentIds.remove(id);
                }
                refreshRentPaymentSumLabel();
            });
            rentPaymentPickPane.getChildren().add(checkBox);
        }
    }

    private void refreshRentPaymentSumLabel() {
        rentPaymentSumLabel.setText(i18n("rent.sumLabel", computeSelectedPaymentSum(), pendingRentPaymentM));
    }

    private int computeSelectedPaymentSum() {
        JsonObject self = findPlayerInState(lastStatePayload, playerId());
        if (self == null) {
            return 0;
        }
        Map<String, Integer> values = new HashMap<>();
        accumulateValues(self.getAsJsonArray("bankCards"), values);
        accumulateValues(self.getAsJsonArray("propertyZoneCards"), values);
        int total = 0;
        for (String id : selectedPaymentIds) {
            total += values.getOrDefault(id, 0);
        }
        return total;
    }

    private int totalPayableValue() {
        JsonObject self = findPlayerInState(lastStatePayload, playerId());
        if (self == null) {
            return 0;
        }
        Map<String, Integer> values = new HashMap<>();
        accumulateValues(self.getAsJsonArray("bankCards"), values);
        accumulateValues(self.getAsJsonArray("propertyZoneCards"), values);
        return values.values().stream().mapToInt(Integer::intValue).sum();
    }

    private List<String> recommendedPaymentIds() {
        if (pendingRentPaymentM <= 0) {
            return List.of();
        }
        JsonObject self = findPlayerInState(lastStatePayload, playerId());
        if (self == null) {
            return List.of();
        }
        List<PaymentOption> options = new ArrayList<>();
        collectPaymentOptions(self.getAsJsonArray("bankCards"), "BANK", options);
        collectPaymentOptions(self.getAsJsonArray("propertyZoneCards"), "PROPERTY", options);
        return bestPaymentCardIds(options, pendingRentPaymentM);
    }

    private static void collectPaymentOptions(JsonArray cards, String zoneKey, List<PaymentOption> out) {
        if (cards == null) {
            return;
        }
        for (JsonElement el : cards) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject card = el.getAsJsonObject();
            String id = jsonString(card, "id", "");
            int value = jsonInt(card, "valueM", 0);
            if (!id.isBlank() && value > 0) {
                out.add(new PaymentOption(id, value, zoneKey));
            }
        }
    }

    private static List<String> bestPaymentCardIds(List<PaymentOption> cards, int amountDue) {
        int due = Math.max(0, amountDue);
        if (due <= 0 || cards == null || cards.isEmpty()) {
            return List.of();
        }
        List<PaymentOption> positive = cards.stream()
                .filter(option -> option.value() > 0)
                .toList();
        if (positive.isEmpty()) {
            return List.of();
        }
        List<PaymentOption> bankOnly = positive.stream()
                .filter(option -> !"PROPERTY".equals(option.zoneKey()))
                .toList();
        int bankTotal = bankOnly.stream().mapToInt(PaymentOption::value).sum();
        List<PaymentOption> eligible = bankTotal >= due ? bankOnly : positive;
        int total = eligible.stream().mapToInt(PaymentOption::value).sum();
        if (total < due) {
            return eligible.stream().map(PaymentOption::id).toList();
        }

        List<PaymentChoice> dp = new ArrayList<>();
        for (int i = 0; i <= total; i++) {
            dp.add(null);
        }
        dp.set(0, new PaymentChoice(List.of(), 0, 0, 0, 0));
        for (PaymentOption option : eligible) {
            for (int sum = total - option.value(); sum >= 0; sum--) {
                PaymentChoice prev = dp.get(sum);
                if (prev == null) {
                    continue;
                }
                int nextSum = sum + option.value();
                PaymentChoice next = prev.with(option);
                PaymentChoice current = dp.get(nextSum);
                if (current == null || comparePaymentChoice(nextSum, next, nextSum, current) < 0) {
                    dp.set(nextSum, next);
                }
            }
        }

        int bestSum = -1;
        PaymentChoice best = null;
        for (int sum = due; sum <= total; sum++) {
            PaymentChoice candidate = dp.get(sum);
            if (candidate == null) {
                continue;
            }
            if (best == null || comparePaymentChoice(sum, candidate, bestSum, best) < 0) {
                bestSum = sum;
                best = candidate;
            }
        }
        return best == null ? List.of() : best.ids();
    }

    private static int comparePaymentChoice(int amountA, PaymentChoice a, int amountB, PaymentChoice b) {
        int amount = Integer.compare(amountA, amountB);
        if (amount != 0) {
            return amount;
        }
        int propertyCount = Integer.compare(a.propertyCount(), b.propertyCount());
        if (propertyCount != 0) {
            return propertyCount;
        }
        int propertyValue = Integer.compare(a.propertyValue(), b.propertyValue());
        if (propertyValue != 0) {
            return propertyValue;
        }
        int cardCount = Integer.compare(a.cardCount(), b.cardCount());
        if (cardCount != 0) {
            return cardCount;
        }
        return Integer.compare(a.bankValue(), b.bankValue());
    }

    private static void accumulateValues(JsonArray cards, Map<String, Integer> values) {
        if (cards == null) {
            return;
        }
        for (JsonElement el : cards) {
            if (el.isJsonObject()) {
                JsonObject card = el.getAsJsonObject();
                values.put(jsonString(card, "id", ""), jsonInt(card, "valueM", 0));
            }
        }
    }

    private String responseContextText(JsonObject state, int due) {
        JsonObject ctx = state.has("pendingResponseContext") && state.get("pendingResponseContext").isJsonObject()
                ? state.getAsJsonObject("pendingResponseContext")
                : null;
        if (ctx == null) {
            return due > 0 ? i18n("response.paymentBody") : i18n("response.defaultBody");
        }
        StringBuilder out = new StringBuilder();
        out.append(i18n("response.actionLine", responseActionTitle(ctx, "")));
        String actor = responsePlayerName(jsonString(ctx, "actorName", ""), jsonString(ctx, "actorPlayerId", ""));
        String target = responsePlayerName(jsonString(ctx, "targetName", ""), jsonString(ctx, "targetPlayerId", ""));
        if (!actor.isBlank() || !target.isBlank()) {
            out.append("\n").append(i18n("response.fromTo",
                    actor.isBlank() ? i18n("player.fallback") : actor,
                    target.isBlank() ? i18n("player.fallback") : target));
        }
        String color = jsonString(ctx, "colorKey", "");
        if (!color.isBlank()) {
            out.append(" · ").append(colorName(color));
        }
        int amount = jsonInt(ctx, "amountDueM", due);
        if (amount > 0) {
            out.append(" · ").append(amount).append("M");
        }
        String original = responseActionTitle(ctx, "original");
        if (!original.isBlank()) {
            out.append("\n").append(i18n("response.originalLine", original));
        }
        return out.toString();
    }

    private String responseActionTitle(JsonObject ctx, String prefix) {
        String nameKey = prefix == null || prefix.isBlank() ? "actionCardName" : prefix + "ActionCardName";
        String codeKey = prefix == null || prefix.isBlank() ? "actionEffectCode" : prefix + "ActionEffectCode";
        String name = jsonString(ctx, nameKey, "");
        if (!name.isBlank()) {
            return name;
        }
        return actionEffectLabel(jsonString(ctx, codeKey, ""));
    }

    private String responsePlayerName(String name, String id) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return id == null || id.isBlank() ? "" : displayNameForPlayer(id);
    }

    private void startResponseCountdown(long deadlineMs) {
        stopResponseCountdown();
        if (deadlineMs <= 0) {
            responseCountdownLabel.setText("");
            return;
        }
        updateResponseCountdownLabel(deadlineMs);
        responseCountdownTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            updateResponseCountdownLabel(deadlineMs);
            eventLineLabel.setText(waitingResponseText());
        }));
        responseCountdownTimer.setCycleCount(Timeline.INDEFINITE);
        responseCountdownTimer.play();
    }

    private void updateResponseCountdownLabel(long deadlineMs) {
        long left = Math.max(0L, (deadlineMs - System.currentTimeMillis() + 999L) / 1000L);
        responseCountdownLabel.setText(i18n("response.countdown", left));
    }

    private void stopResponseCountdown() {
        if (responseCountdownTimer != null) {
            responseCountdownTimer.stop();
            responseCountdownTimer = null;
        }
        if (responseCountdownLabel != null) {
            responseCountdownLabel.setText("");
        }
    }

    private JsonObject findJustSayNoCard() {
        if (latestHandCards == null) {
            return null;
        }
        for (JsonElement el : latestHandCards) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject card = el.getAsJsonObject();
            if ("RENT_WAIVER".equalsIgnoreCase(jsonString(card, "effectCode", ""))) {
                return card;
            }
        }
        return null;
    }

    private void rebuildRoomState(JsonObject room) {
        roomMembersPane.getChildren().clear();
        roomSeatsGrid.getChildren().clear();
        if (room == null) {
            roomStatusLabel.setText(i18n("lobby.noRoom"));
            return;
        }
        JsonArray members = room.has("members") && room.get("members").isJsonArray()
                ? room.getAsJsonArray("members")
                : new JsonArray();
        JsonArray seats = room.has("seats") && room.get("seats").isJsonArray()
                ? room.getAsJsonArray("seats")
                : new JsonArray();
        roomStatusLabel.setText(i18n("lobby.roomStatus",
                jsonString(room, "sessionId", ""),
                members.size(),
                activeSeatCount(seats)));
        for (JsonElement el : members) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject member = el.getAsJsonObject();
            String text = jsonString(member, "nickname", "");
            if (jsonBool(member, "host", false)) {
                text += " · " + i18n("lobby.hostShort");
            }
            Label chip = new Label(text);
            chip.getStyleClass().add("member-chip");
            roomMembersPane.getChildren().add(chip);
        }
        List<String> memberNames = memberNames(room);
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).isJsonObject()) {
                roomSeatsGrid.add(seatCard(room, seats.get(i).getAsJsonObject(), memberNames), i % 3, i / 3);
            }
        }
    }

    private VBox seatCard(JsonObject room, JsonObject seat, List<String> memberNames) {
        int index = jsonInt(seat, "index", 0);
        String role = jsonString(seat, "role", "empty");
        String nickname = jsonString(seat, "nickname", "");
        VBox box = new VBox(6);
        box.getStyleClass().addAll("seat-card", "seat-" + role);
        Label title = new Label(i18n("lobby.seatTitle", index + 1));
        title.getStyleClass().add("seat-title");

        ComboBox<String> roleBox = new ComboBox<>();
        roleBox.getItems().setAll("empty", "human", "hard", "strong", "llm", "student");
        roleBox.getSelectionModel().select(role);
        roleBox.setDisable(!isLobbyHost(room));
        roleBox.valueProperty().addListener((obs, oldRole, newRole) -> {
            if (newRole != null && !newRole.equals(oldRole)) {
                sendRoomSeat(index, newRole, "human".equals(newRole) ? defaultSeatNickname(seat, memberNames) : "");
            }
        });

        ComboBox<String> memberBox = new ComboBox<>();
        memberBox.getItems().setAll(memberNames);
        if (!nickname.isBlank()) {
            memberBox.getSelectionModel().select(nickname);
        }
        memberBox.setDisable(!isLobbyHost(room) || !"human".equals(role));
        memberBox.valueProperty().addListener((obs, oldName, newName) -> {
            if (newName != null && !newName.equals(oldName)) {
                sendRoomSeat(index, "human", newName);
            }
        });

        Label help = new Label(seatSummary(role, nickname));
        help.setWrapText(true);
        help.getStyleClass().add("seat-help");
        box.getChildren().addAll(title, roleBox);
        if ("human".equals(role)) {
            box.getChildren().add(memberBox);
        }
        box.getChildren().add(help);
        return box;
    }

    private void sendRoomSeat(int index, String role, String nickname) {
        Map<String, Object> payload = scopedPayload();
        payload.put("seatIndex", index);
        payload.put("role", role);
        if (nickname != null && !nickname.isBlank()) {
            payload.put("nickname", nickname);
        }
        sendEnvelope("ROOM_SET_SEAT", payload);
    }

    private void updateLobbyControls() {
        boolean inRoom = currentLobbyRoom != null;
        boolean host = isLobbyHost(currentLobbyRoom);
        createRoomButton.setDisable(inRoom);
        joinRoomButton.setDisable(inRoom);
        leaveRoomButton.setDisable(!inRoom);
        startRoomButton.setDisable(!host || currentLobbyRoom == null
                || activeSeatCount(currentLobbyRoom.has("seats") && currentLobbyRoom.get("seats").isJsonArray()
                ? currentLobbyRoom.getAsJsonArray("seats") : new JsonArray()) < 2);
        if (!inRoom) {
            roomStatusLabel.setText(i18n("lobby.noRoom"));
        }
    }

    private void syncActionButtons() {
        boolean connected = ws.isConnected();
        boolean responsePending = lastStatePayload != null
                && "WAITING_FOR_RESPONSE".equals(jsonString(lastStatePayload, "turnPhase", ""));
        boolean myTurn = lastStatePayload != null
                && playerId().equals(jsonString(lastStatePayload, "currentPlayerId", ""))
                && playerId().equals(jsonString(lastStatePayload, "decisionPlayerId", jsonString(lastStatePayload, "currentPlayerId", "")));
        String turnPhase = lastStatePayload == null ? "" : jsonString(lastStatePayload, "turnPhase", "");
        boolean hasCard = selectedCard != null;
        boolean money = hasCard && "MONEY".equals(selectedCard.getKind());
        boolean action = hasCard && "ACTION".equals(selectedCard.getKind());
        boolean property = hasCard && ("PROPERTY".equals(selectedCard.getKind()) || "WILD".equals(selectedCard.getKind()));
        boolean showDraw = connected && !responsePending && myTurn && "DRAW".equals(turnPhase);
        drawButton.setVisible(showDraw);
        drawButton.setManaged(showDraw);
        drawButton.setDisable(!showDraw);
        endTurnButton.setDisable(!connected || responsePending || !myTurn);
        depositButton.setDisable(!connected || responsePending || !myTurn || (!money && !action));
        deployButton.setDisable(!connected || responsePending || !myTurn || !property);
        actionButton.setDisable(!connected || responsePending || !myTurn || !action);
        discardButton.setDisable(!connected || responsePending || !myTurn || !hasCard);
    }

    private void maybeAutoDraw() {
        if (!ws.isConnected() || lastStatePayload == null || jsonBool(lastStatePayload, "gameOver", false)) {
            return;
        }
        String turnPhase = jsonString(lastStatePayload, "turnPhase", "");
        String current = jsonString(lastStatePayload, "currentPlayerId", "");
        String decision = jsonString(lastStatePayload, "decisionPlayerId", current);
        if (!"DRAW".equals(turnPhase) || !playerId().equals(current) || !playerId().equals(decision)) {
            return;
        }
        String key = jsonString(lastStatePayload, "sessionId", sessionId()) + ":"
                + jsonLong(lastStatePayload, "stateSequence", 0L) + ":"
                + current + ":" + turnPhase;
        if (key.equals(lastAutoDrawKey)) {
            return;
        }
        lastAutoDrawKey = key;
        eventLineLabel.setText(i18n("autoDrawing"));
        sendEnvelope("DRAW", Map.of("count", 2));
    }

    private void syncModeUi() {
        String mode = gameModeCombo.getSelectionModel().getSelectedItem();
        boolean hvm = "HVM".equals(mode);
        boolean custom = "CUSTOM".equals(mode);
        aiDifficultyBox.setVisible(hvm);
        aiDifficultyBox.setManaged(hvm);
        customLineupBox.setVisible(custom);
        customLineupBox.setManaged(custom);
        if ("PVP".equals(mode) || custom) {
            playerIdField.setText("pvp-1");
        } else {
            playerIdField.setText("human-1");
        }
        if (custom) {
            syncCustomLineupCount();
        }
        modeHintLabel.setText(switch (mode) {
            case "PVP" -> i18n("hint.pvp");
            case "LLM" -> i18n("hint.llm");
            case "CUSTOM" -> i18n("hint.custom");
            default -> i18n("hint.hvm");
        });
    }

    private void syncCustomLineupCount() {
        if (!"CUSTOM".equals(gameModeCombo.getSelectionModel().getSelectedItem())) {
            return;
        }
        int size = customLineupRoles().size();
        if (size >= 2 && size <= 5) {
            playerCountSpinner.getValueFactory().setValue(size);
        }
    }

    private void applyI18n() {
        brandKickerLabel.setText(i18n("brandKicker"));
        appTitleLabel.setText(i18n("app.title"));
        appSubtitleLabel.setText(i18n("app.subtitle"));
        serverLabel.setText(i18n("label.server"));
        languageLabel.setText(i18n("label.language"));
        connectButton.setText(i18n("btn.connect"));
        refreshRoomsButton.setText(i18n("btn.refreshRooms"));
        topRefreshRoomsButton.setText(i18n("btn.refreshRooms"));
        statusLabel.setText(ws.isConnected() ? i18n("status.connected") : i18n("label.notConnected"));
        connectionLabel.setText(ws.isConnected() ? i18n("status.connected") : i18n("status.disconnected"));

        gameModeLabel.setText(i18n("label.gameMode"));
        playerCountLabel.setText(i18n("label.playerCount"));
        aiDifficultyLabel.setText(i18n("label.aiDifficulty"));
        customLineupLabel.setText(i18n("label.customLineup"));
        playerIdLabel.setText(i18n("label.playerId"));
        sessionIdLabel.setText(i18n("label.sessionId"));
        randomizeFirstCheck.setText(i18n("check.randomFirst"));
        startGameButton.setText(i18n("btn.startGame"));

        infoIntroButton.setText(i18n("infoIntro"));
        infoRulesButton.setText(i18n("infoRules"));
        infoGuideButton.setText(i18n("infoGuide"));
        updateInfoPanel();

        roomListLabel.setText(i18n("lobby.roomList"));
        nicknameLabel.setText(i18n("label.nickname"));
        createRoomButton.setText(i18n("btn.createRoom"));
        joinRoomButton.setText(i18n("btn.joinRoom"));
        waitingRoomLabel.setText(i18n("lobby.waitingRoom"));
        startRoomButton.setText(i18n("btn.startRoom"));
        leaveRoomButton.setText(i18n("btn.leaveRoom"));

        disconnectButton.setText(i18n("btn.disconnect"));
        backSetupButton().setText(i18n("backSetup"));
        myBoardTitleLabel.setText(i18n("propertyZone"));
        drawPileNameLabel.setText(i18n("drawPileName"));
        discardPileNameLabel.setText(i18n("discardPileName"));
        actionPadTitleLabel.setText(i18n("actionArea"));
        handTitleLabel.setText(i18n("hand"));
        handHintLabel.setText(i18n("handHint"));
        drawButton.setText(i18n("btn.draw"));
        endTurnButton.setText(i18n("btn.endTurn"));
        depositButton.setText(i18n("btn.deposit"));
        deployButton.setText(i18n("btn.deploy"));
        actionButton.setText(i18n("btn.action"));
        discardButton.setText(i18n("btn.discard"));
        rentPaymentGreedyButton.setText(i18n("btn.autoSelect"));
        rentPaymentSubmitButton.setText(i18n("btn.confirmPay"));
        rentPaymentClearButton.setText(i18n("btn.clearSelection"));
        responseJsnButton.setText(i18n("btn.playJsn"));
        responsePassButton.setText(i18n("btn.passResponse"));
        if (selectedCard == null) {
            selectedCardLabel.setText(i18n("label.selectCardHint"));
        }
        syncModeUi();
        updateStatusTexts();
    }

    @FXML private Button backSetupButton;

    private Button backSetupButton() {
        return backSetupButton;
    }

    private void updateInfoPanel() {
        infoIntroButton.getStyleClass().setAll("info-tab");
        infoRulesButton.getStyleClass().setAll("info-tab");
        infoGuideButton.getStyleClass().setAll("info-tab");
        Button active = switch (infoPanel) {
            case "rules" -> infoRulesButton;
            case "guide" -> infoGuideButton;
            default -> infoIntroButton;
        };
        active.getStyleClass().add("active");
        if ("rules".equals(infoPanel)) {
            infoTitleLabel.setText(i18n("infoRulesTitle"));
            infoLine1Label.setText(i18n("infoRules1"));
            infoLine2Label.setText(i18n("infoRules2"));
            infoLine3Label.setText(i18n("infoRules3"));
        } else if ("guide".equals(infoPanel)) {
            infoTitleLabel.setText(i18n("infoGuideTitle"));
            infoLine1Label.setText(i18n("infoGuide1"));
            infoLine2Label.setText(i18n("infoGuide2"));
            infoLine3Label.setText(i18n("infoGuide3"));
        } else {
            infoTitleLabel.setText(i18n("infoIntroTitle"));
            infoLine1Label.setText(i18n("infoIntro1"));
            infoLine2Label.setText(i18n("infoIntro2"));
            infoLine3Label.setText(i18n("infoIntro3"));
        }
    }

    private void switchToGameView() {
        startPane.setVisible(false);
        startPane.setManaged(false);
        gamePane.setVisible(true);
        gamePane.setManaged(true);
        clearError();
    }

    private void switchToStartView() {
        gamePane.setVisible(false);
        gamePane.setManaged(false);
        startPane.setVisible(true);
        startPane.setManaged(true);
        clearError();
    }

    private void refreshButtons() {
        boolean connected = ws.isConnected();
        connectButton.setDisable(connected);
        disconnectButton.setDisable(!connected);
    }

    private void runWhenConnected(Runnable action) {
        if (ws.isConnected()) {
            action.run();
            return;
        }
        postConnectAction = action;
        onConnect();
    }

    private void sendEnvelope(String type, Map<String, Object> payload) {
        try {
            Map<String, Object> scoped = new LinkedHashMap<>();
            if (sessionId() != null && !sessionId().isBlank()) {
                scoped.put("sessionId", sessionId());
            }
            if (payload != null) {
                scoped.putAll(payload);
            }
            String json = WsJson.envelope(type, scoped);
            ws.sendRaw(json);
            appendTraffic("← " + type);
        } catch (Exception ex) {
            showError(i18n("log.sendFailed", ex.getMessage()));
            appendTraffic("« " + i18n("log.sendFailed", ex.getMessage()) + " »");
        }
    }

    private Map<String, Object> scopedPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId());
        return payload;
    }

    private String roomListLabel(JsonObject room) {
        String session = jsonString(room, "sessionId", i18n("lobby.unnamedRoom"));
        int seats = jsonInt(room, "seatCount", 0);
        int humans = jsonInt(room, "humanSeats", 0);
        int connected = jsonInt(room, "connectedPlayers", 0);
        String state = jsonBool(room, "started", false) ? i18n("lobby.started") : i18n("lobby.waiting");
        String host = jsonString(room, "hostNickname", "");
        String suffix = host.isBlank() ? "" : " · " + i18n("lobby.host", host);
        return i18n("lobby.roomRow", session, connected, humans, seats, state) + suffix;
    }

    private JsonObject findMyLobbySeat(JsonObject room) {
        if (room == null || !room.has("seats") || !room.get("seats").isJsonArray()) {
            return null;
        }
        String me = nickname();
        for (JsonElement el : room.getAsJsonArray("seats")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject seat = el.getAsJsonObject();
            if ("human".equals(jsonString(seat, "role", "")) && me.equals(jsonString(seat, "nickname", ""))) {
                return seat;
            }
        }
        return null;
    }

    private boolean isLobbyHost(JsonObject room) {
        if (room == null || !room.has("members") || !room.get("members").isJsonArray()) {
            return false;
        }
        String me = nickname();
        for (JsonElement el : room.getAsJsonArray("members")) {
            if (el.isJsonObject()) {
                JsonObject member = el.getAsJsonObject();
                if (me.equals(jsonString(member, "nickname", "")) && jsonBool(member, "host", false)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> memberNames(JsonObject room) {
        List<String> names = new ArrayList<>();
        if (room == null || !room.has("members") || !room.get("members").isJsonArray()) {
            return names;
        }
        for (JsonElement el : room.getAsJsonArray("members")) {
            if (el.isJsonObject()) {
                String name = jsonString(el.getAsJsonObject(), "nickname", "");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private int activeSeatCount(JsonArray seats) {
        int count = 0;
        if (seats == null) {
            return 0;
        }
        for (JsonElement el : seats) {
            if (el.isJsonObject() && !"empty".equals(jsonString(el.getAsJsonObject(), "role", "empty"))) {
                count++;
            }
        }
        return count;
    }

    private String defaultSeatNickname(JsonObject seat, List<String> memberNames) {
        String current = jsonString(seat, "nickname", "");
        if (!current.isBlank()) {
            return current;
        }
        return memberNames.isEmpty() ? nickname() : memberNames.get(0);
    }

    private String seatSummary(String role, String nickname) {
        return switch (role) {
            case "human" -> nickname == null || nickname.isBlank()
                    ? i18n("lobby.humanSeatEmpty")
                    : i18n("lobby.humanSeat", nickname);
            case "hard" -> i18n("lobby.hardSeat");
            case "strong" -> i18n("lobby.strongSeat");
            case "llm" -> i18n("lobby.llmSeat");
            case "student" -> i18n("lobby.studentSeat");
            default -> i18n("lobby.emptySeat");
        };
    }

    private JsonObject findPlayerInState(JsonObject state, String id) {
        if (state == null || !state.has("players") || !state.get("players").isJsonArray()) {
            return null;
        }
        for (JsonElement el : state.getAsJsonArray("players")) {
            if (el.isJsonObject()) {
                JsonObject player = el.getAsJsonObject();
                if (id.equals(jsonString(player, "playerId", ""))) {
                    return player;
                }
            }
        }
        return null;
    }

    private String displayNameForPlayer(String id) {
        JsonObject player = findPlayerInState(lastStatePayload, id);
        return player == null ? id : jsonString(player, "displayName", id);
    }

    private boolean needsOverflowDiscard() {
        return lastStatePayload != null
                && playerId().equals(jsonString(lastStatePayload, "currentPlayerId", ""))
                && jsonInt(lastStatePayload, "overflowDiscardCount", 0) > 0;
    }

    private List<String> customLineupRoles() {
        String raw = customLineupCombo.isEditable() && customLineupCombo.getEditor() != null
                ? customLineupCombo.getEditor().getText()
                : customLineupCombo.getValue();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        for (String token : raw.split("[,;\\s]+")) {
            if (!token.isBlank()) {
                roles.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        return roles;
    }

    private String defaultActionForCard(CardDisplayData card) {
        if ("MONEY".equals(card.getKind())) {
            return "DEPOSIT";
        }
        if ("PROPERTY".equals(card.getKind()) || "WILD".equals(card.getKind())) {
            return "DEPLOY";
        }
        if ("ACTION".equals(card.getKind())) {
            return "ACTION";
        }
        return "DISCARD";
    }

    private String cardKindClass(CardDisplayData data) {
        return switch (data.getKind()) {
            case "MONEY" -> "card-money";
            case "PROPERTY" -> "card-property";
            case "WILD" -> "card-wild";
            case "ACTION" -> "card-action";
            default -> "card-unknown";
        };
    }

    private String cardTitle(CardDisplayData data) {
        return data == null ? i18n("cardFallback") : data.getTitle();
    }

    private String playActionLabel(String actionType) {
        return switch (safeUpper(actionType)) {
            case "DEPOSIT" -> i18n("btn.deposit");
            case "DEPLOY" -> i18n("btn.deploy");
            case "ACTION" -> i18n("btn.action");
            case "DISCARD", "FORCE_DISCARD" -> i18n("btn.discard");
            default -> i18n("directPlay");
        };
    }

    private record PlayedEvent(long sequence, String playerId, String actionType, JsonObject card, String summary) {
    }

    private record PaymentOption(String id, int value, String zoneKey) {
    }

    private record PaymentChoice(List<String> ids, int cardCount, int bankValue, int propertyCount, int propertyValue) {
        PaymentChoice with(PaymentOption option) {
            List<String> nextIds = new ArrayList<>(ids);
            nextIds.add(option.id());
            boolean property = "PROPERTY".equals(option.zoneKey());
            return new PaymentChoice(
                    List.copyOf(nextIds),
                    cardCount + 1,
                    bankValue + (property ? 0 : option.value()),
                    propertyCount + (property ? 1 : 0),
                    propertyValue + (property ? option.value() : 0));
        }
    }

    private String actionEffectLabel(String code) {
        String normalized = safeUpper(code);
        if (I18n.isChinese()) {
            return switch (normalized) {
                case "RENT" -> "收租";
                case "RENT_DUAL" -> "双色收租";
                case "DOUBLE_RENT" -> "租金加倍";
                case "STEAL_PROPERTY" -> "暗中夺产";
                case "FORCED_DEAL" -> "强制交易";
                case "DEBT_COLLECTOR" -> "讨债";
                case "RENT_WAIVER" -> "Just Say No";
                case "PASS_GO" -> "经过起点";
                case "HOUSE" -> "房屋";
                case "HOTEL" -> "旅馆";
                case "BIRTHDAY" -> "生日礼金";
                case "DEAL_BREAKER" -> "交易破坏者";
                default -> normalized;
            };
        }
        return switch (normalized) {
            case "RENT" -> "Rent";
            case "RENT_DUAL" -> "Dual-Color Rent";
            case "DOUBLE_RENT" -> "Double Rent";
            case "STEAL_PROPERTY" -> "Sly Deal";
            case "FORCED_DEAL" -> "Forced Deal";
            case "DEBT_COLLECTOR" -> "Debt Collector";
            case "RENT_WAIVER" -> "Just Say No";
            case "PASS_GO" -> "Pass Go";
            case "HOUSE" -> "House";
            case "HOTEL" -> "Hotel";
            case "BIRTHDAY" -> "Birthday";
            case "DEAL_BREAKER" -> "Deal Breaker";
            default -> normalized;
        };
    }

    private String colorName(String colorKey) {
        String key = safeUpper(colorKey);
        String value = i18n("color." + key);
        return value.startsWith("!color.") ? key : value;
    }

    private void showError(String message) {
        Label target = gamePane.isVisible() ? gameErrorLabel : setupErrorLabel;
        target.setText(message == null ? "" : message);
        target.setVisible(message != null && !message.isBlank());
        target.setManaged(message != null && !message.isBlank());
    }

    private void clearError() {
        setupErrorLabel.setText("");
        setupErrorLabel.setVisible(false);
        setupErrorLabel.setManaged(false);
        gameErrorLabel.setText("");
        gameErrorLabel.setVisible(false);
        gameErrorLabel.setManaged(false);
    }

    private void cancelConnectionTimeout() {
        if (connectionTimeout != null) {
            connectionTimeout.stop();
            connectionTimeout = null;
        }
    }

    private void appendTraffic(String line) {
        if (trafficArea == null) {
            return;
        }
        if (trafficLineCount >= MAX_TRAFFIC_LINES) {
            trafficArea.clear();
            trafficLineCount = 0;
        }
        trafficArea.appendText("[" + LocalTime.now().format(TIME) + "] " + line + "\n");
        trafficLineCount++;
    }

    private String trafficSummary(String type, String raw) {
        if (!"STATE_UPDATE".equals(type) && !"MY_HAND".equals(type)) {
            return type == null || type.isBlank() ? "UNKNOWN" : type;
        }
        try {
            JsonObject p = payload(raw);
            if ("STATE_UPDATE".equals(type)) {
                return "STATE_UPDATE seq=" + jsonLong(p, "stateSequence", 0L)
                        + " phase=" + jsonString(p, "turnPhase", "")
                        + " current=" + jsonString(p, "currentPlayerId", "");
            }
            int count = p.has("cards") && p.get("cards").isJsonArray()
                    ? p.getAsJsonArray("cards").size()
                    : 0;
            return "MY_HAND cards=" + count;
        } catch (RuntimeException ignored) {
            return type;
        }
    }

    private String playerId() {
        return playerIdField.getText() == null ? "" : playerIdField.getText().trim();
    }

    private String sessionId() {
        return sessionIdField.getText() == null ? "" : sessionIdField.getText().trim();
    }

    private String nickname() {
        String value = nicknameField.getText() == null ? "" : nicknameField.getText().trim();
        if (!value.isBlank()) {
            return value;
        }
        String fallback = I18n.isChinese() ? "玩家" : "Player";
        nicknameField.setText(fallback);
        return fallback;
    }

    private static JsonObject payload(String raw) {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        return root.has("payload") && root.get("payload").isJsonObject()
                ? root.getAsJsonObject("payload")
                : new JsonObject();
    }

    private static String jsonString(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue == null ? "" : defaultValue;
        }
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException ex) {
            return defaultValue == null ? "" : defaultValue;
        }
    }

    private static int jsonInt(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private static long jsonLong(JsonObject obj, String key, long defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private static boolean jsonBool(JsonObject obj, String key, boolean defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private static String safeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String i18n(String key, Object... args) {
        return I18n.get(key, args);
    }
}
