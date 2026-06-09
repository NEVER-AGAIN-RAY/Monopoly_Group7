package com.monopoly.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.fx.presentation.CardDisplayData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Mutable JavaFX client state kept outside the FXML controller shell. */
final class FxClientState {

    final Set<String> selectedPaymentIds = new HashSet<>();
    final List<PlayedEvent> playedEvents = new ArrayList<>();

    JsonObject lastStatePayload;
    JsonArray latestHandCards = new JsonArray();
    String latestHandSignature = "";
    CardDisplayData selectedCard;
    Consumer<JsonObject> pendingOptionsResultHandler;
    Runnable postConnectAction;
    JsonObject demoRoomState;
    boolean joinedDemoRoom;
    int pendingRentPaymentM;
    String lastAutoDrawKey = "";
    String playedSessionKey = "";
    String playedTurnKey = "";
    long lastRecordedPlayedSequence;
    boolean awaitingInitialState;

    void clearGameState() {
        lastStatePayload = null;
        latestHandCards = new JsonArray();
        latestHandSignature = "";
        selectedCard = null;
        pendingOptionsResultHandler = null;
        awaitingInitialState = false;
        clearPlayedEvents();
    }

    void clearDemoRoomState() {
        demoRoomState = null;
        joinedDemoRoom = false;
    }

    void clearPlayedEvents() {
        playedEvents.clear();
        playedSessionKey = "";
        playedTurnKey = "";
        lastRecordedPlayedSequence = 0L;
    }

    record PlayedEvent(long sequence, String playerId, String actionType, JsonObject card, String summary) {
    }
}
