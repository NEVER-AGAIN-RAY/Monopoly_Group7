package com.monopoly.model.effects;

import java.util.UUID;

/**
 * One pending rent or waiver entry on the effect stack.
 */
public final class EffectStackEntry {

    public enum Kind {
        RENT,
        DOUBLE_RENT,
        ACTION,
        WAIVER
    }

    private final String id;
    private final Kind kind;
    /** Landlord, action actor, or the player who played Just Say No. */
    private final String actorPlayerId;
    /** Tenant player id for rent-like entries. */
    private final String tenantPlayerId;
    private final String colorKey;
    private final int amountDue;
    /** Stack entry id targeted by a waiver, either a rent entry or the previous waiver. */
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

    public static EffectStackEntry pendingAction(String actorPlayerId, String targetPlayerId) {
        String id = UUID.randomUUID().toString();
        return new EffectStackEntry(id, Kind.ACTION, actorPlayerId, targetPlayerId, null, 0, null);
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

    public boolean isActionLike() {
        return kind == Kind.ACTION;
    }
}
