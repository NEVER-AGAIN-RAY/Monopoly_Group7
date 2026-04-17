package com.monopoly.model.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 双色全员收租（RENT_DUAL）：对多名承租人<strong>依次</strong>各压一条收租并进入响应窗口。
 */
public final class RentChargeSequence {

    private final String landlordId;
    private final String colorKey;
    private final int amountDuePerTenant;
    private final List<String> tenantIdsOrdered;
    /** 当前正在/刚结束响应的承租人在列表中的下标。 */
    private int currentIndex;

    public RentChargeSequence(
            String landlordId,
            String colorKey,
            int amountDuePerTenant,
            List<String> tenantIdsOrdered) {
        this(landlordId, colorKey, amountDuePerTenant, tenantIdsOrdered, 0);
    }

    /**
     * @param initialTenantIndex 当前应付承租人在 {@code tenantIdsOrdered} 中的下标（读档恢复用）
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

    /** 当前轮次应付的承租人（与栈顶收租条目一致）。 */
    public String getCurrentTenantId() {
        if (currentIndex < 0 || currentIndex >= tenantIdsOrdered.size()) {
            return null;
        }
        return tenantIdsOrdered.get(currentIndex);
    }

    /** 本轮结算完成后调用：移向下一名；若已无则返回 false。 */
    public boolean advanceToNextTenant() {
        currentIndex++;
        return currentIndex < tenantIdsOrdered.size();
    }

    public boolean isActive() {
        return landlordId != null && !tenantIdsOrdered.isEmpty() && currentIndex < tenantIdsOrdered.size();
    }
}
