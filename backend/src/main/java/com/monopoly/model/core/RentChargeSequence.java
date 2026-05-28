package com.monopoly.model.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RENT_DUAL sequence: charge each tenant one-by-one with response windows.
 */
public final class RentChargeSequence {

    private final String landlordId;
    private final String colorKey;
    private final int amountDuePerTenant;
    private final List<String> tenantIdsOrdered;
    /** Index of the tenant currently responding, or the tenant whose response just resolved. */
    private int currentIndex;

    public RentChargeSequence(
            String landlordId,
            String colorKey,
            int amountDuePerTenant,
            List<String> tenantIdsOrdered) {
        this(landlordId, colorKey, amountDuePerTenant, tenantIdsOrdered, 0);
    }

    /**
     * @param initialTenantIndex resume index into tenantIdsOrdered
     */
    public RentChargeSequence(
            String landlordId,
            String colorKey,
            int amountDuePerTenant,
            List<String> tenantIdsOrdered,
            int initialTenantIndex) {
        this.landlordId = landlordId;
        this.colorKey = colorKey;
        this.amountDuePerTenant = amountDuePerTenant;
        this.tenantIdsOrdered = tenantIdsOrdered == null
                ? new ArrayList<>()
                : new ArrayList<>(tenantIdsOrdered);
        this.currentIndex = Math.max(0, initialTenantIndex);
        if (this.currentIndex > this.tenantIdsOrdered.size()) {
            this.currentIndex = this.tenantIdsOrdered.size();
        }
    }

    public String getLandlordId() {
        return landlordId;
    }

    public String getColorKey() {
        return colorKey;
    }

    public int getAmountDuePerTenant() {
        return amountDuePerTenant;
    }

    public List<String> getTenantIdsOrderedView() {
        return Collections.unmodifiableList(tenantIdsOrdered);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    /** Tenant due for the current step; this should match the top rent entry. */
    public String getCurrentTenantId() {
        if (currentIndex < 0 || currentIndex >= tenantIdsOrdered.size()) {
            return null;
        }
        return tenantIdsOrdered.get(currentIndex);
    }

    /** Advance after the current tenant settles; returns false when no tenants remain. */
    public boolean advanceToNextTenant() {
        currentIndex++;
        return currentIndex < tenantIdsOrdered.size();
    }

    public boolean isActive() {
        return landlordId != null && !tenantIdsOrdered.isEmpty() && currentIndex < tenantIdsOrdered.size();
    }
}
