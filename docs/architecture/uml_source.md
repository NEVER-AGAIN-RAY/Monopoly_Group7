# UML Source Documentation

This document consolidates UML-related sources into a single location.
Note: Some source comments are bilingual (English/Chinese) because they were originally authored that way.

## Class Diagram

Purpose: Describe the core architecture, model/controller/network boundaries, and major pattern relationships.

PlantUML source:

```plantuml
@startuml
skinparam classAttributeIconSize 0
skinparam linetype ortho
skinparam nodesep 60
skinparam ranksep 60
skinparam monochrome false
skinparam packageStyle rectangle

title Monopoly Deal Cards Game - Architecture Class Diagram

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
}

package "com.monopoly.pattern" {
    package "strategy" #E8F8F5 {
        interface AiPlayStrategy {
            + determineNextMove(player: AIPlayer, context: GameContext): Card
        }
        class EasyAiPlayStrategy implements AiPlayStrategy
        class NormalAiPlayStrategy implements AiPlayStrategy
        class HardAiPlayStrategy implements AiPlayStrategy
    }
    AIPlayer *-- AiPlayStrategy : "uses >"
    package "factory" #FEF9E7 {
        interface CardFactory {
            + createStandardDeck(): List<Card>
        }
        class MonopolyDealCardFactory implements CardFactory
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
        class DefaultGameUpdateSubject implements GameUpdateSubject
    }
    DefaultGameUpdateSubject o-- GameUpdateObserver : "notifies >"
    package "singleton" #F5EEF8 {
        class GameEngineSingleton <<Singleton>> {
            - {static} instance: GameEngineSingleton
            + {static} getInstance(): GameEngineSingleton
        }
    }
}

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
}

package "com.monopoly.network" #FCF3CF {
    class GameServer {
        - port: int
        + startServer(): void
    }
    class ClientConnection implements GameUpdateObserver
    class MessageDispatcher {
        + dispatchMessage(json: String, controller: GameController): void
    }
}

GameEngineSingleton *-- GameController
GameController *-- TurnManager : "composes >"
GameController --> DefaultGameUpdateSubject : "triggers >"
GameController --> CardFactory : "uses >"
GameServer *-- ClientConnection : "manages >"
ClientConnection --> MessageDispatcher : "routes msg >"
MessageDispatcher --> GameController : "calls API >"
@enduml
```

Image:

![Class Diagram](images/class-diagram.png)

## Sequence Diagram

Purpose: Describe the session lifecycle from session start through draw/play/end-turn and snapshot broadcasting.

PlantUML source:

```plantuml
@startuml
skinparam maxMessageSize 150
skinparam participantPadding 20
skinparam boxPadding 10
autonumber "<b>[00]"

title Monopoly Deal - Game Lifecycle Sequence Diagram

actor "Client / Network" as Client
participant "GameController\n<<Facade>>" as Controller
participant "CardFactory\n<<Factory>>" as Factory
participant "GameEngineSingleton\n<<Singleton>>" as Engine
participant "TurnManager" as TurnMgr
participant "GameUpdateSubject\n<<Observer>>" as Subject

== 1. Game Initialization Phase (Start Session) ==
Client -> Controller: startNewSession(sessionId)
Controller -> Factory: createStandardDeck108()
Factory --> Controller: List<Card> (108 cards)
Controller -> Engine: attachDrawPile(deck)
Engine --> Controller: void
Controller -> Controller: pushSnapshot(sessionId, "INIT")
Controller -> Subject: notifyAll(GameStateSnapshot)
Controller --> Client: Session started (ACK)

== 2. Player Draw Phase ==
Client -> Controller: drawCards(player, count, context)
Controller -> Controller: pushSnapshot("unknown", "DRAW")
Controller -> Subject: notifyAll(GameStateSnapshot)
Controller --> Client: Cards drawn (ACK)

== 3. Player Play Phase ==
Client -> Controller: playCard(player, cardId, context)
Controller -> Controller: pushSnapshot("unknown", "PLAY")
Controller -> Subject: notifyAll(GameStateSnapshot)
opt If played card is a Rent Card
    Controller -> Controller: requestRentPayment()
    Controller -> Controller: pushSnapshot("unknown", "RENT")
    Controller -> Subject: notifyAll(GameStateSnapshot)
end
Controller --> Client: Card played (ACK)

== 4. End Turn Phase ==
Client -> Controller: endTurn(player)
Controller -> TurnMgr: advanceTurn()
TurnMgr --> Controller: void
Controller -> Controller: pushSnapshot("unknown", "TURN_END")
Controller -> Subject: notifyAll(GameStateSnapshot)
Controller --> Client: Turn ended (ACK)
@enduml
```

Image:

![Sequence Diagram](images/sequence-diagram.png)

## Use Case Diagram

Purpose: Describe human/AI/system actor interactions and include/extend relationships for gameplay actions.

PlantUML source:

```plantuml
@startuml
left to right direction
skinparam packageStyle rectangle
title Monopoly Deal - System Use Case Diagram

actor "Human Player\n(真实玩家)" as Human
actor "AI Player\n(电脑玩家)" as AI
actor "Game System\n(游戏系统)" as System <<Secondary>>

rectangle "Monopoly Deal System Boundary" {
    package "1. Session Management" <<Rectangle>> {
        usecase "Create / Join Game\n(创建/加入对局)" as UC_Session
        usecase "Save Game\n(保存当前进度)" as UC_Save
        usecase "Load Game\n(读取游戏进度)" as UC_Load
    }
    package "2. Core Turn Actions" <<Rectangle>> {
        usecase "Draw Cards\n(摸牌)" as UC_Draw
        usecase "Play Card\n(出牌)" as UC_Play
        usecase "Discard Cards\n(弃牌)" as UC_Discard
        usecase "End Turn\n(结束回合)" as UC_EndTurn
    }
    package "3. Play Extensions" <<Rectangle>> {
        usecase "Deposit to Bank\n(存入银行)" as UC_Deposit
        usecase "Deploy Property\n(部署房产)" as UC_Deploy
        usecase "Execute Action Card\n(执行行动牌)" as UC_Action
    }
    package "4. Interaction & Settlement" <<Rectangle>> {
        usecase "Pay Rent / Debt\n(支付租金/抵债)" as UC_Pay
        usecase "Declare Win\n(宣告胜利)" as UC_Win
    }
}

Human --> UC_Session
Human --> UC_Save
Human --> UC_Load
Human --> UC_Draw
Human --> UC_Play
Human --> UC_Discard
Human --> UC_EndTurn
Human --> UC_Pay

AI --> UC_Draw
AI --> UC_Play
AI --> UC_Discard
AI --> UC_EndTurn
AI --> UC_Pay

UC_Win <-- System
UC_Pay <-- System : "Triggers passive payment"
UC_Deposit .up.> UC_Play : <<extend>>
UC_Deploy .up.> UC_Play : <<extend>>
UC_Action .up.> UC_Play : <<extend>>
UC_Discard .up.> UC_EndTurn : <<extend>>\n[if hand > 7]
UC_Win .up.> UC_Play : <<extend>>\n[if 3 property sets completed]
@enduml
```

Image:

![Use Case Diagram](images/use-case-diagram.png)
