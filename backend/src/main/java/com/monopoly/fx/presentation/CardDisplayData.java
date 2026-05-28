package com.monopoly.fx.presentation;

import com.google.gson.JsonObject;
import com.monopoly.fx.I18n;

/**
 * MY_HAND 单张牌在客户端的展示模型（兼容旧版仅 id/name）。
 */
public final class CardDisplayData {

    private final String id;
    private final String kind;
    private final String titleZh;
    private final String titleEn;
    private final String hintZh;
    private final String hintEn;
    private final String rentDetailZh;
    private final String rentDetailEn;
    private final String colorGroup;
    private final String effectCode;
    private final Integer valueM;
    private final Integer setNeed;

    public CardDisplayData(
            String id,
            String kind,
            String titleZh,
            String titleEn,
            String hintZh,
            String hintEn,
            String rentDetailZh,
            String rentDetailEn,
            String colorGroup,
            String effectCode,
            Integer valueM,
            Integer setNeed) {
        this.id = id;
        this.kind = kind;
        this.titleZh = titleZh;
        this.titleEn = titleEn;
        this.hintZh = hintZh;
        this.hintEn = hintEn;
        this.rentDetailZh = rentDetailZh;
        this.rentDetailEn = rentDetailEn;
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

    public String getTitleEn() {
        return titleEn;
    }

    public String getTitle() {
        return I18n.isChinese() ? titleZh : (titleEn != null && !titleEn.isBlank() ? titleEn : titleZh);
    }

    public String getHintZh() {
        return hintZh;
    }

    public String getHintEn() {
        return hintEn;
    }

    public String getHint() {
        return I18n.isChinese() ? hintZh : (hintEn != null && !hintEn.isBlank() ? hintEn : hintZh);
    }

    public String getRentDetailZh() {
        return rentDetailZh;
    }

    public String getRentDetailEn() {
        return rentDetailEn;
    }

    public String getRentDetail() {
        return I18n.isChinese() ? rentDetailZh : (rentDetailEn != null && !rentDetailEn.isBlank() ? rentDetailEn : rentDetailZh);
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
        String titleEn = str(c, "titleEn", null);
        String hintZh = str(c, "hintZh", null);
        String hintEn = str(c, "hintEn", null);
        if (titleZh == null || titleZh.isBlank()) {
            titleZh = fallbackTitle(kind, name);
        }
        if (titleEn == null || titleEn.isBlank()) {
            titleEn = fallbackTitle(kind, name);
        }
        if (hintZh == null) {
            hintZh = "";
        }
        if (hintEn == null) {
            hintEn = "";
        }
        String rentDetailZh = str(c, "rentDetailZh", "");
        String rentDetailEn = str(c, "rentDetailEn", "");
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
        return new CardDisplayData(id, kind, titleZh, titleEn, hintZh, hintEn, rentDetailZh, rentDetailEn, colorGroup, effectCode, valueM, setNeed);
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
            case "MONEY" -> I18n.get("card.money");
            case "PROPERTY" -> I18n.get("card.property");
            case "WILD" -> I18n.get("card.wildProperty");
            case "ACTION" -> I18n.get("card.actionCard");
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
