# Monopoly Deal Cards Game Requirements

## 1. User Requirements

- **Game mode:** The system supports 2-5 players, with free selection between AI opponents and human players.
- **Interactive experience:** Players can draw cards, play cards, pay rent, and manage properties through the GUI.
- **Information transparency:** Players can view real-time public deck status, their own assets, and opponents' disclosed assets, while hand cards remain private.
- **Automatic judgment:** The system automatically verifies rules, calculates rent, and announces victory conditions.
- **Progress management:** The system allows game progress to be saved and restored.

## 2. System Requirements

### 2.1 Functional Requirements

#### 2.1.1 Startup and Initialization

- The system provides both human-vs-machine and player-vs-player modes.
- In human-vs-machine mode, AI difficulty supports Easy, Normal, and Hard.
- The system supports creating a game with 2-5 players and assigns first turn order randomly.
- At game start, the system generates a standard 108-card deck, deals 5 cards to each player, and initializes deck/discard piles.
- The system provides an in-game rule panel.

#### 2.1.2 Turn and Action Control

- At turn start, the player draws 2 cards; if the hand is empty before drawing, the player draws 5 cards.
- After drawing, a player can play up to 3 cards in total during the turn.
- Supported play operations include bank deposit, property deployment, action card execution, and discard actions.
- At end of turn, if hand size exceeds 7, the player must discard until 7 cards remain.

#### 2.1.3 Core Mechanics

- The system calculates rent based on completed property sets and applies payment rules.
- Rent payment uses bank cards and/or property cards; hand cards cannot be used for direct payment.
- Overpayment returns no change.
- Wild property cards can be reassigned by color and are included in property-set completion checks.
- The system performs real-time legality checks for action validity and target validity.
- If the draw pile is empty, the discard pile is reshuffled into a new draw pile.

#### 2.1.4 Feedback and Guidance

- The UI displays turn player, deck count, and discard count in real time.
- Each player sees real-time hand count, property zone state, and bank state.
- Opponents' public state is visible, but only hand counts are shown for hand privacy.
- The system provides immediate prompts for draw/play/discard results.

#### 2.1.5 Victory and Session End

- The system continuously checks victory conditions and ends the session when a player wins.
- The system can forcibly terminate a session when all players quit or when session time exceeds the defined limit.

#### 2.1.6 Pause and Save

- In human-vs-machine mode, pause/resume/save/exit operations are supported.
- In player-vs-player mode, pause and load actions require multi-player confirmation.
- The system supports manual save/load.
- The system supports periodic automatic save based on completed rounds.

### 2.2 Non-Functional Requirements

#### 2.2.1 Product Requirements

- **Usability:** The GUI should be intuitive for users aged 8+ and provide clear operation feedback.
- **Performance:** Gameplay operations and synchronization should remain responsive under multiplayer load.
- **Reliability:** Invalid inputs must be handled safely without crashes.
- **Data integrity:** Saved data should remain consistent under network fluctuation or unexpected shutdown.

#### 2.2.2 Organizational and Delivery Constraints

- Backend uses Java (JDK 17+); frontend uses Flutter.
- The architecture follows MVC separation principles.
- The project uses Git-based collaboration and clear commit history.
- Delivery targets standard OS environments (Windows/macOS/Linux).

#### 2.2.3 External Constraints

- The design should follow maintainable software engineering practices.
- Multiplayer privacy must prevent unauthorized access to other players' hand details.
- Local progress data must be protected against tampering.
