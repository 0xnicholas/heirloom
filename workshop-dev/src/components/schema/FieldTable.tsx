import { useState, useCallback } from 'react';
import type { Field, FieldType } from '@/lib/types';
import { FIELD_TYPES } from '@/lib/constants';

interface FieldTableProps {
  fields: Field[];
  onChange: (fields: Field[]) => void;
}

export function FieldTable({ fields, onChange }: FieldTableProps) {
  const [dragIndex, setDragIndex] = useState<number | null>(null);

  const updateField = (index: number, patch: Partial<Field>) => {
    const updated = fields.map((f, i) => (i === index ? { ...f, ...patch } : f));
    onChange(updated);
  };

  const removeField = (index: number) => {
    onChange(fields.filter((_, i) => i !== index));
  };

  const addField = () => {
    onChange([...fields, { name: '', type: 'string', required: false }]);
  };

  const handleDragStart = (index: number) => setDragIndex(index);

  const handleDragOver = useCallback((e: React.DragEvent, index: number) => {
    e.preventDefault();
    if (dragIndex === null || dragIndex === index) return;
    const reordered = [...fields];
    const [moved] = reordered.splice(dragIndex, 1);
    reordered.splice(index, 0, moved);
    onChange(reordered);
    setDragIndex(index);
  }, [dragIndex, fields, onChange]);

  const handleDragEnd = () => setDragIndex(null);

  return (
    <div>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-gray-500 text-xs">
            <th className="pb-1.5 font-medium">Name</th>
            <th className="pb-1.5 font-medium">Type</th>
            <th className="pb-1.5 font-medium text-center">Required</th>
            <th className="pb-1.5 w-8"></th>
          </tr>
        </thead>
        <tbody>
          {fields.map((field, i) => (
            <tr
              key={i}
              className="border-t border-gray-100"
              draggable
              onDragStart={() => handleDragStart(i)}
              onDragOver={e => handleDragOver(e, i)}
              onDragEnd={handleDragEnd}
              style={{ cursor: 'grab', opacity: dragIndex === i ? 0.5 : 1 }}
            >
              <td className="py-1.5">
                <input
                  type="text"
                  value={field.name}
                  onChange={e => updateField(i, { name: e.target.value })}
                  className="w-full px-1.5 py-0.5 text-sm border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
                  placeholder="field_name"
                />
              </td>
              <td className="py-1.5">
                <select
                  value={field.type}
                  onChange={e => updateField(i, { type: e.target.value as FieldType })}
                  className="px-1.5 py-0.5 text-sm border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
                >
                  {FIELD_TYPES.map(ft => (
                    <option key={ft} value={ft}>{ft}</option>
                  ))}
                </select>
              </td>
              <td className="py-1.5 text-center">
                <input
                  type="checkbox"
                  checked={field.required}
                  onChange={e => updateField(i, { required: e.target.checked })}
                  aria-label={`${field.name || 'field'} required`}
                />
              </td>
              <td className="py-1.5">
                <button
                  onClick={() => removeField(i)}
                  className="text-red-400 hover:text-red-600 text-xs"
                  aria-label={`Remove ${field.name || 'field'}`}
                >
                  ✕
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <button
        onClick={addField}
        className="mt-3 inline-flex items-center gap-1 text-xs text-indigo-600 hover:text-indigo-800 font-medium"
      >
        + Add Field
      </button>
    </div>
  );
}
