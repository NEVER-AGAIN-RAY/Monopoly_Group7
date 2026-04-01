package com.monopoly.model;

import java.util.UUID;

/**
 * 效果结算栈中的单条记录：收租（待结算）或免租（指向被取消的栈条目 id）。
 */
public final class EffectStackEntry {

    public enum Kind {
        RENT,
        DOUBLE_RENT,
        WAIVER
    }

    private final String id;
    private final Kind kind;
    /** 收租方 / 打出免租牌的一方 */
    private final String actorPlayerId;
    /** 付租方（仅收租类） */
    private final String tenantPlayerId;
    private final String colorKey;
    private final int amountDue;
    /** 免租指向的栈条目 id（另一条收租或上一条免租） */
    private final String waiverTargetEntryId;

    private EffectStackEntry(
            String id,
            Kind kind,
            String actorPlayerId,
            String tenantPlayerId,
            String colorKey,
            int amountDue,
            String waiverTargetEntryId) {
        this.id = id;
        this.kind = kind;
        this.actorPlayerId = actorPlayerId;
        this.tenantPlayerId = tenantPlayerId;
        this.colorKey = colorKey;
        this.amountDue = amountDue;
        this.waiverTargetEntryId = waiverTargetEntryId;
    }

    public static EffectStackEntry pendingRent(
            String landlordId, String tenantId, String colorKey, int amountDue) {
        String id = UUID.randomUUID().toString();
        return new EffectStackEntry(id, Kind.RENT, landlordId, tenantId, colorKey, amountDue, null);
    }

    public static EffectStackEntry pendingDoubleRent(
            String landlordId, String tenantId, String colorKey, int amountDue) {
        String id = UUID.randomUUID().toString();
        return new EffectStackEntry(id, Kind.DOUBLE_RENT, landlordId, tenantId, colorKey, amountDue, null);
    }

    public static EffectStackEntry waiver(String actorPlayerId, String targetEntryId) {
        String id = UUID.randomUUID().toString();
        return new EffectStackEntry(id, Kind.WAIVER, actorPlayerId, null, null, 0, targetEntryId);
    }

    public String getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public String getActorPlayerId() {
        return actorPlayerId;
    }

    public String getTenantPlayerId() {
        return tenantPlayerId;
    }

    public String getColorKey() {
        return colorKey;
    }

    public int getAmountDue() {
        return amountDue;
    }

    public String getWaiverTargetEntryId() {
        return waiverTargetEntryId;
    }

    public boolean isRentLike() {
        return kind == Kind.RENT || kind == Kind.DOUBLE_RENT;
    }
}
