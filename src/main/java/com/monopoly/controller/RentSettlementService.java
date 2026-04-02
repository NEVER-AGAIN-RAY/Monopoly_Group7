package com.monopoly.controller;

import com.monopoly.model.core.GameContext;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.RentCalculator;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.logging.Logger;

/**
 * 租金结算，从 {@link GameController} 抽出。
 * <p>
 * 不持有可变状态；通过 {@link GameController} 包级方法完成会话检查与快照推送。
 */
final class RentSettlementService {

    private static final Logger LOG = Logger.getLogger(RentSettlementService.class.getName());
    private static final boolean PERF_LOG = Boolean.parseBoolean(
            System.getProperty("monopoly.perfLog", "false"));

    private final GameController controller;
    private final GameEngineSingleton engine = GameEngineSingleton.getInstance();

    RentSettlementService(GameController controller) {
        this.controller = controller;
    }

    PaymentSettlement.Result requestRentPayment(Player from, Player to, int amount, GameContext context) {
        controller.ensureSessionActive();
        PaymentSettlement.Result result = PaymentSettlement.settle(from, to, amount, engine);
        String rentPhase = result.isSuccess() ? "RENT_PAID" : "RENT_FAILED";
        controller.pushSnapshot(controller.getCurrentSessionId(), rentPhase,
                "Rent " + amount + "M from " + from.getDisplayName() + " to " + to.getDisplayName()
                        + " (" + (result.isSuccess() ? "paid" : "failed") + ").");
        return result;
    }

    PaymentSettlement.Result collectRentForColor(Player landlord, Player tenant, String colorKey) {
        controller.ensureSessionActive();
        int due = RentCalculator.computeRentForColor(landlord, colorKey);
        return requestRentPayment(tenant, landlord, due, new GameContext());
    }

    int computeRentDueForColor(Player landlord, String colorKey) {
        long startNs = PERF_LOG ? System.nanoTime() : 0L;
        int due = RentCalculator.computeRentForColor(landlord, colorKey);
        if (PERF_LOG) {
            long elapsedUs = (System.nanoTime() - startNs) / 1_000L;
            LOG.info("perf computeRentDueForColor: " + elapsedUs + "us color=" + colorKey);
        }
        return due;
    }
}
