package com.monopoly.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.monopoly.fx.presentation.CardDisplayData;
import com.monopoly.fx.ui.PlayerBoardPanel;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static com.monopoly.fx.FxJson.jsonBool;
import static com.monopoly.fx.FxJson.jsonInt;
import static com.monopoly.fx.FxJson.jsonLong;
import static com.monopoly.fx.FxJson.jsonString;
import static com.monopoly.fx.FxJson.payload;
import static com.monopoly.fx.FxJson.safeUpper;

/**
 * JavaFX table client rebuilt around the same state model as the web client.
 * Backend messages and game logic stay unchanged; this class only translates
 * snapshots into a cleaner desktop table.
 */
public class MainController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_TRAFFIC_LINES = 120;
    private final FxWebSocketClient ws = new FxWebSocketClient();
    private final FxClientState state = new FxClientState();
    private final ToggleGroup handToggleGroup = new ToggleGroup();
    private ClientCommandGateway commandGateway;
    private ConnectionController connectionController;
    private ActionOptionDialogService actionOptionDialogService;
    private HandPanelController handPanelController;
    private RentPaymentPanelController rentPaymentPanelController;
    private PlayActionController playActionController;
    private ResponsePanelController responsePanelController;
    private String infoPanel = "intro";
    private boolean infoDetailExpanded;
    private int trafficLineCount;
    private boolean infoVisible;

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
    @FXML private Label aiCardKickerLabel;
    @FXML private Label aiCardTitleLabel;
    @FXML private Label aiCardSubtitleLabel;
    @FXML private Label liveCardKickerLabel;
    @FXML private Label liveCardTitleLabel;
    @FXML private Label liveCardSubtitleLabel;
    @FXML private Button startAiButton;
    @FXML private Label featureAiTitleLabel;
    @FXML private Label featureAiTextLabel;
    @FXML private Label featurePvpTitleLabel;
    @FXML private Label featurePvpTextLabel;
    @FXML private Label featureDemoTitleLabel;
    @FXML private Label featureDemoTextLabel;
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
    @FXML private VBox infoCopyBox;
    @FXML private Label infoTitleLabel;
    @FXML private Label infoLine1Label;
    @FXML private Label infoLine2Label;
    @FXML private Label infoLine3Label;
    @FXML private Label infoLine4Label;
    @FXML private Button infoDetailButton;
    @FXML private ScrollPane infoDetailScroll;
    @FXML private Label infoDetailLabel;

    @FXML private Label roomListLabel;
    @FXML private GridPane lobbyPanel;
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
    @FXML private ScrollPane handScroll;
    @FXML private HBox handStrip;
    @FXML private HBox lowerTableBox;

    @FXML private TextArea trafficArea;

    @FXML
    private void initialize() {
        connectionController = new ConnectionController(
                ws,
                state,
                new ConnectionController.Refs(wsUrlField, statusLabel, connectionLabel, connectButton, disconnectButton),
                this::i18n,
                this::appendTraffic,
                this::handleInbound,
                this::showError,
                this::clearError,
                this::switchToStartView,
                this::updateLobbyControls,
                this::clearPlayedEvents);
        commandGateway = new ClientCommandGateway(
                ws,
                state,
                this::sessionId,
                this::onConnect,
                this::showError,
                this::appendTraffic,
                this::i18n);
        actionOptionDialogService = new ActionOptionDialogService(
                root,
                state,
                this::playerId,
                this::publicCardLabel,
                this::displayNameForPlayer,
                this::colorName,
                this::i18n,
                this::sendPlay);
        handPanelController = new HandPanelController(
                state,
                new HandPanelController.Refs(handScroll, handStrip, selectedCardLabel, selectedPreviewPane),
                this::cardKindClass,
                this::i18n);
        rentPaymentPanelController = new RentPaymentPanelController(
                state,
                new RentPaymentPanelController.Refs(
                        rentPaymentBox,
                        rentPaymentHint,
                        rentPaymentSumLabel,
                        rentPaymentPickPane,
                        rentPaymentGreedyButton,
                        rentPaymentSubmitButton,
                        rentPaymentClearButton),
                this::playerId,
                this::findPlayerInState,
                this::cardTitle,
                this::showError,
                paymentIds -> sendEnvelope("PLAY", WsJson.playResponsePass(playerId(), paymentIds)),
                this::i18n);
        playActionController = new PlayActionController(
                state,
                this::playerId,
                this::needsOverflowDiscard,
                this::showError,
                message -> localizedBackendMessage(message, i18n("error.noOptions")),
                commandGateway,
                actionOptionDialogService,
                this::i18n);
        responsePanelController = new ResponsePanelController(
                state,
                new ResponsePanelController.Refs(
                        responseBox,
                        responseTitleLabel,
                        responseCountdownLabel,
                        responseContextLabel,
                        responseJsnButton,
                        responsePassButton,
                        eventLineLabel),
                this::playerId,
                this::displayNameForPlayer,
                this::colorName,
                this::actionEffectLabel,
                payload -> sendEnvelope("PLAY", payload),
                this::showError,
                this::i18n);

        wsUrlField.setText("ws://localhost:8025/ws");
        sessionIdField.setText("web-demo");
        playerIdField.setText("human-1");
        nicknameField.setText(I18n.get("default.nickname"));

        playerCountSpinner.setValueFactory(new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(2, 5, 2));
        gameModeCombo.getItems().setAll("HVM", "PVP", "LLM", "CUSTOM");
        gameModeCombo.setConverter(localizedValueConverter("mode."));
        gameModeCombo.getSelectionModel().select("HVM");
        aiDifficultyCombo.getItems().setAll("EASY", "NORMAL", "HARD", "STRONG", "LLM");
        aiDifficultyCombo.setConverter(aiDifficultyValueConverter());
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
            syncDefaultNickname();
            applyI18n();
            rebuildHand();
            rebuildAllFromState();
        });
        gameModeCombo.valueProperty().addListener((obs, oldValue, newValue) -> syncModeUi());
        playerCountSpinner.valueProperty().addListener((obs, oldValue, newValue) -> updateAiCardSubtitle());
        aiDifficultyCombo.valueProperty().addListener((obs, oldValue, newValue) -> syncModeUi());
        customLineupCombo.valueProperty().addListener((obs, oldValue, newValue) -> syncCustomLineupCount());
        if (customLineupCombo.getEditor() != null) {
            customLineupCombo.getEditor().textProperty().addListener((obs, oldValue, newValue) -> syncCustomLineupCount());
        }
        roomListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, label) -> {
            JsonObject row = state.roomRowsByLabel.get(label);
            if (row != null) {
                sessionIdField.setText(jsonString(row, "sessionId", sessionIdField.getText()));
            }
        });
        handPanelController.installSelectionHandling(handToggleGroup, this::quickPlay, this::syncActionButtons);

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
        toggleInfoPanel("intro");
    }

    private void toggleInfoPanel(String panel) {
        if (infoVisible && infoPanel.equals(panel)) {
            infoVisible = false;
        } else {
            infoPanel = panel;
            infoVisible = true;
        }
        infoDetailExpanded = false;
        updateInfoPanel();
    }

    @FXML
    private void onInfoRules() {
        toggleInfoPanel("rules");
    }

    @FXML
    private void onInfoGuide() {
        toggleInfoPanel("guide");
    }

    @FXML
    private void onStartAiGame() {
        gameModeCombo.getSelectionModel().select("LLM".equals(selectedAiProfile()) ? "LLM" : "HVM");
        playerIdField.setText("human-1");
        onStartGame();
    }

    @FXML
    private void onInfoDetailToggle() {
        infoDetailExpanded = !infoDetailExpanded;
        updateInfoPanel();
    }

    @FXML
    private void onConnect() {
        connectionController.connect(this::onRefreshRooms);
    }

    @FXML
    private void onDisconnect() {
        stopResponseCountdown();
        connectionController.disconnect(this::switchToStartView, this::updateLobbyControls);
        roomListView.getItems().clear();
    }

    @FXML
    private void onStartGame() {
        runWhenConnected(() -> {
            state.awaitingInitialState = true;
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
        JsonObject row = state.roomRowsByLabel.get(selected);
        if (row != null) {
            sessionIdField.setText(jsonString(row, "sessionId", sessionIdField.getText()));
        } else if (sessionId().isBlank()) {
            showError(i18n("lobby.selectRoomFirst"));
            return;
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
        state.currentLobbyRoom = null;
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
            showError(i18n("needDiscard", jsonInt(state.lastStatePayload, "overflowDiscardCount", 0)));
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
        responsePanelController.pass();
    }

    @FXML
    private void onPlayJustSayNo() {
        responsePanelController.playJustSayNo();
    }

    @FXML
    private void onRentPaymentGreedy() {
        rentPaymentPanelController.selectRecommendedAndSubmit();
    }

    @FXML
    private void onRentPaymentSubmit() {
        rentPaymentPanelController.submitSelection();
    }

    @FXML
    private void onRentPaymentClear() {
        rentPaymentPanelController.clearSelection();
    }

    void shutdown() {
        stopResponseCountdown();
        connectionController.shutdown();
    }

    private void onAuth() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerId", playerId());
        sendEnvelope("AUTH", payload);
    }

    private void onStartSession() {
        Map<String, Object> payload = new LinkedHashMap<>();
        String mode = gameModeCombo.getSelectionModel().getSelectedItem();
        String aiProfile = selectedAiProfile();
        String requestMode = "HVM".equals(mode) && "LLM".equals(aiProfile) ? "LLM" : mode;
        List<String> customRoles = customLineupRoles();
        payload.put("sessionId", sessionId());
        payload.put("playerCount", "CUSTOM".equals(requestMode) && !customRoles.isEmpty()
                ? customRoles.size()
                : playerCountSpinner.getValue());
        payload.put("gameMode", requestMode);
        payload.put("randomizeFirstPlayer", randomizeFirstCheck.isSelected());
        if ("HVM".equals(requestMode)) {
            payload.put("aiDifficulty", aiProfile);
        }
        if ("CUSTOM".equals(requestMode) && !customRoles.isEmpty()) {
            payload.put("customLineup", String.join(",", customRoles));
            payload.put("playerRoles", customRoles);
        }
        sendEnvelope("START_SESSION", payload);
    }

    private String selectedAiProfile() {
        String value = aiDifficultyCombo.getSelectionModel().getSelectedItem();
        return value == null || value.isBlank() ? "NORMAL" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void playSelected(String actionType) {
        playActionController.playSelected(actionType);
    }

    private void quickPlay(CardDisplayData card) {
        playActionController.quickPlay(card);
    }

    private String publicCardLabel(String ownerPlayerId, String cardId) {
        if (cardId == null || cardId.isBlank()) {
            return i18n("dialog.unknownCard");
        }
        JsonObject card = findPublicCard(ownerPlayerId, cardId);
        if (card == null) {
            return i18n("dialog.unknownCardWithId", cardId);
        }
        CardDisplayData data = CardDisplayData.fromHandCardJson(card);
        String title = cardTitle(data);
        String color = data.getAssignedColorKey() == null || data.getAssignedColorKey().isBlank()
                ? data.getColorGroup()
                : data.getAssignedColorKey();
        List<String> parts = new ArrayList<>();
        parts.add(title);
        if (color != null && !color.isBlank()
                && ("PROPERTY".equals(data.getKind()) || "WILD".equals(data.getKind()))) {
            parts.add(colorName(color));
        }
        int value = jsonInt(card, "valueM", -1);
        if (value > 0) {
            parts.add(value + "M");
        }
        return String.join(" · ", parts);
    }

    private JsonObject findPublicCard(String ownerPlayerId, String cardId) {
        JsonObject fromHand = findCardInArray(state.latestHandCards, cardId);
        if (fromHand != null) {
            return fromHand;
        }
        if (state.lastStatePayload == null || !state.lastStatePayload.has("players") || !state.lastStatePayload.get("players").isJsonArray()) {
            return null;
        }
        JsonObject exactOwner = ownerPlayerId == null || ownerPlayerId.isBlank()
                ? null
                : findPlayerInState(state.lastStatePayload, ownerPlayerId);
        JsonObject found = findPublicCardInPlayer(exactOwner, cardId);
        if (found != null) {
            return found;
        }
        for (JsonElement el : state.lastStatePayload.getAsJsonArray("players")) {
            if (el.isJsonObject()) {
                found = findPublicCardInPlayer(el.getAsJsonObject(), cardId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private JsonObject findPublicCardInPlayer(JsonObject player, String cardId) {
        if (player == null) {
            return null;
        }
        JsonObject found = findCardInArray(player.getAsJsonArray("propertyZoneCards"), cardId);
        if (found != null) {
            return found;
        }
        found = findCardInArray(player.getAsJsonArray("bankCards"), cardId);
        return found != null ? found : findCardInArray(player.getAsJsonArray("actionZoneCards"), cardId);
    }

    private static JsonObject findCardInArray(JsonArray cards, String cardId) {
        if (cards == null || cardId == null || cardId.isBlank()) {
            return null;
        }
        for (JsonElement el : cards) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject card = el.getAsJsonObject();
            if (cardId.equals(jsonString(card, "id", ""))) {
                return card;
            }
        }
        return null;
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
            showError(localizedBackendMessage(jsonString(payload, "error", ""), i18n("error.authFailed")));
        }
    }

    private void applyStateUpdate(String raw) {
        state.lastStatePayload = payload(raw);
        state.awaitingInitialState = false;
        updatePlayedEvents(state.lastStatePayload);
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
        String signature = HandPanelController.handSignature(cards);
        state.latestHandCards = cards;
        if (!signature.equals(state.latestHandSignature)) {
            state.latestHandSignature = signature;
            rebuildHand();
        }
        updateResponsePanel();
        updateRentPaymentPanel();
    }

    private void applyRoomListResult(String raw) {
        JsonObject payload = payload(raw);
        state.roomRowsByLabel.clear();
        roomListView.getItems().clear();
        if (payload.has("rooms") && payload.get("rooms").isJsonArray()) {
            for (JsonElement el : payload.getAsJsonArray("rooms")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject room = el.getAsJsonObject();
                String label = roomListLabel(room);
                state.roomRowsByLabel.put(label, room);
                roomListView.getItems().add(label);
            }
        }
        if (!roomListView.getItems().isEmpty()) {
            roomListView.getSelectionModel().selectFirst();
        }
    }

    private void applyRoomState(String raw) {
        state.currentLobbyRoom = payload(raw);
        String sid = jsonString(state.currentLobbyRoom, "sessionId", "");
        if (!sid.isBlank()) {
            sessionIdField.setText(sid);
        }
        JsonObject mySeat = findMyLobbySeat(state.currentLobbyRoom);
        if (mySeat != null) {
            String id = jsonString(mySeat, "playerId", "");
            if (!id.isBlank()) {
                playerIdField.setText(id);
            }
        }
        rebuildRoomState(state.currentLobbyRoom);
        updateLobbyControls();
        if (jsonBool(state.currentLobbyRoom, "started", false)) {
            switchToGameView();
        }
    }

    private void applyRoomError(String raw) {
        showError(localizedBackendMessage(jsonString(payload(raw), "error", ""), i18n("lobby.roomError")));
    }

    private void applyPendingOptionsResult(String raw) {
        playActionController.applyPendingOptionsResult(payload(raw));
    }

    private void applyInboundError(String raw) {
        state.pendingOptionsResultHandler = null;
        state.awaitingInitialState = false;
        JsonObject payload = payload(raw);
        String message = jsonString(payload, "message", jsonString(payload, "error", ""));
        showError(localizedBackendMessage(message, i18n("log.error")));
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
        JsonObject p = state.lastStatePayload;
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
            updateCenterNotice(true, i18n("gameOver"), gameOverSummary(p), "game-over");
        } else if (playerId().equals(decision)) {
            tableStatusLabel.setText(decisionTitle(p, true));
            updateCenterNotice(true, decisionTitle(p, true), centerNoticeText(p), "decision");
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
            line = compactEventLine(p);
        }
        eventLineLabel.setText(line);
        connectionLabel.setText(ws.isConnected() ? i18n("status.connected") : i18n("status.disconnected"));
    }

    private String decisionTitle(JsonObject state, boolean self) {
        String kind = safeUpper(jsonString(state, "decisionKind", ""));
        if (self) {
            return switch (kind) {
                case "DRAW" -> i18n("guide.yourTurnDraw");
                case "PLAY", "DISCARD_OVERFLOW" -> i18n("yourDecision");
                case "PAY_OR_JUST_SAY_NO", "JUST_SAY_NO_OR_PASS", "COUNTER_JUST_SAY_NO" -> i18n("guide.yourTurnResponse");
                default -> i18n("yourDecision");
            };
        }
        return i18n("waitingFor", displayNameForPlayer(jsonString(state, "decisionPlayerId", "")));
    }

    private String compactEventLine(JsonObject p) {
        if (p == null) {
            return i18n("brandIntro");
        }
        String text = centerNoticeText(p);
        return text.isBlank() ? i18n("brandIntro") : text.replace('\n', ' ');
    }

    private String gameOverSummary(JsonObject p) {
        String reason = jsonString(p, "forceEndReason", "");
        if (!reason.isBlank()) {
            return i18n("gameOverReason", reason);
        }
        return i18n("gameOver");
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
            String detail = playedActionDetail(p);
            return i18n("notice.playedAction", actor, cardTitle(card), action, detail);
        }
        return "";
    }

    private String playedActionDetail(JsonObject p) {
        if (p == null || !p.has("lastPlayedCard") || !p.get("lastPlayedCard").isJsonObject()) {
            return "";
        }
        CardDisplayData card = CardDisplayData.fromHandCardJson(p.getAsJsonObject("lastPlayedCard"));
        String effect = safeUpper(card.getEffectCode());
        if (effect.isBlank()) {
            return card.getHint();
        }
        return switch (effect) {
            case "PASS_GO" -> i18n("dialog.passGoDetail");
            case "DOUBLE_RENT" -> i18n("dialog.doubleRentOption");
            case "BIRTHDAY" -> i18n("dialog.birthdayDetail");
            case "RENT_WAIVER" -> i18n("action.RENT_WAIVER");
            default -> actionEffectLabel(effect);
        };
    }

    private void updateResponsiveTableLayout() {
        if (lowerTableBox == null) {
            return;
        }
        boolean focusedResponse = state.lastStatePayload != null
                && "WAITING_FOR_RESPONSE".equals(jsonString(state.lastStatePayload, "turnPhase", ""))
                && playerId().equals(jsonString(state.lastStatePayload, "pendingResponsePlayerId", ""));
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

    private String responseActionTitle(JsonObject ctx, String prefix) {
        String nameKey = prefix == null || prefix.isBlank() ? "actionCardName" : prefix + "ActionCardName";
        String codeKey = prefix == null || prefix.isBlank() ? "actionEffectCode" : prefix + "ActionEffectCode";
        String code = jsonString(ctx, codeKey, "");
        String localized = actionEffectLabel(code);
        if (!localized.isBlank() && !localized.startsWith("!action.")) {
            return localized;
        }
        String name = jsonString(ctx, nameKey, "");
        return name.isBlank() ? localized : name;
    }

    private String responsePlayerName(String name, String id) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return id == null || id.isBlank() ? "" : displayNameForPlayer(id);
    }

    private String waitingResponseText() {
        return responsePanelController.waitingResponseText();
    }

    private void rebuildPlayerBoards() {
        opponentsBox.getChildren().clear();
        myBoardBox.getChildren().clear();
        playedCardsPane.getChildren().clear();
        JsonObject p = state.lastStatePayload;
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
        if (!sessionKey.equals(state.playedSessionKey)) {
            clearPlayedEvents();
            state.playedSessionKey = sessionKey;
        }
        if ("INIT".equals(phase)) {
            clearPlayedEvents();
            state.playedSessionKey = sessionKey;
            return;
        }
        if (jsonBool(p, "gameOver", false)) {
            state.playedEvents.clear();
            return;
        }
        if ("TURN_END".equals(phase)) {
            state.playedEvents.clear();
            state.playedTurnKey = playedTurnKey(p);
            state.lastRecordedPlayedSequence = Math.max(state.lastRecordedPlayedSequence, jsonLong(p, "lastPlayedSequence", 0L));
            return;
        }
        String turnKey = playedTurnKey(p);
        if (!turnKey.equals(state.playedTurnKey)) {
            state.playedTurnKey = turnKey;
            state.playedEvents.clear();
        }
        long sequence = jsonLong(p, "lastPlayedSequence", 0L);
        if (sequence <= 0 || !p.has("lastPlayedCard") || !p.get("lastPlayedCard").isJsonObject()) {
            return;
        }
        if (sequence <= state.lastRecordedPlayedSequence) {
            return;
        }
        JsonObject cardJson = p.getAsJsonObject("lastPlayedCard").deepCopy();
        String playerId = jsonString(p, "lastPlayedPlayerId", jsonString(p, "currentPlayerId", ""));
        String actionType = jsonString(p, "lastPlayedActionType", "");
        String summary = jsonString(p, "lastActionSummary", "");
        state.playedEvents.add(new FxClientState.PlayedEvent(sequence, playerId, actionType, cardJson, summary));
        state.lastRecordedPlayedSequence = sequence;
        if (state.playedEvents.size() > 6) {
            state.playedEvents.remove(0);
        }
    }

    private String playedTurnKey(JsonObject p) {
        return jsonString(p, "sessionId", sessionId()) + ":"
                + jsonInt(p, "roundNumber", 1) + ":"
                + jsonString(p, "currentPlayerId", "");
    }

    private void clearPlayedEvents() {
        state.playedEvents.clear();
        state.playedSessionKey = "";
        state.playedTurnKey = "";
        state.lastRecordedPlayedSequence = 0L;
    }

    private void rebuildPlayedCards() {
        if (state.playedEvents.isEmpty()) {
            Label empty = new Label(i18n("playedEmpty"));
            empty.getStyleClass().add("played-empty");
            playedCardsPane.getChildren().add(empty);
            return;
        }
        for (FxClientState.PlayedEvent event : state.playedEvents) {
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
        handPanelController.rebuildHand();
    }

    private void recomputeHandSpacing() {
        handPanelController.recomputeHandSpacing();
    }

    private void updateSelectedPreview() {
        handPanelController.updateSelectedPreview();
    }

    private void updateResponsePanel() {
        responsePanelController.updatePanel();
    }

    private void stopResponseCountdown() {
        responsePanelController.stopCountdown();
    }

    private void updateRentPaymentPanel() {
        rentPaymentPanelController.updatePanel();
    }

    private void rebuildRoomState(JsonObject room) {
        roomMembersPane.getChildren().clear();
        roomSeatsGrid.getChildren().clear();
        state.currentLobbyRoom = room;
        updateLiveCardSubtitle();
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
        updateLiveCardSubtitle();
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
        roleBox.setConverter(localizedValueConverter("seat."));
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
        boolean inRoom = state.currentLobbyRoom != null;
        boolean host = isLobbyHost(state.currentLobbyRoom);
        lobbyPanel.setVisible(inRoom);
        lobbyPanel.setManaged(inRoom);
        createRoomButton.setDisable(inRoom);
        joinRoomButton.setDisable(inRoom);
        leaveRoomButton.setDisable(!inRoom);
        JsonArray seats = state.currentLobbyRoom != null && state.currentLobbyRoom.has("seats")
                && state.currentLobbyRoom.get("seats").isJsonArray()
                ? state.currentLobbyRoom.getAsJsonArray("seats")
                : new JsonArray();
        startRoomButton.setDisable(!host || !canStartLobbyRoom(seats));
        if (!inRoom) {
            roomStatusLabel.setText(i18n("lobby.noRoom"));
        }
    }

    private void syncActionButtons() {
        boolean connected = ws.isConnected();
        boolean responsePending = state.lastStatePayload != null
                && "WAITING_FOR_RESPONSE".equals(jsonString(state.lastStatePayload, "turnPhase", ""));
        boolean myTurn = state.lastStatePayload != null
                && playerId().equals(jsonString(state.lastStatePayload, "currentPlayerId", ""))
                && playerId().equals(jsonString(state.lastStatePayload, "decisionPlayerId", jsonString(state.lastStatePayload, "currentPlayerId", "")));
        String turnPhase = state.lastStatePayload == null ? "" : jsonString(state.lastStatePayload, "turnPhase", "");
        boolean hasCard = state.selectedCard != null;
        boolean money = hasCard && "MONEY".equals(state.selectedCard.getKind());
        boolean action = hasCard && "ACTION".equals(state.selectedCard.getKind());
        boolean property = hasCard && ("PROPERTY".equals(state.selectedCard.getKind()) || "WILD".equals(state.selectedCard.getKind()));
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
        if (!ws.isConnected() || state.lastStatePayload == null || jsonBool(state.lastStatePayload, "gameOver", false)) {
            return;
        }
        String turnPhase = jsonString(state.lastStatePayload, "turnPhase", "");
        String current = jsonString(state.lastStatePayload, "currentPlayerId", "");
        String decision = jsonString(state.lastStatePayload, "decisionPlayerId", current);
        if (!"DRAW".equals(turnPhase) || !playerId().equals(current) || !playerId().equals(decision)) {
            return;
        }
        String key = jsonString(state.lastStatePayload, "sessionId", sessionId()) + ":"
                + jsonLong(state.lastStatePayload, "stateSequence", 0L) + ":"
                + current + ":" + turnPhase;
        if (key.equals(state.lastAutoDrawKey)) {
            return;
        }
        state.lastAutoDrawKey = key;
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
            default -> "LLM".equals(selectedAiProfile()) ? i18n("hint.llm") : i18n("hint.hvm");
        });
    }

    private void updateLiveCardSubtitle() {
        int joined = 0;
        int capacity = 5;
        if (state.currentLobbyRoom != null) {
            JsonArray members = state.currentLobbyRoom.has("members") && state.currentLobbyRoom.get("members").isJsonArray()
                    ? state.currentLobbyRoom.getAsJsonArray("members")
                    : new JsonArray();
            JsonArray seats = state.currentLobbyRoom.has("seats") && state.currentLobbyRoom.get("seats").isJsonArray()
                    ? state.currentLobbyRoom.getAsJsonArray("seats")
                    : new JsonArray();
            joined = members.size();
            capacity = Math.max(2, seats.size());
        }
        liveCardSubtitleLabel.setText(i18n("start.liveSubtitle", joined, capacity));
    }

    private void updateAiCardSubtitle() {
        int count = playerCountSpinner.getValue() == null ? 2 : playerCountSpinner.getValue();
        aiCardSubtitleLabel.setText(i18n("start.aiSubtitle", count, Math.max(1, count - 1)));
    }

    private void rebuildRoomListLabels() {
        if (state.roomRowsByLabel.isEmpty()) {
            return;
        }
        JsonObject selectedRoom = state.roomRowsByLabel.get(roomListView.getSelectionModel().getSelectedItem());
        List<JsonObject> rooms = new ArrayList<>(state.roomRowsByLabel.values());
        state.roomRowsByLabel.clear();
        roomListView.getItems().clear();
        for (JsonObject room : rooms) {
            String label = roomListLabel(room);
            state.roomRowsByLabel.put(label, room);
            roomListView.getItems().add(label);
            if (selectedRoom == room) {
                roomListView.getSelectionModel().select(label);
            }
        }
        if (!roomListView.getItems().isEmpty() && roomListView.getSelectionModel().getSelectedItem() == null) {
            roomListView.getSelectionModel().selectFirst();
        }
    }

    private StringConverter<String> localizedValueConverter(String prefix) {
        return new StringConverter<>() {
            @Override
            public String toString(String value) {
                if (value == null || value.isBlank()) {
                    return "";
                }
                String translated = i18n(prefix + value);
                return translated.startsWith("!" + prefix) ? value : translated;
            }

            @Override
            public String fromString(String value) {
                return value;
            }
        };
    }

    private StringConverter<String> aiDifficultyValueConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(String value) {
                if (value == null || value.isBlank()) {
                    return "";
                }
                return switch (value) {
                    case "EASY" -> "Easy";
                    case "NORMAL" -> "Normal";
                    case "HARD" -> "Hard";
                    case "STRONG" -> "Strong Search";
                    case "LLM" -> "LLM (DeepSeek)";
                    default -> value;
                };
            }

            @Override
            public String fromString(String value) {
                return value;
            }
        };
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
        syncDefaultNickname();
        brandKickerLabel.setText(i18n("brandKicker"));
        appTitleLabel.setText(i18n("app.title"));
        appSubtitleLabel.setText(i18n("app.subtitle"));
        serverLabel.setText(i18n("label.server"));
        languageLabel.setText(i18n("label.language"));
        aiCardKickerLabel.setText(i18n("start.aiKicker"));
        aiCardTitleLabel.setText(i18n("start.aiTitle"));
        updateAiCardSubtitle();
        liveCardKickerLabel.setText(i18n("start.liveKicker"));
        liveCardTitleLabel.setText(i18n("start.liveTitle"));
        updateLiveCardSubtitle();
        startAiButton.setText(i18n("start.aiButton"));
        featureAiTitleLabel.setText(i18n("feature.aiTitle"));
        featureAiTextLabel.setText(i18n("feature.aiText"));
        featurePvpTitleLabel.setText(i18n("feature.pvpTitle"));
        featurePvpTextLabel.setText(i18n("feature.pvpText"));
        featureDemoTitleLabel.setText(i18n("feature.demoTitle"));
        featureDemoTextLabel.setText(i18n("feature.demoText"));
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
        gameModeCombo.setConverter(localizedValueConverter("mode."));
        aiDifficultyCombo.setConverter(aiDifficultyValueConverter());
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
        rebuildRoomListLabels();
        if (state.currentLobbyRoom != null) {
            rebuildRoomState(state.currentLobbyRoom);
        }

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
        if (state.selectedCard == null) {
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
        infoCopyBox.setVisible(infoVisible);
        infoCopyBox.setManaged(infoVisible);
        Button active = switch (infoPanel) {
            case "rules" -> infoRulesButton;
            case "guide" -> infoGuideButton;
            default -> infoIntroButton;
        };
        if (infoVisible) {
            active.getStyleClass().add("active");
        }
        String prefix = switch (infoPanel) {
            case "rules" -> "infoRules";
            case "guide" -> "infoGuide";
            default -> "infoIntro";
        };
        infoTitleLabel.setText(i18n(prefix + "Title"));
        infoLine1Label.setText(i18n(prefix + "1"));
        infoLine2Label.setText(i18n(prefix + "2"));
        infoLine3Label.setText(i18n(prefix + "3"));
        infoLine4Label.setText(i18n(prefix + "4"));
        infoDetailButton.setText(i18n(infoDetailExpanded ? "infoDetailHide" : "infoDetailShow"));
        infoDetailLabel.setText(i18n(prefix + "Detail"));
        infoDetailScroll.setVisible(infoDetailExpanded);
        infoDetailScroll.setManaged(infoDetailExpanded);
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
        connectionController.refreshButtons();
    }

    private void runWhenConnected(Runnable action) {
        commandGateway.runWhenConnected(action);
    }

    private void sendEnvelope(String type, Map<String, Object> payload) {
        commandGateway.sendEnvelope(type, payload);
    }

    private Map<String, Object> scopedPayload() {
        return commandGateway.scopedPayload();
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

    private boolean canStartLobbyRoom(JsonArray seats) {
        return activeSeatCount(seats) >= 2 && humanSeatCount(seats) >= 1;
    }

    private int humanSeatCount(JsonArray seats) {
        int count = 0;
        if (seats == null) {
            return 0;
        }
        for (JsonElement el : seats) {
            if (el.isJsonObject() && "human".equals(jsonString(el.getAsJsonObject(), "role", "empty"))) {
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
        JsonObject player = findPlayerInState(state.lastStatePayload, id);
        return player == null ? id : jsonString(player, "displayName", id);
    }

    private boolean needsOverflowDiscard() {
        return state.lastStatePayload != null
                && playerId().equals(jsonString(state.lastStatePayload, "currentPlayerId", ""))
                && jsonInt(state.lastStatePayload, "overflowDiscardCount", 0) > 0;
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

    private String actionEffectLabel(String code) {
        String normalized = safeUpper(code);
        String value = i18n("action." + normalized);
        return value.startsWith("!action.") ? normalized : value;
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

    private String localizedBackendMessage(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        if (!I18n.isChinese() && containsHan(message)) {
            return fallback;
        }
        return message;
    }

    private static boolean containsHan(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private void clearError() {
        setupErrorLabel.setText("");
        setupErrorLabel.setVisible(false);
        setupErrorLabel.setManaged(false);
        gameErrorLabel.setText("");
        gameErrorLabel.setVisible(false);
        gameErrorLabel.setManaged(false);
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
        String fallback = i18n("default.nickname");
        nicknameField.setText(fallback);
        return fallback;
    }

    private void syncDefaultNickname() {
        if (nicknameField == null) {
            return;
        }
        String value = nicknameField.getText() == null ? "" : nicknameField.getText().trim();
        if (value.isBlank() || "玩家".equals(value) || "Player".equals(value)) {
            nicknameField.setText(i18n("default.nickname"));
        }
    }

    private String i18n(String key, Object... args) {
        return I18n.get(key, args);
    }
}
