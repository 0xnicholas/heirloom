# ADR-022: Discovery Topology——声明式遍历树

## 状态
Accepted

## 日期
2026-06-21

## 上下文

Discovery 的 Phase 1 需要连接数据源并遍历其 schema 层次（DataSource → Schema →
Table → Column → Constraint）。有两种遍历模式：命令式（hand-coded loops）vs
声明式（Topology tree）。

参考 OpenMetadata 的 `DatabaseServiceTopology`：声明式定义节点树，框架驱动深度
优先遍历。

## 决策

**采用声明式 Topology 树。** 每个 `DiscoveryNode` 定义 producer（产生项的方法名）、
stages（处理步骤）、children（子节点）、threads（是否并行）。

### 核心结构

```java
public class DiscoveryTopology {
    DiscoveryNode root = DiscoveryNode.builder()
        .producer("getServices")
        .stages(List.of(Stage.of("yieldSourceMetadata", "source")))
        .children("schema")
        .build();

    DiscoveryNode schema = DiscoveryNode.builder()
        .producer("getSchemaNames")
        .stages(List.of(Stage.of("yieldSchemaMetadata", "schema")))
        .children("table")
        .build();

    DiscoveryNode table = DiscoveryNode.builder()
        .producer("getTableNames")
        .stages(List.of(Stage.of("extractTableSchema", "table")))
        .children("column", "constraint")
        .threads(true)  // 表级并行
        .postProcess("markDeletedTables")
        .build();
}
```

### 为什么节点级并行只在 table 层？

Schema 通常只有几个（public, analytics），并行无收益。表可能有上百个——
并行提取可显著加速。column 和 constraint 是 table 的子节点，与 table
在同一线程中顺序处理（避免跨表 context 竞争）。

### 与 OM 的差异

- Heirloom 的 stage 不产出 Entity（OM 产出 CreateTableRequest 并直接 sink），
  而是累积到 DiscoveryContext 的 RawSchema builder
- Heirloom 的 context 不需要 consumer 依赖链（OM 的 consumer 用于构建 FQN）——
  RawSchema 中的 sourceTable 已包含完整路径

## 后果

**积极**：
- 新数据源 connector 只需实现 producer/stage 方法，框架负责遍历
- 表级并行自动工作

**消极**：
- 声明式树的调试比命令式代码困难（堆栈更深）
- 框架的深度优先遍历顺序对某些数据源可能不是最优

## 参考

- OpenMetadata `DatabaseServiceTopology`: `_references/OpenMetadata-main/ingestion/src/metadata/ingestion/source/database/database_service.py`
- OpenMetadata `TopologyRunnerMixin`: `_references/OpenMetadata-main/ingestion/src/metadata/ingestion/api/topology_runner.py`
