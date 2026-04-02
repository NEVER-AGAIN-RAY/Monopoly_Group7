package com.monopoly.controller;

/**
 * 协议级错误码常量与校验异常，从 {@link GameController} 抽出以单一职责化。
 * <p>
 * 网络层在 {@code PLAY} 分支捕获 {@link ProtocolValidationException} 后可直接取 {@code code} 回传客户端。
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
