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
      <div className="flex-1 p-6 space-y-6">
        {/* Description */}
        <div>
          <label className="text-sm font-semibold text-gray-700 block mb-1">Description</label>
          <input
            type="text"
            value={draft.description || ''}
            onChange={e => update({ description: e.target.value })}
            className="w-full px-2 py-1.5 text-sm border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
            placeholder="Optional description"
          />
        </div>

        {/* Fields */}
        <FieldTable
          fields={draft.fields}
          onChange={fields => update({ fields })}
        />

        {/* State Machine + Abilities side by side */}
        <div className="grid grid-cols-2 gap-6">
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

        {/* Relationships */}
        <RelationshipList
          relationships={draft.relationships}
          typeName={draft.name}
          allTypes={allTypes.filter(t => t.name !== draft.name).map(t => t.name)}
          onChange={relationships => update({ relationships })}
        />
      </div>
    </div>
  );
}
