package com.monopoly.model.effects;

/**
 * 行动卡效果执行结果：统一返回给控制器，用于广播提示与合法性汇报。
 */
public final class ActionEffectResult {

    public enum Status {
        SUCCESS,
        FAILED,
        COUNTERED
    }

    private final Status status;
    private final String message;

    private ActionEffectResult(Status status, String message) {
        this.status = status;
        this.message = message;
    }

    public static ActionEffectResult success(String message) {
        return new ActionEffectResult(Status.SUCCESS, message);
    }

    public static ActionEffectResult failed(String message) {
        return new ActionEffectResult(Status.FAILED, message);
    }

    public static ActionEffectResult countered(String message) {
        return new ActionEffectResult(Status.COUNTERED, message);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
