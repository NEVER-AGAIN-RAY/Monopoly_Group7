package com.monopoly.fx;

@FunctionalInterface
interface FxLocalizer {
    String get(String key, Object... args);
}
