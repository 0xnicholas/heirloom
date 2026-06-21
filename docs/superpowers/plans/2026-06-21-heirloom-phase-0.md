# Heirloom Phase 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Heirloom platform skeleton with two-layer architecture (metadata catalog + semantic layer), Discovery Engine with PostgreSQL connector, automatic audit, and Python Agent SDK.

**Architecture:** Spring Boot + JPA platform with EntityRegistry/EntityResource/EntityRepository patterns. Discovery uses declarative Topology tree for schema extraction and InferencePipeline for semantic proposal generation. Dual output: metadata entities (Table/Column) and semantic proposals (ResourceType).

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA, PostgreSQL + JSONB, Testcontainers, Python 3.11+ (SDK)

**Reference:** OpenMetadata source at `_references/OpenMetadata-main/`

---

## File Structure

```
heirloom-server/src/main/java/com/heirloom/
  entity/
    EntityRegistry.java          (new)
    HeirloomEntity.java          (new)
    EntityRegistration.java      (new)
  repository/
    EntityRepository.java        (new)
    TypeRepository.java          (refactor)
    ProposalRepository.java      (new)
    DiscoverySourceRepository.java  (new)
    DiscoveryReportRepository.java  (new)
    MappingRuleRepository.java   (new)
    EventLogRepository.java      (new)
    TableRepository.java         (new)
    DatabaseServiceRepository.java (new)
    DatabaseRepository.java      (new)
    DatabaseSchemaRepository.java  (new)
    LineageRepository.java       (new)
  web/
    EntityResource.java          (new)
    TypeResource.java            (refactor from TypeController)
    ProposalResource.java        (new)
    DiscoveryResource.java       (new)
    EventResource.java           (new)
  interceptor/
    ChangeEventInterceptor.java  (new)
  auth/
    Authorizer.java              (new)
    NoopAuthorizer.java          (new)
  schema/domain/                 (existing — minor refactors)
    ResourceType.java            (add HeirloomEntity impl)
    Proposal.java                (new)
  schema/service/
    TypeService.java             (refactor from SchemaRegistryService)
    TypeValidator.java           (unchanged)
  metadata/domain/
    DatabaseService.java         (new)
    Database.java                (new)
    DatabaseSchema.java          (new)
    TableEntity.java             (new)
    ColumnEntity.java            (new — embedded record)
    LineageEntity.java           (new)
  discovery/                     (all new)
    domain/  DiscoverySource.java, DiscoveryReport.java
    service/ DiscoveryService.java
    topology/ DiscoveryTopology.java, DiscoveryNode.java, DiscoveryStage.java, DiscoveryContext.java
    runner/  DiscoveryRunner.java
    extractor/ SchemaExtractor.java, postgres/PostgresSchemaExtractor.java
    model/   RawSchema.java, RawTable.java, RawColumn.java, RawConstraint.java, RawRelationship.java
    inference/ InferencePipeline.java, InferenceRule.java, rules/TypeNameInference.java, FieldMapperInference.java

heirloom-sdk/ (new Python project)
  heirloom_sdk/
    __init__.py
    client.py
    models.py
  tests/
    test_client.py

heirloom-server/src/main/resources/db/migration/
  V2__platform_metadata_semantic.sql (already created)
```

---


### Task 3: EntityRepository Base Class + TypeRepository Refactor

**Files:**
- Create: `heirloom-server/src/main/java/com/heirloom/repository/EntityRepository.java`
- Modify: `heirloom-server/src/main/java/com/heirloom/schema/repository/ResourceTypeRepository.java` → move to `heirloom-server/src/main/java/com/heirloom/repository/TypeRepository.java`

- [ ] **Step 1: Create EntityRepository abstract base class**

```java
package com.heirloom.repository;

import com.heirloom.entity.HeirloomEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

public abstract class EntityRepository<E extends HeirloomEntity> {
    protected final String entityType;
    protected final Class<E> entityClass;
    protected final JpaRepository<E, Long> jpaRepository;

    protected EntityRepository(String entityType, Class<E> entityClass, JpaRepository<E, Long> jpa) {
        this.entityType = entityType;
        this.entityClass = entityClass;
        this.jpaRepository = jpa;
    }

    protected abstract void setFullyQualifiedName(E entity);
    protected abstract void prepareInternal(E entity, boolean isUpdate);

    protected void storeEntity(E entity, boolean isUpdate) { jpaRepository.save(entity); }
    protected void storeRelationships(E entity) { /* default no-op */ }

    @Transactional
    public final E create(E entity) {
        setFullyQualifiedName(entity);
        prepareInternal(entity, false);
        storeEntity(entity, false);
        storeRelationships(entity);
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

    public Optional<E> findById(Long id) { return jpaRepository.findById(id); }
    @Transactional
    public void delete(Long id) { jpaRepository.deleteById(id); }
}
```

- [ ] **Step 2: Create TypeRepository extending EntityRepository**

In `heirloom-server/src/main/java/com/heirloom/repository/TypeRepository.java` — copy existing `ResourceTypeRepository` logic, extend `EntityRepository<ResourceType>`, implement `setFullyQualifiedName()` and `prepareInternal()` (calling `TypeValidator`).

- [ ] **Step 3: Run existing TypeValidator/SchemaRegistry tests**

Run: `cd heirloom-server && ./mvnw test -pl .`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add heirloom-server/src/main/java/com/heirloom/repository/
git commit -m "feat: add EntityRepository base class, TypeRepository extends it"
```

### Task 4: EntityResource Base Class + TypeResource

**Files:**
- Create: `heirloom-server/src/main/java/com/heirloom/web/EntityResource.java`
- Modify: `heirloom-server/src/main/java/com/heirloom/schema/web/TypeController.java` → replace with `TypeResource.java`

- [ ] **Step 1: Create EntityResource abstract base**

```java
package com.heirloom.web;

import com.heirloom.auth.Authorizer;
import com.heirloom.entity.EntityRegistry;
import com.heirloom.entity.HeirloomEntity;
import com.heirloom.repository.EntityRepository;
import com.heirloom.service.EntityService;

public abstract class EntityResource<E extends HeirloomEntity> {
    protected final String entityType;
    protected final EntityRepository<E> repository;
    protected final EntityService<E> service;
    protected final Authorizer authorizer;

    @SuppressWarnings("unchecked")
    protected EntityResource(String entityType, Authorizer authorizer) {
        this.entityType = entityType;
        this.authorizer = authorizer;
        this.repository = (EntityRepository<E>) EntityRegistry.getRepository(entityType);
        this.service = (EntityService<E>) EntityRegistry.getService(entityType);
    }

    // Standard CRUD template methods go here (list, getById, getByFQN, create, delete)
}
```

- [ ] **Step 2: Create TypeResource (replaces TypeController)**

Move `TypeController.java` → `web/TypeResource.java`, extend `EntityResource<ResourceType>`. Delegate standard CRUD to base methods. Constructor: `TypeResource(Authorizer auth)`.

- [ ] **Step 3: Run integration tests**

Run: `cd heirloom-server && ./mvnw test -Dtest=SchemaRegistryIntegrationTest`
Expected: CRUD operations work through new TypeResource

- [ ] **Step 4: Commit**

### Task 5: NoopAuthorizer

**Files:** Create `auth/Authorizer.java`, `auth/NoopAuthorizer.java`

- [ ] Step 1: Create Authorizer interface
- [ ] Step 2: Create NoopAuthorizer (allow all)
- [ ] Step 3: Wire into TypeResource
- [ ] Step 4: Commit

### Task 6: ChangeEventInterceptor + EventLogRepository

**Files:** Create `interceptor/ChangeEventInterceptor.java`, `repository/EventLogRepository.java`, `domain/ChangeEvent.java`

- [ ] Step 1: Create ChangeEvent JPA entity
- [ ] Step 2: Create EventLogRepository (append-only)
- [ ] Step 3: Create ChangeEventInterceptor (ResponseBodyAdvice)
- [ ] Step 4: Write test verifying non-GET operations produce events
- [ ] Step 5: Run tests, commit

### Task 7: Metadata Layer Entities — DatabaseService, Database, Schema, Table

**Files:** Create `metadata/domain/*.java`, `metadata/repository/*.java`

- [ ] Step 1: Create DatabaseService, Database, DatabaseSchema, TableEntity JPA entities
- [ ] Step 2: Create ColumnEntity record (embedded in TableEntity JSONB)
- [ ] Step 3: Create TableRepository extending EntityRepository
- [ ] Step 4: Write repository integration tests (Testcontainers PG)
- [ ] Step 5: Register all in EntityRegistry, commit

### Task 8: Proposal Entity + Repository

**Files:** Create `schema/domain/Proposal.java`, `repository/ProposalRepository.java`, `web/ProposalResource.java`

- [ ] Step 1: Create Proposal JPA entity (generalized: targetEntityType, targetEntityFQN, proposedChanges JSONB)
- [ ] Step 2: Create ProposalRepository + ProposalResource
- [ ] Step 3: Write CRUD tests, commit

### Task 9: Discovery Entities — DiscoverySource + DiscoveryReport

**Files:** Create `discovery/domain/DiscoverySource.java`, `discovery/domain/DiscoveryReport.java`, their repositories and resources

- [ ] Step 1: Create DiscoverySource, DiscoveryReport JPA entities
- [ ] Step 2: Create repositories + register in EntityRegistry
- [ ] Step 3: Commit

### Task 10: Discovery Topology + Runner

**Files:** Create `discovery/topology/*.java`, `discovery/runner/DiscoveryRunner.java`

- [ ] Step 1: Create DiscoveryNode, DiscoveryStage, DiscoveryTopology
- [ ] Step 2: Create DiscoveryContext (thread-safe RawSchema builder)
- [ ] Step 3: Create DiscoveryRunner (processNodes, depth-first traversal)
- [ ] Step 4: Write unit tests with mock SchemaExtractor
- [ ] Step 5: Commit

### Task 11: SchemaExtractor Interface + PostgresSchemaExtractor

**Files:** Create `discovery/extractor/SchemaExtractor.java`, `discovery/extractor/postgres/PostgresSchemaExtractor.java`, `discovery/model/*.java`

- [ ] Step 1: Create RawSchema, RawTable, RawColumn, RawConstraint data classes
- [ ] Step 2: Create SchemaExtractor interface
- [ ] Step 3: Implement PostgresSchemaExtractor (JDBC queries against information_schema)
- [ ] Step 4: Write integration test with Testcontainers PG — verify RawSchema extraction
- [ ] Step 5: Commit

### Task 12: InferencePipeline — TypeName + FieldMapper

**Files:** Create `discovery/inference/*.java`

- [ ] Step 1: Create InferenceRule interface, InferencePipeline
- [ ] Step 2: Implement TypeNameInference (snake_case → PascalCase)
- [ ] Step 3: Implement FieldMapperInference (PG type → FieldType mapping)
- [ ] Step 4: Write unit tests for each inference rule
- [ ] Step 5: Commit

### Task 13: DiscoveryService — Dual Output

**Files:** Create `discovery/service/DiscoveryService.java`, `web/DiscoveryResource.java`

- [ ] Step 1: Implement DiscoveryService.runDiscovery() with dual output (metadata entities + semantic proposals)
- [ ] Step 2: Create DiscoveryResource (POST /run endpoint)
- [ ] Step 3: Write integration test — Testcontainers PG → full scan → verify Table entities + ResourceType entities created
- [ ] Step 4: Commit

### Task 14: MappingRule Entity + Repository

**Files:** Create `domain/MappingRule.java`, `repository/MappingRuleRepository.java`

- [ ] Step 1: Create MappingRule JPA entity (typeFQN, fieldName, columnFQN)
- [ ] Step 2: Create repository + register in EntityRegistry
- [ ] Step 3: Integrate into DiscoveryService (create MappingRules during scan)
- [ ] Step 4: Commit

### Task 15: Python Agent SDK

**Files:** Create `heirloom-sdk/` Python project

- [ ] Step 1: Set up Python project (pyproject.toml, package structure)
- [ ] Step 2: Implement HeirloomClient with discover() and query()
- [ ] Step 3: Write integration test against running Heirloom (Testcontainers)
- [ ] Step 4: Commit

### Task 16: End-to-End Integration Test

**Files:** Create `heirloom-server/src/test/java/com/heirloom/DiscoveryE2ETest.java`

- [ ] Step 1: Set up Testcontainers PG with known schema (customers, orders tables)
- [ ] Step 2: Run full discovery → verify metadata entities created
- [ ] Step 3: Verify semantic proposals generated (TypeName + FieldMapper)
- [ ] Step 4: Verify MappingRules created
- [ ] Step 5: Verify ChangeEvents recorded
- [ ] Step 6: Commit

