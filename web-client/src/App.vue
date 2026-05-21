<script setup>
import { computed, ref } from 'vue'

const wsUrl = ref('ws://localhost:8025/ws')
const playerId = ref('human-1')
const sessionId = ref('web-demo')
const playerCount = ref(2)
const gameMode = ref('HVM')
const aiDifficulty = ref('NORMAL')
const randomFirst = ref(false)
const connected = ref(false)
const connecting = ref(false)
const screen = ref('start')
const messages = ref([])
const state = ref(null)
const hand = ref([])
const selectedCardId = ref('')
const optionSheet = ref(null)
const pendingPlay = ref(null)
const paymentSelection = ref(new Set())
const notice = ref('')
const actionBusy = ref(false)
const busyCardId = ref('')
let socket = null

const currentPlayerId = computed(() => state.value?.currentPlayerId || '')
const turnPhase = computed(() => state.value?.turnPhase || '')
const selectedCard = computed(() => hand.value.find((c) => c.id === selectedCardId.value) || null)
const localPlayer = computed(() => (state.value?.players || []).find((p) => p.playerId === playerId.value) || null)
const players = computed(() => state.value?.players || [])
const opponents = computed(() => players.value.filter((p) => p.playerId !== playerId.value))
const localBoard = computed(() => localPlayer.value || players.value[0] || null)
const paymentDue = computed(() => Number(state.value?.pendingPaymentAmountM || 0))
const awaitingPayment = computed(() => {
  return state.value?.turnPhase === 'WAITING_FOR_RESPONSE'
    && state.value?.pendingResponsePlayerId === playerId.value
    && state.value?.pendingResponseRole === 'TENANT'
    && paymentDue.value > 0
})
const paymentCards = computed(() => {
  const p = localPlayer.value
  if (!p) return []
  return [
    ...(p.bankCards || []).map((card) => ({ ...card, zone: '银行' })),
    ...(p.propertyZoneCards || []).map((card) => ({ ...card, zone: '房产' }))
  ]
})
const selectedPaymentTotal = computed(() => {
  return paymentCards.value
    .filter((card) => paymentSelection.value.has(card.id))
    .reduce((sum, card) => sum + Number(card.valueM || 0), 0)
})
const tableStatus = computed(() => {
  if (state.value?.gameOver) return '游戏结束'
  if (playerId.value === currentPlayerId.value) return '你的回合'
  if (currentPlayerId.value) return `等待 ${currentPlayerId.value}`
  return '牌桌就绪'
})
const eventLine = computed(() => {
  if (actionBusy.value) return notice.value || '处理中...'
  return notice.value || state.value?.lastActionSummary || '摸牌、出牌和房产变化会显示在这里'
})

function modeChanged() {
  playerId.value = gameMode.value === 'HVM' ? 'human-1' : 'pvp-1'
}

function connect(autoStart = false) {
  if (connected.value && autoStart) {
    authAndStart()
    return
  }
  if (connected.value || connecting.value) return
  connecting.value = true
  notice.value = '正在连接后端...'
  socket = new WebSocket(wsUrl.value)
  socket.addEventListener('open', () => {
    connected.value = true
    connecting.value = false
    notice.value = '已连接'
    log('system', 'WebSocket connected')
    if (autoStart) authAndStart()
  })
  socket.addEventListener('message', (event) => handleMessage(event.data))
  socket.addEventListener('close', () => {
    connected.value = false
    connecting.value = false
    notice.value = '连接已断开'
    log('system', 'WebSocket closed')
  })
  socket.addEventListener('error', () => {
    connecting.value = false
    notice.value = '连接失败：确认 Java 后端正在 ws://localhost:8025/ws 运行'
  })
}

function disconnect() {
  socket?.close()
}

function send(type, payload = {}) {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    notice.value = '还没连接后端'
    clearBusy()
    return false
  }
  const body = JSON.stringify({ type, payload })
  socket.send(body)
  log('out', body)
  return true
}

function authAndStart() {
  send('AUTH', { playerId: playerId.value })
  send('START_SESSION', {
    sessionId: sessionId.value,
    playerCount: Number(playerCount.value),
    gameMode: gameMode.value,
    aiDifficulty: gameMode.value === 'HVM' ? aiDifficulty.value : undefined,
    randomizeFirstPlayer: randomFirst.value
  })
  screen.value = 'game'
}

function handleMessage(raw) {
  log('in', raw)
  let root
  try {
    root = JSON.parse(raw)
  } catch {
    return
  }
  const payload = root.payload || {}
  switch (root.type) {
    case 'AUTH_RESULT':
      notice.value = payload.ok ? '认证成功' : payload.error || '认证失败'
      break
    case 'STATE_UPDATE':
      state.value = payload
      screen.value = 'game'
      if (!awaitingPayment.value) paymentSelection.value = new Set()
      clearBusy()
      break
    case 'MY_HAND':
      hand.value = payload.cards || []
      if (!hand.value.some((c) => c.id === selectedCardId.value)) {
        selectedCardId.value = ''
      }
      break
    case 'PLAY_OPTIONS_RESULT':
    case 'ACTION_OPTIONS_RESULT':
      handleOptions(payload)
      break
    case 'ERROR':
      notice.value = payload.message || payload.error || '服务器返回错误'
      pendingPlay.value = null
      clearBusy()
      break
    default:
      if (payload.error) notice.value = payload.error
  }
}

function handleOptions(payload) {
  if (!pendingPlay.value) return
  if (!payload.ok) {
    notice.value = payload.error || '当前牌没有可用操作'
    pendingPlay.value = null
    clearBusy()
    return
  }
  const options = payload.options || []
  if (options.length <= 1 || pendingPlay.value.autoDefault) {
    playWithOption(options[0] || {})
    return
  }
  optionSheet.value = {
    title: selectedCard.value?.titleZh || selectedCard.value?.name || '选择目标',
    options
  }
  clearBusy()
}

function queryPlay(actionType) {
  if (actionBusy.value) return
  if (!selectedCard.value) {
    notice.value = '先点一张手牌'
    return
  }
  const directPayload = directPlayPayload(selectedCard.value, actionType)
  if (directPayload) {
    playDirect(directPayload, selectedCard.value, actionType)
    return
  }
  pendingPlay.value = { actionType, cardId: selectedCard.value.id }
  markBusy(selectedCard.value.id, '正在查询可选目标...')
  send('PLAY_OPTIONS', {
    playerId: playerId.value,
    cardId: selectedCard.value.id,
    actionType
  })
}

function defaultActionForCard(card) {
  const kind = card?.kind || ''
  if (kind === 'MONEY') return 'DEPOSIT'
  if (kind === 'PROPERTY' || kind === 'WILD') return 'DEPLOY'
  if (kind === 'ACTION') return 'ACTION'
  return card?.valueM !== undefined ? 'DEPOSIT' : 'ACTION'
}

function quickPlay(card) {
  if (actionBusy.value) return
  if (!card?.id) return
  const actionType = defaultActionForCard(card)
  selectedCardId.value = card.id
  const directPayload = directPlayPayload(card, actionType)
  if (directPayload) {
    playDirect(directPayload, card, actionType)
    return
  }
  pendingPlay.value = { actionType, cardId: card.id, autoDefault: true }
  markBusy(card.id, `默认${actionLabel(actionType)}：${cardTitle(card)}`)
  send('PLAY_OPTIONS', {
    playerId: playerId.value,
    cardId: card.id,
    actionType
  })
}

function playWithOption(row = {}) {
  if (!pendingPlay.value) return
  const payload = {
    actionType: pendingPlay.value.actionType,
    cardId: pendingPlay.value.cardId,
    targetPlayerId: row.allOtherPlayers ? undefined : row.targetPlayerId,
    targetColorKey: row.targetColorKey,
    targetCardId: row.targetCardId,
    actorCardId: row.actorCardId,
    targetZone: row.targetZone
  }
  const cardId = pendingPlay.value.cardId
  const actionType = pendingPlay.value.actionType
  optionSheet.value = null
  pendingPlay.value = null
  markBusy(cardId, `正在${actionLabel(actionType)}...`)
  send('PLAY', compact(payload))
}

function actionLabel(actionType) {
  return ({
    DEPOSIT: '存入银行',
    DEPLOY: '部署房产',
    ACTION: '打出行动牌',
    DISCARD: '弃牌'
  })[actionType] || '出牌'
}

function markBusy(cardId, text) {
  actionBusy.value = true
  busyCardId.value = cardId || ''
  notice.value = text
}

function clearBusy() {
  actionBusy.value = false
  busyCardId.value = ''
}

function directPlayPayload(card, actionType) {
  if (!card?.id) return null
  if (actionType === 'DEPOSIT' || actionType === 'DISCARD') {
    return { actionType, cardId: card.id }
  }
  if (actionType === 'DEPLOY') {
    if (card.kind === 'PROPERTY') return { actionType, cardId: card.id }
    if (card.kind === 'WILD') {
      const color = defaultWildDeployColor(card)
      if (color) return { actionType, cardId: card.id, targetColorKey: color }
    }
  }
  return null
}

function defaultWildDeployColor(card) {
  if (Array.isArray(card?.printedColors) && card.printedColors.length) {
    return card.printedColors[0]
  }
  return card?.colorGroup || 'BROWN'
}

function playDirect(payload, card, actionType) {
  selectedCardId.value = card.id
  pendingPlay.value = null
  optionSheet.value = null
  markBusy(card.id, `正在${actionLabel(actionType)}：${cardTitle(card)}`)
  send('PLAY', compact(payload))
}

function draw() {
  if (actionBusy.value) return
  notice.value = '正在摸牌...'
  send('DRAW', { count: 2 })
}

function endTurn() {
  if (actionBusy.value) return
  notice.value = '正在结束回合...'
  send('END_TURN', {})
}

function autoPayRent() {
  if (actionBusy.value) return
  markBusy('', '正在自动支付租金...')
  send('PLAY', {
    actionType: 'RESPONSE_PASS',
    actingPlayerId: playerId.value
  })
}

function confirmPayRent() {
  if (actionBusy.value) return
  if (selectedPaymentTotal.value < paymentDue.value) {
    notice.value = `已选 ${selectedPaymentTotal.value}M，不足 ${paymentDue.value}M`
    return
  }
  markBusy('', '正在按所选牌支付租金...')
  send('PLAY', {
    actionType: 'RESPONSE_PASS',
    actingPlayerId: playerId.value,
    paymentCardIds: [...paymentSelection.value]
  })
}

function togglePayment(id) {
  const next = new Set(paymentSelection.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  paymentSelection.value = next
}

function cardTitle(card) {
  return card?.titleZh || card?.name || card?.id || 'Card'
}

function cardHint(card) {
  return card?.hintZh || card?.effectCode || card?.colorGroup || ''
}

function cardClass(card) {
  return [
    'game-card',
    'fan-card',
    `kind-${(card?.kind || 'UNKNOWN').toLowerCase()}`,
    selectedCardId.value === card?.id ? 'selected' : '',
    actionBusy.value && busyCardId.value === card?.id ? 'processing' : ''
  ]
}

function tableCardClass(card) {
  return [
    'table-card',
    `kind-${(card?.kind || 'UNKNOWN').toLowerCase()}`
  ]
}

function cardIcon(card) {
  const code = String(card?.effectCode || card?.kind || '').toUpperCase()
  const map = {
    PASS_GO: 'GO',
    RENT: 'RENT',
    RENT_DUAL: 'DUO',
    STEAL_PROPERTY: 'SLY',
    STEAL_PROPERTY_SET: 'SET',
    FORCED_DEAL: 'SWAP',
    DEBT_COLLECTOR: '5M',
    JUST_SAY_NO: 'NO',
    RENT_WAIVER: 'NO',
    DOUBLE_RENT: 'x2',
    HOUSE: 'HOME',
    HOTEL: 'HOTEL',
    BIRTHDAY: 'GIFT',
    DEAL_BREAKER: 'SET'
  }
  if (card?.kind === 'MONEY') return `${card.valueM || 0}M`
  if (card?.kind === 'PROPERTY') return colorName(card.colorGroup)
  if (card?.kind === 'WILD') return 'WILD'
  return map[code] || 'ACT'
}

function cardKindLabel(card) {
  return ({
    ACTION: '行动牌',
    PROPERTY: '房产牌',
    MONEY: '现金',
    WILD: '万能房产'
  })[card?.kind] || '卡牌'
}

function colorStyle(card) {
  if (card?.kind === 'WILD') return { background: 'linear-gradient(90deg,#c62828,#f9a825,#2e7d32,#1565c0,#6a1b9a)' }
  const map = {
    BROWN: '#795548',
    LIGHT_BLUE: '#4fc3f7',
    PINK: '#f06292',
    ORANGE: '#ff9800',
    RED: '#e53935',
    YELLOW: '#ffeb3b',
    GREEN: '#43a047',
    DARK_BLUE: '#1e3a8a',
    RAILROAD: '#5d4037',
    UTILITY: '#90a4ae'
  }
  return { background: map[card?.colorGroup] || (card?.kind === 'MONEY' ? '#fbc02d' : '#1565c0') }
}

function cardVars(card) {
  const accent = colorStyle(card).background
  return {
    '--card-accent': accent,
    '--card-art-bg': card?.kind === 'MONEY'
      ? 'linear-gradient(135deg,#fff7b0,#f0b72f)'
      : card?.kind === 'PROPERTY'
        ? accent
        : 'linear-gradient(135deg,#1e88e5,#64b5f6)',
    '--rot': '0deg'
  }
}

function fanCardStyle(card, index) {
  return {
    ...cardVars(card),
    '--rot': `${Math.max(-11, Math.min(11, (index - (hand.value.length - 1) / 2) * 2.15))}deg`,
    '--fan-z': `${index + 1}`
  }
}

function colorName(key) {
  return ({
    BROWN: '棕', LIGHT_BLUE: '浅蓝', PINK: '粉', ORANGE: '橙', RED: '红',
    YELLOW: '黄', GREEN: '绿', DARK_BLUE: '深蓝', RAILROAD: '铁路', UTILITY: '公共'
  })[key] || key
}

function compact(obj) {
  return Object.fromEntries(Object.entries(obj).filter(([, v]) => v !== undefined && v !== null && v !== ''))
}

function log(direction, text) {
  messages.value.unshift({ direction, text, time: new Date().toLocaleTimeString() })
  messages.value = messages.value.slice(0, 80)
}
</script>

<template>
  <main class="app-shell">
    <section v-if="screen === 'start'" class="start-screen">
      <div class="brand-lockup">
        <div class="brand-kicker">WEB TABLE PROTOTYPE</div>
        <h1>Monopoly Deal</h1>
        <p>浏览器版牌桌，用来快速批注视觉、布局和交互。</p>
      </div>

      <div class="setup-panel">
        <label>
          模式
          <select v-model="gameMode" @change="modeChanged">
            <option>HVM</option>
            <option>PVP</option>
          </select>
        </label>
        <label>
          人数
          <input v-model.number="playerCount" min="2" max="5" type="number" />
        </label>
        <label v-if="gameMode === 'HVM'">
          AI
          <select v-model="aiDifficulty">
            <option>EASY</option>
            <option>NORMAL</option>
            <option>HARD</option>
          </select>
        </label>
        <label>
          玩家 ID
          <input v-model="playerId" />
        </label>
        <label>
          会话
          <input v-model="sessionId" />
        </label>
        <label>
          后端
          <input v-model="wsUrl" />
        </label>
      </div>

      <div class="start-actions">
        <button class="primary" @click="connect(true)" :disabled="connecting">
          {{ connecting ? '连接中...' : '开始游戏' }}
        </button>
        <button class="secondary" @click="connect(false)" :disabled="connected || connecting">只连接</button>
        <label class="check-row"><input type="checkbox" v-model="randomFirst" /> 随机先手</label>
      </div>

      <div class="notice" v-if="notice">{{ notice }}</div>
    </section>

    <section v-else class="game-screen">
      <header class="top-hud">
        <div>
          <div class="brand-small">MONOPOLY DEAL</div>
          <div class="session-line">{{ sessionId }} · {{ connected ? 'online' : 'offline' }}</div>
        </div>
        <div class="hud-status">
          <div class="state-line">
            <span>{{ tableStatus }}</span>
            <span>阶段 {{ state?.phase || '-' }}</span>
            <span>{{ turnPhase || '-' }}</span>
            <span>抽牌 {{ state?.drawPileCount ?? '-' }}</span>
            <span>弃牌 {{ state?.discardPileCount ?? '-' }}</span>
          </div>
          <div class="event-line">{{ eventLine }}</div>
        </div>
        <button class="secondary compact" @click="screen = 'start'">设置</button>
        <button class="secondary compact" @click="disconnect">断开</button>
      </header>

      <div class="table-stage">
        <div class="felt-table">
          <section class="opponent-lane">
            <article v-for="player in opponents" :key="player.playerId" class="tableau opponent-tableau" :class="{ active: player.playerId === currentPlayerId }">
              <header class="tableau-head">
                <div class="avatar">{{ (player.displayName || player.playerId).slice(0, 2).toUpperCase() }}</div>
                <div>
                  <h2>{{ player.displayName || player.playerId }}</h2>
                  <p>{{ player.handCount }} 张手牌 · {{ player.completePropertySets || 0 }}/3 套</p>
                </div>
                <span v-if="player.playerId === currentPlayerId">回合中</span>
              </header>
              <div class="revealed-zones">
                <section class="revealed-zone">
                  <h3>银行 <b>{{ player.bankTotalValueM || 0 }}M</b></h3>
                  <div class="visible-card-row small-cards">
                    <article v-for="card in player.bankCards || []" :key="card.id" :class="tableCardClass(card)" :style="cardVars(card)">
                      <span class="color-band" :style="colorStyle(card)"></span>
                      <span class="card-art"><b>{{ cardIcon(card) }}</b></span>
                      <strong>{{ cardTitle(card) }}</strong>
                      <b class="value-badge" v-if="card.valueM !== undefined">{{ card.valueM }}M</b>
                    </article>
                    <em v-if="!(player.bankCards || []).length">银行空</em>
                  </div>
                </section>
                <section class="revealed-zone property-zone">
                  <h3>房产 <b>{{ player.propertyCount || 0 }}</b></h3>
                  <div class="visible-card-row small-cards">
                    <article v-for="card in player.propertyZoneCards || []" :key="card.id" :class="tableCardClass(card)" :style="cardVars(card)">
                      <span class="color-band" :style="colorStyle(card)"></span>
                      <span class="card-art"><b>{{ cardIcon(card) }}</b></span>
                      <strong>{{ cardTitle(card) }}</strong>
                      <b class="value-badge" v-if="card.valueM !== undefined">{{ card.valueM }}M</b>
                    </article>
                    <em v-if="!(player.propertyZoneCards || []).length">还没有房产</em>
                  </div>
                </section>
              </div>
            </article>
            <article v-if="!opponents.length" class="tableau empty-seat">等待其他玩家入座</article>
          </section>

          <div class="center-play">
            <button class="deck draw-deck" @click="draw" :disabled="actionBusy">
              <strong>DRAW</strong>
              <span>{{ playerId === currentPlayerId ? '摸 2' : '牌堆' }}</span>
            </button>
            <div class="table-center-status">
              <span>{{ tableStatus }}</span>
              <strong>{{ currentPlayerId || '-' }}</strong>
            </div>
            <div class="deck discard-deck">
              <strong>DISCARD</strong>
              <span>{{ state?.discardPileCount ?? 0 }}</span>
            </div>
          </div>

          <section class="lower-table">
            <article v-if="localBoard" class="tableau my-tableau" :class="{ active: localBoard.playerId === currentPlayerId }">
              <header class="tableau-head">
                <div class="avatar">{{ (localBoard.displayName || localBoard.playerId).slice(0, 2).toUpperCase() }}</div>
                <div>
                  <h2>我的置牌区</h2>
                  <p>{{ localBoard.bankTotalValueM || 0 }}M 银行 · {{ localBoard.propertyCount || 0 }} 张房产</p>
                </div>
              </header>
              <div class="revealed-zones my-zones">
                <section class="revealed-zone">
                  <h3>银行 <b>{{ localBoard.bankTotalValueM || 0 }}M</b></h3>
                  <div class="visible-card-row">
                    <article v-for="card in localBoard.bankCards || []" :key="card.id" :class="tableCardClass(card)" :style="cardVars(card)">
                      <span class="color-band" :style="colorStyle(card)"></span>
                      <span class="card-type">{{ cardKindLabel(card) }}</span>
                      <span class="card-art"><b>{{ cardIcon(card) }}</b></span>
                      <strong>{{ cardTitle(card) }}</strong>
                      <b class="value-badge" v-if="card.valueM !== undefined">{{ card.valueM }}M</b>
                    </article>
                    <em v-if="!(localBoard.bankCards || []).length">打出的现金 / 存入银行的行动牌会摊在这里</em>
                  </div>
                </section>
                <section class="revealed-zone property-zone">
                  <h3>房产 <b>{{ localBoard.completePropertySets || 0 }}/3 套</b></h3>
                  <div class="visible-card-row">
                    <article v-for="card in localBoard.propertyZoneCards || []" :key="card.id" :class="tableCardClass(card)" :style="cardVars(card)">
                      <span class="color-band" :style="colorStyle(card)"></span>
                      <span class="card-type">{{ cardKindLabel(card) }}</span>
                      <span class="card-art"><b>{{ cardIcon(card) }}</b></span>
                      <strong>{{ cardTitle(card) }}</strong>
                      <b class="value-badge" v-if="card.valueM !== undefined">{{ card.valueM }}M</b>
                    </article>
                    <em v-if="!(localBoard.propertyZoneCards || []).length">部署后的房产会像真牌一样摊开</em>
                  </div>
                </section>
              </div>
            </article>

            <section class="hand-fan-panel">
              <div class="hand-title">
                <h2>手牌</h2>
                <p>{{ selectedCard ? cardTitle(selectedCard) : '鼠标悬停会展开牌面。' }}</p>
              </div>
              <div class="fan-hand">
                <button
                  v-for="(card, index) in hand"
                  :key="card.id"
                  :class="cardClass(card)"
                  :style="fanCardStyle(card, index)"
                  :title="'双击默认出牌：' + cardTitle(card)"
                  @click="selectedCardId = card.id"
                  @dblclick.prevent.stop="quickPlay(card)"
                >
                  <span class="color-band" :style="colorStyle(card)"></span>
                  <span class="card-type">{{ cardKindLabel(card) }}</span>
                  <span class="card-art">
                    <b>{{ cardIcon(card) }}</b>
                  </span>
                  <strong>{{ cardTitle(card) }}</strong>
                  <small>{{ cardHint(card) }}</small>
                  <b class="value-badge" v-if="card.valueM !== undefined">{{ card.valueM }}M</b>
                  <span class="card-detail-pop">
                    <b>{{ cardTitle(card) }}</b>
                    <small>{{ cardHint(card) }}</small>
                    <i v-if="card.valueM !== undefined">{{ card.valueM }}M</i>
                  </span>
                </button>
                <div v-if="!hand.length" class="empty-hand">还没有收到手牌。先开始游戏，再摸牌。</div>
              </div>
            </section>

            <section class="action-pad">
              <h2>操作区</h2>
              <button class="primary small" @click="draw" :disabled="actionBusy">摸 2 张</button>
              <button class="secondary small" @click="endTurn" :disabled="actionBusy">结束回合</button>
              <button class="green small" @click="queryPlay('DEPOSIT')" :disabled="!selectedCard || actionBusy">存入银行</button>
              <button class="blue small" @click="queryPlay('DEPLOY')" :disabled="!selectedCard || actionBusy">部署房产</button>
              <button class="purple small" @click="queryPlay('ACTION')" :disabled="!selectedCard || actionBusy">打出行动牌</button>
              <button class="gray small" @click="queryPlay('DISCARD')" :disabled="!selectedCard || actionBusy">弃牌</button>
            </section>
          </section>

          <div v-if="awaitingPayment" class="rent-panel">
            <h2>需要支付 {{ paymentDue }}M</h2>
            <p>已选 {{ selectedPaymentTotal }}M。可以让系统自动选，或者自己点选支付牌。</p>
            <div class="payment-list">
              <button v-for="card in paymentCards" :key="card.id" :class="{ picked: paymentSelection.has(card.id) }" @click="togglePayment(card.id)">
                {{ card.zone }} · {{ cardTitle(card) }} · {{ card.valueM || 0 }}M
              </button>
            </div>
            <div class="payment-actions">
              <button class="primary small" @click="autoPayRent" :disabled="actionBusy">自动支付</button>
              <button class="secondary small" @click="confirmPayRent" :disabled="actionBusy">按所选支付</button>
            </div>
          </div>
        </div>
      </div>
    </section>

    <div v-if="optionSheet" class="modal-backdrop" @click.self="optionSheet = null">
      <section class="option-modal">
        <h2>{{ optionSheet.title }}</h2>
        <p>选择一个合法目标 / 参数</p>
        <button v-for="(option, index) in optionSheet.options" :key="index" @click="playWithOption(option)">
          {{ option.labelZh || option.targetPlayerId || option.targetColorKey || '直接打出' }}
        </button>
      </section>
    </div>
  </main>
</template>
