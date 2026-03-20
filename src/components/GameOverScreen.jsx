import { useGame } from '../context/GameContext';
import { PLAYER_NAMES_DEFAULT, PLAYER_COLORS } from '../constants/board';

export default function GameOverScreen() {
  const { state, resetGame } = useGame();
  const { players, winner } = state;

  const winnerPlayer = winner !== null ? players.find(p => p.id === winner) : null;

  // Sort players by money descending
  const sorted = [...players].sort((a, b) => b.money - a.money);

  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-green-700">
      <div className="bg-white rounded-3xl shadow-2xl p-10 flex flex-col items-center gap-6 w-full max-w-md">
        <div className="text-7xl">🏆</div>
        <h1 className="text-4xl font-black text-yellow-600 text-center">
          {winnerPlayer ? `${winnerPlayer.name} Wins!` : 'Game Over!'}
        </h1>

        {/* Final standings */}
        <div className="w-full">
          <p className="text-gray-500 font-semibold text-sm text-center mb-3">FINAL STANDINGS</p>
          <div className="flex flex-col gap-2">
            {sorted.map((player, rank) => (
              <div
                key={player.id}
                className={`flex items-center gap-3 p-3 rounded-xl border
                  ${player.id === winner ? 'bg-yellow-50 border-yellow-300' : 'bg-gray-50 border-gray-200'}`}
              >
                <span className="text-xl font-black text-gray-400 w-6">
                  {rank === 0 ? '🥇' : rank === 1 ? '🥈' : rank === 2 ? '🥉' : `${rank + 1}.`}
                </span>
                <div
                  className="w-6 h-6 rounded-full border-2 border-white shadow"
                  style={{ backgroundColor: player.color }}
                />
                <span className="font-bold text-gray-700 flex-1">{player.name}</span>
                <span className={`font-bold ${player.bankrupt ? 'text-red-500' : 'text-green-600'}`}>
                  {player.bankrupt ? 'Bankrupt' : `$${player.money.toLocaleString()}`}
                </span>
              </div>
            ))}
          </div>
        </div>

        <button
          onClick={resetGame}
          className="w-full bg-indigo-600 hover:bg-indigo-700 active:scale-95 text-white text-xl font-black py-4 rounded-2xl shadow-lg transition-all"
        >
          🔄 Play Again
        </button>
      </div>
    </div>
  );
}
