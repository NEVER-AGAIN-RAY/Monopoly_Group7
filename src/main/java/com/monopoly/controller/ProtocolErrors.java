package com.monopoly.controller;

/**
 * WebSocket error codes; GameServer maps ProtocolValidationException to ERROR envelope.
 */
public final class ProtocolErrors {

    private ProtocolErrors() {
    }

    public static final String ERR_PLAY_REQUEST_EMPTY = "PLAY_REQUEST_EMPTY";
    public static final String ERR_PLAY_ACTION_TYPE_REQUIRED = "PLAY_ACTION_TYPE_REQUIRED";
    public static final String ERR_PLAY_ACTION_TYPE_INVALID = "PLAY_ACTION_TYPE_INVALID";
    public static final String ERR_PLAY_CARD_SELECTOR_REQUIRED = "PLAY_CARD_SELECTOR_REQUIRED";
    public static final String ERR_PLAY_HAND_INDEX_INVALID = "PLAY_HAND_INDEX_INVALID";
    public static final String ERR_PLAY_ACTING_PLAYER_REQUIRED = "PLAY_ACTING_PLAYER_REQUIRED";

    public static final class ProtocolValidationException extends IllegalArgumentException {
        private final String code;

        public ProtocolValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
