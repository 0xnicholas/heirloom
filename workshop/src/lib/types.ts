// === Resource Type ===
export type FieldType = 'string' | 'number' | 'boolean' | 'enum' | 'date' | 'rid';

export interface Field {
  name: string;
  type: FieldType;
  required: boolean;
  enumValues?: string[];
}

export type Ability = 'key' | 'store' | 'query' | 'mutate' | 'transfer' | 'copy' | 'drop' | 'freeze';

export interface StateTransition {
  from: string;
  to: string;
  label?: string;
}

export type RelationshipSemantics = 'Ownership' | 'Reference' | 'Association';

export interface Relationship {
  label: string;
  targetType: string;
  semantics: RelationshipSemantics;
}

export interface ResourceType {
  name: string;
  description?: string;
  fields: Field[];
  abilities: Ability[];
  stateMachine: StateTransition[];
  relationships: Relationship[];
  version: number;
}

// === Query DSL ===
export interface QueryFilter {
  [field: string]: string | number | boolean | { $eq?: unknown; $gt?: number; $lt?: number; $in?: unknown[]; $and?: QueryFilter[]; $or?: QueryFilter[] };
}

export interface TraverseStep {
  path: string;
  alias?: string;
  filter?: QueryFilter;
  traverse?: TraverseStep[];
}

export interface SearchBlock {
  type: 'hybrid' | 'vector' | 'keyword';
  query: string;
  properties: string[];
  min_score?: number;
}

export interface QueryDSL {
  from: string;
  alias?: string;
  filter?: QueryFilter;
  traverse?: TraverseStep[];
  search?: SearchBlock;
  select?: string[];
  limit?: number;
  offset?: number;
  aggregate?: {
    fn: '$count' | '$sum' | '$avg' | '$max' | '$min';
    field?: string;
    group_by?: string[];
  };
}

// === Query Results ===
export interface QueryResultRow {
  [key: string]: unknown;
  _meta?: {
    rid: string;
    type: string;
    version: number;
    state: string;
  };
}

export interface QueryResult {
  rows: QueryResultRow[];
  total: number;
  meta: {
    query_ms: number;
    plan: string;
  };
}

export interface SavedQuery {
  id: string;
  name: string;
  query: QueryDSL;
  createdAt: string;
  favorited: boolean;
}

// === Security ===
export type RoleScope = 'Ontology' | 'Type' | 'Instance';

export interface Capability {
  ability: Ability;
  targetType: string;
  scope: RoleScope;
}

export interface Role {
  name: string;
  scope: RoleScope;
  targets: string[];
  capabilities: Capability[];
  actors: string[];
}

export interface ActionParam {
  name: string;
  type: FieldType;
  required: boolean;
}

export interface Action {
  name: string;
  targetType: string;
  requires: Ability;
  gate?: {
    state: string;
  };
  parameters: ActionParam[];
  validateRules: string[];
  executeTemplate: string;
}

// === Validation ===
export type Severity = 'error' | 'warning' | 'info';

export interface Diagnostic {
  severity: Severity;
  message: string;
  field?: string;
  line?: number;
  column?: number;
}

// === Schema Registry Snapshot ===
export interface SchemaRegistrySnapshot {
  types: Map<string, ResourceType>;
  actions: Map<string, Action>;
  roles: Map<string, Role>;
}
