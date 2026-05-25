package com.monopoly.model.effects;

/**
 * Who may respond with Just Say No or pass.
 */
public final class StackResponseState {

    public enum Role {
        /** Tenant may play Just Say No or pass and pay. */
        TENANT,
        /** Landlord may counter the tenant's Just Say No. */
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
