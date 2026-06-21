# ADR-025: MappingRule — Column FQN vs Physical Path

## 状态
Accepted

## 日期
2026-06-21

## 上下文

MappingRule 用于连接语义层 field 和元数据层 Column。可以用简单的 physical path
字符串（"analytics.public.customers.tier"），也可以用 Column FQN。

## 决策

**使用 Column FQN（"postgres-prod.analytics.public.customers.tier"）。**
Column FQN 是平台上的唯一标识——可回溯到 Column Entity 获取完整元数据上下文
（数据类型、注释、质量信号、血缘）。

physical path 只能生成 SQL——Column FQN 还能让 Perspective Engine 注入
元数据上下文（新鲜度、空值率）到查询响应中。

## 后果

**积极**：Query Resolver 可获取完整元数据上下文。Schema 漂移时 Column FQN 不变。

**消极**：Column FQN 比 physical path 长。需要额外查 DB 获取元数据上下文。

## 参考

- 设计 Spec 4b.11 节
