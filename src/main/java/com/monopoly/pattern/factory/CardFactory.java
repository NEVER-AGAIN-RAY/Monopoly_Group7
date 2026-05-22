package com.monopoly.pattern.factory;

import com.monopoly.model.card.Card;
import com.monopoly.model.core.GameConstants;

import java.util.List;

/**
 * [Factory Method]
 * Creator for card instances; concrete factories build the
 * {@link GameConstants#STANDARD_DECK_SIZE}-card standard deck.
 */
public abstract class CardFactory {

    /**
     * Factory method: subclasses create individual cards.
     */
    protected abstract Card createCard(String specKey);

    /**
     * Template: assemble full deck from factory methods (shuffle is done elsewhere).
     */
    public abstract List<Card> createStandardDeck108();
}
