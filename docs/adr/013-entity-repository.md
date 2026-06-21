# ADR-013: EntityRepository——标准生命周期基类

## 状态
Accepted

## 日期
2026-06-21

## 上下文

Heirloom 的每种实体类型都有相似的数据访问模式：创建前校验（TypeValidator）、构建 FQN、
计算 changeHash、持久化。当前使用 Spring Data JPA 的裸 `save()`——这些横切关注点
分散在各 Service 方法中，容易遗漏。

参考 OpenMetadata 的 `EntityRepository<T>`：`setFields → setFullyQualifiedName →
storeEntity → storeRelationships` 的标准生命周期。

## 决策

**创建 `EntityRepository<E>` 抽象基类，包装 Spring Data JPA。**
不是替换 JPA，而是在 JPA 的 `save()/findById()` 之上增加生命周期钩子。

```java
public abstract class EntityRepository<E extends HeirloomEntity> {

    protected final JpaRepository<E, Long> jpaRepository;

    // 子类实现
    protected abstract void setFullyQualifiedName(E entity);
    protected abstract void prepareInternal(E entity, boolean isUpdate);

    // 子类可选覆盖
    protected void storeEntity(E entity, boolean isUpdate) {
        jpaRepository.save(entity);
    }
    protected void storeRelationships(E entity) { /* default no-op */ }

    // 模板方法（final——子类不覆盖）
    @Transactional
    public final E create(E entity) {
        setFullyQualifiedName(entity);     // 1. 构建 FQN
        prepareInternal(entity, false);    // 2. 结构性校验
        storeEntity(entity, false);        // 3. 持久化
        storeRelationships(entity);        // 4. 持久化关系
        return entity;
    }
}
```

### 为什么 `create()` 是 final？

防止子类跳过 `prepareInternal()` 或 `setFullyQualifiedName()`。如果子类需要自定义
逻辑，应该覆盖被调用的钩子方法（`prepareInternal`、`storeEntity` 等），而不是
`create()` 本身。

### 为什么 `storeRelationships()` 默认 no-op？

Phase 0 的关系信息内嵌在 ResourceType 的 JSONB 中，不需要额外存储。后续引入
Graph Store 时，覆盖此方法。

## 后果

**积极**：
- 每种实体的数据访问有一致的行为保证
- TypeValidator 从 Service 层移到 Repository.prepareInternal()，更接近持久化边界
- 新实体类型只需实现 `setFullyQualifiedName()` 和 `prepareInternal()`

**消极**：
- JPA 的 `@Transactional` 边界与 EntityRepository 的模板方法绑定——子类不能轻易
  改变事务行为

## 参考

- OpenMetadata `EntityRepository.java`: `_references/OpenMetadata-main/openmetadata-service/src/main/java/org/openmetadata/service/jdbi3/EntityRepository.java`
- ADR-002（原）: TypeValidator 在 prepareInternal 中调用
