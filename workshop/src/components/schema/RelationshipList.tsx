import type { Relationship, RelationshipSemantics } from '@/lib/types';
import { RELATIONSHIP_SEMANTICS } from '@/lib/constants';

interface RelationshipListProps {
  relationships: Relationship[];
  typeName: string;
  allTypes: string[];
  onChange: (relationships: Relationship[]) => void;
}

export function RelationshipList({ relationships, typeName, allTypes, onChange }: RelationshipListProps) {
  const updateRel = (index: number, patch: Partial<Relationship>) => {
    const updated = relationships.map((r, i) => (i === index ? { ...r, ...patch } : r));
    onChange(updated);
  };

  const removeRel = (index: number) => {
    onChange(relationships.filter((_, i) => i !== index));
  };

  const addRel = () => {
    onChange([...relationships, { label: '', targetType: allTypes[0] || '', semantics: 'Association' }]);
  };

  return (
    <div>
      <h4 className="text-sm font-semibold text-gray-700 mb-2">Relationships</h4>
      {relationships.length === 0 && (
        <p className="text-sm text-gray-400 mb-2">No relationships defined</p>
      )}
      {relationships.map((rel, i) => (
        <div key={i} className="flex items-center gap-2 mb-1.5 text-sm">
          <span className="text-gray-500 font-mono">{typeName}</span>
          <span className="text-gray-400">─[</span>
          <input
            type="text"
            value={rel.label}
            onChange={e => updateRel(i, { label: e.target.value })}
            className="w-20 px-1 py-0.5 text-xs border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300 font-mono"
            placeholder="label"
          />
          <span className="text-gray-400">]─▶</span>
          <select
            value={rel.targetType}
            onChange={e => updateRel(i, { targetType: e.target.value })}
            className="px-1 py-0.5 text-xs border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
          >
            {allTypes.map(t => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
          <select
            value={rel.semantics}
            onChange={e => updateRel(i, { semantics: e.target.value as RelationshipSemantics })}
            className="px-1 py-0.5 text-xs border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
          >
            {RELATIONSHIP_SEMANTICS.map(s => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
          <button onClick={() => removeRel(i)} className="text-red-400 hover:text-red-600 text-xs">✕</button>
        </div>
      ))}
      <button onClick={addRel} className="text-xs text-indigo-600 hover:text-indigo-800 font-medium mt-1">
        + Add Relationship
      </button>
    </div>
  );
}
