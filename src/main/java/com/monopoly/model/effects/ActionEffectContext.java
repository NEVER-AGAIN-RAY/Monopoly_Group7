package com.monopoly.model.effects;

import com.monopoly.model.Card;
import com.monopoly.model.Player;
import com.monopoly.model.PropertyCard;
import com.monopoly.model.StealTargetZone;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.List;

/**
 * 行动卡效果执行时的完整上下文：持有施效者、目标、全局牌堆等引用。
 */
public class ActionEffectContext {

    private final Player actor;
    private final Player target;
    private final List<Player> allPlayers;
    private final GameEngineSingleton engine;
    private final String targetColorKey;
    private final PropertyCard targetProperty;
    private final PropertyCard actorProperty;
    private final Card targetBankCard;
    private final StealTargetZone stealTargetZone;

    private ActionEffectContext(Builder b) {
        this.actor = b.actor;
        this.target = b.target;
        this.allPlayers = b.allPlayers;
        this.engine = b.engine;
        this.targetColorKey = b.targetColorKey;
        this.targetProperty = b.targetProperty;
        this.actorProperty = b.actorProperty;
        this.targetBankCard = b.targetBankCard;
        this.stealTargetZone = b.stealTargetZone;
    }

    public Player getActor() {
        return actor;
    }

    public Player getTarget() {
        return target;
    }

    public List<Player> getAllPlayers() {
        return allPlayers;
    }

    public GameEngineSingleton getEngine() {
        return engine;
    }

    public String getTargetColorKey() {
        return targetColorKey;
    }

    public PropertyCard getTargetProperty() {
        return targetProperty;
    }

    /**
     * 己方财产区目标：强制交换时为己方出让房产；HOUSE/HOTEL 时为要打出的升级目标房产（由 actorCardId / targetCardId 解析）。
     */
    public PropertyCard getActorProperty() {
        return actorProperty;
    }

    /** 偷银行牌时指定的目标银行卡牌；财产区偷牌时为 null */
    public Card getTargetBankCard() {
        return targetBankCard;
    }

    public StealTargetZone getStealTargetZone() {
        return stealTargetZone;
    }

    public static Builder builder(Player actor, GameEngineSingleton engine, List<Player> allPlayers) {
        return new Builder(actor, engine, allPlayers);
    }

    public static final class Builder {
        private final Player actor;
        private final GameEngineSingleton engine;
        private final List<Player> allPlayers;
        private Player target;
        private String targetColorKey;
        private PropertyCard targetProperty;
        private PropertyCard actorProperty;
        private Card targetBankCard;
        private StealTargetZone stealTargetZone = StealTargetZone.PROPERTY;

        private Builder(Player actor, GameEngineSingleton engine, List<Player> allPlayers) {
            this.actor = actor;
            this.engine = engine;
            this.allPlayers = allPlayers;
        }

        public Builder target(Player target) {
            this.target = target;
            return this;
        }

        public Builder colorKey(String colorKey) {
            this.targetColorKey = colorKey;
            return this;
        }

        public Builder targetProperty(PropertyCard card) {
            this.targetProperty = card;
            return this;
        }

        public Builder actorProperty(PropertyCard card) {
            this.actorProperty = card;
            return this;
        }

        public Builder targetBankCard(Card card) {
            this.targetBankCard = card;
            return this;
        }

        public Builder stealTargetZone(StealTargetZone zone) {
            this.stealTargetZone = zone != null ? zone : StealTargetZone.PROPERTY;
            return this;
        }

        public ActionEffectContext build() {
            return new ActionEffectContext(this);
        }
    }
}
