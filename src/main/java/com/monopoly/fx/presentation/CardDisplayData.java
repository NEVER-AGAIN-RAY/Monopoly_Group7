package com.monopoly.fx.presentation;

import com.google.gson.JsonObject;

/**
 * MY_HAND 单张牌在客户端的展示模型（兼容旧版仅 id/name）。
 */
public final class CardDisplayData {

    private final String id;
    private final String kind;
    private final String titleZh;
    private final String hintZh;
    private final String rentDetailZh;
    private final String colorGroup;
    private final String effectCode;
    private final Integer valueM;
    /** 该色凑齐完整套所需张数（仅房产牌常见）。 */
    private final Integer setNeed;

    public CardDisplayData(
            String id,
            String kind,
            String titleZh,
            String hintZh,
            String rentDetailZh,
            String colorGroup,
            String effectCode,
            Integer valueM,
            Integer setNeed) {
        this.id = id;
        this.kind = kind;
        this.titleZh = titleZh;
        this.hintZh = hintZh;
        this.rentDetailZh = rentDetailZh;
        this.colorGroup = colorGroup;
        this.effectCode = effectCode;
        this.valueM = valueM;
        this.setNeed = setNeed;
    }

    public String getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public String getTitleZh() {
        return titleZh;
    }

    public String getHintZh() {
        return hintZh;
    }

    /** 与 RentCalculator / PropertySetCalculator 一致的中文租金轨道说明（房产/万能）。 */
    public String getRentDetailZh() {
        return rentDetailZh;
    }

    public String getColorGroup() {
        return colorGroup;
    }

    public String getEffectCode() {
        return effectCode;
    }

    public Integer getValueM() {
        return valueM;
    }

    public Integer getSetNeed() {
        return setNeed;
    }

    public static CardDisplayData fromHandCardJson(JsonObject c) {
        String id = str(c, "id", "");
        String name = str(c, "name", id);
        String kind = str(c, "kind", "").toUpperCase();
        if (kind.isEmpty()) {
            kind = inferKind(id, name);
        }
        String titleZh = str(c, "titleZh", null);
        String hintZh = str(c, "hintZh", null);
        if (titleZh == null || titleZh.isBlank()) {
            titleZh = fallbackTitle(kind, name);
        }
        if (hintZh == null) {
            hintZh = "";
        }
        String rentDetailZh = str(c, "rentDetailZh", "");
        String colorGroup = str(c, "colorGroup", "");
        String effectCode = str(c, "effectCode", "");
        Integer valueM = null;
        if (c.has("valueM") && !c.get("valueM").isJsonNull()) {
            try {
                valueM = c.get("valueM").getAsInt();
            } catch (RuntimeException ignored) {
                valueM = null;
            }
        }
        Integer setNeed = null;
        if (c.has("setNeed") && !c.get("setNeed").isJsonNull()) {
            try {
                setNeed = c.get("setNeed").getAsInt();
            } catch (RuntimeException ignored) {
                setNeed = null;
            }
        }
        return new CardDisplayData(id, kind, titleZh, hintZh, rentDetailZh, colorGroup, effectCode, valueM, setNeed);
    }

    private static String inferKind(String id, String name) {
        String u = (id + " " + name).toUpperCase();
        if (u.contains("MONEY") || u.matches(".*\\d+M.*")) {
            return "MONEY";
        }
        if (u.contains("WILD")) {
            return "WILD";
        }
        if (u.contains("PROPERTY") || u.contains("PROP")) {
            return "PROPERTY";
        }
        if (u.contains("ACTION") || u.contains("RENT") || u.contains("BIRTHDAY")) {
            return "ACTION";
        }
        return "UNKNOWN";
    }

    private static String fallbackTitle(String kind, String name) {
        return switch (kind) {
            case "MONEY" -> "现金";
            case "PROPERTY" -> "房产";
            case "WILD" -> "万能房产";
            case "ACTION" -> "行动牌";
            default -> name;
        };
    }

    private static String str(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return def == null ? "" : def;
        }
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            return def == null ? "" : def;
        }
    }
}
