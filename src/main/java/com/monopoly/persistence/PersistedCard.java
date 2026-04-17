package com.monopoly.persistence;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gson 友好的卡牌快照：{@link #getId()} + 具体类型字段，用于存档往返，不依赖运行时单张实例引用。
 */
public final class PersistedCard {

    private String id;
    private String className;
    private String name;

    private Integer valueM;
    private String colorGroup;
    private String buildingLevel;
    private String effectCode;
    /** RENT_DUAL 卡面色组，如 {@code LIGHT_BLUE|BROWN} */
    private String rentPalette;
    private Boolean rentDualChargesEachOtherPlayer;
    private Boolean wildcardRentCard;
    /** {@link com.monopoly.model.card.PropertyWildCard.WildPropertyKind#name()} */
    private String wildKind;
    /** 双色万能印色 {@code A|B} */
    private String wildPrintedPair;
    private String assignedColorKey;

    public PersistedCard() {
    }

    public static PersistedCard fromCard(Card card) {
        if (card == null) {
            return null;
        }
        PersistedCard p = new PersistedCard();
        p.id = card.getId();
        p.className = card.getClass().getSimpleName();
        p.name = card.getName();
        if (card instanceof MoneyCard m) {
            p.valueM = m.getValueM();
        } else if (card instanceof PropertyWildCard w) {
            p.assignedColorKey = w.getAssignedColorKey();
            p.wildKind = w.getWildPropertyKind().name();
            if (w.getWildPropertyKind() == PropertyWildCard.WildPropertyKind.DUAL_COLOR
                    && w.getPrintedColorPairView().size() == 2) {
                p.wildPrintedPair = String.join("|", w.getPrintedColorPairView());
            }
            p.buildingLevel = w.getBuildingLevel() != null ? w.getBuildingLevel().name() : BuildingLevel.BASE.name();
        } else if (card instanceof PropertyCard pc) {
            p.colorGroup = pc.getColorGroup();
            p.buildingLevel = pc.getBuildingLevel() != null ? pc.getBuildingLevel().name() : BuildingLevel.BASE.name();
        } else if (card instanceof ActionCard a) {
            p.effectCode = a.getEffectCode();
            if (!a.getRentPaletteView().isEmpty()) {
                p.rentPalette = String.join("|", a.getRentPaletteView());
            }
            if (a.isRentDualChargesEachOtherPlayer()) {
                p.rentDualChargesEachOtherPlayer = true;
            }
            if (a.isWildcardRentCard()) {
                p.wildcardRentCard = true;
            }
        }
        return p;
    }

    public static Card toCard(PersistedCard p) {
        if (p == null || p.id == null || p.className == null) {
            throw new IllegalArgumentException("PersistedCard 缺少 id 或 className。");
        }
        String cn = p.className.trim();
        String nm = p.name != null && !p.name.isBlank() ? p.name : p.id;
        return switch (cn) {
            case "MoneyCard" -> {
                if (p.valueM == null || p.valueM <= 0) {
                    throw new IllegalArgumentException("MoneyCard 需要正整数 valueM: " + p.id);
                }
                yield new MoneyCard(p.id, nm, p.valueM);
            }
            case "PropertyWildCard" -> {
                PropertyWildCard.WildPropertyKind k = PropertyWildCard.WildPropertyKind.ANY_COLOR;
                if (p.wildKind != null && !p.wildKind.isBlank()) {
                    try {
                        k = PropertyWildCard.WildPropertyKind.valueOf(p.wildKind.trim());
                    } catch (IllegalArgumentException ignored) {
                        k = PropertyWildCard.WildPropertyKind.ANY_COLOR;
                    }
                }
                List<String> wpair = parseRentPaletteJoined(p.wildPrintedPair);
                if (k == PropertyWildCard.WildPropertyKind.ANY_COLOR) {
                    wpair = List.of();
                } else if (wpair.size() != 2) {
                    k = PropertyWildCard.WildPropertyKind.ANY_COLOR;
                    wpair = List.of();
                }
                PropertyWildCard w = new PropertyWildCard(p.id, nm, k, wpair);
                if (p.assignedColorKey != null && !p.assignedColorKey.isBlank()) {
                    w.setAssignedColorKey(p.assignedColorKey);
                }
                if (p.buildingLevel != null && !p.buildingLevel.isBlank()) {
                    w.setBuildingLevel(BuildingLevel.valueOf(p.buildingLevel.trim()));
                }
                yield w;
            }
            case "PropertyCard" -> {
                PropertyCard pc = new PropertyCard(p.id, nm, p.colorGroup);
                if (p.buildingLevel != null && !p.buildingLevel.isBlank()) {
                    pc.setBuildingLevel(BuildingLevel.valueOf(p.buildingLevel.trim()));
                }
                yield pc;
            }
            case "ActionCard" -> {
                String ec = p.effectCode != null ? p.effectCode : "BIRTHDAY";
                List<String> pal = parseRentPaletteJoined(p.rentPalette);
                boolean dualAll = p.rentDualChargesEachOtherPlayer != null && p.rentDualChargesEachOtherPlayer;
                boolean wildRent = p.wildcardRentCard != null && p.wildcardRentCard;
                int bv = com.monopoly.model.rules.MonopolyDealBankValues.bankValueForActionEffect(ec);
                if (pal.isEmpty()) {
                    yield new ActionCard(p.id, nm, ec, bv, List.of(), dualAll, wildRent);
                }
                yield new ActionCard(p.id, nm, ec, bv, pal, dualAll, wildRent);
            }
            default -> throw new IllegalArgumentException("不支持的卡牌类型: " + cn);
        };
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getValueM() {
        return valueM;
    }

    public void setValueM(Integer valueM) {
        this.valueM = valueM;
    }

    public String getColorGroup() {
        return colorGroup;
    }

    public void setColorGroup(String colorGroup) {
        this.colorGroup = colorGroup;
    }

    public String getBuildingLevel() {
        return buildingLevel;
    }

    public void setBuildingLevel(String buildingLevel) {
        this.buildingLevel = buildingLevel;
    }

    public String getEffectCode() {
        return effectCode;
    }

    public void setEffectCode(String effectCode) {
        this.effectCode = effectCode;
    }

    public String getRentPalette() {
        return rentPalette;
    }

    public void setRentPalette(String rentPalette) {
        this.rentPalette = rentPalette;
    }

    private static List<String> parseRentPaletteJoined(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : joined.split("\\|")) {
            if (part != null && !part.isBlank()) {
                out.add(part.trim().toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    public String getAssignedColorKey() {
        return assignedColorKey;
    }

    public void setAssignedColorKey(String assignedColorKey) {
        this.assignedColorKey = assignedColorKey;
    }
}
