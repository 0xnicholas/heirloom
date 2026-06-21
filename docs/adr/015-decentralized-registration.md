# ADR-015: 分散式实体注册

## 状态
Accepted

## 日期
2026-06-21

## 上下文

EntityRegistry 需要一个机制将每种实体类型与其 Repository、Service、FQN 模板
关联起来。有两种注册模式：集中式（在 EntityRegistry 的 @PostConstruct 中列出
所有实体）vs 分散式（每个 Repository 自注册）。

OpenMetadata 使用分散式：每个 `EntityRepository` 构造函数调用
`Entity.registerEntity(clazz, entity, this)`。新增实体不需要修改 Entity.java。

## 决策

**采用分散式注册。** 每个 `@Repository` Bean 在其 `@PostConstruct` 中调用
`EntityRegistry.register()`。

```java
@Repository
public class TypeRepository extends EntityRepository<ResourceType> {
    @PostConstruct
    void register() {
        EntityRegistry.register(
            EntityRegistry.RESOURCE_TYPE, ResourceType.class, this, typeService,
            "{domain}.{name}", "/v1/resourceTypes");
    }
}
```

### 为什么不用集中式（EntityRegistry.init() 列清单）？

集中式的问题：新增实体类型需要在两处改代码（新 Entity 类 + EntityRegistry.init()）。
分散式只需要在新 Repository 中加一行。符合开放-封闭原则。

### 线程安全顾虑

`EntityRegistry.register()` 操作 `ConcurrentHashMap`。Spring 的 Bean 初始化在
容器启动阶段完成，单线程——但使用 ConcurrentHashMap 作为防御性设计，避免未来
的并发注册场景（如动态注册）。

## 后果

**积极**：
- 新增实体无需修改 EntityRegistry
- 每个 Repository 的注册代码紧邻其类定义

**消极**：
- 无法从单一位置看到所有已注册实体（需要搜索 `EntityRegistry.register` 调用）
- 运行时注册顺序取决于 Spring Bean 初始化顺序（通常不影响功能）

## 备选方案

**集中式注册（已在之前的 spec 中探索并放弃）**
放弃理由：见上文「为什么不用集中式」。

## 参考

- OpenMetadata `Entity.java` 的 `registerEntity()` 方法
- OpenMetadata `TableRepository` 构造函数
