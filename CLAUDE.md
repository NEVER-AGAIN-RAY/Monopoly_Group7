# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run all tests
mvn -q test

# Run a single test class
mvn -q test -Dtest=DealBreakerEffectTest

# Run a single test method
mvn -q test -Dtest=DealBreakerEffectTest#shouldStealCompleteSet

# Start WebSocket server (port 8025, endpoint ws://localhost:8025/ws)
mvn -q exec:java

# Start JavaFX desktop client (requires server running)
mvn javafx:run

# Quick connectivity test
wscat -c ws://localhost:8025/ws
# Send: {"type":"PING","payload":{}}
# Send: {"type":"START_SESSION","payload":{"sessionId":"demo","playerCount":2,"gameMode":"PVP","randomizeFirstPlayer":false}}
```

### JVM flags (via `-D`)

| Flag | Default | Purpose |
|------|---------|---------|
| `monopoly.verifyDeck` | `false` | Validate deck size = 108 at key transitions |
| `monopoly.autosave` | `false` | Auto-write to `~/.monopoly-deal/autosave.json` every 3 full rounds |
| `monopoly.saveKey` | unset | AES-GCM encryption key for saves; plaintext JSON when unset |
| `monopoly.sessionLimitMs` | `GameConstants.DEFAULT_SESSION_LIMIT_MS` | Session timeout override |

## Architecture

**Stack**: Java 17, Maven 3.9+, Gson, Tyrus (Jakarta WebSocket), JavaFX + FXML, JUnit 5.

**Topology**: The server is a single-process WebSocket server (`WsServerMain` → Tyrus/Grizzly on port 8025). The JavaFX client (`MonopolyFxApp`) connects as a WebSocket client. Both can run on the same machine. The communication protocol is JSON envelopes over WebSocket text frames (see `docs/interface/websocket-protocol.md`).

### Package roles

- **`controller/`** — Facade layer. `GameController` is a thin Facade that delegates to single-responsibility services: `TurnFlowService` (draw/play/discard/end-turn lifecycle + turn phase state machine), `EffectStackOrchestrator` (rent response windows, Just Say No chains, 15s timeout), `RentSettlementService`, `AiTurnService`, `PauseVoteService`, `SaveLoadService`. `TurnManager` owns turn order.

- **`model/card/`** — Card type hierarchy: `Card` → `PropertyCard`, `PropertyWildCard` (with `WildPropertyKind`: `ANY_COLOR`/`DUAL_COLOR`), `ActionCard` (with `effectCode`), `MoneyCard`. Key interfaces: `Playable.canPlay()` and `Payable.getPaymentValue()`.

- **`model/effects/`** — Strategy pattern for action card effects. `ActionEffectDispatcher` maps `effectCode` → concrete `ActionEffect` implementations (Rent, DealBreaker, SlyDeal, ForcedDeal, DebtCollector, Birthday, House, Hotel, PassGo, DoubleRent, RentWaiver). Effects use `ActionEffectContext` (actor, target, engine, card IDs, colors) and return `ActionEffectResult` (SUCCESS/FAILED/COUNTERED).

- **`model/settlement/`** — Rent calculation: `PropertySetCalculator` (sets per color), `RentCalculator` (step-table by set completeness + house/hotel bonuses), `PaymentSettlement` (player-to-player payment with no change). Rent uses tier tables from `RentTierTable.java`.

- **`model/player/`** — `Player` holds `handCards`, `propertyCards`, `bankCards`, `actionZoneCards`. Subtypes: `HumanPlayer`, `AIPlayer` (composes `AiPlayStrategy`).

- **`model/rules/`** — Static rule data: `MonopolyDealBankValues` (action card face values when banked), `MonopolyDealRulesSummary` (embedded rule text used as normative reference).

- **`model/core/`** — `GameContext` (players + effect stack + response state), `GameConstants`, `AiGameBridge` (interface allowing AI to reuse human validation path).

- **`dto/`** — Wire-format objects: `PlayActionRequest`, `GameStateSnapshot`, `StartSessionRequest`, `ActionParamContext`, `PropertyColorCount`. These are the JSON shapes, decoupled from domain model.

- **`persistence/`** — Memento pattern: `GameSessionMemento.capture()` / `applyToController()`. `SaveEncryption` handles optional AES-GCM. `PersistedCard` bridges DTO ↔ domain.

- **`presentation/`** — View-model helpers: `HandCardJson` (builds per-player JSON hand with playability flags and rent previews), `RentScheduleText` (human-readable rent tier text).

- **`network/`** — Three sub-packages: `endpoint/` (Tyrus `@ServerEndpoint` + `WsServerMain`), `connection/` (`ClientConnection` abstraction + `SessionRegistry` bidirectional player-connection index), `protocol/` (`MessageDispatcher` for JSON parse/route).

- **`pattern/`** — Explicit design patterns: `factory/` (`CardFactory` → 108-card standard deck), `observer/` (`GameUpdateSubject` → `GameUpdateObserver` for state broadcasts), `singleton/` (`GameEngineSingleton` — draw pile, discard pile, shuffle), `strategy/` (`AiPlayStrategy` with Easy/Normal/Hard implementations).

- **`fx/`** — JavaFX+FXML desktop client: `MonopolyFxApp` (entry), `MainController` (FXML controller), `FxWebSocketClient` (JDK HttpClient WebSocket), `ui/` (card views, player boards, target pickers).

### Key data flow

1. Client sends JSON message → `MonopolyWebSocketEndpoint.onMessage` → `GameServer.onMessage`
2. `MessageDispatcher` parses message type → routes to `GameController` method
3. `GameController` delegates to service (e.g., `TurnFlowService.playActionCard`)
4. Service runs domain logic, mutates `GameEngineSingleton`/`Player`/`GameContext`
5. `GameController.pushSnapshot()` → `GameUpdateSubject.notifyAll(state)` → `GameServer.onGameStateUpdated` broadcasts `STATE_UPDATE` to all clients; private `MY_HAND` sent per-connection

### Turn phase state machine

`TurnFlowService` maintains `TurnPhase`: `DRAW` → `PLAY` → (`WAITING_FOR_RESPONSE` on rent/double-rent) → `END_TURN`. The `WAITING_FOR_RESPONSE` phase is managed by `EffectStackOrchestrator` with a 15-second timeout scheduler.

### Rules authority

When implementation and rules appear to conflict, the normative sources (in priority order) are:
1. `rules.md` Appendix A + `MonopolyDealCardFactory.java` (deck composition)
2. `MonopolyDealRulesSummary.java` (embedded rule summary)
3. `docs/implementation/requirement-trace-and-deviations.md` (known deviations)

## Maintenance conventions

- **Protocol changes**: When modifying `MessageDispatcher` / `GameServer` message branches, update `docs/interface/websocket-protocol.md` in the same commit.
- **Architecture docs**: `docs/architecture/uml_source.md` is the single source for directory/module descriptions and UML. Do not create parallel architecture docs.
- **Changelog**: Add entries to `docs/ENGINEERING.md` (newest first) when merging to main.
- **Requirements**: Do not rewrite historical requirement semantics in `docs/requirements/requirements.md` without team review. Record deviations in `docs/implementation/requirement-trace-and-deviations.md`.

## Project context

- University course project (2 phases: Week 8 analysis/UML, Week 15 implementation/testing)
- Phase 1 due 2026-04-22, Phase 2 due 2026-06-09, demo 2026-06-11/12
- 5-person team; code contribution is tracked in README
