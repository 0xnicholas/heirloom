# ADR-020: FQN 统一命名体系

## 状态
Accepted

## 日期
2026-06-21

## 上下文

Heirloom 有多种实体类型，每种需要唯一标识。当前的 `ResourceType` 只按 `name` 标识
（如 "Customer"），缺少命名空间。当多个领域有同名类型时（如两个团队的 "Order"），
需要命名空间来区分。

OpenMetadata 使用 FQN（Fully Qualified Name）体系：`service.database.schema.table`，
用 `.` 分隔符构成层次路径。所有实体都有唯一 FQN。

## 决策

**所有 Heirloom 实体采用 FQN 体系。** 分隔符为 `.`。FQN 模板由 EntityRegistration
中的 `fqnTemplate` 定义。

### FQN 模式

| 实体类型 | FQN 模式 | 示例 |
|---------|---------|------|
| resourceType | `{domain}.{name}` | `crm.Customer` |
| proposal | `{typeFQN}.proposal-{uuid8}` | `crm.Customer.proposal-a1b2c3d4` |
| discoverySource | `{env}.{name}` | `prod.postgres-analytics` |
| discoveryReport | `{sourceFQN}.{timestamp}` | `prod.postgres-analytics.20260621-143022` |
| mappingRule | `{typeFQN}.{field}` | `crm.Customer.email` |
| event | `event.{id}` | `event.12345` |

### 为什么用 `.` 而非 `/` 或 `:`？

`.` 是 OpenMetadata 的标准分隔符。用相同的分隔符方便未来可能的互操作。
`.` 在 URL 中不需要转义（`/v1/resourceTypes/name/crm.Customer` 正常工作）。

### FQN 在 `EntityRepository.setFullyQualifiedName()` 中构建

每种实体类型的 FQN 构建规则不同，由具体的 `EntityRepository` 子类实现。
例如 `TypeRepository` 将 `setFullyQualifiedName()` 构建为 `{domain}.{name}`。

## 后果

**积极**：
- 支持多租户/多领域——不同 domain 可以有同名类型
- 按 FQN 查询是确定性的（`findByFQN("crm.Customer")` 返回唯一实体）
- FQN 作为 `UNIQUE` 约束在数据库层面保证唯一性

**消极**：
- 每个实体都需要 FQN 字段和对应的数据库 `UNIQUE` 约束
- FQN 一旦确定不应修改（修改需要级联更新所有引用）

## 参考

- OpenMetadata `Entity.java` 的 FQN 模式: `_references/OpenMetadata-main/openmetadata-service/src/main/java/org/openmetadata/service/Entity.java`
