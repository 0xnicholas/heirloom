# Heirloom Architecture Redesign — Design Specification

**Date:** 2026-06-21
**Status:** Draft
**Reference:** OpenMetadata (open-metadata/OpenMetadata)

---

## 1. Motivation

### 1.1 Problem

The current Heirloom architecture has the following structural gaps when compared
to production-grade metadata platforms like OpenMetadata:

| Gap | Current State | Consequence |
|-----|--------------|-------------|
| No Entity registry | Types scattered across JPA entities with no central lookup | Adding a new entity type requires ad-hoc wiring; no standard discoverability |
| No standard REST pattern | Each Controller hand-written with inconsistent pagination, field filtering, error handling | Developer overhead per endpoint; inconsistent API surface |
| No standard Repository lifecycle | Spring Data JPA `save()` used directly, no `prepare()` / `storeRelationships()` | Cross-entity consistency logic lives in Service layer (duplicated or forgotten) |
| Manual audit | Event Log calls must be remembered by each developer | Audit gaps are inevitable |
| No pluggable authorization | Hardcoded or absent | Cannot evolve from dev-mode (no auth) to production (RBAC) |
| Discovery as tool, not entity | Discovery Engine designed as a standalone callback | Cannot query, schedule, or govern discovery through the platform API |

### 1.2 Goal

Redesign Heirloom's architecture using OpenMetadata's platform patterns as reference,
while preserving Heirloom's unique value: **AI Agent as first-class data consumer with
type-level safety guarantees.**

---

## 2. Reference Platform: OpenMetadata Architecture

OpenMetadata's architecture is organized into 8 layers. The layers Heirloom should
adopt (with Heirloom-specific adaptations) are:

### Layer 1: Entity Registry (`Entity.java`)

A central singleton registry where every entity type is registered with its
Java class, Repository, Service, collection path, and FQN template.

**Heirloom adaptation:** `EntityRegistry` class with constants for:
- Core: `RESOURCE_TYPE`, `PROPOSAL`, `MAPPING_RULE`
- Discovery: `DISCOVERY_SOURCE`, `DISCOVERY_REPORT`
- Governance (future): `ROLE`, `CAPABILITY`, `ACTION`, `FUNCTION`
- Audit: `EVENT`, `ACTOR`

### Layer 2: Entity Interface (`EntityInterface`)

All entities implement a common interface providing: `getId()`, `getEntityType()`,
`getFullyQualifiedName()`, `getName()`, `getDescription()`, `getOwner()`,
`getVersion()`, `getCreatedAt()`, `getUpdatedAt()`, `getChangeHash()`.

**Heirloom adaptation:** `HeirloomEntity` interface. Existing `ResourceType` and
all new entities implement it.

### Layer 3: Entity Repository (`EntityRepository<E>`)

Abstract base class providing standard lifecycle methods:
- `prepare(entity, isUpdate)` — validate references, resolve owner
- `setFullyQualifiedName(entity)` — build FQN from parent context
- `storeEntity(entity, isUpdate)` — persist with change hash
- `storeRelationships(entity)` — persist cross-entity relationships
- Template methods: `create()` and `update()` orchestrate the above

**Heirloom adaptation:** Java abstract class wrapping Spring Data JPA.
`TypeRepository`, `ProposalRepository`, `DiscoverySourceRepository`,
`DiscoveryReportRepository`, `MappingRuleRepository` all extend this base.

### Layer 4: Entity Resource (`EntityResource<E>`)

Abstract REST controller providing standard CRUD endpoints:
- `GET /v1/{type}s` — paginated list with field filtering
- `GET /v1/{type}s/{id}` — get by ID
- `GET /v1/{type}s/name/{fqn}` — get by FQN
- `POST /v1/{type}s` — create
- `PATCH /v1/{type}s/{id}` — partial update (JSON Patch)
- `DELETE /v1/{type}s/{id}` — soft delete

**Heirloom adaptation:** Java abstract class using Spring MVC annotations.

### Layer 5: Change Event Interceptor

A Spring `ResponseBodyAdvice` that intercepts all non-GET responses containing
`HeirloomEntity` and automatically produces `ChangeEvent` entries. Resource
developers never call event logging directly.

### Layer 6: Authorizer (Pluggable)

An `Authorizer` interface with `authorize(actor, entityType, operation, fqn)`.
Phase 0 uses `NoopAuthorizer`. Phase 2 switches to `RoleBasedAuthorizer` using
Heirloom's Role → Capability → Action model.

---

## 3. Heirloom Entity Catalog

### 3.1 Phase 0 Entities

| Entity Type | FQN Pattern | Purpose |
|-------------|------------|---------|
| `resourceType` | `{domain}.{name}` | Business semantic type definition (Customer, Order, etc.) |
| `proposal` | `{domain}.{name}.proposal-{id}` | Schema change proposal for review/approval |
| `discoverySource` | `{env}.{serviceName}` | A configured data source for automated schema discovery |
| `discoveryReport` | `{sourceFQN}.{timestamp}` | Result of one discovery scan |
| `mappingRule` | `{typeFQN}.{field}` | Field → physical data source mapping |

### 3.2 Future Phase Entities

| Entity Type | Purpose |
|-------------|---------|
| `role` | Actor permission boundary definition |
| `capability` | Temporary access ticket derived from Role |
| `action` | Write operation definition (the only write path) |
| `function` | Pure computation definition |
| `event` | Immutable audit log entry |
| `actor` | Human user / AI Agent / Automation identity |

---

## 4. Module Structure

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

  schema/                          *** REFACTORED ***
  ├── domain/
  │   ├── ResourceType.java        → implements HeirloomEntity
  │   ├── Proposal.java            NEW
  │   ├── Field.java               (unchanged)
  │   ├── Ability.java             (unchanged)
  │   ├── Relationship.java        (unchanged)
  │   ├── StateTransition.java     (unchanged)
  │   └── FieldType.java           (unchanged)
  ├── service/
  │   ├── TypeService.java         → implements EntityService
  │   └── TypeValidator.java       (unchanged, called from TypeRepository.prepare())

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

## 4a. OpenMetadata Source Code Reference

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

## 4b. Component Deep-Dives

### 4b.1 EntityRegistry — Decentralized Registration (OM pattern)

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

### 4b.2 HeirloomEntity Interface

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

### 4b.3 EntityService Interface

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

### 4b.4 EntityRepository — Wrapping Spring Data JPA

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

### 4b.5 EntityResource — Gets Repository from EntityRegistry (OM pattern)

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

### 4b.6 ChangeEventInterceptor — Automatic Audit (Spring equivalent of OM's ChangeEventHandler)

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

### 4b.7 Discovery as Platform Entity

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

**DiscoveryService.runDiscovery()** full lifecycle:

```java
public DiscoveryReport runDiscovery(String sourceFQN) {
    // 1. Load source entity
    DiscoverySource source = sourceRepo.findByFQN(sourceFQN);

    // 2. Create report (status: RUNNING) — ChangeEventInterceptor auto-logs
    DiscoveryReport report = DiscoveryReport.start(source);
    reportRepo.create(report);

    try {
        // 3. Phase 1: Extract raw schema
        SchemaExtractor extractor = extractors.get(source.getSourceType());
        extractor.prepare(source.toConfig());
        RawSchema schema = runner.run(extractor, getTopology());

        // 4. Incremental check (contentHash comparison)
        if (isUnchanged(sourceFQN, schema.getContentHash())) {
            return reportRepo.markSkipped(report);
        }

        // 5. Phase 2: Infer proposals
        List<ResourceTypeProposal> proposals = inference.infer(schema);

        // 6. Register — split by confidence
        int registered = 0;
        for (ResourceTypeProposal p : proposals) {
            if (p.isHighConfidence()) {
                typeRepo.create(p.toResourceType());     // Auto-register
                registered++;
            } else {
                proposalRepo.create(p.toProposal());     // Awaiting approval
            }
            mappingRepo.create(p.toMappingRule());       // Field → source mapping
        }

        return reportRepo.markSuccess(report, proposals.size(), registered);
    } catch (Exception e) {
        return reportRepo.markFailed(report, e);
    }
}
```

### 4b.8 InferencePipeline — Rule Chain + Proposal Merging

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

## 5. Key Design Decisions

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

## 6. Phase 0 Scope

### 6.1 Deliverables

1. **EntityRegistry** with all Phase 0 entity types registered
2. **HeirloomEntity** interface + **EntityRepository** base class
3. **TypeRepository** refactored to extend EntityRepository
4. **TypeResource** using EntityResource base (standard CRUD)
5. **ChangeEventInterceptor** for automatic audit
6. **NoopAuthorizer** for Phase 0 auth (allow all)
7. **DiscoverySource** + **DiscoveryReport** entities (CRUD via standard endpoints)
8. **PostgresSchemaExtractor** + **DiscoveryRunner** + **TypeNameInference** + **FieldMapperInference**
9. **DiscoveryResource** with `POST /v1/discovery/run`

### 6.2 Out of Scope (Phase 1+)

- RelationshipInference (MEDIUM confidence)
- Role / Capability / Action entities
- RoleBasedAuthorizer
- MySQL / dbt extractors
- Proposal approval workflow (Phase 2)
- StateMachineInference, AbilityInference

---

## 7. Migration from Current Codebase

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
| `SchemaRegistryService.java` | Refactor to implement `EntityService<ResourceType>` |

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

-- New table: proposals
CREATE TABLE proposals (
  id BIGSERIAL PRIMARY KEY,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  target_type_fqn VARCHAR(512),
  proposed_changes JSONB NOT NULL,
  status VARCHAR(32) DEFAULT 'PENDING',
  description TEXT,
  owner VARCHAR(256),
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- New table: mapping_rules
CREATE TABLE mapping_rules (
  id BIGSERIAL PRIMARY KEY,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  type_fqn VARCHAR(512) NOT NULL,
  field_name VARCHAR(256) NOT NULL,
  source_id VARCHAR(512) NOT NULL,
  physical_path VARCHAR(1024) NOT NULL,
  description TEXT,
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- New table: event_log (append-only)
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

## 8. Open Questions

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
