import { describe, it, expect } from 'vitest';
import { validateType } from './type-validator';
import { createSnapshot } from './registry-snapshot';
import type { ResourceType } from '../types';

function makeSnapshot(types: ResourceType[]) {
  return createSnapshot(types, [], []);
}

const baseType: ResourceType = {
  name: 'Customer',
  fields: [
    { name: 'name', type: 'string', required: true },
    { name: 'tier', type: 'enum', required: false, enumValues: ['free', 'pro'] },
  ],
  abilities: ['key', 'query', 'mutate'],
  stateMachine: [
    { from: 'Draft', to: 'Active' },
    { from: 'Active', to: 'Frozen' },
  ],
  relationships: [],
  version: 1,
};

describe('validateType', () => {
  it('returns no diagnostics for valid type', () => {
    const diags = validateType(baseType, makeSnapshot([]));
    expect(diags.filter(d => d.severity === 'error')).toHaveLength(0);
  });

  it('reports error for duplicate field names', () => {
    const type = {
      ...baseType,
      fields: [
        { name: 'name', type: 'string' as const, required: true },
        { name: 'name', type: 'number' as const, required: false },
      ],
    };
    const diags = validateType(type, makeSnapshot([]));
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.some(e => e.message.toLowerCase().includes('duplicate'))).toBe(true);
  });

  it('reports error for relationship referencing non-existent type', () => {
    const type = {
      ...baseType,
      relationships: [{ label: 'owns', targetType: 'GhostType', semantics: 'Ownership' as const }],
    };
    const diags = validateType(type, makeSnapshot([baseType]));
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.some(e => e.message.includes('GhostType'))).toBe(true);
  });

  it('does not error on states outside the initial state machine — states are defined implicitly by transitions', () => {
    const type = {
      ...baseType,
      stateMachine: [
        ...baseType.stateMachine,
        { from: 'Active', to: 'Archived' },
      ],
    };
    const diags = validateType(type, makeSnapshot([]));
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.length).toBe(0);
  });

  it('reports warning for orphan state nodes (no incoming edges, excluding initial state)', () => {
    const type = {
      ...baseType,
      stateMachine: [
        { from: 'Draft', to: 'Active' },
        { from: 'Active', to: 'Frozen' },
        { from: 'Frozen', to: 'Frozen' },
      ],
    };
    const diags = validateType(type, makeSnapshot([]));
    const warnings = diags.filter(d => d.severity === 'warning');
    expect(warnings.some(w => w.message.includes('orphan'))).toBe(false);
  });

  it('reports error for relationship with undeclared target type', () => {
    const type = {
      ...baseType,
      relationships: [{ label: 'ref', targetType: 'MissingType', semantics: 'Reference' as const }],
    };
    const diags = validateType(type, makeSnapshot([baseType]));
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.some(e => e.message.includes('MissingType'))).toBe(true);
  });

  it('reports info for PascalCase suggestion', () => {
    const type = { ...baseType, name: 'customer' };
    const diags = validateType(type, makeSnapshot([]));
    const infos = diags.filter(d => d.severity === 'info');
    expect(infos.some(i => i.message.toLowerCase().includes('pascalcase'))).toBe(true);
  });

  it('reports warning for no abilities declared', () => {
    const type = { ...baseType, abilities: [] };
    const diags = validateType(type, makeSnapshot([]));
    const warnings = diags.filter(d => d.severity === 'warning');
    expect(warnings.some(w => w.message.includes('abilities'))).toBe(true);
  });
});
