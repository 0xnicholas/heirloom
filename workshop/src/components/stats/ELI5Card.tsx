import { useState } from 'react';

type Level = 'Simple' | 'Technical' | 'Expert';

const explanations: Record<Level, string> = {
  Simple:
    "This is like your business world map — it shows what kinds of things exist (like Customers and Orders), how many there are, and how everything connects together.",
  Technical:
    "The Stats dashboard summarizes the ontology: Resource Types define the schema, Resource Instances are the data, and Relationships model how entities interact. The graph visualizes the type graph for quick orientation.",
  Expert:
    "This view aggregates schema registry metadata — type cardinality, relationship cardinality, ability matrices, and state machine coverage — into a system-level health dashboard with an interactive force-directed type graph.",
};

export function ELI5Card() {
  const [level, setLevel] = useState<Level>('Simple');

  return (
    <div className="bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 p-5 shadow-sm">
      <div className="flex items-start justify-between gap-4 mb-2">
        <h2 className="text-sm font-semibold text-gray-900 dark:text-white">What is this?</h2>
        <select
          value={level}
          onChange={e => setLevel(e.target.value as Level)}
          className="px-2 py-1 text-xs border rounded-md bg-gray-50 dark:bg-gray-800 border-gray-200 dark:border-gray-700 text-gray-700 dark:text-gray-200"
        >
          {(['Simple', 'Technical', 'Expert'] as Level[]).map(l => (
            <option key={l} value={l}>{l} (ELI5)</option>
          ))}
        </select>
      </div>
      <p className="text-sm text-gray-600 dark:text-gray-300 leading-relaxed">{explanations[level]}</p>
    </div>
  );
}
