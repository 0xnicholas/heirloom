# Open Ontology 前端截图分析

> 分析对象：`screenshots/openontology-demo.png`
> 更新日期：2026-06-28

---

## 1. 截图概述

这是一张 Open Ontology 的 Web UI 截图，展示的是 `demo-hr` 数据库的 **Stats（统计）页面**。整体风格简洁、信息密度适中，采用经典的三栏式布局：左侧导航栏、顶部操作栏、中央内容区。

---

## 2. 布局结构

```
┌─────────────────────────────────────────────────────────────┐
│  Open Ontology    demo-hr ▼                    API Docs  🌙  │  ← 顶部栏
├──────────┬──────────────────────────────────────────────────┤
│  Stats   │  ELI5 解释卡片                                    │
│  Schema  │                                                   │
│  Explorer│  1570 Triples  217 Entities  6 Object Types ...  │
│  Builder │                                                   │
│  Attributes│                                                 │
│  Objects │  ┌──────────────┐  ┌─────────────────────────┐   │
│  Links   │  │ Object Types │  │                         │   │
│  Actions │  │  • Course    │  │    关系图可视化          │   │
│  Rules   │  │  • Department│  │    (React Flow)          │   │
│  ...     │  │  • Employee  │  │                         │   │
│          │  │  Link Types  │  │                         │   │
│          │  │  → assigned  │  │                         │   │
│          │  └──────────────┘  └─────────────────────────┘   │
└──────────┴──────────────────────────────────────────────────┘
```

---

## 3. 左侧导航栏分析

导航栏把系统的核心概念直接暴露为一级入口：

| 入口 | 说明 | 对应 Heirloom 概念 |
|------|------|-------------------|
| **Stats** | 统计仪表盘 | Resource/关系概览 |
| **Schema** | 本体/schema 定义 | Schema Registry |
| **Explorer** | 数据探索器 | Resource 查询/浏览 |
| **Builder** | 构建工具 | Resource Type/Action 定义 |
| **Attributes** | 属性管理 | Property |
| **Objects** | 实体实例列表 | Resource 实例 |
| **Links** | 关系管理 | Relationship |
| **Actions** | 操作定义 | Action |
| **Rules** | 规则/约束 | Constraint（Heirloom 尚未有） |
| **Violations** | 违规检测 | 审计/告警 |
| **Tasks** | 任务队列 | Automation/人工审批 |
| **Forms** | 表单定义 | Workshop 表单 |
| **Inbox** | 收件箱/通知 | Notification |
| **Queries** | 查询管理 | Query/Function |
| **Console** | 控制台/REPL | Agent/开发者工具 |
| **Chat** | 聊天界面 | Agent 交互 |
| **Settings** | 设置 | 平台配置 |

### 对 Heirloom 的启示

1. **概念即导航**：每个核心原语都有独立入口，降低用户认知成本；
2. **Stats 作为首页**：先让用户看到「系统里有什么」，再深入操作；
3. **Chat/Console 并列**：把 Agent 交互和开发者工具放在同一层级，体现 Agent 是一等消费者。

---

## 4. 顶部栏分析

- **Logo + 产品名**：强化品牌；
- **数据库选择器 `demo-hr 4.8 MB`**：清晰的上下文切换，显示数据规模；
- **API Docs**：文档入口放在显著位置；
- **主题切换**：深色/浅色模式。

### 对 Heirloom 的启示

- **Workspace/租户选择器**：Heirloom 的 Workshop 也需要显式的 Ontology/Workspace 上下文切换；
- **API Docs 常驻**：开发者友好，符合「人类和 Agent 共享同一接口」的设计理念；
- **数据规模提示**：显示 Resource 数量、关系数量等摘要信息，增强掌控感。

---

## 5. Stats 仪表盘分析

### 5.1 ELI5 解释卡片

截图顶部有一张解释卡片：

> "This is like your dashboard - it shows you how much stuff is in your database! You can see counts of all your things (like people, items, relationships) and even a cool picture that shows how everything connects together."

右上角有 **Simple (ELI5)** 下拉框，暗示可能有不同解释级别（Simple/Technical/Expert）。

### 对 Heirloom 的启示

1. **分层解释**：同一界面为不同角色提供不同详细程度的说明；
2. **降低本体门槛**：用日常语言解释抽象概念，帮助非技术业务用户理解；
3. **Heirloom 可应用**：在 Schema Registry、Action Pipeline、Abilities 等复杂概念处加入 ELI5 提示。

### 5.2 核心指标卡片

```
1570 Triples    217 Entities    6 Object Types    6 Link Types    84 Attributes
```

Open Ontology 用 triple/entity/object type/link type/attribute 五个维度概括系统状态。

### 对 Heirloom 的启示

Heirloom 可以设计对应的指标：

```
X Resource Types    Y Resource Instances    Z Relationships    N Actions    M Functions
```

或者更贴近业务：

```
X 业务实体类型    Y 实体实例    Z 关系    N 操作    M 角色
```

### 5.3 Object Types / Link Types 列表

左侧列表显示：

- **Object Types（6）**：Course, Department, Document, Employee, Equipment, Training，每个标注 `v1`；
- **Link Types（6）**：assigned-equipment, enrolled-in, has-document, heads-department 等，显示源 → 目标类型。

### 对 Heirloom 的启示

1. **版本号外露**：`v1` 提示 schema 有版本历史，与 Heirloom 的 Proposal/版本治理一致；
2. **关系类型展示**：不仅列出关系名，还显示 `Employee → Equipment` 这样的方向性，帮助理解模型；
3. **可折叠分组**：Object Types 和 Link Types 可折叠，信息层级清晰。

---

## 6. 关系图可视化分析

截图右侧是一个交互式关系图，使用 **React Flow** 实现（右下角水印可见）。

### 6.1 图的元素

- **节点**：Department, Employee, Course, Equipment, Training, Document 等；
- **边**：heads-department, reports-to, manages, works-in, has-document, enrolled-in, assigned-equipment 等；
- **节点标签**：显示类型名和版本 `v1`；
- **边标签**：显示关系名；
- **布局选项**：3D View, Schema, Dagre (Hierarchical), Top-Down, Curved, Spacious。

### 6.2 布局控制

| 控制 | 作用 |
|------|------|
| **3D View** | 三维展示 |
| **Schema** | schema 视图 |
| **Dagre (Hierarchical)** | 层次布局 |
| **Top-Down** | 自上而下 |
| **Curved** | 曲线边 |
| **Spacious** | 宽松布局 |

### 对 Heirloom 的启示

1. **本体可视化是必需功能**：用户需要直观地看到 Resource Type 之间的关系；
2. **多种布局**：不同用户偏好不同布局，应提供多种选项；
3. **关系语义可视化**：Heirloom 的 Ownership/Reference/Association 三种关系可以用不同颜色/线型区分，这是强差异化卖点；
4. **React Flow 是可行技术选型**：成熟、灵活、与 React 生态兼容。

---

## 7. 整体设计风格

- **浅色主题、低饱和度**：专业、不刺眼；
- **信息密度适中**：没有过度拥挤；
- **图标 + 文字导航**：左侧导航既有图标又有文字，降低学习成本；
- **卡片式布局**：内容区分清晰；
- **React Flow 集成**：图与列表并列，形成「概览 + 细节」模式。

---

## 8. 对 Heirloom Workshop 前端的具体建议

基于这张截图，Heirloom 的 Workshop 可以考虑以下设计：

### 8.1 首页：Stats Dashboard

```
┌─────────────────────────────────────────────────────────────┐
│  Heirloom Workshop    [Ontology: demo-hr ▼]    API Docs  🌙  │
├──────────┬──────────────────────────────────────────────────┤
│  Stats   │  ELI5: "这是你企业的业务世界地图..."              │
│  Schema  │                                                   │
│  Explorer│  6 Resource Types  217 Resources  12 Actions ... │
│  Builder │                                                   │
│  Objects │  ┌──────────────┐  ┌─────────────────────────┐   │
│  Actions │  │ Resource     │  │                         │   │
│  Roles   │  │ Types        │  │   关系图可视化           │   │
│  Functions│ │  • Customer  │  │   (Ownership=红,        │   │
│  Events  │  │  • Order     │  │    Reference=蓝,        │   │
│  Queries │  │  • Product   │  │    Association=灰)      │   │
│  Audit   │  │  Relationships│  │                         │   │
│  Agent   │  │  → owns      │  │                         │   │
│  Console │  │  → references│  │                         │   │
│  Settings│  └──────────────┘  └─────────────────────────┘   │
└──────────┴──────────────────────────────────────────────────┘
```

### 8.2 导航设计

把 Heirloom 核心概念映射为一级导航：

| Heirloom 概念 | Workshop 导航入口 |
|--------------|------------------|
| Resource Type | Schema / Resource Types |
| Resource 实例 | Objects / Explorer |
| Relationship | Relationships |
| Action | Actions |
| Function | Functions |
| Role | Roles |
| Event Log | Audit / Events |
| Query Resolver | Queries |
| Agent SDK | Agent Console |

### 8.3 关系图必须表达 Heirloom 的三级语义

这是 Heirloom 相对于 Open Ontology 的独特点，必须在 UI 上强化：

- **Ownership**：实线 + 红色 + 箭头指向被拥有方；
- **Reference**：虚线 + 蓝色 + 箭头指向被引用方；
- **Association**：点线 + 灰色 + 无箭头或双向箭头。

hover 时显示：

> "Customer owns Order：删除 Customer 会级联处理其 Order。"

### 8.4 ELI5 解释层

在以下场景提供分层解释：

- Resource Type 页面：「这是什么业务对象？」
- Abilities 页面：「这个类型允许发生什么？」
- Action 页面：「这个操作会改变什么？」
- Relationship 页面：「删除一方会怎样？」

### 8.5 版本外露

每个 Resource Type 显示当前版本（如 `v1`），点击可查看版本历史和 Proposal 记录。

---

## 9. 可进一步验证的问题

由于只有一张截图，以下问题需要更多信息：

1. Schema 编辑器的具体交互方式（表单？代码？可视化拖拽？）；
2. Action/Process 的定义界面；
3. Workspace/View 的声明式 UI 如何实现；
4. Chat 界面与 Agent 的交互模式；
5. Console 是 Datalog REPL 还是通用命令行。

---

## 10. 结论

Open Ontology 的前端设计遵循「**概念即导航、概览即首页、关系即可视化**」的原则。它的 Stats 页面是一个有效的 Workshop 首页参考，强调：

1. 用简单语言解释复杂概念；
2. 用指标卡片给出系统整体状态；
3. 用左侧导航把核心原语直接暴露；
4. 用关系图让本体模型可感知。

Heirloom 的 Workshop 可以借鉴这种布局，但应在关系图、Abilities 展示、Action 流水线可视化上做出自己的差异化，特别是把 **Ownership/Reference/Association 三级语义** 作为视觉核心。
