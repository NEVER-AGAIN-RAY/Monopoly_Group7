package com.monopoly.pattern.singleton;

import com.monopoly.model.card.Card;
import com.monopoly.model.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Holder for one game session's draw pile and discard pile.
 * <p>
 * Each live session owns its own instance (see {@link #createIsolated()}) so that
 * multiple games in one JVM never share a deck. The legacy class name is retained
 * to avoid churn across callers and persistence; it is no longer a true singleton.
 */
public final class GameEngineSingleton {

    private final List<Card> drawPile = new ArrayList<>();
    private final List<Card> discardPile = new ArrayList<>();
    private Random deterministicReshuffleRandom;

    private GameEngineSingleton() {
    }

    /**
     * Creates an isolated engine for one game session.
     */
    public static GameEngineSingleton createIsolated() {
        return new GameEngineSingleton();
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

    public void useDeterministicReshuffleSeed(long seed) {
        deterministicReshuffleRandom = new Random(seed);
    }

    public void clearDeterministicReshuffleSeed() {
        deterministicReshuffleRandom = null;
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
        Random rng = deterministicReshuffleRandom != null
                ? deterministicReshuffleRandom
                : ThreadLocalRandom.current();
        Collections.shuffle(drawPile, rng);
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
