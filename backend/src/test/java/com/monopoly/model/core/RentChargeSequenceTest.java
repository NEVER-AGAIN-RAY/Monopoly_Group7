package com.monopoly.model.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RentChargeSequenceTest {

    @Test
    void advance_movesThroughTenants_thenEnds() {
        RentChargeSequence s = new RentChargeSequence("L", "RED", 3, List.of("a", "b", "c"));
        assertEquals("a", s.getCurrentTenantId());
        assertTrue(s.isActive());
        assertTrue(s.advanceToNextTenant());
        assertEquals("b", s.getCurrentTenantId());
        assertTrue(s.advanceToNextTenant());
        assertEquals("c", s.getCurrentTenantId());
        assertFalse(s.advanceToNextTenant());
        assertFalse(s.isActive());
    }

    @Test
    void resumeIndex_restoresPosition() {
        RentChargeSequence s = new RentChargeSequence("L", "BLUE", 1, List.of("x", "y"), 1);
        assertEquals("y", s.getCurrentTenantId());
    }
}
