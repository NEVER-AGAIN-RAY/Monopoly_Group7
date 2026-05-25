package com.monopoly.dto;

import java.util.List;

/**
 * PLAY message payload: card id, actionType, targets (max 3 plays per turn).
 */
public class PlayActionRequest {

    /** Card id from hand; preferred over handIndex when both are supplied. */
    private String cardId;
    /** Legacy client fallback: hand index used only when cardId is blank. */
    private Integer handIndex;
    /** DEPLOY / DEPOSIT / ACTION / DISCARD. */
    private String actionType;
    private String targetPlayerId;
    private String targetColorKey;
    /** Target property card id for steal, forced deal, and similar actions. */
    private String targetCardId;
    /** Actor-side property card id, mainly used by Forced Deal. */
    private String actorCardId;
    /** Requested target zone for steal-style actions: PROPERTY or BANK. */
    private String targetZone;
    /**
     * actingPlayerId for RESPONSE_PASS or waiver during rent response window.
     */
    private String actingPlayerId;
    /**
     * Optional paymentCardIds when tenant passes without Just Say No.
     */
    private List<String> paymentCardIds;

    public String getCardId() {
        return cardId;
    }

    public void setCardId(String cardId) {
        this.cardId = cardId;
    }

    public Integer getHandIndex() {
        return handIndex;
    }

    public void setHandIndex(Integer handIndex) {
        this.handIndex = handIndex;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
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

    public String getActingPlayerId() {
        return actingPlayerId;
    }

    public void setActingPlayerId(String actingPlayerId) {
        this.actingPlayerId = actingPlayerId;
    }

    public List<String> getPaymentCardIds() {
        return paymentCardIds;
    }

    public void setPaymentCardIds(List<String> paymentCardIds) {
        this.paymentCardIds = paymentCardIds;
    }
}
