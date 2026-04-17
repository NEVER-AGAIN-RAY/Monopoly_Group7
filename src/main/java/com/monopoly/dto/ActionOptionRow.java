package com.monopoly.dto;

/**
 * 行动牌可选操作一行：展示文案 + 填入 {@link PlayActionRequest} 的字段。
 */
public final class ActionOptionRow {

    private String labelZh;
    private String targetPlayerId;
    private String targetColorKey;
    private String targetCardId;
    private String actorCardId;
    private String targetZone;
    /** 为 true 时客户端打出应省略 targetPlayerId（由服务器按会话顺序对全员依次结算）。 */
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
