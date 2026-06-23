# Knowledge Audit Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add server-side audit event emission to `KnowledgeArticleResource` so the existing Phase 3.4 dashboard surfaces 3 new event types (KNOWLEDGE_SEARCH, KNOWLEDGE_CONTEXT_FETCH, KNOWLEDGE_ACCESS_DENIED) and 1 new endpoint (`/v1/knowledge/context`).

**Architecture:** Additive instrumentation on existing read endpoints + 1 new endpoint. New `details` JSONB column on `event_log` carries per-event-type metadata. New `Visibility` tri-state enum on `KnowledgePerspectiveFilter` distinguishes "policy-blocked" from "not found" so denials emit a separate event. New endpoints never break existing client behavior — all responses keep their existing shape.

**Tech Stack:** Spring Boot 3, JPA/Hibernate 6, hypersistence-utils 3.9.10 (`@Type(JsonType.class)`), JUnit 5, Mockito, AssertJ, PostgreSQL JSONB.

**Spec:** `docs/superpowers/specs/2026-06-23-knowledge-audit-events.md`
**Test framework:** Maven (`./mvnw test` from `heirloom-server/`). Run a single test class with `./mvnw -pl heirloom-server test -Dtest=ClassName`.

**Conventions observed in this codebase:**
- Migration file pattern: `V{n}__description.sql` (see `V11__cross_ontology_mappings.sql`)
- JSONB map pattern: `@Type(JsonType.class) @Column(columnDefinition = "jsonb") private Map<String, Object> field;` (see `KnowledgeArticle.java:25`)
- Event-log pattern: `eventLog.append(captureEvent)` in `Propagation.REQUIRES_NEW` (see `FunctionService.java:64-72`)
- Test pattern: `@Mock EventLogRepository eventLog;` + `verify(eventLog).append(captor.capture())` (see `FunctionServiceTest.java`)

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `heirloom-server/src/main/resources/db/migration/V12__event_log_details.sql` | Add JSONB `details` column to `event_log` | **Create** |
| `heirloom-server/src/main/java/com/heirloom/domain/ChangeEvent.java` | Add `details` field + 3 new `EventType` values | **Modify** |
| `heirloom-server/src/main/java/com/heirloom/knowledge/service/KnowledgePerspectiveFilter.java` | Add `Visibility` enum + `checkVisibility()`; refactor `canSee()` to delegate | **Modify** |
| `heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java` | Inject `HttpServletRequest` in 6 endpoints; add `emitKnowledgeEvent` helper; instrument 5 read endpoints + 1 new context endpoint | **Modify** |
| `heirloom-server/src/test/java/com/heirloom/repository/EventLogDetailsRoundTripIT.java` | Integration test for JSONB `details` round-trip | **Create** |
| `heirloom-server/src/test/java/com/heirloom/knowledge/service/KnowledgePerspectiveFilterVisibilityTest.java` | Unit test for `Visibility` enum + `checkVisibility()` | **Create** |
| `heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeArticleEventInstrumentationTest.java` | Unit test for event emission in all 6 endpoints | **Create** |
| `heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeContextEndpointIT.java` | Integration test for `/v1/knowledge/context` end-to-end | **Create** |
| `docs/ROADMAP.md` | Mark "知识审计事件" done; add v0.14 changelog row | **Modify** |

---

## Task 1: V12 migration + ChangeEvent field

**Files:**
- Create: `heirloom-server/src/main/resources/db/migration/V12__event_log_details.sql`
- Modify: `heirloom-server/src/main/java/com/heirloom/domain/ChangeEvent.java`
- Test: `heirloom-server/src/test/java/com/heirloom/repository/EventLogDetailsRoundTripIT.java`

- [ ] **Step 1: Write the failing test for `details` round-trip**

Create `EventLogDetailsRoundTripIT.java`:

```java
package com.heirloom.repository;

import com.heirloom.HeirloomApplication;
import com.heirloom.domain.ChangeEvent;
import com.heirloom.repository.EventLogJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = HeirloomApplication.class
)
@Testcontainers
@ActiveProfiles("test")
class EventLogDetailsRoundTripIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired EventLogJpaRepository jpa;

    @Test
    void detailsRoundTripsThroughJsonb() {
        ChangeEvent e = new ChangeEvent();
        e.setEventType(ChangeEvent.EventType.KNOWLEDGE_SEARCH);
        e.setActor("test:test-roundtrip-" + System.nanoTime());
        e.setEntityType("knowledge");
        e.setDetails(Map.of(
            "path", "/v1/knowledge/search",
            "resultCount", 7,
            "trimmedCount", 2,
            "_v", 1
        ));
        jpa.save(e);
        jpa.flush();

        Long id = e.getId();
        assertThat(id).isNotNull();
        ChangeEvent loaded = jpa.findById(id).orElseThrow();
        assertThat(loaded.getDetails()).isNotNull();
        assertThat(loaded.getDetails().get("path")).isEqualTo("/v1/knowledge/search");
        assertThat(((Number) loaded.getDetails().get("resultCount")).intValue()).isEqualTo(7);
        assertThat(((Number) loaded.getDetails().get("trimmedCount")).intValue()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd heirloom-server && ./mvnw test -Dtest=EventLogDetailsRoundTripIT`
Expected: FAIL with compilation error (no `setDetails` method on `ChangeEvent`).

- [ ] **Step 3: Write V12 migration**

Create `heirloom-server/src/main/resources/db/migration/V12__event_log_details.sql`:

```sql
-- V12: Add JSONB details column to event_log for Phase 3.1 knowledge audit events.
-- See docs/superpowers/specs/2026-06-23-knowledge-audit-events.md §3.1.
-- Intentionally no GIN index — current dashboards do not filter on details.

ALTER TABLE event_log ADD COLUMN details JSONB;
```

- [ ] **Step 4: Modify `ChangeEvent.java` — add `details` field + 3 new `EventType` values**

In `heirloom-server/src/main/java/com/heirloom/domain/ChangeEvent.java`:

1. Add import at top:
   ```java
   import io.hypersistence.utils.hibernate.type.json.JsonType;
   import org.hibernate.annotations.Type;
   import java.util.Map;
   ```

2. Add field after `private String deniedOperation;`:
   ```java
   @Type(JsonType.class)
   @Column(name = "details", columnDefinition = "jsonb")
   private Map<String, Object> details;
   ```

3. Add getter/setter after `setDeniedOperation`:
   ```java
   public Map<String, Object> getDetails() { return details; }
   public void setDetails(Map<String, Object> d) { this.details = d; }
   ```

4. Update the `EventType` enum (currently on the same line as the class):
   ```java
   public enum EventType {
       ENTITY_CREATED, ENTITY_UPDATED, ENTITY_DELETED,
       ENTITY_DENIED, FUNCTION_INVOKED,
       KNOWLEDGE_SEARCH,
       KNOWLEDGE_CONTEXT_FETCH,
       KNOWLEDGE_ACCESS_DENIED
   }
   ```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd heirloom-server && ./mvnw test -Dtest=EventLogDetailsRoundTripIT`
Expected: PASS. Existing `AuditServiceTest` and `AgentExperienceCaptureTest` should still pass (additive change).

- [ ] **Step 6: Commit**

```bash
git add heirloom-server/src/main/resources/db/migration/V12__event_log_details.sql \
        heirloom-server/src/main/java/com/heirloom/domain/ChangeEvent.java \
        heirloom-server/src/test/java/com/heirloom/repository/EventLogDetailsRoundTripIT.java
git commit -m "feat(audit): add event_log.details JSONB + 3 knowledge event types

V12 migration adds nullable details JSONB column (no GIN index; dashboards
don't filter on it yet). ChangeEvent.details holds per-event-type metadata
(Map<String,Object> via hypersistence-utils JsonType).

Adds 3 new EventType values: KNOWLEDGE_SEARCH, KNOWLEDGE_CONTEXT_FETCH,
KNOWLEDGE_ACCESS_DENIED — wired into KnowledgeArticleResource in subsequent
commits. Spec: docs/superpowers/specs/2026-06-23-knowledge-audit-events.md"
```

---

## Task 2: KnowledgePerspectiveFilter.Visibility + checkVisibility

**Files:**
- Modify: `heirloom-server/src/main/java/com/heirloom/knowledge/service/KnowledgePerspectiveFilter.java`
- Test: `heirloom-server/src/test/java/com/heirloom/knowledge/service/KnowledgePerspectiveFilterVisibilityTest.java`

- [ ] **Step 1: Write the failing test for `checkVisibility()`**

Create `KnowledgePerspectiveFilterVisibilityTest.java`:

```java
package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.security.KnowledgeCapability;
import com.heirloom.security.KnowledgeCapabilityResolver;
import com.heirloom.security.KnowledgeCapabilityResolver.Resolution;
import com.heirloom.security.KnowledgeRestrictions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgePerspectiveFilterVisibilityTest {

    private final KnowledgeCapabilityResolver resolver = mock(KnowledgeCapabilityResolver.class);
    private final KnowledgePerspectiveFilter filter = new KnowledgePerspectiveFilter(resolver);

    private static KnowledgeArticle article(String fqn, String domain, String type, String status) {
        KnowledgeArticle a = new KnowledgeArticle();
        a.setFullyQualifiedName(fqn);
        a.setDomain(domain);
        a.setType(type);
        a.setStatus(status);
        return a;
    }

    private void stub(String actor, KnowledgeCapability cap, KnowledgeRestrictions r) {
        boolean isAdmin = cap == KnowledgeCapability.KNOWLEDGE_ADMIN;
        when(resolver.resolve(actor)).thenReturn(new Resolution(cap, r, isAdmin));
    }

    @Test
    void nullArticle_isNotFound() {
        var policy = filter.resolvePolicy("anyone");
        assertThat(filter.checkVisibility(null, policy)).isEqualTo(KnowledgePerspectiveFilter.Visibility.NOT_FOUND);
    }

    @Test
    void adminSeesArticle_evenDraft() {
        stub("admin", KnowledgeCapability.KNOWLEDGE_ADMIN, KnowledgeRestrictions.NONE);
        var policy = filter.resolvePolicy("admin");
        assertThat(filter.checkVisibility(article("a","d","t","DRAFT"), policy))
            .isEqualTo(KnowledgePerspectiveFilter.Visibility.VISIBLE);
    }

    @Test
    void noReadCapability_isDenied() {
        stub("nobody", null, KnowledgeRestrictions.NONE);
        var policy = filter.resolvePolicy("nobody");
        assertThat(filter.checkVisibility(article("a","d","t","PUBLISHED"), policy))
            .isEqualTo(KnowledgePerspectiveFilter.Visibility.DENIED);
    }

    @Test
    void draftNotAllowedForQueryOnly_isDenied() {
        stub("reader", KnowledgeCapability.KNOWLEDGE_QUERY, KnowledgeRestrictions.NONE);
        var policy = filter.resolvePolicy("reader");
        assertThat(filter.checkVisibility(article("a","d","t","DRAFT"), policy))
            .isEqualTo(KnowledgePerspectiveFilter.Visibility.DENIED);
    }

    @Test
    void domainNotAllowed_isDenied() {
        stub("scoped", KnowledgeCapability.KNOWLEDGE_QUERY,
                new KnowledgeRestrictions(List.of("crm"), List.of(), List.of(), -1, true));
        var policy = filter.resolvePolicy("scoped");
        assertThat(filter.checkVisibility(article("a","hr","t","PUBLISHED"), policy))
            .isEqualTo(KnowledgePerspectiveFilter.Visibility.DENIED);
    }

    @Test
    void typeNotAllowed_isDenied() {
        stub("scoped", KnowledgeCapability.KNOWLEDGE_QUERY,
                new KnowledgeRestrictions(List.of(), List.of("Glossary"), List.of(), -1, true));
        var policy = filter.resolvePolicy("scoped");
        assertThat(filter.checkVisibility(article("a","d","Other","PUBLISHED"), policy))
            .isEqualTo(KnowledgePerspectiveFilter.Visibility.DENIED);
    }

    @Test
    void canSee_backCompat_delegatesToCheckVisibility() {
        stub("admin", KnowledgeCapability.KNOWLEDGE_ADMIN, KnowledgeRestrictions.NONE);
        var policy = filter.resolvePolicy("admin");
        var a = article("a","d","t","DRAFT");
        assertThat(filter.canSee(a, policy))
            .isEqualTo(filter.checkVisibility(a, policy) == KnowledgePerspectiveFilter.Visibility.VISIBLE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgePerspectiveFilterVisibilityTest`
Expected: FAIL (no `Visibility` enum, no `checkVisibility` method).

- [ ] **Step 3: Modify `KnowledgePerspectiveFilter.java`**

In `heirloom-server/src/main/java/com/heirloom/knowledge/service/KnowledgePerspectiveFilter.java`:

1. Add `Visibility` enum (place inside the class, after the `AccessPolicy` record):
   ```java
   public enum Visibility { VISIBLE, DENIED, NOT_FOUND }
   ```

2. Add `checkVisibility` method (place right above the existing `canSee` method):
   ```java
   public Visibility checkVisibility(KnowledgeArticle article, AccessPolicy policy) {
       if (article == null) return Visibility.NOT_FOUND;
       if (policy.isAdmin()) return Visibility.VISIBLE;
       if (!policy.canRead()) return Visibility.DENIED;
       KnowledgeRestrictions r = policy.restrictions() != null
               ? policy.restrictions() : KnowledgeRestrictions.NONE;
       boolean ok = domainAllowed(article, r)
               && typeAllowed(article, r)
               && !deniedType(article, r)
               && statusVisible(article, r);
       return ok ? Visibility.VISIBLE : Visibility.DENIED;
   }
   ```

3. Replace the existing `canSee` method body with:
   ```java
   public boolean canSee(KnowledgeArticle article, AccessPolicy policy) {
       return checkVisibility(article, policy) == Visibility.VISIBLE;
   }
   ```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgePerspectiveFilterVisibilityTest,KnowledgePerspectiveFilterTest`
Expected: BOTH pass. The pre-existing `KnowledgePerspectiveFilterTest` must still pass (back-compat).

- [ ] **Step 5: Commit**

```bash
git add heirloom-server/src/main/java/com/heirloom/knowledge/service/KnowledgePerspectiveFilter.java \
        heirloom-server/src/test/java/com/heirloom/knowledge/service/KnowledgePerspectiveFilterVisibilityTest.java
git commit -m "feat(knowledge): KnowledgePerspectiveFilter.Visibility tri-state enum

Adds Visibility {VISIBLE, DENIED, NOT_FOUND} and checkVisibility() returning
it. canSee() now delegates to checkVisibility() for back-compat. Enables
KnowledgeArticleResource to distinguish policy-blocked reads from genuine
'not found' when emitting KNOWLEDGE_ACCESS_DENIED events."
```

---

## Task 3: KnowledgeArticleResource — emit helper + instrument `list` endpoint

**Files:**
- Modify: `heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java`
- Test: `heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeArticleEventInstrumentationTest.java`

- [ ] **Step 1: Write the failing test for `list` emitting KNOWLEDGE_SEARCH**

Create `KnowledgeArticleEventInstrumentationTest.java`:

```java
package com.heirloom.knowledge.web;

import com.heirloom.audit.AuditService;
import com.heirloom.auth.Authorizer;
import com.heirloom.domain.ChangeEvent;
import com.heirloom.entity.EntityRegistry;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.knowledge.repository.KnowledgeArticleRepository;
import com.heirloom.knowledge.service.*;
import com.heirloom.repository.EventLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KnowledgeArticleEventInstrumentationTest {

    private KnowledgeArticleJpaRepository jpa;
    private KnowledgePerspectiveFilter perspectiveFilter;
    private EventLogRepository eventLog;
    private KnowledgeGraphService graphService;
    private HttpServletRequest request;

    private KnowledgeArticleResource resource;

    @BeforeEach
    void setup() {
        Authorizer auth = mock(Authorizer.class);
        jpa = mock(KnowledgeArticleJpaRepository.class);
        perspectiveFilter = mock(KnowledgePerspectiveFilter.class);
        eventLog = mock(EventLogRepository.class);
        graphService = mock(KnowledgeGraphService.class);
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/v1/knowledge");

        KnowledgeQualityScorer qs = mock(KnowledgeQualityScorer.class);
        KnowledgePromotionEngine pe = mock(KnowledgePromotionEngine.class);
        EmbeddingProvider ep = mock(EmbeddingProvider.class);
        StaleArticleScanner sas = mock(StaleArticleScanner.class);
        KnowledgeCoverageService kcs = mock(KnowledgeCoverageService.class);
        KnowledgeArticleRepository ar = mock(KnowledgeArticleRepository.class);
        KnowledgeWorkflowService wf = mock(KnowledgeWorkflowService.class);

        resource = new KnowledgeArticleResource(
            auth, jpa, graphService, qs, pe, ep, sas, perspectiveFilter,
            kcs, ar, wf, eventLog);
    }

    private static KnowledgeArticle article(String fqn, String domain, String status) {
        KnowledgeArticle a = new KnowledgeArticle();
        a.setFullyQualifiedName(fqn);
        a.setDomain(domain);
        a.setStatus(status);
        return a;
    }

    private void stubPolicy(String actor, KnowledgePerspectiveFilter.AccessPolicy policy) {
        when(perspectiveFilter.resolvePolicy(actor)).thenReturn(policy);
    }

    @Test
    void list_emitsKnowledgeSearch_withResultAndTrimmedCounts() {
        KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
        when(policy.canRead()).thenReturn(true);
        when(policy.isAdmin()).thenReturn(false);
        stubPolicy("agent-007", policy);

        List<KnowledgeArticle> raw = List.of(
            article("a.1","d","PUBLISHED"),
            article("a.2","d","PUBLISHED"),
            article("a.3","d","PUBLISHED")
        );
        when(jpa.findAll()).thenReturn(raw);
        // Simulate filter trimming 1 of 3
        when(perspectiveFilter.filterByPolicy(eq(raw), eq(policy)))
            .thenReturn(List.of(raw.get(0), raw.get(1)));

        var response = resource.list(request, null, "agent-007", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
        verify(eventLog).append(captor.capture());
        ChangeEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_SEARCH);
        assertThat(event.getActor()).isEqualTo("agent-007");
        assertThat(event.getDetails().get("path")).isEqualTo("/v1/knowledge");
        assertThat(((Number) event.getDetails().get("resultCount")).intValue()).isEqualTo(2);
        assertThat(((Number) event.getDetails().get("trimmedCount")).intValue()).isEqualTo(1);
    }
}
```

(Plus placeholder tests for the other endpoints — added in subsequent tasks. Use `@Disabled` or comment them out and add per task.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgeArticleEventInstrumentationTest#list_emitsKnowledgeSearch_withResultAndTrimmedCounts`
Expected: FAIL — compilation or runtime error (no `eventLog` field on resource; no `list(HttpServletRequest,...)` overload).

- [ ] **Step 3: Add `EventLogRepository` field to `KnowledgeArticleResource`**

In `heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java`:

1. Add imports:
   ```java
   import com.heirloom.domain.ChangeEvent;
   import com.heirloom.repository.EventLogRepository;
   import jakarta.servlet.http.HttpServletRequest;
   import java.util.HashMap;
   import java.util.Map;
   ```

2. Add field:
   ```java
   private final EventLogRepository eventLog;
   ```

3. Add `eventLog` to the constructor parameter list (last position) and assign:
   ```java
   public KnowledgeArticleResource(Authorizer a, KnowledgeArticleJpaRepository j,
                                   KnowledgeGraphService gs, KnowledgeQualityScorer qs,
                                   KnowledgePromotionEngine pe, EmbeddingProvider ep,
                                   StaleArticleScanner sas,
                                   KnowledgePerspectiveFilter kpf,
                                   KnowledgeCoverageService kcs,
                                   KnowledgeArticleRepository ar,
                                   KnowledgeWorkflowService wf,
                                   EventLogRepository eventLog) {
       super(EntityRegistry.KNOWLEDGE_ARTICLE, a);
       jpa=j; graphService=gs; qualityScorer=qs; promotionEngine=pe;
       embeddingProvider=ep; staleScanner=sas; perspectiveFilter=kpf;
       coverageService=kcs; articleRepo=ar; workflowService=wf;
       this.eventLog = eventLog;
   }
   ```

4. Add the private helper (place near the bottom of the class, above `arrayToString`):
   ```java
   private void emitKnowledgeEvent(ChangeEvent.EventType type,
                                    String path,
                                    String actor,
                                    Map<String, Object> details) {
       try {
           Map<String, Object> safe = details == null ? new HashMap<>() : new HashMap<>(details);
           safe.putIfAbsent("_v", 1);
           safe.put("path", path);
           ChangeEvent e = new ChangeEvent();
           e.setEventType(type);
           e.setActor(actor != null ? actor : "system");
           e.setEntityType("knowledge");
           e.setDetails(safe);
           eventLog.append(e);
       } catch (Exception ex) {
           // Audit must never break business reads.
           org.slf4j.LoggerFactory.getLogger(KnowledgeArticleResource.class)
               .warn("Failed to emit knowledge audit event: {}", ex.getMessage());
       }
   }
   ```

- [ ] **Step 4: Wire `list` endpoint**

In `KnowledgeArticleResource.list(...)`, change the signature and body:

```java
@GetMapping
public ResponseEntity<List<KnowledgeArticle>> list(
        HttpServletRequest request,
        @RequestHeader(value = "X-Agent-Role", required = false) String role,
        @RequestHeader(value = "X-Agent-Id",   required = false) String agentId,
        @RequestHeader(value = "X-User",       required = false) String user) {
    String actor = pickActor(role, agentId, user);
    AccessPolicy policy = resolvePolicy(role, agentId, user);
    if (!policy.canRead()) {
        emitKnowledgeEvent(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED,
                request.getRequestURI(), actor,
                Map.of("reason", "no_read_capability"));
        return ResponseEntity.ok(List.of());
    }
    List<KnowledgeArticle> raw = jpa.findAll();
    List<KnowledgeArticle> filtered = perspectiveFilter.filterByPolicy(raw, policy);
    emitKnowledgeEvent(ChangeEvent.EventType.KNOWLEDGE_SEARCH,
            request.getRequestURI(), actor,
            Map.of("resultCount", filtered.size(),
                   "trimmedCount", raw.size() - filtered.size()));
    return ResponseEntity.ok(filtered);
}
```

The 12-arg constructor change is done in Step 3. The test's `setup()` was also updated in Step 1 to use the 12-arg form.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgeArticleEventInstrumentationTest#list_emitsKnowledgeSearch_withResultAndTrimmedCounts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java \
        heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeArticleEventInstrumentationTest.java
git commit -m "feat(knowledge): emit KNOWLEDGE_SEARCH on /v1/knowledge list + helper

Adds EventLogRepository dependency to KnowledgeArticleResource and a private
emitKnowledgeEvent() helper. Wire the list endpoint to emit KNOWLEDGE_SEARCH
with resultCount and trimmedCount in details. Audit emission is best-effort:
eventLog.append() failures are logged at WARN and never break the read."
```

---

## Task 4: Wire `search` endpoint (KNOWLEDGE_SEARCH + DENIED on block)

**Files:**
- Modify: `heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java`
- Modify: `heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeArticleEventInstrumentationTest.java`

- [ ] **Step 1: Add the failing test**

Append to `KnowledgeArticleEventInstrumentationTest.java`:

```java
@Test
void search_emitsKnowledgeSearch_withQueryModeAndCounts() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(true);
    stubPolicy("agent-007", policy);

    when(request.getRequestURI()).thenReturn("/v1/knowledge/search");
    List<KnowledgeArticle> raw = List.of(article("a.1","d","PUBLISHED"));
    when(jpa.search(anyString(), eq(20), eq(0))).thenReturn(raw);
    when(perspectiveFilter.filterByPolicy(eq(raw), eq(policy))).thenReturn(raw);

    var response = resource.search(request, "customer churn", null, "fts", 20, 0, null, "agent-007", null);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
    verify(eventLog).append(captor.capture());
    ChangeEvent event = captor.getValue();
    assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_SEARCH);
    assertThat(event.getDetails().get("query")).isEqualTo("customer churn");
    assertThat(event.getDetails().get("mode")).isEqualTo("fts");
    assertThat(((Number) event.getDetails().get("resultCount")).intValue()).isEqualTo(1);
    assertThat(((Number) event.getDetails().get("trimmedCount")).intValue()).isEqualTo(0);
}

@Test
void search_refParam_emitsWithRefFieldNotQuery() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(true);
    stubPolicy("agent-007", policy);

    when(request.getRequestURI()).thenReturn("/v1/knowledge/search");
    List<KnowledgeArticle> raw = List.of(article("a.1","d","PUBLISHED"));
    when(jpa.findByEntityRef(anyString())).thenReturn(raw);
    when(perspectiveFilter.filterByPolicy(eq(raw), eq(policy))).thenReturn(raw);

    var response = resource.search(request, null, "crm.Customer", "fts", 20, 0, null, "agent-007", null);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
    verify(eventLog).append(captor.capture());
    ChangeEvent event = captor.getValue();
    assertThat(event.getDetails().get("ref")).isEqualTo("crm.Customer");
    assertThat(event.getDetails()).doesNotContainKey("query");
}

@Test
void search_denied_emitsAccessDenied() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(false);
    stubPolicy("nobody", policy);
    when(request.getRequestURI()).thenReturn("/v1/knowledge/search");

    var response = resource.search(request, "x", null, "fts", 20, 0, null, "nobody", null);

    assertThat(response.getStatusCode().value()).isEqualTo(200);  // bulk read returns empty
    ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
    verify(eventLog).append(captor.capture());
    ChangeEvent event = captor.getValue();
    assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED);
    assertThat(event.getDetails().get("reason")).isEqualTo("no_read_capability");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgeArticleEventInstrumentationTest#search_emitsKnowledgeSearch_withQueryModeAndCounts`
Expected: FAIL (no `search(HttpServletRequest,...)` overload).

- [ ] **Step 3: Modify `search` endpoint in `KnowledgeArticleResource`**

**Important:** `search` has 5 return paths (deny, ref, blank-q, empty-tsquery, success). Emit events at a single chokepoint after all returns, using a helper that builds the details map. Replace the entire `search(...)` method body with the version below — copy verbatim, do not abbreviate.

```java
@GetMapping("/search")
public ResponseEntity<?> search(
        HttpServletRequest request,
        @RequestParam(required=false) String q,
        @RequestParam(required=false) String ref,
        @RequestParam(defaultValue="fts") String mode,
        @RequestParam(defaultValue="20") int limit,
        @RequestParam(defaultValue="0") int offset,
        @RequestHeader(value = "X-Agent-Role", required = false) String role,
        @RequestHeader(value = "X-Agent-Id",   required = false) String agentId,
        @RequestHeader(value = "X-User",       required = false) String user) {

    String actor = pickActor(role, agentId, user);
    AccessPolicy policy = resolvePolicy(role, agentId, user);

    if (!policy.canRead()) {
        Map<String, Object> den = new HashMap<>();
        den.put("reason", "no_read_capability");
        if (q != null) den.put("query", q);
        if (ref != null) den.put("ref", ref);
        emitKnowledgeEvent(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED,
                request.getRequestURI(), actor, den);
        return ResponseEntity.ok(List.of());
    }

    if (ref != null && !ref.isBlank()) {
        List<KnowledgeArticle> raw = jpa.findByEntityRef("[{\"fqn\":\"" + ref + "\"}]");
        List<KnowledgeArticle> filtered = perspectiveFilter.filterByPolicy(raw, policy);
        Map<String, Object> det = new HashMap<>();
        det.put("ref", ref);
        det.put("mode", mode);
        det.put("limit", limit);
        det.put("offset", offset);
        det.put("resultCount", filtered.size());
        det.put("trimmedCount", raw.size() - filtered.size());
        emitKnowledgeEvent(ChangeEvent.EventType.KNOWLEDGE_SEARCH,
                request.getRequestURI(), actor, det);
        return ResponseEntity.ok(filtered);
    }

    if (q == null || q.isBlank()) {
        return ResponseEntity.badRequest().body(Map.of("error","Provide q or ref"));
    }

    String tsQuery = QuerySanitizer.toTsQuery(q);
    if (tsQuery.isEmpty()) return ResponseEntity.ok(List.of());

    String effectiveMode = mode;
    if (!"fts".equals(mode) && !embeddingProvider.isAvailable()) {
        effectiveMode = "fts";
    }

    Object rawResult = switch (effectiveMode) {
        case "vector" -> {
            float[] qe = embeddingProvider.embed(q);
            yield jpa.vectorSearch(arrayToString(qe), limit, offset);
        }
        case "hybrid" -> {
            float[] qe = embeddingProvider.embed(q);
            List<KnowledgeArticle> fts = jpa.search(tsQuery, limit * 2, 0);
            List<KnowledgeArticle> vec = jpa.vectorSearch(arrayToString(qe), limit * 2, 0);
            var fused = rrfScorer.fuse(fts, vec);
            yield fused.stream().limit(limit)
                    .map(r -> Map.of("article", r.article(), "score", r.score()))
                    .collect(Collectors.toList());
        }
        default -> jpa.search(tsQuery, limit, offset);
    };

    // Compute resultCount/trimmedCount and emit KNOWLEDGE_SEARCH (one emit, before all returns).
    int resultCount = 0;
    int trimmedCount = 0;
    List<KnowledgeArticle> filtered = List.of();

    if (rawResult instanceof List<?> list) {
        List<KnowledgeArticle> articles = list.stream()
                .filter(KnowledgeArticle.class::isInstance)
                .map(KnowledgeArticle.class::cast)
                .toList();
        filtered = perspectiveFilter.filterByPolicy(articles, policy);
        resultCount = filtered.size();
        trimmedCount = articles.size() - resultCount;
    }

    Map<String, Object> det = new HashMap<>();
    det.put("query", q);
    det.put("mode", mode);
    det.put("limit", limit);
    det.put("offset", offset);
    det.put("resultCount", resultCount);
    det.put("trimmedCount", trimmedCount);
    emitKnowledgeEvent(ChangeEvent.EventType.KNOWLEDGE_SEARCH,
            request.getRequestURI(), actor, det);

    if ("hybrid".equals(effectiveMode)) {
        return ResponseEntity.ok(rawResult instanceof List<?> list ? list.stream()
                .filter(o -> o instanceof Map<?, ?> m
                        && m.get("article") instanceof KnowledgeArticle a
                        && perspectiveFilter.canSee(a, policy))
                .limit(limit)
                .toList() : List.of());
    }
    return ResponseEntity.ok(filtered);
}
```

Notes on the change:
- Every return path now either emits the event before returning, or is an error/empty path that intentionally doesn't emit (`q.isBlank()` 400, `tsQuery.isEmpty()` empty list — same as before).
- The `ref` branch now emits `KNOWLEDGE_SEARCH` with `ref` in details (was silently returning without auditing).
- The hybrid branch emits once with the **non-hybrid** resultCount (since the perspective filter still applies), then preserves the (article, score) shape on the way out. The test in Step 1 only covers the non-hybrid path; the hybrid emit is verified by running the full test class.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgeArticleEventInstrumentationTest`
Expected: All 4 list/search tests pass. Run the full KnowledgeArticleEventInstrumentationTest class.

- [ ] **Step 5: Commit**

```bash
git add heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java \
        heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeArticleEventInstrumentationTest.java
git commit -m "feat(knowledge): emit KNOWLEDGE_SEARCH + KNOWLEDGE_ACCESS_DENIED on /search

search endpoint now emits KNOWLEDGE_SEARCH with query, ref (if present), mode,
limit, offset, resultCount, trimmedCount. On policy block, emits
KNOWLEDGE_ACCESS_DENIED with reason='no_read_capability' and includes the
query/ref fields for forensics."
```

---

## Task 5: Wire `traverse` endpoint (KNOWLEDGE_SEARCH + DENIED on block)

**Files:**
- Modify: `heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java`
- Modify: `heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeArticleEventInstrumentationTest.java`

- [ ] **Step 1: Add the failing test**

Append to `KnowledgeArticleEventInstrumentationTest.java`:

```java
@Test
void traverse_emitsKnowledgeSearch() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(true);
    when(policy.isAdmin()).thenReturn(true);
    stubPolicy("agent-007", policy);

    when(request.getRequestURI()).thenReturn("/v1/knowledge/graph/traverse");
    when(perspectiveFilter.maxDepth(policy)).thenReturn(-1);

    KnowledgeArticle a = article("a.1","d","PUBLISHED");
    KnowledgeGraphService.GraphResult graph =
        new KnowledgeGraphService.GraphResult(List.of(a), List.of(), 1);
    when(graphService.traverse("a.1", 2)).thenReturn(graph);

    var response = resource.traverse(request, "a.1", 2, null, "agent-007", null);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
    verify(eventLog).append(captor.capture());
    ChangeEvent event = captor.getValue();
    assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_SEARCH);
    assertThat(event.getDetails().get("path")).isEqualTo("/v1/knowledge/graph/traverse");
    assertThat(((Number) event.getDetails().get("resultCount")).intValue()).isEqualTo(1);
}

@Test
void traverse_denied_emitsAccessDenied() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(false);
    stubPolicy("nobody", policy);
    when(request.getRequestURI()).thenReturn("/v1/knowledge/graph/traverse");

    var response = resource.traverse(request, "a.1", 2, null, "nobody", null);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
    verify(eventLog).append(captor.capture());
    assertThat(captor.getValue().getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgeArticleEventInstrumentationTest#traverse_emitsKnowledgeSearch`
Expected: FAIL.

- [ ] **Step 3: Modify `traverse` endpoint in `KnowledgeArticleResource`**

Add `HttpServletRequest request` as the first parameter to `traverse(...)`. Wire:

- After `if (!policy.canRead())`: emit `KNOWLEDGE_ACCESS_DENIED` (reason=`no_read_capability`), then return.
- After `graphService.traverse(...)` returns: compute `resultCount = result.nodes().size() - 1` (excluding the root) and `trimmedCount` (count of nodes where `!perspectiveFilter.canSee(node, policy)`), emit `KNOWLEDGE_SEARCH`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgeArticleEventInstrumentationTest`
Expected: All traverse tests pass.

- [ ] **Step 5: Commit**

```bash
git add heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java \
        heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeArticleEventInstrumentationTest.java
git commit -m "feat(knowledge): emit KNOWLEDGE_SEARCH + KNOWLEDGE_ACCESS_DENIED on /traverse

traverse endpoint emits KNOWLEDGE_SEARCH with resultCount (excluding root)
and trimmedCount (nodes blocked by perspective filter)."
```

---

## Task 6: Wire `getById` + `getByFQN` (DENIED on block, no event on success)

**Files:**
- Modify: `heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java`
- Modify: `heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeArticleEventInstrumentationTest.java`

- [ ] **Step 1: Add the failing tests**

Append to `KnowledgeArticleEventInstrumentationTest.java`:

```java
@Test
void getByFQN_denied_emitsAccessDenied_withFqnAndReason() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(true);
    when(policy.isAdmin()).thenReturn(false);
    stubPolicy("scoped", policy);
    when(request.getRequestURI()).thenReturn("/v1/knowledge/name/crm.Customer");

    KnowledgeArticle a = article("crm.Customer","crm","Glossary","PUBLISHED");
    when(jpa.findByFullyQualifiedName("crm.Customer")).thenReturn(Optional.of(a));
    when(perspectiveFilter.checkVisibility(a, policy))
        .thenReturn(KnowledgePerspectiveFilter.Visibility.DENIED);

    var response = resource.getByFQN(request, "crm.Customer", null, null, "scoped");

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
    verify(eventLog).append(captor.capture());
    ChangeEvent event = captor.getValue();
    assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED);
    assertThat(event.getDetails().get("fqn")).isEqualTo("crm.Customer");
    assertThat(event.getDetails().get("reason")).isIn(
        "domain_not_allowed", "type_not_allowed", "type_denied", "draft_not_allowed");
}

@Test
void getByFQN_notFound_doesNotEmitEvent() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(true);
    stubPolicy("agent-007", policy);
    when(request.getRequestURI()).thenReturn("/v1/knowledge/name/crm.Customer");
    when(jpa.findByFullyQualifiedName("crm.Customer")).thenReturn(Optional.empty());

    var response = resource.getByFQN(request, "crm.Customer", null, null, "agent-007");

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    verify(eventLog, never()).append(any());
}

@Test
void getByFQN_visible_doesNotEmitEvent() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(true);
    when(policy.isAdmin()).thenReturn(false);
    stubPolicy("admin", policy);
    when(request.getRequestURI()).thenReturn("/v1/knowledge/name/crm.Customer");

    KnowledgeArticle a = article("crm.Customer","crm","Glossary","PUBLISHED");
    when(jpa.findByFullyQualifiedName("crm.Customer")).thenReturn(Optional.of(a));
    when(perspectiveFilter.checkVisibility(a, policy))
        .thenReturn(KnowledgePerspectiveFilter.Visibility.VISIBLE);

    var response = resource.getByFQN(request, "crm.Customer", null, "admin", null);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    verify(eventLog, never()).append(any());
}
```

Also add a similar test for `getById`. The wire-up is the same pattern — emit DENIED when `Visibility.DENIED`, do nothing when VISIBLE or NOT_FOUND.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgeArticleEventInstrumentationTest#getByFQN_denied_emitsAccessDenied_withFqnAndReason`
Expected: FAIL.

- [ ] **Step 3: Modify `getById` and `getByFQN` in `KnowledgeArticleResource`**

Both endpoints follow the same pattern. Use `checkVisibility(article, policy)` instead of `canSee(article, policy)` and emit on DENIED:

```java
@GetMapping("/{id}")
public ResponseEntity<KnowledgeArticle> getById(
        HttpServletRequest request,
        @PathVariable Long id,
        @RequestHeader(value = "X-Agent-Role", required = false) String role,
        @RequestHeader(value = "X-Agent-Id",   required = false) String agentId,
        @RequestHeader(value = "X-User",       required = false) String user) {
    String actor = pickActor(role, agentId, user);
    AccessPolicy policy = resolvePolicy(role, agentId, user);
    if (!policy.canRead()) {
        emitKnowledgeEvent(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED,
                request.getRequestURI(), actor, Map.of("reason", "no_read_capability"));
        return ResponseEntity.notFound().build();
    }
    return jpa.findById(id)
            .map(a -> {
                Visibility v = perspectiveFilter.checkVisibility(a, policy);
                if (v == Visibility.VISIBLE) return ResponseEntity.ok(a);
                if (v == Visibility.DENIED) {
                    emitKnowledgeEvent(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED,
                            request.getRequestURI(), actor,
                            Map.of("fqn", a.getFullyQualifiedName(),
                                   "reason", denyReason(a, policy)));
                    return ResponseEntity.<KnowledgeArticle>notFound().build();
                }
                return ResponseEntity.<KnowledgeArticle>notFound().build();
            })
            .orElse(ResponseEntity.notFound().build());
}
```

Add a private helper `denyReason(KnowledgeArticle, AccessPolicy)` that walks the same predicates as `checkVisibility` and returns the matching reason string. Mirror logic:

```java
private static String denyReason(KnowledgeArticle a, AccessPolicy policy) {
    if (!policy.canRead()) return "no_read_capability";
    KnowledgeRestrictions r = policy.restrictions() != null
            ? policy.restrictions() : KnowledgeRestrictions.NONE;
    if (r.allowedDomains() != null && !r.allowedDomains().isEmpty()
            && !r.allowedDomains().contains("*")
            && !r.allowedDomains().contains(a.getDomain())) return "domain_not_allowed";
    if (r.allowedTypes() != null && !r.allowedTypes().isEmpty()
            && !r.allowedTypes().contains(a.getType())) return "type_not_allowed";
    if (r.deniedTypes() != null && r.deniedTypes().contains(a.getType())) return "type_denied";
    if (!r.allowDrafts() && "DRAFT".equalsIgnoreCase(a.getStatus())) return "draft_not_allowed";
    return "no_read_capability";  // fallback (shouldn't happen)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgeArticleEventInstrumentationTest`
Expected: All getById/getByFQN tests pass.

- [ ] **Step 5: Commit**

```bash
git add heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java \
        heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeArticleEventInstrumentationTest.java
git commit -m "feat(knowledge): emit KNOWLEDGE_ACCESS_DENIED on getById/getByFQN block

getById and getByFQN now emit KNOWLEDGE_ACCESS_DENIED on policy block
(deterministically inferring the deny reason via the same predicates
checkVisibility uses). On VISIBLE or NOT_FOUND, no event is emitted
(single-read success is intentionally quiet to avoid dashboard noise)."
```

---

## Task 7: Add `/v1/knowledge/context` endpoint

**Files:**
- Modify: `heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java`
- Modify: `heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeArticleEventInstrumentationTest.java`
- Test: `heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeContextEndpointIT.java`

- [ ] **Step 1: Write the failing unit test**

Append to `KnowledgeArticleEventInstrumentationTest.java`:

```java
@Test
void context_success_emitsContextFetch_withPrerequisiteCount() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(true);
    when(policy.isAdmin()).thenReturn(true);
    stubPolicy("agent-007", policy);
    when(perspectiveFilter.maxDepth(policy)).thenReturn(-1);
    when(request.getRequestURI()).thenReturn("/v1/knowledge/context");

    KnowledgeArticle root = article("crm.Customer","crm","Glossary","PUBLISHED");
    root.setTitle("Customer");
    root.setBody("# Customer\n\nA customer is ...");
    root.setVersion(3L);
    when(jpa.findByFullyQualifiedName("crm.Customer")).thenReturn(Optional.of(root));
    when(perspectiveFilter.checkVisibility(root, policy))
        .thenReturn(KnowledgePerspectiveFilter.Visibility.VISIBLE);

    KnowledgeArticle prereq = article("crm.Order","crm","Glossary","PUBLISHED");
    prereq.setTitle("Order");
    prereq.setBody("# Order\n\n...");
    KnowledgeGraphService.GraphResult graph =
        new KnowledgeGraphService.GraphResult(List.of(root, prereq), List.of(), 1);
    when(graphService.traverse("crm.Customer", 1)).thenReturn(graph);
    when(jpa.findByFullyQualifiedName("crm.Order")).thenReturn(Optional.of(prereq));
    when(perspectiveFilter.checkVisibility(prereq, policy))
        .thenReturn(KnowledgePerspectiveFilter.Visibility.VISIBLE);

    var response = resource.context(request, "crm.Customer", 1, 4096, null, "agent-007", null);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsKeys("root", "prerequisites", "context", "truncated");
    assertThat(body.get("truncated")).isEqualTo(false);

    ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
    verify(eventLog).append(captor.capture());
    ChangeEvent event = captor.getValue();
    assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_CONTEXT_FETCH);
    assertThat(event.getDetails().get("fqn")).isEqualTo("crm.Customer");
    assertThat(((Number) event.getDetails().get("depth")).intValue()).isEqualTo(1);
    assertThat(((Number) event.getDetails().get("prerequisiteCount")).intValue()).isEqualTo(1);
}

@Test
void context_truncated_setsTruncatedTrue() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(true);
    when(policy.isAdmin()).thenReturn(true);
    stubPolicy("agent-007", policy);
    when(perspectiveFilter.maxDepth(policy)).thenReturn(-1);
    when(request.getRequestURI()).thenReturn("/v1/knowledge/context");

    KnowledgeArticle root = article("a","d","Glossary","PUBLISHED");
    root.setTitle("A");
    root.setBody("x".repeat(200));
    when(jpa.findByFullyQualifiedName("a")).thenReturn(Optional.of(root));
    when(perspectiveFilter.checkVisibility(root, policy))
        .thenReturn(KnowledgePerspectiveFilter.Visibility.VISIBLE);

    KnowledgeArticle big = article("b","d","Glossary","PUBLISHED");
    big.setTitle("B");
    big.setBody("y".repeat(200));
    when(graphService.traverse("a", 1))
        .thenReturn(new KnowledgeGraphService.GraphResult(List.of(root, big), List.of(), 1));
    when(jpa.findByFullyQualifiedName("b")).thenReturn(Optional.of(big));
    when(perspectiveFilter.checkVisibility(big, policy))
        .thenReturn(KnowledgePerspectiveFilter.Visibility.VISIBLE);

    var response = resource.context(request, "a", 1, 50, null, "agent-007", null);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body.get("truncated")).isEqualTo(true);
}

@Test
void context_denied_emitsAccessDenied() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(false);
    stubPolicy("nobody", policy);
    when(request.getRequestURI()).thenReturn("/v1/knowledge/context");

    var response = resource.context(request, "crm.Customer", 1, 4096, null, "nobody", null);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
    verify(eventLog).append(captor.capture());
    ChangeEvent event = captor.getValue();
    assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED);
    assertThat(event.getDetails().get("fqn")).isEqualTo("crm.Customer");
    assertThat(event.getDetails().get("reason")).isEqualTo("no_read_capability");
}

@Test
void context_notFound_doesNotEmitEvent() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(true);
    stubPolicy("agent-007", policy);
    when(request.getRequestURI()).thenReturn("/v1/knowledge/context");
    when(jpa.findByFullyQualifiedName("missing")).thenReturn(Optional.empty());

    var response = resource.context(request, "missing", 1, 4096, null, "agent-007", null);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    verify(eventLog, never()).append(any());
}

@Test
void eventLogAppendFailure_doesNotBreakResponse() {
    KnowledgePerspectiveFilter.AccessPolicy policy = mock(KnowledgePerspectiveFilter.AccessPolicy.class);
    when(policy.canRead()).thenReturn(true);
    when(policy.isAdmin()).thenReturn(true);
    stubPolicy("agent-007", policy);
    when(perspectiveFilter.maxDepth(policy)).thenReturn(-1);

    KnowledgeArticle root = article("a","d","Glossary","PUBLISHED");
    root.setTitle("A"); root.setBody("body");
    when(jpa.findByFullyQualifiedName("a")).thenReturn(Optional.of(root));
    when(perspectiveFilter.checkVisibility(root, policy))
        .thenReturn(KnowledgePerspectiveFilter.Visibility.VISIBLE);
    when(graphService.traverse("a", 1))
        .thenReturn(new KnowledgeGraphService.GraphResult(List.of(root), List.of(), 1));
    doThrow(new RuntimeException("DB down")).when(eventLog).append(any());

    var response = resource.context(request, "a", 1, 4096, null, "agent-007", null);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgeArticleEventInstrumentationTest#context_success_emitsContextFetch_withPrerequisiteCount`
Expected: FAIL (no `context(...)` method).

- [ ] **Step 3: Add `context` endpoint to `KnowledgeArticleResource`**

```java
@GetMapping("/context")
public ResponseEntity<?> context(
        HttpServletRequest request,
        @RequestParam String fqn,
        @RequestParam(defaultValue = "1") int depth,
        @RequestParam(defaultValue = "4096") int maxBytes,
        @RequestHeader(value = "X-Agent-Role", required = false) String role,
        @RequestHeader(value = "X-Agent-Id",   required = false) String agentId,
        @RequestHeader(value = "X-User",       required = false) String user) {
    if (fqn == null || fqn.isBlank()) {
        return ResponseEntity.badRequest().body(Map.of("error", "Provide fqn"));
    }
    int clampedDepth = Math.max(1, Math.min(2, depth));
    String actor = pickActor(role, agentId, user);
    AccessPolicy policy = resolvePolicy(role, agentId, user);
    if (!policy.canRead()) {
        emitKnowledgeEvent(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED,
                request.getRequestURI(), actor,
                Map.of("fqn", fqn, "reason", "no_read_capability"));
        return ResponseEntity.notFound().build();
    }

    Optional<KnowledgeArticle> rootOpt = jpa.findByFullyQualifiedName(fqn);
    if (rootOpt.isEmpty()) {
        return ResponseEntity.notFound().build();
    }
    KnowledgeArticle root = rootOpt.get();
    Visibility v = perspectiveFilter.checkVisibility(root, policy);
    if (v == Visibility.DENIED) {
        emitKnowledgeEvent(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED,
                request.getRequestURI(), actor,
                Map.of("fqn", fqn, "reason", denyReason(root, policy)));
        return ResponseEntity.notFound().build();
    }

    int cap = perspectiveFilter.maxDepth(policy);
    int effectiveDepth = cap >= 0 ? Math.min(clampedDepth, cap) : clampedDepth;
    KnowledgeGraphService.GraphResult graph = graphService.traverse(fqn, effectiveDepth);

    StringBuilder ctx = new StringBuilder();
    ctx.append("# ").append(root.getTitle()).append("\n\n").append(root.getBody()).append("\n");
    boolean truncated = false;
    int prereqCount = 0;
    List<Map<String, Object>> prereqList = new ArrayList<>();
    for (KnowledgeArticle node : graph.nodes()) {
        if (node.getFullyQualifiedName().equals(fqn)) continue;  // skip root
        if (perspectiveFilter.checkVisibility(node, policy) != Visibility.VISIBLE) continue;
        prereqList.add(Map.of("fqn", node.getFullyQualifiedName(),
                              "title", node.getTitle() == null ? "" : node.getTitle(),
                              "depth", 1));  // depth=1 in this iteration; could refine via Edge traversal
        if (ctx.length() >= maxBytes) { truncated = true; break; }
        ctx.append("\n## Prerequisite: ").append(node.getTitle()).append("\n\n")
           .append(node.getBody() == null ? "" : node.getBody()).append("\n");
        prereqCount++;
    }

    emitKnowledgeEvent(ChangeEvent.EventType.KNOWLEDGE_CONTEXT_FETCH,
            request.getRequestURI(), actor,
            Map.of("fqn", fqn, "depth", effectiveDepth, "prerequisiteCount", prereqCount));

    Map<String, Object> rootMap = new HashMap<>();
    rootMap.put("fqn", root.getFullyQualifiedName());
    rootMap.put("title", root.getTitle());
    rootMap.put("status", root.getStatus());
    rootMap.put("domain", root.getDomain());
    rootMap.put("type", root.getType());
    rootMap.put("body", root.getBody());
    rootMap.put("version", root.getVersion());

    return ResponseEntity.ok(Map.of(
        "root", rootMap,
        "prerequisites", prereqList,
        "context", ctx.toString(),
        "truncated", truncated
    ));
}
```

Add necessary imports: `java.util.ArrayList`.

- [ ] **Step 4: Run unit tests to verify they pass**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgeArticleEventInstrumentationTest`
Expected: All tests pass.

- [ ] **Step 5: Write the integration test**

Create `KnowledgeContextEndpointIT.java` following the project IT pattern (`@Testcontainers` + `@ServiceConnection` + `TestRestTemplate`):

```java
package com.heirloom.knowledge.web;

import com.heirloom.HeirloomApplication;
import com.heirloom.domain.ChangeEvent;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.domain.KnowledgeStatus;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.repository.EventLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = HeirloomApplication.class
)
@Testcontainers
@ActiveProfiles("test")
class KnowledgeContextEndpointIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired TestRestTemplate rest;
    @Autowired KnowledgeArticleJpaRepository jpa;
    @Autowired EventLogRepository eventLog;

    @BeforeEach
    void setup() {
        jpa.deleteAll();
        KnowledgeArticle a = new KnowledgeArticle();
        a.setFullyQualifiedName("crm.Customer");
        a.setTitle("Customer");
        a.setDomain("crm");
        a.setType("Glossary");
        a.setStatus(KnowledgeStatus.PUBLISHED.name());
        a.setBody("# Customer\n\nA customer is a person who buys things.");
        jpa.saveAndFlush(a);
    }

    @Test
    void contextReturnsRootAndEmptyPrereqs() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Agent-Role", "admin");
        ResponseEntity<Map> response = rest.exchange(
            "/v1/knowledge/context?fqn=crm.Customer",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsKeys("root", "prerequisites", "context", "truncated");
        assertThat(((Map) body.get("root")).get("fqn")).isEqualTo("crm.Customer");
    }

    @Test
    void contextEmitsEventInEventLog() {
        long before = countContextFetchEvents();
        HttpHeaders h = new HttpHeaders();
        h.set("X-Agent-Role", "admin");
        rest.exchange("/v1/knowledge/context?fqn=crm.Customer",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(countContextFetchEvents()).isEqualTo(before + 1);
    }

    private long countContextFetchEvents() {
        return eventLog.actorActivity("admin",
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(60)).stream()
            .filter(e -> e.getEventType() == ChangeEvent.EventType.KNOWLEDGE_CONTEXT_FETCH)
            .count();
    }
}
```

Notes:
- `pgvector/pgvector:pg16` matches `KnowledgeSearchTest`'s choice; the image has full JSONB support needed for `event_log.details`.
- `TestRestTemplate` autowires with the random port; no manual URL construction.
- `actorActivity("admin", ...)` filters on the `X-Agent-Role: admin` header which the resource resolves to actor `"admin"` (not `"agent:admin"` — only `X-Agent-Id` triggers the `agent:` prefix; see `pickActor` in `KnowledgeArticleResource`).

- [ ] **Step 6: Run integration test**

Run: `cd heirloom-server && ./mvnw test -Dtest=KnowledgeContextEndpointIT`
Expected: PASS (requires running test DB; CI uses TestContainers or in-memory per project setup — check `application-test.yml` if any of these details differ).

- [ ] **Step 7: Commit**

```bash
git add heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java \
        heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeArticleEventInstrumentationTest.java \
        heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeContextEndpointIT.java
git commit -m "feat(knowledge): add /v1/knowledge/context endpoint

New endpoint bundles a root article + its prerequisite graph into an
LLM-friendly payload (root + prerequisites + context string + truncated flag).
Emits KNOWLEDGE_CONTEXT_FETCH on success with fqn/depth/prerequisiteCount in
details. Emits KNOWLEDGE_ACCESS_DENIED on policy block (404 to client).
maxBytes default 4096; depth clamped to [1,2]."
```

---

## Task 8: Run full server test suite

- [ ] **Step 1: Run all server tests**

Run: `cd heirloom-server && ./mvnw test`
Expected: All existing tests + 3 new test classes pass. No regressions.

- [ ] **Step 2: Investigate and fix any failures**

If anything fails, fix the root cause (not the test) and re-run.

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix(server): address test failures from knowledge audit event work"
```

(only if there are fixes)

---

## Task 9: Update ROADMAP.md

- [ ] **Step 1: Mark "知识审计事件" done and add v0.14 changelog row**

In `docs/ROADMAP.md`:

1. In Phase 3.1 section, change:
   ```
   - [ ] 知识审计事件：KNOWLEDGE_SEARCH、KNOWLEDGE_CONTEXT_FETCH、KNOWLEDGE_ACCESS_DENIED
   ```
   to:
   ```
   - [x] 知识审计事件：KNOWLEDGE_SEARCH、KNOWLEDGE_CONTEXT_FETCH、KNOWLEDGE_ACCESS_DENIED — `event_log.details` JSONB (V12) + 5 个 read 端点埋点 + 新 `/v1/knowledge/context` 端点
   ```

2. At the top of the Phase 0 / Phase 1 / etc. preamble, the count is "97/135 done" — change to "98/135 done" (and adjust "+1" if needed).

3. Append a v0.14 row to the version history table at the bottom of the file:
   ```
   | 2026-06-23 | v0.14 | Knowledge 审计事件 (Phase 3.1) 落地：V12 event_log.details JSONB + 3 个新 EventType (KNOWLEDGE_SEARCH / _CONTEXT_FETCH / _ACCESS_DENIED) + KnowledgeArticleResource 5 read 端点埋点 + 新 `/v1/knowledge/context` 端点。KnowledgePerspectiveFilter.Visibility 三态枚举区分 denied vs not_found。Server tests 192 → 200+。现为 98/135 done |
   ```

- [ ] **Step 2: Commit**

```bash
git add docs/ROADMAP.md
git commit -m "docs(roadmap): v0.14 — mark knowledge audit events as done

98/135 done. Server now emits KNOWLEDGE_SEARCH / KNOWLEDGE_CONTEXT_FETCH /
KNOWLEDGE_ACCESS_DENIED on knowledge base reads. Audit dashboard surfaces
the new event types without code changes (existing AuditService already
aggregates by EventType)."
```

---

## Task 10: Final smoke test (manual)

- [ ] **Step 1: Start the server**

```bash
cd heirloom-server && ./mvnw spring-boot:run
```

- [ ] **Step 2: Hit the endpoints, then inspect event_log**

```bash
# Trigger a search event
curl -H "X-Agent-Id: agent-007" -H "X-Agent-Role: admin" \
     "http://localhost:8080/v1/knowledge/search?q=customer"

# Trigger an access denied event
curl -H "X-Agent-Id: agent-007" -H "X-Agent-Role: nobody" \
     "http://localhost:8080/v1/knowledge/name/secret-internal-doc"

# Trigger a context fetch event
curl -H "X-Agent-Id: agent-007" -H "X-Agent-Role: admin" \
     "http://localhost:8080/v1/knowledge/context?fqn=crm.Customer&depth=1"
```

Then query the DB:
```sql
SELECT event_type, actor, details FROM event_log
WHERE event_type IN ('KNOWLEDGE_SEARCH', 'KNOWLEDGE_CONTEXT_FETCH', 'KNOWLEDGE_ACCESS_DENIED')
ORDER BY id DESC LIMIT 10;
```

- [ ] **Step 3: Verify audit dashboard sees the new types**

```bash
curl -H "X-Agent-Id: agent-007" -H "X-Agent-Role: admin" \
     "http://localhost:8080/v1/audit/actors/agent-007/activity?since=2026-06-23T00:00:00Z"
```

Expected: Response includes `KNOWLEDGE_SEARCH`, `KNOWLEDGE_CONTEXT_FETCH`, `KNOWLEDGE_ACCESS_DENIED` in the event-type breakdown.

- [ ] **Step 4: Final commit if anything needed cleanup**

(only if Step 2 or 3 reveals issues; otherwise this task is a no-op)

---

## Acceptance Checklist

- [ ] All 9 task commits made
- [ ] `./mvnw test` in `heirloom-server/` passes 100%
- [ ] `ROADMAP.md` updated: Phase 3.1 "知识审计事件" checked, v0.14 row added, count "98/135"
- [ ] Manual smoke test confirms new event types appear in `event_log` and the audit dashboard
- [ ] No breaking changes to existing client behavior (response shapes unchanged for existing endpoints)
- [ ] `/v1/knowledge/context` is the only new endpoint
- [ ] `canSee()` API is preserved (back-compat verified by `KnowledgePerspectiveFilterTest`)

## Out of Scope (deferred to follow-up work)

- SDK `knowledge.getContext()` / `getPrerequisites()` methods
- Auto context injection in `actions.execute()`
- `KNOWLEDGE_READ` event for successful single-article reads
- GIN index on `event_log.details`
- Async event queue (Kafka / Redis)
- Cursor-paginated `/v1/knowledge/context`

## References

- Spec: `docs/superpowers/specs/2026-06-23-knowledge-audit-events.md`
- ADR-005: Nine-step Action Pipeline
- ADR-007: Semantic Constrains Kinetic
- ADR-009: Perspective Engine Placement
- ADR-016: Change Event Interceptor
- ADR-035: Agent SDK & Perspective Integration
- Migration pattern: `V11__cross_ontology_mappings.sql`
- JSONB pattern: `KnowledgeArticle.java:25`
- Event-log pattern: `FunctionService.java:64-72`
- Test pattern: `FunctionServiceTest.java`
