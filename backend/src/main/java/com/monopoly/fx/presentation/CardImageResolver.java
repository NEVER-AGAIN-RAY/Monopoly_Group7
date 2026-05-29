package com.monopoly.fx.presentation;

import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the same card art set used by the web client.
 */
public final class CardImageResolver {

    private static final String BASE = "/com/monopoly/fx/cards/";

    private CardImageResolver() {
    }

    public static URL imageUrl(CardDisplayData card) {
        String file = imageFile(card);
        return file == null || file.isBlank()
                ? null
                : CardImageResolver.class.getResource(BASE + file);
    }

    private static String imageFile(CardDisplayData card) {
        List<String> files = imageFiles(card);
        if (files.isEmpty()) {
            return "";
        }
        String seed = card.getId() == null || card.getId().isBlank()
                ? card.getTitle()
                : card.getId();
        return files.get(stableIndex(seed, files.size()));
    }

    private static List<String> imageFiles(CardDisplayData card) {
        if (card == null) {
            return List.of();
        }
        return switch (safe(card.getKind())) {
            case "PROPERTY" -> propertyImages(safe(card.getColorGroup()));
            case "WILD" -> wildImages(card);
            case "MONEY" -> moneyImages(card.getValueM() == null ? 0 : card.getValueM());
            case "ACTION" -> actionImages(card);
            default -> List.of();
        };
    }

    private static List<String> propertyImages(String color) {
        return switch (color) {
            case "BROWN" -> List.of("05-Property Card - Brown.jpg", "06-Property Card - Brown.jpg");
            case "LIGHT_BLUE" -> List.of("01-Property Card - Light Blue.jpg", "02-Property Card - Light Blue.jpg", "03-Property Card - Light Blue.jpg");
            case "PINK" -> List.of("17-Property Card - Pink.jpg", "18-Property Card - Pink.jpg", "19-Property Card - Pink.jpg");
            case "ORANGE" -> List.of("25-Property Card - Orange.jpg", "26-Property Card - Orange.jpg", "27-Property Card - Orange.jpg");
            case "RED" -> List.of("35-Property Card - Red.jpg", "36-Property Card - Red.jpg", "37-Property Card - Red.jpg");
            case "YELLOW" -> List.of("32-Property Card - Yellow.jpg", "33-Property Card - Yellow.jpg", "34-Property Card - Yellow.jpg");
            case "GREEN" -> List.of("43-Property Card - Green.jpg", "44-Property Card - Green.jpg", "45-Property Card - Green.jpg");
            case "DARK_BLUE" -> List.of("48-Property Card - Blue.jpg", "49-Property Card - Blue.jpg");
            case "RAILROAD" -> List.of("28-Property Card - Railroad.jpg", "29-Property Card - Railroad.jpg", "30-Property Card - Railroad.jpg", "31-Property Card - Railroad.jpg");
            case "UTILITY" -> List.of("22-Property Card - Utility.jpg", "23-Property Card - Utility.jpg");
            default -> List.of();
        };
    }

    private static List<String> wildImages(CardDisplayData card) {
        if ("ANY_COLOR".equals(safe(card.getWildKind()))) {
            return List.of("53-Property Wild Card - Multi-Color.jpg", "54-Property Wild Card - Multi-Color.jpg");
        }
        return switch (pairKey(card.getPrintedColors())) {
            case "LIGHT_BLUE|BROWN", "BROWN|LIGHT_BLUE" -> List.of("04-Property Wild Card - Light Blue Brown.jpg");
            case "LIGHT_BLUE|RAILROAD", "RAILROAD|LIGHT_BLUE" -> List.of("50-Property Wild Card - Light Blue Railroad.jpg");
            case "PINK|ORANGE", "ORANGE|PINK" -> List.of("20-Property Wild Card - Pink Orange.jpg", "21-Property Wild Card - Pink Orange.jpg");
            case "RED|YELLOW", "YELLOW|RED" -> List.of("38-Property Wild Card - Red Yellow.jpg", "39-Property Wild Card - Red Yellow.jpg");
            case "DARK_BLUE|GREEN", "GREEN|DARK_BLUE" -> List.of("47-Property Wild Card - Dark Blue Green.jpg");
            case "GREEN|RAILROAD", "RAILROAD|GREEN" -> List.of("46-Property Wild Card - Green Railroad.jpg");
            case "RAILROAD|UTILITY", "UTILITY|RAILROAD" -> List.of("24-Property Wild Card - Railroad Utility.jpg");
            default -> List.of("53-Property Wild Card - Multi-Color.jpg", "54-Property Wild Card - Multi-Color.jpg");
        };
    }

    private static List<String> moneyImages(int valueM) {
        return switch (valueM) {
            case 1 -> List.of("55-Money Card - 1M.jpg", "56-Money Card - 1M.jpg", "57-Money Card - 1M.jpg", "58-Money Card - 1M.jpg", "59-Money Card - 1M.jpg", "60-Money Card - 1M.jpg");
            case 2 -> List.of("73-Money Card - 2M.jpg", "74-Money Card - 2M.jpg", "75-Money Card - 2M.jpg", "76-Money Card - 2M.jpg", "77-Money Card - 2M.jpg");
            case 3 -> List.of("81-Money Card - 3M.jpg", "82-Money Card - 3M.jpg", "83-Money Card - 3M.jpg");
            case 4 -> List.of("96-Money Card - 4M.jpg", "97-Money Card - 4M.jpg", "98-Money Card - 4M.jpg");
            case 5 -> List.of("104-Money Card - 5M.jpg", "105-Money Card - 5M.jpg");
            case 10 -> List.of("108-Money Card - 10M.jpg");
            default -> List.of();
        };
    }

    private static List<String> actionImages(CardDisplayData card) {
        String effect = safe(card.getEffectCode());
        if ("RENT".equals(effect)) {
            return List.of("40-Rent Card - Any Rent.jpg", "41-Rent Card - Any Rent.jpg", "42-Rent Card - Any Rent.jpg");
        }
        if ("RENT_DUAL".equals(effect)) {
            return switch (pairKey(card.getRentPalette())) {
                case "LIGHT_BLUE|BROWN", "BROWN|LIGHT_BLUE" -> List.of("11-Rent Card - Light Blue Brown.jpg", "12-Rent Card - Light Blue Brown.jpg");
                case "PINK|ORANGE", "ORANGE|PINK" -> List.of("09-Rent Card - Pink Orange.jpg", "10-Rent Card - Pink Orange.jpg");
                case "RED|YELLOW", "YELLOW|RED" -> List.of("15-Rent Card - Red Yellow.jpg", "16-Rent Card - Red Yellow.jpg");
                case "DARK_BLUE|GREEN", "GREEN|DARK_BLUE" -> List.of("13-Rent Card - Dark Blue Green.jpg", "14-Rent Card - Dark Blue Green.jpg");
                case "RAILROAD|UTILITY", "UTILITY|RAILROAD" -> List.of("07-Rent Card - Railroad Utility.jpg", "08-Rent Card - Railroad Utility.jpg");
                default -> List.of();
            };
        }
        return switch (effect) {
            case "PASS_GO" -> List.of("61-Action Card - Pass Go.jpg", "62-Action Card - Pass Go.jpg", "63-Action Card - Pass Go.jpg", "64-Action Card - Pass Go.jpg", "65-Action Card - Pass Go.jpg", "66-Action Card - Pass Go.jpg", "67-Action Card - Pass Go.jpg", "68-Action Card - Pass Go.jpg", "69-Action Card - Pass Go.jpg", "70-Action Card - Pass Go.jpg");
            case "DOUBLE_RENT" -> List.of("71-Action Card - Double The Rent.jpg", "72-Action Card - Double The Rent.jpg");
            case "BIRTHDAY" -> List.of("78-Action Card - Its My Birthday.jpg", "79-Action Card - Its My Birthday.jpg", "80-Action Card - Its My Birthday.jpg");
            case "DEBT_COLLECTOR" -> List.of("84-Action Card - Debt Collector.jpg", "85-Action Card - Debt Collector.jpg", "86-Action Card - Debt Collector.jpg");
            case "STEAL_PROPERTY" -> List.of("87-Action Card - Sly Deal.jpg", "88-Action Card - Sly Deal.jpg", "89-Action Card - Sly Deal.jpg");
            case "HOUSE" -> List.of("90-Action Card - House.jpg", "91-Action Card - House.jpg", "92-Action Card - House.jpg");
            case "FORCED_DEAL" -> List.of("93-Action Card - Forced Deal.jpg", "94-Action Card - Forced Deal.jpg", "95-Action Card - Forced Deal.jpg");
            case "RENT_WAIVER" -> List.of("99-Action Card - Just Say No.jpg", "100-Action Card - Just Say No (2).jpg", "101-Action Card - Just Say No (1).jpg");
            case "HOTEL" -> List.of("102-Action Card - Hotel.jpg", "103-Action Card - Hotel.jpg");
            case "DEAL_BREAKER" -> List.of("106-Action Card - Deal Breaker.jpg", "107-Action Card - Deal Breaker.jpg");
            default -> List.of();
        };
    }

    private static int stableIndex(String seed, int size) {
        int hash = 0;
        for (int i = 0; i < seed.length(); i++) {
            hash = ((hash << 5) - hash) + seed.charAt(i);
        }
        return Math.floorMod(hash, size);
    }

    private static String pairKey(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .map(CardImageResolver::safe)
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
