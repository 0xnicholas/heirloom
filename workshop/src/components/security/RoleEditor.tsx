import { useState, useEffect } from 'react';
import type { Role, RoleScope, Ability, ResourceType } from '@/lib/types';
import { ABILITIES } from '@/lib/constants';

interface RoleEditorProps {
  role: Role | null;
  allTypes: ResourceType[];
  onSave: (role: Role) => void;
  isNew?: boolean;
}

export function RoleEditor({ role, allTypes, onSave }: RoleEditorProps) {
  const [draft, setDraft] = useState<Role | null>(role);
  const [dirty, setDirty] = useState(false);

  // Reset draft when the edited entity changes (form reset pattern)
  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    setDraft(role);
    setDirty(false);
  }, [role]);
  /* eslint-enable react-hooks/set-state-in-effect */

  if (!draft) {
    return (
      <div className="flex items-center justify-center h-full text-gray-400 dark:text-gray-500">
        Select a role or create one
      </div>
    );
  }

  const update = (patch: Partial<Role>) => {
    setDraft(prev => prev ? { ...prev, ...patch } : prev);
    setDirty(true);
  };

  const handleSave = () => {
    onSave(draft);
    setDirty(false);
  };

  const addCapability = () => {
    update({
      capabilities: [
        ...draft.capabilities,
        { ability: 'query', targetType: allTypes[0]?.name || '*', scope: draft.scope },
      ],
    });
  };

  const removeCapability = (i: number) => {
    update({ capabilities: draft.capabilities.filter((_, idx) => idx !== i) });
  };

  const updateCapability = (
    i: number,
    patch: Partial<{ ability: Ability; targetType: string; scope: RoleScope }>,
  ) => {
    const caps = draft.capabilities.map((c, idx) => (idx === i ? { ...c, ...patch } : c));
    update({ capabilities: caps });
  };

  const addActor = () => {
    update({ actors: [...draft.actors, ''] });
  };

  const updateActor = (i: number, value: string) => {
    const actors = draft.actors.map((a, idx) => (idx === i ? value : a));
    update({ actors });
  };

  const removeActor = (i: number) => {
    update({ actors: draft.actors.filter((_, idx) => idx !== i) });
  };

  const inputClass =
    'px-2 py-1.5 text-sm border border-gray-200 dark:border-gray-700 rounded focus:outline-none focus:ring-1 focus:ring-indigo-300 bg-white dark:bg-gray-800 text-gray-800 dark:text-gray-100 placeholder-gray-400 dark:placeholder-gray-500';
  const selectClass =
    'px-2 py-1.5 text-xs border border-gray-200 dark:border-gray-700 rounded focus:outline-none focus:ring-1 focus:ring-indigo-300 bg-white dark:bg-gray-800 text-gray-800 dark:text-gray-100';

  return (
    <div className="flex flex-col h-full overflow-auto bg-white dark:bg-gray-900">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-3 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 sticky top-0 z-10">
        <input
          type="text"
          value={draft.name}
          onChange={e => update({ name: e.target.value })}
          className="text-lg font-semibold bg-transparent border-0 focus:outline-none focus:ring-0 text-gray-800 dark:text-gray-100 placeholder-gray-400 dark:placeholder-gray-500"
          placeholder="Role name"
        />
        <button
          onClick={handleSave}
          disabled={!dirty}
          className="px-4 py-1.5 text-sm font-medium text-white bg-indigo-600 rounded-md hover:bg-indigo-700 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        >
          {dirty ? 'Save *' : 'Save'}
        </button>
      </div>

      {/* Body */}
      <div className="p-6 space-y-6">
        {/* Scope + Targets */}
        <div className="flex gap-6">
          <div>
            <label className="text-sm font-semibold text-gray-700 dark:text-gray-300 block mb-1">Scope</label>
            <select
              value={draft.scope}
              onChange={e => update({ scope: e.target.value as RoleScope })}
              className={selectClass}
            >
              <option value="Ontology">Ontology</option>
              <option value="Type">Type</option>
              <option value="Instance">Instance</option>
            </select>
          </div>

          {draft.scope !== 'Ontology' && (
            <div>
              <label className="text-sm font-semibold text-gray-700 dark:text-gray-300 block mb-1">Targets</label>
              <div className="flex flex-wrap gap-2">
                {allTypes.map(t => {
                  const checked = draft.targets.includes(t.name);
                  return (
                    <label
                      key={t.name}
                      className="flex items-center gap-1.5 text-sm cursor-pointer text-gray-700 dark:text-gray-300"
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={e =>
                          update({
                            targets: e.target.checked
                              ? [...draft.targets, t.name]
                              : draft.targets.filter(x => x !== t.name),
                          })
                        }
                        className="rounded"
                      />
                      {t.name}
                    </label>
                  );
                })}
              </div>
            </div>
          )}
        </div>

        {/* Granted Capabilities */}
        <div>
          <h4 className="text-sm font-semibold text-gray-700 dark:text-gray-300 mb-2">Granted Capabilities</h4>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-gray-500 dark:text-gray-400 text-xs">
                <th className="pb-1 font-medium">Ability</th>
                <th className="pb-1 font-medium">Target Type</th>
                <th className="pb-1 font-medium">Scope</th>
                <th className="pb-1 w-8"></th>
              </tr>
            </thead>
            <tbody>
              {draft.capabilities.length === 0 && (
                <tr>
                  <td colSpan={4} className="py-2 text-xs text-gray-400 dark:text-gray-500">
                    No capabilities granted
                  </td>
                </tr>
              )}
              {draft.capabilities.map((cap, i) => (
                <tr key={i} className="border-t border-gray-100 dark:border-gray-800">
                  <td className="py-1.5">
                    <select
                      value={cap.ability}
                      onChange={e => updateCapability(i, { ability: e.target.value as Ability })}
                      className={selectClass}
                    >
                      {ABILITIES.map(a => (
                        <option key={a} value={a}>
                          {a}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="py-1.5">
                    <select
                      value={cap.targetType}
                      onChange={e => updateCapability(i, { targetType: e.target.value })}
                      className={selectClass}
                    >
                      <option value="*">* (Global)</option>
                      {allTypes.map(t => (
                        <option key={t.name} value={t.name}>
                          {t.name}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="py-1.5">
                    <select
                      value={cap.scope}
                      onChange={e => updateCapability(i, { scope: e.target.value as RoleScope })}
                      className={selectClass}
                    >
                      <option value="Ontology">Ontology</option>
                      <option value="Type">Type</option>
                      <option value="Instance">Instance</option>
                    </select>
                  </td>
                  <td className="py-1.5">
                    <button
                      onClick={() => removeCapability(i)}
                      className="text-red-400 hover:text-red-600 text-xs"
                      aria-label={`Remove capability ${i}`}
                    >
                      ✕
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <button
            onClick={addCapability}
            className="mt-2 text-xs text-indigo-600 dark:text-indigo-400 hover:text-indigo-800 dark:hover:text-indigo-300 font-medium"
          >
            + Grant Capability
          </button>
        </div>

        {/* Assigned Actors */}
        <div>
          <h4 className="text-sm font-semibold text-gray-700 dark:text-gray-300 mb-2">Assigned Actors</h4>
          {draft.actors.length === 0 && (
            <p className="text-xs text-gray-400 dark:text-gray-500 mb-2">No actors assigned</p>
          )}
          {draft.actors.map((actor, i) => (
            <div key={i} className="flex items-center gap-2 mb-1.5">
              <input
                type="text"
                value={actor}
                onChange={e => updateActor(i, e.target.value)}
                className={`flex-1 ${inputClass}`}
                placeholder="agent.name or user.name"
              />
              <button
                onClick={() => removeActor(i)}
                className="text-red-400 hover:text-red-600 text-xs"
              >
                ✕
              </button>
            </div>
          ))}
          <button
            onClick={addActor}
            className="mt-1 text-xs text-indigo-600 dark:text-indigo-400 hover:text-indigo-800 dark:hover:text-indigo-300 font-medium"
          >
            + Assign Actor
          </button>
        </div>
      </div>
    </div>
  );
}
