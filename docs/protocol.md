# WebSocket Protocol

本文档定义客户端与服务端的 JSON 消息协议。  
所有消息统一采用 envelope 结构：

```json
{
  "type": "MESSAGE_TYPE",
  "payload": {}
}
```

## 1) 客户端 -> 服务端（上行）

以下 `type` 与 `GameServer.onMessage` 中 `".equals(type)"` 分支保持一致：

- `AUTH`
- `JOIN_SESSION`
- `START_SESSION`
- `PAUSE`
- `PAUSE_REQUEST`
- `PAUSE_ACK`
- `RESUME`
- `REASSIGN_WILD`
- `DRAW`
- `PLAY`
- `END_TURN`
- `QUIT`
- `RESPONSE_PASS`
- `SAVE_GAME`
- `LOAD_GAME`
- `LOAD_VOTE`
- `PING`

### AUTH / JOIN_SESSION

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `playerId` | string | 是 | 玩家唯一 ID，用于连接绑定 |
| `sessionToken` | string | 否 | 鉴权预留字段（当前仅占位） |

示例：

```json
{
  "type": "AUTH",
  "payload": {
    "playerId": "pvp-1",
    "sessionToken": "optional-token"
  }
}
```

### START_SESSION

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `sessionId` | string | 否 | 会话 ID |
| `playerCount` | number | 否 | 玩家数量（2-5） |
| `gameMode` | string | 否 | `HVM` 或 `PVP` |
| `aiDifficulty` | string | 否 | 人机模式下可选 `EASY`/`NORMAL`/`HARD` |
| `randomizeFirstPlayer` | boolean | 否 | 是否随机先手 |

示例：

```json
{
  "type": "START_SESSION",
  "payload": {
    "sessionId": "demo",
    "playerCount": 2,
    "gameMode": "PVP",
    "randomizeFirstPlayer": false
  }
}
```

### PAUSE / PAUSE_REQUEST

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| 无 | - | - | 直接请求暂停（`PVP` 下走投票流程） |

示例：

```json
{ "type": "PAUSE_REQUEST", "payload": {} }
```

### PAUSE_ACK

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `playerId` | string | 是 | 确认暂停的玩家 ID |

示例：

```json
{
  "type": "PAUSE_ACK",
  "payload": { "playerId": "pvp-2" }
}
```

### RESUME

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| 无 | - | - | 解除暂停 |

示例：

```json
{ "type": "RESUME", "payload": {} }
```

### REASSIGN_WILD

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `wildPropertyCardId` | string | 是 | 万能房产卡 ID |
| `newColorKey` | string | 是 | 目标颜色键 |

示例：

```json
{
  "type": "REASSIGN_WILD",
  "payload": {
    "wildPropertyCardId": "card-123",
    "newColorKey": "BLUE"
  }
}
```

### DRAW

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `count` | number | 否 | 摸牌数，默认 2 |

示例：

```json
{
  "type": "DRAW",
  "payload": { "count": 2 }
}
```

### PLAY

`payload` 会反序列化为 `PlayActionRequest`，常用字段如下：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `actionType` | string | 是 | 如 `DEPOSIT`/`DEPLOY`/`ACTION`/`DISCARD` |
| `cardId` | string | 否 | 手牌卡 ID（优先于 `handIndex`） |
| `handIndex` | number | 否 | 手牌索引 |
| `targetPlayerId` | string | 否 | 行动卡目标玩家 |
| `targetColorKey` | string | 否 | 收租/万能牌颜色参数 |
| `targetCardId` | string | 否 | 目标卡 ID |
| `actorCardId` | string | 否 | 自己的卡 ID（交换等） |
| `actingPlayerId` | string | 否 | 响应链中的行动者 ID |
| `targetZone` | string | 否 | 偷牌目标区域（如 BANK/PROPERTY） |

示例：

```json
{
  "type": "PLAY",
  "payload": {
    "actionType": "DEPOSIT",
    "handIndex": 0
  }
}
```

### END_TURN

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| 无 | - | - | 结束当前回合 |

示例：

```json
{ "type": "END_TURN", "payload": {} }
```

### QUIT

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `playerId` | string | 是 | 退出玩家 ID |

示例：

```json
{
  "type": "QUIT",
  "payload": { "playerId": "pvp-1" }
}
```

### RESPONSE_PASS

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `actingPlayerId` | string | 是 | 当前响应链里选择放弃的玩家 |

示例：

```json
{
  "type": "RESPONSE_PASS",
  "payload": { "actingPlayerId": "pvp-2" }
}
```

### SAVE_GAME

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `path` | string | 否 | 写盘路径；缺省时直接返回 `mementoJson` |

示例：

```json
{
  "type": "SAVE_GAME",
  "payload": {
    "path": "/tmp/monopoly-save.json"
  }
}
```

### LOAD_GAME

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `mementoJson` | string | 是 | 保存内容（支持加密存储格式）；在多人场景会进入投票待确认状态 |

示例：

```json
{
  "type": "LOAD_GAME",
  "payload": {
    "mementoJson": "{...}"
  }
}
```

### LOAD_VOTE

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `playerId` | string | 否 | 投票玩家 ID（已 AUTH 时可省略，服务端可从连接绑定推断） |

示例：

```json
{
  "type": "LOAD_VOTE",
  "payload": { "playerId": "pvp-2" }
}
```

### PING

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| 无 | - | - | 连通性探测占位消息 |

示例：

```json
{ "type": "PING", "payload": {} }
```

## 2) 服务端 -> 客户端（下行）

### STATE_UPDATE（广播）

广播整个公开游戏快照。`players[*]` 仅含公开信息（如 `handCount`），不包含手牌明细。

示例：

```json
{
  "type": "STATE_UPDATE",
  "payload": {
    "sessionId": "demo",
    "phase": "DRAW",
    "currentPlayerId": "pvp-1",
    "players": [
      {
        "playerId": "pvp-1",
        "displayName": "Player-1",
        "handCount": 7
      }
    ]
  }
}
```

### MY_HAND（定向）

仅发送给已 `AUTH/JOIN_SESSION` 绑定到该 `playerId` 的连接，不广播给其他玩家。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `playerId` | string | 当前手牌所属玩家 |
| `cards` | array | 手牌明细（当前包含 `id`、`name`） |

示例：

```json
{
  "type": "MY_HAND",
  "payload": {
    "playerId": "pvp-1",
    "cards": [
      { "id": "c-001", "name": "Pass Go" },
      { "id": "c-002", "name": "Rent" }
    ]
  }
}
```

### AUTH_RESULT

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `ok` | boolean | 是否绑定成功 |
| `error` | string | 失败原因（可选） |

示例：

```json
{
  "type": "AUTH_RESULT",
  "payload": {
    "ok": true
  }
}
```

### SAVE_GAME_RESULT / LOAD_GAME_RESULT

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `ok` | boolean | 操作是否成功 |
| `error` | string | 失败信息（可选） |
| `mementoJson` | string | 仅 `SAVE_GAME_RESULT` 且未指定 `path` 时返回 |
| `writtenPath` | string | 仅 `SAVE_GAME_RESULT` 且指定 `path` 时返回 |

示例：

```json
{
  "type": "SAVE_GAME_RESULT",
  "payload": {
    "ok": true,
    "writtenPath": "/tmp/monopoly-save.json"
  }
}
```

### LOAD_VOTE_REQUIRED / LOAD_VOTE_PROGRESS

当 `LOAD_GAME` 需要全员确认时，服务端广播投票状态：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `requiredVotes` | number | 需要票数（当前会话已绑定的人类玩家数） |
| `currentVotes` | number | 当前已确认票数 |

## 3) 维护约束

- 当 `GameServer.onMessage` 的 `type` 分支新增/删除/重命名时，必须同步更新本文档。
- 验收脚本建议：
  - 代码侧：`rg "\"[A-Z_]+\"\\.equals\\(type\\)" src/main/java/com/monopoly/network/GameServer.java`
  - 文档侧：检查“客户端 -> 服务端（上行）”类型列表是否一一对应。
