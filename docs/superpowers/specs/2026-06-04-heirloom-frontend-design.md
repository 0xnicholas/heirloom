# Heirloom Workshop — 前端设计规范

> 状态：待审阅 | 日期：2026-06-04 | 版本：v1.0

## 1. 概述

**Heirloom Workshop** 是面向本体建模者和管理员的前端管理控制台。它以 IDE 形态支持建模者完成 **Schema 定义 → 查询测试 → 安全配置** 的完整工作流，覆盖 Heirloom 路线图 Phase 0-2 的全部功能域。

### 1.1 核心约束

| 约束 | 决策 |
|------|------|
| 目标用户 | 本体建模者/管理员 |
| 工作流形态 | 建模 + 调试 IDE（写 Schema → 测试查询 → 验证安全边界） |
| 技术栈 | React 19 + TypeScript 5 + Vite |
| 后端状态 | 前端先行，使用 MSW 完整 mock API 层独立开发 |
| 功能范围 | Phase 0-2：Schema Registry、Query DSL、数据浏览器、Abilities 矩阵、状态机、Role/Capability、Action 定义器 |

---

## 2. 信息架构与导航

### 2.1 顶级导航

```
◇ Heirloom    Schema  │  Query  │  Security
```

三个 Tab 通过 React Router 映射 URL：`/schema`、`/query`、`/security`。默认进入 `/schema`。

### 2.2 主从布局模式

每个 Tab 内部使用统一的主从布局：

```
┌── 主面板（左侧 280px）──┬── 详情面板（右侧剩余）──────┐
│                        │                            │
│  列表 + 搜索 + 新建     │   编辑器 / 配置表单 /       │
│                        │   查询结果                  │
└────────────────────────┴────────────────────────────┘
```

### 2.3 全局 Query Console

固定在底部的抽屉面板，可从任何 Tab 通过 `Ctrl+`` ` 快捷键或底部状态栏按钮打开/关闭。高度可拖拽调整（25%-75% 视口高度）。包含：

- 迷你 Monaco JSON DSL 编辑器
- 结果展示区（编辑器右侧，利用宽屏）
- 独立 Recent Runs 历史（localStorage 持久化，最近 5 条缩略预览）

**上下文感知**：从 Schema Tab 打开时，如果正在编辑某个 Type，Console 中 `from` 自动预设为该 Type 名。

### 2.4 路由表

| Path | Page | 说明 |
|------|------|------|
| `/` | Redirect → `/schema` | 默认进入 Schema |
| `/schema` | SchemaPage | 含 TypeList + TypeEditor |
| `/schema/:typeName` | SchemaPage | 直接打开指定 Type |
| `/query` | QueryPage | 含 QueryHistory + QueryEditor + QueryResults |
| `/security` | SecurityPage | 左侧 Role 列表 + Action 列表 |
| `/security/roles/:roleName` | SecurityPage | 直接打开指定 Role |
| `/security/actions/:actionName` | SecurityPage | 直接打开指定 Action |

`QueryConsole` 不占用路由——它是全局 overlay，状态由 React context 管理。

---

## 3. 各 Tab 详细设计

### 3.1 Schema Tab

#### 3.1.1 左侧 TypeList

- 所有 Resource Type 的扁平列表（含搜索过滤）
- 选中项高亮，点击加载右侧编辑器
- 底部 `+ New Type` 按钮

#### 3.1.2 右侧 TypeEditor

##### 字段定义表

内联编辑的字段表格，支持：

- 字段名、类型（下拉选择：string/number/boolean/enum/date/rid）、必填/可选标记
- 点击字段名直接编辑，点击类型弹出下拉
- 拖拽排序
- `+ Add Field` 按钮追加新行
- 每行右侧 `✕` 删除按钮

##### 状态机编辑器（ReactFlow）

有向图渲染 Resource Type 的状态迁移：

- 节点表示状态，边表示合法迁移
- 双击节点/边弹出编辑对话框
- 从节点连接点拖拽到另一节点创建新迁移
- 自动校验：重复迁移标红提示，目标状态是否存在于状态机定义中

##### Abilities 矩阵

简单的复选框矩阵：

| query | mutate | drop | freeze | transfer | copy | store | key |
|-------|--------|------|--------|----------|------|-------|-----|
| [✓]   | [✓]    | [ ]  | [✓]    | [ ]      | [✓]  | [ ]   | [✓] |

未选中的 Ability 灰色显示，提示其在此类型中不可表达。

##### 关系列表

```
Customer ──[placed]──▶ Order    (Association)   [✕]
Customer ──[owns]───▶ Contract (Ownership)      [✕]
[+ Add Relationship]
```

- 每条关系显示：源 —[标签]—> 目标（语义类型）
- 点击展开可编辑标签名和切换语义类型（Ownership / Reference / Association）
- 选择目标 Type 时弹出下拉，列出所有已定义 Type

##### 保存逻辑

- 本地编辑状态跟踪（脏标记）
- Save 按钮提交到 API
- 未保存离开时弹出确认对话框

### 3.2 Query Tab

#### 3.2.1 左侧 QueryHistory

- 已保存查询列表（时间倒序）
- 前 80 字符预览
- 收藏/取消收藏切换
- 点击加载到编辑器
- 搜索已保存查询

#### 3.2.2 右侧 QueryEditor（Monaco）

- JSON DSL 编辑，语法高亮
- **自动补全**：
  - `"from": "` → 弹出已注册 Type 列表
  - `"select": ["` → 弹出当前 Type 的字段列表
  - 遍历路径中 → 提示已有的 Relationship 标签
  - 聚合操作符：`$count`、`$sum`、`$avg`、`$max`、`$min`
- **实时校验**：基于 SchemaRegistrySnapshot，即时红色波浪线标注：Type 不存在、字段拼写错误、路径语法错误
- **代码片段**：预设模板——基础查询、遍历查询、聚合查询、语义搜索
- Run / Save 按钮

#### 3.2.3 右侧 Results 面板

上下分栏：编辑器（40%）+ 结果（60%），可拖拽分割条。

三种视图切换：

| 视图 | 用途 | 关键交互 |
|------|------|---------|
| **Table** | 扁平行数据查看 | 分页、列排序、列宽拖拽、点击 rid 可导航 |
| **Graph** | 关系遍历结果可视化 | ReactFlow 渲染节点和边，悬停显示属性，拖拽布局 |
| **Raw JSON** | 调试原始响应 | 语法高亮 + 折叠 + 搜索结果高亮 |

Graph 视图仅在查询包含 `traverse` 时激活。每个 Resource 为一个节点，关系为边。

### 3.3 Security Tab

#### 3.3.1 左侧双列表

两个折叠分组，各自有 `+ New` 入口：

- **Roles**：Admin、Sales、SupplyChainAnalyst、Compliance ...
- **Actions**：update_tier、send_alert、approve_order、inventory.update_safety_stock ...

点击 Role 加载 RoleEditor，点击 Action 加载 ActionEditor。

#### 3.3.2 RoleEditor

```
Role: SupplyChainAnalyst                          [Save]
───────────────────────────────────────────────────
Scope: Resource Type      Target: Material, Inventory

Granted Capabilities:
┌──────────────────────────────────────────┐
│ Capability           Target      Scope   │
│ ─────────            ──────      ─────   │
│ Material.query       Material    Type    │
│ Inventory.query      Inventory   Type    │
│ notification.send    *           Global  │
│ [+ Grant Capability]                     │
└──────────────────────────────────────────┘

Assigned Actors:
┌──────────────────────────────────────────┐
│ agent.supply_chain_analyst         [✕]   │
│ user.alice                         [✕]   │
│ [+ Assign Actor]                         │
└──────────────────────────────────────────┘
```

Capability 分配时，下拉选择 Ability + Target Type + Scope 的组合。系统校验 Ability 是否在 Target Type 上已声明。

#### 3.3.3 ActionEditor

```
Action: inventory.update_safety_stock                [Save]
────────────────────────────────────────────────────────

Target Type:  [Inventory ▾]
Requires:     [mutate ▾]        ← 仅显示目标 Type 已声明的 Ability
Gate:         [state = Active ▾]

Parameters:
┌──────────────────────────────────────────┐
│ safety_stock   number   required         │
│ reason          string   optional        │
│ [+ Add Parameter]                        │
└──────────────────────────────────────────┘

Validate (业务规则):
┌──────────────────────────────────────────┐
│ risk_score(inventory) > 0.3              │
│ safety_stock >= 0                        │
│ [+ Add Rule]                             │
└──────────────────────────────────────────┘

Execute:
┌──────────────────────────────────────────┐
│ (Action 执行 DSL — 格式待 Phase 2        │
│  实现时确定。编辑器提供语法高亮和基本     │
│  校验的代码区域)                          │
└──────────────────────────────────────────┘

⚠ Live Validation:  ✓ requires mutates exists on Inventory
                     ✓ gate state Active exists in SM
```

**实时校验反馈条**（绿色/红色状态条），持续检查：

- `Requires` 的 Ability 是否在 Target Type 上已声明
- `Gate` 的状态是否存在于 Target Type 的状态机中
- 参数类型是否匹配 Target Type 的字段类型

---

## 4. 实时校验引擎

校验在编辑过程中持续运行，非 Save 时才触发。这是方案 2 实现「IDE 体验」的核心。

### 4.1 SchemaRegistrySnapshot

应用启动时通过 `listTypes()` 全量加载到内存（返回完整 Type 定义：字段、Abilities、状态机、关系）。每次成功 Save 后乐观更新。所有校验器从这个单一快照读取——保证跨 Tab 一致性。

### 4.2 校验器

| 校验器 | 触发时机 | Debounce | 输出 |
|--------|---------|----------|------|
| Type Validator | 字段/状态机/Ability 变更 | 300ms | Diagnostic[] |
| Query Validator | Monaco onChange | 500ms | Monaco markers |
| Action Validator | requires/gate/参数变更 | 300ms | Diagnostic[] |

### 4.3 错误严重级别

| 级别 | 视觉 | 行为 |
|------|------|------|
| **Error**（红色） | 红色波浪线 + 悬浮提示 | Save 按钮 disabled |
| **Warning**（黄色） | 黄色波浪线 | 可保存，但提示风险 |
| **Info**（蓝色） | 蓝色下划线虚线 | 最佳实践建议 |

### 4.4 校验规则清单

#### Type Validator

- 字段名重复 → Error
- 字段类型不合法 → Error
- 关系引用不存在的目标 Type → Error
- 状态机迁移引用不存在的状态 → Error
- 状态机中存在孤立节点（无入边也无出边）→ Warning
- 未声明任何 Abilities → Warning
- 类型名不符合命名规范（建议 PascalCase）→ Info

#### Query Validator

- `from` Type 不存在 → Error
- `select` 字段在目标 Type 上不存在 → Error
- 遍历路径中关系标签不存在 → Error
- 遍历路径中目标 Type 不存在 → Error
- `filter` 字段不存在 → Error
- 聚合操作符拼写错误 → Error
- `limit` 为 0 或负数 → Warning

#### Action Validator

- `requires` 的 Ability 在 Target Type 上未声明 → Error
- `gate` 的状态在 Target Type 的状态机中不存在 → Error
- Target Type 未在 Schema Registry 中注册 → Error
- 参数类型与 Target Type 对应字段类型不匹配 → Error
- 参数名不在 Target Type 字段列表中 → Warning

---

## 5. 数据流与状态管理

### 5.1 状态分层

| 层 | 工具 | 存储内容 |
|---|------|---------|
| **服务端状态** | TanStack Query v5 | Type 列表、查询历史、Role/Action 列表等 API 数据 |
| **编辑器本地状态** | React state / useReducer | 当前编辑的 Type、未保存的字段变更、Monaco 内容 |
| **UI 状态** | React context | Query Console 开/关、高度、当前 Tab、侧栏选中项 |
| **持久化 UI 状态** | localStorage | Query Console 历史、Monaco 主题、分割条位置 |

### 5.2 无需引入的库

- 状态管理库（Redux/Zustand/Jotai）：TanStack Query + React state + context 已足够
- 拖拽面板框架：方案 2 是固定主从 + Console 抽屉，不需自由面板布局
- UI 组件库（Ant Design/MUI/shadcn/ui）：Tailwind CSS 自建组件

### 5.3 React 19 特性利用

- TanStack Query 内置 Suspense 支持（`suspense: true` 选项），用于声明式加载状态管理
- `useOptimistic`：Save 操作的即时 UI 反馈
- `useActionState`：表单提交的 pending/error 状态管理

---

## 6. API 层设计

### 6.1 适配器模式

所有 API 调用通过统一接口，组件不感知底层实现：

```typescript
// api/client.ts — 统一接口
interface ApiClient {
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

### 6.2 Mock 层（MSW v2）

- 使用 MSW 在浏览器 Service Worker 层拦截 fetch 请求
- Mock 数据预设：4-5 个 Resource Type（含完整字段、状态机、Abilities、关系）、10+ 个符合 Type 定义的实例、3 个 Role、5 个 Action
- 数据持久化到 localStorage，刷新不丢失
- 通过环境变量 `VITE_API_MODE=mock|real` 切换适配器

### 6.3 切真实 API

后期实现 `api/real/client.ts`，实现相同的 `ApiClient` 接口。组件代码零改动。

---

## 7. 组件树与文件结构

### 7.1 组件树

```
App
├─ QueryClientProvider (TanStack Query)
│  ├─ AppLayout
│  │  ├─ NavBar (Schema | Query | Security)
│  │  ├─ Outlet (React Router)
│  │  │  ├─ SchemaPage
│  │  │  │  ├─ TypeList
│  │  │  │  └─ TypeEditor
│  │  │  │     ├─ FieldTable
│  │  │  │     ├─ StateMachineEditor (ReactFlow)
│  │  │  │     ├─ AbilitiesMatrix
│  │  │  │     └─ RelationshipList
│  │  │  ├─ QueryPage
│  │  │  │  ├─ QueryHistory
│  │  │  │  ├─ QueryEditor (Monaco)
│  │  │  │  └─ QueryResults (Table | Graph | Raw)
│  │  │  └─ SecurityPage
│  │  │     ├─ RoleList / RoleEditor
│  │  │     └─ ActionList / ActionEditor
│  │  └─ QueryConsole (全局 overlay)
│  │     ├─ MiniMonaco
│  │     └─ MiniResults
│  └─ React Router
```

### 7.2 文件结构

```
/src
├── main.tsx
├── App.tsx
├── api/
│   ├── client.ts                # ApiClient 接口
│   ├── mock/
│   │   ├── handlers.ts          # MSW handlers
│   │   ├── data.ts              # 预设 mock 数据
│   │   └── store.ts             # localStorage 读写
│   └── real/
│       └── client.ts            # 后期真实 API 适配器
├── lib/
│   ├── validation/
│   │   ├── types.ts             # Diagnostic, Severity 类型
│   │   ├── registry-snapshot.ts # SchemaRegistrySnapshot 构建/更新
│   │   ├── type-validator.ts
│   │   ├── query-validator.ts
│   │   └── action-validator.ts
│   └── constants.ts
├── components/
│   ├── layout/
│   │   ├── AppLayout.tsx
│   │   ├── NavBar.tsx
│   │   └── QueryConsole.tsx
│   ├── schema/
│   │   ├── TypeList.tsx
│   │   ├── TypeEditor.tsx
│   │   ├── FieldTable.tsx
│   │   ├── StateMachineEditor.tsx
│   │   ├── AbilitiesMatrix.tsx
│   │   └── RelationshipList.tsx
│   ├── query/
│   │   ├── QueryHistory.tsx
│   │   ├── QueryEditor.tsx
│   │   └── QueryResults.tsx
│   ├── security/
│   │   ├── RoleList.tsx
│   │   ├── RoleEditor.tsx
│   │   ├── ActionList.tsx
│   │   └── ActionEditor.tsx
│   └── shared/
│       ├── MasterDetail.tsx
│       ├── ValidationBar.tsx
│       └── ConfirmDialog.tsx
├── hooks/
│   ├── useSchemaRegistry.ts
│   ├── useQueries.ts
│   ├── useSecurity.ts
│   └── useValidation.ts
├── pages/
│   ├── SchemaPage.tsx
│   ├── QueryPage.tsx
│   └── SecurityPage.tsx
└── styles/
    └── globals.css
```

---

## 8. 技术选型总览

| 类别 | 选型 | 说明 |
|------|------|------|
| 框架 | React 19 + TypeScript 5 | — |
| 构建 | Vite | 环境变量切换 mock/real |
| 路由 | React Router v6 | Layout route + 嵌套 Outlet |
| 服务端状态 | TanStack Query v5 | 缓存、refetch、乐观更新、mutation |
| Mock API | MSW v2 | Service Worker 拦截，仿真 HTTP；测试复用 |
| 代码编辑器 | `@monaco-editor/react` | 自动补全、diagnostic markers、代码片段 |
| 图可视化 | `@xyflow/react` (ReactFlow) | 状态机有向图、遍历结果图 |
| 表格 | TanStack Table v8 | Headless，不强制 UI |
| CSS | Tailwind CSS | — |
| 测试 | Vitest + React Testing Library | MSW handlers 在测试中复用 |

---

## 9. 测试策略

| 层级 | 工具 | 测试内容 | 优先级 |
|------|------|---------|--------|
| **单元测试** | Vitest | 三个校验器——验证 Diagnostic 输出正确性 | 最高 |
| **组件测试** | Vitest + RTL | TypeEditor 字段增删改、StateMachineEditor 连线拖拽、QueryEditor 自动补全触发 | 高 |
| **集成测试** | Vitest + RTL + MSW | 关键用户路径（新建 Type → 定义字段 → Save → Query Console 查询 → 验证结果）| 中（3-5 条） |
| **E2E** | — | Phase 0-2 不做。Phase 3-4 Agent 集成后再引入 Playwright/Cypress | — |

### 9.1 校验器测试示例

```
describe('TypeValidator', () => {
  it('reports error when field name is duplicated')
  it('reports error when relationship target type does not exist')
  it('reports warning when state machine has orphan nodes')
  it('reports warning when no abilities declared')
  it('reports info when type name does not follow PascalCase')
})

describe('QueryValidator', () => {
  it('reports error when from type does not exist')
  it('reports error when select field does not exist on type')
  it('reports error when traverse path uses undeclared relationship')
  it('reports warning when limit is zero or negative')
})

describe('ActionValidator', () => {
  it('reports error when requires ability not declared on target type')
  it('reports error when gate state not in target state machine')
  it('reports error when target type not registered')
  it('reports warning when parameter not in target type fields')
})
```

---

## 10. 非目标（YAGNI）

本阶段明确不做：

- 复杂的可拖拽面板布局框架（react-mosaic）
- 状态管理库（Redux、Zustand、Jotai）
- UI 组件库（Ant Design、MUI、shadcn/ui）
- GraphQL 客户端
- E2E 测试
- 多语言/国际化
- 暗色模式（Monaco 主题切换除外）
- 移动端适配
- 用户登录/认证系统（Phase 3 引入 Agent 身份后处理）

---

## 版本历史

| 日期 | 版本 | 说明 |
|------|------|------|
| 2026-06-04 | v1.0 | 初始设计规范，基于头脑风暴对话 |
