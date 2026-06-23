# Knowledge Audit Events — Phase 3.1 Server-Side Instrumentation

**Date:** 2026-06-23
**Status:** Draft (brainstorming complete, awaiting spec review)
**Scope:** Server-side event instrumentation for the Knowledge Base. SDK methods and auto context injection are explicitly **out of scope** (separate Phase 3.1 work).

---

## 1. Goal

Wire `EventLog` write-side coverage into the `KnowledgeArticleResource` read endpoints so the Phase 3.4 audit dashboard (already shipped) immediately surfaces:

- **KNOWLEDGE_SEARCH** — every list / search / traverse request, with `resultCount` and `trimmedCount` for visibility into perspective filter activity.
- **KNOWLEDGE_CONTEXT_FETCH** — a new `/v1/knowledge/context` endpoint that bundles a root article + its prerequisites into a single LLM-friendly payload.
- **KNOWLEDGE_ACCESS_DENIED** — every policy-blocked read attempt (per ADR-007 / ADR-009 security observability principles).

`AuditService` and `AuditResource` require **zero** changes — they already aggregate by `EventType`.

---

## 2. Non-Goals

| Item | Why deferred |
|---|---|
| `KNOWLEDGE_READ` event for successful single-article reads | Dashboard is actor-centric, not article-centric. Forensic "who read X" can be added later as 10 LOC if needed. |
| SDK `knowledge.getContext()` / `getPrerequisites()` methods | Out of scope per user direction. |
| Auto context injection in `actions.execute()` | Cross-cutting Action engine design. Separate work. |
| GIN index on `event_log.details` | No current dashboard query filters on `details`. Add at 1M+ rows. |
| Async event queue (Kafka / Redis stream) | Synchronous REQUIRES_NEW insert < 1ms. Add when read QPS > ~100. |
| `details.path` GIN index | Not needed today. |
| Cursor-paginated `/v1/knowledge/context` | `maxBytes` truncation is sufficient. |
| Deprecating `KnowledgePerspectiveFilter.canSee()` | Keep for back-compat; new code uses `checkVisibility()`. |

---

## 3. Data Model Changes

### 3.1 `ChangeEvent.details` (new JSONB column)

```java
@Type(JsonType.class)  // io.hypersistence.utils.type.json — matches KnowledgeArticle.frontmatter
@Column(name = "details", columnDefinition = "jsonb")
private Map<String, Object> details;
```

**Migration V12** (`db/migration/V12__event_log_details.sql`):

```sql
ALTER TABLE event_log ADD COLUMN details JSONB;
-- No GIN index. Rationale: see §2.
```

### 3.2 `ChangeEvent.EventType` (3 new values)

```java
public enum EventType {
    ENTITY_CREATED, ENTITY_UPDATED, ENTITY_DELETED,
    ENTITY_DENIED, FUNCTION_INVOKED,
    KNOWLEDGE_SEARCH,           // list / search / traverse
    KNOWLEDGE_CONTEXT_FETCH,    // /v1/knowledge/context
    KNOWLEDGE_ACCESS_DENIED     // policy block on any knowledge read
}
```

### 3.3 `KnowledgePerspectiveFilter.Visibility` (new tri-state enum)

```java
public enum Visibility { VISIBLE, DENIED, NOT_FOUND }

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

// canSee() now delegates for back-compat
public boolean canSee(KnowledgeArticle article, AccessPolicy policy) {
    return checkVisibility(article, policy) == Visibility.VISIBLE;
}
```

**Deny reason mapping** (used to populate `details.reason` on `KNOWLEDGE_ACCESS_DENIED`):

| Reason string | Trigger |
|---|---|
| `no_read_capability` | `!policy.canRead()` |
| `domain_not_allowed` | `!domainAllowed(article, r)` |
| `type_not_allowed` | `!typeAllowed(article, r)` |
| `type_denied` | `deniedType(article, r)` |
| `draft_not_allowed` | `!statusVisible(article, r)` |

---

## 4. Event Payload Schemas

All events share the same `details` envelope. **Path is the source of truth for endpoint identification** — no separate `endpoint` field, no hardcoded strings.

```jsonc
// KNOWLEDGE_SEARCH
{
  "_v": 1,                                    // schema version (R3 mitigation)
  "path": "/v1/knowledge/search",
  "query": "customer churn",                  // @RequestParam q (search/traverse only)
  "ref":  "crm.Customer",                     // @RequestParam ref (search only, when set)
  "mode": "fts|vector|hybrid",                // @RequestParam mode (search only)
  "limit": 20, "offset": 0,                   // @RequestParam limit/offset
  "resultCount": 12,
  "trimmedCount": 3                           // raw.size() - filtered.size()
}

// KNOWLEDGE_CONTEXT_FETCH
{
  "_v": 1,
  "path": "/v1/knowledge/context",
  "fqn": "crm.Customer",
  "depth": 1,                                 // effective depth (after maxDepth cap)
  "prerequisiteCount": 4                      // traversed nodes that passed perspective filter
}

// KNOWLEDGE_ACCESS_DENIED
{
  "_v": 1,
  "path": "/v1/knowledge/search",
  "fqn": "crm.Customer",                      // attempted FQN (if known; null for list)
  "query": "...",                             // search query if applicable
  "reason": "no_read_capability" | "domain_not_allowed" | "type_not_allowed"
          | "type_denied" | "draft_not_allowed"
}
```

**Note on `entityFQN`:** Knowledge audit events are **access behaviors**, not entity mutations. The `entity_fqn` column on `event_log` is left **null** for the three new event types. Existing 5 event types continue to populate it.

---

## 5. Endpoint Instrumentation Matrix

| HTTP endpoint | Success event | DENIED event (policy block) | NOT_FOUND event |
|---|---|---|---|
| `GET /v1/knowledge` | `KNOWLEDGE_SEARCH` | `KNOWLEDGE_ACCESS_DENIED` (reason=`no_read_capability`) | (none) |
| `GET /v1/knowledge/search` | `KNOWLEDGE_SEARCH` | `KNOWLEDGE_ACCESS_DENIED` | (none, e.g. empty query) |
| `GET /v1/knowledge/graph/traverse` | `KNOWLEDGE_SEARCH` | `KNOWLEDGE_ACCESS_DENIED` | (none) |
| `GET /v1/knowledge/{id}` | (none) | `KNOWLEDGE_ACCESS_DENIED` | (none) |
| `GET /v1/knowledge/name/{fqn}` | (none) | `KNOWLEDGE_ACCESS_DENIED` | (none) |
| `GET /v1/knowledge/context` | `KNOWLEDGE_CONTEXT_FETCH` | `KNOWLEDGE_ACCESS_DENIED` | (none) |

**Rationale for "single-article read does not emit on success":** Avoids event-table bloat from agent loops. Dashboard signals (search frequency, denial rate) remain meaningful. Forensic gap is acceptable for non-PII content.

---

## 6. New Endpoint: `GET /v1/knowledge/context`

### 6.1 Contract

```
GET /v1/knowledge/context?fqn={fqn}&depth={1|2}&maxBytes={4096}
Headers: X-Agent-Id / X-Agent-Role / X-User (any one)

200 OK
{
  "root": {
    "fqn": "crm.Customer",
    "title": "Customer",
    "status": "PUBLISHED",
    "domain": "crm",
    "type": "Glossary",
    "body": "# Customer\n\nA customer is ...",
    "version": 3
  },
  "prerequisites": [
    { "fqn": "crm.Order",   "title": "Order",   "depth": 1 },
    { "fqn": "crm.Product", "title": "Product", "depth": 1 }
  ],
  "context": "# Customer\n\nA customer is ...\n\n## Prerequisite: Order\n\n...",
  "truncated": false
}

400 — missing fqn
404 — not found OR policy block (indistinguishable to client; 404 is convention)
```

### 6.2 Parameters

| Param | Type | Default | Notes |
|---|---|---|---|
| `fqn` | string | required | root article FQN |
| `depth` | int | `1` | traverse depth, clamped to [1, 2] |
| `maxBytes` | int | `4096` | upper bound on `context` field bytes |

### 6.3 Algorithm

1. Resolve `AccessPolicy` from actor headers.
2. If `!canRead(policy)` → emit DENIED, return 404.
3. Look up root article by FQN. If absent → return 404 (no event).
4. `checkVisibility(root, policy)`. If NOT_FOUND or DENIED → emit DENIED, return 404.
5. Call `graphService.traverse(fqn, effectiveDepth)` where `effectiveDepth = min(depth, perspectiveFilter.maxDepth(policy))`.
6. Build `context` string: root body first, then for each traversed node (excluding root), if `checkVisibility` is VISIBLE, append as `## Prerequisite: <title>\n\n<body>\n`. Stop when `length >= maxBytes` → `truncated=true`.
7. Emit `KNOWLEDGE_CONTEXT_FETCH` with `prerequisiteCount = <actually-appended count>`.
8. Return 200.

### 6.4 Endpoint Location

Lives in `KnowledgeArticleResource` (same file as `search` / `traverse`), not a new controller. Reuses existing dependencies: `jpa`, `graphService`, `perspectiveFilter`.

### 6.5 Known Limitations (to be called out in code comments)

- `prerequisiteCount` reflects the **filtered and truncated** count, not raw graph size. Operators infer "heavy graph" from `truncated=true` or `prerequisiteCount` near `maxBytes/avgBodySize`.
- `graphService.traverse()` return shape may need verification during implementation (assert it returns nodes with `fqn` + edge type metadata; spec TBD on read).
- Truncation is post-filter (simple but slightly wasteful vs. byte-budget-aware selection).

---

## 7. Error Handling & Invariants

| Invariant | Implementation |
|---|---|
| Business reads never fail because of audit | `eventLog.append()` wrapped in try/catch; exception logged at WARN, not propagated |
| Per-article policy denials return 404; bulk reads return 200 with empty results | `getById` / `getByFQN` / `context` blocked → 404 + DENIED; `list` / `search` / `traverse` blocked → 200 with `[]` + DENIED |
| Event payloads never include article body | Details are access metadata only; no `body` field in any of the 3 new event types |
| `details._v = 1` for forward compat | Set by emitter helper |
| `entity_fqn` is null on knowledge audit events | These are access behaviors, not entity changes |
| Event durability independent of request transaction | `EventLogRepository.append()` uses `@Transactional(propagation = REQUIRES_NEW)`, matching `FunctionService` pattern |

---

## 8. Testing Strategy

### 8.1 New: `KnowledgeArticleEventInstrumentationTest` (unit, no Spring)

Mock all collaborators. Coverage:

- `search` emits `KNOWLEDGE_SEARCH` with `resultCount` + `trimmedCount` + path
- `search` with `?ref=` emits with `ref` field
- `list` emits `KNOWLEDGE_SEARCH` with `resultCount` + `trimmedCount`
- `traverse` emits `KNOWLEDGE_SEARCH` for visible graph
- `getByFQN` DENIED emits `KNOWLEDGE_ACCESS_DENIED` with fqn + reason
- `getById` DENIED emits with fqn
- `search` DENIED emits with query
- `context` DENIED emits with fqn
- DENIED `reason` is one of the 5 documented strings
- **NOT_FOUND does NOT emit** (key negative test)
- **`getByFQN` success does NOT emit** (key negative test — single-read skip)
- `context` success emits `KNOWLEDGE_CONTEXT_FETCH` with `prerequisiteCount`
- `context` with `maxBytes=100` truncates → `truncated=true`
- Event `details.path` matches `HttpServletRequest.getRequestURI()` exactly
- **`eventLog.append()` failure does NOT break the response** (key negative test)

### 8.2 New: `KnowledgeContextEndpointIT` (integration, @SpringBootTest)

End-to-end:
- `context` returns 200 with root + prerequisites + context string assembled correctly
- `context` respects `KnowledgePerspectiveFilter` (denied prerequisites are excluded)
- `context` truncates at `maxBytes`
- `context` writes 1 row to `event_log` with `event_type=KNOWLEDGE_CONTEXT_FETCH`

### 8.3 New: `EventLogRepositoryDetailsTest` (unit)

JSONB round-trip: write event with `details` → read back → map equality.

### 8.4 Existing tests that must still pass

- `KnowledgePerspectiveFilterTest` — `canSee()` is now a thin wrapper; behavior unchanged
- `AuditServiceTest` — 5 existing EventTypes still work; 3 new types don't break counting
- `AgentExperienceCaptureTest` — unchanged; `CAPTURED_EVENTS` list doesn't include new types
- `KnowledgeCapabilityResolverTest` — unchanged
- `KnowledgeCapabilityTest` — unchanged

### 8.5 Manual smoke tests

1. Start server. `GET /v1/knowledge/search?q=customer` → query `event_log` → 1 row with `event_type=KNOWLEDGE_SEARCH`, `details.path=/v1/knowledge/search`, `details.resultCount` matches response.
2. `GET /v1/knowledge/name/secret-internal-doc` without admin header → 404 + 1 `KNOWLEDGE_ACCESS_DENIED` row with correct `reason`.
3. `GET /v1/knowledge/context?fqn=crm.Customer&depth=2` → 200, `context` non-empty, `prerequisiteCount >= 0`.
4. `GET /v1/audit/actors/{actor}/activity` → see all 3 new event types in breakdown.

### 8.6 Things explicitly NOT tested

- PostgreSQL JSONB indexing performance (no GIN index added)
- `event_log` query performance at 1M+ rows
- LLM prompt injection in `context` field (the field is meant for LLM consumption; injection attack surface is downstream)

---

## 9. File Change List

```
heirloom-server/src/main/java/com/heirloom/domain/ChangeEvent.java
  - add Map<String,Object> details field
  - add 3 EventType enum values

heirloom-server/src/main/java/com/heirloom/knowledge/service/KnowledgePerspectiveFilter.java
  - add Visibility enum
  - add checkVisibility(KnowledgeArticle, AccessPolicy)
  - rewrite canSee() to delegate to checkVisibility()

heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java
  - inject HttpServletRequest into list/getById/getByFQN/search/traverse
  - add GET /v1/knowledge/context (new)
  - add private emitKnowledgeEvent(...) helper
  - wire KNOWLEDGE_SEARCH in list/search/traverse
  - wire KNOWLEDGE_ACCESS_DENIED in 5 read endpoints + context
  - wire KNOWLEDGE_CONTEXT_FETCH in context

heirloom-server/src/main/resources/db/migration/V12__event_log_details.sql  (new)
  - ALTER TABLE event_log ADD COLUMN details JSONB;

heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeArticleEventInstrumentationTest.java  (new)
heirloom-server/src/test/java/com/heirloom/knowledge/web/KnowledgeContextEndpointIT.java               (new)
heirloom-server/src/test/java/com/heirloom/repository/EventLogRepositoryDetailsTest.java              (new)

docs/ROADMAP.md
  - check "知识审计事件" item
  - add v0.14 changelog row
```

**Estimated diff size:** ~500 lines server (300 prod + 200 tests), 0 SDK, 1 SQL migration.

---

## 10. Risk Register

| ID | Risk | Trigger | Mitigation |
|---|---|---|---|
| R1 | `/v1/knowledge/context` abused as low-cost traversal to bypass perspective audit | Agent loops with `context` + `depth=1` | `prerequisiteCount` in details; future rate-limit hook |
| R2 | Per-read DB INSERT degrades throughput | Knowledge read QPS > ~100 | Switch to async queue; REQUIRES_NEW keeps today acceptable |
| R3 | `details` JSONB schema has no version control | Old reader crashes when new key added | `_v: 1` field, increment on breaking payload changes |
| R4 | `details.path` may log raw query strings with sensitive data | Search `?q=secret-key` | We log `q` value, not URL — same exposure as response body. Redact later if needed |
| R5 | Existing 5 EventTypes don't populate `details`; future bulk-update risk | Any future change | Additive only; no retroactive requirement |

---

## 11. Roadmap Impact

Phase 3.1 currently has 4 unchecked items. After this work:

- ✅ Knowledge audit events (3 new types + 6 endpoint instrumentation + 1 new endpoint)
- ❌ SDK knowledge methods (`getContext` / `getPrerequisites`) — deferred to next Phase 3.1 work
- ❌ Auto context injection for `actions.execute()` — separate design work
- ❌ Agent-specific Role templates — actually **already implemented** (`ROLE_TEMPLATES` in `heirloom-sdk/__init__.py`); roadmap entry is stale, will mark checked opportunistically

`ROADMAP.md` v0.14 changelog row will note "Knowledge audit events (Phase 3.1) — 97 → 98/135 done."

---

## 12. Open Questions (for implementation phase, not blocking)

1. Does `KnowledgeGraphService.traverse()` currently return node metadata (title, depth) or just FQNs? Need to verify during implementation.
2. Should `KnowledgeArticleEventInstrumentationTest` use Mockito or hand-rolled fakes? Project convention: Mockito (verify `EventLogRepository` is the only collaborator not mocked).
3. Migration V12: any existing rows in `event_log` to backfill `details`? Default is `null` (no backfill). Confirm acceptable.

---

## 13. References

- ADR-005: Nine-step Action Pipeline
- ADR-007: Semantic Constrains Kinetic
- ADR-009: Perspective Engine Placement
- ADR-016: Change Event Interceptor
- ADR-035: Agent SDK & Perspective Integration
- Phase 3.4 spec (already shipped): `/v1/audit/*` endpoints + `AuditService`
- `KnowledgeArticleResource` current implementation: `heirloom-server/src/main/java/com/heirloom/knowledge/web/KnowledgeArticleResource.java`
- `FunctionService` precedent for REQUIRES_NEW event pattern
