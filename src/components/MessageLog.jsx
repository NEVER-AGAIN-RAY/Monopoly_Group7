import { useEffect, useRef } from 'react';
import { useGame } from '../context/GameContext';

export default function MessageLog() {
  const { state } = useGame();
  const { messages } = state;
  const bottomRef = useRef(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  return (
    <div className="bg-white rounded-2xl shadow-lg p-4 flex flex-col gap-2">
      <h2 className="text-lg font-bold text-gray-700">📋 Event Log</h2>
      <div className="h-48 overflow-y-auto flex flex-col gap-1 pr-1">
        {messages.map((msg, i) => (
          <div
            key={i}
            className={`text-xs rounded px-2 py-1 leading-snug
              ${msg.startsWith('---') ? 'bg-indigo-100 text-indigo-700 font-semibold' :
                msg.includes('pays') || msg.includes('fine') || msg.includes('tax') ? 'bg-red-50 text-red-700' :
                msg.includes('collected') || msg.includes('bought') || msg.includes('Collect') || msg.includes('Receive') ? 'bg-green-50 text-green-700' :
                msg.includes('Jail') || msg.includes('jail') || msg.includes('bankrupt') ? 'bg-orange-50 text-orange-700' :
                msg.includes('wins') ? 'bg-yellow-100 text-yellow-800 font-bold' :
                'bg-gray-50 text-gray-600'
              }`}
          >
            {msg}
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
    </div>
  );
}
