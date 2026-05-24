package com.monopoly.pattern.strategy;

import java.nio.file.Path;

/**
 * Backward-compatible name for scripts/tests that still refer to the linear ranker.
 * The implementation now supports both linear and MLP JSON rankers.
 */
public final class LocalLinearRankerAiPlayStrategy extends LocalRankerAiPlayStrategy {

    public LocalLinearRankerAiPlayStrategy(Path modelPath) {
        super(modelPath);
    }
}
