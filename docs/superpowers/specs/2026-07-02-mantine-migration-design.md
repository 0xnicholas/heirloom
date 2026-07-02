# Spec: Workshop 前端迁移至 Mantine

**日期**: 2026-07-02
**关联**: `7abe028 feat(workshop): rewrite Heirloom frontend with full concept coverage and dark mode`（当前 Workshop 主分支）
**状态**: Draft v1
**范围**: 把 `workshop/` 项目的自定义 UI 层（Tailwind v4 + inline SVG 图标 + 手写组件）替换为 Mantine v7。保持数据契约、域模型、验证器、MSW mock、Monaco、xyflow 不动。

---

## 0. 问题陈述

Workshop 当前是 React 19 + Vite 8 + Tailwind v4 + TypeScript 项目，5,975 行代码 / 30+ 自定义组件 / **14 page 路由**（共 23 条 path 条目：17 unique page routes + 6 个 PlaceholderPage，含 1 条 `/objects` → `/explorer` 重定向） / 79 tests（73 ✅ / 6 ❌）。三类问题驱动这次迁移：

### 0.1 组件可重用性低

每个交互元素都从零手写。例证：
- `SideNav.tsx` 含 19 个 inline SVG 图标组件（~200 行），TopBar 5 个，PlaceholderPage 1 个（**全 Workshop 共 25 个**）
- `ConfirmDialog.tsx` 手写 state + render 模式
- `AppLayout.tsx`、`TopBar.tsx`、`QueryConsole.tsx` 各自维护 useState 控制显示
- `AbilitiesMatrix.tsx`、`FieldTable.tsx`、`TypeList.tsx` 等都手写 checkbox/select/button 视觉
- 任何新页面都要重新拼一遍这些原语

### 0.2 缺失现代 UI 范式

- 无 toast/notification 系统（错误只能 `console.error` 或 `alert`）
- 无受控表单 + 校验原语（当前 click-to-edit 状态散落在 useState）
- 无 Modal/Drawer 标准模式（每个 modal 写自己的 backdrop + escape + focus trap）
- 无 theme tokens（颜色是写死的 `bg-indigo-700`、`text-stone-600`）
- 无命令面板（QueryConsole 局限于 Ctrl+` 触发，缺全局快捷入口）

### 0.3 测试基础设施有缺漏

- 6/79 tests 静默失败（`window.localStorage` 不可用——jsdom 26+ lazy getter 行为变化），无人察觉
- 缺少错误处理路径的测试覆盖（因为没有 notification 系统可测）
- 缺少 a11y 行为测试（手写组件属性不规范）

### 0.4 期望产出

迁移后 Workshop 应：
- 拥有与白皮书地位匹配的现代交互体验（form/feedback/navigation）
- 组件 LOC 净减少（删 inline SVG + 自实现 ConfirmDialog）
- 测试 100% 绿，包含新交互的测试覆盖
- 与 `website/` 营销站形成一致设计语言（同样 indigo/stone 调色板）
- 未来加新页面/新概念时，无需再写 UI 原语

---

## 1. 设计决策

### 1.1 包选型

**引入**：

| 包 | 用途 | 替代的现有代码 |
|---|---|---|
| `@mantine/core` | Button/Input/Table/Modal/Alert/NavLink/AppShell 等所有原子组件 | Tailwind 写的 30+ 组件 |
| `@mantine/hooks` | useDisclosure / useDebouncedValue / useMediaQuery | 手写的 `useDebounce` 等 |
| `@mantine/form` | 受控表单 + 校验 | 散落在各 Editor 组件的 useState |
| `@mantine/modals` | 命令式 Modal API | `components/shared/ConfirmDialog.tsx` |
| `@mantine/notifications` | Toast 系统 | **当前无**，新增 |
| `@mantine/code-highlight` | 代码块高亮 | `Specimen` 风格的预格式化代码 |
| `@mantine/dates` | DateInput 日期选择器 | FieldType `'date'` 当前用 text input 凑合 |
| `@tabler/icons-react` | 图标库 | **inline SVG 组件**（共 25 个：SideNav 19 + TopBar 5 + PlaceholderPage 1） |
| `dayjs` | @mantine/dates 的 peer dep | — |

**不引入**（评估后排除）：

- `@mantine/charts` — Workshop 暂无 chart 代码，引入会多 ~100KB 但无立即使用处
- `@mantine/spotlight` — QueryConsole 已用 Ctrl+` 触发，spotlight 是不同交互模式，目前不必要
- `@mantine/dropzone` / `@mantine/carousel` / `@mantine/typo` / `@mantine/tiptap` / `@mantine/nprogress` — 无业务场景
- `lucide-react` — **Workshop 不使用 lucide-react**（`grep -rln "from 'lucide-react'" src/` 零结果）。图标全部是 inline SVG，迁移目标是用 `@tabler/icons-react` 替换这 25 个 SVG，不是替换 lucide

### 1.2 主题策略

```ts
// src/lib/theme.ts
import { createTheme, MantineColorsTuple } from '@mantine/core'

const indigo: MantineColorsTuple = [
  '#eef2ff', '#e0e7ff', '#c7d2fe', '#a5b4fc', '#818cf8',
  '#6366f1', '#4f46e5', '#4338ca', '#3730a3', '#312e81',
]

const stone: MantineColorsTuple = [
  '#fafaf9', '#f5f5f4', '#e7e5e4', '#d6d3d1', '#a8a29e',
  '#78716c', '#57534e', '#44403c', '#292524', '#1c1917',
]

export const theme = createTheme({
  primaryColor: 'indigo',
  primaryShade: { light: 6, dark: 5 },
  fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
  defaultRadius: 'md',
  colors: { indigo, stone },
})
```

调色板定义与 `website/` 营销站一致（同一品牌）。

### 1.3 暗色模式

**完全交给 Mantine**：
- 删除 `src/components/theme/ThemeContext.tsx`、`ThemeProvider.tsx`、`ThemeProvider.test.tsx`（共 3 文件）
- 删除 `src/hooks/useTheme.ts`
- 使用 `MantineProvider defaultColorScheme="auto"` + Mantine 内建 localStorage 持久化
- 现有 `useTheme` 调用点全部替换为 `useMantineColorScheme`
- `index.html` 的 FOUC 脚本替换为 Mantine 的 `data-mantine-color-scheme` 内联脚本

### 1.4 Provider 层级

```tsx
// src/main.tsx
<MantineProvider theme={theme} defaultColorScheme="auto">
  <ModalsProvider>
    <Notifications position="top-right" />
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </ModalsProvider>
</MantineProvider>
```

层级顺序依据：
- `MantineProvider` 必须最外层（提供 theme + color scheme 给所有子组件）
- `ModalsProvider` 依赖主题上下文，置于 MantineProvider 内部
- `Notifications` 同理
- `QueryClientProvider` 与主题无关，可放外层
- `BrowserRouter` 必须在外层（route context 影响所有子组件）

### 1.5 Tailwind 命运

**完全移除，不留过渡期**：
- 迁移过程中，**每个组件迁移时同步移除其 Tailwind className**
- 不再有"为兼容性保留 Tailwind"的安全窗口
- Phase 9 cleanup：卸载 `tailwindcss` + `@tailwindcss/vite`，清 `index.css` 的 `@import "tailwindcss"`，清 `vite.config.ts` 的 `tailwindcss()` plugin
- 节奏：Phase 0 后 Tailwind 仍在 `package.json`（被未迁移代码使用），但**不再新增任何 Tailwind class**；每个组件迁移完，它的 Tailwind class 就被清空；Phase 8 tests 收尾时，未迁移的代码应已清空；Phase 9 卸载包

### 1.6 不动的部分

完全保持原样，不参与迁移：

- `src/lib/types.ts`（域模型：ResourceType / QueryDSL / Role / Action / Field 等）
- `src/lib/constants.ts`（`ABILITIES` / `FIELD_TYPES` / `RELATIONSHIP_SEMANTICS` / `AGGREGATE_FNS` / `QUERY_TEMPLATES`）
- `src/lib/validation/*`（4 个验证器 + 4 个测试文件 = 31 tests）
- `src/api/mock/*`（`data.ts` 225 行 mock data + `handlers.ts` MSW handlers + `store.ts` localStorage 持久化）
- `src/api/query.ts`（`executeQuery` 函数）
- `src/hooks/useSchemaRegistry.ts` / `useQueries.ts` / `useSecurity.ts` / `useValidation.ts` / `useActions.ts` / `useRoles.ts`（**6 个数据 hook，零 UI 依赖**）
- `src/mocks/browser.ts`（MSW worker 入口）
- `public/mockServiceWorker.js`（MSW 生成的 service worker）
- 所有 `*.test.ts`（除 UI 组件测试外）

`src/api/client.ts` 整个文件删除（`ApiClient` 接口 0 引用，是早期设计遗留）。

### 1.7 测试策略

**当前 79 tests 分布**（完整分项表见 §5.1，本节给出粗略分布供快速参考）：

| 类别 | 文件数 | tests |
|---|---|---|
| 单元（validators + types） | 5 | 31 |
| 组件行为 | 6 | 34 |
| 集成（MSW 端到端） | 1 | 3 |
| ThemeProvider | 1 | 2 |
| Shared | 1 | 9（含 ValidationBar / ConfirmDialog）|

**改动原则**：
- 每个组件迁移时，**同步更新它的测试**（同 PR 提交）
- 不再写"快照测试"——Mantine 内部 DOM 变化频繁，快照不稳
- 改用 Testing Library 行为测试：找 label、找 button、找 role、模拟点击
- 验证器测试 0 改动
- 集成测试（`integration.test.tsx`）0 改动——它只测外部 API 契约

**新增测试**：
- `notifications.test.tsx`：验证 mutation 失败时 toast 出现
- `useModals.test.tsx`：验证 `openConfirmModal` 在 onConfirm 时触发回调
- 验证 `jsdom` 下 `window.localStorage` 在 `test/setup.ts` 注入 polyfill——顺手修 6 个失败测试

**净变化**：~79 → ~85-90 tests

---

## 2. 架构

### 2.1 文件结构变化

```
workshop/
├── src/
│   ├── api/                          # 不动
│   │   ├── query.ts
│   │   └── mock/{data,handlers,store}.ts
│   ├── components/
│   │   ├── layout/                   # 重写
│   │   │   ├── AppLayout.tsx         # → AppShell，QueryConsole 保留 inline 面板
│   │   │   ├── TopBar.tsx            # → Group + ActionIcon，删 5 inline SVG
│   │   │   ├── SideNav.tsx           # → NavLink，删 19 个 inline SVG
│   │   │   ├── QueryConsole.tsx      # 保留 inline 底部面板，Paper + Group 包 Monaco + 拖拽缩放保留
│   │   │   └── ConsoleContext.tsx    # 保留（纯 React Context）
│   │   ├── schema/                   # 逐个替换
│   │   │   ├── TypeList.tsx          # → Table
│   │   │   ├── FieldTable.tsx        # → Table + TextInput 单元格
│   │   │   ├── StateMachineEditor.tsx # 保留 xyflow，包 Paper
│   │   │   ├── AbilitiesMatrix.tsx   # → Checkbox.Group + SimpleGrid
│   │   │   ├── RelationshipList.tsx  # → Table
│   │   │   ├── TypeEditor.tsx        # → @mantine/form + Tabs
│   │   │   └── SchemaTab.test.tsx    # 同步重写
│   │   ├── security/                 # 逐个替换
│   │   │   ├── RoleList.tsx          # → Table
│   │   │   ├── ActionList.tsx        # → Table
│   │   │   ├── RoleEditor.tsx        # → @mantine/form
│   │   │   ├── ActionEditor.tsx      # → @mantine/form
│   │   │   └── SecurityTab.test.tsx  # 同步重写
│   │   ├── query/                    # 逐个替换
│   │   │   ├── QueryEditor.tsx       # 保留 Monaco，包 @mantine/form
│   │   │   ├── QueryHistory.tsx      # → Table
│   │   │   ├── QueryResults.tsx      # → Tabs (Table/Raw/Plan)
│   │   │   └── QueryTab.test.tsx     # 同步重写
│   │   ├── stats/                    # 逐个替换
│   │   │   ├── MetricCards.tsx       # → Card + SimpleGrid
│   │   │   ├── ELI5Card.tsx          # → Paper + Text
│   │   │   ├── OntologyGraph.tsx     # 保留 xyflow，包 Paper
│   │   │   ├── RelationshipPanel.tsx # → Table
│   │   │   ├── ResourceTypePanel.tsx # → Table
│   │   │   └── StatsPage.test.tsx    # 同步重写
│   │   ├── shared/                   # 替换
│   │   │   ├── ValidationBar.tsx     # → Alert + Badge
│   │   │   ├── ConfirmDialog.tsx     # 删除
│   │   │   ├── PlaceholderPage.tsx   # → Stack + Title
│   │   │   └── shared.test.tsx       # 同步重写
│   │   └── theme/                    # 整个删除
│   │       ├── ThemeContext.tsx      # 删
│   │       ├── ThemeProvider.tsx     # 删
│   │       └── ThemeProvider.test.tsx # 删
│   ├── hooks/                        # 改一个
│   │   ├── useTheme.ts               # 删
│   │   └── (其余 6 个不动)
│   ├── lib/                          # 加/改/删
│   │   ├── types.ts                  # 不动
│   │   ├── constants.ts              # 改（新增 3 个常量：DEFAULT_ONTOLOGY / ONTOLOGIES / API_DOCS_URL）
│   │   ├── config.ts                 # 删（占位文件，5 行；内容已并入 constants.ts）
│   │   ├── theme.ts                  # 新增（Mantine 主题）
│   │   ├── notifications.ts          # 新增（toast 工具）
│   │   └── validation/*              # 不动
│   ├── pages/                        # 14 个轻量调整
│   │   └── (各 page 用 Container/Stack 包裹)
│   ├── mocks/browser.ts              # 不动
│   ├── test/
│   │   ├── setup.ts                  # 加 localStorage polyfill
│   │   └── integration.test.tsx      # 不动
│   ├── App.tsx                       # 轻调（删除 PlaceholderPage 路由自定义 JSX，用新组件）
│   ├── main.tsx                      # 重写为 MantineProviders 包装
│   └── index.css                     # 清 @variant dark，加 Mantine CSS imports
├── public/                           # 不动
├── index.html                        # 替换 FOUC 脚本
├── vite.config.ts                    # 删 tailwindcss() plugin
├── package.json                      # swap 依赖
└── tsconfig.app.json                 # 考虑开 strict: true
```

### 2.2 关键文件

**新增**：

- `src/lib/theme.ts` — Mantine theme 配置
- `src/lib/notifications.ts` — `notifyError` / `notifySuccess` 工具函数
- `src/components/providers/AppProviders.tsx` — 合并 MantineProvider / ModalsProvider / Notifications / QueryClient / Router

**删除**：

- `src/components/theme/ThemeContext.tsx` + `ThemeProvider.tsx` + `ThemeProvider.test.tsx`
- `src/hooks/useTheme.ts`
- `src/api/client.ts`（死代码）
- `src/lib/config.ts`（占位文件，5 行；TopBar.tsx 和 SettingsPage.tsx 共 4 个引用点）
- `src/components/shared/ConfirmDialog.tsx`（被 `@mantine/modals` 替代）

**修改**：

- `src/main.tsx` — 用 AppProviders 包装
- `src/test/setup.ts` — 加 localStorage polyfill
- `index.html` — 替换 FOUC 脚本
- `index.css` — 清 `@variant dark`，加 Mantine CSS imports

### 2.3 关键代码范式

**Container/Stack 替代 div 嵌套**：

```tsx
// 之前
<div className="max-w-7xl mx-auto p-4">
  <div className="space-y-4">
    <h1 className="text-2xl font-semibold">Schema</h1>
    ...
  </div>
</div>

// 之后
<Container size="xl">
  <Stack gap="md">
    <Title order={1}>Schema</Title>
    ...
  </Stack>
</Container>
```

**Table 替代手写 thead/tbody**：

```tsx
// 之前
<table className="w-full text-sm">
  <thead className="border-b border-gray-200">
    <tr>
      <th className="text-left py-2 px-3">Name</th>
      ...
    </tr>
  </thead>
  <tbody>
    {items.map(item => (
      <tr key={item.id} className="hover:bg-gray-50">
        <td className="py-2 px-3">{item.name}</td>
        ...
      </tr>
    ))}
  </tbody>
</table>

// 之后
<Table>
  <Table.Thead>
    <Table.Tr>
      <Table.Th>Name</Table.Th>
      ...
    </Table.Tr>
  </Table.Thead>
  <Table.Tbody>
    {items.map(item => (
      <Table.Tr key={item.id}>
        <Table.Td>{item.name}</Table.Td>
        ...
      </Table.Tr>
    ))}
  </Table.Tbody>
</Table>
```

**`modals.openConfirmModal()` 范式**（未来生产代码用）：

```tsx
// 之前：手写 ConfirmDialog（实际 Workshop 当前 0 生产调用）
const [open, setOpen] = useState(false)
<ConfirmDialog
  open={open}
  title="Delete Type"
  message={`Delete "${type.name}"?`}
  onConfirm={handleDelete}
  onCancel={() => setOpen(false)}
/>
<Button onClick={() => setOpen(true)}>Delete</Button>

// 之后（Phase 5+ 添加删除交互时用此模式）
import { modals } from '@mantine/modals'

function handleDelete() {
  modals.openConfirmModal({
    title: 'Delete Type',
    children: <Text>Delete "{type.name}"?</Text>,
    labels: { confirm: 'Delete', cancel: 'Cancel' },
    confirmProps: { color: 'red' },
    onConfirm: () => deleteMutation.mutate(type.name),
  })
}
<Button color="red" onClick={handleDelete}>Delete</Button>
```

> 注：当前 Workshop 无生产 ConfirmDialog 调用点，但 `modals` API 仍是新能力，Phase 5+ 添加 delete 操作时直接用此模式。

**@mantine/form 替代 useState 表单状态**：

```tsx
// 之前
const [name, setName] = useState('')
const [type, setType] = useState<'string' | 'number'>('string')
const [required, setRequired] = useState(false)
const [errors, setErrors] = useState<Record<string, string>>({})

function validate() {
  const errs: Record<string, string> = {}
  if (!name.trim()) errs.name = 'Name is required'
  if (Object.keys(errs).length) { setErrors(errs); return false }
  return true
}

// 之后
import { useForm } from '@mantine/form'

const form = useForm({
  initialValues: { name: '', type: 'string' as FieldType, required: false },
  validate: {
    name: (v) => v.trim() ? null : 'Name is required',
  },
})
```

> **不引入 zod**：验证用 `@mantine/form` 内联函数即可。如果未来需要 schema 级校验，单独评估。

**错误处理统一模式**：

```ts
// src/lib/notifications.ts
import { notifications } from '@mantine/notifications'

export function notifyError(title: string, error: unknown) {
  notifications.show({
    color: 'red',
    title,
    message: error instanceof Error ? error.message : String(error),
    autoClose: 5000,
  })
}

export function notifySuccess(message: string) {
  notifications.show({
    color: 'green',
    message,
    autoClose: 3000,
  })
}
```

应用点：所有 **6 个数据 hook** 的 `onError` 钩子统一调 `notifyError`，所有 mutation 成功路径调 `notifySuccess`。

---

## 3. Component Mapping 详细表

### 3.1 布局层

| 现有 | Mantine 替代 | 关键决策 |
|---|---|---|
| `AppLayout.tsx`（57 行） | `AppShell` + 自定义底部栏 | 保留 Ctrl+` 快捷键监听；QueryConsole **保留 inline** 模式（见下）|
| `TopBar.tsx`（~50 行） | `Group` + `Burger`（移动端）+ `ActionIcon`（主题切换）| |
| `SideNav.tsx`（**250 行**） | `AppShell.Navbar` + `NavLink` + 分组 `Stack` | **删 19 个 inline SVG 组件**（~200 行），改用 Tabler 图标 |
| `QueryConsole.tsx`（155 行） | **保留 inline 底部面板**（外层 `Paper` + `Group` 标题栏） | **不切 Drawer**：现有 3-pane 布局（editor / results / recent runs）+ 拖拽缩放（25-75% height）Drawer 无法承载。外层 `useDisclosure` 控制整体 open/close；内部 `result` / `dragging` / `containerRef` 维持原 useState；保留 `RECENT_RUNS_KEY`；保留 `onMouseDown/onMouseUp` 拖拽逻辑 |
| `ConsoleContext.tsx` | **保留** | 跟 UI 无关 |

### 3.2 Shared 层

| 现有 | Mantine 替代 |
|---|---|
| `ValidationBar.tsx` | `Alert` + 内嵌 `Badge` 列表 |
| `ConfirmDialog.tsx` | **删除整个文件** | 当前**只在测试夹具中使用**（`shared.test.tsx` 5 个用例），无生产调用点。删除后 `modals.openConfirmModal()` 作为新能力备用 |
| `PlaceholderPage.tsx` | `Stack` + `Title` + `Text` + `Card` |

### 3.3 Schema 标签页

| 现有 | Mantine 替代 | 关键决策 |
|---|---|---|
| `TypeList.tsx` | `Table` + `ScrollArea` + 行点击 | 列：name / version / abilities 数 / 关系数 |
| `FieldTable.tsx` | `Table`（可编辑行） | `TextInput` / `Select` / `Checkbox` 直接放 cell；`@mantine/form` 管 state |
| `StateMachineEditor.tsx` | **保留 xyflow**，外层 `Paper` + `Group`（编辑按钮）| xyflow 无 Mantine 对应，保留 |
| `AbilitiesMatrix.tsx` | `Checkbox.Group` + `SimpleGrid cols={2}` | 用 `Checkbox.Group` 的 `value` 数组直接绑定 |
| `RelationshipList.tsx` | `Table` + `ActionIcon` | |
| `TypeEditor.tsx` | `TextInput` / `Select` / `Tabs`（4 个：Fields/Abilities/States/Relationships） | **核心受益者**：`@mantine/form` 收编散落的 useState |

### 3.4 Security 标签页

| 现有 | Mantine 替代 |
|---|---|
| `RoleList.tsx` | `Table` + `ActionIcon` |
| `ActionList.tsx` | `Table` + `ActionIcon` |
| `RoleEditor.tsx` | `@mantine/form` + `TextInput` / `MultiSelect`（targets）/ `Select`（scope） |
| `ActionEditor.tsx` | `@mantine/form` + `TextInput` / `Select`（targetType, requires）/ `NumberInput` / `Textarea` |

### 3.5 Query 标签页

| 现有 | Mantine 替代 | 关键决策 |
|---|---|---|
| `QueryEditor.tsx` | **保留 Monaco** + `Tabs`（Templates）+ `Group`（Run/Save 按钮） | Monaco 不替换；`useForm` 替代手写编辑器状态 |
| `QueryHistory.tsx` | `Table` + `ActionIcon` | |
| `QueryResults.tsx` | `Tabs`（Table / Raw / Plan）+ `Table` | **决定：丢 TanStack Table**，统一 Mantine Table |

### 3.6 Stats 标签页

| 现有 | Mantine 替代 |
|---|---|
| `MetricCards.tsx` | `SimpleGrid cols={4}` + `Card` |
| `ELI5Card.tsx` | `Paper` p="lg" + `Text` |
| `OntologyGraph.tsx` | **保留 xyflow**，外层 `Paper` + 主题切换适配 |
| `RelationshipPanel.tsx` | `Table` + `Badge`（语义色） |
| `ResourceTypePanel.tsx` | `Table` + 行点击 |

### 3.7 Pages

14 个 page **只做轻量调整**——用 Mantine `Container` / `Stack` 替换外层 div，组件替换后页面自然到位。其中 6 个 `PlaceholderPage` 路由直接收益（不再手写 placeholder 视觉）。

### 3.8 跨页通用转换范式

| 之前（Tailwind） | 之后（Mantine） |
|---|---|
| `<div className="rounded-xl border border-stone-200 bg-white p-6 shadow-sm">` | `<Paper p="md" withBorder shadow="sm" radius="md">` |
| `<button className="px-4 py-2 bg-indigo-700 text-white rounded-lg hover:bg-indigo-800">` | `<Button>` |
| `<input className="border border-stone-300 rounded px-3 py-2">` | `<TextInput>` |
| `<select className="border rounded px-2 py-1">` | `<Select>` |
| `<input type="checkbox" className="rounded">` | `<Checkbox>` |
| `<div className="flex gap-4">` | `<Group gap="md">` |
| `<div className="grid grid-cols-3 gap-4">` | `<SimpleGrid cols={3} spacing="md">` |
| `<div className="space-y-4">` | `<Stack gap="md">` |
| `<div className="absolute top-4 right-4">` | `<Box pos="absolute" top="md" right="md">` |

---

## 4. 错误处理

### 4.1 错误来源分类

| 类别 | 来源 | 表现 |
|---|---|---|
| 数据 fetch 失败 | MSW 拦截的 4xx/5xx；real 后端网络错误 | 之前：`console.error` + 静默；之后：toast |
| Mutation 失败 | createType / updateType / deleteType / saveQuery 等 | 之前：throw；之后：toast + 错误回滚 |
| 校验失败 | `validateType` / `validateQuery` / `validateAction` | 之前：`ValidationBar` 静态展示；之后：保持展示 + 表单 error 高亮 |
| 用户操作确认 | 删除前确认 | 之前：`ConfirmDialog` 组件；之后：`modals.openConfirmModal()` |

### 4.2 通知 API

```ts
// src/lib/notifications.ts
import { notifications } from '@mantine/notifications'

export function notifyError(title: string, error: unknown) {
  notifications.show({
    color: 'red',
    title,
    message: error instanceof Error ? error.message : String(error),
    autoClose: 5000,
  })
}

export function notifySuccess(message: string) {
  notifications.show({
    color: 'green',
    message,
    autoClose: 3000,
  })
}

export function notifyInfo(message: string) {
  notifications.show({ color: 'blue', message, autoClose: 3000 })
}
```

### 4.3 hook 错误处理统一

```ts
// 例: useSchemaRegistry 改造
const saveMutation = useMutation({
  mutationFn: ({ type, isNew }) => saveType(type, isNew),
  onSuccess: () => {
    qc.invalidateQueries({ queryKey: ['types'] })
    notifySuccess(isNew ? 'Type created' : 'Type updated')
  },
  onError: (err) => notifyError('Failed to save type', err),
})
```

**6 个数据 hook** 全部按此模式改造。

---

## 5. 测试策略

### 5.1 当前测试分布

| 类别 | 文件 | tests |
|---|---|---|
| 类型单元 | `src/lib/types.test.ts` | 3 |
| Validator 单元 | `src/lib/validation/*.test.ts`（4 文件）| 28 |
| ThemeProvider 单元 | `src/components/theme/ThemeProvider.test.tsx` | 2 |
| Shared 组件 | `src/components/shared/shared.test.tsx` | 9 |
| Layout 组件 | `src/components/layout/AppLayout.test.tsx`（含 `renderWithProviders` helper 包装 ThemeProvider） | 2 |
| Stats 组件 | `src/components/stats/StatsPage.test.tsx` | 2 |
| Schema 组件 | `src/components/schema/SchemaTab.test.tsx` | 14 |
| Security 组件 | `src/components/security/SecurityTab.test.tsx` | 6 |
| Query 组件 | `src/components/query/QueryTab.test.tsx` | 6 |
| Security Pages | `src/pages/SecurityPages.test.tsx` | 4 |
| 集成 | `src/test/integration.test.tsx` | 3 |
| **总计** | **14 文件** | **79（73 ✅ / 6 ❌）** |

### 5.2 失败原因

6 个失败测试都是 `ThemeProvider` / `AppLayout` / `StatsPage` 的 setup 阶段：

```
TypeError: Cannot read properties of undefined (reading 'removeItem')
  window.localStorage.removeItem(STORAGE_KEY)
```

根因：jsdom 26+ 把 `localStorage` 实现为 lazy getter，在某些配置下默认 undefined。**Phase 0 修复**。

### 5.3 修复方案

```ts
// src/test/setup.ts（在 vi.mock 之前）
if (typeof window !== 'undefined' && !window.localStorage) {
  const store = new Map<string, string>()
  Object.defineProperty(window, 'localStorage', {
    value: {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => store.set(k, v),
      removeItem: (k: string) => store.delete(k),
      clear: () => store.clear(),
      key: (i: number) => Array.from(store.keys())[i] ?? null,
      get length() { return store.size },
    },
  })
}
```

### 5.4 改动量

| 类别 | 改动 | 估算 |
|---|---|---|
| 类型 + Validator 单元（31 tests） | 0 改 | 0 |
| 集成（3 tests） | 0 改 | 0 |
| ThemeProvider 测试（2 tests） | **删除文件** | 0 |
| AppLayout.test.tsx `renderWithProviders` helper | **改 helper**：删除 `<ThemeProvider>` 包装，直接用 `<MantineProvider>` | 短 |
| 组件行为测试（34 tests） | 重写 selector，部分加新场景 | 中 |
| Shared 测试（9 tests） | 重写 selector | 中 |
| **新增** notifications.test.tsx | ~3 tests | 短 |
| **新增** useModals.test.tsx | ~2 tests | 短 |
| **新增** localStorage polyfill test | 1 test | 极短 |
| **总计** | ~85-90 tests，100% 绿 | — |

### 5.5 测试范式迁移

| 之前 | 之后 |
|---|---|
| `screen.getByText('Save')` | `screen.getByRole('button', { name: 'Save' })` |
| `container.querySelector('.btn-primary')` | `screen.getByRole('button', { name: /save/i })` |
| `fireEvent.click(getByText('Delete'))` | `fireEvent.click(screen.getByRole('button', { name: /delete/i }))` |
| 快照测试 `<Component />` | 删除，纯行为测试 |
| `expect(container).toMatchSnapshot()` | 改用 `expect(screen.getByRole(...)).toBeInTheDocument()` |

---

## 6. 迁移顺序

### 6.1 9 个 Phase

| Phase | 内容 | 风险 | 预计 commits |
|---|---|---|---|
| **0** | Foundation：install + theme + providers + 删 theme/* + 修 6 测试 | 低（UI 没动）| 1 |
| **1** | Layout：AppLayout + TopBar + SideNav + QueryConsole | 中（每页依赖）| 3-4 |
| **2** | Shared：ValidationBar + ConfirmDialog（删）+ PlaceholderPage | 低 | 1-2 |
| **3** | Schema tab：6 组件 + SchemaTab.test 重写 | 中（最大模块）| 5-6 |
| **4** | Security tab：4 组件 + SecurityTab.test 重写 | 中 | 3-4 |
| **5** | Query tab：3 组件 + QueryTab.test 重写 + 丢 TanStack Table | 中（Monaco 集成）| 3-4 |
| **6** | Stats tab：5 组件 + StatsPage.test 重写 | 低 | 3-4 |
| **7** | Pages：14 文件轻调 | 低 | 2-3 |
| **8** | Tests 收尾：所有未过 component tests 修绿 + 新增 6 tests | 低 | 3-4 |
| **9** | Cleanup：卸载 tailwindcss + @tailwindcss/vite + @tanstack/react-table + 清 vite.config + 清 index.css + 更新 README | 低 | 1 |
| **总计** | — | — | **~30** |

### 6.2 工作模式

**强烈建议 git worktree 隔离**：

```bash
git worktree add ../heirloom-mantine -b feat/workshop-mantine
cd ../heirloom-mantine
```

每个 phase 一个或多个 commit，phase 之间可独立 review。Phase 8 后 squash 成几个语义 commit，再 merge 回 main。

### 6.3 验证检查点

每完成一个 phase：

```bash
# 1. 类型检查
cd workshop && npx tsc -b --noEmit

# 2. lint
npm run lint

# 3. 测试
npx vitest run

# 4. 构建
npm run build

# 5. 视觉冒烟（手动）
npm run dev
# → 访问 http://localhost:5200 验证每个已迁移页面正常渲染
```

Phase 0 后每 phase 必跑这 5 步；任一失败必须修通才能 commit。

### 6.4 工期估算

| Phase | 工时 |
|---|---|
| 0 | 0.5h |
| 1 | 1.5-2h（SideNav 重写 + AppShell 改造）|
| 2 | 0.5h |
| 3 | 2-3h（schema 组件最多 + TypeEditor 用 form 收编）|
| 4 | 1-1.5h |
| 5 | 1.5-2h（Monaco 集成 + 丢 TanStack Table）|
| 6 | 1-1.5h |
| 7 | 0.5-1h（轻调）|
| 8 | 1.5-2h（测试重写）|
| 9 | 0.5h |
| **总计** | **~12-15h 连续工作** |

> **现实约束**：单次会话不能完成。建议分 3-4 个 session：
> - Session 1：Phase 0-2（基础 + 布局 + shared，约 2-3h）
> - Session 2：Phase 3-5（schema + security + query，约 5-7h）
> - Session 3：Phase 6-7（stats + pages，约 2-3h）
> - Session 4：Phase 8-9（tests + cleanup，约 2-2.5h）

---

## 7. 风险与权衡

### 7.1 风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| 79 tests 静默失败的连锁影响 | 中 | Phase 0 修 localStorage polyfill；Phase 8 全量验证 |
| Mantine 7 主题与现有视觉差异 | 低 | §1.2 显式定义 indigo/stone 调色板对齐；Phase 1 视觉冒烟 |
| Bundle 体积 +85KB gzipped | 低 | Workshop 是内部工具，可接受；后续可 tree-shake 优化 |
| Monaco 在 Mantine `Paper` + `Group` 容器中的尺寸适配 | 低 | QueryConsole 保留 inline 模式，Monaco 在 Paper 内的 `100% height` 已验证可行；如出问题改 `flex: 1` 容器 |
| 14 个 page 隐藏的 Tailwind 依赖 | 中 | Phase 7 集中处理；如发现某 page 用了复杂 utility，单独评估 |
| 丢 TanStack Table 后 QueryResults 性能 | 低 | Mock 数据 200 行内，Mantine Table 完全够用；真实后端再说 |
| 单次会话不能完成 | 中 | worktree + 分 3-4 session |
| Phase 9 卸载 Tailwind 时漏掉某处引用 | 低 | 卸载前 `grep "className=\".*flex\\|gap-\\|p-\\|m-\\|w-\\|h-" src/` 应零结果 |

### 7.2 权衡

| 决策 | 选的方案 | 弃的方案 | 理由 |
|---|---|---|---|
| 包选型 | dates+code-highlight | 暂不引入 | FieldType 有 date；code block 高亮覆盖 placeholder 场景 |
| 包选型 | **不引入** charts/spotlight | 引入 | 暂无业务场景；避免 bundle 膨胀 |
| 主题 | 自定义 indigo/stone 调色板 | 用 Mantine 默认 indigo | 与 `website/` 营销站一致；调色板已与设计对齐 |
| 暗色模式 | 删 ThemeContext，完全交给 Mantine | 保留 React Context 桥接 | 简化架构；Mantine 内建持久化 |
| Tailwind | 立即清掉（不留过渡期） | Phase 9 才清 | 避免技术债；用户明确要求 |
| QueryConsole | **保留 inline 底部面板** | Drawer 模式 | Drawer 不支持拖拽缩放 / 3-pane 布局；inline 模式更适合 IDE 风格的 Console |
| TanStack Table | 丢 | 保留 | Workshop 数据规模小；统一 Mantine 减少包 |
| 测试快照 | 不写 | 写 | Mantine 内部 DOM 易变；行为测试更稳 |
| 工作模式 | worktree + 分 session | 直接在 main 改 | 风险太大；30+ commits 不应污染 main |

### 7.3 后续优化（不在本次范围）

- `@mantine/charts`：当 StatsPage 需要可视化时再引入
- `@mantine/spotlight`：如要加 Cmd+K 命令面板时引入
- Tree-shake Mantine：当前全量 import，可改为 `import { Button } from '@mantine/core'` 而非 `import * as`
- E2E 测试：用 Playwright 覆盖关键流程（创建 type → 跑 query → 看到结果）
- `tsconfig.app.json` 考虑开 `strict: true`

---

## 8. 验收标准

迁移完成的定义（全部满足）：

1. ✅ `npm run build` 成功，无 TS 错误
2. ✅ `npm run lint` 零 warning
3. ✅ `npx vitest run` 100% 绿（~85-90 tests）
4. ✅ 14 个 page 都能正常加载、渲染、操作（注：`App.tsx` 实际声明 22 条 `path`，其中 6 个 `PlaceholderPage` 共享同一组件 + 1 个 `/objects` → `/explorer` 重定向）
5. ✅ Monaco 编辑器在 QueryEditor + QueryConsole（inline 底部面板）中正常工作
6. ✅ xyflow 图在 OntologyGraph + StateMachineEditor 中正常渲染、深色模式颜色正确
7. ✅ 暗色模式切换：localStorage 持久化 + 跨页同步 + 无 FOUC
   - TopBar 的 `useTheme` 替换为 `useMantineColorScheme`，sun/moon 图标根据 `computedColorScheme` 渲染
   - SettingsPage 的 theme `<select>` 替换为 `SegmentedControl`（light/dark/auto）或 `Select`
8. ✅ 通知系统：错误 mutation 触发红色 toast
9. ✅ `git grep "className=\"" workshop/src/ | grep -E "flex|gap-|p-|m-|w-|h-|bg-|text-|border-"` 零结果
10. ✅ `package.json` 无 `tailwindcss` / `@tailwindcss/vite` / `@tanstack/react-table`
11. ✅ `vite.config.ts` 不引入 `tailwindcss()` plugin
12. ✅ `index.css` 不含 `@import "tailwindcss"` 或 `@variant dark`
13. ✅ `src/components/theme/` 目录不存在
14. ✅ `src/hooks/useTheme.ts` 不存在
15. ✅ `src/api/client.ts` 不存在
16. ✅ `src/lib/config.ts` 不存在（其内容已并入 `src/lib/constants.ts`）
17. ✅ README 更新到 Mantine 主题

---

## 9. 不在范围内

- Workshop 的功能扩展（新增 route / 新概念）
- 真实后端切换（保持 MSW mock 模式）
- 国际化 i18n（保持中英混排现状）
- 性能优化（不引入 virtual list，不做 code split）
- 移动端深度优化（响应式基础可，不做 PWA）
- E2E 测试（用手动冒烟 + 单测覆盖）
- CI/CD（无 GitHub Actions 配合）

---

**Spec 完成。下一步：dispatch spec-document-reviewer 审稿。**
