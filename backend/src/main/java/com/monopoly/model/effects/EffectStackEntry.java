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
    private final String actionCardName;
    private final String actionEffectCode;

    private EffectStackEntry(
            String id,
            Kind kind,
            String actorPlayerId,
            String tenantPlayerId,
            String colorKey,
            int amountDue,
            String waiverTargetEntryId,
            String actionCardName,
            String actionEffectCode) {
        this.id = id;
        this.kind = kind;
        this.actorPlayerId = actorPlayerId;
        this.tenantPlayerId = tenantPlayerId;
        this.colorKey = colorKey;
        this.amountDue = amountDue;
        this.waiverTargetEntryId = waiverTargetEntryId;
        this.actionCardName = actionCardName;
        this.actionEffectCode = actionEffectCode;
    }

    public static EffectStackEntry pendingRent(
            String landlordId, String tenantId, String colorKey, int amountDue) {
        return pendingRent(landlordId, tenantId, colorKey, amountDue, null, null);
    }

    public static EffectStackEntry pendingRent(
            String landlordId,
            String tenantId,
            String colorKey,
            int amountDue,
            String actionCardName,
            String actionEffectCode) {
        String id = UUID.randomUUID().toString();
        return new EffectStackEntry(
                id,
                Kind.RENT,
                landlordId,
                tenantId,
                colorKey,
                amountDue,
                null,
                actionCardName,
                actionEffectCode);
    }

    public static EffectStackEntry pendingDoubleRent(
            String landlordId, String tenantId, String colorKey, int amountDue) {
        return pendingDoubleRent(landlordId, tenantId, colorKey, amountDue, null, null);
    }

    public static EffectStackEntry pendingDoubleRent(
            String landlordId,
            String tenantId,
            String colorKey,
            int amountDue,
            String actionCardName,
            String actionEffectCode) {
        String id = UUID.randomUUID().toString();
        return new EffectStackEntry(
                id,
                Kind.DOUBLE_RENT,
                landlordId,
                tenantId,
                colorKey,
                amountDue,
                null,
                actionCardName,
                actionEffectCode);
    }

    public static EffectStackEntry waiver(String actorPlayerId, String targetEntryId) {
        String id = UUID.randomUUID().toString();
        return new EffectStackEntry(id, Kind.WAIVER, actorPlayerId, null, null, 0, targetEntryId, null, null);
    }

    public static EffectStackEntry pendingAction(String actorPlayerId, String targetPlayerId) {
        return pendingAction(actorPlayerId, targetPlayerId, null, null);
    }

    public static EffectStackEntry pendingAction(
            String actorPlayerId,
            String targetPlayerId,
            String actionCardName,
            String actionEffectCode) {
        String id = UUID.randomUUID().toString();
        return new EffectStackEntry(
                id,
                Kind.ACTION,
                actorPlayerId,
                targetPlayerId,
                null,
                0,
                null,
                actionCardName,
                actionEffectCode);
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

    public String getActionCardName() {
        return actionCardName;
    }

    public String getActionEffectCode() {
        return actionEffectCode;
    }

    public boolean isRentLike() {
        return kind == Kind.RENT || kind == Kind.DOUBLE_RENT;
    }

    public boolean isActionLike() {
        return kind == Kind.ACTION;
    }
}
