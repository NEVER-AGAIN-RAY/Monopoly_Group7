// Monopoly board tiles (40 tiles, index 0 = GO)
export const BOARD_TILES = [
  // Bottom row (0-9, right to left on bottom)
  { id: 0, name: 'GO', type: 'go' },
  { id: 1, name: 'Mediterranean Avenue', type: 'property', price: 60, rent: [2, 10, 30, 90, 160, 250], colorGroup: 'brown', houseCost: 50 },
  { id: 2, name: 'Community Chest', type: 'community_chest' },
  { id: 3, name: 'Baltic Avenue', type: 'property', price: 60, rent: [4, 20, 60, 180, 320, 450], colorGroup: 'brown', houseCost: 50 },
  { id: 4, name: 'Income Tax', type: 'tax', amount: 200 },
  { id: 5, name: 'Reading Railroad', type: 'railroad', price: 200, rent: [25, 50, 100, 200] },
  { id: 6, name: 'Oriental Avenue', type: 'property', price: 100, rent: [6, 30, 90, 270, 400, 550], colorGroup: 'lightblue', houseCost: 50 },
  { id: 7, name: 'Chance', type: 'chance' },
  { id: 8, name: 'Vermont Avenue', type: 'property', price: 100, rent: [6, 30, 90, 270, 400, 550], colorGroup: 'lightblue', houseCost: 50 },
  { id: 9, name: 'Connecticut Avenue', type: 'property', price: 120, rent: [8, 40, 100, 300, 450, 600], colorGroup: 'lightblue', houseCost: 50 },

  // Right column bottom to top (10-19)
  { id: 10, name: 'Jail / Just Visiting', type: 'jail' },
  { id: 11, name: 'St. Charles Place', type: 'property', price: 140, rent: [10, 50, 150, 450, 625, 750], colorGroup: 'pink', houseCost: 100 },
  { id: 12, name: 'Electric Company', type: 'utility', price: 150, rent: [4, 10] },
  { id: 13, name: 'States Avenue', type: 'property', price: 140, rent: [10, 50, 150, 450, 625, 750], colorGroup: 'pink', houseCost: 100 },
  { id: 14, name: 'Virginia Avenue', type: 'property', price: 160, rent: [12, 60, 180, 500, 700, 900], colorGroup: 'pink', houseCost: 100 },
  { id: 15, name: 'Pennsylvania Railroad', type: 'railroad', price: 200, rent: [25, 50, 100, 200] },
  { id: 16, name: 'St. James Place', type: 'property', price: 180, rent: [14, 70, 200, 550, 750, 950], colorGroup: 'orange', houseCost: 100 },
  { id: 17, name: 'Community Chest', type: 'community_chest' },
  { id: 18, name: 'Tennessee Avenue', type: 'property', price: 180, rent: [14, 70, 200, 550, 750, 950], colorGroup: 'orange', houseCost: 100 },
  { id: 19, name: 'New York Avenue', type: 'property', price: 200, rent: [16, 80, 220, 600, 800, 1000], colorGroup: 'orange', houseCost: 100 },

  // Top row (20-29, left to right on top)
  { id: 20, name: 'Free Parking', type: 'free_parking' },
  { id: 21, name: 'Kentucky Avenue', type: 'property', price: 220, rent: [18, 90, 250, 700, 875, 1050], colorGroup: 'red', houseCost: 150 },
  { id: 22, name: 'Chance', type: 'chance' },
  { id: 23, name: 'Indiana Avenue', type: 'property', price: 220, rent: [18, 90, 250, 700, 875, 1050], colorGroup: 'red', houseCost: 150 },
  { id: 24, name: 'Illinois Avenue', type: 'property', price: 240, rent: [20, 100, 300, 750, 925, 1100], colorGroup: 'red', houseCost: 150 },
  { id: 25, name: 'B&O Railroad', type: 'railroad', price: 200, rent: [25, 50, 100, 200] },
  { id: 26, name: 'Atlantic Avenue', type: 'property', price: 260, rent: [22, 110, 330, 800, 975, 1150], colorGroup: 'yellow', houseCost: 150 },
  { id: 27, name: 'Ventnor Avenue', type: 'property', price: 260, rent: [22, 110, 330, 800, 975, 1150], colorGroup: 'yellow', houseCost: 150 },
  { id: 28, name: 'Water Works', type: 'utility', price: 150, rent: [4, 10] },
  { id: 29, name: 'Marvin Gardens', type: 'property', price: 280, rent: [24, 120, 360, 850, 1025, 1200], colorGroup: 'yellow', houseCost: 150 },

  // Left column top to bottom (30-39)
  { id: 30, name: 'Go to Jail', type: 'go_to_jail' },
  { id: 31, name: 'Pacific Avenue', type: 'property', price: 300, rent: [26, 130, 390, 900, 1100, 1275], colorGroup: 'green', houseCost: 200 },
  { id: 32, name: 'North Carolina Avenue', type: 'property', price: 300, rent: [26, 130, 390, 900, 1100, 1275], colorGroup: 'green', houseCost: 200 },
  { id: 33, name: 'Community Chest', type: 'community_chest' },
  { id: 34, name: 'Pennsylvania Avenue', type: 'property', price: 320, rent: [28, 150, 450, 1000, 1200, 1400], colorGroup: 'green', houseCost: 200 },
  { id: 35, name: 'Short Line Railroad', type: 'railroad', price: 200, rent: [25, 50, 100, 200] },
  { id: 36, name: 'Chance', type: 'chance' },
  { id: 37, name: 'Park Place', type: 'property', price: 350, rent: [35, 175, 500, 1100, 1300, 1500], colorGroup: 'darkblue', houseCost: 200 },
  { id: 38, name: 'Luxury Tax', type: 'tax', amount: 100 },
  { id: 39, name: 'Boardwalk', type: 'property', price: 400, rent: [50, 200, 600, 1400, 1700, 2000], colorGroup: 'darkblue', houseCost: 200 },
];

export const COLOR_GROUP_STYLES = {
  brown: { bg: 'bg-amber-800', text: 'text-white' },
  lightblue: { bg: 'bg-sky-400', text: 'text-white' },
  pink: { bg: 'bg-pink-500', text: 'text-white' },
  orange: { bg: 'bg-orange-500', text: 'text-white' },
  red: { bg: 'bg-red-600', text: 'text-white' },
  yellow: { bg: 'bg-yellow-400', text: 'text-gray-900' },
  green: { bg: 'bg-green-600', text: 'text-white' },
  darkblue: { bg: 'bg-blue-800', text: 'text-white' },
};

export const PLAYER_COLORS = ['#e74c3c', '#3498db', '#2ecc71', '#f39c12'];
export const PLAYER_NAMES_DEFAULT = ['Player 1', 'Player 2', 'Player 3', 'Player 4'];

export const STARTING_MONEY = 1500;
export const GO_SALARY = 200;
export const JAIL_POSITION = 10;
export const GO_TO_JAIL_POSITION = 30;

export const CHANCE_CARDS = [
  { text: 'Advance to GO. Collect $200.', action: 'move', target: 0 },
  { text: 'Bank pays you dividend of $50.', action: 'earn', amount: 50 },
  { text: 'Go to Jail!', action: 'jail' },
  { text: 'You have won a crossword competition. Collect $100.', action: 'earn', amount: 100 },
  { text: 'Pay poor tax of $15.', action: 'pay', amount: 15 },
  { text: 'Advance to Illinois Avenue.', action: 'move', target: 24 },
  { text: 'Speeding fine $15.', action: 'pay', amount: 15 },
  { text: 'Your building and loan matures. Receive $150.', action: 'earn', amount: 150 },
];

export const COMMUNITY_CHEST_CARDS = [
  { text: 'Bank error in your favor. Collect $200.', action: 'earn', amount: 200 },
  { text: 'Doctor\'s fees. Pay $50.', action: 'pay', amount: 50 },
  { text: 'From sale of stock you get $50.', action: 'earn', amount: 50 },
  { text: 'Go to Jail!', action: 'jail' },
  { text: 'Holiday fund matures. Receive $100.', action: 'earn', amount: 100 },
  { text: 'Income tax refund. Collect $20.', action: 'earn', amount: 20 },
  { text: 'Pay hospital fees of $100.', action: 'pay', amount: 100 },
  { text: 'You inherit $100.', action: 'earn', amount: 100 },
];
