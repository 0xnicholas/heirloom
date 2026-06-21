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
| `ResourceType.java` | Add `implements HeirloomEntity`. Add `fqn`, `changeHash`, `domain` fields. |
| `Field.java` | No change |
| `Ability.java` | No change |
| `Relationship.java` | No change |
| `StateTransition.java` | No change |
| `TypeValidator.java` | No change (called from `TypeRepository.prepare()`) |
| `TypeController.java` | Replace with `TypeResource extends EntityResource<ResourceType>` |

### 7.2 Files to Add

All files listed in Section 4 under `entity/`, `repository/`, `web/`, `interceptor/`,
`auth/`, and `discovery/`.

### 7.3 Database Migration

Add columns to `resource_types` table:
- `fully_qualified_name VARCHAR(512) UNIQUE`
- `change_hash VARCHAR(64)`
- `domain VARCHAR(128)`

New tables:
- `proposals`
- `discovery_sources`
- `discovery_reports`
- `mapping_rules`
- `event_log` (or extend existing)

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
