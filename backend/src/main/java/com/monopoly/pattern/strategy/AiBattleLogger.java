package com.monopoly.pattern.strategy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Lightweight project-root AI battle log.
 */
public final class AiBattleLogger {

    public static final Path LOG_PATH = Path.of("AI_BATTLE_LOG.md");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile MetricsSink metricsSink;

    private AiBattleLogger() {
    }

    public static synchronized void log(String category, String message) {
        String safeCategory = category == null || category.isBlank() ? "AI" : category.trim();
        String safeMessage = message == null ? "" : message.trim();
        MetricsSink sink = metricsSink;
        if (sink != null) {
            sink.onLog(safeCategory, safeMessage);
        }
        if (!Boolean.parseBoolean(System.getProperty("monopoly.aiBattle.log.enabled", "true"))) {
            return;
        }
        String line = "- " + TS.format(LocalDateTime.now()) + " [" + safeCategory + "] " + safeMessage + "\n";
        try {
            if (!Files.exists(LOG_PATH)) {
                Files.writeString(LOG_PATH,
                        "# AI Battle Log\n\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
            }
            Files.writeString(LOG_PATH,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The game should not fail because diagnostics cannot be written.
        }
    }

    public static void setMetricsSink(MetricsSink sink) {
        metricsSink = sink;
    }

    public interface MetricsSink {
        void onLog(String category, String message);
    }
}
