# Monopoly Deal Cards Game — Requirements

## 1. User Requirements

- **Game mode:** The system supports 2–5 players, allowing free selection between AI opponents and real players.
- **Interactive experience:** Players should be able to perform card drawing, playing, rent payment, and property management through the graphical user interface (GUI).
- **Information transparency:** Players should be able to view real-time public deck status, their own assets, and their opponents’ disclosed assets, while maintaining privacy of their hand cards.
- **Automatic judgment:** The system should automatically perform complex rent collection calculations and rule verification, and immediately notify the player when victory conditions are met.
- **Progress management:** The system should allow players to save game progress across different modes for later restoration.

---

## 2. System Requirements

### 2.1 Functional Requirements

#### 2.1.1 Game Basic Startup and Initialization

a. **Mode selection:** The system should provide entry points for both “human vs. machine” and “player vs. player” modes. In human vs. machine mode, players should be able to select three AI difficulty levels: Easy, Normal, and Hard.

b. **Number of players and turn order:** The system supports 2–5 players to create a new game, randomly assigning one player as the first mover with clockwise turn rotation.

c. **Resource initialization:** When the game starts, the system should:

- Generate a deck using the standard 108-card configuration.
- Automatically deal 5 initial cards to each player.
- Display the remaining cards in the deck and initialize an empty discard pile.

d. **Rule view:** The system should integrate a game rules panel, allowing players to access and view them anytime in-game.

#### 2.1.2 Turn Logic and Action Control

a. **Card drawing mechanism:** During their turn, players must first draw 2 cards from the shared deck. If the player’s hand is empty before drawing, the system must allow them to draw 5 cards. After drawing, the system automatically updates the player’s hand count and refreshes the hand display area in real time.

b. **Playing cards (after drawing):** After drawing cards, players may play up to three cards per turn (cumulative across all play actions in that turn). Supported operations include placing cards into the property zone, the central action zone, the personal bank, or discarding. Supported operations include:

- **Bank deposit:** Players can deposit money cards and action cards into their personal bank stacks. After depositing, action cards lose their original functions and are only used for currency settlement.
- **Property placement:** Players can place property cards from their hand into their property zone to build a matching property set. The system automatically displays property zone cards by color.
- **Action execution:** Players may play action cards from their hand to the central action zone, triggering corresponding special effects (such as rent collection, card theft, rent waiver, etc.). The system automatically executes the effects and updates the game state after play.

c. **Round end and discard:** After completing all actions including drawing and playing cards, the player enters the discard phase. If the player’s hand exceeds 7 cards, the system must force them to discard to the discard pile until the hand reaches 7. Upon completion of discarding, the system terminates the current player’s turn and proceeds to the next player.

#### 2.1.3 Core Game Mechanisms

a. **Rent collection logic**

- The system automatically calculates rental fees based on the completion status of matching property sets (basic properties, houses with attached properties, or hotels with attached properties) in players’ property zones, in accordance with game rules, and sends rent collection requests to designated target players.

- **Payment processing:**
  - The system allows players to settle rent arrears using money cards from their personal bank or by moving property cards from the property zone to the discard pile; direct payment using hand cards is prohibited.
  - The system must enforce the “no change returned” policy: if a player’s card payment exceeds the total rent due, the excess amount is not refunded but is retained by the rent collector.
  - If the paying player’s personal bank has no available money cards and their property zone contains no refundable property cards, they are exempt from paying the current rent, and the rent collector’s collection attempt fails.

b. **Universal (wild) property card:** The Universal Property Card can substitute for any color property card to complete matching property sets. Players may freely adjust the position of the Universal Property Card in their property zone during their turn. Upon adjustment, the system immediately recalculates the completion status of the corresponding property set and updates the rent calculation criteria.

c. **Card legality verification**

- The system performs real-time rule validation on all player actions, immediately blocking illegal moves such as playing rent collection cards without matching property sets, exceeding the three-card per-turn play limit, or invalid discards when the hand is empty. Clear error messages are displayed, for example: “No available property sets currently available to play rent collection cards.”
- When players select action card targets, the system verifies their legitimacy (e.g., stealing cards requires selecting players with hand cards or bank cards; demolishing properties requires selecting players with deployed properties) and blocks invalid targets.

d. **Drawing deck replenishment mechanism:** When players draw cards, if the public deck has no available cards, the system automatically reshuffles all cards from the discard pile and converts them into a new public deck to ensure smooth gameplay. After replenishing the deck, the remaining quantity of the new public deck is displayed to all players.

#### 2.1.4 Game Status Feedback and Operation Tips

a. **Real-time status feedback**

- The system displays in real time on the game interface the current turn player, remaining cards in the deck, and discard pile count.
- Each player’s interface displays real-time information including their hand size, property zone cards (categorized by color), personal bank stack count, and total face value.
- Public information of other players (property zone cards and total bank stack value) is displayed normally. Hand cards are private information, showing only quantities without displaying specific cards.

b. **Instant prompts for operations**

- After players perform actions such as drawing cards, playing cards, or discarding cards, the system provides textual prompts and card animations to display the results (e.g., “Player A draws 2 cards, current hand count: 6” or “Player B initiates rent collection from Player C, rent amount: 5”).
- When players have executable actions (such as drawing cards or playing cards), the system highlights the corresponding action buttons to guide them.

#### 2.1.5 Game Victory and End Judgment

a. **Victory determination:** The system continuously monitors all players’ property zone statuses. If a player collects three or more complete property sets of the same color (including those supplemented with universal cards), they are immediately declared the winner, the game process terminates, and a victory interface is displayed.

b. **Forced termination:** If all players voluntarily quit the game or a single game session exceeds 1 hour, the system forcibly terminates the game and displays the current progress (including each player’s property collection status and total bank balance).

#### 2.1.6 Pause Game and Save Progress

a. **Human vs. machine mode**

- **Pause:** Players can pause the game at any time to freeze the interface. After pausing, they can continue, save and exit, or abandon and exit without additional confirmation steps.
- **Save progress:** Supports manual save (available anytime) plus automatic save (saves progress after every 3 full rounds). When starting the game, locally saved progress can be loaded directly to restore all game state (players / AI / deck / round) with one action.

b. **Player vs. player (multiplayer)**

- **Pause:** Only the player whose turn it is can initiate a pause, which requires confirmation from all players to take effect. After pausing, only “Continue game for all” or “Save and exit for all” are available, with no option for individual players to quit alone.
- **Save progress:** Manual save requires confirmation from all players (initiated by the current player and stored after unanimous agreement). No automatic save is available. Loading progress requires confirmation from all players who participated in the save; otherwise loading is not possible.

### 2.2 Non-Functional Requirements

#### 2.2.1 Product Requirements

a. **Usability**

- **Audience adaptation:** The GUI design should align with the cognitive level of children aged 8 and above, featuring simple and intuitive operation logic.
- **Interactive feedback:** The system must provide visual or textual feedback within 0.5 seconds after a user performs any legal or illegal action (e.g., attempting to play a fourth card in a turn).
- **Visual adaptation:** Color-coded property cards include text labels for color names to prevent color-blind users from encountering recognition difficulties.
- **Newbie guide:** The game automatically launches a distributed tutorial upon first startup. Players can complete the core flow of drawing cards, playing cards, and rent collection through pop-up windows and highlighted guidance, with the option to skip steps at any time.

b. **Performance**

- **Response speed:** The combined decision-making time for AI players and UI animation rendering time must not exceed 1.5 seconds to ensure a fast-paced trading experience.
- **Concurrent processing:** In multiplayer battle mode, the system must reliably support up to 5 players interacting online simultaneously without noticeable latency.
- **Processing efficiency:** The single-operation time for drawing, shuffling, and automatic rent calculation must not exceed 0.3 seconds, ensuring smooth interface performance without lag.
- **Network communication latency:** In multiplayer online battle mode, WebSocket JSON message transmission and state synchronization latency between the client (Flutter) and server (Java) must be kept under 200 ms to ensure state consistency across multi-platform interfaces.

c. **Robustness and reliability**

- **Error handling:** The system must identify and block all non-compliant inputs (e.g., attempts to pay with insufficient balance) without causing program crashes.
- **Data integrity:** During gameplay, the system must ensure that automatically saved progress files remain intact in case of network fluctuations or unexpected system shutdowns.
- **Card data consistency:** The game maintains a constant total of 108 standard cards throughout. Real-time pile count verification occurs during drawing, discarding, and replenishment to prevent card duplication or loss.

#### 2.2.2 Organizational Requirements

a. **Development requirements**

- **Programming languages and technology stack:** The system adopts a front-end and back-end separation architecture. Core backend logic and servers must be developed using Java (JDK 17 or later), while the front-end GUI is built with the Flutter framework for cross-platform visuals and animated interactions.
- **Architecture pattern:** The MVC (Model–View–Controller) pattern must be adopted to achieve separation of logic and interface.
- **Design pattern implementation:** The system must incorporate five design patterns appropriately (including Singleton, Facade, Observer, etc.).
- **Version control:** The team must collaborate using Git, with commit messages clearly documenting changes. Contributions from all five team members should be proportionally balanced.

b. **Delivery constraints (environmental requirements)**

- **Running environment:** The program must run on standard operating systems (Windows / macOS / Linux) with JDK 17 or later installed.
- **Cross-platform compatibility:** The application works seamlessly on Windows, macOS, and Linux without interface garbled text or font display issues, while maintaining consistent card images and button styles.

#### 2.2.3 External Requirements — Software Engineering Guidelines

- **Design quality:** Code structure must adhere to clean design principles, prohibiting “god classes” or excessively long methods, and ensuring each class implements only a cohesive set of related functionality.
- **Maintainability:** Proper interfaces must be defined to handle classes with similar behaviors but no inheritance relationship.
- **Confidentiality:** In multiplayer mode, Player A must not access Player B’s hand information through any unauthorized means.
- **Local data security:** Player progress files (including hand cards, assets, and banking information) are stored using lightweight encryption to prevent tampering via manual file access. In multiplayer mode, players’ public information is synchronized solely via the game interface, eliminating risks of local data leakage.
