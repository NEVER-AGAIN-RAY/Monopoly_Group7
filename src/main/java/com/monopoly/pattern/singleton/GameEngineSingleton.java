package com.monopoly.pattern.singleton;

import com.monopoly.model.card.Card;
import com.monopoly.model.core.GameConstants;
import com.monopoly.model.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * [Singleton]
 * Process-wide holder for draw pile and discard pile (Singleton).
 * <p>
 * Deck size: GameConstants.STANDARD_DECK_SIZE.
 */
public final class GameEngineSingleton {

    /** Standard deck size. */
    public static final int STANDARD_DECK_SIZE = GameConstants.STANDARD_DECK_SIZE;

    private static volatile GameEngineSingleton instance;

    private final List<Card> drawPile = new ArrayList<>();
    private final List<Card> discardPile = new ArrayList<>();

    private GameEngineSingleton() {
    }

    /**
     * Double-checked locking for lazy init.
     */
    public static GameEngineSingleton getInstance() {
        if (instance == null) {
            synchronized (GameEngineSingleton.class) {
                if (instance == null) {
                    instance = new GameEngineSingleton();
                }
            }
        }
        return instance;
    }

    /** Test-only singleton reset */
    static void resetForTests() {
        synchronized (GameEngineSingleton.class) {
            instance = null;
        }
    }

    public List<Card> getDrawPileView() {
        return Collections.unmodifiableList(drawPile);
    }

    public List<Card> getDiscardPileView() {
        return Collections.unmodifiableList(discardPile);
    }

    /** Pile contents are set via attachDrawPile from the controller */
    public void attachDrawPile(List<Card> pile) {
        drawPile.clear();
        discardPile.clear();
        if (pile != null) {
            drawPile.addAll(pile);
        }
    }

    /**
     * Draw one card; reshuffle discard into draw when draw pile is empty; null if both empty.
     */
    public Card drawOne() {
        replenishDrawPileFromDiscardIfEmpty();
        if (drawPile.isEmpty()) {
            return null;
        }
        return drawPile.remove(0);
    }

    /**
     * When draw pile is empty, shuffle all discard cards into a new draw pile.
     */
    public void replenishDrawPileFromDiscardIfEmpty() {
        if (!drawPile.isEmpty()) {
            return;
        }
        if (discardPile.isEmpty()) {
            return;
        }
        drawPile.addAll(discardPile);
        discardPile.clear();
        Collections.shuffle(drawPile, ThreadLocalRandom.current());
    }

    /**
     * Moves one card to the discard pile.
     */
    public void discard(Card card) {
        if (card != null) {
            discardPile.add(card);
        }
    }

    /**
     * @return cards left in draw pile
     */
    public int remainingCount() {
        return drawPile.size();
    }

    public int discardCount() {
        return discardPile.size();
    }

    /**
     * Counts every card: piles plus all player zones (deck integrity check).
     */
    public int countAllCardsInPlay(List<Player> players) {
        int n = remainingCount() + discardCount();
        if (players == null) {
            return n;
        }
        for (Player p : players) {
            if (p != null) {
                n += p.countOwnedCardsTotal();
            }
        }
        return n;
    }

    public void discardMany(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        for (Card card : cards) {
            discard(card);
        }
    }
}
