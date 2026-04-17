package com.monopoly.persistence;

import com.monopoly.model.core.RentChargeSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link RentChargeSequence} 的 Gson 快照字段。
 */
public final class RentChargeSequenceMemento {

    private String landlordId;
    private String colorKey;
    private int amountDuePerTenant;
    private List<String> tenantIdsOrdered = new ArrayList<>();
    private int currentIndex;

    public static RentChargeSequenceMemento from(RentChargeSequence seq) {
        if (seq == null) {
            return null;
        }
        RentChargeSequenceMemento m = new RentChargeSequenceMemento();
        m.landlordId = seq.getLandlordId();
        m.colorKey = seq.getColorKey();
        m.amountDuePerTenant = seq.getAmountDuePerTenant();
        m.tenantIdsOrdered = new ArrayList<>(seq.getTenantIdsOrderedView());
        m.currentIndex = seq.getCurrentIndex();
        return m;
    }

    public RentChargeSequence toDomain() {
        return new RentChargeSequence(
                landlordId,
                colorKey,
                amountDuePerTenant,
                tenantIdsOrdered,
                currentIndex);
    }

    public String getLandlordId() {
        return landlordId;
    }

    public void setLandlordId(String landlordId) {
        this.landlordId = landlordId;
    }

    public String getColorKey() {
        return colorKey;
    }

    public void setColorKey(String colorKey) {
        this.colorKey = colorKey;
    }

    public int getAmountDuePerTenant() {
        return amountDuePerTenant;
    }

    public void setAmountDuePerTenant(int amountDuePerTenant) {
        this.amountDuePerTenant = amountDuePerTenant;
    }

    public List<String> getTenantIdsOrdered() {
        return tenantIdsOrdered;
    }

    public void setTenantIdsOrdered(List<String> tenantIdsOrdered) {
        this.tenantIdsOrdered = tenantIdsOrdered != null ? tenantIdsOrdered : new ArrayList<>();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }
}
