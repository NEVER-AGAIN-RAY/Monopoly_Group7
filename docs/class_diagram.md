# Phase-1 核心类图设计

```mermaid
classDiagram
    %% 1. 继承关系 (Inheritance)
    Card <|-- PropertyCard : 继承
    Card <|-- ActionCard : 继承
    Card <|-- MoneyCard : 继承

    %% 2. 关联/聚合关系 (Association)
    GameController "1" --> "2..5" Player : 管理
    GameController "1" --> "1" Deck : 控制牌堆
    Player "1" --> "*" Card : 拥有手牌/资产
    Deck "1" --> "106" Card : 包含

    class Card {
        <<abstract>>
        +String name
        +int value
    }

    class PropertyCard {
        +String color
        +int rentAmount
    }

    class ActionCard {
        +String actionType
        +executeAction()
    }

    class MoneyCard {
    }

    class Player {
        +String name
        +List~Card~ handCards
        +List~Card~ bank
        +drawCards()
        +playCard()
    }

    class GameController {
        +List~Player~ players
        +startGame()
        +nextTurn()
        +checkWinner()
    }

    class Deck {
        +shuffle()
        +draw()
    }
