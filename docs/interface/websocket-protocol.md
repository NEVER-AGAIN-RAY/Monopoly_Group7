# WebSocket Protocol

This document defines the JSON message protocol between client and server.

## Envelope

All messages use a shared envelope:

```json
{
  "type": "MESSAGE_TYPE",
  "payload": {}
}
```

## Client -> Server Messages

Implemented message types:

- `AUTH`
- `JOIN_SESSION`
- `START_SESSION`
- `PAUSE`
- `PAUSE_REQUEST`
- `PAUSE_ACK`
- `RESUME`
- `REASSIGN_WILD`
- `DRAW`
- `ACTION_OPTIONS`（出牌阶段：仅查询某张**行动牌**的合法参数组合）
- `PLAY_OPTIONS`（出牌阶段：按 `actionType` 查询存入/部署/弃牌/行动的合法参数）
- `PLAY`
- `END_TURN`
- `QUIT`
- `RESPONSE_PASS`
- `SAVE_GAME`
- `LOAD_GAME`
- `LOAD_VOTE`
- `PING`

Minimum required flow coverage:

- `AUTH`
- `START_SESSION`
- `DRAW`
- `PLAY`
- `END_TURN`
- `SAVE_GAME`
- `LOAD_GAME`

## Server -> Client Messages (ACK/REJECT-style included)

- `STATE_UPDATE` (broadcast state snapshot)
- `MY_HAND` (private hand payload; see **MY_HAND card fields** below)
- `AUTH_RESULT` (`ok: true/false`, includes error on failure)
- `ACTION_OPTIONS_RESULT`（仅请求方连接收到；见下文）
- `PLAY_OPTIONS_RESULT`（仅请求方连接收到；载荷形状与 `ACTION_OPTIONS_RESULT` 相同）
- `SAVE_GAME_RESULT` (`ok: true/false`, optional `error`)
- `LOAD_GAME_RESULT` (`ok: true/false`, optional `error`)
- `LOAD_VOTE_REQUIRED` (vote required notification)
- `LOAD_VOTE_PROGRESS` (vote progress updates)

Note: This project uses `*_RESULT` with `ok/error` fields as ACK/REJECT style responses.

## Compatibility and Deprecation Notes

- `PAUSE` and `PAUSE_REQUEST` may both appear in clients. `PAUSE_REQUEST` is the explicit multi-player vote-oriented request.
- `JOIN_SESSION` and `AUTH` are both used for identity/session binding workflows depending on client stage.
- If legacy docs mention non-result ACK labels, treat `*_RESULT` + `ok/error` as the canonical replacement.

## MY_HAND card fields

`MY_HAND.payload` contains `playerId` and `cards` (array). Each element includes at least `id` and `name`, and **should** include the following for GUI clients:

| Field | Type | When present | Meaning |
| ----- | ---- | ------------ | ------- |
| `kind` | string | always | `MONEY` / `PROPERTY` / `WILD` / `ACTION` / `UNKNOWN` |
| `colorGroup` | string | `PROPERTY` | Color key, e.g. `BROWN`, `RAILROAD` |
| `effectCode` | string | `ACTION` | Server effect code, e.g. `RENT`, `RENT_DUAL`, `STEAL_PROPERTY` |
| `rentPalette` | string[] | `ACTION`（`RENT_DUAL`） | 卡面色组，如 `["LIGHT_BLUE","BROWN"]` |
| `wildKind` | string | `WILD` | `ANY_COLOR` 或 `DUAL_COLOR` |
| `printedColors` | string[] | `WILD`（`DUAL_COLOR`） | 卡面印有的两色 |
| `valueM` | int | `MONEY`, `ACTION`, `PROPERTY`, `WILD` | Bank / mortgage face value in millions (action cards: corner value when banked; wild uses `0` in this project’s model) |
| `setNeed` | int | `PROPERTY` | Number of same-color cards required to complete one full set for that color |
| `titleZh` | string | recommended | Short Chinese title for display |
| `hintZh` | string | optional | One-line hint for players |
| `rentDetailZh` | string | optional | `PROPERTY` / `WILD`：与 `RentCalculator` 一致的中文租金轨道说明（凑套张数、满套平地总基础租、房/旅馆加值；万能牌含「无实体双色地权」说明） |
| `buildingLevel` | string | `PROPERTY`, `WILD` | `BASE` / `HOUSE` / `HOTEL` |

### STATE_UPDATE: per-player public zones

Each element of `STATE_UPDATE.payload.players` may include:

| Field | Meaning |
| ----- | ------- |
| `bankCards` | JSON array of bank cards (same field conventions as `MY_HAND` cards) |
| `propertyZoneCards` | Deployed property / wild cards |
| `propertyColorProgress` | Array of `{ colorKey, effectiveCount, need, completeSets }` |
| `bankTotalValueM` | Sum of bank card payment values |

Top-level `STATE_UPDATE.payload` may include `pendingPaymentAmountM` when `turnPhase` is `WAITING_FOR_RESPONSE` and the pending role is `TENANT` (amount for the first rent on the effect stack).

### PLAY: RESPONSE_PASS and rent payment

`actionType`: `RESPONSE_PASS`, `actingPlayerId`: required. Optional `paymentCardIds`: array of card ids from the debtor’s bank and property zone; total face value must be ≥ the first payable rent amount (no change). If omitted or empty, the server selects cards automatically (smaller denominations first).

The standalone `RESPONSE_PASS` message uses the same payload shape (parsed as `PlayActionRequest`).

Legacy clients may ignore unknown fields and keep using `id` / `name` only.

## ACTION_OPTIONS / ACTION_OPTIONS_RESULT

**上行 `ACTION_OPTIONS.payload`**

| Field | Type | Meaning |
| ----- | ---- | ------- |
| `playerId` | string | 须为当前回合玩家（与已认证连接一致） |
| `cardId` | string | 该玩家手牌中的行动牌 id |

仅在 `turnPhase == PLAY` 时有效。失败时对该连接下发 `ERROR`（如 `ACTION_OPTIONS_BAD` / `ACTION_OPTIONS_STATE`）。

**下行 `ACTION_OPTIONS_RESULT.payload`**

| Field | Type | Meaning |
| ----- | ---- | ------- |
| `ok` | boolean | 是否成功生成列表 |
| `error` | string | `ok == false` 时的说明 |
| `effectCode` | string | 行动牌效果码（大写） |
| `truncated` | boolean | 强制交易等组合过多时是否截断 |
| `options` | array | 每元素为 `ActionOptionRow`：`labelZh`、`targetPlayerId`、`targetColorKey`、`targetCardId`、`actorCardId`、`targetZone`（与 `PLAY` / `PlayActionRequest` 字段对齐，空字段可省略） |

客户端展示 `labelZh` 供点选，再按所选行的非空字段构造 `PLAY`（`actionType`: `ACTION`，`cardId` 同查询所用）。

## PLAY_OPTIONS / PLAY_OPTIONS_RESULT

**上行 `PLAY_OPTIONS.payload`**

| Field | Type | Meaning |
| ----- | ---- | ------- |
| `playerId` | string | 须为当前回合玩家 |
| `cardId` | string | 该玩家手牌中的牌 id |
| `actionType` | string | `DEPOSIT` / `DEPLOY` / `DISCARD` / `ACTION` |

仅在 `turnPhase == PLAY` 时有效。失败时对该连接下发 `ERROR`（`PLAY_OPTIONS_BAD` / `PLAY_OPTIONS_STATE` / `PLAY_OPTIONS_FAIL`）。

**下行 `PLAY_OPTIONS_RESULT.payload`**

与 `ACTION_OPTIONS_RESULT` 相同（`ok`、`error`、`effectCode` 在此为所选 `actionType` 或行动牌效果码、`truncated`、`options`）。

`options` 中每行可含 **`allOtherPlayers`: boolean**（为 `true` 时打出 `RENT_DUAL` 应省略 `targetPlayerId`，由服务端按会话玩家顺序依次收租）。

客户端点选后构造 `PLAY`：`actionType` 与查询一致，`cardId` 相同；其余字段与所选行对齐。

## Field Notes

- `SAVE_GAME.payload.path` is optional. If absent, server can return serialized save content in response.
- `LOAD_GAME.payload.mementoJson` is required for load operations.
- `PLAY.payload` maps to a play action request body; fields vary by action type.

## Maintenance Rule

When message type branches change in server message handling logic, this document must be updated in the same change set.
