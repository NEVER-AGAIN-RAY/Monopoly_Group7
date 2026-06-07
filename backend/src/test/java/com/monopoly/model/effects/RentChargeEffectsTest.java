package com.monopoly.model.effects;

import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RentChargeEffectsTest {

    @Test
    void rentComputeDue_shouldNormalizeColorAndIncludeBuildings() {
        Player landlord = player("p1", "Landlord");
        Player tenant = player("p2", "Tenant");
        PropertyCard red1 = property("r1", "Red 1", "RED");
        PropertyCard red2 = property("r2", "Red 2", "RED");
        red1.setBuildingLevel(BuildingLevel.HOUSE);
        red2.setBuildingLevel(BuildingLevel.HOTEL);
        landlord.addToPropertyZone(red1);
        landlord.addToPropertyZone(red2);

        RentEffect.DueResult due = RentEffect.computeDue(ctx(landlord, tenant).colorKey(" red ").build());

        assertTrue(due.isOk());
        assertEquals(13, due.getAmountDue());
    }

    @Test
    void rentComputeDue_shouldRejectMissingTargetColorAndLandlordProperty() {
        Player landlord = player("p1", "Landlord");
        Player tenant = player("p2", "Tenant");

        RentEffect.DueResult noTarget = RentEffect.computeDue(ctx(landlord, null).colorKey("BROWN").build());
        assertFalse(noTarget.isOk());
        assertTrue(noTarget.getError().contains("target player"));

        RentEffect.DueResult noColor = RentEffect.computeDue(ctx(landlord, tenant).build());
        assertFalse(noColor.isOk());
        assertTrue(noColor.getError().contains("color"));

        RentEffect.DueResult noProperty = RentEffect.computeDue(ctx(landlord, tenant).colorKey("GREEN").build());
        assertFalse(noProperty.isOk());
        assertTrue(noProperty.getError().contains("GREEN"));
    }

    @Test
    void rentExecute_shouldTransferBankCardsFromTenantToLandlord() {
        Player landlord = player("p1", "Landlord");
        Player tenant = player("p2", "Tenant");
        PropertyCard brown1 = property("b1", "Brown 1", "BROWN");
        PropertyCard brown2 = property("b2", "Brown 2", "BROWN");
        MoneyCard m1 = money("m1", 1);
        MoneyCard m2 = money("m2", 2);
        landlord.addToPropertyZone(brown1);
        landlord.addToPropertyZone(brown2);
        tenant.addToBank(m1);
        tenant.addToBank(m2);

        ActionEffectResult result = new RentEffect().execute(ctx(landlord, tenant).colorKey("BROWN").build());

        assertTrue(result.isSuccess());
        assertEquals(1, tenant.getBankCardCount());
        assertTrue(tenant.getBankCardsView().contains(m1));
        assertFalse(tenant.getBankCardsView().contains(m2));
        assertTrue(landlord.getBankCardsView().contains(m2));
        assertEquals(2, landlord.getPropertyCardCount());
    }

    @Test
    void rentExecute_shouldUsePropertyOnlyWhenBankCannotCover() {
        Player landlord = player("p1", "Landlord");
        Player tenant = player("p2", "Tenant");
        PropertyCard darkBlue1 = property("db1", "Dark Blue 1", "DARK_BLUE");
        PropertyCard darkBlue2 = property("db2", "Dark Blue 2", "DARK_BLUE");
        MoneyCard m1 = money("m1", 1);
        PropertyCard tenantProperty = property("tb1", "Tenant Brown", "BROWN");
        landlord.addToPropertyZone(darkBlue1);
        landlord.addToPropertyZone(darkBlue2);
        tenant.addToBank(m1);
        tenant.addToPropertyZone(tenantProperty);

        ActionEffectResult result = new RentEffect().execute(ctx(landlord, tenant).colorKey("DARK_BLUE").build());

        assertTrue(result.isSuccess());
        assertTrue(landlord.getBankCardsView().contains(m1));
        assertTrue(landlord.getPropertyCardsView().contains(tenantProperty));
        assertFalse(tenant.getPropertyCardsView().contains(tenantProperty));
    }

    @Test
    void doubleRentComputeDue_shouldDoubleBaseRentAndBuildingBonus() {
        Player landlord = player("p1", "Landlord");
        Player tenant = player("p2", "Tenant");
        PropertyCard green1 = property("g1", "Green 1", "GREEN");
        PropertyCard green2 = property("g2", "Green 2", "GREEN");
        green1.setBuildingLevel(BuildingLevel.HOUSE);
        landlord.addToPropertyZone(green1);
        landlord.addToPropertyZone(green2);

        RentEffect.DueResult due = DoubleRentEffect.computeDue(ctx(landlord, tenant).colorKey("GREEN").build());

        assertTrue(due.isOk());
        assertEquals(14, due.getAmountDue());
    }

    @Test
    void doubleRentExecute_shouldOverpayWhenNoChangeIsAvailable() {
        Player landlord = player("p1", "Landlord");
        Player tenant = player("p2", "Tenant");
        landlord.addToPropertyZone(property("lb1", "Light Blue 1", "LIGHT_BLUE"));
        MoneyCard m5 = money("m5", 5);
        tenant.addToBank(m5);

        ActionEffectResult result = new DoubleRentEffect().execute(ctx(landlord, tenant).colorKey("LIGHT_BLUE").build());

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("base 1M x 2"));
        assertTrue(landlord.getBankCardsView().contains(m5));
        assertEquals(0, tenant.getBankCardCount());
    }

    @Test
    void debtCollector_shouldTransferBestPaymentCombination() {
        Player actor = player("p1", "Collector");
        Player target = player("p2", "Debtor");
        MoneyCard m2 = money("m2", 2);
        MoneyCard m3 = money("m3", 3);
        MoneyCard m5 = money("m5", 5);
        target.addToBank(m2);
        target.addToBank(m3);
        target.addToBank(m5);

        ActionEffectResult result = new DebtCollectorEffect().execute(ctx(actor, target).build());

        assertTrue(result.isSuccess());
        assertTrue(actor.getBankCardsView().contains(m5));
        assertFalse(target.getBankCardsView().contains(m5));
        assertTrue(target.getBankCardsView().contains(m2));
        assertTrue(target.getBankCardsView().contains(m3));
    }

    @Test
    void debtCollector_shouldFailWithoutTarget() {
        Player actor = player("p1", "Collector");

        ActionEffectResult result = new DebtCollectorEffect().execute(ctx(actor, null).build());

        assertFalse(result.isSuccess());
        assertEquals(ActionEffectResult.Status.FAILED, result.getStatus());
        assertTrue(result.getMessage().contains("target"));
    }

    @Test
    void birthday_shouldCollectFromEveryOtherPlayerAndSkipActor() {
        Player actor = player("p1", "Birthday");
        Player rich = player("p2", "Rich");
        Player shortOnCash = player("p3", "Short");
        MoneyCard richGift = money("m2", 2);
        MoneyCard partialGift = money("m1", 1);
        rich.addToBank(richGift);
        shortOnCash.addToBank(partialGift);

        ActionEffectResult result = new BirthdayEffect().execute(
                ActionEffectContext.builder(actor, engine(), List.of(actor, rich, shortOnCash)).build());

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("3M"));
        assertTrue(actor.getBankCardsView().contains(richGift));
        assertTrue(actor.getBankCardsView().contains(partialGift));
        assertEquals(0, rich.getBankCardCount());
        assertEquals(0, shortOnCash.getBankCardCount());
    }

    @Test
    void birthday_shouldRejectMissingActorOrSinglePlayerGame() {
        Player actor = player("p1", "Birthday");

        ActionEffectResult missingActor = new BirthdayEffect().execute(
                ActionEffectContext.builder(null, engine(), List.of(actor)).build());
        assertFalse(missingActor.isSuccess());
        assertTrue(missingActor.getMessage().contains("Missing"));

        ActionEffectResult notEnoughPlayers = new BirthdayEffect().execute(
                ActionEffectContext.builder(actor, engine(), List.of(actor)).build());
        assertFalse(notEnoughPlayers.isSuccess());
        assertTrue(notEnoughPlayers.getMessage().contains("Not enough"));
    }

    @Test
    void rentWaiver_shouldReturnCounteredStatusWithActorName() {
        Player actor = player("p1", "Defender");

        ActionEffectResult result = new RentWaiverEffect().execute(ctx(actor, null).build());

        assertEquals(ActionEffectResult.Status.COUNTERED, result.getStatus());
        assertTrue(result.getMessage().contains("Defender"));
    }

    private static ActionEffectContext.Builder ctx(Player actor, Player target) {
        return ActionEffectContext.builder(actor, engine(), target == null ? List.of(actor) : List.of(actor, target))
                .target(target);
    }

    private static GameEngineSingleton engine() {
        return GameEngineSingleton.createIsolated();
    }

    private static Player player(String id, String name) {
        return new HumanPlayer(id, name);
    }

    private static MoneyCard money(String id, int value) {
        return new MoneyCard(id, "Money " + value, value);
    }

    private static PropertyCard property(String id, String name, String color) {
        return new PropertyCard(id, name, color);
    }
}
