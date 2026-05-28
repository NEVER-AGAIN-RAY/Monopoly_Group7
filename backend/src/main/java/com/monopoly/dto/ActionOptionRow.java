package com.monopoly.dto;

/**
 * One selectable row in ACTION_OPTIONS_RESULT.
 */
public final class ActionOptionRow {

    private String labelZh;
    private String targetPlayerId;
    private String targetColorKey;
    private String targetCardId;
    private String actorCardId;
    private String targetZone;
    /** When true, the client should omit targetPlayerId and let the server walk opponents in turn order. */
    private boolean allOtherPlayers;

    public ActionOptionRow() {
    }

    public ActionOptionRow(
            String labelZh,
            String targetPlayerId,
            String targetColorKey,
            String targetCardId,
            String actorCardId,
            String targetZone) {
        this(labelZh, targetPlayerId, targetColorKey, targetCardId, actorCardId, targetZone, false);
    }

    public ActionOptionRow(
            String labelZh,
            String targetPlayerId,
            String targetColorKey,
            String targetCardId,
            String actorCardId,
            String targetZone,
            boolean allOtherPlayers) {
        this.labelZh = labelZh;
        this.targetPlayerId = targetPlayerId;
        this.targetColorKey = targetColorKey;
        this.targetCardId = targetCardId;
        this.actorCardId = actorCardId;
        this.targetZone = targetZone;
        this.allOtherPlayers = allOtherPlayers;
    }

    public String getLabelZh() {
        return labelZh;
    }

    public void setLabelZh(String labelZh) {
        this.labelZh = labelZh;
    }

    public String getTargetPlayerId() {
        return targetPlayerId;
    }

    public void setTargetPlayerId(String targetPlayerId) {
        this.targetPlayerId = targetPlayerId;
    }

    public String getTargetColorKey() {
        return targetColorKey;
    }

    public void setTargetColorKey(String targetColorKey) {
        this.targetColorKey = targetColorKey;
    }

    public String getTargetCardId() {
        return targetCardId;
    }

    public void setTargetCardId(String targetCardId) {
        this.targetCardId = targetCardId;
    }

    public String getActorCardId() {
        return actorCardId;
    }

    public void setActorCardId(String actorCardId) {
        this.actorCardId = actorCardId;
    }

    public String getTargetZone() {
        return targetZone;
    }

    public void setTargetZone(String targetZone) {
        this.targetZone = targetZone;
    }

    public boolean isAllOtherPlayers() {
        return allOtherPlayers;
    }

    public void setAllOtherPlayers(boolean allOtherPlayers) {
        this.allOtherPlayers = allOtherPlayers;
    }
}
