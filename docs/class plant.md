下面的是类图的代码，直接拷贝到网页版plantuml即可


@startuml
skinparam classAttributeIconSize 0
skinparam linetype ortho
skinparam nodesep 60
skinparam ranksep 60
skinparam monochrome false
skinparam packageStyle rectangle

title Monopoly Deal Cards Game - Architecture Class Diagram

' === 1. Model 层 ===
package "com.monopoly.model" #EBF4FA {

    interface Playable {
        + executeEffect(context: GameContext): void
    }
    
    interface Payable {
        + getFaceValue(): int
    }
    
    abstract class Card {
        - id: String
        - name: String
        - colorType: String
        + getId(): String
        + getName(): String
    }
    
    class PropertyCard extends Card implements Payable {
        - rentValues: int[]
        + getFaceValue(): int
    }
    
    class ActionCard extends Card implements Playable {
        - actionType: String
        + executeEffect(context: GameContext): void
    }

    abstract class Player {
        - playerId: String
        - handCards: List<Card>
        + drawCards(amount: int): void
        + playCard(card: Card): void
    }
    
    class HumanPlayer extends Player
    class AIPlayer extends Player
    
    class GameContext {
        - activePlayers: List<Player>
        - discardPile: List<Card>
        + getActivePlayers(): List<Player>
    }
    
    package "dto" #D6EAF8 {
        class GameStateSnapshot {
            + currentPlayerId: String
            + remainingDeckSize: int
            + playersState: Map<String, Object>
        }
    }
}

' === 2. Pattern 层 ===
package "com.monopoly.pattern" {

    package "strategy" #E8F8F5 {
        interface AiPlayStrategy {
            + determineNextMove(player: AIPlayer, context: GameContext): Card
        }
        class EasyAiPlayStrategy implements AiPlayStrategy
        class NormalAiPlayStrategy implements AiPlayStrategy
        class HardAiPlayStrategy implements AiPlayStrategy
        
        note right of AiPlayStrategy : **Strategy Pattern**\nDynamically swaps AI logic.
    }
    AIPlayer *-- AiPlayStrategy : "uses >"

    package "factory" #FEF9E7 {
        interface CardFactory {
            + createStandardDeck(): List<Card>
        }
        class MonopolyDealCardFactory implements CardFactory {
            + createStandardDeck(): List<Card>
            - createProperties(): void
        }
        note top of CardFactory : **Factory Pattern**\nEncapsulates deck creation.
    }
    MonopolyDealCardFactory ..> Card : "<<creates>>"

    package "observer" #FDEDEC {
        interface GameUpdateObserver {
            + onGameStateUpdated(snapshot: GameStateSnapshot): void
        }
        
        interface GameUpdateSubject {
            + attach(obs: GameUpdateObserver): void
            + notifyAll(snapshot: GameStateSnapshot): void
        }
        
        class DefaultGameUpdateSubject implements GameUpdateSubject {
            - observers: List<GameUpdateObserver>
            + attach(obs: GameUpdateObserver): void
            + notifyAll(snapshot: GameStateSnapshot): void
        }
        note left of GameUpdateSubject : **Observer Pattern**\nDecouples Model from Network.
    }
    DefaultGameUpdateSubject o-- GameUpdateObserver : "notifies >"

    package "singleton" #F5EEF8 {
        class GameEngineSingleton <<Singleton>> {
            - {static} instance: GameEngineSingleton
            + {static} getInstance(): GameEngineSingleton
        }
    }
}

' === 3. Controller 层 ===
package "com.monopoly.controller" #F4ECF7 {

    class TurnManager {
        - currentPlayerIndex: int
        - remainingActions: int
        + advanceTurn(): void
    }

    class GameController <<Facade>> {
        - turnManager: TurnManager
        - cardFactory: CardFactory
        - stateSubject: GameUpdateSubject
        + startGame(): void
        + handlePlayerAction(playerId: String): void
    }
    note right of GameController : **Facade Pattern**\nUnified API for Network.
}

GameEngineSingleton *-- GameController
GameController *-- TurnManager : "composes >"
GameController --> DefaultGameUpdateSubject : "triggers >"
GameController --> CardFactory : "uses >"

' === 4. Network 层 ===
package "com.monopoly.network" #FCF3CF {
class GameServer {
- port: int
+ startServer(): void
}

    class ClientConnection implements GameUpdateObserver {
        - playerId: String
        + onGameStateUpdated(snapshot: GameStateSnapshot): void
    }
    
    class MessageDispatcher {
        + dispatchMessage(json: String, controller: GameController): void
    }
}

GameServer *-- ClientConnection : "manages >"
ClientConnection --> MessageDispatcher : "routes msg >"
MessageDispatcher --> GameController : "calls API >"

@enduml