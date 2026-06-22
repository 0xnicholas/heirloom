import { describe, it, expect } from 'vitest';
import { createSnapshot, updateTypeInSnapshot, updateActionInSnapshot, updateRoleInSnapshot } from './registry-snapshot';
import type { ResourceType, Action, Role } from '../types';

const customerType: ResourceType = {
  name: 'Customer',
  fields: [{ name: 'name', type: 'string', required: true }],
  abilities: ['key', 'query', 'mutate'],
  stateMachine: [{ from: 'Draft', to: 'Active' }],
  relationships: [],
  version: 1,
};

const orderType: ResourceType = {
  name: 'Order',
  fields: [{ name: 'total', type: 'number', required: true }],
  abilities: ['key', 'query'],
  stateMachine: [],
  relationships: [{ label: 'placed', targetType: 'Customer', semantics: 'Association' }],
  version: 1,
};

describe('createSnapshot', () => {
  it('builds snapshot from type/action/role arrays', () => {
    const snapshot = createSnapshot([customerType, orderType], [], []);
    expect(snapshot.types.size).toBe(2);
    expect(snapshot.types.get('Customer')?.fields[0].name).toBe('name');
    expect(snapshot.types.get('Order')?.relationships[0].targetType).toBe('Customer');
  });

  it('handles empty arrays', () => {
    const snapshot = createSnapshot([], [], []);
    expect(snapshot.types.size).toBe(0);
    expect(snapshot.actions.size).toBe(0);
    expect(snapshot.roles.size).toBe(0);
  });
});

describe('updateTypeInSnapshot', () => {
  it('adds new type to snapshot', () => {
    const snapshot = createSnapshot([], [], []);
    const updated = updateTypeInSnapshot(snapshot, customerType);
    expect(updated.types.get('Customer')).toBeDefined();
  });

  it('updates existing type', () => {
    const snapshot = createSnapshot([customerType], [], []);
    const updated = updateTypeInSnapshot(snapshot, {
      ...customerType,
      fields: [...customerType.fields, { name: 'tier', type: 'enum', required: false, enumValues: ['free', 'pro'] }],
    });
    expect(updated.types.get('Customer')?.fields).toHaveLength(2);
  });

  it('returns new snapshot instance (immutable)', () => {
    const snapshot = createSnapshot([customerType], [], []);
    const updated = updateTypeInSnapshot(snapshot, customerType);
    expect(updated).not.toBe(snapshot);
  });
});

describe('updateActionInSnapshot', () => {
  it('adds new action and updates action map', () => {
    const action: Action = {
      name: 'update_tier',
      targetType: 'Customer',
      requires: 'mutate',
      parameters: [],
      validateRules: [],
      executeTemplate: '',
    };
    const snapshot = createSnapshot([], [], []);
    const updated = updateActionInSnapshot(snapshot, action);
    expect(updated.actions.get('update_tier')?.requires).toBe('mutate');
  });
});

describe('updateRoleInSnapshot', () => {
  it('adds new role and updates role map', () => {
    const role: Role = {
      name: 'Admin',
      scope: 'Ontology',
      targets: [],
      capabilities: [{ ability: 'query', targetType: '*', scope: 'Ontology' }],
      actors: [],
    };
    const snapshot = createSnapshot([], [], []);
    const updated = updateRoleInSnapshot(snapshot, role);
    expect(updated.roles.get('Admin')).toBeDefined();
  });
});
