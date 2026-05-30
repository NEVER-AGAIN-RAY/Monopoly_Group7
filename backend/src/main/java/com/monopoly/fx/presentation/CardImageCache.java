package com.monopoly.fx.presentation;

import javafx.scene.image.Image;

import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared card image cache. JavaFX decodes JPEGs lazily by default; rebuilding the
 * table on every state update without caching makes the UI thread stutter.
 */
public final class CardImageCache {

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private CardImageCache() {
    }

    public static Image image(CardDisplayData card, double requestedWidth, double requestedHeight) {
        URL url = CardImageResolver.imageUrl(card);
        if (url == null) {
            return null;
        }
        String external = url.toExternalForm();
        String key = external + "#" + Math.round(requestedWidth) + "x" + Math.round(requestedHeight);
        return CACHE.computeIfAbsent(key, ignored -> new Image(
                external,
                requestedWidth,
                requestedHeight,
                true,
                true,
                false));
    }
}
