# Monopoly Deal — Software Requirements (English)

This document is the **English** requirements baseline for the Monopoly Deal digital card-game project. It is aligned with `[requirements.md](requirements.md)` (same semantics). Card-level rule details and deck composition are defined in `[../../rules.md](../../rules.md)` (Appendix A and the card factory are normative for implementation).

---

## Document Scope


| Aspect               | Description                                                                                                                                        |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Project goal**     | Implement Monopoly Deal as a desktop application with AI and human opponents, rule enforcement, rent settlement, synchronization, and persistence. |
| **Primary audience** | Course deliverables, grading, and team alignment on scope.                                                                                         |


---

## 1. User Requirements

User requirements describe goals and experience expectations from the player’s point of view.


| ID        | Requirement                                                                                                                                                                 |
| --------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **UR-01** | **Game mode:** Support **2–5 players** with flexible combinations of **AI opponents** and **human players**.                                                                |
| **UR-02** | **Interactive experience:** Players can **draw**, **play**, **pay rent**, and **manage properties** through the graphical user interface.                                   |
| **UR-03** | **Information transparency:** Show real-time **public deck state**, **own assets**, and **opponents’ disclosed assets**; **hand contents remain private** to other players. |
| **UR-04** | **Automatic adjudication:** The system **validates rules**, **computes rent**, and **evaluates win conditions** automatically.                                              |
| **UR-05** | **Progress management:** Support **saving and restoring** game progress.                                                                                                    |


---

## 2. System Requirements

### 2.1 Functional Requirements

#### FR-1 — Startup and Initialization

- Provide **human-vs-machine** and **player-vs-player** modes.
- In human-vs-machine mode, AI difficulty levels: **Easy**, **Normal**, and **Hard**.
- Support sessions with **2–5 players** and **random first-player** selection.
- On start: build a **standard 108-card deck**, deal **5 cards** to each player, and initialize **draw** and **discard** piles.
- Provide an **in-game rules** panel.

#### FR-2 — Turn and Action Control

- At turn start, draw **2** cards; if the hand is **empty** immediately before drawing, draw **5** cards instead.
- After drawing, the player may play **up to 3 cards** in total during that turn.
- Supported actions include **bank deposits**, **property deployment**, **action-card resolution**, and **discards**, subject to rules.
- At end of turn, if the hand size **exceeds 7**, the player must **discard down to 7**.

#### FR-3 — Core Mechanics

- **Calculate rent** from **completed property sets** and apply **payment** rules.
- Rent is paid only with cards already on the table (**bank** and/or **property zones**); **hands cannot pay directly**.
- **No change** is returned for overpayment.
- **Wild property** cards may be **reassigned** (e.g., by color) and participate in **set completion** checks where applicable.
- Perform **real-time validity checks** for actions and targets.
- If the **draw pile** is empty, **reshuffle the discard pile** into a new draw pile.

#### FR-4 — Feedback and Guidance

- Show **current turn player**, **draw pile count**, and **discard pile count** in real time.
- Each player sees **hand count**, **property zone**, and **bank** state live.
- Opponents’ **public** information is visible; for privacy, opponents see **hand counts only**, not card identities.
- Provide **immediate feedback** after draw, play, and discard operations.

#### FR-5 — Victory and Session End

- **Continuously evaluate** win conditions and **end** the session when a player wins.
- **Forcibly end** a session when **all players leave** or **session duration** exceeds the configured limit.

#### FR-6 — Pause and Save

- Human-vs-machine: support **pause**, **resume**, **save**, and **exit**.
- Player-vs-player: **pause** and **load** require **multi-player confirmation**.
- Support **manual** save and load.
- Support **periodic automatic save** triggered by completed rounds.

---

### 2.2 Non-Functional Requirements

#### NFR-1 — Product Qualities

- **Usability:** The GUI should be intuitive for users **aged 8+** with clear feedback for actions.
- **Performance:** Gameplay actions and state synchronization remain **responsive** under multiplayer load.
- **Reliability:** Invalid inputs are handled **safely** without crashing the client or server.
- **Data integrity:** Saved state remains **consistent** under network instability or unexpected shutdown.

#### NFR-2 — Organizational and Delivery Constraints

- **Backend:** **Java** (JDK **17+**).
- **Desktop UI:** **JavaFX** with **FXML** layouts.
- **Architecture:** **MVC** separation.
- **Collaboration:** **Git** with a clear commit history.
- **Deployment:** Standard desktop OS targets (**Windows**, **macOS**, **Linux**).

#### NFR-3 — External Constraints

- Follow **maintainable** software engineering practices.
- **Multiplayer privacy:** prevent **unauthorized** visibility of other players’ **hand contents**.
- **Local saves:** protect progress data against **tampering** (e.g., encryption or integrity checks as implemented).

---

## 3. Traceability and Related Documents


| Document                                                                                                         | Purpose                                                           |
| ---------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `[requirements.md](requirements.md)`                                                                             | Canonical requirements text (English, baseline).                  |
| `[../../rules.md](../../rules.md)`                                                                               | Card-game rules (Chinese summary + Appendix A vs implementation). |
| `[../implementation/requirement-trace-and-deviations.md](../implementation/requirement-trace-and-deviations.md)` | Implementation status, deviations, and follow-ups.                |
| `[../architecture/uml_source.md](../architecture/uml_source.md)`                                                 | Architecture notes and UML sources/exports.                       |


---

## 4. Revision Policy

Do **not** change the **meaning** of historic requirements without team review. When interpretation or implementation diverges, update the **traceability** document in the same change set.