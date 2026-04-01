package com.monopoly.model.persistence;

import com.monopoly.model.EffectStackEntry;

/**
 * {@link EffectStackEntry} 的扁平快照（与枚举 {@link EffectStackEntry.Kind} 对应）。
 */
public final class EffectStackEntryMemento {

    private String id;
    private String kind;
    private String actorPlayerId;
    private String tenantPlayerId;
    private String colorKey;
    private int amountDue;
    private String waiverTargetEntryId;

    public static EffectStackEntryMemento fromEntry(EffectStackEntry e) {
        if (e == null) {
            return null;
        }
        EffectStackEntryMemento m = new EffectStackEntryMemento();
        m.id = e.getId();
        m.kind = e.getKind().name();
        m.actorPlayerId = e.getActorPlayerId();
        m.tenantPlayerId = e.getTenantPlayerId();
        m.colorKey = e.getColorKey();
        m.amountDue = e.getAmountDue();
        m.waiverTargetEntryId = e.getWaiverTargetEntryId();
        return m;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getActorPlayerId() {
        return actorPlayerId;
    }

    public void setActorPlayerId(String actorPlayerId) {
        this.actorPlayerId = actorPlayerId;
    }

    public String getTenantPlayerId() {
        return tenantPlayerId;
    }

    public void setTenantPlayerId(String tenantPlayerId) {
        this.tenantPlayerId = tenantPlayerId;
    }

    public String getColorKey() {
        return colorKey;
    }

    public void setColorKey(String colorKey) {
        this.colorKey = colorKey;
    }

    public int getAmountDue() {
        return amountDue;
    }

    public void setAmountDue(int amountDue) {
        this.amountDue = amountDue;
    }

    public String getWaiverTargetEntryId() {
        return waiverTargetEntryId;
    }

    public void setWaiverTargetEntryId(String waiverTargetEntryId) {
        this.waiverTargetEntryId = waiverTargetEntryId;
    }
}
