# Spec: 核心内核硬化 — 语义原语约束动力学原语

**日期**: 2026-07-01
**关联 ADR**: 002, 003, 005, 007, 008
**状态**: Draft v5
**范围**: 三件事——(1) 把砍掉的 Resource Store 加回来，(2) 让语义原语在代码中真正约束动力学原语，(3) 让查询引擎从拼字符串变成有结构的东西

---

## 0. 问题陈述

当前代码库和设计文档之间存在一个断层。

**设计说**：语义原语定义世界，动力学原语只能做语义原语允许的事。Ability 是类型契约。State Machine 是硬边界。Action 必须通过九步校验流水线。Agent 无法绕过——不是依赖 prompt 告诉它不能做什么，而是系统结构上无法表达「不该做」的事。

**代码现状**：

| 设计承诺 | 代码现实 |
|---------|---------|
| Resource 是一等实体，有 RID、状态、完整事件历史 | `Resource` 类不存在。Phase 0.2 被标记 out of scope |
| Action 定义时校验 `requiredAbility` 是否在 target type 的 abilities 中 | `ActionRepository.prepareInternal()` 是空方法 |
| Action 定义时校验 `stateGate` 是否对应状态机的合法迁移 | 无代码 |
| 九步校验流水线 | 不存在。仅 `RoleBasedAuthorizer`（60 行字符串匹配） |
| Capability 是访问特定 Action 的通行证 | Capability 是 `(entityType, operation)` 字符串元组，与 Action 无连接 |
| 状态机运行时阻止非法迁移 | 只有 TypeValidator 的孤立节点 warning |
| Query Resolver 是语义查询引擎 | 130 行字符串拼 SQL |

**本 Spec 目标**：6 次迭代，每次可独立验证，每次改动后现有 192 个测试继续通过。

---

## 1. 迭代总览

| # | 主题 | 核心产出 | 依赖 | 工作量 |
|---|------|---------|------|--------|
| **I-0** | Resource Store | `Resource` 实体 + CRUD API + 状态跟踪 | 无 | 5-7 天 |
| **I-1** | Action 定义时校验 | `ActionValidator` — ADR-007 三条规则 | 无（独立于 I-0） | 3-4 天 |
| **I-2** | 状态机运行时 guard | `StateMachineGuard` — 执行前状态校验 | I-0, I-1 | 2-3 天 |
| **I-3** | 九步校验流水线 + Capability 硬化 | `ActionPipeline` + 类型安全 Capability | I-0, I-1, I-2 | 8-10 天 |
| **I-4** | Capability 数据迁移 | 旧格式→新格式 JSON 迁移 + 兼容解析 | I-3 | 1-2 天 |
| **I-5** | 查询引擎结构化 | Parser + 参数化 SQL 生成 | 无（独立） | 2-3 天 |

---

## 2. Iteration 0: Resource Store

**目标**：让 Resource 不再只是一份设计文档里的概念。实现 Resource 实例的存储、查询、状态管理。这是后续所有 Action 流水线工作的基础——没有 Resource 实例，就无可执行的 Action。

### 2.1 设计决策

**为什么需要 Resource Store？** Phase 0.2 曾被标记 out of scope——理由是「Resource 实例存在于外部数据源，Heirloom 是语义层」。但 Action 流水线的核心操作（读当前状态、写新状态、版本控制、乐观锁）需要一个 Heirloom 可控的锚点。外部数据源不保证有 state 列、不保证有 version 列、不保证 Heirloom 能写入。

**不是推翻 descope，而是澄清边界**：
- Heirloom Resource Store：存语义元数据（RID、type、owner、state、version、事件引用）
- Heirloom fields JSONB：存业务数据副本（可选，用于统一查询）
- 外部数据源：业务数据的权威来源，通过 Mapping Engine 连接

一条 Resource 记录是外部数据的语义锚点——Heirloom 通过它知道「customer-456 当前处于 Active 状态、版本号 12」，而 `customer-456` 的详细业务字段可能来自 PostgreSQL 也可能来自 API。Action 执行时改的是这个锚点（state、version），业务字段的变更通过 Mapping Engine 写回外部源。

### 2.2 数据库 Schema

```sql
-- V14: Resource instances
CREATE TABLE heirloom_resources (
    rid             VARCHAR(256) PRIMARY KEY,
    resource_type   VARCHAR(128) NOT NULL REFERENCES resource_types(name),
    owner           VARCHAR(256),  -- 预留字段：Actor/Agent 标识。不做 FK，权限由 I-3 Action 流水线处理
    current_state   VARCHAR(64)  NOT NULL,
    fields          JSONB        NOT NULL DEFAULT '{}',
    version         BIGINT       NOT NULL DEFAULT 0,
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_resources_type  ON heirloom_resources(resource_type);
CREATE INDEX idx_resources_owner ON heirloom_resources(owner);
CREATE INDEX idx_resources_state ON heirloom_resources(resource_type, current_state);
CREATE INDEX idx_resources_fields_gin ON heirloom_resources USING GIN (fields jsonb_path_ops);
```

**RID 格式**：`{domain}.{typeName}.{8-char base62}`，例：`default.Customer.a1B2c3D4`。生成逻辑：`domain + "." + typeName + "." + NanoId(8)`。

**version 字段**：乐观锁。每次更新时 `WHERE version = :expectedVersion`，update 成功后 `SET version = version + 1`。冲突时返回 409。

**fields JSONB**：存储 Resource 的业务字段。字段名和类型由 ResourceType 定义约束。支持 GIN 索引用于属性过滤查询。

### 2.3 代码产物

| 文件 | 操作 | 说明 |
|------|------|------|
| `domain/Resource.java` | 新建 | JPA 实体：rid, resourceType, owner, currentState, fields, version |
| `repository/ResourceJpaRepository.java` | 新建 | Spring Data JPA：findByRid, findByResourceType, findByOwner |
| `repository/ResourceRepository.java` | 新建 | 继承 `EntityRepository<Resource>`，乐观锁更新，状态迁移 |
| `service/ResourceService.java` | 新建 | 业务逻辑：创建时校验字段/类型，更新时校验状态迁移合法性 |
| `service/ResourceValidationException.java` | 新建 | 校验失败异常 |
| `web/ResourceResource.java` | 新建 | REST 端点 |
| `db/migration/V14__resource_store.sql` | 新建 | Flyway 迁移 |
| `schema/domain/ResourceType.java` | 修改 | 新增 `initialState` 字段（VARCHAR(64)）；新增 `@Column(name="initial_state")` 映射 |
| `schema/service/TypeValidator.java` | 修改 | 新增两条校验：(1) 状态机非空时必须声明 initialState，(2) initialState 必须是状态机中的合法状态 |
| `db/migration/V15__resourcetype_initial_state.sql` | 新建 | Flyway 迁移：resource_types 表加 initial_state 列 |

**Migration 编号**：I-0 占 V14（resource 表）+ V15（initialState 列）。I-1 使用 V16。

### 2.4 REST API

```
POST /v1/resources
{
  "resourceType": "Customer",
  "owner": "agent-123",
  "fields": {
    "name": "Acme Corp",
    "tier": "gold",
    "email": "acme@example.com"
  }
}
→ 201
{
  "rid": "default.Customer.a1B2c3D4",
  "resourceType": "Customer",
  "owner": "agent-123",
  "currentState": "Draft",
  "fields": { "name": "Acme Corp", "tier": "gold", "email": "acme@example.com" },
  "version": 0,
  "createdAt": "2026-07-01T12:00:00Z"
}
// currentState 的值来自 ResourceType.initialState，本例中 Customer 类型声明 initialState="Draft"
```

```
GET /v1/resources/default.Customer.a1B2c3D4
→ 200 { ...同上... }
```

```
PUT /v1/resources/default.Customer.a1B2c3D4
If-Match: 0
{
  "fields": { "tier": "platinum" }
}
→ 200 { ...fields.tier: "platinum", version: 1... }

If version mismatch:
→ 409 { "error": "Version conflict: expected 0, current 1" }
```

```
GET /v1/resources?type=Customer&state=Active&fields.tier=gold&limit=20
→ 200 { "items": [...], "total": 47 }
```

```
PATCH /v1/resources/default.Customer.a1B2c3D4/state
{
  "targetState": "Active"
}
→ 200 { "previousState": "Draft", "currentState": "Active", "version": 2 }
```

### 2.5 创建时校验

`ResourceService.create()` 执行：

1. `resourceType` 必须在 Schema Registry 中存在 → 否则 400
2. `fields` 的 key 必须是 ResourceType.fields 中声明的字段 → 多余字段 400
3. `fields` 的 value 类型必须匹配 FieldType（基础校验：String/Number/Boolean）→ 类型不匹配 400
4. `currentState` 设为 ResourceType 的 `initialState`（若类型未声明 initialState 则拒绝——不依赖 DB 默认值。应用层完全控制 state 值）

**注意**：ResourceType.fields 当前没有 `required` 属性，故创建时不校验「必填字段缺失」。待 Field 模型增加 required 后补充该校验（follow-up task，不阻塞本 iteration）。
5. 写入 Event Log（eventType=RESOURCE_CREATED）

### 2.6 状态迁移校验

`ResourceService.transitionState()` 执行：

1. 读 Resource 当前 `currentState`
2. 在 ResourceType 的状态机中查找 `from=currentState, to=targetState` 的迁移边
3. 如果不存在该边 → 409，错误消息包含 currentState、targetState 和该类型的所有合法迁移
4. 如果存在 → 更新 `currentState`，version+1，写 Event Log（eventType=STATE_TRANSITION）

### 2.7 验证标准

- [ ] 创建合法 Resource → 201，RID 格式正确，initialState 正确
- [ ] 创建时 resourceType 不存在 → 400，错误消息包含类型名
- [ ] 创建时 fields 包含未声明字段 → 400，错误消息列出多余字段
- [ ] 创建时 fields 类型不匹配 → 400
- [ ] 创建时 ResourceType 未声明 initialState → 400，提示完善类型定义
- [ ] GET by RID → 200，包含所有字段
- [ ] PUT 更新 fields（正确的 version）→ 200，version 递增
- [ ] PUT 更新 fields（错误的 version）→ 409
- [ ] PATCH state 合法迁移 → 200，currentState 更新
- [ ] PATCH state 非法迁移 → 409，错误消息包含合法迁移列表
- [ ] GET list 按 type + state + 字段值过滤 → 正确分页
- [ ] 创建/更新/状态迁移均写入 Event Log
- [ ] 现有 192 个 server tests 继续通过（新增迁移不影响已有表和数据）
- [ ] 10 个新单元测试 + 5 个集成测试

### 2.8 约束

**Out of scope (本 iteration)**：
- 级联删除（依赖 Relationship 的 Ownership 语义 → 未来 iteration）
- 权限检查（→ I-3 的 Action 流水线处理）
- 批量操作
- Resource 间关系存储（relationship edges → 单独的 Graph Store iteration）
- **KnowledgeArticle 的 status 迁移到 Resource Store**：KnowledgeArticle 的 `status`（draft→review→published→archived）是知识条目的独立生命周期，通过 `KnowledgeWorkflowService` 管理，与 Resource 的通用 `currentState` 并行存在。未来 iteration 决定是否统一，本 iteration 不碰 KnowledgeArticle

**安全注意**：I-0 的 Resource REST 写入端点（POST/PUT/PATCH）当前无权限检查——这是故意的，I-0 是原始数据层。I-3 完成后，这些写入端点应限制为 admin/system 角色或直接关闭，强制所有写入通过 Action 流水线。读端点（GET）保持开放。记录为 I-3 完成后的安全加固 follow-up。

**SDK 影响**：Python SDK 需新增 `client.resources.create/update/get/list/transition_state` 方法。不在 I-0 范围内，记录为 I-3 完成后的 follow-up task。

**Workshop UI 影响**：Schema tab 需新增 Resource 实例管理面板。不在 I-0 范围内。

---

## 3. Iteration 1: Action 定义时校验

**目标**：让 ADR-007 的三条规则在 Action 创建/更新时生效。不合法的 Action 在 Schema Registry 层面就被拒绝，不等到运行时。

### 3.1 原则

**不改现有列类型，不加破坏性迁移。** 旧 Action 的 `requiredAbility`（String）、`stateGate`（裸 String）继续存在。新增结构化列。校验逻辑同时支持新旧两种格式。

### 3.2 数据库迁移

```sql
-- V16: Action structured columns
ALTER TABLE security_actions
  ADD COLUMN IF NOT EXISTS target_type_fqn  VARCHAR(256),
  ADD COLUMN IF NOT EXISTS required_ability VARCHAR(32),  -- Ability enum name
  ADD COLUMN IF NOT EXISTS state_gate_json  JSONB,        -- StateGate record
  ADD COLUMN IF NOT EXISTS input_schema_json JSONB;       -- ActionInput[] record

-- 旧列保留 deprecated，不删除
-- requiredAbility (旧 String) 仍可读，向后兼容
-- stateGate (旧 String) 仍可读，向后兼容
```

### 3.3 新结构化 model

```java
// StateGate: 替换旧 String "state = Active"
public record StateGate(
    String fromState,    // 资源必须在此状态下
    String toState       // 执行后目标状态，null = 不指定（由调用者决定）
) {}

// ActionInput: 替换旧 JSONB String
public record ActionInput(
    String fieldName,    // 字段名
    FieldType fieldType, // 类型
    boolean required     // 是否必填
) {}
```

### 3.4 代码产物

| 文件 | 操作 | 说明 |
|------|------|------|
| `security/domain/Action.java` | 修改 | 新增 targetTypeFQN, requiredAbilityEnum, stateGateJson, inputSchemaJson；保留旧 getter/setter |
| `security/domain/StateGate.java` | 新建 | record，含 from/to 状态 |
| `security/domain/ActionInput.java` | 新建 | record，含字段名/类型/必填标记 |
| `security/validation/ActionValidator.java` | 新建 | 三条规则的实现 |
| `security/validation/ActionValidationException.java` | 新建 | 校验失败异常 |
| `schema/domain/StateMachine.java` | 新建 | 共享工具类：`isValidState()`, `isValidTransition()`, `allStates()`, `transitionsFrom()`——供 I-0、I-1、I-2 共用 |
| `repository/ActionRepository.java` | 修改 | `prepareInternal()` 调用 `ActionValidator.validate()` |
| `db/migration/V16__action_structured_columns.sql` | 新建 | Flyway 迁移 |

**辅助 getter 方法**：`Action.java` 新增三个桥接方法——`getRequiredAbility()`（优先读新列 `requiredAbilityEnum`，fallback 解析旧 String）、`getStateGate()`（优先读 `stateGateJson`，fallback 解析旧 String）、`getInputSchema()`（读 `inputSchemaJson`，fallback 返回空列表）。

### 3.5 校验逻辑

```java
// ActionValidator.validate(Action action)
// 内部通过 typeRepo 加载 targetType，调用者只需传入 Action 对象
public void validate(Action action) {
    ResourceType targetType = typeRepo.findByFQN(action.getTargetTypeFQN())
        .orElseThrow(() -> new ActionValidationException(
            "Target type '%s' not found in Schema Registry"
                .formatted(action.getTargetTypeFQN())));

    // 规则一：Ability 门禁
    Ability required = action.getRequiredAbility();
    if (!targetType.getAbilities().contains(required)) {
        throw new ActionValidationException(
            "Action '%s' requires '%s' but type '%s' does not declare it. Declared: %s"
                .formatted(action.getName(), required, targetType.getName(),
                           targetType.getAbilities()));
    }

    // 规则二：State 门禁（仅当 stateGate 非空时检查）
    StateGate gate = action.getStateGate();
    if (gate != null) {
        if (!StateMachine.isValidState(targetType, gate.fromState())) {
            throw new ActionValidationException(
                "StateGate fromState '%s' is not a valid state for type '%s'. Valid: %s"
                    .formatted(gate.fromState(), targetType.getName(),
                               StateMachine.allStates(targetType)));
        }
        if (gate.toState() != null
            && !StateMachine.isValidTransition(targetType, gate.fromState(), gate.toState())) {
            throw new ActionValidationException(
                "StateGate transition '%s → %s' is not defined in type '%s' state machine. Valid from '%s': %s"
                    .formatted(gate.fromState(), gate.toState(), targetType.getName(),
                               gate.fromState(), StateMachine.transitionsFrom(targetType, gate.fromState())));
        }
    }

    // 规则三：类型一致性
    if (action.getInputSchema() != null) {
        for (ActionInput input : action.getInputSchema()) {
            Field declared = targetType.getFields().stream()
                .filter(f -> f.name().equals(input.fieldName()))
                .findFirst()
                .orElseThrow(() -> new ActionValidationException(
                    "Input field '%s' not declared on type '%s'"
                        .formatted(input.fieldName(), targetType.getName())));
            if (input.fieldType() != declared.type()) {
                throw new ActionValidationException(
                    "Input field '%s' type mismatch: declared %s, action expects %s"
                        .formatted(input.fieldName(), declared.type(), input.fieldType()));
            }
        }
    }
}
```

### 3.6 验证标准

- [ ] 创建 `requiredAbility=DROP`，target 类型无 DROP → 400，错误消息含类型名、能力名、该类型声明的能力列表
- [ ] 创建 `stateGate.fromState="Draft"`，类型无此状态 → 400，错误消息含该类型所有合法状态
- [ ] 创建 `stateGate` 指定 `Draft → Archived`，状态机无此边 → 400，错误消息含从 Draft 出发的所有合法迁移
- [ ] 创建 `targetType` 不存在 → 400
- [ ] 创建 `inputSchema` 含未声明字段 → 400
- [ ] 创建 `inputSchema` 字段类型不匹配 → 400
- [ ] 创建合法 Action（全部通过）→ 201
- [ ] 已有 Action 不受影响（旧 String 列保持不变，新列为 null）
- [ ] **现有 192 个 server tests 继续通过**
- [ ] 8 个新单元测试（每条拒绝路径 + 1 个成功）

---

## 4. Iteration 2: 状态机运行时 Guard

**目标**：在 Resource 实例上执行状态迁移前，用 I-1 建立的 `StateMachine` 共享工具类做运行时校验。I-1 检查的是「这个 Action 的 stateGate 定义是否合法」，I-2 检查的是「这个具体的 Resource 实例此刻是否处于允许执行的状态」。

### 4.1 与 I-0 和 I-1 的关系

- **I-0** 提供了 Resource 实例（`I-0.ResourceService` 存储和读取 `currentState`）
- **I-1** 提供了 `StateMachine` 工具类和 `StateGate` record
- **I-2** 不重复这两个——它组合它们

### 4.2 代码产物

| 文件 | 操作 | 说明 |
|------|------|------|
| `schema/guard/StateMachineGuard.java` | 新建 | 核心 guard：`guard(ResourceType, currentState, StateGate)` |
| `schema/guard/StateGuardException.java` | 新建 | 状态校验失败异常 |
| `service/ResourceService.java` | 修改 | `transitionState()` 调用 `StateMachineGuard` |
| `web/ResourceResource.java` | 修改 | state PATCH 端点的错误响应整合 guard 消息 |

### 4.3 核心逻辑

```java
public class StateMachineGuard {

    /**
     * Guard a direct state transition. Caller (ResourceService) is responsible
     * for resolving the effective current state before calling this method.
     * If the resource has no explicit state, ResourceService applies
     * ResourceType.initialState as the fallback BEFORE calling guard.
     */
    public static void guardTransition(ResourceType type, String currentState,
                                        String toState) {
        if (!StateMachine.isValidTransition(type, currentState, toState)) {
            throw new StateGuardException(
                "Transition '%s → %s' is not allowed for type '%s'. Valid from '%s': %s"
                    .formatted(currentState, toState, type.getName(),
                               currentState, StateMachine.transitionsFrom(type, currentState)));
        }
    }

    public static void guardGate(ResourceType type, String currentState,
                                  StateGate gate) {
        // fromState 必须在状态机中
        if (!StateMachine.isValidState(type, gate.fromState())) {
            throw new StateGuardException(
                "StateGate fromState '%s' is not a valid state for type '%s'"
                    .formatted(gate.fromState(), type.getName()));
        }

        // 当前状态必须匹配 gate.fromState
        if (!currentState.equals(gate.fromState())) {
            throw new StateGuardException(
                "Resource is in state '%s', but action requires '%s'"
                    .formatted(currentState, gate.fromState()));
        }

        // 如果 gate 指定了 toState，检查迁移合法性
        if (gate.toState() != null) {
            guardTransition(type, currentState, gate.toState());
        }
    }
}
```

### 4.4 集成到 ResourceService

`ResourceService` 在调用 guard 之前负责确定 `effectiveCurrentState`：

```java
// ResourceService.transitionState() / executeAction()
String effectiveCurrent = resource.getCurrentState();
if (effectiveCurrent == null) {
    effectiveCurrent = resourceType.getInitialState();  // fallback
}
StateMachineGuard.guardTransition(resourceType, effectiveCurrent, toState);
// 通过 → 继续更新
// 失败 → 抛出 StateGuardException → 全局异常处理返回 409 + Event Log
```

### 4.5 验证标准

- [ ] Resource 在 `Draft`，transition 到 `Active`（合法边）→ 200
- [ ] Resource 在 `Draft`，transition 到 `Frozen`（非法边）→ 409，错误消息列出从 Draft 出发的合法迁移
- [ ] Resource 在 `Draft`，guardGate 要求 `fromState=Active` → 拒绝，错误消息指出当前状态
- [ ] 拒绝事件写入 Event Log（eventType=`STATE_GUARD_DENIED`）
- [ ] **现有 192 个 server tests 继续通过**（注意：I-0 新增的 Resource 相关测试如果依赖 state transition，可能需要更新）
- [ ] 5 个新单元测试

---

## 5. Iteration 3: 九步校验流水线

**目标**：实现 ADR-005 的完整流水线。这是 Heirloom 安全模型的运行时骨架——所有写入操作经过这九步。

### 5.1 与前几个 iteration 的关系

- **I-0** 提供了 Resource 实例（流水线 Step 5 读取 state，Step 7 写入变更）
- **I-1** 提供了 ActionValidator（Action 定义在创建时已经过校验，流水线假设所有已注册的 Action 定义合法）
- **I-2** 提供了 StateMachineGuard（流水线 Step 5 调用它）
- **I-3** 不重复这些——它编排它们

### 5.2 Scope

九步完整实现：

| 步骤 | 名称 | 做什么 | 失败时 HTTP | Event Log |
|------|------|--------|-------------|-----------|
| 1 | **Auth** | 解析 `X-Actor-Type` / `X-Actor-Id` header → Actor 对象 | 401 | `AUTH_DENIED` |
| 2 | **Role** | 查 Actor 的 Role（从 RoleRepository） | 403 | `ROLE_DENIED` |
| 3 | **Capability** | 从 Role 派生有效的 Capability 列表（纯查询，不拒绝） | — | — |
| 4 | **Gate** | 校验 Capability 是否覆盖 Action 的 requiredAbility + targetType；列表为空则拒绝 | 403 | `GATE_DENIED` |
| 5 | **State** | 读 Resource 当前 state，调 StateMachineGuard | 409 | `STATE_DENIED` |
| 6 | **Validate** | 执行 Action 的 validationRules（SpEL 表达式） | 422 | `VALIDATION_FAILED` |
| 7 | **Execute** | 写入变更（details in §5.6） | 500 | `EXECUTION_FAILED` |
| 8 | **Event** | 追加不可变事件（成功或被拒都写） | —（非致命） | `ACTION_INVOKED` / `ACTION_DENIED` |
| 9 | **Notify** | 发 Spring ApplicationEvent → 下游 Automation 预留 | —（非致命） | — |

### 5.3 设计决策：不用每个 Step 一个类

ADT 评审中指出原本 spec 的 16 个文件存在过度抽象。本 v3 改为：

```
ActionPipeline.java              — 编排器，内含 9 个 private 方法
PipelineContext.java             — 上下文 dto
PipelineResult.java              — 结果 dto
PipelineRejection.java           — 流水线拒绝异常（携带 step 编号 + reason）
CapabilityResolver.java          — 接口（抽象 Step 3-4 的能力查询）
TypeSafeCapabilityResolver.java  — 唯一实现（类型安全匹配，从第一天就用这个）
ActionResource.java              — REST 端点
ActionService.java               — 业务层
```

**8 个文件，不是 16 个。** 九步中有八步是直线逻辑（读 header、查 DB、调 guard、写 event log），不需要多态。`CapabilityResolver` 在 I-3 就是类型安全的——数据源是 `RoleCapabilityCache`（读 Role.capabilities JSONB），不查 Capability 表。I-4 只做 Role JSON 格式升级 + 兼容解析，不改变 resolver 实现。

### 5.4 代码产物

| 文件 | 操作 | 说明 |
|------|------|------|
| `security/pipeline/ActionPipeline.java` | 新建 | 编排器，9 个 private 方法 + 1 个 public `execute()` |
| `security/pipeline/PipelineContext.java` | 新建 | 上下文：action, actor, targetResource, inputParams, 中间状态, 计时 |
| `security/pipeline/PipelineResult.java` | 新建 | 结果：status, steps[], deniedAtStep, reason, result, durationMs |
| `security/pipeline/CapabilityResolver.java` | 新建 | 接口：`List<Capability> resolve(Actor, Ability, String resourceType)` |
| `security/pipeline/TypeSafeCapabilityResolver.java` | 新建 | 唯一实现——包装 `RoleCapabilityCache`，解析 Role JSON 中的 capabilities 为新旧双格式，按 Ability + resourceType 匹配（见 §6.1） |
| `security/pipeline/PipelineRejection.java` | 新建 | 流水线拒绝异常：step 编号 + reason + 是否写入 Event Log |
| `security/service/ActionService.java` | 新建 | 业务层：查 Action → 委托 Pipeline → 返回 |
| `security/web/ActionResource.java` | 新建 | `POST /v1/actions/{name}/execute` |

### 5.5 流水线伪代码

```java
public PipelineResult execute(Action action, Actor actor,
                               String targetResourceRid, Map<String, Object> params) {
    // ActionService 在调用 Pipeline 之前已通过 ActionRepository 加载 Action 对象
    // 并将其注入 PipelineContext。Pipeline 本身不负责 Action 的加载。
    PipelineContext ctx = new PipelineContext(action, actor, targetResourceRid, params);
    
    try {
        stepAuth(ctx);        // 1. 解析 header → Actor 对象。I-3 范围内仅解析 header（信任调用方），不做 DB 查询。真正的身份验证是未来 concern
        stepRole(ctx);        // 2. 查 RoleRepository → effectiveRoles
        stepCapability(ctx);  // 3. capabilityResolver.resolve() → capabilities
        stepGate(ctx);        // 4. caps 中是否有一条覆盖 (requiredAbility, targetType)
        stepState(ctx);       // 5. 读 Resource.currentState → StateMachineGuard.guardGate()
        stepValidate(ctx);    // 6. SpEL 表达式 eval，输入是 ctx.inputParams
        stepExecute(ctx);     // 7. 写变更（→ §5.6）
        stepEvent(ctx, "ACTION_INVOKED", null);  // 8. 成功事件
        stepNotify(ctx);      // 9. Spring ApplicationEvent
        return ctx.buildSuccessResult();
        
    } catch (PipelineRejection e) {
        stepEvent(ctx, stepNameToEventType(e.step), e.getMessage());  // 8. 拒绝事件
        return ctx.buildDeniedResult(e);
    }
}
```

### 5.6 Step 7 Execute 的语义

这是整个流水线唯一「做事」的步骤。它执行 Action 的 `executeParams` 模版。

**executeParams 结构**（JSONB）：

```json
{
  "updates": {
    "fields": {
      "tier": "{{params.newTier}}",
      "reviewedBy": "{{actor.id}}"
    },
    "newState": "Active"
  }
}
```

`{{params.x}}` 是模版变量，从请求的 `inputParams` 中取值。`{{actor.id}}` 从认证后的 Actor 取值。

**Execute 步骤的原子操作**（在一个 `@Transactional` 内）：

1. 从 `ctx.targetResource` 读当前 version
2. 解析 executeParams 模版 → 实际的字段更新 + 目标状态
3. 确定有效的 targetState：
   - 如果 `gate.toState != null`，使用 `gate.toState` 作为 targetState（gate 定义优先）。若模版中也有 `newState` 且与 gate.toState 不一致 → 拒绝（模版不得覆盖 gate）
   - 如果 `gate.toState == null`，使用模版中的 `newState`（由调用者决定）。若模版也无 `newState` → 仅更新字段，不改变状态
4. 调 `ResourceService.updateFields(rid, fields, expectedVersion)` —乐观锁写入，部分更新（merge）：只更新指定的字段，保留其他字段不变
5. 如果有 targetState，调 `ResourceService.transitionState(rid, targetState)`。注意：此时 `transitionState` 内会再次校验状态迁移合法性——这不是重复，而是 enforcement point：Step 5 的 guardGate 检查的是「Action 定义是否允许该操作」，Step 7 的 transitionState 检查的是「Resource 实例当前状态是否允许该具体迁移」。两者角度不同。如果 `gate.toState != null`，Step 5 已完整校验了 `from→to` 迁移，transitionState 的二次校验是冗余但安全的——未来可优化跳过
6. 如果步骤 4 或 5 失败（乐观锁冲突、DB 连接异常）→ 抛异常 → 事务回滚 → 返回 500
7. 如果全部成功 → ctx 记录执行结果 → 继续 Step 8

**事务边界**：整个流水线 Step 1-9 在一个 Spring `@Transactional` 内。Step 7 失败自动回滚 Resource 变更。Step 8 写入 Event Log 使用 `REQUIRES_NEW` 传播——即使主事务回滚，拒绝事件也被记录。

**Step 8 与 ChangeEventInterceptor 的分工**：`ChangeEventInterceptor` 通过 JPA `@PrePersist`/`@PreUpdate` 自动记录 Resource 实体的数据变更事件（`RESOURCE_CREATED`、`RESOURCE_UPDATED`）。Step 8 记录的是**审计事件**（`ACTION_INVOKED` / `ACTION_DENIED`）——Actor、Action、输入参数、耗时——这是一种不同的事件类型，回答「谁在什么时候调了什么操作」。两者不重叠——Interceptor 关注资源变化，Step 8 关注操作审计。

### 5.7 REST API

```
POST /v1/actions/{actionName}/execute
Headers:
  X-Actor-Type:  agent
  X-Actor-Id:    agent-123
  X-Actor-Role:  compliance-agent

Request Body:
{
  "targetResourceId": "default.Customer.a1B2c3D4",
  "params": {
    "newTier": "platinum",
    "reason": "Annual review passed"
  }
}

Response (200):
{
  "status": "SUCCESS",
  "action": "update_customer_tier",
  "actor": { "type": "agent", "id": "agent-123", "role": "compliance-agent" },
  "targetResource": "default.Customer.a1B2c3D4",
  "result": { "previousTier": "gold", "newTier": "platinum" },
  "durationMs": 42,
  "steps": [
    { "step": 1, "name": "AUTH",       "status": "PASSED", "durationMs": 1 },
    { "step": 2, "name": "ROLE",       "status": "PASSED", "durationMs": 3 },
    { "step": 3, "name": "CAPABILITY", "status": "PASSED", "durationMs": 2 },
    { "step": 4, "name": "GATE",       "status": "PASSED", "durationMs": 1 },
    { "step": 5, "name": "STATE",      "status": "PASSED", "durationMs": 4 },
    { "step": 6, "name": "VALIDATE",   "status": "PASSED", "durationMs": 5 },
    { "step": 7, "name": "EXECUTE",    "status": "PASSED", "durationMs": 20 },
    { "step": 8, "name": "EVENT",      "status": "PASSED", "durationMs": 3 },
    { "step": 9, "name": "NOTIFY",     "status": "PASSED", "durationMs": 2 }
  ]
}

Response (403) — Gate 在第 4 步失败:
{
  "status": "DENIED",
  "action": "drop_customer",
  "deniedAtStep": 4,
  "deniedAtName": "GATE",
  "reason": "Actor 'agent-123' lacks capability 'DROP' on type 'Customer'",
  "durationMs": 6,
  "steps": [
    { "step": 1, "name": "AUTH",       "status": "PASSED", "durationMs": 1 },
    { "step": 2, "name": "ROLE",       "status": "PASSED", "durationMs": 2 },
    { "step": 3, "name": "CAPABILITY", "status": "PASSED", "durationMs": 1 },
    { "step": 4, "name": "GATE",       "status": "DENIED", "durationMs": 0 },
    { "step": 8, "name": "EVENT",      "status": "PASSED", "durationMs": 1 }
  ]
}
```

### 5.8 验证标准

- [ ] 合法 Action + 合法 Actor + 合法 Resource → 9 步全部 PASSED
- [ ] Actor 无 header → Step 1 拒绝 401
- [ ] Actor 无 Role → Step 2 拒绝 403
- [ ] Actor Role 的 Capability 不覆盖 Action 的 requiredAbility → Step 4 拒绝 403
- [ ] Resource 状态不匹配 stateGate → Step 5 拒绝 409
- [ ] Validate SpEL 表达式返回 false → Step 6 拒绝 422
- [ ] Execute 写入 Resource Store 失败（乐观锁冲突、DB 连接异常）→ Step 7 失败 500，事务回滚，Resource 保持旧值和旧 version
- [ ] 拒绝的每一步都在 Event Log 有对应记录
- [ ] Response body 包含每步状态、耗时、拒绝原因（denied 时）
- [ ] **现有 192 个 server tests 继续通过**
- [ ] 14 个集成测试（启动 Spring + H2/Testcontainers PG）

### 5.9 SDK / MCP / Workshop 兼容性

**SDK (Python)**：I-3 完成后需新增 `client.actions.execute(name, target_rid, params)` 方法。记录为 follow-up task，不在 I-3 范围内。

**MCP Server**：I-3 完成后 MCP 工具可暴露 `execute_action` tool。记录为 follow-up task。

**Workshop UI**：Security tab 已有基础面板。需新增 Action 执行界面（选择 Action → 填写 params → 查看流水线步骤）。记录为 follow-up task。

---

## 6. Iteration 4: Capability 数据迁移

**目标**：把 Role.capabilities JSONB 中的旧格式数据升级为新格式。I-3 的 `TypeSafeCapabilityResolver` 已经支持双格式解析——本 iteration 确保数据库中的 Role JSON 全部为新格式，同时 bootstrap data 更新。

**数据源说明**：Capability 解析走的是 Role JSON 路径（`RoleCapabilityCache` → `Role.capabilities` JSONB），不走 `security_capabilities` 表。后者是独立的能力授予记录，与 Role 体系并行存在，当前不参与流水线的 Capability 解析。本 iteration 不修改 `security_capabilities` 表结构。

### 6.1 背景

I-3 的 `TypeSafeCapabilityResolver` 包装 `RoleCapabilityCache`，已支持新旧双格式自动检测。匹配逻辑（已在 I-3 实现，I-4 不改变）：

```java
// TypeSafeCapabilityResolver.resolve() — 已在 I-3 实现
// 数据源：RoleCapabilityCache（读 Role.capabilities JSONB）
public List<CapabilityRecord> resolve(Actor actor, Ability requiredAbility, String resourceTypeFqn) {
    List<Map<String, String>> raw = capabilityCache.get(actor.role());
    return raw.stream()
        .map(this::parse)                         // 新旧格式自动检测
        .filter(c -> c.ability() == requiredAbility)
        .filter(c -> "*".equals(c.resourceType()) || resourceTypeFqn.equals(c.resourceType()))
        .filter(c -> c.expiry() == null || c.expiry().isAfter(Instant.now()))
        .toList();
}
```

### 6.2 Scope

#### In scope

| 能力 | 说明 |
|------|------|
| **Role.capabilities JSON 格式升级** | 解析现有 Role 的 capabilities JSONB，将旧格式条目按映射表升级；旧格式继续可读（graceful degradation） |
| **RoleCapabilityCache 双格式解析** | 缓存解析时探测 JSON 结构，新旧格式分别处理 |
| **bootstrap data 更新** | `RoleBootstrapService` 中的内置 Role 的 capabilities 更新为新格式 |
| **旧格式 best-effort 映射** | `operation` 字符串按映射表转 Ability 枚举：`"query"→QUERY`, `"mutate"→MUTATE`, `"drop"→DROP`, `"transfer"→TRANSFER`, `"copy"→COPY`, `"freeze"→FREEZE`。无法识别的 operation → 降级为 `QUERY` + warn 日志 |

**不涉及**：`security_capabilities` 表——该表不参与流水线 Capability 解析，结构保持不变。

### 6.3 数据库迁移

**无 DDL 迁移。** Role.capabilities 是 JSONB 列，格式升级通过 `RoleCapabilityCache` 的解析逻辑处理，不需要 ALTER TABLE。bootstrap data 在应用启动时通过 `RoleBootstrapService` 写入/更新。

### 6.4 代码产物

| 文件 | 操作 | 说明 |
|------|------|------|
| `security/RoleCapabilityCache.java` | 修改 | 解析逻辑增加双格式探测 + best-effort 映射表 |
| `security/service/RoleBootstrapService.java` | 修改 | 内置 Role 的 capabilities JSON 更新为新格式 |

### 6.5 旧格式兼容策略

```java
// RoleCapabilityCache 解析逻辑
private static final Map<String, Ability> OPERATION_MAP = Map.of(
    "query",    Ability.QUERY,
    "mutate",   Ability.MUTATE,
    "drop",     Ability.DROP,
    "transfer", Ability.TRANSFER,
    "copy",     Ability.COPY,
    "freeze",   Ability.FREEZE
);

private CapabilityRecord parseItem(Map<String, String> item) {
    if (item.containsKey("ability")) {
        // 新格式：{"ability": "MUTATE", "resourceType": "Customer"}
        return new CapabilityRecord(
            Ability.valueOf(item.get("ability")),
            item.getOrDefault("resourceType", "*"),
            parseExpiry(item.get("expiry"))
        );
    }
    // 旧格式：{"entityType": "resourceType", "operation": "mutate"}
    String op = item.getOrDefault("operation", "query");
    Ability ability = OPERATION_MAP.getOrDefault(op, Ability.QUERY);
    if (!OPERATION_MAP.containsKey(op)) {
        log.warn("Unknown operation '{}' in old-format capability, mapped to QUERY", op);
    }
    return new CapabilityRecord(ability, "*", null);
}
```

### 6.6 验证标准

- [ ] 旧格式 `"entityType":"resourceType","operation":"mutate"` → 解析为 `MUTATE on *`
- [ ] 旧格式 `"entityType":"resourceType","operation":"drop"` → 解析为 `DROP on *`
- [ ] 旧格式 `"operation":"unknownOp"`（无法识别）→ 降级为 `QUERY on *` + warn 日志
- [ ] 新格式 `"ability":"MUTATE","resourceType":"Customer"` → 正确解析
- [ ] bootstrap Role 的 capabilities JSON 全部为新格式
- [ ] **I-3 的 14 个集成测试继续通过**（resolver 匹配逻辑不变）
- [ ] **现有 192 个 server tests 继续通过**
- [ ] 5 个新单元测试（旧格式映射 3 + 新格式 1 + bootstrap 验证 1）

---

## 7. Iteration 5: 查询引擎结构化

**目标**：把 `QueryController` 的裸 `Map<String,Object>` + 字符串拼 SQL 替换为一个有类型的查询对象 + 参数化 SQL 生成。**不加三层引擎，不加新 JSON DSL。** 保留现有 v1 查询格式。

### 7.1 当前问题

```java
// 当前的 QueryController
Map<String, Object> request  // 裸 Map，无类型
StringBuilder sql = new StringBuilder("SELECT ");
fieldToCol.forEach((f, c) -> sql.append("t0.").append(c)...);  // 字符串拼 SQL
sql.append(" WHERE ").append(col).append(mapOp(op)).append("?");  // 字符串拼条件
```

问题：无类型安全、无查询验证（字段是否存在？类型是否匹配？）、SQL 注入风险（依赖 `isValidSqlName` 正则，不如参数化白名单可靠）。

### 7.2 方案

```
现有 v1 JSON DSL（不改变请求格式）
    │
    ▼
QueryParser  ──► SemanticQuery (有类型的查询对象，字段名/类型已验证)
    │
    ▼
SqlGenerator ──► PreparedStatement (参数化 SQL，列名/表名来自白名单)
    │
    ▼
ResultSet → List<Map<String, Object>>
```

### 7.3 Scope

#### In scope

| 组件 | 说明 |
|------|------|
| **SemanticQuery** | 结构化查询对象：type, fields[], filter, traversals[], aggregation, sort, pagination |
| **QueryParser** | 现有 v1 JSON → SemanticQuery。解析时验证：`type` 是否在 Schema Registry 中存在、`fields` 是否在该 ResourceType.fields 中声明、filter 的 `op` 是否合法 |
| **SqlGenerator** | SemanticQuery → `{sql: String, params: List<Object>}`。使用 PreparedStatement，列名从 ResourceType.fields 白名单解析，不拼接用户输入 |
| **QueryController（修改）** | 内部走 Parser → SqlGenerator，不维护两套生成逻辑 |

#### Out of scope

- 新 JSON DSL v2 格式（保留 v1，等查询复杂度真正需要嵌套 filter 时再加）
- LogicalPlan、QueryPlanner、PlanOptimizer、cost model
- 跨多源查询
- Streaming / 游标
- 查询缓存

### 7.4 代码产物

| 文件 | 操作 | 说明 |
|------|------|------|
| `query/SemanticQuery.java` | 新建 | 查询对象：type, fields[], filter map, traversals, aggregate, sort, pagination |
| `query/QueryParser.java` | 新建 | 现有 v1 JSON → SemanticQuery。验证 type/fields/op |
| `query/QueryParseException.java` | 新建 | 解析/验证错误 |
| `query/SqlGenerator.java` | 新建 | SemanticQuery → `{sql, params}`（PreparedStatement） |
| `query/GeneratedSql.java` | 新建 | record: `String sql, List<Object> params` |
| `web/QueryController.java` | 修改 | 内部走 Parser → SqlGenerator；不维护两套生成逻辑；不新增端点 |

### 7.5 参数化 SQL 生成示例

```java
// SqlGenerator.generate(SemanticQuery q)
// → { sql: "SELECT t0.name, t0.tier, t0.total_spend FROM customers t0
//           WHERE t0.tier = ? AND t0.total_spend > ? LIMIT ?",
//     params: ["gold", 10000, 50] }

// 列名从 ResourceType.fields 白名单解析——不是从请求中的字符串直接拼
// type="Customer" → typeRepo.findByFQN("Customer") → type.getFields()
// 每个列名都经过白名单：type.getFields().stream().filter(f -> f.name().equals(requested)).findFirst()
// 不在白名单中 → QueryParseException → 400
```

### 7.6 验证标准

- [ ] 现有 v1 JSON → 正确解析为 SemanticQuery → 生成参数化 SQL → 执行成功
- [ ] filter 中引用未声明的字段 → 400，错误消息列出该类型可用字段
- [ ] 恶意输入（`"field": "1; DROP TABLE users"`）→ 400（字段不在 ResourceType.fields 白名单中）
- [ ] 生成的 SQL 使用 `?` 占位符，列名来自白名单（不经由请求字符串直接拼接）
- [ ] 聚合 + groupBy → 正确生成 `GROUP BY` + 聚合函数
- [ ] 多表遍历 → 正确生成 JOIN 链
- [ ] 响应结构与当前 `/v1/query` 完全一致（不改变 API 契约）
- [ ] **现有 192 个 server tests 继续通过**
- [ ] 6 个新单元测试（Parser 3 + SqlGenerator 3）

---

## 8. 跨迭代关注点

### 8.1 迁移策略

**规则**：加列不改列。旧格式继续读。旧端点继续工作。

| Iteration | 数据库迁移 | 代码兼容 |
|-----------|-----------|---------|
| I-0 | V14: `heirloom_resources` 表；V15: `resource_types` 加 `initial_state` 列 | 无关已有表 |
| I-1 | V16: `security_actions` 加 4 列 | 旧 `requiredAbility`/`stateGate` 列保留，新 Action 同时填新旧列 |
| I-2 | 无迁移 | — |
| I-3 | 无迁移 | 新增 `/v1/actions/{name}/execute`，不修改现有端点 |
| I-4 | 无 DDL 迁移（Role JSONB 格式升级通过解析逻辑处理） | 旧 JSON 格式继续解析；bootstrap data 更新；不新增端点 |
| I-5 | 无迁移 | 不新增端点；`/v1/query` 内部重构，API 契约不变 |

### 8.2 测试策略

| 层 | 策略 | 工具 |
|----|------|------|
| 校验器（I-1, I-2） | 纯单元，mock TypeRepository + ResourceService | JUnit 5 + Mockito |
| Pipeline（I-3） | Spring 集成测试，真实 H2（不含 JSONB 的步骤）+ Testcontainers PG（含 JSONB 的步骤） | SpringBootTest |
| Capability（I-4） | 单元 RoleCapabilityCache 解析 + bootstrap 数据验证 | JUnit 5 + Mockito |
| Query Engine（I-5） | 单元 Parser + SqlGenerator + 集成 Testcontainers PG（验证参数化 SQL 正确执行） | JUnit 5 + Testcontainers |

**每个 iteration 的退出标准包含**：「现有 192 个 server tests 全部通过」+ 该 iteration 的新增测试。

### 8.3 依赖图

```
I-0 (Resource Store) ──┐
I-1 (Action validation) ──┤ 三者可并行启动
I-5 (Query engine)    ──┘
                       │
                       ├──► I-2 (StateMachine guard — 需 I-0 Resource 实例 + I-1 StateMachine 类)
                       │
                       └──► I-3 (Pipeline — 编排 I-0 ResourceService + I-2 StateMachineGuard)
                              │
                              └──► I-4 (Capability data migration — 升级 Role JSON 格式)
```

### 8.4 SDK 跟进任务

| Iteration | SDK 新增方法 | 优先级 |
|-----------|-------------|--------|
| I-0 | `client.resources.create/update/get/list/transition_state()` | I-3 完成后 |
| I-3 | `client.actions.execute(name, target_rid, params)` | I-3 完成后 |
| I-5 | 无需新增 SDK 方法（`/v1/query` API 契约不变） | — |

### 8.5 MCP Server 跟进任务

| Iteration | MCP Tool | 优先级 |
|-----------|---------|--------|
| I-0 | `create_resource`, `get_resource`, `update_resource`, `transition_resource_state` | I-3 完成后 |
| I-3 | `execute_action` | I-3 完成后 |
| I-5 | 无需新增 MCP tool（`/v1/query` API 契约不变） | — |

### 8.6 Workshop UI 跟进任务

| Iteration | UI 面板 | 优先级 |
|-----------|---------|--------|
| I-0 | Schema tab → Resource 实例管理（列表、详情、状态可视化） | I-3 完成后 |
| I-3 | Security tab → Action 执行面板（选择 Action → 填写 params → 流水线步骤可视化） | I-3 完成后 |
| I-4 | Security tab → Capability 矩阵视图（Actor → Abilities × ResourceTypes 热力图） | I-4 完成后 |

---

## 9. 版本历史

| 日期 | 版本 | 说明 |
|------|------|------|
| 2026-07-01 | v0.1 | 初版：5 次迭代，基于代码审计发现的核心断层 |
| 2026-07-01 | v0.2 | 重写：新增 I-0 Resource Store；I-1 加列不改列；I-3 从 16 文件压缩到 7 文件；I-3+I-4 Gate 步骤用接口解耦；I-5 从三层引擎降级为 Parser+SqlGenerator；各迭代增加「现有测试通过」退出标准；增加 SDK/MCP/Workshop 兼容性跟进清单 |
| 2026-07-01 | v0.3 | Review fixes：I-0 去掉 DB default + 修正 GIN index + 明确与 KnowledgeArticle 的关系；统一命名 fromState/toState；I-3 从第一天就用 TypeSafeCapabilityResolver；I-4 降级为纯数据迁移；明确 ChangeEventInterceptor 与 Step 8 的分工；I-5 取消 v2 JSON DSL |
| 2026-07-01 | v0.4 | 第二轮 review：I-3 resolver 数据源改为 RoleCapabilityCache；I-4 去掉 Capability 表迁移；I-0 新增 ResourceType.initialState 字段；I-3 Step 3 去掉 CAPABILITY_DENIED；Action 加载时机明确；旧格式 best-effort 映射；双重校验分工 |
| 2026-07-01 | v0.5 | 第三轮 review：修正依赖图（I-0/I-1/I-5 并行）；Step 1 Auth 仅解析 header；gate.toState 默认 targetState；SqlGenerator+MappingRule；I-0 写入端点安全声明；migration 编号修正 V14/V15→I-0 V16→I-1 |
