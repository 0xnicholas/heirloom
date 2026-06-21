# ADR-014: EntityResource——标准 REST 端点基类

## 状态
Accepted

## 日期
2026-06-21

## 上下文

当前每种实体类型手写 Controller，导致：
- 分页逻辑不一致（有的用 offset/limit，有的用 cursor）
- 字段过滤（`fields` 参数）不一致
- 错误处理不一致
- 授权检查可能被遗漏

参考 OpenMetadata 的 `EntityResource<T, K>`：所有标准 CRUD 端点由基类提供，
具体 Resource 只声明 entityType 和自定义端点。

## 决策

**创建 `EntityResource<E>` 抽象基类，提供标准 CRUD 端点模板。**
具体 Resource 通过 `@RequestMapping` 声明端点，委托给基类 protected 方法。

### 核心设计

```java
public abstract class EntityResource<E extends HeirloomEntity> {

    protected final String entityType;
    protected final EntityRepository<E> repository;
    protected final Authorizer authorizer;

    @SuppressWarnings("unchecked")
    protected EntityResource(String entityType, Authorizer authorizer) {
        this.entityType = entityType;
        this.authorizer = authorizer;
        // OM pattern: getRepository from EntityRegistry, not @Autowired
        this.repository = (EntityRepository<E>) EntityRegistry.getRepository(entityType);
    }

    // 标准端点模板（子类用具体类型声明 @RequestMapping 并委托）
    protected ResponseEntity<EntityList<E>> list(String fields, int limit, ...) { ... }
    protected ResponseEntity<E> getById(Long id, String fields) { ... }
    protected ResponseEntity<E> getByFQN(String fqn, String fields) { ... }
    protected ResponseEntity<E> create(CreateRequest<E> request) { ... }
    protected ResponseEntity<Void> delete(Long id) { ... }
}
```

### TypeResource 示例（极简）

```java
@RestController
@RequestMapping("/v1/resourceTypes")
public class TypeResource extends EntityResource<ResourceType> {
    public TypeResource(Authorizer auth) { super(EntityRegistry.RESOURCE_TYPE, auth); }

    @GetMapping
    public ResponseEntity<EntityList<ResourceType>> list(...) { return super.list(...); }

    @PostMapping
    public ResponseEntity<ResourceType> create(@RequestBody CreateTypeRequest req) {
        return super.create(req);
    }
}
```

### Java 泛型擦除问题如何解决？

Spring MVC 在运行时看不到泛型 `<E>`。解决方案：子类声明具体类型的 `@RequestBody`
（如 `CreateTypeRequest`），Spring 可以反序列化具体类型。然后传给基类的
`create(CreateRequest<E> request)` 方法——因为 `CreateTypeRequest extends
CreateRequest<ResourceType>`。

## 后果

**积极**：
- 新增实体类型的 REST 端点几乎零样板代码
- 所有 ENDPOINT 行为一致（分页、字段过滤、授权检查、错误处理）

**消极**：
- 子类必须手动声明 `@RequestMapping` 方法（不能从基类继承注解）
- 静态内部 List 类（`TypeList extends ResultList<ResourceType>`）仍需手写

## 参考

- OpenMetadata `EntityResource.java`: `_references/OpenMetadata-main/openmetadata-service/src/main/java/org/openmetadata/service/resources/EntityResource.java`
- OpenMetadata `TableResource.java`: `_references/OpenMetadata-main/openmetadata-service/src/main/java/org/openmetadata/service/resources/databases/TableResource.java`
