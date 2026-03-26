序列图的生成代码：

@startuml
skinparam maxMessageSize 150
skinparam participantPadding 20
skinparam boxPadding 10
skinparam NoteBackgroundColor #FEF9E7
skinparam NoteBorderColor #F1C40F
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
activate Controller

Controller -> Factory: createStandardDeck108()
activate Factory
Factory --> Controller: List<Card> (108 cards)
deactivate Factory

Controller -> Engine: attachDrawPile(deck)
activate Engine
Engine --> Controller: void
deactivate Engine

' 自调用 pushSnapshot
Controller -> Controller: pushSnapshot(sessionId, "INIT")
activate Controller
Controller -> Subject: notifyAll(GameStateSnapshot)
activate Subject
Subject --> Controller: void
deactivate Subject
deactivate Controller

Controller --> Client: Session started (ACK)
deactivate Controller

== 2. Player Draw Phase ==
Client -> Controller: drawCards(player, count, context)
activate Controller

note right of Controller: Evaluates deck size and\nmoves cards to player hand

Controller -> Controller: pushSnapshot("unknown", "DRAW")
activate Controller
Controller -> Subject: notifyAll(GameStateSnapshot)
activate Subject
Subject --> Controller: void
deactivate Subject
deactivate Controller

Controller --> Client: Cards drawn (ACK)
deactivate Controller

== 3. Player Play Phase ==
Client -> Controller: playCard(player, cardId, context)
activate Controller

Controller -> Controller: pushSnapshot("unknown", "PLAY")
activate Controller
Controller -> Subject: notifyAll(GameStateSnapshot)
activate Subject
Subject --> Controller: void
deactivate Subject
deactivate Controller

opt If played card is a Rent Card
Controller -> Controller: requestRentPayment()
activate Controller

    Controller -> Controller: pushSnapshot("unknown", "RENT")
    activate Controller
    Controller -> Subject: notifyAll(GameStateSnapshot)
    activate Subject
    Subject --> Controller: void
    deactivate Subject
    deactivate Controller
    
    deactivate Controller
end

Controller --> Client: Card played (ACK)
deactivate Controller

== 4. End Turn Phase ==
Client -> Controller: endTurn(player)
activate Controller

Controller -> TurnMgr: advanceTurn()
activate TurnMgr
TurnMgr --> Controller: void
deactivate TurnMgr

Controller -> Controller: pushSnapshot("unknown", "TURN_END")
activate Controller
Controller -> Subject: notifyAll(GameStateSnapshot)
activate Subject
Subject --> Controller: void
deactivate Subject
deactivate Controller

Controller --> Client: Turn ended (ACK)
deactivate Controller
@enduml