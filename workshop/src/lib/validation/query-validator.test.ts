import { describe, it, expect } from 'vitest';
import { validateQuery } from './query-validator';
import { createSnapshot } from './registry-snapshot';
import type { ResourceType, QueryDSL } from '../types';

const customerType: ResourceType = {
  name: 'Customer',
  fields: [
    { name: 'name', type: 'string', required: true },
    { name: 'tier', type: 'enum', required: false },
    { name: 'arr', type: 'number', required: false },
  ],
  abilities: ['key', 'query'],
  stateMachine: [],
  relationships: [
    { label: 'placed', targetType: 'Order', semantics: 'Association' },
  ],
  version: 1,
};

const orderType: ResourceType = {
  name: 'Order',
  fields: [{ name: 'total', type: 'number', required: true }],
  abilities: ['key', 'query'],
  stateMachine: [],
  relationships: [],
  version: 1,
};

const snapshot = createSnapshot([customerType, orderType], [], []);

describe('validateQuery', () => {
  it('returns no errors for valid basic query', () => {
    const query: QueryDSL = {
      from: 'Customer',
      select: ['name', 'tier'],
      limit: 10,
    };
    const diags = validateQuery(query, snapshot);
    expect(diags.filter(d => d.severity === 'error')).toHaveLength(0);
  });

  it('reports error when from type does not exist', () => {
    const query: QueryDSL = { from: 'GhostType', select: ['x'], limit: 10 };
    const diags = validateQuery(query, snapshot);
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.some(e => e.message.includes('GhostType'))).toBe(true);
  });

  it('reports error when select field does not exist on type', () => {
    const query: QueryDSL = { from: 'Customer', select: ['nonexistent'], limit: 10 };
    const diags = validateQuery(query, snapshot);
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.some(e => e.message.includes('nonexistent'))).toBe(true);
  });

  it('reports error when traverse path uses undeclared relationship', () => {
    const query: QueryDSL = {
      from: 'Customer',
      traverse: [{ path: 'c --[ghost]--> Order' }],
      select: ['name'],
      limit: 10,
    };
    const diags = validateQuery(query, snapshot);
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.some(e => e.message.includes('ghost'))).toBe(true);
  });

  it('reports error when traverse target type does not exist', () => {
    const query: QueryDSL = {
      from: 'Customer',
      traverse: [{ path: 'c --[placed]--> GhostOrder' }],
      select: ['name'],
      limit: 10,
    };
    const diags = validateQuery(query, snapshot);
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.some(e => e.message.includes('GhostOrder'))).toBe(true);
  });

  it('reports warning when limit is zero or negative', () => {
    const query: QueryDSL = { from: 'Customer', select: ['name'], limit: 0 };
    const diags = validateQuery(query, snapshot);
    const warnings = diags.filter(d => d.severity === 'warning');
    expect(warnings.some(w => w.message.toLowerCase().includes('limit'))).toBe(true);
  });

  it('reports error for filter on non-existent field', () => {
    const query: QueryDSL = {
      from: 'Customer',
      filter: { ghost_field: 'value' },
      select: ['name'],
      limit: 10,
    };
    const diags = validateQuery(query, snapshot);
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.some(e => e.message.includes('ghost_field'))).toBe(true);
  });
});
