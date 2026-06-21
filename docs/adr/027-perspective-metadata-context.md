# ADR-027: Perspective Engine with Metadata Context Injection

## 状态
Accepted

## 日期
2026-06-21

## 上下文

原 Perspective Engine 只做基于 Role 的字段裁剪。现在有了元数据层，可以在查询
响应中注入元数据上下文（新鲜度、空值率、血缘摘要），让 Agent 判断数据可信度。

## 决策

**Perspective Engine 做两件事**：字段裁剪 + 元数据上下文注入。

对于每个查询返回的字段，通过 MappingRule → Column FQN → 查找 TableProfile 和
Lineage，将上下文注入到查询响应的 `context` 字段中。

## 后果

Agent 不仅能获得数据，还能知道「这个字段最近 5 分钟更新过，空值率 0.2%，数据
来自 S3 → dbt → PG」。不需要额外查询。

## 参考

- 设计 Spec 4b.13 节
