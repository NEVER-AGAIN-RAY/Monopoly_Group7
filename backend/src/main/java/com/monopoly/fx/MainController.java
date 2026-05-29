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
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Toggle;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Window;

import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
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
 * Main match controller for the desktop client: hand cards, player boards,
 * guided play choices, and turn guidance.
 */
public class MainController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final FxWebSocketClient ws = new FxWebSocketClient();
    private final javafx.scene.control.ToggleGroup handToggleGroup = new javafx.scene.control.ToggleGroup();

    private JsonObject lastStatePayload;
    private CardDisplayData selectedCard;
    private int pendingRentPaymentM;
    /** Whether a successful connection should immediately authenticate and start the session. */
    private boolean autoStartAfterConnect;
    /** Pending lobby action to continue after a connection is established. */
    private Runnable postConnectAction;
    /** Timeout guard for quick-start connection attempts. */
    private PauseTransition connectionTimeout;
    /** FX-thread callback waiting for ACTION_OPTIONS_RESULT or PLAY_OPTIONS_RESULT. */
    private Consumer<JsonObject> pendingOptionsResultHandler;
    private JsonObject currentLobbyRoom;
    private final Map<String, JsonObject> roomRowsByLabel = new LinkedHashMap<>();
    private JsonArray latestHandCards = new JsonArray();
    private Timeline responseCountdownTimer;

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
    private HBox customLineupBox;
    @FXML
    private Label customLineupLabel;
    @FXML
    private ComboBox<String> customLineupCombo;
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
    private TitledPane lobbyPane;
    @FXML
    private Label nicknameLabel;
    @FXML
    private TextField nicknameField;
    @FXML
    private Button createRoomButton;
    @FXML
    private Button joinRoomButton;
    @FXML
    private Button refreshRoomsButton;
    @FXML
    private Label roomListLabel;
    @FXML
    private ListView<String> roomListView;
    @FXML
    private Label waitingRoomLabel;
    @FXML
    private Label roomStatusLabel;
    @FXML
    private Button startRoomButton;
    @FXML
    private Button leaveRoomButton;
    @FXML
    private FlowPane roomMembersPane;
    @FXML
    private GridPane roomSeatsGrid;
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
    private StackPane quickStartCard;
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
    private VBox responseBox;
    @FXML
    private Label responseTitleLabel;
    @FXML
    private Label responseCountdownLabel;
    @FXML
    private Label responseContextLabel;
    @FXML
    private Button responseJsnButton;
    @FXML
    private Button responsePassButton;
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
    private WebView rulesWebView;
    @FXML
    private javafx.scene.control.TabPane rootTabPane;
    @FXML
    private javafx.scene.control.Tab gameTab;
    @FXML
    private javafx.scene.control.Tab rulesTab;
    @FXML
    private javafx.scene.control.Tab guideTab;
    @FXML
    private WebView guideWebView;
    @FXML
    private Button showGuideButton;
    @FXML
    private javafx.scene.control.Tab debugTab;

    @FXML
    private void initialize() {
        wsUrlField.setText("ws://localhost:8025/ws");
        sessionIdField.setText("demo-pvp");
        nicknameField.setText("玩家");
        playerCountSpinner.setValueFactory(new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(2, 5, 2));
        gameModeCombo.getItems().setAll("HVM", "PVP", "LLM", "CUSTOM");
        gameModeCombo.getSelectionModel().selectFirst();

        aiDifficultyCombo.getItems().setAll("EASY", "NORMAL", "HARD", "STRONG");
        aiDifficultyCombo.getSelectionModel().select("NORMAL");
        customLineupCombo.getItems().setAll(
                "human,strong",
                "human,human,strong,strong",
                "human,human,lookahead,lookahead",
                "human,human,hard,lookahead",
                "human,human,llm,llm",
                "human,human,hard,llm",
                "hard,hard,lookahead,lookahead",
                "human,llm,llm,llm",
                "hard,hard,llm,llm",
                "human,human,hard,hard");
        customLineupCombo.setEditable(true);
        customLineupCombo.getSelectionModel().selectFirst();
        customLineupCombo.valueProperty().addListener((obs, prev, val) -> syncCustomLineupCount());
        customLineupCombo.getEditor().textProperty().addListener((obs, prev, val) -> syncCustomLineupCount());

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
        roomListView.getSelectionModel().selectedItemProperty().addListener((obs, prev, label) -> {
            JsonObject row = roomRowsByLabel.get(label);
            if (row != null) {
                sessionIdField.setText(jsonString(row, "sessionId", sessionIdField.getText()));
            }
        });

        randomizeFirstCheck.setSelected(false);
        summaryLabel.setText("");
        hideError();
        updateLobbyControls();
        syncWizardButtons();
        refreshButtons();
        applyI18n();
    }

    @FXML
    private void onShowGuide() {
        rootTabPane.getSelectionModel().select(guideTab);
    }

    private static void loadHtmlToWebView(WebView webView, String html) {
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
        boolean custom = "CUSTOM".equals(mode);
        aiDifficultyBox.setVisible(hvm);
        aiDifficultyBox.setManaged(hvm);
        customLineupBox.setVisible(custom);
        customLineupBox.setManaged(custom);
        if (hvm) {
            playerIdField.setText("human-1");
            modeHintLabel.setText(I18n.get("hint.hvm"));
        } else if ("LLM".equals(mode)) {
            playerIdField.setText("human-1");
            modeHintLabel.setText(I18n.get("hint.llm"));
        } else if (custom) {
            syncCustomLineupCount();
            playerIdField.setText("pvp-1");
            modeHintLabel.setText(I18n.get("hint.custom"));
        } else {
            playerIdField.setText("pvp-1");
            modeHintLabel.setText(I18n.get("hint.pvp"));
        }
    }

    private void syncCustomLineupCount() {
        String mode = gameModeCombo.getSelectionModel().getSelectedItem();
        if (!"CUSTOM".equals(mode) || playerCountSpinner.getValueFactory() == null) {
            return;
        }
        int size = customLineupRoles().size();
        if (size >= 2 && size <= 5 && playerCountSpinner.getValue() != size) {
            playerCountSpinner.getValueFactory().setValue(size);
        }
    }

    private List<String> customLineupRoles() {
        String raw = customLineupText();
        if (raw.isBlank()) {
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

    private String customLineupText() {
        if (customLineupCombo == null) {
            return "";
        }
        if (customLineupCombo.isEditable() && customLineupCombo.getEditor() != null) {
            String editor = customLineupCombo.getEditor().getText();
            if (editor != null && !editor.isBlank()) {
                return editor;
            }
        }
        String value = customLineupCombo.getValue();
        return value == null ? "" : value;
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
        customLineupLabel.setText(I18n.get("label.customLineup"));
        languageLabel.setText(I18n.get("label.language"));
        startGameButton.setText(I18n.get("btn.startGame"));
        lobbyPane.setText(I18n.get("lobby.title"));
        nicknameLabel.setText(I18n.get("label.nickname"));
        createRoomButton.setText(I18n.get("btn.createRoom"));
        joinRoomButton.setText(I18n.get("btn.joinRoom"));
        refreshRoomsButton.setText(I18n.get("btn.refreshRooms"));
        roomListLabel.setText(I18n.get("lobby.roomList"));
        waitingRoomLabel.setText(I18n.get("lobby.waitingRoom"));
        startRoomButton.setText(I18n.get("btn.startRoom"));
        leaveRoomButton.setText(I18n.get("btn.leaveRoom"));
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
        responseJsnButton.setText(I18n.get("btn.playJsn"));
        responsePassButton.setText(I18n.get("btn.passResponse"));
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
            // Already connected, so authenticate and start immediately.
            onAuth();
            onStartSession();
            switchToGameView();
        } else {
            // Connect first; onOpen will finish auth and session start.
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
                    if (postConnectAction != null) {
                        Runnable action = postConnectAction;
                        postConnectAction = null;
                        action.run();
                    } else {
                        onRefreshRooms();
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
                    postConnectAction = null;
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
                    postConnectAction = null;
                    switchToPreGameView();
                    refreshButtons();
                    updateLobbyControls();
                    updateTurnGuide();
                });
            }
        });
        connectionTimeout = new PauseTransition(Duration.seconds(8));
        connectionTimeout.setOnFinished(e -> {
            if (!ws.isConnected() && (autoStartAfterConnect || postConnectAction != null)) {
                autoStartAfterConnect = false;
                postConnectAction = null;
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
        postConnectAction = null;
        currentLobbyRoom = null;
        roomRowsByLabel.clear();
        roomListView.getItems().clear();
        switchToPreGameView();
        refreshButtons();
        updateLobbyControls();
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
        } else if ("CUSTOM".equals(mode)) {
            List<String> roles = customLineupRoles();
            if (!roles.isEmpty()) {
                p.put("playerCount", roles.size());
                p.put("customLineup", String.join(",", roles));
                p.put("playerRoles", roles);
            }
        }
        sendEnvelope("START_SESSION", p);
    }

    @FXML
    private void onRefreshRooms() {
        runWhenConnected(() -> sendEnvelope("ROOM_LIST", scopedPayload()));
    }

    @FXML
    private void onCreateRoom() {
        runWhenConnected(() -> {
            Map<String, Object> p = scopedPayload();
            p.put("nickname", nickname());
            sendEnvelope("CREATE_ROOM", p);
        });
    }

    @FXML
    private void onJoinRoom() {
        String selected = roomListView.getSelectionModel().getSelectedItem();
        JsonObject row = roomRowsByLabel.get(selected);
        if (row != null) {
            sessionIdField.setText(jsonString(row, "sessionId", sessionIdField.getText().trim()));
        }
        runWhenConnected(() -> {
            Map<String, Object> p = scopedPayload();
            p.put("nickname", nickname());
            sendEnvelope("JOIN_ROOM", p);
        });
    }

    @FXML
    private void onStartRoom() {
        Map<String, Object> p = scopedPayload();
        p.put("randomizeFirstPlayer", randomizeFirstCheck.isSelected());
        sendEnvelope("START_ROOM", p);
    }

    @FXML
    private void onLeaveRoom() {
        sendEnvelope("LEAVE_ROOM", scopedPayload());
        currentLobbyRoom = null;
        rebuildRoomState(null);
        updateLobbyControls();
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
     * Ask the server for PLAY_OPTIONS first, let the user choose one row, then
     * send PLAY. Both wizard buttons and the advanced play form use this path.
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

    private void runWhenConnected(Runnable action) {
        if (ws.isConnected()) {
            action.run();
            return;
        }
        postConnectAction = action;
        onConnect();
    }

    private Map<String, Object> scopedPayload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sessionId", sessionIdField.getText().trim());
        return p;
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

    /** Debug path: build PLAY directly from the form without asking PLAY_OPTIONS first. */
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
        stopResponseCountdown();
        ws.closeQuietly();
    }

    private void handleInbound(String raw) {
        appendTraffic("→ " + raw);
        String type = WsJson.typeOf(raw);
        switch (type) {
            case "STATE_UPDATE" -> applyStateUpdate(raw);
            case "MY_HAND" -> applyMyHand(raw);
            case "AUTH_RESULT" -> applyAuthResult(raw);
            case "ROOM_LIST_RESULT" -> applyRoomListResult(raw);
            case "ROOM_STATE" -> applyRoomState(raw);
            case "ROOM_ERROR" -> applyRoomError(raw);
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

    private void applyRoomListResult(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject p = root.getAsJsonObject("payload");
            roomRowsByLabel.clear();
            roomListView.getItems().clear();
            if (p == null || !p.has("rooms") || !p.get("rooms").isJsonArray()) {
                return;
            }
            for (JsonElement el : p.getAsJsonArray("rooms")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject room = el.getAsJsonObject();
                String label = roomListLabel(room);
                roomRowsByLabel.put(label, room);
                roomListView.getItems().add(label);
            }
            if (!roomListView.getItems().isEmpty() && roomListView.getSelectionModel().isEmpty()) {
                roomListView.getSelectionModel().selectFirst();
            }
            updateLobbyControls();
        } catch (RuntimeException ex) {
            showError(I18n.get("msg.stateParseError", ex.getMessage()));
        }
    }

    private void applyRoomState(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject p = root.getAsJsonObject("payload");
            currentLobbyRoom = p;
            String sid = jsonString(p, "sessionId", "");
            if (!sid.isBlank()) {
                sessionIdField.setText(sid);
            }
            rebuildRoomState(p);
            boolean started = p != null && p.has("started") && p.get("started").getAsBoolean();
            if (started) {
                JsonObject mySeat = findMyLobbySeat(p);
                if (mySeat != null) {
                    String playerId = jsonString(mySeat, "playerId", "");
                    if (!playerId.isBlank()) {
                        playerIdField.setText(playerId);
                    }
                }
                switchToGameView();
            } else if (lobbyPane != null) {
                lobbyPane.setExpanded(true);
            }
            updateLobbyControls();
        } catch (RuntimeException ex) {
            showError(I18n.get("msg.stateParseError", ex.getMessage()));
        }
    }

    private void applyRoomError(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject p = root.getAsJsonObject("payload");
            showError(jsonString(p, "error", I18n.get("lobby.roomError")));
        } catch (RuntimeException ignored) {
            showError(I18n.get("lobby.roomError"));
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
            updateResponsePanel(p);
            updateRentPaymentPanel(p);
            updateTurnGuide();
        } catch (RuntimeException ex) {
            summaryLabel.setText(I18n.get("msg.stateParseError", ex.getMessage()));
        }
    }

    private void updateResponsePanel(JsonObject p) {
        if (responseBox == null) {
            return;
        }
        String local = playerIdField.getText().trim();
        boolean show = "WAITING_FOR_RESPONSE".equals(jsonString(p, "turnPhase", ""))
                && local.equals(jsonString(p, "pendingResponsePlayerId", ""));
        responseBox.setVisible(show);
        responseBox.setManaged(show);
        if (!show) {
            stopResponseCountdown();
            return;
        }
        int due = jsonInt(p, "pendingPaymentAmountM", 0);
        String role = jsonString(p, "pendingResponseRole", "");
        if ("LANDLORD_COUNTER".equals(role)) {
            responseTitleLabel.setText(I18n.get("response.counterTitle"));
        } else if (due > 0) {
            responseTitleLabel.setText(I18n.get("response.paymentTitle", due));
        } else {
            responseTitleLabel.setText(I18n.get("response.targetedTitle"));
        }
        responseContextLabel.setText(responseContextText(
                p.has("pendingResponseContext") && p.get("pendingResponseContext").isJsonObject()
                        ? p.getAsJsonObject("pendingResponseContext")
                        : null,
                due));
        responseJsnButton.setDisable(findJustSayNoCard() == null);
        responsePassButton.setText(due > 0 ? I18n.get("btn.autoPay") : I18n.get("btn.passResponse"));
        startResponseCountdown(jsonLong(p, "responseDeadlineEpochMs", 0L));
    }

    private String responseContextText(JsonObject ctx, int due) {
        if (ctx == null) {
            return due > 0 ? I18n.get("response.paymentBody") : I18n.get("response.defaultBody");
        }
        String action = responseActionTitle(ctx, "");
        String actor = responsePlayerName(jsonString(ctx, "actorName", ""), jsonString(ctx, "actorPlayerId", ""));
        String target = responsePlayerName(jsonString(ctx, "targetName", ""), jsonString(ctx, "targetPlayerId", ""));
        String color = jsonString(ctx, "colorKey", "");
        StringBuilder out = new StringBuilder();
        out.append(I18n.get("response.actionLine", action));
        if (!actor.isBlank() || !target.isBlank()) {
            out.append("\n").append(I18n.get("response.fromTo",
                    actor.isBlank() ? I18n.get("player.fallback") : actor,
                    target.isBlank() ? I18n.get("player.fallback") : target));
        }
        if (!color.isBlank()) {
            out.append(" · ").append(colorName(color));
        }
        int amount = jsonInt(ctx, "amountDueM", due);
        if (amount > 0) {
            out.append(" · ").append(amount).append("M");
        }
        String original = responseActionTitle(ctx, "original");
        if (!original.isBlank()) {
            out.append("\n").append(I18n.get("response.originalLine", original));
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
        String code = jsonString(ctx, codeKey, "");
        return actionEffectLabel(code);
    }

    private String responsePlayerName(String name, String playerId) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (playerId == null || playerId.isBlank()) {
            return "";
        }
        JsonObject player = findPlayerInState(lastStatePayload, playerId);
        return player == null ? playerId : jsonString(player, "displayName", playerId);
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

    private void startResponseCountdown(long deadlineMs) {
        stopResponseCountdown();
        if (deadlineMs <= 0L) {
            responseCountdownLabel.setText("");
            return;
        }
        responseCountdownTimer = new Timeline(new KeyFrame(Duration.millis(250), e -> {
            long leftMs = Math.max(0L, deadlineMs - System.currentTimeMillis());
            responseCountdownLabel.setText(I18n.get("response.countdown", (leftMs + 999L) / 1000L));
        }));
        responseCountdownTimer.setCycleCount(Timeline.INDEFINITE);
        responseCountdownTimer.play();
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

    private String roomListLabel(JsonObject room) {
        String session = jsonString(room, "sessionId", I18n.get("lobby.unnamedRoom"));
        int seats = jsonInt(room, "seatCount", 0);
        int humans = jsonInt(room, "humanSeats", 0);
        int connected = jsonInt(room, "connectedPlayers", 0);
        boolean started = room != null && room.has("started") && room.get("started").getAsBoolean();
        String host = jsonString(room, "hostNickname", "");
        String state = started ? I18n.get("lobby.started") : I18n.get("lobby.waiting");
        String suffix = host.isBlank() ? "" : " · " + I18n.get("lobby.host", host);
        return I18n.get("lobby.roomRow", session, connected, humans, seats, state) + suffix;
    }

    private void rebuildRoomState(JsonObject room) {
        roomMembersPane.getChildren().clear();
        roomSeatsGrid.getChildren().clear();
        if (room == null) {
            roomStatusLabel.setText(I18n.get("lobby.noRoom"));
            return;
        }
        String session = jsonString(room, "sessionId", "");
        JsonArray members = room.has("members") && room.get("members").isJsonArray()
                ? room.getAsJsonArray("members")
                : new JsonArray();
        JsonArray seats = room.has("seats") && room.get("seats").isJsonArray()
                ? room.getAsJsonArray("seats")
                : new JsonArray();
        roomStatusLabel.setText(I18n.get("lobby.roomStatus", session, members.size(), activeSeatCount(seats)));
        for (JsonElement el : members) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject member = el.getAsJsonObject();
            String text = jsonString(member, "nickname", "");
            if (member.has("host") && member.get("host").getAsBoolean()) {
                text += " · " + I18n.get("lobby.hostShort");
            }
            Label chip = new Label(text);
            chip.getStyleClass().add("member-chip");
            roomMembersPane.getChildren().add(chip);
        }
        List<String> memberNames = memberNames(room);
        for (int i = 0; i < seats.size(); i++) {
            if (!seats.get(i).isJsonObject()) {
                continue;
            }
            JsonObject seat = seats.get(i).getAsJsonObject();
            roomSeatsGrid.add(seatCard(room, seat, memberNames), i % 3, i / 3);
        }
    }

    private VBox seatCard(JsonObject room, JsonObject seat, List<String> memberNames) {
        int index = jsonInt(seat, "index", 0);
        String role = jsonString(seat, "role", "empty");
        String nickname = jsonString(seat, "nickname", "");
        VBox box = new VBox(6);
        box.getStyleClass().addAll("seat-card", "seat-" + role);
        Label title = new Label(I18n.get("lobby.seatTitle", index + 1));
        title.getStyleClass().add("seat-title");
        ComboBox<String> roleBox = new ComboBox<>();
        roleBox.getItems().setAll("empty", "human", "hard", "strong", "llm", "student");
        roleBox.getSelectionModel().select(role);
        roleBox.setDisable(!isLobbyHost(room));
        roleBox.valueProperty().addListener((obs, oldRole, newRole) -> {
            if (newRole == null || newRole.equals(oldRole)) {
                return;
            }
            sendRoomSeat(index, newRole, "human".equals(newRole) ? defaultSeatNickname(seat, memberNames) : "");
        });
        ComboBox<String> memberBox = new ComboBox<>();
        memberBox.getItems().setAll(memberNames);
        if (!nickname.isBlank()) {
            memberBox.getSelectionModel().select(nickname);
        }
        memberBox.setDisable(!isLobbyHost(room) || !"human".equals(role));
        memberBox.valueProperty().addListener((obs, oldName, newName) -> {
            if (newName == null || newName.equals(oldName)) {
                return;
            }
            sendRoomSeat(index, "human", newName);
        });
        Label help = new Label(seatSummary(role, nickname));
        help.getStyleClass().add("seat-help");
        help.setWrapText(true);
        box.getChildren().addAll(title, roleBox);
        if ("human".equals(role)) {
            box.getChildren().add(memberBox);
        }
        box.getChildren().add(help);
        return box;
    }

    private void sendRoomSeat(int index, String role, String nickname) {
        Map<String, Object> p = scopedPayload();
        p.put("seatIndex", index);
        p.put("role", role);
        if (nickname != null && !nickname.isBlank()) {
            p.put("nickname", nickname);
        }
        sendEnvelope("ROOM_SET_SEAT", p);
    }

    private boolean isLobbyHost(JsonObject room) {
        if (room == null || !room.has("members") || !room.get("members").isJsonArray()) {
            return false;
        }
        String me = nickname();
        for (JsonElement el : room.getAsJsonArray("members")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject member = el.getAsJsonObject();
            if (me.equals(jsonString(member, "nickname", ""))
                    && member.has("host") && member.get("host").getAsBoolean()) {
                return true;
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
            if (!el.isJsonObject()) {
                continue;
            }
            String name = jsonString(el.getAsJsonObject(), "nickname", "");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private String defaultSeatNickname(JsonObject seat, List<String> memberNames) {
        String current = jsonString(seat, "nickname", "");
        if (!current.isBlank()) {
            return current;
        }
        return memberNames.isEmpty() ? nickname() : memberNames.get(0);
    }

    private int activeSeatCount(JsonArray seats) {
        int count = 0;
        for (JsonElement el : seats) {
            if (el.isJsonObject() && !"empty".equals(jsonString(el.getAsJsonObject(), "role", "empty"))) {
                count++;
            }
        }
        return count;
    }

    private String seatSummary(String role, String nickname) {
        return switch (role) {
            case "human" -> nickname == null || nickname.isBlank()
                    ? I18n.get("lobby.humanSeatEmpty")
                    : I18n.get("lobby.humanSeat", nickname);
            case "hard" -> I18n.get("lobby.hardSeat");
            case "strong" -> I18n.get("lobby.strongSeat");
            case "llm" -> I18n.get("lobby.llmSeat");
            case "student" -> I18n.get("lobby.studentSeat");
            default -> I18n.get("lobby.emptySeat");
        };
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
            if ("human".equals(jsonString(seat, "role", ""))
                    && me.equals(jsonString(seat, "nickname", ""))) {
                return seat;
            }
        }
        return null;
    }

    private void updateLobbyControls() {
        if (createRoomButton == null) {
            return;
        }
        boolean inRoom = currentLobbyRoom != null;
        boolean host = isLobbyHost(currentLobbyRoom);
        createRoomButton.setDisable(inRoom);
        joinRoomButton.setDisable(inRoom);
        startRoomButton.setDisable(!host || currentLobbyRoom == null
                || activeSeatCount(currentLobbyRoom.has("seats") && currentLobbyRoom.get("seats").isJsonArray()
                ? currentLobbyRoom.getAsJsonArray("seats") : new JsonArray()) < 2);
        leaveRoomButton.setDisable(!inRoom);
        if (!inRoom) {
            roomStatusLabel.setText(I18n.get("lobby.noRoom"));
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

    @FXML
    private void onResponsePass() {
        sendEnvelope("PLAY", WsJson.playResponsePass(playerIdField.getText().trim(), null));
    }

    @FXML
    private void onPlayJustSayNo() {
        JsonObject card = findJustSayNoCard();
        if (card == null) {
            showError(I18n.get("error.noJsn"));
            return;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("actionType", "ACTION");
        p.put("actingPlayerId", playerIdField.getText().trim());
        p.put("cardId", jsonString(card, "id", ""));
        sendEnvelope("PLAY", p);
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
                latestHandCards = new JsonArray();
                selectedCardLabel.setText(I18n.get("msg.noHand"));
                return;
            }
            JsonArray cards = payload.getAsJsonArray("cards");
            latestHandCards = cards;
            int visibleIndex = 0;
            int totalCards = cards.size();
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
                double angle = Math.max(-7.0, Math.min(7.0, (visibleIndex - (totalCards - 1) / 2.0) * 1.15));
                cv.setRotate(angle);
                cv.setTranslateY(Math.abs(angle) * 0.55);
                handStrip.getChildren().add(cv);
                visibleIndex++;
            }
            handStrip.layout();
            handScroll.layout();
            if (lastStatePayload != null) {
                updateResponsePanel(lastStatePayload);
            }
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

    private static long jsonLong(JsonObject o, String key, long def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return def;
        }
        try {
            return o.get(key).getAsLong();
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static String actionEffectLabel(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
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

    private static String colorName(String colorKey) {
        String key = colorKey == null ? "" : colorKey.trim().toUpperCase(Locale.ROOT);
        String value = I18n.get("color." + key);
        return value.startsWith("!color.") ? key : value;
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

    /** Switch into the in-game layout and hide the quick-start card. */
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

    /** Return to the pre-game layout and hide the board/hand area. */
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
