package com.monopoly.pattern.factory;

import com.monopoly.model.card.Card;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 断言标准牌堆经固定种子洗牌后与工厂顺序不同（新局应洗牌，避免连续同类型摸牌）。
 */
class DeckShuffleOrderTest {

    @Test
    void shuffleWithFixedSeed_changesOrderRelativeToFactory() {
        MonopolyDealCardFactory factory = new MonopolyDealCardFactory();
        List<Card> ordered = factory.createStandardDeck108();
        List<String> orderedIds = ordered.stream().map(Card::getId).collect(Collectors.toList());

        List<Card> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled, new Random(12345L));
        List<String> shuffledIds = shuffled.stream().map(Card::getId).collect(Collectors.toList());

        assertEquals(ordered.size(), shuffledIds.size());
        assertNotEquals(orderedIds, shuffledIds, "固定种子洗牌后顺序应与工厂有序列表不同");
    }
}
