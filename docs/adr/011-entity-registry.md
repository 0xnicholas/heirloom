# ADR-011: EntityRegistry——中央实体注册表（元数据+语义）

## 状态
Accepted

## 日期
2026-06-21 (updated 2026-06-21)

## 上下文

Heirloom 平台上有两类实体：
- **元数据实体**：Table、Column、Database、Schema、Lineage、GlossaryTerm 等（对标 OpenMetadata）
- **语义实体**：ResourceType、Proposal、Role、Action、Function 等（Heirloom 独有）

两类实体共存于同一个平台，共用同一套 EntityRegistry、EntityResource、EntityRepository 基础设施。

## 决策

**创建 `EntityRegistry` 类作为中央注册表，统一注册元数据实体和语义实体。**

### 核心设计

```java
@Component
public class EntityRegistry {

    // 实体类型常量
    public static final String RESOURCE_TYPE    = "resourceType";
    public static final String PROPOSAL         = "proposal";
    public static final String DISCOVERY_SOURCE = "discoverySource";
    public static final String DISCOVERY_REPORT = "discoveryReport";
    public static final String MAPPING_RULE     = "mappingRule";
    public static final String EVENT            = "event";

    // 通用字段常量（避免硬编码字符串）
    public static final String FIELD_OWNER       = "owner";
    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_FQN         = "fullyQualifiedName";
    public static final String FIELD_VERSION     = "version";
    public static final String FQN_SEPARATOR     = ".";

    // 线程安全静态注册表
    private static final Map<String, EntityRegistration> registry = new ConcurrentHashMap<>();

    /** 由各 Repository 的 @PostConstruct 调用 */
    public static void register(String entityType, Class<?> entityClass,
                                 EntityRepository<?> repo, EntityService<?> svc,
                                 String fqnTemplate, String collectionPath) {
        registry.put(entityType, new EntityRegistration(
            entityType, entityClass, repo, svc, fqnTemplate, collectionPath));
    }

    public static EntityRepository<?> getRepository(String entityType) { ... }
    public static EntityService<?>    getService(String entityType)    { ... }
    public static Class<?>            getEntityClass(String entityType) { ... }
}
```

### 为什么不用 Spring DI 直接注入 Repository？

Spring DI 按类型解析（`@Autowired TypeRepository`）。EntityRegistry 提供按
**实体类型字符串**解析（`getRepository("resourceType")` → `TypeRepository`）。
这使能了：
- 泛型 EntityResource 自动找到对应的 Repository
- ChangeEventInterceptor 按 entityType 路由事件
- FQN-based 查找，无需编译时知道 Java 类

### 为什么用静态方法而非实例方法？

EntityRegistry 的注册表需要全局可访问——不仅在 Spring Bean 内部，
也在静态工具方法中（如 `FullyQualifiedName.build()` 可能需要查询 entityType）。
静态方法 + ConcurrentHashMap 满足此需求。

## 后果

**积极**：
- 新增实体类型只需在 EntityRegistry 中加一个常量 + 在对应 Repository 中调用 `register()`
- ChangeEventInterceptor、EntityResource 等可通过 entityType 字符串动态查找

**消极**：
- 静态注册表在单元测试中需要特殊处理（`@BeforeEach` 清理或 mock）
- Java 泛型擦除导致 `getRepository()` 返回 raw type，调用方需要手动 cast

## 备选方案

**方案 A：Spring Bean Map（`Map<String, EntityRepository> repoMap`）**
放弃理由：Bean Map 按 bean name 做 key，EntityRepository 的 bean name 是 Spring
生成的（如 "typeRepository"），与 entityType 字符串不一致。需要手动映射，没有
EntityRegistry 简洁。

**方案 B：每个 Resource 直接注入对应的 Repository**
放弃理由：导致每个 ConcreteResource 需要声明 `@Autowired` 注入，基类 `EntityResource`
无法提供通用逻辑。失去了「子类只声明 entityType 字符串」的简洁性。

## 参考

- OpenMetadata `Entity.java`: `_references/OpenMetadata-main/openmetadata-service/src/main/java/org/openmetadata/service/Entity.java`
- 设计 Spec 4b.1 节
