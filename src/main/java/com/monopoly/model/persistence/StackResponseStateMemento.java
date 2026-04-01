package com.monopoly.model.persistence;

import com.monopoly.model.StackResponseState;

/**
 * {@link StackResponseState} 的快照。
 */
public final class StackResponseStateMemento {

    private String role;
    private String awaitingPlayerId;
    private long deadlineEpochMs;

    public static StackResponseStateMemento fromState(StackResponseState s) {
        if (s == null) {
            return null;
        }
        StackResponseStateMemento m = new StackResponseStateMemento();
        m.role = s.getRole().name();
        m.awaitingPlayerId = s.getAwaitingPlayerId();
        m.deadlineEpochMs = s.getDeadlineEpochMs();
        return m;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getAwaitingPlayerId() {
        return awaitingPlayerId;
    }

    public void setAwaitingPlayerId(String awaitingPlayerId) {
        this.awaitingPlayerId = awaitingPlayerId;
    }

    public long getDeadlineEpochMs() {
        return deadlineEpochMs;
    }

    public void setDeadlineEpochMs(long deadlineEpochMs) {
        this.deadlineEpochMs = deadlineEpochMs;
    }
}
