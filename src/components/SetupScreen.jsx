import { useGame } from '../context/GameContext';

export default function SetupScreen() {
  const { state, setPlayerCount, startGame } = useGame();
  const { playerCount } = state;

  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-green-700">
      <div className="bg-white rounded-3xl shadow-2xl p-10 flex flex-col items-center gap-8 w-full max-w-md">
        {/* Title */}
        <div className="text-center">
          <h1 className="text-6xl font-black text-indigo-700 tracking-widest">MONOPOLY</h1>
          <p className="text-gray-500 text-sm mt-1 font-semibold tracking-wide">Group 7 Edition</p>
        </div>

        {/* Player count selector */}
        <div className="w-full">
          <label className="block text-gray-700 font-bold mb-3 text-center text-lg">
            How many players?
          </label>
          <div className="flex justify-center gap-3">
            {[2, 3, 4].map(n => (
              <button
                key={n}
                onClick={() => setPlayerCount(n)}
                className={`w-16 h-16 rounded-2xl text-2xl font-black border-2 transition-all
                  ${playerCount === n
                    ? 'bg-indigo-600 text-white border-indigo-700 scale-110 shadow-lg'
                    : 'bg-gray-100 text-gray-600 border-gray-200 hover:bg-gray-200'}`}
              >
                {n}
              </button>
            ))}
          </div>
        </div>

        {/* Player preview */}
        <div className="w-full">
          <p className="text-gray-500 text-xs font-semibold text-center mb-2">PLAYERS</p>
          <div className="flex justify-center gap-3">
            {['Player 1', 'Player 2', 'Player 3', 'Player 4'].slice(0, playerCount).map((name, i) => (
              <div key={i} className="flex flex-col items-center gap-1">
                <div
                  className="w-10 h-10 rounded-full border-4 border-white shadow-md"
                  style={{ backgroundColor: ['#e74c3c', '#3498db', '#2ecc71', '#f39c12'][i] }}
                />
                <span className="text-xs text-gray-600 font-medium">{name}</span>
              </div>
            ))}
          </div>
        </div>

        {/* Start button */}
        <button
          onClick={startGame}
          className="w-full bg-indigo-600 hover:bg-indigo-700 active:scale-95 text-white text-xl font-black py-4 rounded-2xl shadow-lg transition-all"
        >
          🎲 Start Game!
        </button>

        {/* Game info */}
        <div className="text-center text-gray-400 text-xs space-y-1">
          <p>Each player starts with $1,500</p>
          <p>Collect $200 when passing GO</p>
        </div>
      </div>
    </div>
  );
}
