package com.monopoly.persistence;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;

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
            p.buildingLevel = w.getBuildingLevel() != null ? w.getBuildingLevel().name() : BuildingLevel.BASE.name();
        } else if (card instanceof PropertyCard pc) {
            p.colorGroup = pc.getColorGroup();
            p.buildingLevel = pc.getBuildingLevel() != null ? pc.getBuildingLevel().name() : BuildingLevel.BASE.name();
        } else if (card instanceof ActionCard a) {
            p.effectCode = a.getEffectCode();
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
                PropertyWildCard w = new PropertyWildCard(p.id, nm);
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
            case "ActionCard" -> new ActionCard(p.id, nm, p.effectCode != null ? p.effectCode : "BIRTHDAY");
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

    public String getAssignedColorKey() {
        return assignedColorKey;
    }

    public void setAssignedColorKey(String assignedColorKey) {
        this.assignedColorKey = assignedColorKey;
    }
}
