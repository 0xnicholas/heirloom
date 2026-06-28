import type { Ability } from '@/lib/types';
import { ABILITIES } from '@/lib/constants';

interface AbilitiesMatrixProps {
  selected: Ability[];
  onChange: (abilities: Ability[]) => void;
}

export function AbilitiesMatrix({ selected, onChange }: AbilitiesMatrixProps) {
  const toggle = (ability: Ability) => {
    if (selected.includes(ability)) {
      onChange(selected.filter(a => a !== ability));
    } else {
      onChange([...selected, ability]);
    }
  };

  return (
    <div>
      <h4 className="text-sm font-semibold text-gray-700 dark:text-gray-300 mb-2">Abilities</h4>
      <div className="grid grid-cols-2 gap-1.5">
        {ABILITIES.map(ability => {
          const checked = selected.includes(ability);
          return (
            <label
              key={ability}
              className={`flex items-center gap-2 px-2 py-1.5 rounded text-sm cursor-pointer transition-colors ${
                checked
                  ? 'bg-indigo-50 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300'
                  : 'bg-gray-50 dark:bg-gray-800 text-gray-400 dark:text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-700'
              }`}
            >
              <input
                type="checkbox"
                checked={checked}
                onChange={() => toggle(ability)}
                className="sr-only"
                aria-label={ability}
              />
              <span className={`text-xs font-mono ${checked ? 'text-indigo-600 dark:text-indigo-400' : 'text-gray-400 dark:text-gray-500'}`}>
                {checked ? '✓' : '○'}
              </span>
              <span>{ability}</span>
            </label>
          );
        })}
      </div>
    </div>
  );
}
