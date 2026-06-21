# ADR-024: Metadata Entity Models — JPA + JSONB

## 状态
Accepted

## 日期
2026-06-21

## 上下文

元数据层需要存储 DatabaseService、Database、Schema、Table、Column、Lineage、
TableProfile 等实体。需要决定 Column 是否独立建表、Lineage 如何关联源/目标实体。

## 决策

- **Column 内嵌于 Table JSONB**（Phase 0）。Column 总是和 Table 一起查询，分开建表增加 JOIN 开销。后续可拆分为独立 Entity
- **Lineage 用 FQN 字符串引用**，不用 JPA `@ManyToOne`。因为 from/to 可能指向不同 Entity 类型（Table、Dashboard、Pipeline），FQN 更灵活
- **TableProfile 独立建表**——数据质量快照是独立时间序列，不应内嵌在 Table 中
- **DatabaseService 与 DiscoverySource 分离**——前者存连接信息（加密），后者是扫描任务的调度配置

## 后果

**积极**：Phase 0 快速启动——6 张表覆盖核心元数据层。Column 内嵌避免 JOIN 复杂度。

**消极**：Column 不能作为独立 Entity 被直接引用（如「查询所有 email 列」需要扫描 JSONB）。Phase 1 可拆分。

## 参考

- OpenMetadata entity schemas: `_references/OpenMetadata-main/openmetadata-spec/src/main/resources/json/schema/entity/data/`
- 设计 Spec 4b.9 节
