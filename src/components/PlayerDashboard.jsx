import { useGame } from '../context/GameContext';
import { BOARD_TILES, COLOR_GROUP_STYLES } from '../constants/board';

function PropertyBadge({ tileId }) {
  const tile = BOARD_TILES[tileId];
  if (!tile) return null;
  const style = tile.colorGroup ? COLOR_GROUP_STYLES[tile.colorGroup] : null;
  return (
    <span
      className={`inline-block text-[9px] px-1 rounded font-semibold border border-gray-300
        ${style ? `${style.bg} ${style.text}` : 'bg-gray-200 text-gray-700'}`}
      title={tile.name}
    >
      {tile.name.split(' ').slice(-1)[0]}
    </span>
  );
}

function PlayerCard({ player, isActive }) {
  return (
    <div
      className={`rounded-xl p-3 border-2 transition-all shadow-sm
        ${isActive ? 'border-yellow-400 bg-yellow-50 shadow-yellow-200 shadow-md' : 'border-gray-200 bg-white'}`}
    >
      {/* Header */}
      <div className="flex items-center gap-2 mb-2">
        <div
          className="w-5 h-5 rounded-full border-2 border-white shadow flex-shrink-0"
          style={{ backgroundColor: player.color }}
        />
        <span className={`font-bold text-sm ${isActive ? 'text-yellow-800' : 'text-gray-700'}`}>
          {player.name}
          {isActive && <span className="ml-1 text-yellow-600">▶</span>}
          {player.bankrupt && <span className="ml-1 text-red-600 text-xs">(Bankrupt)</span>}
          {player.inJail && <span className="ml-1 text-orange-600 text-xs">⛓️ Jail</span>}
        </span>
      </div>

      {/* Money */}
      <div className="flex items-center gap-1 mb-1">
        <span className="text-green-600 font-bold text-base">
          ${player.money.toLocaleString()}
        </span>
      </div>

      {/* Position */}
      <div className="text-xs text-gray-500 mb-2">
        📍 {BOARD_TILES[player.position]?.name}
      </div>

      {/* Properties */}
      {player.properties.length > 0 && (
        <div>
          <div className="text-[10px] text-gray-400 font-semibold mb-1">PROPERTIES ({player.properties.length})</div>
          <div className="flex flex-wrap gap-1">
            {player.properties.map(id => (
              <PropertyBadge key={id} tileId={id} />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

export default function PlayerDashboard() {
  const { state } = useGame();
  const { players, currentPlayerIndex, phase } = state;

  if (phase === 'setup') return null;

  return (
    <div className="flex flex-col gap-3">
      <h2 className="text-lg font-bold text-gray-700">👥 Players</h2>
      {players.map((player, i) => (
        <PlayerCard
          key={player.id}
          player={player}
          isActive={i === currentPlayerIndex && !player.bankrupt}
        />
      ))}
    </div>
  );
}
