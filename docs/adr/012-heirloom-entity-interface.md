# ADR-012: HeirloomEntity——统一实体接口

## 状态
Accepted

## 日期
2026-06-21

## 上下文

平台上有多种实体类型。ChangeEventInterceptor 需要从任意实体提取 `entityType`、
`fullyQualifiedName`、`version` 等信息来生成审计事件。EntityResource 需要统一
的 `getId()`、`getName()` 来生成标准 REST 端点。需要一个共同接口。

参考 OpenMetadata 的 `EntityInterface`：核心字段为必需，扩展字段（owners、tags、
domains 等）为 `default null` 的可选方法。

## 决策

**定义 `HeirloomEntity` 接口，采用必需字段 + default-null 可选字段模式。**

```java
public interface HeirloomEntity {
    // === 必需字段 ===
    Long getId();
    String getEntityType();           // "resourceType", "proposal", ...
    String getFullyQualifiedName();
    void   setFullyQualifiedName(String fqn);
    String getName();
    String getDescription();
    Long getVersion();
    Instant getCreatedAt();
    Instant getUpdatedAt();

    // === 可选字段（对标 EntityInterface 的 default-null 模式）===
    default String  getOwner()       { return null; }
    default String  getDomain()      { return null; }
    default String  getChangeHash()  { return null; }
    default Boolean getDeleted()     { return false; }
}
```

### 为什么用 default-null 而非把所有字段都声明为必需？

不是所有实体都需要 owner/domain/changeHash。例如 ChangeEvent（审计日志条目）没有
owner。用 `default null` 允许每个实体类型只覆盖需要的字段，ChangeEventInterceptor
也不会因为某个字段为 null 而崩溃（它做 null 检查）。

### 为什么没有 `getUpdatedBy()`、`getTags()` 等 OM 字段？

Heirloom 不做社交功能（无 followers/votes/feed），不做层级标签（用 Abilities 替代）。
Phase 0 也不追踪 `updatedBy`（简化 Actor 模型）。

## 后果

**积极**：
- ChangeEventInterceptor 可处理任意 `HeirloomEntity`
- EntityResource 泛型 `E extends HeirloomEntity` 保证类型安全
- 新实体类型只需实现必需方法 + 可选覆盖

**消极**：
- 未来如需在 interface 层面增加新字段（如 `getUpdatedBy`），会影响所有实现类
- Java 的 `default` 方法在 interface 中不能声明 `@Column` 等 JPA 注解——需要
  各实现类自行注解

## 备选方案

**方案 A：抽象类 `BaseEntity` 替代接口**
放弃理由：JPA 实体通常只继承一个类。如果 BaseEntity 是抽象类，子类无法再继承其他
JPA 便利类。接口更灵活。

**方案 B：不做统一接口，用反射**
放弃理由：反射在运行时才暴露错误。接口提供编译时安全。

## 参考

- OpenMetadata `EntityInterface.java`: `_references/OpenMetadata-main/openmetadata-spec/src/main/java/org/openmetadata/schema/EntityInterface.java`
