package com.monopoly.model.player;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PayableCards;
import com.monopoly.model.card.PropertyCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Base player: hand, bank, property, and action zones; Human vs AI in subclasses.
 */
public abstract class Player {

    protected final String playerId;
    protected final String displayName;
    // Card zones; controller drives rules, this class only stores partitions
    protected final List<Card> handCards = new ArrayList<>();
    protected final List<PropertyCard> propertyCards = new ArrayList<>();
    protected final List<Card> bankCards = new ArrayList<>();
    protected final List<ActionCard> actionZoneCards = new ArrayList<>();

    protected Player(String playerId, String displayName) {
        this.playerId = playerId;
        this.displayName = displayName;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<Card> getHandCardsView() {
        return Collections.unmodifiableList(handCards);
    }

    public List<PropertyCard> getPropertyCardsView() {
        return Collections.unmodifiableList(propertyCards);
    }

    public List<Card> getBankCardsView() {
        return Collections.unmodifiableList(bankCards);
    }

    public List<ActionCard> getActionZoneCardsView() {
        return Collections.unmodifiableList(actionZoneCards);
    }

    public int getHandCardCount() {
        return handCards.size();
    }

    public int getBankCardCount() {
        return bankCards.size();
    }

    public int getPropertyCardCount() {
        return propertyCards.size();
    }

    /** Number of complete color sets on the board (win at 3+). */
    public int countCompletePropertySets() {
        return PropertySetCalculator.countCompletePropertySets(propertyCards);
    }

    public int getActionZoneCardCount() {
        return actionZoneCards.size();
    }

    /** Total cards owned (hand + bank + property + action zone) for deck integrity checks. */
    public int countOwnedCardsTotal() {
        return handCards.size() + bankCards.size() + propertyCards.size() + actionZoneCards.size();
    }

    /** Called when dealing or drawing into hand. */
    public void receiveCardToHand(Card card) {
        if (card != null) {
            handCards.add(card);
        }
    }

    /** Removes a card from hand after play or discard. */
    public void removeCardFromHand(Card card) {
        handCards.remove(card);
    }

    /** Discards one card from hand; returns whether it was present. */
    public boolean discardFromHand(Card card) {
        if (card == null) {
            return false;
        }
        return handCards.remove(card);
    }

    /**
     * Discards from the tail of hand until size &lt;= limit (end-of-turn rule: 7).
     *
     * @return cards removed so the controller can move them to the discard pile
     */
    public List<Card> discardOverflowTo(int limit) {
        List<Card> discarded = new ArrayList<>();
        if (limit < 0) {
            limit = 0;
        }
        while (handCards.size() > limit) {
            Card removed = chooseOverflowDiscardCard();
            handCards.remove(removed);
            discarded.add(removed);
        }
        return discarded;
    }

    public List<Card> chooseOverflowDiscardsTo(int limit) {
        List<Card> copy = new ArrayList<>(handCards);
        List<Card> chosen = new ArrayList<>();
        if (limit < 0) {
            limit = 0;
        }
        while (copy.size() > limit) {
            Card removed = copy.stream()
                    .min(Comparator.comparingInt(Player::handRetentionScore))
                    .orElse(copy.get(copy.size() - 1));
            copy.remove(removed);
            chosen.add(removed);
        }
        return chosen;
    }

    public List<Card> discardSpecificFromHand(List<Card> cards) {
        List<Card> discarded = new ArrayList<>();
        if (cards == null) {
            return discarded;
        }
        for (Card card : cards) {
            if (card != null && handCards.remove(card)) {
                discarded.add(card);
            }
        }
        return discarded;
    }

    private Card chooseOverflowDiscardCard() {
        return handCards.stream()
                .min(Comparator.comparingInt(Player::handRetentionScore))
                .orElse(handCards.get(handCards.size() - 1));
    }

    private static int handRetentionScore(Card card) {
        if (card instanceof PropertyCard) {
            return 900 + PayableCards.valueOf(card) * 10;
        }
        if (card instanceof ActionCard ac) {
            String effect = ac.getEffectCode() == null
                    ? ""
                    : ac.getEffectCode().trim().toUpperCase(Locale.ROOT);
            int base = switch (effect) {
                case "DEAL_BREAKER" -> 1_000;
                case "RENT_WAIVER" -> 950;
                case "STEAL_PROPERTY", "FORCED_DEAL" -> 875;
                case "HOTEL", "HOUSE" -> 720;
                case "DEBT_COLLECTOR", "BIRTHDAY" -> 620;
                case "PASS_GO" -> 520;
                case "DOUBLE_RENT", "RENT", "RENT_DUAL" -> 420;
                default -> 500;
            };
            return base + PayableCards.valueOf(card) * 10;
        }
        return PayableCards.valueOf(card) * 10;
    }

    /** Moves a hand card into the bank pile (action cards lose effect when banked). */
    public void depositToBank(Card card) {
        if (card == null) {
            return;
        }
        removeCardFromHand(card);
        bankCards.add(card);
    }

    public void addToBank(Card card) {
        if (card != null) {
            bankCards.add(card);
        }
    }

    public boolean removeFromBank(Card card) {
        return card != null && bankCards.remove(card);
    }

    public List<ActionCard> clearActionZone() {
        if (actionZoneCards.isEmpty()) {
            return List.of();
        }
        List<ActionCard> cleared = new ArrayList<>(actionZoneCards);
        actionZoneCards.clear();
        return cleared;
    }

    public boolean removePropertyCard(PropertyCard card) {
        return card != null && propertyCards.remove(card);
    }

    /** Adds property directly to the zone (steal/swap), skipping hand. */
    public void addToPropertyZone(PropertyCard card) {
        if (card != null) {
            propertyCards.add(card);
        }
    }

    /** Sum of bank card values in millions (M). */
    public int totalBankValueM() {
        int s = 0;
        for (Card c : bankCards) {
            s += PayableCards.valueOf(c);
        }
        return s;
    }

    /** Property zone payment value in M (rent payments cannot use hand cards). */
    public int totalPropertyPaymentValueM() {
        int s = 0;
        for (PropertyCard p : propertyCards) {
            s += PayableCards.valueOf(p);
        }
        return s;
    }

    /** Deploys a property card from hand to the property zone. */
    public void deployProperty(PropertyCard card) {
        if (card == null) {
            return;
        }
        removeCardFromHand(card);
        propertyCards.add(card);
    }

    /** Places an action card in the center zone; effect logic runs in the controller. */
    public void placeActionToCenter(ActionCard card) {
        if (card == null) {
            return;
        }
        removeCardFromHand(card);
        actionZoneCards.add(card);
    }

    /** Delegates playability to Card.canPlay. */
    public boolean canPlay(Card card, ActionParamContext params, GameContext context) {
        if (card == null) {
            return false;
        }
        if (context == null) {
            context = new GameContext();
        }
        return card.canPlay(this, params, context);
    }

    /**
     * Hook for "what to do on my turn": empty for humans (client drives), AI uses strategy.
     */
    public abstract void requestPlayDecision(GameContext context);
}
