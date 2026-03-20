import { BOARD_TILES, COLOR_GROUP_STYLES, PLAYER_COLORS } from '../constants/board';
import { useGame } from '../context/GameContext';

// Board layout:
// Tile 0  = GO       (bottom-right corner)
// Tiles 1-9   = bottom row, right to left
// Tile 10 = Jail     (bottom-left corner)
// Tiles 11-19 = left column, bottom to top
// Tile 20 = Free Parking (top-left corner)
// Tiles 21-29 = top row, left to right
// Tile 30 = Go to Jail (top-right corner)
// Tiles 31-39 = right column, top to bottom

// CSS Grid: 11 columns × 11 rows
// Corners occupy 1 cell each at the four corners.
// Edges are 9 cells each between corners.

const GRID_SIZE = 11; // 11×11 grid

// Map tile id → {col, row} (1-based)
function getTileGridPos(id) {
  if (id === 0)  return { col: 11, row: 11 }; // GO - bottom right
  if (id >= 1 && id <= 9)  return { col: 11 - id, row: 11 }; // bottom row R→L
  if (id === 10) return { col: 1,  row: 11 }; // Jail - bottom left
  if (id >= 11 && id <= 19) return { col: 1, row: 11 - (id - 10) }; // left col B→T
  if (id === 20) return { col: 1,  row: 1 };  // Free Parking - top left
  if (id >= 21 && id <= 29) return { col: id - 19, row: 1 }; // top row L→R
  if (id === 30) return { col: 11, row: 1 };  // Go To Jail - top right
  if (id >= 31 && id <= 39) return { col: 11, row: id - 29 }; // right col T→B
  return { col: 1, row: 1 };
}

function getTileColor(tile) {
  if (!tile.colorGroup) return '';
  return COLOR_GROUP_STYLES[tile.colorGroup]?.bg || '';
}

function getTileIcon(tile) {
  switch (tile.type) {
    case 'go':           return '🏁';
    case 'jail':         return '⛓️';
    case 'free_parking': return '🅿️';
    case 'go_to_jail':   return '👮';
    case 'chance':       return '❓';
    case 'community_chest': return '📦';
    case 'tax':          return '💸';
    case 'railroad':     return '🚂';
    case 'utility':      return tile.name.includes('Electric') ? '⚡' : '🚰';
    default:             return '';
  }
}

function PlayerTokens({ tileId }) {
  const { state } = useGame();
  const playersHere = state.players.filter(p => !p.bankrupt && p.position === tileId);
  if (playersHere.length === 0) return null;
  return (
    <div className="flex flex-wrap justify-center gap-0.5 mt-0.5">
      {playersHere.map(p => (
        <div
          key={p.id}
          title={p.name}
          className="w-3 h-3 rounded-full border border-white shadow"
          style={{ backgroundColor: p.color }}
        />
      ))}
    </div>
  );
}

function Tile({ tile }) {
  const { state } = useGame();
  const owned = state.properties[tile.id];
  const isCorner = [0, 10, 20, 30].includes(tile.id);
  const colorBar = getTileColor(tile);
  const icon = getTileIcon(tile);
  const ownerColor = owned !== undefined ? PLAYER_COLORS[owned.ownerId] : null;

  return (
    <div
      className={`relative flex flex-col items-center justify-start overflow-hidden border border-gray-400
        ${isCorner ? 'text-center text-[7px] font-bold' : 'text-[6px]'}
        bg-green-50 h-full w-full`}
    >
      {/* Color group bar */}
      {colorBar && (
        <div className={`w-full h-2 flex-shrink-0 ${colorBar}`} />
      )}

      {/* Owner indicator */}
      {ownerColor && (
        <div
          className="w-full h-1 flex-shrink-0 opacity-70"
          style={{ backgroundColor: ownerColor }}
        />
      )}

      {/* Icon */}
      {icon && <span className="text-[10px] leading-none mt-0.5">{icon}</span>}

      {/* Name */}
      <span className="leading-tight text-center px-px break-words w-full text-gray-800 font-semibold">
        {tile.name}
      </span>

      {/* Price */}
      {tile.price && (
        <span className="text-gray-500 leading-none">${tile.price}</span>
      )}
      {tile.amount && (
        <span className="text-red-600 leading-none">${tile.amount}</span>
      )}

      {/* Player tokens */}
      <PlayerTokens tileId={tile.id} />
    </div>
  );
}

export default function Board() {
  return (
    <div
      className="border-4 border-gray-700 bg-green-700 shadow-2xl"
      style={{
        display: 'grid',
        gridTemplateColumns: `repeat(${GRID_SIZE}, 1fr)`,
        gridTemplateRows: `repeat(${GRID_SIZE}, 1fr)`,
        width: '660px',
        height: '660px',
        minWidth: '660px',
        minHeight: '660px',
      }}
    >
      {BOARD_TILES.map(tile => {
        const { col, row } = getTileGridPos(tile.id);
        return (
          <div
            key={tile.id}
            style={{
              gridColumn: col,
              gridRow: row,
            }}
          >
            <Tile tile={tile} />
          </div>
        );
      })}

      {/* Center area - game logo */}
      <div
        style={{
          gridColumn: '2 / 11',
          gridRow: '2 / 11',
        }}
        className="flex flex-col items-center justify-center bg-green-600 text-white select-none"
      >
        <div className="text-5xl font-black tracking-widest rotate-0 drop-shadow-lg">MONOPOLY</div>
        <div className="text-sm font-semibold tracking-wide mt-1 opacity-80">Group 7 Edition</div>
      </div>
    </div>
  );
}
