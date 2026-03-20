import { GameProvider, useGame } from './context/GameContext';
import SetupScreen from './components/SetupScreen';
import Board from './components/Board';
import PlayerDashboard from './components/PlayerDashboard';
import DiceRoller from './components/DiceRoller';
import MessageLog from './components/MessageLog';
import GameOverScreen from './components/GameOverScreen';

function GameLayout() {
  const { state, resetGame } = useGame();
  const { phase } = state;

  if (phase === 'setup') return <SetupScreen />;
  if (phase === 'gameover') return <GameOverScreen />;

  return (
    <div className="min-h-screen bg-green-700 flex flex-col items-center py-6 px-4">
      {/* Header */}
      <div className="flex items-center justify-between w-full max-w-5xl mb-4">
        <h1 className="text-3xl font-black text-white tracking-widest drop-shadow">MONOPOLY</h1>
        <span className="text-green-200 text-sm font-semibold">Group 7 Edition</span>
        <button
          onClick={resetGame}
          className="bg-white/20 hover:bg-white/30 text-white text-sm font-semibold px-3 py-1.5 rounded-xl transition-all"
        >
          🔄 New Game
        </button>
      </div>

      {/* Main layout */}
      <div className="flex gap-4 w-full max-w-5xl items-start justify-center">
        {/* Board */}
        <div className="flex-shrink-0">
          <Board />
        </div>

        {/* Right panel */}
        <div className="flex flex-col gap-4 w-72 flex-shrink-0">
          <DiceRoller />
          <PlayerDashboard />
          <MessageLog />
        </div>
      </div>
    </div>
  );
}

export default function App() {
  return (
    <GameProvider>
      <GameLayout />
    </GameProvider>
  );
}
