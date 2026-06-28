import type { ResourceType, Role, Action, QueryDSL, QueryResult } from '@/lib/types';

export interface ResourceInstance {
  rid: string;
  type: string;
  state: string;
  data: Record<string, unknown>;
}

export interface FunctionDef {
  name: string;
  description: string;
  language: string;
  signature: string;
}

export interface AuditEvent {
  id: string;
  timestamp: string;
  actor: string;
  action: string;
  target: string;
}


export const mockTypes: ResourceType[] = [
  {
    name: 'Customer',
    description: 'A business customer or account',
    fields: [
      { name: 'name', type: 'string', required: true },
      { name: 'tier', type: 'enum', required: false, enumValues: ['free', 'pro', 'enterprise'] },
      { name: 'industry', type: 'string', required: false },
      { name: 'arr', type: 'number', required: false },
      { name: 'email', type: 'string', required: false },
    ],
    abilities: ['key', 'query', 'mutate', 'freeze', 'copy'],
    stateMachine: [
      { from: 'Draft', to: 'Active' },
      { from: 'Active', to: 'Frozen' },
      { from: 'Frozen', to: 'Active', label: 'unfreeze' },
    ],
    relationships: [
      { label: 'placed', targetType: 'Order', semantics: 'Association' },
      { label: 'owns', targetType: 'Contract', semantics: 'Ownership' },
    ],
    version: 1,
  },
  {
    name: 'Order',
    description: 'A customer order',
    fields: [
      { name: 'total', type: 'number', required: true },
      { name: 'status', type: 'enum', required: true, enumValues: ['pending', 'shipped', 'delivered', 'cancelled'] },
      { name: 'placedAt', type: 'date', required: false },
    ],
    abilities: ['key', 'query', 'mutate', 'copy'],
    stateMachine: [
      { from: 'Draft', to: 'Pending' },
      { from: 'Pending', to: 'Shipped' },
      { from: 'Shipped', to: 'Delivered' },
      { from: 'Pending', to: 'Cancelled' },
    ],
    relationships: [],
    version: 1,
  },
  {
    name: 'Contract',
    description: 'A legal contract',
    fields: [
      { name: 'title', type: 'string', required: true },
      { name: 'value', type: 'number', required: true },
      { name: 'signedAt', type: 'date', required: false },
    ],
    abilities: ['key', 'query', 'mutate', 'freeze', 'drop'],
    stateMachine: [
      { from: 'Draft', to: 'Active' },
      { from: 'Active', to: 'Expired' },
    ],
    relationships: [],
    version: 1,
  },
  {
    name: 'Product',
    description: 'A product or SKU',
    fields: [
      { name: 'name', type: 'string', required: true },
      { name: 'sku', type: 'string', required: true },
      { name: 'price', type: 'number', required: true },
      { name: 'category', type: 'string', required: false },
    ],
    abilities: ['key', 'query', 'mutate', 'copy'],
    stateMachine: [
      { from: 'Draft', to: 'Active' },
      { from: 'Active', to: 'Discontinued' },
    ],
    relationships: [],
    version: 1,
  },
];

export const mockRoles: Role[] = [
  {
    name: 'Admin',
    scope: 'Ontology',
    targets: [],
    capabilities: [
      { ability: 'query', targetType: '*', scope: 'Ontology' },
      { ability: 'mutate', targetType: '*', scope: 'Ontology' },
    ],
    actors: ['user.admin'],
  },
  {
    name: 'SupplyChainAnalyst',
    scope: 'Type',
    targets: ['Material', 'Inventory'],
    capabilities: [
      { ability: 'query', targetType: 'Material', scope: 'Type' },
      { ability: 'query', targetType: 'Inventory', scope: 'Type' },
    ],
    actors: ['agent.supply_chain_analyst', 'user.alice'],
  },
  {
    name: 'SalesManager',
    scope: 'Type',
    targets: ['Customer', 'Order', 'Contract'],
    capabilities: [
      { ability: 'query', targetType: 'Customer', scope: 'Type' },
      { ability: 'mutate', targetType: 'Customer', scope: 'Type' },
      { ability: 'query', targetType: 'Order', scope: 'Type' },
    ],
    actors: ['user.bob'],
  },
];

export const mockActions: Action[] = [
  {
    name: 'update_tier',
    targetType: 'Customer',
    requires: 'mutate',
    gate: { state: 'Active' },
    parameters: [{ name: 'tier', type: 'enum', required: true }],
    validateRules: ['tier in ["free", "pro", "enterprise"]'],
    executeTemplate: 'UPDATE customer SET tier = {{params.tier}} WHERE rid = {{target.rid}}',
  },
  {
    name: 'freeze_customer',
    targetType: 'Customer',
    requires: 'freeze',
    gate: { state: 'Active' },
    parameters: [{ name: 'reason', type: 'string', required: true }],
    validateRules: [],
    executeTemplate: 'UPDATE customer SET state = "Frozen" WHERE rid = {{target.rid}}',
  },
  {
    name: 'approve_order',
    targetType: 'Order',
    requires: 'mutate',
    gate: { state: 'Pending' },
    parameters: [],
    validateRules: ['target.total > 0'],
    executeTemplate: 'UPDATE order SET status = "Shipped" WHERE rid = {{target.rid}}',
  },
  {
    name: 'send_alert',
    targetType: 'Customer',
    requires: 'query',
    parameters: [{ name: 'message', type: 'string', required: true }],
    validateRules: [],
    executeTemplate: '',
  },
];

export const mockResourceInstances: ResourceInstance[] = [
  { rid: 'ri.customer.1', type: 'Customer', state: 'Active', data: { name: 'Acme Corp', tier: 'enterprise', arr: 1200000 } },
  { rid: 'ri.customer.2', type: 'Customer', state: 'Active', data: { name: 'Beta Ltd', tier: 'pro', arr: 180000 } },
  { rid: 'ri.order.1', type: 'Order', state: 'Shipped', data: { total: 5400, status: 'shipped' } },
  { rid: 'ri.order.2', type: 'Order', state: 'Pending', data: { total: 1200, status: 'pending' } },
  { rid: 'ri.contract.1', type: 'Contract', state: 'Active', data: { title: 'Enterprise SLA', value: 1200000 } },
  { rid: 'ri.product.1', type: 'Product', state: 'Active', data: { name: 'Widget Pro', sku: 'WDG-001', price: 299 } },
];

export const mockFunctions: FunctionDef[] = [
  { name: 'risk_score', description: 'Compute a risk score for a resource instance.', language: 'typescript', signature: 'risk_score(instance: ResourceInstance): number' },
  { name: 'annual_recurring_revenue', description: 'Sum ARR across customers.', language: 'typescript', signature: 'annual_recurring_revenue(): number' },
  { name: 'has_capability', description: 'Check whether an actor has a capability.', language: 'typescript', signature: 'has_capability(actor: string, ability: Ability, targetType: string): boolean' },
];

export const mockEvents: AuditEvent[] = [
  { id: 'ev-1', timestamp: '2026-06-28T10:00:00Z', actor: 'user.admin', action: 'type.created', target: 'Customer' },
  { id: 'ev-2', timestamp: '2026-06-28T10:05:00Z', actor: 'agent.supply_chain_analyst', action: 'query.executed', target: 'Inventory' },
  { id: 'ev-3', timestamp: '2026-06-28T11:30:00Z', actor: 'user.bob', action: 'action.executed', target: 'approve_order' },
];

export function generateMockResults(query: QueryDSL): QueryResult {
  const rows: Record<string, unknown>[] = [];
  const count = Math.min(query.limit || 10, 5);
  for (let i = 0; i < count; i++) {
    const row: Record<string, unknown> = {};
    if (query.select && query.select.length > 0) {
      for (const sel of query.select) {
        if (sel === 'name' || sel === 'c.name') row['name'] = `Mock ${query.from} ${i + 1}`;
        else if (sel === 'tier') row['tier'] = ['free', 'pro', 'enterprise'][i % 3];
        else if (sel === 'total' || sel === 'o.total') row['total'] = Math.round(Math.random() * 100000) / 100;
        else if (sel === 'arr') row['arr'] = Math.round(Math.random() * 1000000) / 100;
        else if (sel === 'status' || sel === 'o.status') row['status'] = ['pending', 'shipped'][i % 2];
        else row[sel] = `mock_${sel}_${i + 1}`;
      }
    } else {
      row['_default'] = `mock_${i}`;
    }
    row['_meta'] = {
      rid: `ri.${query.from.toLowerCase()}.${i + 1}`,
      type: query.from,
      version: 1,
      state: 'Active',
    };
    rows.push(row);
  }
  return {
    rows,
    total: count,
    meta: { query_ms: Math.round(Math.random() * 50), plan: 'mock' },
  };
}
