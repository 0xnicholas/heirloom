# ADR-021: EntityRepository 包裹 Spring Data JPA（不替换）

## 状态
Accepted

## 日期
2026-06-21

## 上下文

OpenMetadata 使用 JDBI3（手写 SQL）作为数据访问层。Heirloom 已有 Spring Data JPA
基础设施（`ResourceTypeRepository extends JpaRepository`）。需要在两种方式之间
选择数据访问策略。

## 决策

**EntityRepository 包裹 Spring Data JPA，不替换它。** 即：EntityRepository 持有
`JpaRepository<E, Long>` 引用，在其之上增加生命周期钩子。

```java
public abstract class EntityRepository<E extends HeirloomEntity> {
    protected final JpaRepository<E, Long> jpaRepository;

    // 委托给 JPA
    public Optional<E> findById(Long id) { return jpaRepository.findById(id); }
    public Page<E> list(Pageable pageable) { return jpaRepository.findAll(pageable); }

    // 增加的生命周期钩子
    protected abstract void setFullyQualifiedName(E entity);
    protected abstract void prepareInternal(E entity, boolean isUpdate);
    protected void storeEntity(E entity, boolean isUpdate) { jpaRepository.save(entity); }
}
```

### 为什么不替换为 JDBI3？

1. 现有 `ResourceType` 已是 JPA Entity，迁移成本高
2. JPA 的 `@Version` 提供乐观锁，`@GeneratedValue` 提供 ID 生成——自建需要手写
3. Heirloom 的实体复杂度低于 OM——不需要 JDBI3 的手写 SQL 灵活性

### 为什么不用纯 JPA（不加 EntityRepository）？

JPA 提供 `save()` / `findById()`，但缺少：
- `setFullyQualifiedName()`：FQN 构建规则因实体类型而异
- `prepareInternal()`：结构性校验（TypeValidator）
- `storeRelationships()`：跨实体关系持久化（Graph Store 预留）

## 后果

**积极**：
- 复用现有 JPA 基础设施（无需迁移）
- 开发者仍然可以使用 Spring Data JPA 的查询方法（`findByFullyQualifiedName` 等）

**消极**：
- EntityRepository 不是 JPA Repository 接口——不能直接用于 Spring Data 的 `@Query` 注解
- 两层抽象（EntityRepository + JpaRepository）增加了理解成本

## 参考

- OpenMetadata `EntityRepository.java` (JDBI3): `_references/OpenMetadata-main/openmetadata-service/src/main/java/org/openmetadata/service/jdbi3/EntityRepository.java`
