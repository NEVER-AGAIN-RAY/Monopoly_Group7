package com.monopoly.model.effects;

/**
 * Who may respond with Just Say No or pass.
 */
public final class StackResponseState {

    public enum Role {
        /** 被收租方是否免租 */
        TENANT,
        /** 收租方是否反制对方的免租 */
        LANDLORD_COUNTER
    }

    private final Role role;
    private final String awaitingPlayerId;
    private final long deadlineEpochMs;

    public StackResponseState(Role role, String awaitingPlayerId, long deadlineEpochMs) {
        this.role = role;
        this.awaitingPlayerId = awaitingPlayerId;
        this.deadlineEpochMs = deadlineEpochMs;
    }

    public Role getRole() {
        return role;
    }

    public String getAwaitingPlayerId() {
        return awaitingPlayerId;
    }

    public long getDeadlineEpochMs() {
        return deadlineEpochMs;
    }
}
