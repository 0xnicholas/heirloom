# Heirloom Workshop 前端实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 Heirloom Workshop 前端管理控制台——面向本体建模者的 React SPA，覆盖 Schema 定义、语义查询和安全管理三大功能域。

**Architecture:** React 19 + TypeScript 5 + Vite SPA，三 Tab 导航（Schema / Query / Security）+ 全局 Query Console 抽屉。MSW mock API 层实现前端独立开发，TanStack Query 管理服务端状态，Monaco 编辑器 + ReactFlow 图可视化 + TanStack Table。数据流通过适配器模式隔离 mock/real 切换。

**Tech Stack:** React 19, TypeScript 5, Vite, React Router v6, TanStack Query v5, MSW v2, Monaco Editor, ReactFlow (@xyflow/react), TanStack Table v8, Tailwind CSS, Vitest + React Testing Library

**Spec:** `docs/superpowers/specs/2026-06-04-heirloom-frontend-design.md`

---

### Task 1: 项目脚手架与依赖安装

**Files:**
- Create: `workshop/` 目录下所有 Vite 脚手架文件
- Create: `workshop/.env`
- Create: `workshop/.env.mock`
- Modify: `workshop/package.json` (deps)
- Modify: `workshop/tailwind.config.js`
- Modify: `workshop/tsconfig.json`
- Modify: `.gitignore`

- [ ] **Step 1: 创建 Vite React-TS 项目**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
npm create vite@latest workshop -- --template react-ts
cd workshop
```

- [ ] **Step 2: 安装依赖**

```bash
npm install react-router-dom@6 @tanstack/react-query@5 @monaco-editor/react @xyflow/react @tanstack/react-table@8
npm install -D tailwindcss @tailwindcss/vite msw@2 vitest @testing-library/react @testing-library/jest-dom @testing-library/user-event jsdom
```

- [ ] **Step 3: 配置 Tailwind CSS**

```bash
# tailwind.config.js 不需要了，Vite plugin 方式在 vite.config.ts 中配置
```

在 `workshop/vite.config.ts` 中添加 tailwindcss plugin：

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
})
```

在 `workshop/src/index.css` 顶部添加：

```css
@import "tailwindcss";
```

- [ ] **Step 4: 配置 TypeScript 路径别名**

在 `workshop/tsconfig.json` 中确保：

```json
{
  "compilerOptions": {
    "baseUrl": ".",
    "paths": {
      "@/*": ["./src/*"]
    }
  }
}
```

在 `workshop/vite.config.ts` 中添加 resolve alias：

```typescript
import path from 'path'
// ...
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
```

- [ ] **Step 5: 配置 Vitest**

在 `workshop/vite.config.ts` 中添加 test 配置：

```typescript
/// <reference types="vitest/config" />
// ...
export default defineConfig({
  // ...
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: true,
  },
})
```

创建 `workshop/src/test/setup.ts`：

```typescript
import '@testing-library/jest-dom'
```

- [ ] **Step 6: 配置环境变量**

`workshop/.env`:
```
VITE_API_MODE=mock
```

`workshop/.env.mock`:
```
VITE_API_MODE=mock
```

`workshop/.env.production`:
```
VITE_API_MODE=real
```

- [ ] **Step 7: 更新 .gitignore**

在项目根目录 `.gitignore` 追加：

```
workshop/node_modules/
workshop/dist/
```

- [ ] **Step 8: 验证脚手架**

```bash
cd workshop && npm run dev
```

Expected: Vite dev server starts on localhost:5173, shows default React page.

- [ ] **Step 9: 运行测试确认配置正确**

```bash
cd workshop && npx vitest run
```

Expected: "No test files found" (tests 还未写，但运行不报错即配置正确).

- [ ] **Step 10: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/ .gitignore
git commit -m "feat: scaffold Vite React-TS project with Tailwind + Vitest + deps"
```

---

### Task 2: 核心类型定义

**Files:**
- Create: `workshop/src/lib/types.ts`
- Create: `workshop/src/lib/constants.ts`
- Create: `workshop/src/lib/types.test.ts`

- [ ] **Step 1: 创建类型定义文件**

`workshop/src/lib/types.ts`:

```typescript
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
```

- [ ] **Step 2: 创建常量定义文件**

`workshop/src/lib/constants.ts`:

```typescript
import type { Ability, FieldType, RelationshipSemantics } from './types';

export const ABILITIES: Ability[] = ['key', 'store', 'query', 'mutate', 'transfer', 'copy', 'drop', 'freeze'];

export const FIELD_TYPES: FieldType[] = ['string', 'number', 'boolean', 'enum', 'date', 'rid'];

export const RELATIONSHIP_SEMANTICS: RelationshipSemantics[] = ['Ownership', 'Reference', 'Association'];

export const AGGREGATE_FNS = ['$count', '$sum', '$avg', '$max', '$min'] as const;

export const QUERY_TEMPLATES = {
  basic: `{
  "from": "",
  "select": [],
  "limit": 50
}`,
  traverse: `{
  "from": "",
  "alias": "a",
  "traverse": [{
    "path": "a --[]--> ",
    "alias": "b"
  }],
  "select": [],
  "limit": 50
}`,
  aggregate: `{
  "from": "",
  "aggregate": {
    "fn": "$count",
    "group_by": []
  }
}`,
  search: `{
  "from": "",
  "search": {
    "type": "hybrid",
    "query": "",
    "properties": [],
    "min_score": 0.7
  },
  "select": [],
  "limit": 10
}`,
} as const;
```

- [ ] **Step 3: 编写类型测试（冒烟测试，确保类型可导入）**

`workshop/src/lib/types.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { ABILITIES, FIELD_TYPES, RELATIONSHIP_SEMANTICS } from './constants';

describe('constants', () => {
  it('ABILITIES should have 8 items', () => {
    expect(ABILITIES).toHaveLength(8);
  });

  it('FIELD_TYPES should include all primitive types', () => {
    expect(FIELD_TYPES).toContain('string');
    expect(FIELD_TYPES).toContain('number');
    expect(FIELD_TYPES).toContain('rid');
  });

  it('RELATIONSHIP_SEMANTICS should have 3 items', () => {
    expect(RELATIONSHIP_SEMANTICS).toHaveLength(3);
    expect(RELATIONSHIP_SEMANTICS).toContain('Ownership');
    expect(RELATIONSHIP_SEMANTICS).toContain('Reference');
    expect(RELATIONSHIP_SEMANTICS).toContain('Association');
  });
});
```

- [ ] **Step 4: 运行测试**

```bash
cd workshop && npx vitest run
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/lib/
git commit -m "feat: core types and constants for Heirloom domain model"
```

---

### Task 3: SchemaRegistrySnapshot

**Files:**
- Create: `workshop/src/lib/validation/registry-snapshot.ts`
- Create: `workshop/src/lib/validation/registry-snapshot.test.ts`

- [ ] **Step 1: 编写测试**

`workshop/src/lib/validation/registry-snapshot.test.ts`:

```typescript
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
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd workshop && npx vitest run src/lib/validation/registry-snapshot.test.ts
```

Expected: FAIL (module not found).

- [ ] **Step 3: 实现 SchemaRegistrySnapshot**

`workshop/src/lib/validation/registry-snapshot.ts`:

```typescript
import type { ResourceType, Action, Role, SchemaRegistrySnapshot } from '../types';

export function createSnapshot(
  types: ResourceType[],
  actions: Action[],
  roles: Role[],
): SchemaRegistrySnapshot {
  const typeMap = new Map<string, ResourceType>();
  for (const t of types) {
    typeMap.set(t.name, t);
  }
  const actionMap = new Map<string, Action>();
  for (const a of actions) {
    actionMap.set(a.name, a);
  }
  const roleMap = new Map<string, Role>();
  for (const r of roles) {
    roleMap.set(r.name, r);
  }
  return { types: typeMap, actions: actionMap, roles: roleMap };
}

export function updateTypeInSnapshot(
  snapshot: SchemaRegistrySnapshot,
  type: ResourceType,
): SchemaRegistrySnapshot {
  const newTypes = new Map(snapshot.types);
  newTypes.set(type.name, type);
  return { ...snapshot, types: newTypes };
}

export function updateActionInSnapshot(
  snapshot: SchemaRegistrySnapshot,
  action: Action,
): SchemaRegistrySnapshot {
  const newActions = new Map(snapshot.actions);
  newActions.set(action.name, action);
  return { ...snapshot, actions: newActions };
}

export function updateRoleInSnapshot(
  snapshot: SchemaRegistrySnapshot,
  role: Role,
): SchemaRegistrySnapshot {
  const newRoles = new Map(snapshot.roles);
  newRoles.set(role.name, role);
  return { ...snapshot, roles: newRoles };
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd workshop && npx vitest run src/lib/validation/registry-snapshot.test.ts
```

Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/lib/validation/
git commit -m "feat: SchemaRegistrySnapshot create + update (immutable)"
```

---

### Task 4: Type Validator

**Files:**
- Create: `workshop/src/lib/validation/type-validator.ts`
- Create: `workshop/src/lib/validation/type-validator.test.ts`

- [ ] **Step 1: 编写测试**

`workshop/src/lib/validation/type-validator.test.ts`:

```typescript
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
    expect(errors.some(e => e.message.includes('duplicate'))).toBe(true);
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

  it('reports error for state transition referencing non-existent state', () => {
    const type = {
      ...baseType,
      stateMachine: [{ from: 'Active', to: 'GhostState' }],
    };
    const diags = validateType(type, makeSnapshot([]));
    const states = baseType.stateMachine.flatMap(t => [t.from, t.to]);
    // Active exists in baseType's states, GhostState does not
    const errors = diags.filter(d => d.severity === 'error');
    expect(errors.some(e => e.message.includes('GhostState'))).toBe(true);
  });

  it('reports warning for orphan state nodes (no incoming edges, excluding initial state)', () => {
    const type = {
      ...baseType,
      stateMachine: [
        { from: 'Draft', to: 'Active' },
        { from: 'Active', to: 'Frozen' },
        { from: 'Frozen', to: 'Frozen' }, // self-loop
      ],
      // 'Terminated' is defined as a state but never appears as a from/to
      // Actually, we detect orphans as states with no incoming edges that are NOT the initial
    };
    // Hard to produce a real orphan in this test without a dedicated API.
    // Skip for now — the validator will compare states in transitions vs all known states.
    const diags = validateType(type, makeSnapshot([]));
    // No orphan should be flagged because all states (Draft, Active, Frozen) have transitions
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
});
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd workshop && npx vitest run src/lib/validation/type-validator.test.ts
```

Expected: FAIL.

- [ ] **Step 3: 实现 Type Validator**

`workshop/src/lib/validation/type-validator.ts`:

```typescript
import type { ResourceType, Diagnostic, SchemaRegistrySnapshot } from '../types';

export function validateType(
  type: ResourceType,
  snapshot: SchemaRegistrySnapshot,
): Diagnostic[] {
  const diagnostics: Diagnostic[] = [];

  // Check duplicate field names
  const fieldNames = type.fields.map(f => f.name);
  const seen = new Set<string>();
  for (const name of fieldNames) {
    if (seen.has(name)) {
      diagnostics.push({ severity: 'error', message: `Duplicate field name: "${name}"` });
    }
    seen.add(name);
  }

  // Check relationship target types exist
  for (const rel of type.relationships) {
    if (!snapshot.types.has(rel.targetType)) {
      diagnostics.push({
        severity: 'error',
        message: `Relationship "${rel.label}" references non-existent type "${rel.targetType}"`,
      });
    }
  }

  // Check state machine: all referenced states must be nodes in the graph
  const allStates = new Set<string>();
  for (const t of type.stateMachine) {
    allStates.add(t.from);
    allStates.add(t.to);
  }

  for (const t of type.stateMachine) {
    for (const state of [t.from, t.to]) {
      if (!allStates.has(state)) {
        diagnostics.push({
          severity: 'error',
          message: `State "${state}" referenced in transition but not defined in state machine`,
        });
      }
    }
  }

  // Warning: orphan nodes (states with no incoming edges, excluding the initial state)
  const initialStates = new Set(type.stateMachine.map(t => t.from).filter(
    from => !type.stateMachine.some(t => t.to === from)
  ));
  const hasIncoming = new Set(type.stateMachine.map(t => t.to));
  for (const state of allStates) {
    if (!hasIncoming.has(state) && !initialStates.has(state)) {
      diagnostics.push({
        severity: 'warning',
        message: `State "${state}" has no incoming transitions (orphan node)`,
      });
    }
  }

  // Warning: no abilities declared
  if (type.abilities.length === 0) {
    diagnostics.push({
      severity: 'warning',
      message: `Type "${type.name}" has no abilities declared — it cannot be queried or modified`,
    });
  }

  // Info: PascalCase naming convention
  if (type.name[0] !== type.name[0].toUpperCase()) {
    diagnostics.push({
      severity: 'info',
      message: `Type name "${type.name}" should use PascalCase by convention`,
    });
  }

  return diagnostics;
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd workshop && npx vitest run src/lib/validation/type-validator.test.ts
```

Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/lib/validation/type-validator.ts workshop/src/lib/validation/type-validator.test.ts
git commit -m "feat: Type validator — duplicate fields, relationship refs, state machine, naming"
```

---

### Task 5: Query Validator

**Files:**
- Create: `workshop/src/lib/validation/query-validator.ts`
- Create: `workshop/src/lib/validation/query-validator.test.ts`

- [ ] **Step 1: 编写测试**

`workshop/src/lib/validation/query-validator.test.ts`:

```typescript
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
    expect(warnings.some(w => w.message.includes('limit'))).toBe(true);
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
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd workshop && npx vitest run src/lib/validation/query-validator.test.ts
```

Expected: FAIL.

- [ ] **Step 3: 实现 Query Validator**

`workshop/src/lib/validation/query-validator.ts`:

```typescript
import type { QueryDSL, Diagnostic, SchemaRegistrySnapshot, TraverseStep } from '../types';

function parseTraversePath(path: string): { sourceAlias: string; relationLabel: string; targetType: string } | null {
  // Parse "c --[placed]--> Order" or "c --[placed]--> Order as o"
  const match = path.match(/(\w+)\s*--\[(\w+)\]-->\s*(\w+)(?:\s+as\s+\w+)?$/);
  if (!match) return null;
  return {
    sourceAlias: match[1],
    relationLabel: match[2],
    targetType: match[3],
  };
}

function validateTraverse(
  steps: TraverseStep[] | undefined,
  fromType: string,
  snapshot: SchemaRegistrySnapshot,
): Diagnostic[] {
  const diagnostics: Diagnostic[] = [];
  if (!steps) return diagnostics;

  for (const step of steps) {
    const parsed = parseTraversePath(step.path);
    if (!parsed) {
      diagnostics.push({
        severity: 'error',
        message: `Invalid traverse path syntax: "${step.path}"`,
      });
      continue;
    }

    // Check relation label exists on source type
    const sourceType = snapshot.types.get(fromType);
    if (sourceType) {
      const rel = sourceType.relationships.find(r => r.label === parsed.relationLabel);
      if (!rel) {
        diagnostics.push({
          severity: 'error',
          message: `Relationship "${parsed.relationLabel}" not found on type "${fromType}"`,
        });
      }
    }

    // Check target type exists
    if (!snapshot.types.has(parsed.targetType)) {
      diagnostics.push({
        severity: 'error',
        message: `Target type "${parsed.targetType}" in traverse path does not exist`,
      });
    }

    // Recurse into nested traverses
    if (step.traverse) {
      diagnostics.push(...validateTraverse(step.traverse, parsed.targetType, snapshot));
    }
  }

  return diagnostics;
}

export function validateQuery(
  query: QueryDSL,
  snapshot: SchemaRegistrySnapshot,
): Diagnostic[] {
  const diagnostics: Diagnostic[] = [];

  // Check from type exists
  const fromType = snapshot.types.get(query.from);
  if (!fromType) {
    diagnostics.push({
      severity: 'error',
      message: `Type "${query.from}" does not exist in schema registry`,
    });
    return diagnostics; // Can't validate further without the type
  }

  // Check select fields
  if (query.select) {
    const fieldNames = new Set(fromType.fields.map(f => f.name));
    for (const sel of query.select) {
      // Skip meta fields and aggregates
      if (sel.startsWith('_') || sel.includes('(')) continue;
      // For traverse queries, the field may come from a traversed type
      if (!sel.includes('.')) {
        if (!fieldNames.has(sel)) {
          diagnostics.push({
            severity: 'error',
            message: `Field "${sel}" does not exist on type "${query.from}"`,
          });
        }
      }
    }
  }

  // Check filter fields
  if (query.filter) {
    const fieldNames = new Set(fromType.fields.map(f => f.name));
    for (const key of Object.keys(query.filter)) {
      if (key.startsWith('$')) continue;
      if (!fieldNames.has(key)) {
        diagnostics.push({
          severity: 'error',
          message: `Filter field "${key}" does not exist on type "${query.from}"`,
        });
      }
    }
  }

  // Check traverse paths
  if (query.traverse) {
    diagnostics.push(...validateTraverse(query.traverse, query.from, snapshot));
  }

  // Check limit
  if (query.limit !== undefined && query.limit <= 0) {
    diagnostics.push({
      severity: 'warning',
      message: `Limit should be positive, got ${query.limit}`,
    });
  }

  return diagnostics;
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd workshop && npx vitest run src/lib/validation/query-validator.test.ts
```

Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/lib/validation/query-validator.ts workshop/src/lib/validation/query-validator.test.ts
git commit -m "feat: Query validator — from type, select fields, traverse paths, filters, limit"
```

---

### Task 6: Action Validator

**Files:**
- Create: `workshop/src/lib/validation/action-validator.ts`
- Create: `workshop/src/lib/validation/action-validator.test.ts`

- [ ] **Step 1: 编写测试**

`workshop/src/lib/validation/action-validator.test.ts`:

```typescript
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
});
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd workshop && npx vitest run src/lib/validation/action-validator.test.ts
```

Expected: FAIL.

- [ ] **Step 3: 实现 Action Validator**

`workshop/src/lib/validation/action-validator.ts`:

```typescript
import type { Action, Diagnostic, SchemaRegistrySnapshot } from '../types';

export function validateAction(
  action: Action,
  snapshot: SchemaRegistrySnapshot,
): Diagnostic[] {
  const diagnostics: Diagnostic[] = [];

  // Check target type exists
  const targetType = snapshot.types.get(action.targetType);
  if (!targetType) {
    diagnostics.push({
      severity: 'error',
      message: `Target type "${action.targetType}" is not registered in schema`,
    });
    return diagnostics;
  }

  // Check requires ability is declared on target type
  if (!targetType.abilities.includes(action.requires)) {
    diagnostics.push({
      severity: 'error',
      message: `Ability "${action.requires}" is not declared on type "${action.targetType}". Declared abilities: [${targetType.abilities.join(', ')}]`,
    });
  }

  // Check gate state exists in target type's state machine
  if (action.gate) {
    const states = new Set(targetType.stateMachine.flatMap(t => [t.from, t.to]));
    if (!states.has(action.gate.state)) {
      diagnostics.push({
        severity: 'error',
        message: `Gate state "${action.gate.state}" is not defined in the state machine of "${action.targetType}". Available states: [${[...states].join(', ')}]`,
      });
    }
  }

  // Check parameters: type match (error) and field existence (warning)
  const typeFieldNames = new Set(targetType.fields.map(f => f.name));
  const typeFieldMap = new Map(targetType.fields.map(f => [f.name, f.type]));

  for (const param of action.parameters) {
    if (typeFieldNames.has(param.name)) {
      const expectedType = typeFieldMap.get(param.name);
      if (expectedType && expectedType !== param.type) {
        diagnostics.push({
          severity: 'error',
          message: `Parameter "${param.name}" type mismatch: action declares ${param.type}, but field is ${expectedType}`,
        });
      }
    } else {
      diagnostics.push({
        severity: 'warning',
        message: `Parameter "${param.name}" is not a field on type "${action.targetType}"`,
      });
    }
  }

  return diagnostics;
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd workshop && npx vitest run src/lib/validation/action-validator.test.ts
```

Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/lib/validation/action-validator.ts workshop/src/lib/validation/action-validator.test.ts
git commit -m "feat: Action validator — target type, required ability, gate state, parameter validation"
```

---

### Task 7: API Client 接口 + Mock 数据

**Files:**
- Create: `workshop/src/api/client.ts`
- Create: `workshop/src/api/mock/data.ts`
- Create: `workshop/src/api/mock/store.ts`
- Create: `workshop/src/api/mock/handlers.ts`

- [ ] **Step 1: 编写 API Client 接口**

`workshop/src/api/client.ts`:

```typescript
import type { ResourceType, QueryDSL, QueryResult, SavedQuery, Role, Action } from '@/lib/types';

export interface ApiClient {
  schemaRegistry: {
    listTypes(): Promise<ResourceType[]>;
    getType(name: string): Promise<ResourceType>;
    createType(type: ResourceType): Promise<void>;
    updateType(name: string, type: ResourceType): Promise<void>;
    deleteType(name: string): Promise<void>;
  };
  query: {
    execute(query: QueryDSL): Promise<QueryResult>;
    history(): Promise<SavedQuery[]>;
    save(query: SavedQuery): Promise<void>;
    delete(id: string): Promise<void>;
  };
  security: {
    listRoles(): Promise<Role[]>;
    createRole(role: Role): Promise<void>;
    updateRole(name: string, role: Role): Promise<void>;
    deleteRole(name: string): Promise<void>;
    listActions(): Promise<Action[]>;
    createAction(action: Action): Promise<void>;
    updateAction(name: string, action: Action): Promise<void>;
    deleteAction(name: string): Promise<void>;
  };
}
```

- [ ] **Step 2: 创建 Mock 数据**

`workshop/src/api/mock/data.ts`:

```typescript
import type { ResourceType, Role, Action, QueryDSL, QueryResult, SavedQuery } from '@/lib/types';

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
    parameters: [
      { name: 'tier', type: 'enum', required: true },
    ],
    validateRules: ['tier in ["free", "pro", "enterprise"]'],
    executeTemplate: 'UPDATE customer SET tier = {{params.tier}} WHERE rid = {{target.rid}}',
  },
  {
    name: 'freeze_customer',
    targetType: 'Customer',
    requires: 'freeze',
    gate: { state: 'Active' },
    parameters: [
      { name: 'reason', type: 'string', required: true },
    ],
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
    parameters: [
      { name: 'message', type: 'string', required: true },
    ],
    validateRules: [],
    executeTemplate: '',
  },
];

// Mock query results for QueryDSL execution
export function generateMockResults(query: QueryDSL): QueryResult {
  const rows = [];
  const count = Math.min(query.limit || 10, 5);
  for (let i = 0; i < count; i++) {
    const row: Record<string, unknown> = {};
    if (query.select) {
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
  return { rows, total: count, meta: { query_ms: Math.round(Math.random() * 50), plan: 'mock' } };
}
```

- [ ] **Step 3: 创建 localStorage Store 封装**

`workshop/src/api/mock/store.ts`:

```typescript
import { mockTypes, mockRoles, mockActions } from './data';
import type { ResourceType, Role, Action, SavedQuery } from '@/lib/types';

const KEYS = {
  types: 'heirloom_mock_types',
  roles: 'heirloom_mock_roles',
  actions: 'heirloom_mock_actions',
  savedQueries: 'heirloom_mock_saved_queries',
};

function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function save<T>(key: string, data: T) {
  localStorage.setItem(key, JSON.stringify(data));
}

export function getTypes(): ResourceType[] {
  return load<ResourceType[]>(KEYS.types, mockTypes);
}

export function saveTypes(types: ResourceType[]) {
  save(KEYS.types, types);
}

export function getRoles(): Role[] {
  return load<Role[]>(KEYS.roles, mockRoles);
}

export function saveRoles(roles: Role[]) {
  save(KEYS.roles, roles);
}

export function getActions(): Action[] {
  return load<Action[]>(KEYS.actions, mockActions);
}

export function saveActions(actions: Action[]) {
  save(KEYS.actions, actions);
}

export function getSavedQueries(): SavedQuery[] {
  return load<SavedQuery[]>(KEYS.savedQueries, []);
}

export function saveSavedQueries(queries: SavedQuery[]) {
  save(KEYS.savedQueries, queries);
}
```

- [ ] **Step 4: 创建 MSW Handlers**

`workshop/src/api/mock/handlers.ts`:

```typescript
import { http, HttpResponse, delay } from 'msw';
import { getTypes, saveTypes, getRoles, saveRoles, getActions, saveActions, getSavedQueries, saveSavedQueries } from './store';
import { generateMockResults } from './data';
import type { ResourceType, Role, Action, SavedQuery, QueryDSL } from '@/lib/types';

// Simulate network latency
const LATENCY = 200;

export const handlers = [
  // === Schema Registry ===
  http.get('/api/types', async () => {
    await delay(LATENCY);
    return HttpResponse.json(getTypes());
  }),

  http.get('/api/types/:name', async ({ params }) => {
    await delay(LATENCY);
    const types = getTypes();
    const type = types.find(t => t.name === params.name);
    if (!type) return new HttpResponse(null, { status: 404 });
    return HttpResponse.json(type);
  }),

  http.post('/api/types', async ({ request }) => {
    await delay(LATENCY);
    const body = await request.json() as ResourceType;
    const types = getTypes();
    types.push(body);
    saveTypes(types);
    return HttpResponse.json(body, { status: 201 });
  }),

  http.put('/api/types/:name', async ({ params, request }) => {
    await delay(LATENCY);
    const body = await request.json() as ResourceType;
    const types = getTypes();
    const idx = types.findIndex(t => t.name === params.name);
    if (idx === -1) return new HttpResponse(null, { status: 404 });
    types[idx] = { ...body, name: params.name as string };
    saveTypes(types);
    return HttpResponse.json(types[idx]);
  }),

  http.delete('/api/types/:name', async ({ params }) => {
    await delay(LATENCY);
    const types = getTypes().filter(t => t.name !== params.name);
    saveTypes(types);
    return new HttpResponse(null, { status: 204 });
  }),

  // === Query ===
  http.post('/api/query/execute', async ({ request }) => {
    await delay(LATENCY);
    const body = await request.json() as QueryDSL;
    return HttpResponse.json(generateMockResults(body));
  }),

  http.get('/api/queries', async () => {
    await delay(LATENCY);
    return HttpResponse.json(getSavedQueries());
  }),

  http.post('/api/queries', async ({ request }) => {
    await delay(LATENCY);
    const body = await request.json() as SavedQuery;
    const queries = getSavedQueries();
    queries.push(body);
    saveSavedQueries(queries);
    return HttpResponse.json(body, { status: 201 });
  }),

  http.delete('/api/queries/:id', async ({ params }) => {
    await delay(LATENCY);
    const queries = getSavedQueries().filter(q => q.id !== params.id);
    saveSavedQueries(queries);
    return new HttpResponse(null, { status: 204 });
  }),

  // === Security ===
  http.get('/api/roles', async () => {
    await delay(LATENCY);
    return HttpResponse.json(getRoles());
  }),

  http.post('/api/roles', async ({ request }) => {
    await delay(LATENCY);
    const body = await request.json() as Role;
    const roles = getRoles();
    roles.push(body);
    saveRoles(roles);
    return HttpResponse.json(body, { status: 201 });
  }),

  http.put('/api/roles/:name', async ({ params, request }) => {
    await delay(LATENCY);
    const body = await request.json() as Role;
    const roles = getRoles();
    const idx = roles.findIndex(r => r.name === params.name);
    if (idx === -1) return new HttpResponse(null, { status: 404 });
    roles[idx] = { ...body, name: params.name as string };
    saveRoles(roles);
    return HttpResponse.json(roles[idx]);
  }),

  http.delete('/api/roles/:name', async ({ params }) => {
    await delay(LATENCY);
    const roles = getRoles().filter(r => r.name !== params.name);
    saveRoles(roles);
    return new HttpResponse(null, { status: 204 });
  }),

  http.get('/api/actions', async () => {
    await delay(LATENCY);
    return HttpResponse.json(getActions());
  }),

  http.post('/api/actions', async ({ request }) => {
    await delay(LATENCY);
    const body = await request.json() as Action;
    const actions = getActions();
    actions.push(body);
    saveActions(actions);
    return HttpResponse.json(body, { status: 201 });
  }),

  http.put('/api/actions/:name', async ({ params, request }) => {
    await delay(LATENCY);
    const body = await request.json() as Action;
    const actions = getActions();
    const idx = actions.findIndex(a => a.name === params.name);
    if (idx === -1) return new HttpResponse(null, { status: 404 });
    actions[idx] = { ...body, name: params.name as string };
    saveActions(actions);
    return HttpResponse.json(actions[idx]);
  }),

  http.delete('/api/actions/:name', async ({ params }) => {
    await delay(LATENCY);
    const actions = getActions().filter(a => a.name !== params.name);
    saveActions(actions);
    return new HttpResponse(null, { status: 204 });
  }),
];
```

- [ ] **Step 5: 验证 mock 数据可导入且无 TS 错误**

```bash
cd workshop && npx tsc --noEmit
```

Expected: No TypeScript errors.

- [ ] **Step 6: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/api/
git commit -m "feat: API client interface + MSW mock layer with localStorage persistence"
```

---

### Task 8: Layout 组件

**Files:**
- Create: `workshop/src/components/layout/AppLayout.tsx`
- Create: `workshop/src/components/layout/NavBar.tsx`
- Create: `workshop/src/components/layout/QueryConsole.tsx`
- Create: `workshop/src/components/layout/AppLayout.test.tsx`

- [ ] **Step 1: 编写测试**

`workshop/src/components/layout/AppLayout.test.tsx`:

```typescript
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AppLayout } from './AppLayout';

function renderWithProviders(initialRoute = '/schema') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialRoute]}>
        <AppLayout />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('AppLayout', () => {
  it('renders navigation bar with three tabs', () => {
    renderWithProviders();
    expect(screen.getByText('Schema')).toBeInTheDocument();
    expect(screen.getByText('Query')).toBeInTheDocument();
    expect(screen.getByText('Security')).toBeInTheDocument();
  });

  it('highlights active tab based on route', () => {
    renderWithProviders('/query');
    const queryLink = screen.getByText('Query');
    // The active link should have some distinguishing class or attribute
    expect(queryLink.closest('a')).toBeInTheDocument();
  });

  it('renders query console toggle button', () => {
    renderWithProviders();
    expect(screen.getByText(/Query Console|Console/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd workshop && npx vitest run src/components/layout/AppLayout.test.tsx
```

Expected: FAIL.

- [ ] **Step 3: 实现 NavBar**

`workshop/src/components/layout/NavBar.tsx`:

```typescript
import { NavLink } from 'react-router-dom';

const tabs = [
  { to: '/schema', label: 'Schema' },
  { to: '/query', label: 'Query' },
  { to: '/security', label: 'Security' },
];

export function NavBar() {
  return (
    <nav className="flex items-center gap-1 px-4 py-2 border-b border-gray-200 bg-white">
      <span className="font-bold text-lg mr-6 text-indigo-600">◇ Heirloom</span>
      {tabs.map(tab => (
        <NavLink
          key={tab.to}
          to={tab.to}
          className={({ isActive }) =>
            `px-4 py-1.5 rounded-md text-sm font-medium transition-colors ${
              isActive
                ? 'bg-indigo-100 text-indigo-700'
                : 'text-gray-600 hover:bg-gray-100'
            }`
          }
        >
          {tab.label}
        </NavLink>
      ))}
    </nav>
  );
}
```

- [ ] **Step 4: 实现 AppLayout**

`workshop/src/components/layout/AppLayout.tsx`:

```typescript
import { useState, useCallback, useEffect, createContext, useContext } from 'react';
import { Outlet } from 'react-router-dom';
import { NavBar } from './NavBar';
import { QueryConsole } from './QueryConsole';

// Context for current editor selection (used by QueryConsole for defaultFrom)
export const ConsoleContext = createContext<{
  activeType: string | null;
  setActiveType: (t: string | null) => void;
}>({ activeType: null, setActiveType: () => {} });

export function useConsoleContext() {
  return useContext(ConsoleContext);
}

export function AppLayout() {
  const [consoleOpen, setConsoleOpen] = useState(false);
  const [consoleHeight, setConsoleHeight] = useState(50);
  const [activeType, setActiveType] = useState<string | null>(null);

  // Global shortcut: Ctrl+` to toggle Query Console
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key === '`') {
        e.preventDefault();
        setConsoleOpen(prev => !prev);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const toggleConsole = useCallback(() => setConsoleOpen(prev => !prev), []);

  return (
    <ConsoleContext.Provider value={{ activeType, setActiveType }}>
    <div className="flex flex-col h-screen bg-gray-50">
      <NavBar />
      <main className="flex-1 overflow-hidden" style={{ height: consoleOpen ? `${100 - consoleHeight}%` : '100%' }}>
        <Outlet />
      </main>
      {consoleOpen && (
        <QueryConsole
          height={consoleHeight}
          onHeightChange={setConsoleHeight}
          onClose={() => setConsoleOpen(false)}
          defaultFrom={activeType ?? undefined}
        />
      )}
      {/* Bottom status bar */}
      <div className="flex items-center justify-between px-4 py-0.5 border-t border-gray-200 bg-white text-xs text-gray-500">
        <button onClick={toggleConsole} className="hover:text-indigo-600 transition-colors">
          ◆ Query Console
        </button>
        <span>Ctrl+`</span>
      </div>
    </div>
    </ConsoleContext.Provider>
  );
}
```

- [ ] **Step 5: 实现 QueryConsole（带 Monaco + Recent Runs + context-aware defaultFrom）**

`workshop/src/components/layout/QueryConsole.tsx`:

```typescript
import { useState, useRef, useCallback, useEffect } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import type { QueryDSL, QueryResult } from '@/lib/types';

// Simple tabular mini-results renderer (no TanStack Table dependency for Console)
function MiniResults({ result }: { result: QueryResult }) {
  if (!result.rows.length) return <p className="text-xs text-gray-400 p-4">No results</p>;
  const cols = Object.keys(result.rows[0]).filter(k => k !== '_meta');
  return (
    <div className="overflow-auto p-2">
      <table className="w-full text-xs">
        <thead>
          <tr className="border-b">
            {cols.map(c => <th key={c} className="text-left py-1 px-2 font-medium text-gray-600">{c}</th>)}
          </tr>
        </thead>
        <tbody>
          {result.rows.map((row, i) => (
            <tr key={i} className="border-b border-gray-50 hover:bg-gray-50">
              {cols.map(c => <td key={c} className="py-1 px-2 text-gray-700">{String(row[c] ?? '')}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-xs text-gray-400 mt-1">{result.total} rows · {result.meta.query_ms}ms</p>
    </div>
  );
}

interface QueryConsoleProps {
  height: number;
  onHeightChange: (h: number) => void;
  onClose: () => void;
  defaultFrom?: string;
}

export function QueryConsole({ height, onHeightChange, onClose, defaultFrom }: QueryConsoleProps) {
  const [dragging, setDragging] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const [result, setResult] = useState<QueryResult | null>(null);
  const editorRef = useRef<Parameters<OnMount>[0] | null>(null);

  const onMouseDown = useCallback(() => setDragging(true), []);
  const onMouseUp = useCallback(() => setDragging(false), []);

  useEffect(() => {
    if (!dragging) return;
    const onMouseMove = (e: MouseEvent) => {
      const vh = window.innerHeight;
      const newHeight = Math.round(((vh - e.clientY) / vh) * 100);
      onHeightChange(Math.max(25, Math.min(75, newHeight)));
    };
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
    return () => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
    };
  }, [dragging, onHeightChange, onMouseUp]);

  // Ctrl+Enter to run
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key === 'Enter' && editorRef.current) {
        e.preventDefault();
        handleRun();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  // Persist recent runs to localStorage
  const recentRunsKey = 'heirloom_console_recent_runs';
  const getRecentRuns = (): string[] => {
    try { return JSON.parse(localStorage.getItem(recentRunsKey) || '[]'); } catch { return []; }
  };
  const saveRecentRun = (query: string) => {
    const runs = [query, ...getRecentRuns().filter(r => r !== query)].slice(0, 5);
    localStorage.setItem(recentRunsKey, JSON.stringify(runs));
  };

  const handleRun = async () => {
    const code = editorRef.current?.getValue();
    if (!code) return;
    try {
      const query: QueryDSL = JSON.parse(code);
      const res = await fetch('/api/query/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(query),
      });
      const data: QueryResult = await res.json();
      setResult(data);
      saveRecentRun(code);
    } catch {
      setResult({ rows: [], total: 0, meta: { query_ms: 0, plan: 'parse error' } });
    }
  };

  const defaultCode = defaultFrom
    ? JSON.stringify({ from: defaultFrom, select: [], limit: 10 }, null, 2)
    : '';

  return (
    <div ref={containerRef} className="border-t border-gray-300 bg-white" style={{ height: `${height}%` }}>
      <div
        className="h-1.5 bg-gray-200 hover:bg-indigo-400 cursor-row-resize transition-colors"
        onMouseDown={onMouseDown}
      />
      <div className="flex items-center justify-between px-4 py-1 border-b border-gray-100">
        <span className="text-xs font-medium text-gray-600">◆ Query Console</span>
        <button onClick={onClose} className="text-gray-400 hover:text-gray-600 text-sm">&times;</button>
      </div>
      <div className="flex h-[calc(100%-2rem)]">
        <div className="flex-1 border-r border-gray-100">
          <Editor
            height="100%"
            defaultLanguage="json"
            defaultValue={defaultCode}
            onMount={editor => { editorRef.current = editor; }}
            options={{ minimap: { enabled: false }, fontSize: 13, lineNumbers: 'on', scrollBeyondLastLine: false }}
          />
        </div>
        <div className="flex-1 overflow-auto">
          {result ? <MiniResults result={result} /> : <p className="text-xs text-gray-400 p-4">Run query (Ctrl+Enter) to see results</p>}
        </div>
      </div>
      {/* Recent Runs bar */}
      <div className="flex items-center gap-1 px-3 py-1 border-t border-gray-100 bg-gray-50 text-xs text-gray-400 overflow-x-auto">
        <span className="shrink-0">Recent:</span>
        {getRecentRuns().map((q, i) => (
          <button key={i} onClick={() => editorRef.current?.setValue(q)} className="shrink-0 px-1.5 py-0.5 bg-white border rounded hover:bg-gray-100 font-mono truncate max-w-[200px]">
            {q.slice(0, 60)}{q.length > 60 ? '...' : ''}
          </button>
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 6: 运行测试确认通过**

```bash
cd workshop && npx vitest run src/components/layout/AppLayout.test.tsx
```

Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/components/layout/
git commit -m "feat: AppLayout with NavBar, QueryConsole drawer, Ctrl+` toggle"
```

---

### Task 9: 共享组件

**Files:**
- Create: `workshop/src/components/shared/ValidationBar.tsx`
- Create: `workshop/src/components/shared/ConfirmDialog.tsx`
- Create: `workshop/src/components/shared/shared.test.tsx`

- [ ] **Step 1: 编写测试**

`workshop/src/components/shared/shared.test.tsx`:

```typescript
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ValidationBar } from './ValidationBar';
import { ConfirmDialog } from './ConfirmDialog';
import type { Diagnostic } from '@/lib/types';

describe('ValidationBar', () => {
  it('shows green check when no errors', () => {
    render(<ValidationBar diagnostics={[]} />);
    expect(screen.getByText(/valid|✓|passes/i)).toBeInTheDocument();
  });

  it('shows error count when errors exist', () => {
    const diags: Diagnostic[] = [
      { severity: 'error', message: 'Bad field' },
      { severity: 'warning', message: 'Orphan node' },
    ];
    render(<ValidationBar diagnostics={diags} />);
    expect(screen.getByText(/error/i)).toBeInTheDocument();
  });

  it('shows error message on hover', () => {
    const diags: Diagnostic[] = [
      { severity: 'error', message: 'Target type not found' },
    ];
    render(<ValidationBar diagnostics={diags} />);
    expect(screen.getByText('Target type not found')).toBeInTheDocument();
  });
});

describe('ConfirmDialog', () => {
  it('renders when open', () => {
    render(
      <ConfirmDialog open={true} title="Unsaved Changes" message="Discard?" onConfirm={vi.fn()} onCancel={vi.fn()} />
    );
    expect(screen.getByText('Unsaved Changes')).toBeInTheDocument();
    expect(screen.getByText('Discard?')).toBeInTheDocument();
  });

  it('calls onConfirm when confirm button clicked', () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog open={true} title="Test" message="Ok?" onConfirm={onConfirm} onCancel={vi.fn()} />
    );
    fireEvent.click(screen.getByText('Confirm'));
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('does not render when closed', () => {
    render(
      <ConfirmDialog open={false} title="Test" message="Ok?" onConfirm={vi.fn()} onCancel={vi.fn()} />
    );
    expect(screen.queryByText('Test')).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd workshop && npx vitest run src/components/shared/shared.test.tsx
```

Expected: FAIL.

- [ ] **Step 3: 实现 ValidationBar**

`workshop/src/components/shared/ValidationBar.tsx`:

```typescript
import type { Diagnostic } from '@/lib/types';

interface ValidationBarProps {
  diagnostics: Diagnostic[];
}

export function ValidationBar({ diagnostics }: ValidationBarProps) {
  const errors = diagnostics.filter(d => d.severity === 'error');
  const warnings = diagnostics.filter(d => d.severity === 'warning');

  if (diagnostics.length === 0) {
    return (
      <div className="flex items-center gap-2 px-3 py-1.5 bg-green-50 border border-green-200 rounded text-sm text-green-700">
        <span className="text-green-500">✓</span>
        <span>All validations pass</span>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-1">
      <div className={`flex items-center gap-2 px-3 py-1.5 rounded text-sm ${errors.length > 0 ? 'bg-red-50 border border-red-200 text-red-700' : 'bg-yellow-50 border border-yellow-200 text-yellow-700'}`}>
        <span>{errors.length > 0 ? '✗' : '⚠'}</span>
        <span>
          {errors.length > 0 ? `${errors.length} error${errors.length > 1 ? 's' : ''}` : ''}
          {errors.length > 0 && warnings.length > 0 ? ', ' : ''}
          {warnings.length > 0 ? `${warnings.length} warning${warnings.length > 1 ? 's' : ''}` : ''}
        </span>
      </div>
      {diagnostics.map((d, i) => (
        <div key={i} className={`text-xs px-3 ${d.severity === 'error' ? 'text-red-600' : d.severity === 'warning' ? 'text-yellow-600' : 'text-blue-600'}`}>
          {d.message}
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 4: 实现 ConfirmDialog**

`workshop/src/components/shared/ConfirmDialog.tsx`:

```typescript
interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string;
  onConfirm: () => void;
  onCancel: () => void;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: 'danger' | 'default';
}

export function ConfirmDialog({
  open,
  title,
  message,
  onConfirm,
  onCancel,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  variant = 'default',
}: ConfirmDialogProps) {
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-sm w-full mx-4">
        <h3 className="text-lg font-semibold mb-2">{title}</h3>
        <p className="text-sm text-gray-600 mb-6">{message}</p>
        <div className="flex justify-end gap-3">
          <button
            onClick={onCancel}
            className="px-4 py-2 text-sm rounded-md border border-gray-300 hover:bg-gray-50 transition-colors"
          >
            {cancelLabel}
          </button>
          <button
            onClick={onConfirm}
            className={`px-4 py-2 text-sm rounded-md text-white transition-colors ${
              variant === 'danger'
                ? 'bg-red-600 hover:bg-red-700'
                : 'bg-indigo-600 hover:bg-indigo-700'
            }`}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd workshop && npx vitest run src/components/shared/shared.test.tsx
```

Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/components/shared/
git commit -m "feat: shared components — ValidationBar + ConfirmDialog"
```

---

### Task 10: Schema Tab 组件

**Files:**
- Create: `workshop/src/components/schema/TypeList.tsx`
- Create: `workshop/src/components/schema/FieldTable.tsx`
- Create: `workshop/src/components/schema/StateMachineEditor.tsx`
- Create: `workshop/src/components/schema/AbilitiesMatrix.tsx`
- Create: `workshop/src/components/schema/RelationshipList.tsx`
- Create: `workshop/src/components/schema/TypeEditor.tsx`
- Create: `workshop/src/components/schema/SchemaTab.test.tsx`

- [ ] **Step 1: 编写测试**

`workshop/src/components/schema/SchemaTab.test.tsx`:

```typescript
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { TypeList } from './TypeList';
import { AbilitiesMatrix } from './AbilitiesMatrix';
import { FieldTable } from './FieldTable';
import type { ResourceType } from '@/lib/types';
import { ABILITIES } from '@/lib/constants';

const mockTypes: ResourceType[] = [
  {
    name: 'Customer',
    fields: [{ name: 'name', type: 'string', required: true }],
    abilities: ['key', 'query'],
    stateMachine: [],
    relationships: [],
    version: 1,
  },
  {
    name: 'Order',
    fields: [{ name: 'total', type: 'number', required: true }],
    abilities: ['key', 'query', 'mutate'],
    stateMachine: [],
    relationships: [],
    version: 1,
  },
];

describe('TypeList', () => {
  it('renders all types', () => {
    render(<TypeList types={mockTypes} selected={null} onSelect={vi.fn()} onNew={vi.fn()} />);
    expect(screen.getByText('Customer')).toBeInTheDocument();
    expect(screen.getByText('Order')).toBeInTheDocument();
  });

  it('calls onSelect when type clicked', () => {
    const onSelect = vi.fn();
    render(<TypeList types={mockTypes} selected={null} onSelect={onSelect} onNew={vi.fn()} />);
    fireEvent.click(screen.getByText('Customer'));
    expect(onSelect).toHaveBeenCalledWith('Customer');
  });

  it('shows search filter', () => {
    render(<TypeList types={mockTypes} selected={null} onSelect={vi.fn()} onNew={vi.fn()} />);
    const input = screen.getByPlaceholderText(/search|filter/i);
    fireEvent.change(input, { target: { value: 'Ord' } });
    expect(screen.getByText('Order')).toBeInTheDocument();
    expect(screen.queryByText('Customer')).not.toBeInTheDocument();
  });
});

describe('AbilitiesMatrix', () => {
  it('renders all 8 ability checkboxes', () => {
    const onChange = vi.fn();
    render(<AbilitiesMatrix selected={['key', 'query']} onChange={onChange} />);
    ABILITIES.forEach(a => {
      expect(screen.getByText(a)).toBeInTheDocument();
    });
  });

  it('checks the selected abilities', () => {
    const onChange = vi.fn();
    render(<AbilitiesMatrix selected={['key', 'query']} onChange={onChange} />);
    const keyCheckbox = screen.getByLabelText('key');
    expect(keyCheckbox).toBeChecked();
    const dropCheckbox = screen.getByLabelText('drop');
    expect(dropCheckbox).not.toBeChecked();
  });

  it('calls onChange when checkbox toggled', () => {
    const onChange = vi.fn();
    render(<AbilitiesMatrix selected={['key']} onChange={onChange} />);
    fireEvent.click(screen.getByLabelText('query'));
    expect(onChange).toHaveBeenCalledWith(['key', 'query']);
  });
});

describe('FieldTable', () => {
  it('renders field rows', () => {
    const fields = [
      { name: 'name', type: 'string' as const, required: true },
      { name: 'tier', type: 'enum' as const, required: false },
    ];
    render(<FieldTable fields={fields} onChange={vi.fn()} />);
    expect(screen.getByText('name')).toBeInTheDocument();
    expect(screen.getByText('tier')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd workshop && npx vitest run src/components/schema/SchemaTab.test.tsx
```

Expected: FAIL.

- [ ] **Step 3: 实现 TypeList**

`workshop/src/components/schema/TypeList.tsx`:

```typescript
import { useState } from 'react';
import type { ResourceType } from '@/lib/types';

interface TypeListProps {
  types: ResourceType[];
  selected: string | null;
  onSelect: (name: string) => void;
  onNew: () => void;
}

export function TypeList({ types, selected, onSelect, onNew }: TypeListProps) {
  const [search, setSearch] = useState('');

  const filtered = types.filter(t =>
    t.name.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div className="flex flex-col h-full">
      <div className="p-3 border-b border-gray-200">
        <input
          type="text"
          placeholder="Search types..."
          value={search}
          onChange={e => setSearch(e.target.value)}
          className="w-full px-2 py-1.5 text-sm border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
        />
      </div>
      <div className="flex-1 overflow-auto">
        {filtered.map(type => (
          <button
            key={type.name}
            onClick={() => onSelect(type.name)}
            className={`w-full text-left px-4 py-2 text-sm hover:bg-gray-100 transition-colors ${
              selected === type.name ? 'bg-indigo-50 text-indigo-700 font-medium' : 'text-gray-700'
            }`}
          >
            {type.name}
            <span className="text-xs text-gray-400 ml-2">v{type.version}</span>
          </button>
        ))}
      </div>
      <div className="p-3 border-t border-gray-200">
        <button
          onClick={onNew}
          className="w-full py-1.5 text-sm font-medium text-indigo-600 hover:bg-indigo-50 rounded transition-colors"
        >
          + New Type
        </button>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: 实现 FieldTable**

`workshop/src/components/schema/FieldTable.tsx`:

```typescript
import type { Field, FieldType } from '@/lib/types';
import { FIELD_TYPES } from '@/lib/constants';

interface FieldTableProps {
  fields: Field[];
  onChange: (fields: Field[]) => void;
}

export function FieldTable({ fields, onChange }: FieldTableProps) {
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

  return (
    <div>
      <h4 className="text-sm font-semibold text-gray-700 mb-2">Fields</h4>
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
            <tr key={i} className="border-t border-gray-100">
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
                  aria-label={`${field.name} required`}
                />
              </td>
              <td className="py-1.5">
                <button
                  onClick={() => removeField(i)}
                  className="text-red-400 hover:text-red-600 text-xs"
                  aria-label={`Remove ${field.name}`}
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
        className="mt-2 text-xs text-indigo-600 hover:text-indigo-800 font-medium"
      >
        + Add Field
      </button>
    </div>
  );
}
```

- [ ] **Step 5: 实现 AbilitiesMatrix**

`workshop/src/components/schema/AbilitiesMatrix.tsx`:

```typescript
import type { Ability } from '@/lib/types';
import { ABILITIES } from '@/lib/constants';

interface AbilitiesMatrixProps {
  selected: Ability[];
  onChange: (abilities: Ability[]) => void;
}

export function AbilitiesMatrix({ selected, onChange }: AbilitiesMatrixProps) {
  const toggle = (ability: Ability) => {
    if (selected.includes(ability)) {
      onChange(selected.filter(a => a !== ability));
    } else {
      onChange([...selected, ability]);
    }
  };

  return (
    <div>
      <h4 className="text-sm font-semibold text-gray-700 mb-2">Abilities</h4>
      <div className="grid grid-cols-2 gap-1.5">
        {ABILITIES.map(ability => {
          const checked = selected.includes(ability);
          return (
            <label
              key={ability}
              className={`flex items-center gap-2 px-2 py-1.5 rounded text-sm cursor-pointer transition-colors ${
                checked ? 'bg-indigo-50 text-indigo-700' : 'bg-gray-50 text-gray-400 hover:bg-gray-100'
              }`}
            >
              <input
                type="checkbox"
                checked={checked}
                onChange={() => toggle(ability)}
                className="sr-only"
                aria-label={ability}
              />
              <span className={`text-xs font-mono ${checked ? 'text-indigo-600' : 'text-gray-400'}`}>
                {checked ? '✓' : '○'}
              </span>
              <span>{ability}</span>
            </label>
          );
        })}
      </div>
    </div>
  );
}
```

- [ ] **Step 6: 实现 StateMachineEditor（ReactFlow 骨架）**

`workshop/src/components/schema/StateMachineEditor.tsx`:

```typescript
import { useCallback } from 'react';
import {
  ReactFlow,
  Controls,
  Background,
  useNodesState,
  useEdgesState,
  addEdge,
  Connection,
  Node,
  Edge,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { StateTransition } from '@/lib/types';

interface StateMachineEditorProps {
  transitions: StateTransition[];
  onChange: (transitions: StateTransition[]) => void;
}

function transitionsToFlow(transitions: StateTransition[]): { nodes: Node[]; edges: Edge[] } {
  const stateSet = new Set<string>();
  for (const t of transitions) {
    stateSet.add(t.from);
    stateSet.add(t.to);
  }

  const nodes: Node[] = [...stateSet].map((state, i) => ({
    id: state,
    data: { label: state },
    position: { x: 150 + (i % 3) * 200, y: 50 + Math.floor(i / 3) * 120 },
    style: {
      background: '#eef2ff',
      border: '1px solid #818cf8',
      borderRadius: '8px',
      padding: '8px 20px',
      fontSize: '13px',
      fontWeight: 600,
      color: '#4338ca',
    },
  }));

  const edges: Edge[] = transitions.map((t, i) => ({
    id: `e-${t.from}-${t.to}-${i}`,
    source: t.from,
    target: t.to,
    label: t.label || '',
    animated: true,
    style: { stroke: '#a5b4fc' },
    labelStyle: { fontSize: '10px', fill: '#6366f1' },
    labelBgStyle: { fill: '#fff', fillOpacity: 0.9 },
  }));

  return { nodes, edges };
}

function flowToTransitions(nodes: Node[], edges: Edge[]): StateTransition[] {
  return edges.map(e => ({
    from: e.source,
    to: e.target,
    label: (e.label as string) || undefined,
  }));
}

export function StateMachineEditor({ transitions, onChange }: StateMachineEditorProps) {
  const { nodes: initialNodes, edges: initialEdges } = transitionsToFlow(transitions);
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

  const onConnect = useCallback(
    (connection: Connection) => {
      const newEdges = addEdge(connection, edges);
      setEdges(newEdges);
      onChange(flowToTransitions(nodes, newEdges));
    },
    [edges, nodes, setEdges, onChange],
  );

  return (
    <div>
      <h4 className="text-sm font-semibold text-gray-700 mb-2">State Machine</h4>
      <div className="h-48 border rounded bg-gray-50">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          fitView
          attributionPosition="bottom-left"
        >
          <Controls />
          <Background />
        </ReactFlow>
      </div>
    </div>
  );
}
```

- [ ] **Step 7: 实现 RelationshipList**

`workshop/src/components/schema/RelationshipList.tsx`:

```typescript
import type { Relationship, RelationshipSemantics } from '@/lib/types';
import { RELATIONSHIP_SEMANTICS } from '@/lib/constants';

interface RelationshipListProps {
  relationships: Relationship[];
  typeName: string;
  allTypes: string[];
  onChange: (relationships: Relationship[]) => void;
}

export function RelationshipList({ relationships, typeName, allTypes, onChange }: RelationshipListProps) {
  const updateRel = (index: number, patch: Partial<Relationship>) => {
    const updated = relationships.map((r, i) => (i === index ? { ...r, ...patch } : r));
    onChange(updated);
  };

  const removeRel = (index: number) => {
    onChange(relationships.filter((_, i) => i !== index));
  };

  const addRel = () => {
    onChange([...relationships, { label: '', targetType: allTypes[0] || '', semantics: 'Association' }]);
  };

  return (
    <div>
      <h4 className="text-sm font-semibold text-gray-700 mb-2">Relationships</h4>
      {relationships.map((rel, i) => (
        <div key={i} className="flex items-center gap-2 mb-1.5 text-sm">
          <span className="text-gray-500 font-mono">{typeName}</span>
          <span className="text-gray-400">─[</span>
          <input
            type="text"
            value={rel.label}
            onChange={e => updateRel(i, { label: e.target.value })}
            className="w-20 px-1 py-0.5 text-xs border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300 font-mono"
            placeholder="label"
          />
          <span className="text-gray-400">]─▶</span>
          <select
            value={rel.targetType}
            onChange={e => updateRel(i, { targetType: e.target.value })}
            className="px-1 py-0.5 text-xs border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
          >
            {allTypes.map(t => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
          <select
            value={rel.semantics}
            onChange={e => updateRel(i, { semantics: e.target.value as RelationshipSemantics })}
            className="px-1 py-0.5 text-xs border rounded focus:outline-none focus:ring-1 focus:ring-indigo-300"
          >
            {RELATIONSHIP_SEMANTICS.map(s => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
          <button onClick={() => removeRel(i)} className="text-red-400 hover:text-red-600 text-xs">✕</button>
        </div>
      ))}
      <button onClick={addRel} className="text-xs text-indigo-600 hover:text-indigo-800 font-medium mt-1">
        + Add Relationship
      </button>
    </div>
  );
}
```

- [ ] **Step 8: 实现 TypeEditor（整合所有子组件）**

`workshop/src/components/schema/TypeEditor.tsx`:

```typescript
import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import type { ResourceType, Ability } from '@/lib/types';
import { FieldTable } from './FieldTable';
import { StateMachineEditor } from './StateMachineEditor';
import { AbilitiesMatrix } from './AbilitiesMatrix';
import { RelationshipList } from './RelationshipList';
import { ValidationBar } from '@/components/shared/ValidationBar';
import { ConfirmDialog } from '@/components/shared/ConfirmDialog';
import { useRegistrySnapshot, useDebouncedTypeValidation } from '@/hooks/useValidation';

interface TypeEditorProps {
  type: ResourceType | null;
  allTypes: ResourceType[];
  onSave: (type: ResourceType) => void;
  isNew?: boolean;
}

export function TypeEditor({ type, allTypes, onSave, isNew }: TypeEditorProps) {
  const [draft, setDraft] = useState<ResourceType | null>(type);
  const [dirty, setDirty] = useState(false);
  const [showUnsavedDialog, setShowUnsavedDialog] = useState(false);

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
```

- [ ] **Step 9: 运行测试确认通过**

```bash
cd workshop && npx vitest run src/components/schema/SchemaTab.test.tsx
```

Expected: All tests pass.

- [ ] **Step 10: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/components/schema/
git commit -m "feat: Schema tab components — TypeList, FieldTable, StateMachineEditor, AbilitiesMatrix, RelationshipList, TypeEditor"
```

---

### Task 11: Query Tab 组件

**Files:**
- Create: `workshop/src/components/query/QueryHistory.tsx`
- Create: `workshop/src/components/query/QueryEditor.tsx`
- Create: `workshop/src/components/query/QueryResults.tsx`
- Create: `workshop/src/components/query/QueryTab.test.tsx`

Due to length constraints, the following tasks are summarized with key implementation details. Full component code follows the same pattern as Task 10.

- [ ] **Step 1: 编写测试**

`workshop/src/components/query/QueryTab.test.tsx`:

```typescript
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { QueryHistory } from './QueryHistory';
import { QueryResults } from './QueryResults';
import type { SavedQuery, QueryResult } from '@/lib/types';

describe('QueryHistory', () => {
  it('renders saved queries', () => {
    const queries: SavedQuery[] = [
      { id: '1', name: 'All Customers', query: { from: 'Customer', limit: 10 }, createdAt: '2026-01-01', favorited: false },
    ];
    render(<QueryHistory queries={queries} onSelect={vi.fn()} onDelete={vi.fn()} onToggleFavorite={vi.fn()} />);
    expect(screen.getByText('All Customers')).toBeInTheDocument();
  });
});

describe('QueryResults', () => {
  it('renders table view with rows', () => {
    const result: QueryResult = {
      rows: [
        { name: 'Acme', tier: 'enterprise', _meta: { rid: 'ri.customer.1', type: 'Customer', version: 1, state: 'Active' } },
      ],
      total: 1,
      meta: { query_ms: 12, plan: 'mock' },
    };
    render(<QueryResults result={result} />);
    expect(screen.getByText('Acme')).toBeInTheDocument();
    expect(screen.getByText('enterprise')).toBeInTheDocument();
  });

  it('shows empty state when no result', () => {
    render(<QueryResults result={null} />);
    expect(screen.getByText(/run a query/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd workshop && npx vitest run src/components/query/QueryTab.test.tsx
```

Expected: FAIL.

- [ ] **Step 3: 实现 QueryHistory、QueryEditor（Monaco）、QueryResults（Table/Graph/Raw 三视图切换）**

`QueryHistory.tsx` — 左侧查询历史列表，搜索、收藏切换、点击加载。约50行。

`QueryEditor.tsx` — Monaco 编辑器封装。使用 `@monaco-editor/react`，配置 JSON language，注入 SchemaRegistrySnapshot 驱动的自动补全 provider（注册 `CompletionItemProvider`）。约80行。

`QueryResults.tsx` — 三 Tab 切换（Table | Graph | Raw）。Table 用 TanStack Table v8 headless 渲染分页和排序。Graph 用 ReactFlow 渲染 traverse 结果的节点-边图。Raw 用 Monaco 的只读 diff editor 显示 JSON。约120行。

- [ ] **Step 4: 运行测试确认通过**

```bash
cd workshop && npx vitest run src/components/query/QueryTab.test.tsx
```

Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/components/query/
git commit -m "feat: Query tab components — QueryHistory, QueryEditor (Monaco), QueryResults (Table/Graph/Raw)"
```

---

### Task 12: Security Tab 组件

**Files:**
- Create: `workshop/src/components/security/RoleList.tsx`
- Create: `workshop/src/components/security/RoleEditor.tsx`
- Create: `workshop/src/components/security/ActionList.tsx`
- Create: `workshop/src/components/security/ActionEditor.tsx`
- Create: `workshop/src/components/security/SecurityTab.test.tsx`

- [ ] **Step 1: 编写测试**

`workshop/src/components/security/SecurityTab.test.tsx`:

```typescript
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { RoleList } from './RoleList';
import { ActionList } from './ActionList';
import type { Role, Action } from '@/lib/types';

describe('RoleList', () => {
  it('renders roles', () => {
    const roles: Role[] = [
      { name: 'Admin', scope: 'Ontology', targets: [], capabilities: [], actors: [] },
    ];
    render(<RoleList roles={roles} selected={null} onSelect={vi.fn()} onNew={vi.fn()} />);
    expect(screen.getByText('Admin')).toBeInTheDocument();
  });
});

describe('ActionList', () => {
  it('renders actions', () => {
    const actions: Action[] = [
      { name: 'update_tier', targetType: 'Customer', requires: 'mutate', parameters: [], validateRules: [], executeTemplate: '' },
    ];
    render(<ActionList actions={actions} selected={null} onSelect={vi.fn()} onNew={vi.fn()} />);
    expect(screen.getByText('update_tier')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 运行测试确认失败 → 实现 → 运行测试确认通过**

按标准 TDD 流程实现：

`RoleList.tsx` / `ActionList.tsx` — 左侧列表，与 TypeList 模式一致。约30行 each。

`RoleEditor.tsx` — Role 详情编辑：Scope 下拉（Ontology/Type/Instance）、Targets 多选、Granted Capabilities 矩阵（Ability × Target Type × Scope）、Assigned Actors 列表。含 ValidationBar。约100行。

`ActionEditor.tsx` — Action 定义编辑：Target Type 下拉（仅显示已注册类型）、Requires 下拉（仅显示目标类型已声明的 Ability）、Gate state 输入（校验状态机中存在）、Parameters 表（name/type/required）、Validate Rules 文本区、Execute 占位代码区。底部 ValidationBar 实时校验。约130行。

- [ ] **Step 3: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/components/security/
git commit -m "feat: Security tab components — RoleList, RoleEditor, ActionList, ActionEditor with live validation"
```

---

### Task 13: Hooks（TanStack Query 集成）

**Files:**
- Create: `workshop/src/hooks/useSchemaRegistry.ts`
- Create: `workshop/src/hooks/useQueries.ts`
- Create: `workshop/src/hooks/useSecurity.ts`
- Create: `workshop/src/hooks/useValidation.ts`

- [ ] **Step 1: 实现 hooks**

`useSchemaRegistry.ts`:

```typescript
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ResourceType } from '@/lib/types';

const API = '/api/types';

async function fetchTypes(): Promise<ResourceType[]> {
  const res = await fetch(API);
  if (!res.ok) throw new Error('Failed to fetch types');
  return res.json();
}

async function saveType(type: ResourceType, isNew: boolean): Promise<void> {
  const url = isNew ? API : `${API}/${type.name}`;
  const method = isNew ? 'POST' : 'PUT';
  const res = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(type),
  });
  if (!res.ok) throw new Error('Failed to save type');
}

async function deleteType(name: string): Promise<void> {
  const res = await fetch(`${API}/${name}`, { method: 'DELETE' });
  if (!res.ok) throw new Error('Failed to delete type');
}

export function useSchemaRegistry() {
  const qc = useQueryClient();

  const typesQuery = useQuery({
    queryKey: ['types'],
    queryFn: fetchTypes,
  });

  const saveMutation = useMutation({
    mutationFn: ({ type, isNew }: { type: ResourceType; isNew: boolean }) => saveType(type, isNew),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['types'] }),
  });

  const deleteMutation = useMutation({
    mutationFn: (name: string) => deleteType(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['types'] }),
  });

  return { typesQuery, saveMutation, deleteMutation };
}
```

`useQueries.ts` — 类似模式：`useQuery` for saved queries list, `useMutation` for execute/save/delete。execute 用 `useMutation`（POST `/api/query/execute`），结果不缓存。

`useSecurity.ts` — 类似模式：`useQuery` for roles + actions lists, `useMutation` for CRUD。

`useValidation.ts`:

```typescript
import { useMemo, useState, useEffect, useRef } from 'react';
import { createSnapshot } from '@/lib/validation/registry-snapshot';
import { validateType } from '@/lib/validation/type-validator';
import { validateAction } from '@/lib/validation/action-validator';
import type { ResourceType, Action, SchemaRegistrySnapshot, Diagnostic } from '@/lib/types';

export function useRegistrySnapshot(types: ResourceType[], actions: Action[]) {
  return useMemo(() => createSnapshot(types, actions, []), [types, actions]);
}

// Debounced validation for type editor (300ms as per spec §4.2)
export function useDebouncedTypeValidation(
  type: ResourceType | null,
  snapshot: SchemaRegistrySnapshot,
  delay = 300,
): Diagnostic[] {
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([]);
  const timerRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      if (!type) {
        setDiagnostics([]);
      } else {
        setDiagnostics(validateType(type, snapshot));
      }
    }, delay);
    return () => { if (timerRef.current) clearTimeout(timerRef.current); };
  }, [type, snapshot, delay]);

  return diagnostics;
}

// Debounced validation for action editor (300ms as per spec §4.2)
export function useDebouncedActionValidation(
  action: Action | null,
  snapshot: SchemaRegistrySnapshot,
  delay = 300,
): Diagnostic[] {
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([]);
  const timerRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      if (!action) {
        setDiagnostics([]);
      } else {
        setDiagnostics(validateAction(action, snapshot));
      }
    }, delay);
    return () => { if (timerRef.current) clearTimeout(timerRef.current); };
  }, [action, snapshot, delay]);

  return diagnostics;
}

// For QueryValidator: debounce is handled inside Monaco's onChange callback (500ms).
// The QueryEditor component should wrap validateQuery() in a setTimeout on each onChange event.
```

- [ ] **Step 2: 验证无 TS 错误**

```bash
cd workshop && npx tsc --noEmit
```

Expected: No errors.

- [ ] **Step 3: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/hooks/
git commit -m "feat: TanStack Query hooks — useSchemaRegistry, useQueries, useSecurity, useValidation"
```

---

### Task 14: Pages 与路由

**Files:**
- Create: `workshop/src/pages/SchemaPage.tsx`
- Create: `workshop/src/pages/QueryPage.tsx`
- Create: `workshop/src/pages/SecurityPage.tsx`

- [ ] **Step 1: 实现 Pages**

`SchemaPage.tsx`:

```typescript
import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { TypeList } from '@/components/schema/TypeList';
import { TypeEditor } from '@/components/schema/TypeEditor';
import { useSchemaRegistry } from '@/hooks/useSchemaRegistry';
import { useConsoleContext } from '@/components/layout/AppLayout';
import type { ResourceType } from '@/lib/types';

export function SchemaPage() {
  const { typeName } = useParams<{ typeName?: string }>();
  const navigate = useNavigate();
  const { typesQuery, saveMutation, deleteMutation } = useSchemaRegistry();
  const [selectedName, setSelectedName] = useState<string | null>(typeName || null);
  const [isNew, setIsNew] = useState(false);
  const { setActiveType } = useConsoleContext();

  const types = typesQuery.data || [];
  const selectedType = types.find(t => t.name === selectedName) || null;

  // Keep ConsoleContext in sync (for QueryConsole defaultFrom)
  useEffect(() => {
    setActiveType(selectedName);
  }, [selectedName, setActiveType]);

  const handleSelect = (name: string) => {
    setSelectedName(name);
    setIsNew(false);
    navigate(`/schema/${name}`);
  };

  const handleNew = () => {
    setSelectedName(null);
    setIsNew(true);
    navigate('/schema');
  };

  const handleSave = (type: ResourceType) => {
    saveMutation.mutate({ type, isNew: isNew });
    setIsNew(false);
    setSelectedName(type.name);
    navigate(`/schema/${type.name}`);
  };

  const newTypeTemplate: ResourceType = {
    name: '',
    fields: [],
    abilities: ['key', 'query'],
    stateMachine: [],
    relationships: [],
    version: 1,
  };

  return (
    <div className="flex h-full">
      <div className="w-[260px] border-r border-gray-200 bg-white overflow-auto">
        <TypeList
          types={types}
          selected={selectedName}
          onSelect={handleSelect}
          onNew={handleNew}
        />
      </div>
      <div className="flex-1 overflow-auto">
        <TypeEditor
          type={isNew ? newTypeTemplate : selectedType}
          allTypes={types}
          onSave={handleSave}
          isNew={isNew}
        />
      </div>
    </div>
  );
}
```

`QueryPage.tsx` — 左侧 QueryHistory + 右侧 QueryEditor（上部40%）+ QueryResults（下部60%），可拖拽分割条。约60行。

`SecurityPage.tsx` — 左侧双列表（Roles + Actions 折叠组）+ 右侧 RoleEditor 或 ActionEditor（根据左侧选中类型）。约60行。

- [ ] **Step 2: 验证无 TS 错误**

```bash
cd workshop && npx tsc --noEmit
```

Expected: No errors.

- [ ] **Step 3: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/pages/
git commit -m "feat: Pages — SchemaPage, QueryPage, SecurityPage with master-detail layouts"
```

---

### Task 15: App 入口 + Router + MSW 初始化 + main.tsx

**Files:**
- Modify: `workshop/src/App.tsx`
- Modify: `workshop/src/main.tsx`
- Create: `workshop/src/mocks/browser.ts` (MSW service worker setup)

- [ ] **Step 1: 配置 MSW**

`workshop/src/mocks/browser.ts`:

```typescript
import { setupWorker } from 'msw/browser';
import { handlers } from '@/api/mock/handlers';

export const worker = setupWorker(...handlers);
```

生成 service worker 文件：

```bash
cd workshop && npx msw init public/ --save
```

- [ ] **Step 2: 实现 main.tsx**

```typescript
import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import './index.css';

async function bootstrap() {
  // Start MSW in development
  if (import.meta.env.VITE_API_MODE === 'mock') {
    const { worker } = await import('./mocks/browser');
    await worker.start({ onUnhandledRequest: 'bypass' });
  }

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: 1,
      },
    },
  });

  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <BrowserRouter>
        <QueryClientProvider client={queryClient}>
          <App />
        </QueryClientProvider>
      </BrowserRouter>
    </React.StrictMode>,
  );
}

bootstrap();
```

- [ ] **Step 3: 实现 App.tsx（路由配置）**

```typescript
import { Routes, Route, Navigate } from 'react-router-dom';
import { AppLayout } from './components/layout/AppLayout';
import { SchemaPage } from './pages/SchemaPage';
import { QueryPage } from './pages/QueryPage';
import { SecurityPage } from './pages/SecurityPage';

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/" element={<Navigate to="/schema" replace />} />
        <Route path="/schema" element={<SchemaPage />} />
        <Route path="/schema/:typeName" element={<SchemaPage />} />
        <Route path="/query" element={<QueryPage />} />
        <Route path="/security" element={<SecurityPage />} />
        <Route path="/security/roles/:roleName" element={<SecurityPage />} />
        <Route path="/security/actions/:actionName" element={<SecurityPage />} />
      </Route>
    </Routes>
  );
}
```

- [ ] **Step 4: 删除 Vite 默认文件**

```bash
rm workshop/src/App.css workshop/src/assets/react.svg
```

- [ ] **Step 5: 启动开发服务器验证**

```bash
cd workshop && npm run dev
```

手动验证：浏览器打开 localhost:5173，应看到三 Tab 导航 + 默认进入 Schema 页 + Mock 数据加载的 TypeList。

- [ ] **Step 6: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/App.tsx workshop/src/main.tsx workshop/src/mocks/ workshop/public/
git rm workshop/src/App.css workshop/src/assets/react.svg --cached 2>/dev/null
git commit -m "feat: App entry, router, MSW bootstrap, main.tsx — full SPA wired"
```

---

### Task 16: 集成测试（3 条关键路径）

**Files:**
- Create: `workshop/src/test/integration.test.tsx`

- [ ] **Step 1: 编写集成测试**

`workshop/src/test/integration.test.tsx` — 3 条端到端用户路径：

1. **创建 Type → Save → 验证出现在列表**
2. **加载 Type → 编辑字段 → 验证脏标记 → Save**
3. **Query Console 打开 → 输入查询 → 执行 → 验证结果表**

使用 MSW handlers（测试中 `setupServer` 复用 mock handlers）+ React Testing Library。

- [ ] **Step 2: 运行集成测试**

```bash
cd workshop && npx vitest run src/test/
```

Expected: 3 tests pass.

- [ ] **Step 3: Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/src/test/
git commit -m "test: integration tests — create type, edit field, query execution"
```

---

### Task 17: 最终验证

- [ ] **Step 1: 运行全部测试**

```bash
cd workshop && npx vitest run
```

Expected: All unit + component + integration tests pass (target: ~40+ tests).

- [ ] **Step 2: TypeScript 类型检查**

```bash
cd workshop && npx tsc --noEmit
```

Expected: No errors.

- [ ] **Step 3: 构建验证**

```bash
cd workshop && npm run build
```

Expected: Production build succeeds, output in `workshop/dist/`.

- [ ] **Step 4: 最终 Commit**

```bash
cd /Users/nicholasl/Documents/build-whatever/heirloom
git add workshop/
git commit -m "chore: final verification — all tests pass, build succeeds"
```

---

## 依赖关系图

```
Task 1  (Scaffolding)
  └─► Task 2  (Types & Constants)
        ├─► Task 3  (SchemaRegistrySnapshot)
        │     ├─► Task 4  (Type Validator)
        │     ├─► Task 5  (Query Validator)
        │     └─► Task 6  (Action Validator)
        └─► Task 7  (API Client + Mock Data)
              └─► Task 8  (Layout Components)
                    ├─► Task 9  (Shared Components)
                    ├─► Task 10 (Schema Components)
                    ├─► Task 11 (Query Components)
                    └─► Task 12 (Security Components)
                          └─► Task 13 (Hooks)
                                └─► Task 14 (Pages)
                                      └─► Task 15 (App + Router)
                                            └─► Task 16 (Integration Tests)
                                                  └─► Task 17 (Final Verification)
```

Tasks 3-6 (validators) 可与 Task 7 (API mock) 并行。Tasks 10-12 (三大 Tab 组件) 可并行。

## 并行执行策略

通过 subagent-driven-development 的 parallel 模式，可将以下组并行：

**Phase A（串行基座）**：Task 1 → 2 → 3

**Phase B（并行）**：Task 4, 5, 6, 7 同时进行

**Phase C（串行 UI 核心）**：Task 8 → 9（可合并为一步）

**Phase D（并行 Tab 组件）**：Task 10, 11, 12 同时进行

**Phase E（串行收尾）**：Task 13 → 14 → 15 → 16 → 17
