# Heirloom Architecture Redesign — Design Specification

**Date:** 2026-06-21 (updated 2026-06-21)
**Status:** Draft
**Reference:** OpenMetadata (open-metadata/OpenMetadata)

---

## 1. Heirloom 的两层架构

Heirloom 是一个完整的平台，包含两层：

### 第一层：元数据目录（对标 OpenMetadata）

自动发现、采集和管理企业数据资产的元数据：
- **技术元数据**：Database、Schema、Table、Column、Dashboard、Pipeline、Topic
- **血缘**：表级血缘、列级血缘（通过 dbt、Airflow、OpenLineage 等）
- **数据质量**：freshness、nullCount、uniqueCount、profiling
- **治理**：Ownership、Domain、GlossaryTerm、Tag、DataProduct
- **使用统计**：查询频率、最近使用

### 第二层：语义操作层（Heirloom 独有）

在元数据之上，为 AI Agent 提供类型安全的操作界面：
- **ResourceType**：将元数据实体包装为带安全边界的业务实体
- **Abilities**：类型级能力标记（QUERY、MUTATE、DROP、FREEZE 等）——在类型定义时声明
- **StateMachine**：实体生命周期状态图——非法迁移在类型层被拒绝
- **Relationship**：三级语义（Ownership/Reference/Association）——比元数据层的 FK 更有语义
- **Action**：唯一写入路径——九步校验流水线
- **Function**：无副作用计算
- **Role → Capability → Action**：Agent 与人类平权的授权链

### 两层关系

语义层引用元数据层。例如：
- `Customer` ResourceType 的 `tier` 字段映射到 `analytics.public.customers.tier` Column
- Agent 查询 ResourceType 时，Query Resolver 翻译为底层 SQL，Perspective Engine 根据 Role 裁剪字段
- 元数据层提供上下文（数据新鲜度、空值率），语义层提供安全边界（不能删 Customer）

---

## 2. Motivation

### 2.1 Problem

The current Heirloom architecture has the following structural gaps when compared
to production-grade metadata platforms like OpenMetadata:

| Gap | Current State | Consequence |
|-----|--------------|-------------|
| No metadata catalog | No Table, Column, Lineage, Quality entities | Agent has no way to discover what data exists |
| No Entity registry | Types scattered across JPA entities with no central lookup | Adding a new entity type requires ad-hoc wiring; no standard discoverability |
| No standard REST pattern | Each Controller hand-written with inconsistent pagination, field filtering, error handling | Developer overhead per endpoint; inconsistent API surface |
| No standard Repository lifecycle | Spring Data JPA `save()` used directly, no `prepare()` / `storeRelationships()` | Cross-entity consistency logic lives in Service layer (duplicated or forgotten) |
| Manual audit | Event Log calls must be remembered by each developer | Audit gaps are inevitable |
| No pluggable authorization | Hardcoded or absent | Cannot evolve from dev-mode (no auth) to production (RBAC) |
| Discovery as tool, not entity | Discovery Engine designed as a standalone callback | Cannot query, schedule, or govern discovery through the platform API |

### 2.2 Goal

Build Heirloom as a complete platform with two layers:
1. **Metadata catalog layer** (OpenMetadata parity) — discover, catalog, and govern data assets
2. **Semantic operating layer** (Heirloom unique) — type-safe operating interface for AI agents

---

## 3. Reference Platform: OpenMetadata Architecture (for Metadata Layer)

OpenMetadata's architecture is organized into 8 layers. Heirloom's **metadata layer**
adopts the same patterns. Heirloom's **semantic layer** is built on top.

### Layers Heirloom Adopts (for metadata catalog)

| OM Layer | Heirloom Adaptation |
|----------|-------------------|
| Entity Registry (`Entity.java`) | `EntityRegistry` — 20+ entity type constants for both metadata and semantic entities |
| Entity Interface (`EntityInterface`) | `HeirloomEntity` — unified contract for all entities |
| Entity Repository (`EntityRepository<T>`) | `EntityRepository<E>` — standard lifecycle wrapping JPA |
| Entity Resource (`EntityResource<T,K>`) | `EntityResource<E>` — standard REST CRUD base |
| Change Event Handler | `ChangeEventInterceptor` — Spring ResponseBodyAdvice |
| Authorizer | `Authorizer` — pluggable (Phase 0 Noop, Phase 2 RoleBased) |
| Schema Layer (`openmetadata-spec`) | JSON Schema definitions → code generation (Phase 1) |
| Search Infrastructure (Elasticsearch) | pgvector for semantic search (Phase 3) |

---

## 4. Heirloom Entity Catalog

### 4.1 Metadata Layer Entities (对标 OpenMetadata)

| Entity Type | FQN Pattern | Purpose | Phase |
|-------------|------------|---------|-------|
| `databaseService` | `{name}` | Database connection config (PG, MySQL, ...) | 0 |
| `database` | `{service}.{name}` | Database instance | 0 |
| `databaseSchema` | `{service}.{db}.{name}` | Schema namespace | 0 |
| `table` | `{service}.{db}.{schema}.{name}` | Table or view metadata | 0 |
| `column` | `{tableFQN}.{name}` | Column metadata (type, nullable, comment) | 0 |
| `tableProfile` | `{tableFQN}.profile` | Data quality profile (freshness, nulls, unique) | 1 |
| `lineage` | `{fromEntity}.{toEntity}.{type}` | Upstream/downstream lineage edge | 1 |
| `glossaryTerm` | `{glossary}.{name}` | Business term definition | 2 |
| `tag` | `{classification}.{name}` | Classification tag (PII, sensitive, ...) | 2 |
| `domain` | `{name}` | Data domain (Marketing, Finance, ...) | 1 |
| `dataProduct` | `{domain}.{name}` | Logical grouping of data assets | 2 |

### 4.2 Semantic Layer Entities (Heirloom 独有)

| Entity Type | FQN Pattern | Purpose | Phase |
|-------------|------------|---------|-------|
| `resourceType` | `{domain}.{name}` | Business semantic type definition | 0 |
| `proposal` | `{typeFQN}.proposal-{uuid}` | Schema change proposal for review | 0 |
| `mappingRule` | `{typeFQN}.{field}` | Field → physical column mapping | 0 |
| `role` | `{name}` | Actor permission boundary | 2 |
| `capability` | `{role}.{entityType}.{ability}` | Temporary access ticket | 2 |
| `action` | `{type}.{name}` | Write operation definition | 2 |
| `function` | `{name}` | Pure computation definition | 2 |

### 4.3 Platform Entities (共用)

| Entity Type | FQN Pattern | Purpose | Phase |
|-------------|------------|---------|-------|
| `discoverySource` | `{env}.{name}` | Configured data source for discovery | 0 |
| `discoveryReport` | `{sourceFQN}.{timestamp}` | Result of one discovery scan | 0 |
| `event` | `event.{id}` | Immutable audit log entry | 0 |
| `actor` | `{type}.{name}` | Human user / AI Agent / Automation | 2 |

---

## 5. Adjustments to Original Heirloom Design

Adding the metadata layer requires recalibrating the original Heirloom concepts.
Core semantic concepts (Abilities, StateMachine, Relationship, Action, Function,
Role→Capability) are UNCHANGED. What changes is their positioning within the
larger platform.

### What Changes

| Original | Problem | Adjustment |
|----------|---------|------------|
| ResourceType is the sole representation of data | Now Table/Column metadata entities also exist | ResourceType is a **semantic wrapper** around Table. Table describes structure; ResourceType describes safety boundaries |
| Schema Registry is the only registry | Now EntityRegistry is the platform-level registry | SchemaRegistryService becomes the **type management service** within the semantic layer, not the sole registry |
| Proposal only for ResourceType changes | Metadata entities may also need change approval | Proposal generalized to any Entity type |
| MappingRule: field → physical path | Need access to metadata context (quality, lineage) | MappingRule: field → Column FQN, enabling metadata context lookup |
| Discovery not in original design | Entirely new | Discovery Engine joins Semantic Core as 5th member |
| Manual Event Log calls | Unreliable | ChangeEventInterceptor auto-audits all Entity changes |

### What Stays the Same

Abilities (type-level safety), StateMachine (declarative state transitions),
Relationship (Ownership/Reference/Association semantics), Action (9-step pipeline),
Function (pure computation), Role→Capability→Action (Agent-human parity),
Semantic Core architecture (now 5 members, not 4).

### Semantic Core: 5 Members (was 4)

1. **Discovery Engine** (NEW) — metadata ingestion + semantic inference
2. **SchemaRegistryService** (was Schema Registry) — ResourceType CRUD and validation
3. **Mapping Engine** — field → Column FQN mapping (enhanced)
4. **Query Resolver** — semantic query → SQL translation
5. **Perspective Engine** — Role-based field visibility filtering

---

## 6. Module Structure

```
heirloom-server/src/main/java/com/heirloom/

  entity/                          *** NEW — Platform skeleton ***
  ├── EntityRegistry.java          Central singleton registry
  ├── HeirloomEntity.java          Entity interface
  ├── EntityRegistration.java      Registry entry record
  └── BaseEntity.java              Optional JPA convenience base

  repository/                      *** NEW — Repository base ***
  ├── EntityRepository.java        Abstract base (prepare/setFQN/storeEntity/storeRelationships)
  ├── TypeRepository.java          ResourceType repository
  ├── ProposalRepository.java      Proposal repository
  ├── DiscoverySourceRepository.java
  ├── DiscoveryReportRepository.java
  ├── MappingRuleRepository.java
  └── EventLogRepository.java      Append-only event log

  web/                             *** NEW — REST base ***
  ├── EntityResource.java          Abstract REST base (standard CRUD)
  ├── TypeResource.java            /v1/resourceTypes
  ├── ProposalResource.java        /v1/proposals
  ├── DiscoveryResource.java       /v1/discovery (run + history)
  ├── MappingResource.java         /v1/mappings
  └── EventResource.java           /v1/events (read-only)

  interceptor/                     *** NEW ***
  └── ChangeEventInterceptor.java  Automatic audit on all non-GET

  auth/                            *** NEW ***
  ├── Authorizer.java              Pluggable auth interface
  └── NoopAuthorizer.java          Phase 0: allow all

  schema/                          *** REFACTORED — semantic layer type management ***
  ├── domain/
  │   ├── ResourceType.java        → implements HeirloomEntity (semantic wrapper around Table)
  │   ├── Proposal.java            NEW
  │   ├── Field.java               (unchanged)
  │   ├── Ability.java             (unchanged)
  │   ├── Relationship.java        (unchanged — semantic, not data lineage)
  │   ├── StateTransition.java     (unchanged)
  │   └── FieldType.java           (unchanged)
  ├── service/
  │   ├── TypeService.java         → implements EntityService (was SchemaRegistryService)
  │   └── TypeValidator.java       (unchanged)

  metadata/                        *** NEW — metadata catalog (OpenMetadata parity) ***
  ├── domain/
  │   ├── DatabaseService.java     → implements HeirloomEntity
  │   ├── Database.java
  │   ├── DatabaseSchema.java
  │   ├── Table.java
  │   ├── Column.java
  │   ├── Lineage.java             // Data lineage edge (upstream→downstream)
  │   ├── TableProfile.java        // Data quality profile
  │   └── GlossaryTerm.java
  ├── repository/
  │   ├── TableRepository.java     → extends EntityRepository<Table>
  │   ├── ColumnRepository.java
  │   └── ...
  └── web/
      ├── TableResource.java       → extends EntityResource<Table>
      └── ...

  discovery/                       *** NEW — Discovery as platform entity ***
  ├── domain/
  │   ├── DiscoverySource.java     → implements HeirloomEntity
  │   └── DiscoveryReport.java     → implements HeirloomEntity
  ├── service/
  │   └── DiscoveryService.java    runDiscovery(sourceFQN) on platform
  ├── topology/
  │   ├── DiscoveryTopology.java
  │   ├── DiscoveryNode.java
  │   ├── DiscoveryStage.java
  │   └── DiscoveryContext.java
  ├── runner/
  │   └── DiscoveryRunner.java
  ├── extractor/
  │   ├── SchemaExtractor.java     Interface
  │   └── postgres/
  │       └── PostgresSchemaExtractor.java
  ├── model/
  │   ├── RawSchema.java
  │   ├── RawTable.java
  │   ├── RawColumn.java
  │   ├── RawConstraint.java
  │   └── RawRelationship.java
  └── inference/
      ├── InferencePipeline.java
      ├── InferenceRule.java
      └── rules/
          ├── TypeNameInference.java
          ├── FieldMapperInference.java
          └── RelationshipInference.java
```

---

## 6a. OpenMetadata Source Code Reference

This architecture references OpenMetadata's actual source code patterns:
- `Entity.java` — central entity type registry with `registerEntity()` called from Repository constructors
- `EntityResource<T, K>` — base REST resource that fetches Repository from `Entity.getEntityRepository()`
- `EntityInterface` — rich interface with default-null optional fields (owners, tags, domains, sourceHash)
- `EntityRepository<T>` — JDBI3-based base repository with `setFields`, `storeEntity`, `storeRelationships`
- `ChangeEventHandler` — JAX-RS `ContainerResponseFilter` that intercepts non-GET responses
- `TableResource` — thin concrete resource: constructor takes `Authorizer + Limits`, entity type hardcoded

Key adaptations for Heirloom (Spring Boot, not Dropwizard/JDBI3):
1. Repository self-registration via `EntityRegistry.register()` in constructor (not `@PostConstruct`)
2. `EntityResource` gets Repository from `EntityRegistry` (not constructor injection)
3. `HeirloomEntity` has mandatory + default-null optional fields (like `EntityInterface`)
4. Static inner List classes for generic serialization (`TypeList extends ResultList<ResourceType>`)
5. Heirloom does NOT adopt: EntityReference, TagLabel, ChangeDescription, Votes, Followers, Feed

---

## 6b. Component Deep-Dives

### 6b.1 EntityRegistry — Decentralized Registration (OM pattern)

OpenMetadata: each `EntityRepository` constructor calls `Entity.registerEntity()`.
Registration is decentralized — each Repository registers itself.

Heirloom adaptation: `EntityRegistry` is a singleton with a static registry map.
Each `@Repository` bean calls `EntityRegistry.register()` in its `@PostConstruct`.
The static `register()` method and `getRepository()`/`getService()` are thread-safe.

```java
@Component
public class EntityRegistry {

    // Entity type constants — static final, no DI dependency
    public static final String RESOURCE_TYPE    = "resourceType";
    public static final String PROPOSAL         = "proposal";
    public static final String DISCOVERY_SOURCE = "discoverySource";
    public static final String DISCOVERY_REPORT = "discoveryReport";
    public static final String MAPPING_RULE     = "mappingRule";
    public static final String EVENT            = "event";

    // Common field constants
    public static final String FIELD_OWNER       = "owner";
    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_FQN         = "fullyQualifiedName";
    public static final String FIELD_VERSION     = "version";
    public static final String FQN_SEPARATOR     = ".";

    // Thread-safe static registry
    private static final Map<String, EntityRegistration> registry = new ConcurrentHashMap<>();

    // OM pattern: each Repository registers itself in its constructor.
    // Heirloom adaptation: Spring Beans — Repository @Component registers via
    // @PostConstruct in its own class. Not centralized here.

    /** Called by each EntityRepository in its constructor/@PostConstruct */
    public static void register(String entityType, Class<?> entityClass,
                                 EntityRepository<?> repo, EntityService<?> svc,
                                 String fqnTemplate, String collectionPath) {
        registry.put(entityType, new EntityRegistration(
            entityType, entityClass, repo, svc, fqnTemplate, collectionPath
        ));
    }

    // Query methods return raw types (Java generics erasure limitation)
    public EntityRepository<?> getRepository(String entityType) { ... }
    public EntityService<?>    getService(String entityType)    { ... }
    public Class<?>            getEntityClass(String entityType) { ... }
    public Set<String>         getAllEntityTypes()              { ... }
}

record EntityRegistration(
    String entityType,
    Class<?> entityClass,
    EntityRepository<?> repository,
    EntityService<?> service,
    String fqnTemplate,
    String collectionPath
) {}
```

### 6b.2 HeirloomEntity Interface

Modeled on OpenMetadata's `EntityInterface` — mandatory fields plus default-null optional fields.

```java
public interface HeirloomEntity {
    // === Mandatory ===
    Long getId();
    String getEntityType();
    String getFullyQualifiedName();
    void   setFullyQualifiedName(String fqn);
    String getName();
    String getDescription();
    Long getVersion();
    Instant getCreatedAt();
    Instant getUpdatedAt();

    // === Optional (default null — like OM's EntityInterface) ===
    default String  getOwner()       { return null; }
    default String  getDomain()      { return null; }
    default String  getChangeHash()  { return null; }
    default Boolean getDeleted()     { return false; }
}
```

### 6b.3 EntityService Interface

```java
public interface EntityService<E extends HeirloomEntity> {
    E buildEntity(CreateRequest<E> request);
    void validateCreate(E entity);
    void validateUpdate(E existing, E incoming);
    void validateDelete(E entity);
    Map<String, Object> toResponse(E entity, Set<String> fields);
}
```

`validateCreate()` is business validation (uniqueness, authorization).
`prepareInternal()` in EntityRepository is structural validation (TypeValidator).
Both run during `create()`, in that order.

### 6b.4 EntityRepository — Wrapping Spring Data JPA

`EntityRepository<E>` is NOT a Spring Data interface. It is a wrapper class
that holds a reference to `JpaRepository<E, Long>` and adds lifecycle hooks.

```java
public abstract class EntityRepository<E extends HeirloomEntity> {

    protected final String entityType;
    protected final String collectionPath;
    protected final Class<E> entityClass;
    protected final JpaRepository<E, Long> jpaRepository;

    // Abstract — each concrete repository must implement
    protected abstract void setFullyQualifiedName(E entity);
    protected abstract void prepareInternal(E entity, boolean isUpdate);

    // Overridable with defaults
    protected void storeEntity(E entity, boolean isUpdate) {
        if (entity instanceof ResourceType rt) {
            rt.setChangeHash(computeHash(rt));
        }
        jpaRepository.save(entity);
    }

    protected void storeRelationships(E entity) {
        // Default no-op. Override when Graph Store is introduced.
    }

    // Template methods (final — subclasses do NOT override)
    @Transactional
    public final E create(E entity) {
        setFullyQualifiedName(entity);       // 1. Build FQN
        prepareInternal(entity, false);      // 2. Structural validation
        storeEntity(entity, false);          // 3. Persist entity
        storeRelationships(entity);          // 4. Persist relationships
        return entity;
    }

    @Transactional
    public final E update(E entity) {
        setFullyQualifiedName(entity);
        prepareInternal(entity, true);
        storeEntity(entity, true);
        storeRelationships(entity);
        return entity;
    }

    // Standard queries delegate to JPA
    public Optional<E> findById(Long id) { return jpaRepository.findById(id); }
    public Page<E> list(Pageable pageable) { return jpaRepository.findAll(pageable); }
    @Transactional
    public void delete(Long id) { jpaRepository.deleteById(id); }
}
```

**TypeRepository example** — `prepareInternal()` calls existing `TypeValidator`:

```java
@Repository
public class TypeRepository extends EntityRepository<ResourceType> {
    private final ResourceTypeJpaRepository jpa;

    public TypeRepository(ResourceTypeJpaRepository jpa) {
        super(EntityRegistry.RESOURCE_TYPE, "/v1/resourceTypes", ResourceType.class, jpa);
        this.jpa = jpa;
    }

    @Override
    protected void setFullyQualifiedName(ResourceType type) {
        String domain = type.getDomain() != null ? type.getDomain() : "default";
        type.setFullyQualifiedName(domain + EntityRegistry.FQN_SEPARATOR + type.getName());
    }

    @Override
    protected void prepareInternal(ResourceType type, boolean isUpdate) {
        Map<String, ResourceType> knownTypes = new HashMap<>();
        jpa.findAll().forEach(t -> knownTypes.put(t.getFullyQualifiedName(), t));
        List<TypeValidator.Diagnostic> diags = TypeValidator.validate(type, knownTypes);
        boolean hasErrors = diags.stream()
            .anyMatch(d -> d.severity() == TypeValidator.Severity.ERROR);
        if (hasErrors) throw new TypeValidationException(diags);
    }

    public Optional<ResourceType> findByFQN(String fqn) {
        return jpa.findByFullyQualifiedName(fqn);
    }
}
```

### 6b.5 EntityResource — Gets Repository from EntityRegistry (OM pattern)

**Problem:** Spring MVC cannot see generic type parameters at runtime due to
type erasure. `@RequestBody CreateRequest<E>` would be erased to `Object`.

**Solution:** Each concrete Resource declares its own `@RequestMapping` methods
with concrete parameter types, then delegates to `EntityResource` base logic.

```java
public abstract class EntityResource<E extends HeirloomEntity> {

    protected final String entityType;
    protected final EntityRepository<E> repository;
    protected final EntityService<E> service;
    protected final Authorizer authorizer;

    @SuppressWarnings("unchecked")
    protected EntityResource(String entityType, Authorizer authorizer) {
        this.entityType = entityType;
        this.authorizer = authorizer;
        // OM pattern: Entity.getEntityRepository(entityType)
        this.repository = (EntityRepository<E>) EntityRegistry.getRepository(entityType);
        this.service    = (EntityService<E>)    EntityRegistry.getService(entityType);
    }

    // Protected template methods that subclasses delegate to
    protected ResponseEntity<EntityList<E>> list(String fields, int limit,
                                                  String before, String after) { ... }
    protected ResponseEntity<E> getById(Long id, String fields) { ... }
    protected ResponseEntity<E> getByFQN(String fqn, String fields) { ... }
    protected ResponseEntity<E> create(CreateRequest<E> request) { ... }
    protected ResponseEntity<Void> delete(Long id) { ... }

    // Subclass provides FQN lookup
    protected abstract E findEntityByFQN(String fqn);
}
```

**Concrete Resource** — Spring sees concrete types:

```java
@RestController
@RequestMapping("/v1/resourceTypes")
public class TypeResource extends EntityResource<ResourceType> {

    public TypeResource(Authorizer auth) {
        super(EntityRegistry.RESOURCE_TYPE, auth);
        // OM pattern: repository fetched from EntityRegistry in base constructor
    }

    @GetMapping
    public ResponseEntity<EntityList<ResourceType>> list(
        @RequestParam(defaultValue="*") String fields, ...) {
        return super.list(fields, limit, before, after);
    }

    @PostMapping
    public ResponseEntity<ResourceType> create(
        @RequestBody CreateTypeRequest request) {  // Concrete type!
        return super.create(request);
    }
}
```

**CreateRequest pattern** — `CreateTypeRequest extends CreateRequest<ResourceType>`:

```java
public abstract class CreateRequest<E extends HeirloomEntity> {
    private String name, description, owner;
    public abstract E toEntity();
}

public class CreateTypeRequest extends CreateRequest<ResourceType> {
    private String domain;
    private List<Field> fields;
    private List<Ability> abilities;
    private List<StateTransition> stateMachine;
    private List<Relationship> relationships;

    @Override
    public ResourceType toEntity() {
        ResourceType type = new ResourceType(getName());
        type.setDomain(domain);
        type.setFields(fields);
        // ...
        return type;
    }
}
```

### 6b.6 ChangeEventInterceptor — Automatic Audit (Spring equivalent of OM's ChangeEventHandler)

Spring `ResponseBodyAdvice` intercepts all non-GET responses containing
`HeirloomEntity` and writes `ChangeEvent` to `EventLogRepository` in an
independent transaction (`Propagation.REQUIRES_NEW`).

```java
@Component
public class ChangeEventInterceptor implements ResponseBodyAdvice<Object> {

    private final EventLogRepository eventLog;

    @Override
    public Object beforeBodyWrite(Object body, ...) {
        if (isReadOnly(httpMethod)) return body;
        if (!(body instanceof HeirloomEntity entity)) return body;

        ChangeEvent event = ChangeEvent.builder()
            .entityType(entity.getEntityType())
            .entityId(entity.getId())
            .entityFQN(entity.getFullyQualifiedName())
            .eventType(resolveEventType(httpMethod))
            .actor(getCurrentActor())
            .timestamp(Instant.now())
            .version(entity.getVersion())
            .changeHash(entity.getChangeHash())
            .build();

        eventLog.append(event);  // Independent transaction
        return body;
    }
}
```

**Rejected operations** are logged from `@ExceptionHandler`:

```java
@ExceptionHandler(UnauthorizedException.class)
public ResponseEntity<ErrorResponse> handleUnauthorized(
    UnauthorizedException ex, HttpServletRequest request) {
    eventInterceptor.logRejected(ex.getEntityType(), ex.getOperation(),
        getActor(), ex.getMessage());
    return ResponseEntity.status(403).body(new ErrorResponse(ex.getMessage()));
}
```

**EventLogRepository** — append-only, independent transactions:

```java
@Repository
public class EventLogRepository {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(ChangeEvent event) { jpa.save(event); }
    public List<ChangeEvent> findByEntity(String type, String fqn, Instant from, Instant to) { ... }
    public List<ChangeEvent> findByActor(String actor, Instant from, Instant to) { ... }
}

public enum EventType {
    ENTITY_CREATED, ENTITY_UPDATED, ENTITY_DELETED,
    ENTITY_DENIED, ENTITY_NO_CHANGE
}
```

### 6b.7 Discovery as Platform Entity

**DiscoverySource** — a configured data source for automated schema discovery.
Standard CRUD via `EntityResource<DiscoverySource>`. Fields: `name`, `sourceType`,
`connectionConfig`, `schedule`, `status` (ACTIVE/PAUSED/ERROR).

**DiscoveryReport** — result of one scan. Fields: `sourceFQN`, `status`
(RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED/SKIPPED), `tablesScanned`, `tablesFailed`,
`proposalsGenerated`, `proposalsRegistered`, `contentHash`, `durationMs`,
`partialSuccess`.

**DiscoveryResource** provides custom endpoints beyond standard CRUD:
- `POST /v1/discovery/sources/{sourceFQN}/run` — trigger scan
- `GET /v1/discovery/reports?source={fqn}` — scan history

**DiscoveryService.runDiscovery()** full lifecycle with dual output:

```java
public DiscoveryReport runDiscovery(String sourceFQN) {
    DiscoverySource source = sourceRepo.findByFQN(sourceFQN);
    DiscoveryReport report = DiscoveryReport.start(source);
    reportRepo.create(report);

    int metadataCreated = 0, proposalsGenerated = 0, registered = 0;

    try {
        SchemaExtractor extractor = extractors.get(source.getSourceType());
        extractor.prepare(source.toConfig());
        RawSchema schema = runner.run(extractor, getTopology());

        if (isUnchanged(sourceFQN, schema.getContentHash())) {
            return reportRepo.markSkipped(report);
        }

        // Ensure metadata hierarchy exists
        DatabaseService dbSvc = ensureDatabaseService(source);
        Database db = ensureDatabase(dbSvc, schema);
        DatabaseSchema dbSchema = ensureDatabaseSchema(db, schema);

        // Path ①: Metadata entities
        for (RawTable rawTable : schema.tables()) {
            try {
                TableEntity table = buildTableEntity(rawTable, dbSvc, db, dbSchema);
                tableRepo.create(table);
                metadataCreated++;

                for (RawConstraint c : rawTable.constraints()) {
                    if (c.type() == ConstraintType.FOREIGN_KEY) {
                        lineageRepo.create(buildLineage(table, c));
                    }
                }
            } catch (Exception e) {
                report.addError(rawTable.tableName(), "METADATA", e);
            }
        }

        // Path ② + ③: Semantic inference + Mapping rules
        List<ResourceTypeProposal> proposals = inference.infer(schema);
        proposalsGenerated = proposals.size();

        for (ResourceTypeProposal p : proposals) {
            try {
                if (p.isHighConfidence()) {
                    typeRepo.create(p.toResourceType());
                    registered++;
                } else {
                    proposalRepo.create(p.toProposal());
                }
                for (FieldProposal fp : p.fields()) {
                    mappingRepo.create(MappingRule.builder()
                        .typeFQN(p.getFullyQualifiedName())
                        .fieldName(fp.name())
                        .columnFQN(buildColumnFQN(p.sourceTable(), fp.sourceColumn()))
                        .build());
                }
            } catch (Exception e) {
                report.addError(p.proposedTypeName(), "SEMANTIC", e);
            }
        }

        return reportRepo.markSuccess(report, metadataCreated, proposalsGenerated, registered);
    } catch (Exception e) {
        return reportRepo.markFailed(report, e);
    }
}
```

### 6b.8 InferencePipeline — Rule Chain + Proposal Merging

Multiple rules may produce partial proposals for the same table. The pipeline
merges them by `proposedTypeName` using `LinkedHashMap.merge()`.

```java
public class InferencePipeline {
    private final List<InferenceRule> rules;

    public List<ResourceTypeProposal> infer(RawSchema schema) {
        Map<String, ResourceTypeProposal> proposals = new LinkedHashMap<>();

        for (InferenceRule rule : rules) {
            try {
                for (ResourceTypeProposal incoming : rule.infer(schema)) {
                    proposals.merge(incoming.proposedTypeName(), incoming,
                        (existing, inc) -> existing.merge(inc));
                }
            } catch (Exception e) {
                log.warn("Rule {} failed: {}", rule.getClass(), e.getMessage());
            }
        }

        proposals.values().forEach(ResourceTypeProposal::computeGlobalConfidence);
        return new ArrayList<>(proposals.values());
    }
}
```

**ResourceTypeProposal** supports incremental merging — each rule adds its
fields (fields, relationships, abilities, stateMachine) to the proposal.
Confidence is tracked per-field and the global confidence is the minimum
across all fields.

```java
public class ResourceTypeProposal {
    private String proposedTypeName;      // Merge key
    private String sourceTable;
    private List<Field> fields = new ArrayList<>();
    private List<Relationship> relationships = new ArrayList<>();
    private List<Ability> abilities = new ArrayList<>();
    private List<StateTransition> stateMachine = new ArrayList<>();

    // Per-field confidence
    private Confidence fieldsConfidence = Confidence.NONE;
    private Confidence relationshipsConfidence = Confidence.NONE;
    private Confidence abilitiesConfidence = Confidence.NONE;
    private Confidence stateMachineConfidence = Confidence.NONE;

    public ResourceTypeProposal merge(ResourceTypeProposal incoming) {
        if (incoming.fields != null) this.fields.addAll(incoming.fields);
        if (incoming.relationships != null) this.relationships.addAll(incoming.relationships);
        if (incoming.abilities != null) this.abilities.addAll(incoming.abilities);
        if (incoming.stateMachine != null) this.stateMachine.addAll(incoming.stateMachine);
        // Confidence: take minimum
        this.fieldsConfidence = min(this.fieldsConfidence, incoming.fieldsConfidence);
        return this;
    }

    public boolean isHighConfidence() {
        return fieldsConfidence != Confidence.LOW
            && abilitiesConfidence == Confidence.NONE;
    }
}

public enum Confidence { HIGH, MEDIUM, LOW, NONE }
```

**InferenceRule interface:**

```java
public interface InferenceRule {
    Confidence confidence();
    List<ResourceTypeProposal> infer(RawSchema schema);
}
```

**TypeNameInference** (Phase 0, HIGH confidence): maps `customer_orders` → `"CustomerOrder"`.
**FieldMapperInference** (Phase 0, HIGH confidence): maps column name + type → `Field`,
with PostgreSQL → `FieldType` mapping (integer→NUMBER, text→STRING, boolean→BOOLEAN,
timestamp→TIMESTAMP, unknown→STRING).

---

### 6b.9 Metadata Entity JPA Models

Metadata layer entities implement `HeirloomEntity` and follow OpenMetadata's
hierarchical FQN pattern: `{service}.{database}.{schema}.{table}`.

**Entity hierarchy:**
- `DatabaseService` — connection config (serviceType, connectionConfig encrypted)
- `Database` — FQN: `{service}.{name}`
- `DatabaseSchema` — FQN: `{service}.{db}.{name}`
- `TableEntity` — FQN: `{service}.{db}.{schema}.{name}`. Columns stored as JSONB.
- `ColumnEntity` — embedded in TableEntity JSONB (not separate table in Phase 0)
- `LineageEntity` — FQN: `{fromFQN}.{toFQN}.{type}`. References entities by FQN string (not JPA @ManyToOne)
- `TableProfileEntity` — FQN: `{tableFQN}.profile.{timestamp}`. Column-level metrics as JSONB.

**Key design decision:** Column is embedded JSONB (Phase 0 simplification — avoids JOIN overhead).
Lineage uses FQN strings instead of JPA @ManyToOne (from/to may point to different entity types).

### 6b.10 Discovery Engine Dual Output

One scan produces three parallel output streams:

1. **Metadata entities**: RawTable → TableEntity (with embedded ColumnEntity).
   RawConstraint(FK) → LineageEntity. Registered via TableRepository/LineageRepository.
2. **Semantic proposals**: RawTable → InferencePipeline → ResourceTypeProposal.
   Registered via TypeRepository (HIGH confidence) or ProposalRepository (MEDIUM/LOW).
3. **Mapping rules**: Field → Column FQN. Registered via MappingRuleRepository.

Each path has per-table error isolation — one table failure does not abort the scan.
ContentHash-based incremental detection applies to all three paths.

### 6b.11 Mapping Engine — Field to Column FQN Bridge

`MappingRule` stores `columnFQN` (not raw physical path). This enables the Query
Resolver to look up full metadata context: TableProfile (freshness, nullRate),
Column metadata (dataType, nullable), Lineage (where data came from).

Query translation flow:
1. SchemaRegistryService validates ResourceType and Agent capability
2. Mapping Engine resolves each field → Column FQN
3. From Column FQN, parse physical location: `{service}.{db}.{schema}.{table}.{column}`
4. Query Resolver generates SQL
5. Perspective Engine injects Role-based field filtering and metadata context

Schema drift: when underlying Column changes, contentHash mismatch triggers
re-scan. MappingRule's columnFQN remains stable; the ResourceType Proposal
captures the semantic change for human review.

### 6b.12 Generalized Proposal

`Proposal` applies to any entity type (not just ResourceType). Fields:
- `targetEntityType` — "resourceType" / "table" / "glossaryTerm" / ...
- `targetEntityFQN` — null for CREATE proposals
- `proposedChanges` — JSONB, structure varies by targetEntityType
- `changeType` — CREATE / UPDATE / DELETE
- `status` — PENDING / APPROVED / REJECTED
- `source` — "discovery" / "manual" / "agent"

Phase 0 approval: manual via REST API (`POST /v1/proposals/{id}/approve`).
Phase 2: multi-level approval chains, notifications, expiry.
`ProposalService.applyProposal()` resolves the target EntityRepository from
EntityRegistry and applies changes (create/update/delete).

### 6b.13 Perspective Engine + Agent SDK

Perspective Engine does two things:
1. **Field-level visibility**: crop fields based on Role configuration
2. **Metadata context injection** (NEW): for each field, look up Column FQN →
   fetch TableProfile (freshness, nullRate), Lineage summary → inject into response

Agent SDK (Python/TypeScript) provides three operations:
- `discover()` — list available ResourceTypes and fields (Role-filtered)
- `query(jsonDsl)` — structured semantic query → JSON result with metadata context
- `action(type, action, params)` — safe write operation through Action engine (Phase 2)

### 6b.14 Error Handling and Transaction Boundaries

**Exception propagation**: EntityResource → EntityService.validateCreate() →
EntityRepository.create(). Each layer throws typed exceptions caught by
GlobalExceptionHandler → standard error response with diagnostics.

**Transaction boundaries**: `EntityRepository.create()` is `@Transactional`.
All lifecycle steps (setFQN → prepareInternal → storeEntity → storeRelationships)
in one transaction. Any exception → full rollback.

**ChangeEventInterceptor** uses `Propagation.REQUIRES_NEW` — audit events are
written in independent transactions. If the business transaction rolls back,
the audit event persists (we want to know the attempt was made).

**Discovery partial failure**: per-table try/catch. One table's metadata
registration failure does not affect others. DiscoveryReport records all errors.

### 6b.15 ResourceType Lifecycle

Six stages: DISCOVERED → ACTIVE → EVOLVING → STALE → DEPRECATED → DELETED.

- DISCOVERED: Proposal generated by Discovery, awaiting approval
- ACTIVE: Registered, Agent can use
- EVOLVING: Underlying Column changed, UPDATE Proposal pending
- STALE: Underlying Table/Column deleted, MappingRule invalid
- DEPRECATED: Marked for removal, Agent can query but not act
- DELETED: Soft-deleted (deleted=true)

Phase 0: manual lifecycle transitions via Proposal approval.
Phase 1+: automatic stale detection during Discovery scans.

### 6b.16 Query Resolver — JSON DSL to SQL

Translation steps for a semantic query:
1. SchemaRegistryService validates ResourceType exists, Agent has QUERY capability
2. Mapping Engine resolves each field → Column FQN → physical location
3. For traverse (relationship walk): follow Relationship semantics → JOIN
4. Generate SQL: SELECT fields, JOIN if traversing, WHERE from filters, ORDER BY, LIMIT
5. Perspective Engine injects Role-based field filtering

Supported operations (Phase 0): $eq/$neq/$gt/$gte/$lt/$lte/$in/$like,
$and/$or/$not, orderBy, limit/offset, single-step traverse (JOIN).
Phase 1+: aggregation ($count/$sum/$avg, groupBy), subqueries.
Phase 2+: multi-source queries (PostgreSQL + REST API, in-memory JOIN).

### 6b.17 Stale Detection, Security, Testing

**Stale detection** (Phase 1+): after each scan, compare discovered FQNs against
previously registered FQNs. Entities in the DB but not in the source → soft-delete
and generate Deprecation Proposal.

**Security**: DatabaseService.connectionConfig encrypted with AES-256 via JPA
@Converter. API responses never return connectionConfig (@JsonIgnore).

**Testing pyramid** (~93 tests for Phase 0):
- Unit (~40): TypeValidator, InferenceRules, TypeService, DiscoveryRunner, ChangeEventInterceptor
- Repository integration (~25): Testcontainers PG for all EntityRepository subclasses
- Resource integration (~20): MockMvc for all EntityResource subclasses, auth failure scenarios
- Discovery E2E (~8): Testcontainers PG + PostgresSchemaExtractor end-to-end

---

## 7. Key Design Decisions

### 5.1 Why EntityRegistry instead of Spring's built-in dependency injection?

Spring DI resolves beans by type. `EntityRegistry` resolves entities by **entity
type string** (e.g., `"resourceType"` → `TypeRepository`). This enables:
- Generic endpoints that work across entity types
- Change event routing by entity type
- FQN-based lookup without knowing the Java class at compile time

### 5.2 Why EntityRepository base class instead of Spring Data JPA alone?

Spring Data JPA provides `save()` / `findById()`. It does not provide:
- `setFullyQualifiedName()` — FQN construction rules differ per entity type
- `prepare()` — pre-persistence validation (e.g., TypeValidator)
- `storeRelationships()` — cross-entity relationship persistence

The base class enforces these lifecycle steps for every entity type.

### 5.3 Why ChangeEventInterceptor instead of manual event logging?

The `ResponseBodyAdvice` fires after every controller method returns. It
intercepts any response body that implements `HeirloomEntity` and was
produced by a non-GET request. This means:
- Developers never call event logging manually
- Skipped audit is architecturally impossible

### 5.4 Why pluggable Authorizer?

Phase 0 has no auth needs. Phase 2 introduces Heirloom's Role → Capability →
Action model. The `Authorizer` interface allows the switch without touching
any Resource or Repository code.

### 5.5 Two-phase Discovery (extract → infer) instead of streaming

Unlike OpenMetadata's streaming ingestion (entity-by-entity to sink),
Heirloom's Discovery Engine collects the full `RawSchema` before inference
because:
- Relationship inference needs global view (FK may reference a table not yet scanned)
- StateMachine inference needs to see all constraints across tables
- Abilities inference benefits from cross-table naming conventions

---

## 8. Phase 0 Scope

### 6.1 Deliverables

1. **EntityRegistry** with all Phase 0 entity types registered (11 types)
2. **HeirloomEntity** interface + **EntityRepository** base class
3. **TypeRepository** refactored to extend EntityRepository; **TypeService** implements EntityService<ResourceType>
4. **TypeResource** using EntityResource base (standard CRUD)
5. **ChangeEventInterceptor** for automatic audit
6. **NoopAuthorizer** for Phase 0 auth (allow all)
7. **Metadata layer entities**: DatabaseService, Database, DatabaseSchema, TableEntity — JPA models + Repository + Resource
8. **DiscoverySource** + **DiscoveryReport** entities (CRUD via standard endpoints)
9. **PostgresSchemaExtractor** + **DiscoveryRunner** with dual output:
   - Metadata entities: TableEntity (with embedded ColumnEntity), LineageEntity
   - Semantic proposals: TypeNameInference + FieldMapperInference (HIGH confidence)
10. **MappingRule** creation from discovery (field → Column FQN)
11. **DiscoveryResource** with `POST /v1/discovery/sources/{fqn}/run`
12. **Database migration**: V1 (existing) + V2 (12 tables, see V2__platform_metadata_semantic.sql)

### 6.2 Out of Scope (Phase 1+)

- RelationshipInference (MEDIUM confidence)
- Role / Capability / Action entities
- RoleBasedAuthorizer
- MySQL / dbt extractors
- Proposal approval workflow (Phase 2)
- StateMachineInference, AbilityInference

---

## 9. Migration from Current Codebase

### 7.1 Files to Keep (refactored)

| Current File | Action |
|-------------|--------|
| `ResourceType.java` | Add `implements HeirloomEntity`. Add `fullyQualifiedName`, `changeHash`, `domain` fields. |
| `Field.java` | No change |
| `Ability.java` | No change |
| `Relationship.java` | No change |
| `StateTransition.java` | No change |
| `TypeValidator.java` | No change (called from `TypeRepository.prepareInternal()`) |
| `TypeController.java` | Replace with `TypeResource extends EntityResource<ResourceType>` (constructor: `Authorizer` only) |
| `SchemaRegistryService.java` | Refactor to `TypeService.java` implementing `EntityService<ResourceType>` |

### 7.2 Files to Add

All files listed in Section 4 under `entity/`, `repository/`, `web/`, `interceptor/`,
`auth/`, and `discovery/`. Approximately 35 new files across 6 new packages.

### 7.3 Full Database Migration SQL

```sql
-- Modify existing resource_types table (backward compatible)
ALTER TABLE resource_types
  ADD COLUMN fully_qualified_name VARCHAR(512),
  ADD COLUMN domain VARCHAR(128) DEFAULT 'default',
  ADD COLUMN change_hash VARCHAR(64);

-- Backfill FQN for existing data
UPDATE resource_types
  SET fully_qualified_name = 'default.' || name
  WHERE fully_qualified_name IS NULL;

ALTER TABLE resource_types
  ADD CONSTRAINT uq_resource_types_fqn UNIQUE (fully_qualified_name);

-- New table: discovery_sources
CREATE TABLE discovery_sources (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  source_type VARCHAR(64) NOT NULL,
  environment VARCHAR(64) DEFAULT 'prod',
  connection_config JSONB,
  schedule VARCHAR(64) DEFAULT 'manual',
  status VARCHAR(32) DEFAULT 'ACTIVE',
  description TEXT,
  owner VARCHAR(256),
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- New table: discovery_reports
CREATE TABLE discovery_reports (
  id BIGSERIAL PRIMARY KEY,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  source_fqn VARCHAR(512) NOT NULL,
  status VARCHAR(32) NOT NULL,
  tables_scanned INTEGER DEFAULT 0,
  tables_skipped INTEGER DEFAULT 0,
  tables_failed INTEGER DEFAULT 0,
  proposals_generated INTEGER DEFAULT 0,
  proposals_registered INTEGER DEFAULT 0,
  content_hash VARCHAR(64),
  duration_ms BIGINT,
  error_summary JSONB,
  partial_success BOOLEAN DEFAULT FALSE,
  description TEXT,
  owner VARCHAR(256),
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- See V2__platform_metadata_semantic.sql for full migration.
-- Summary: 12 tables total (1 existing extended + 11 new)
-- Platform: discovery_sources, discovery_reports, event_log
-- Metadata: database_services, metadata_databases, metadata_schemas, metadata_tables, metadata_lineage
-- Semantic: proposals, mapping_rules
-- resource_types table extended with fully_qualified_name, domain, change_hash, deleted columns.

-- New table: event_log (append-only, shown here; full migration in V2 SQL)
CREATE TABLE event_log (
  id BIGSERIAL PRIMARY KEY,
  entity_type VARCHAR(64) NOT NULL,
  entity_id BIGINT,
  entity_fqn VARCHAR(512),
  event_type VARCHAR(32) NOT NULL,
  actor VARCHAR(256),
  version BIGINT,
  change_hash VARCHAR(64),
  denied_reason TEXT,
  denied_operation VARCHAR(64),
  timestamp TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_event_log_entity ON event_log(entity_type, entity_fqn, timestamp DESC);
CREATE INDEX idx_event_log_actor  ON event_log(actor, timestamp DESC);
```

### 7.4 Complete Call Chain Example: POST /v1/resourceTypes

```
POST /v1/resourceTypes  { "name": "Customer", "domain": "crm", ... }
│
├─ TypeResource.create(@RequestBody CreateTypeRequest)  // Constructor: TypeResource(Authorizer auth) only
│   │  Spring deserializes CreateTypeRequest (concrete type)
│   │
│   ├─ authorizer.authorize(actor, "resourceType", "CREATE", null)
│   │
│   ├─ [EntityService layer]
│   │   ├─ typeService.buildEntity(request)  → new ResourceType("Customer")
│   │   └─ typeService.validateCreate(entity) // Business: uniqueness check
│   │
│   ├─ [EntityRepository layer]
│   │   └─ typeRepo.create(entity)            // Template method (final)
│   │       ├─ setFullyQualifiedName(entity)   → "crm.Customer"
│   │       ├─ prepareInternal(entity, false)  → TypeValidator.validate()
│   │       ├─ storeEntity(entity, false)      → computeHash() → jpa.save()
│   │       └─ storeRelationships(entity)      → no-op (embedded JSONB)
│   │
│   └─ return ResponseEntity.status(201).body(entity)
│
├─ [ChangeEventInterceptor auto-fires]
│   └─ body instanceof HeirloomEntity + non-GET →
│       build ChangeEvent(ENTITY_CREATED) → eventLog.append()
│
└─ Response: 201 Created { id: 1, fqn: "crm.Customer", ... }
```

---

## 10. Open Questions

1. **Schema layer format:** Phase 0 uses Java interfaces + JPA annotations. Should
   we introduce JSON Schema definitions as the source of truth (like OpenMetadata's
   `openmetadata-spec/`) in Phase 1, or stay with Java-first?

2. **Graph Store:** `storeRelationships()` currently stores relationships inline in
   the `ResourceType` JSONB. When should we introduce a dedicated Graph Store
   (Neo4j / PostgreSQL adjacency table)?

3. **Multi-tenant FQN:** Should FQN include tenant/org prefix? Currently: `{domain}.{name}`.
   With multi-tenancy: `{tenant}.{domain}.{name}`.

4. **Event Log storage:** Append-only PostgreSQL partition table (Phase 0) vs
   dedicated log store / Kafka (Phase 4)?
