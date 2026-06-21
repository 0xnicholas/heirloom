import { useState, useEffect } from 'react';
import type { ResourceType, Ability, SchemaRegistrySnapshot, Diagnostic } from '@/lib/types';
import { FieldTable } from './FieldTable';
import { StateMachineEditor } from './StateMachineEditor';
import { AbilitiesMatrix } from './AbilitiesMatrix';
import { RelationshipList } from './RelationshipList';
import { ValidationBar } from '@/components/shared/ValidationBar';
import { createSnapshot } from '@/lib/validation/registry-snapshot';
import { validateType } from '@/lib/validation/type-validator';

// Inline stub hooks (to be replaced with debounced versions in Task 13)
function useRegistrySnapshot(types: ResourceType[], _actions: never[]) {
  return createSnapshot(types, [], []);
}

function useDebouncedTypeValidation(type: ResourceType | null, snapshot: SchemaRegistrySnapshot): Diagnostic[] {
  if (!type) return [];
  return validateType(type, snapshot);
}

interface TypeEditorProps {
  type: ResourceType | null;
  allTypes: ResourceType[];
  onSave: (type: ResourceType) => void;
  isNew?: boolean;
}

export function TypeEditor({ type, allTypes, onSave }: TypeEditorProps) {
  const [draft, setDraft] = useState<ResourceType | null>(type);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    setDraft(type);
    setDirty(false);
  }, [type]);

  // Warn on browser tab close with unsaved changes
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [dirty]);

  if (!draft) {
    return (
      <div className="flex items-center justify-center h-full text-gray-400">
        Select a type or create a new one
      </div>
    );
  }

  const snapshot = useRegistrySnapshot(allTypes, []);
  const diagnostics = useDebouncedTypeValidation(draft, snapshot);

  const handleSave = () => {
    onSave(draft);
    setDirty(false);
  };

  const update = (patch: Partial<ResourceType>) => {
    setDraft(prev => prev ? { ...prev, ...patch } : prev);
    setDirty(true);
  };

  return (
    <div className="flex flex-col h-full overflow-auto">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-3 border-b border-gray-200 bg-white sticky top-0 z-10">
        <input
          type="text"
          value={draft.name}
          onChange={e => update({ name: e.target.value })}
          className="text-lg font-semibold bg-transparent border-0 focus:outline-none focus:ring-0 text-gray-800"
          placeholder="Type name"
        />
        <div className="flex items-center gap-2">
          <ValidationBar diagnostics={diagnostics} />
          <button
            onClick={handleSave}
            disabled={diagnostics.some(d => d.severity === 'error')}
            className="px-4 py-1.5 text-sm font-medium text-white bg-indigo-600 rounded-md hover:bg-indigo-700 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          >
            {dirty ? 'Save *' : 'Save'}
          </button>
        </div>
      </div>

      {/* Body */}
      <div className="flex-1 p-6 space-y-4 overflow-auto">

        {/* Card 1: 属性 — what this entity is */}
        <section className="bg-white rounded-lg border border-gray-200 shadow-sm">
          <div className="px-4 py-2.5 border-b border-gray-100 bg-gray-50/50 flex items-center gap-2 rounded-t-lg">
            <span className="text-xs font-bold text-gray-400 bg-gray-100 px-1.5 py-0.5 rounded">1</span>
            <h3 className="text-sm font-semibold text-gray-700">属性</h3>
            <span className="text-xs text-gray-400">Properties</span>
          </div>
          <div className="p-4 space-y-4">
            <div>
              <label className="text-xs font-medium text-gray-500 block mb-1">Description</label>
              <input
                type="text"
                value={draft.description || ''}
                onChange={e => update({ description: e.target.value })}
                className="w-full px-2.5 py-1.5 text-sm border rounded-md focus:outline-none focus:ring-1 focus:ring-indigo-300 focus:border-indigo-300"
                placeholder="What is this entity in business terms?"
              />
            </div>
            <FieldTable
              fields={draft.fields}
              onChange={fields => update({ fields })}
            />
          </div>
        </section>

        {/* Card 2: 能力与约束 — what this entity can do & can't do */}
        <section className="bg-white rounded-lg border border-gray-200 shadow-sm">
          <div className="px-4 py-2.5 border-b border-gray-100 bg-gray-50/50 flex items-center gap-2 rounded-t-lg">
            <span className="text-xs font-bold text-gray-400 bg-gray-100 px-1.5 py-0.5 rounded">2</span>
            <h3 className="text-sm font-semibold text-gray-700">能力与约束</h3>
            <span className="text-xs text-gray-400">Capabilities &amp; Constraints</span>
          </div>
          <div className="p-4 grid grid-cols-2 gap-6">
            <StateMachineEditor
              key={draft.name}
              transitions={draft.stateMachine}
              onChange={stateMachine => update({ stateMachine })}
            />
            <AbilitiesMatrix
              selected={draft.abilities}
              onChange={abilities => update({ abilities: abilities as Ability[] })}
            />
          </div>
        </section>

        {/* Card 3: 关系 — how this entity connects to others */}
        <section className="bg-white rounded-lg border border-gray-200 shadow-sm">
          <div className="px-4 py-2.5 border-b border-gray-100 bg-gray-50/50 flex items-center gap-2 rounded-t-lg">
            <span className="text-xs font-bold text-gray-400 bg-gray-100 px-1.5 py-0.5 rounded">3</span>
            <h3 className="text-sm font-semibold text-gray-700">关系</h3>
            <span className="text-xs text-gray-400">Relationships</span>
          </div>
          <div className="p-4">
            <RelationshipList
              relationships={draft.relationships}
              typeName={draft.name}
              allTypes={allTypes.filter(t => t.name !== draft.name).map(t => t.name)}
              onChange={relationships => update({ relationships })}
            />
          </div>
        </section>

      </div>
    </div>
  );
}
