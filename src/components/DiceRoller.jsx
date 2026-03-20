import { useGame } from '../context/GameContext';
import { BOARD_TILES } from '../constants/board';

const DICE_DOTS = {
  1: [[50, 50]],
  2: [[25, 25], [75, 75]],
  3: [[25, 25], [50, 50], [75, 75]],
  4: [[25, 25], [75, 25], [25, 75], [75, 75]],
  5: [[25, 25], [75, 25], [50, 50], [25, 75], [75, 75]],
  6: [[25, 25], [75, 25], [25, 50], [75, 50], [25, 75], [75, 75]],
};

function DiceFace({ value }) {
  return (
    <svg viewBox="0 0 100 100" className="w-12 h-12 drop-shadow-md">
      <rect x="5" y="5" width="90" height="90" rx="15" fill="white" stroke="#ccc" strokeWidth="3" />
      {(DICE_DOTS[value] || []).map(([cx, cy], i) => (
        <circle key={i} cx={cx} cy={cy} r="8" fill="#1a1a1a" />
      ))}
    </svg>
  );
}

export default function DiceRoller() {
  const { state, rollAndMove, buyProperty, endTurn } = useGame();
  const { phase, players, currentPlayerIndex, dice, hasRolled, properties } = state;

  if (phase !== 'playing') return null;

  const currentPlayer = players[currentPlayerIndex];
  if (!currentPlayer) return null;

  const currentTile = currentPlayer
    ? BOARD_TILES[currentPlayer.position]
    : null;

  const canBuy =
    hasRolled &&
    currentTile &&
    ['property', 'railroad', 'utility'].includes(currentTile.type) &&
    !properties[currentPlayer.position] &&
    currentPlayer.money >= currentTile.price;

  return (
    <div className="bg-white rounded-2xl shadow-lg p-4 flex flex-col gap-3">
      <h2 className="text-lg font-bold text-gray-700 text-center">
        🎲 Dice
      </h2>

      {/* Dice display */}
      <div className="flex justify-center gap-3 items-center">
        <DiceFace value={dice.d1} />
        <DiceFace value={dice.d2} />
      </div>

      {hasRolled && (
        <p className="text-center text-sm font-semibold text-gray-600">
          Total: {dice.total}
          {dice.doubles && <span className="ml-2 text-yellow-600">Doubles!</span>}
        </p>
      )}

      {/* Action buttons */}
      <div className="flex flex-col gap-2">
        {!hasRolled && !currentPlayer.inJail && (
          <button
            onClick={rollAndMove}
            className="w-full bg-indigo-600 hover:bg-indigo-700 active:scale-95 text-white font-bold py-2 px-4 rounded-xl transition-all"
          >
            🎲 Roll Dice
          </button>
        )}

        {!hasRolled && currentPlayer.inJail && (
          <div className="text-center text-sm text-red-600 font-semibold">
            ⛓️ In Jail – Roll for doubles to escape
            <button
              onClick={rollAndMove}
              className="mt-2 w-full bg-red-500 hover:bg-red-600 text-white font-bold py-2 px-4 rounded-xl transition-all"
            >
              🎲 Roll (Jail)
            </button>
          </div>
        )}

        {canBuy && (
          <button
            onClick={buyProperty}
            className="w-full bg-emerald-600 hover:bg-emerald-700 active:scale-95 text-white font-bold py-2 px-4 rounded-xl transition-all"
          >
            🏠 Buy {currentTile.name} (${currentTile.price})
          </button>
        )}

        {hasRolled && (
          <button
            onClick={endTurn}
            className="w-full bg-gray-600 hover:bg-gray-700 active:scale-95 text-white font-bold py-2 px-4 rounded-xl transition-all"
          >
            ➡️ End Turn
          </button>
        )}
      </div>
    </div>
  );
}
