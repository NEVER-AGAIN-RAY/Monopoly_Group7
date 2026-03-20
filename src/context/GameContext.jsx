import { createContext, useContext, useReducer, useCallback } from 'react';
import {
  BOARD_TILES,
  STARTING_MONEY,
  GO_SALARY,
  JAIL_POSITION,
  GO_TO_JAIL_POSITION,
  PLAYER_COLORS,
  PLAYER_NAMES_DEFAULT,
  CHANCE_CARDS,
  COMMUNITY_CHEST_CARDS,
} from '../constants/board';

const GameContext = createContext(null);

// ─── helpers ─────────────────────────────────────────────────────────────────

function createPlayers(count) {
  return Array.from({ length: count }, (_, i) => ({
    id: i,
    name: PLAYER_NAMES_DEFAULT[i],
    color: PLAYER_COLORS[i],
    position: 0,
    money: STARTING_MONEY,
    properties: [],
    inJail: false,
    jailTurns: 0,
    bankrupt: false,
  }));
}

function rollDice() {
  const d1 = Math.floor(Math.random() * 6) + 1;
  const d2 = Math.floor(Math.random() * 6) + 1;
  return { d1, d2, total: d1 + d2, doubles: d1 === d2 };
}

function pickRandom(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

// ─── initial state ────────────────────────────────────────────────────────────

const INITIAL_STATE = {
  phase: 'setup',        // 'setup' | 'playing' | 'gameover'
  playerCount: 2,
  players: [],
  currentPlayerIndex: 0,
  dice: { d1: 1, d2: 1, total: 2, doubles: false },
  hasRolled: false,
  properties: {},        // tileId -> { ownerId }
  messages: ['Welcome to Monopoly! Set up your game to start.'],
  winner: null,
};

// ─── reducer ─────────────────────────────────────────────────────────────────

function gameReducer(state, action) {
  switch (action.type) {
    case 'SET_PLAYER_COUNT':
      return { ...state, playerCount: action.count };

    case 'START_GAME': {
      const players = createPlayers(state.playerCount);
      return {
        ...state,
        phase: 'playing',
        players,
        currentPlayerIndex: 0,
        hasRolled: false,
        properties: {},
        messages: [`Game started with ${state.playerCount} players! ${players[0].name}'s turn.`],
        winner: null,
      };
    }

    case 'ROLL_AND_MOVE': {
      if (state.hasRolled) return state;

      const player = state.players[state.currentPlayerIndex];
      if (player.bankrupt) return state;

      const dice = rollDice();
      let messages = [...state.messages];
      let players = [...state.players];
      let properties = { ...state.properties };
      let currentPlayerIndex = state.currentPlayerIndex;
      let phase = state.phase;
      let winner = state.winner;

      messages.push(`${player.name} rolled ${dice.d1} + ${dice.d2} = ${dice.total}${dice.doubles ? ' (Doubles!)' : ''}`);

      // Handle jail
      if (player.inJail) {
        if (dice.doubles) {
          messages.push(`${player.name} rolled doubles and gets out of jail!`);
          players = players.map((p, i) =>
            i === state.currentPlayerIndex ? { ...p, inJail: false, jailTurns: 0 } : p
          );
        } else {
          const newJailTurns = player.jailTurns + 1;
          if (newJailTurns >= 3) {
            messages.push(`${player.name} has served 3 turns in jail and must pay $50 fine.`);
            players = players.map((p, i) =>
              i === state.currentPlayerIndex
                ? { ...p, inJail: false, jailTurns: 0, money: p.money - 50 }
                : p
            );
          } else {
            messages.push(`${player.name} is stuck in jail (turn ${newJailTurns}/3).`);
            players = players.map((p, i) =>
              i === state.currentPlayerIndex ? { ...p, jailTurns: newJailTurns } : p
            );
            return { ...state, dice, players, hasRolled: true, messages };
          }
        }
      }

      // Move player
      const updatedPlayer = players[currentPlayerIndex];
      const oldPos = updatedPlayer.position;
      let newPos = (oldPos + dice.total) % 40;

      // Passed GO
      if (newPos < oldPos && !updatedPlayer.inJail) {
        messages.push(`${player.name} passed GO and collected $${GO_SALARY}!`);
        players = players.map((p, i) =>
          i === currentPlayerIndex ? { ...p, money: p.money + GO_SALARY } : p
        );
      }

      // Go to Jail tile
      if (newPos === GO_TO_JAIL_POSITION) {
        newPos = JAIL_POSITION;
        messages.push(`${player.name} landed on Go to Jail! Go directly to Jail.`);
        players = players.map((p, i) =>
          i === currentPlayerIndex ? { ...p, position: newPos, inJail: true, jailTurns: 0 } : p
        );
        return { ...state, dice, players, hasRolled: true, messages };
      }

      // Update position
      players = players.map((p, i) =>
        i === currentPlayerIndex ? { ...p, position: newPos } : p
      );

      const tile = BOARD_TILES[newPos];
      messages.push(`${player.name} landed on ${tile.name}.`);

      // Handle tile effects
      if (tile.type === 'property' || tile.type === 'railroad' || tile.type === 'utility') {
        const owned = properties[newPos];
        if (!owned) {
          // Tile is available to buy — buying is handled by BUY action
        } else if (owned.ownerId !== currentPlayerIndex) {
          // Pay rent
          const owner = players[owned.ownerId];
          const ownedByOwner = players[owned.ownerId].properties;
          let rent;
          if (tile.type === 'railroad') {
            const railroadsOwned = ownedByOwner.filter(id => BOARD_TILES[id].type === 'railroad').length;
            rent = tile.rent[railroadsOwned - 1] || tile.rent[0];
          } else if (tile.type === 'utility') {
            const utilitiesOwned = ownedByOwner.filter(id => BOARD_TILES[id].type === 'utility').length;
            rent = utilitiesOwned === 2 ? dice.total * 10 : dice.total * 4;
          } else {
            const groupOwned = ownedByOwner.filter(id => BOARD_TILES[id].colorGroup === tile.colorGroup).length;
            const groupTotal = BOARD_TILES.filter(t => t.colorGroup === tile.colorGroup).length;
            const monopoly = groupOwned === groupTotal;
            rent = monopoly ? tile.rent[0] * 2 : tile.rent[0];
          }

          const currentP = players[currentPlayerIndex];
          const actualRent = Math.min(rent, currentP.money);
          messages.push(`${player.name} pays $${actualRent} rent to ${owner.name}.`);

          players = players.map((p, i) => {
            if (i === currentPlayerIndex) return { ...p, money: p.money - actualRent };
            if (i === owned.ownerId) return { ...p, money: p.money + actualRent };
            return p;
          });

          // Check bankruptcy
          if (players[currentPlayerIndex].money <= 0) {
            messages.push(`${player.name} is bankrupt! They are out of the game.`);
            players = players.map((p, i) =>
              i === currentPlayerIndex ? { ...p, bankrupt: true, money: 0 } : p
            );
          }
        } else {
          messages.push(`${player.name} owns this property.`);
        }
      } else if (tile.type === 'tax') {
        messages.push(`${player.name} pays $${tile.amount} in taxes.`);
        players = players.map((p, i) =>
          i === currentPlayerIndex ? { ...p, money: Math.max(0, p.money - tile.amount) } : p
        );
      } else if (tile.type === 'chance') {
        const card = pickRandom(CHANCE_CARDS);
        messages.push(`Chance: "${card.text}"`);
        ({ players, properties, messages } = applyCard(card, players, properties, messages, currentPlayerIndex));
      } else if (tile.type === 'community_chest') {
        const card = pickRandom(COMMUNITY_CHEST_CARDS);
        messages.push(`Community Chest: "${card.text}"`);
        ({ players, properties, messages } = applyCard(card, players, properties, messages, currentPlayerIndex));
      }

      // Check win condition: only one non-bankrupt player remains
      const activePlayers = players.filter(p => !p.bankrupt);
      if (activePlayers.length === 1) {
        winner = activePlayers[0].id;
        phase = 'gameover';
        messages.push(`🏆 ${activePlayers[0].name} wins the game!`);
      }
      return { ...state, dice, players, properties, hasRolled: true, messages, phase, winner };
    }

    case 'BUY_PROPERTY': {
      const player = state.players[state.currentPlayerIndex];
      const tile = BOARD_TILES[player.position];
      if (
        state.properties[player.position] ||
        player.money < tile.price ||
        !['property', 'railroad', 'utility'].includes(tile.type)
      ) {
        return state;
      }

      const messages = [
        ...state.messages,
        `${player.name} bought ${tile.name} for $${tile.price}.`,
      ];
      const players = state.players.map((p, i) =>
        i === state.currentPlayerIndex
          ? { ...p, money: p.money - tile.price, properties: [...p.properties, player.position] }
          : p
      );
      const properties = {
        ...state.properties,
        [player.position]: { ownerId: state.currentPlayerIndex },
      };
      return { ...state, players, properties, messages };
    }

    case 'END_TURN': {
      if (!state.hasRolled) return state;
      let next = (state.currentPlayerIndex + 1) % state.players.length;
      while (state.players[next].bankrupt) {
        next = (next + 1) % state.players.length;
      }
      const messages = [
        ...state.messages,
        `--- ${state.players[next].name}'s turn ---`,
      ];
      return { ...state, currentPlayerIndex: next, hasRolled: false, messages };
    }

    case 'RESET':
      return { ...INITIAL_STATE };

    default:
      return state;
  }
}

function applyCard(card, players, properties, messages, currentPlayerIndex) {
  if (card.action === 'earn') {
    players = players.map((p, i) =>
      i === currentPlayerIndex ? { ...p, money: p.money + card.amount } : p
    );
  } else if (card.action === 'pay') {
    players = players.map((p, i) =>
      i === currentPlayerIndex ? { ...p, money: Math.max(0, p.money - card.amount) } : p
    );
  } else if (card.action === 'jail') {
    players = players.map((p, i) =>
      i === currentPlayerIndex ? { ...p, position: JAIL_POSITION, inJail: true, jailTurns: 0 } : p
    );
    messages = [...messages, `${players[currentPlayerIndex].name} goes to Jail!`];
  } else if (card.action === 'move') {
    const oldPos = players[currentPlayerIndex].position;
    const newPos = card.target;
    if (newPos < oldPos) {
      players = players.map((p, i) =>
        i === currentPlayerIndex ? { ...p, money: p.money + GO_SALARY } : p
      );
      messages = [...messages, `${players[currentPlayerIndex].name} passed GO and collected $${GO_SALARY}!`];
    }
    players = players.map((p, i) =>
      i === currentPlayerIndex ? { ...p, position: newPos } : p
    );
  }
  return { players, properties, messages };
}

// ─── provider & hook ──────────────────────────────────────────────────────────

export function GameProvider({ children }) {
  const [state, dispatch] = useReducer(gameReducer, INITIAL_STATE);

  const setPlayerCount = useCallback((count) => dispatch({ type: 'SET_PLAYER_COUNT', count }), []);
  const startGame = useCallback(() => dispatch({ type: 'START_GAME' }), []);
  const rollAndMove = useCallback(() => dispatch({ type: 'ROLL_AND_MOVE' }), []);
  const buyProperty = useCallback(() => dispatch({ type: 'BUY_PROPERTY' }), []);
  const endTurn = useCallback(() => dispatch({ type: 'END_TURN' }), []);
  const resetGame = useCallback(() => dispatch({ type: 'RESET' }), []);

  return (
    <GameContext.Provider value={{ state, setPlayerCount, startGame, rollAndMove, buyProperty, endTurn, resetGame }}>
      {children}
    </GameContext.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useGame() {
  const ctx = useContext(GameContext);
  if (!ctx) throw new Error('useGame must be used within GameProvider');
  return ctx;
}
