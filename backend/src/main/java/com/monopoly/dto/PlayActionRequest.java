package com.monopoly.dto;

import java.util.List;

/**
 * PLAY message payload: card id, actionType, targets (max 3 plays per turn).
 */
public class PlayActionRequest {

    /** 要打出的手牌卡牌 ID（优先于 handIndex） */
    private String cardId;
    /** 兼容旧客户端：手牌下标，当 cardId 为空时使用 */
    private Integer handIndex;
    /** DEPLOY / DEPOSIT / ACTION / DISCARD（出牌阶段弃入手牌至弃牌堆） */
    private String actionType;
    private String targetPlayerId;
    private String targetColorKey;
    /** 目标财产区房产卡 ID（偷牌、强制交换等） */
    private String targetCardId;
    /** 己方财产区房产卡 ID（强制交换） */
    private String actorCardId;
    /** 偷牌目标分区：PROPERTY（财产区）或 BANK（银行堆） */
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
