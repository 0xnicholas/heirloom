# ADR-029: Query Resolver — JSON DSL to SQL Translation

## 状态
Accepted

## 日期
2026-06-21

## 上下文

Agent 通过 JSON DSL 表达语义查询。Query Resolver 需要将其翻译为底层 SQL。

## 决策

**五步翻译流程**：
1. SchemaRegistryService 验证 ResourceType + Agent capability
2. Mapping Engine 解析每个 field → Column FQN → 物理位置
3. traverse（关系遍历）→ JOIN，filter 作用于 JOIN 条件
4. 生成 SQL（SELECT → FROM → JOIN → WHERE → ORDER BY → LIMIT）
5. Perspective Engine 注入 Role 裁剪 + 元数据上下文

Phase 0 支持：过滤（$eq/$gt/$in/$and/$or）、排序、分页、单步关系遍历（JOIN）。
Phase 1+：聚合（$count/$sum/groupBy）、子查询、多源查询（DB + REST API）。

## 后果

Agent 不需要写 SQL——只需描述「查询什么类型、过滤什么条件、遍历什么关系」。
安全边界在类型层（Abilities）和 Role 层（Perspective Engine）双重保证。

## 参考

- 设计 Spec 4b.16 节
- ADR-004（JSON DSL 作为查询语言）
