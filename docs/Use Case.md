用例图的生成代码如下所示，使用 PlantUML 语法编写：


@startuml
left to right direction
skinparam packageStyle rectangle
skinparam NoteBackgroundColor #FEF9E7
skinparam NoteBorderColor #F1C40F
skinparam usecase {
BackgroundColor #EBF4FA
BorderColor #2874A6
ArrowColor #2874A6
}
skinparam actor {
BackgroundColor #FCF3CF
BorderColor #B7950B
}

title Monopoly Deal - System Use Case Diagram

' === 定义参与者 ===
actor "Human Player\n(真实玩家)" as Human
actor "AI Player\n(电脑玩家)" as AI

' 次要参与者（放在右侧，表示系统后台自动触发的机制）
actor "Game System\n(游戏系统)" as System <<Secondary>>

' === 定义系统边界 ===
rectangle "Monopoly Deal System Boundary" {

    ' 1. 游戏大厅与会话管理
    package "1. Session Management" <<Rectangle>> {
        usecase "Create / Join Game\n(创建/加入对局)" as UC_Session
        usecase "Save Game\n(保存当前进度)" as UC_Save
        usecase "Load Game\n(读取游戏进度)" as UC_Load
    }

    ' 2. 玩家回合内核心操作
    package "2. Core Turn Actions" <<Rectangle>> {
        usecase "Draw Cards\n(摸牌)" as UC_Draw
        usecase "Play Card\n(出牌)" as UC_Play
        usecase "Discard Cards\n(弃牌)" as UC_Discard
        usecase "End Turn\n(结束回合)" as UC_EndTurn
    }

    ' 3. 出牌的具体扩展行为
    package "3. Play Extensions" <<Rectangle>> {
        usecase "Deposit to Bank\n(存入银行)" as UC_Deposit
        usecase "Deploy Property\n(部署房产)" as UC_Deploy
        usecase "Execute Action Card\n(执行行动牌)" as UC_Action
    }

    ' 4. 互动与结算机制
    package "4. Interaction & Settlement" <<Rectangle>> {
        usecase "Pay Rent / Debt\n(支付租金/抵债)" as UC_Pay
        usecase "Declare Win\n(宣告胜利)" as UC_Win
    }
}

' === 参与者与用例的关联 ===
Human --> UC_Session
Human --> UC_Save
Human --> UC_Load

Human --> UC_Draw
Human --> UC_Play
Human --> UC_Discard
Human --> UC_EndTurn
Human --> UC_Pay

' AI 不参与大厅管理，只参与游戏内核心操作
AI --> UC_Draw
AI --> UC_Play
AI --> UC_Discard
AI --> UC_EndTurn
AI --> UC_Pay

' 系统参与结算与胜利判定
UC_Win <-- System
UC_Pay <-- System : "Triggers passive payment"

' === 核心逻辑关系 (Include / Extend) ===
' 出牌动作的 3 种扩展
UC_Deposit .up.> UC_Play : <<extend>>
UC_Deploy .up.> UC_Play : <<extend>>
UC_Action .up.> UC_Play : <<extend>>

' 弃牌是结束回合时的条件触发动作
UC_Discard .up.> UC_EndTurn : <<extend>>\n[if hand > 7]

' 胜利判定是出牌/回合推进时的条件触发动作
UC_Win .up.> UC_Play : <<extend>>\n[if 3 property sets completed]
@enduml