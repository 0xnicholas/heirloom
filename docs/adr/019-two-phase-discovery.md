# ADR-019: 两阶段发现——提取→推断

## 状态
Accepted

## 日期
2026-06-21

## 上下文

Discovery Engine 需要将数据源 schema 翻译为 ResourceTypeProposal。有两个架构选择：
1. 流式处理（边扫描边推断，类似 OM 的 Ingestion 流式 Sink）
2. 两阶段处理（先采集完整 RawSchema，再统一推断）

## 决策

**采用两阶段处理：Phase 1 提取完整 RawSchema → Phase 2 推断。**

### 原因

Heirloom 的推断不是简单的「列名 → Field」映射。它需要全局视图：
- **Relationship 推断**：扫描到 `orders.customer_id` 是 FK 指向 `customers.id` 时，
  `customers` 表可能尚未被扫描到（取决于扫描顺序）。无法判断 `ON DELETE` 行为
  来推断关系语义（Ownership vs Reference）
- **StateMachine 推断**：`CHECK` 约束可能跨多个表相关
- **Abilities 推断**：基于命名约定的推断需要看到所有表名（如 `_log/_audit` 后缀
  表应加 `freeze`，但需要全局视图确认此约定）

### 与 OpenMetadata 的对比

OM 的 Ingestion 是流式的——因为 OM 只做元数据搬运（Table → CreateTableRequest），
不需要语义推断。Heirloom 的推断比元数据搬运复杂得多——必须先有全局视图再决策。

### Phase 1: 提取（TopologyRunner）

使用声明式 Topology 树遍历数据源（对标 OM 的 `DatabaseServiceTopology`），
产出独立于 Heirloom 概念的 `RawSchema`（纯结构数据：RawTable, RawColumn,
RawConstraint, RawRelationship）。

### Phase 2: 推断（InferencePipeline）

策略链模式：多条 `InferenceRule` 依次执行，每条产出部分 `ResourceTypeProposal`，
按 `proposedTypeName` 合并。单条规则失败不影响全局。

## 后果

**积极**：
- 推断逻辑可以跨表决策（关系语义、状态机）
- 每条推断规则独立可测
- Phase 1 和 Phase 2 的边界清晰——新增数据源只需实现 Phase 1

**消极**：
- 大 schema（1000+ 表）需要将完整 RawSchema 放在内存中
- 不能像 OM 那样实时推送部分结果

## 备选方案

**流式处理**
放弃理由：推断正确性 > 实时性。Heirloom 的扫描频率低（分钟级/小时级），不需要
毫秒级的流式产出。

## 参考

- 设计 Spec 5.5 节
- OpenMetadata `TopologyRunnerMixin`: `_references/OpenMetadata-main/ingestion/src/metadata/ingestion/api/topology_runner.py`
