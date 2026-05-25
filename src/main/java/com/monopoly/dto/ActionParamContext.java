package com.monopoly.dto;

/**
 * Normalized play parameters for effect dispatch.
 */
public final class ActionParamContext {

    private final String cardId;
    private final Integer handIndex;
    private final String targetPlayerId;
    private final String targetColorKey;
    private final String targetCardId;
    private final String actorCardId;
    /** Zone requested by steal-style actions: PROPERTY or BANK. */
    private final String targetZone;

    public ActionParamContext(
            String cardId,
            Integer handIndex,
            String targetPlayerId,
            String targetColorKey,
            String targetCardId,
            String actorCardId,
            String targetZone) {
        this.cardId = cardId;
        this.handIndex = handIndex;
        this.targetPlayerId = targetPlayerId;
        this.targetColorKey = targetColorKey;
        this.targetCardId = targetCardId;
        this.actorCardId = actorCardId;
        this.targetZone = targetZone;
    }

    public static ActionParamContext fromPlayRequest(PlayActionRequest r) {
        if (r == null) {
            return new ActionParamContext(null, null, null, null, null, null, null);
        }
        return new ActionParamContext(
                r.getCardId(),
                r.getHandIndex(),
                r.getTargetPlayerId(),
                r.getTargetColorKey(),
                r.getTargetCardId(),
                r.getActorCardId(),
                r.getTargetZone());
    }

    public String getCardId() {
        return cardId;
    }

    public Integer getHandIndex() {
        return handIndex;
    }

    public String getTargetPlayerId() {
        return targetPlayerId;
    }

    public String getTargetColorKey() {
        return targetColorKey;
    }

    public String getTargetCardId() {
        return targetCardId;
    }

    public String getActorCardId() {
        return actorCardId;
    }

    public String getTargetZone() {
        return targetZone;
    }
}
