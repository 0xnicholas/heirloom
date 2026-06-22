import { describe, it, expect } from 'vitest';
import { validateAction } from './action-validator';
import { createSnapshot } from './registry-snapshot';
import type { ResourceType, Action } from '../types';

const customerType: ResourceType = {
  name: 'Customer',
  fields: [
    { name: 'name', type: 'string', required: true },
    { name: 'tier', type: 'enum', required: false },
  ],
  abilities: ['key', 'query', 'mutate', 'freeze'],
  stateMachine: [
    { from: 'Draft', to: 'Active' },
    { from: 'Active', to: 'Frozen' },
  ],
  relationships: [],
  version: 1,
};

const snapshot = createSnapshot([customerType], [], []);

describe('validateAction', () => {
  it('returns no errors for valid action', () => {
    const action: Action = {
      name: 'update_tier',
      targetType: 'Customer',
      requires: 'mutate',
      parameters: [{ name: 'tier', type: 'enum', required: true }],
      validateRules: [],
      executeTemplate: '',
    };
    const diags = validateAction(action, snapshot);
    expect(diags.filter(d => d.severity === 'error')).toHaveLength(0);
  });

  it('reports error when target type not registered', () => {
    const action: Action = {
      name: 'bad',
      targetType: 'GhostType',
      requires: 'query',
      parameters: [],
      validateRules: [],
      executeTemplate: '',
    };
    const diags = validateAction(action, snapshot);
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.some(e => e.message.includes('GhostType'))).toBe(true);
  });

  it('reports error when requires ability not declared on target type', () => {
    const action: Action = {
      name: 'delete_customer',
      targetType: 'Customer',
      requires: 'drop', // Customer does NOT have 'drop'
      parameters: [],
      validateRules: [],
      executeTemplate: '',
    };
    const diags = validateAction(action, snapshot);
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.some(e => e.message.includes('drop'))).toBe(true);
  });

  it('reports error when gate state not in target state machine', () => {
    const action: Action = {
      name: 'approve',
      targetType: 'Customer',
      requires: 'mutate',
      gate: { state: 'GhostState' },
      parameters: [],
      validateRules: [],
      executeTemplate: '',
    };
    const diags = validateAction(action, snapshot);
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.some(e => e.message.includes('GhostState'))).toBe(true);
  });

  it('reports error when parameter type does not match field type', () => {
    const action: Action = {
      name: 'update_name',
      targetType: 'Customer',
      requires: 'mutate',
      parameters: [{ name: 'name', type: 'number', required: true }],
      validateRules: [],
      executeTemplate: '',
    };
    const diags = validateAction(action, snapshot);
    expect(diags.filter(d => d.severity === 'error').some(e => e.message.includes('type mismatch'))).toBe(true);
  });

  it('reports warning when parameter is not a field on target type', () => {
    const action: Action = {
      name: 'extra_param',
      targetType: 'Customer',
      requires: 'mutate',
      parameters: [{ name: 'not_a_field', type: 'string', required: false }],
      validateRules: [],
      executeTemplate: '',
    };
    const diags = validateAction(action, snapshot);
    expect(diags.filter(d => d.severity === 'warning').some(e => e.message.includes('not_a_field'))).toBe(true);
  });
});
