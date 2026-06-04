import { useState, useEffect, useRef } from 'react';
import type { Action, Ability, ResourceType, FieldType, Diagnostic } from '@/lib/types';
import { FIELD_TYPES } from '@/lib/constants';
import { ValidationBar } from '@/components/shared/ValidationBar';
import { createSnapshot } from '@/lib/validation/registry-snapshot';
import { validateAction } from '@/lib/validation/action-validator';

interface ActionEditorProps {
  action: Action | null;
  allTypes: ResourceType[];
  onSave: (action: Action) => void;
  isNew?: boolean;
}

export function ActionEditor({ action, allTypes, onSave, isNew: _isNew }: ActionEditorProps) {
  const [draft, setDraft] = useState<Action | null>(action);
  const [dirty, setDirty] = useState(false);
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([]);
  const timerRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => {
    setDraft(action);
    setDirty(false);
  }, [action]);

  // Debounced live validation (300ms)
  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      if (!draft) {
        setDiagnostics([]);
        return;
      }
      const snapshot = createSnapshot(allTypes, [], []);
      setDiagnostics(validateAction(draft, snapshot));
    }, 300);
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [draft, allTypes]);

  if (!draft) {
    return (
      <div className="flex items-center justify-center h-full text-gray-400">
        Select an action or create one
      </div>
    );
  }

  // Derive available options based on currently selected target type
  const targetType = allTypes.find(t => t.name === draft.targetType);
  const declaredAbilities = targetType?.abilities || [];
  const stateMachineStates = new Set(
    targetType?.stateMachine.flatMap(t => [t.from, t.to]) || [],
  );

  const update = (patch: Partial<Action>) => {
    setDraft(prev => prev ? { ...prev, ...patch } : prev);
    setDirty(true);
  };

  const handleSave = () => {
    onSave(draft);
    setDirty(false);
  };

  const addParam = () => {
    update({
      parameters: [...draft.parameters, { name: '', type: 'string', required: false }],
    });
  };

  const updateParam = (
    i: number,
    patch: Partial<{ name: string; type: FieldType; required: boolean }>,
  ) => {
    const params = draft.parameters.map((p, idx) => (idx === i ? { ...p, ...patch } : p));
    update({ parameters: params });
  };

  const removeParam = (i: number) => {
    update({ parameters: draft.parameters.filter((_, idx) => idx !== i) });
  };

  const hasErrors = diagnostics.some(d => d.severity === 'error');

  return (
    <div className="flex flex-col h-full overflow-auto">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-3 border-b bg-white sticky top-0 z-10">
        <input
          type="text"
          value={draft.name}
          onChange={e => update({ name: e.target.value })}
          className="text-lg font-semibold bg-transparent border-0 focus:outline-none focus:ring-0 text-gray-800"
          placeholder="Action name"
        />
        <div className="flex items-center gap-2">
          <ValidationBar diagnostics={diagnostics} />
          <button
            onClick={handleSave}
            disabled={hasErrors}
            className="px-4 py-1.5 text-sm font-medium text-white bg-indigo-600 rounded-md hover:bg-indigo-700 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          >
            {dirty ? 'Save *' : 'Save'}
          </button>
        </div>
      </div>

      {/* Body */}
      <div className="p-6 space-y-6">
        {/* Target Type / Requires / Gate */}
        <div className="grid grid-cols-3 gap-4">
          <div>
            <label className="text-sm font-semibold text-gray-700 block mb-1">
              Target Type
            </label>
            <select
              value={draft.targetType}
              onChange={e =>
                update({
                  targetType: e.target.value,
                  // Reset requires if it's no longer valid for the new target type
                  requires: 'query',
                })
              }
              className="w-full px-2 py-1.5 text-sm border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
            >
              {allTypes.map(t => (
                <option key={t.name} value={t.name}>
                  {t.name}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="text-sm font-semibold text-gray-700 block mb-1">
              Requires
            </label>
            <select
              value={draft.requires}
              onChange={e => update({ requires: e.target.value as Ability })}
              className="w-full px-2 py-1.5 text-sm border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
            >
              {declaredAbilities.map(a => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
              {declaredAbilities.length === 0 && (
                <option value="query">
                  {draft.requires} (not on target type)
                </option>
              )}
            </select>
          </div>

          <div>
            <label className="text-sm font-semibold text-gray-700 block mb-1">
              Gate (state)
            </label>
            <input
              type="text"
              value={draft.gate?.state || ''}
              onChange={e =>
                update({
                  gate: e.target.value ? { state: e.target.value } : undefined,
                })
              }
              className="w-full px-2 py-1.5 text-sm border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
              placeholder="e.g. Active"
              list="gate-states-list"
            />
            <datalist id="gate-states-list">
              {[...stateMachineStates].map(s => (
                <option key={s} value={s} />
              ))}
            </datalist>
          </div>
        </div>

        {/* Parameters */}
        <div>
          <h4 className="text-sm font-semibold text-gray-700 mb-2">Parameters</h4>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-gray-500 text-xs">
                <th className="pb-1 font-medium">Name</th>
                <th className="pb-1 font-medium">Type</th>
                <th className="pb-1 font-medium text-center">Required</th>
                <th className="pb-1 w-8"></th>
              </tr>
            </thead>
            <tbody>
              {draft.parameters.length === 0 && (
                <tr>
                  <td colSpan={4} className="py-2 text-xs text-gray-400">
                    No parameters defined
                  </td>
                </tr>
              )}
              {draft.parameters.map((p, i) => (
                <tr key={i} className="border-t border-gray-100">
                  <td className="py-1.5">
                    <input
                      type="text"
                      value={p.name}
                      onChange={e => updateParam(i, { name: e.target.value })}
                      className="w-full px-1 py-0.5 text-xs border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
                      placeholder="param_name"
                    />
                  </td>
                  <td className="py-1.5">
                    <select
                      value={p.type}
                      onChange={e => updateParam(i, { type: e.target.value as FieldType })}
                      className="px-1 py-0.5 text-xs border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
                    >
                      {FIELD_TYPES.map(ft => (
                        <option key={ft} value={ft}>
                          {ft}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="py-1.5 text-center">
                    <input
                      type="checkbox"
                      checked={p.required}
                      onChange={e => updateParam(i, { required: e.target.checked })}
                      className="rounded"
                      aria-label={`${p.name} required`}
                    />
                  </td>
                  <td className="py-1.5">
                    <button
                      onClick={() => removeParam(i)}
                      className="text-red-400 hover:text-red-600 text-xs"
                      aria-label={`Remove parameter ${p.name}`}
                    >
                      ✕
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <button
            onClick={addParam}
            className="mt-2 text-xs text-indigo-600 hover:text-indigo-800 font-medium"
          >
            + Add Parameter
          </button>
        </div>

        {/* Validate Rules */}
        <div>
          <h4 className="text-sm font-semibold text-gray-700 mb-2">Validate Rules</h4>
          <textarea
            value={draft.validateRules.join('\n')}
            onChange={e =>
              update({
                validateRules: e.target.value
                  .split('\n')
                  .filter(line => line.trim() !== ''),
              })
            }
            className="w-full h-20 px-3 py-2 text-sm font-mono border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
            placeholder="risk_score(inventory) > 0.3"
          />
        </div>

        {/* Execute */}
        <div>
          <h4 className="text-sm font-semibold text-gray-700 mb-2">Execute</h4>
          <textarea
            value={draft.executeTemplate}
            onChange={e => update({ executeTemplate: e.target.value })}
            className="w-full h-20 px-3 py-2 text-sm font-mono border rounded bg-gray-50 focus:outline-none focus:ring-1 focus:ring-indigo-300"
            placeholder="(Action execution DSL — format TBD in Phase 2 implementation)"
          />
        </div>
      </div>
    </div>
  );
}
