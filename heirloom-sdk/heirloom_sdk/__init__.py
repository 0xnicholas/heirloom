"""Heirloom Python Agent SDK — Phase 3.1

Wraps the Heirloom HTTP API for AI agent consumers. Provides:

- ``HeirloomClient`` — top-level client with ``discover`` / ``query`` / namespace accessors
- ``KnowledgeNamespace`` — ``knowledge.search / get / traverse / quality / list``
- ``KnowledgeSourceNamespace`` — ``sources.list / create / sync / export / import / webhook``
- ``ProposalsNamespace`` — ``proposals.list / approve / reject``
- ``search_semantic()`` — hybrid full-text + vector retrieval

Each request carries ``X-Agent-Id`` and ``X-Agent-Role`` headers so the
server-side Perspective Engine and Event Log can attribute the call.
"""

import copy
import requests
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any, Iterable
import logging

logger = logging.getLogger("heirloom")

# --- Agent role templates (Phase 3.1) ---
# Minimal-capability role definitions for agent bootstrapping. Pass the
# resulting dict into ``create_resource_type`` / role-registration calls.

ROLE_TEMPLATES: Dict[str, Dict[str, Any]] = {
    "data_analyst": {
        "name": "DataAnalyst",
        "capabilities": ["query.read", "knowledge.search", "knowledge.read"],
        "restrictions": {"mutate": False, "admin": False},
    },
    "data_steward": {
        "name": "DataSteward",
        "capabilities": [
            "query.read", "knowledge.search", "knowledge.read",
            "knowledge.write", "proposal.create", "proposal.review",
        ],
        "restrictions": {"admin": False},
    },
    "supply_chain_analyst": {
        "name": "SupplyChainAnalyst",
        "capabilities": [
            "query.read", "knowledge.search", "knowledge.read",
            "query.aggregate",
        ],
        "restrictions": {"mutate": False, "admin": False, "domain": "supply_chain"},
    },
    "notification_only": {
        "name": "NotificationAgent",
        "capabilities": ["notification.send", "query.read"],
        "restrictions": {"mutate": False},
    },
}


@dataclass
class HeirloomClient:
    """Client for Heirloom semantic platform. Agent SDK entry point."""

    base_url: str
    agent_id: str = "anonymous"
    agent_role: str = "default"
    _session: requests.Session = field(default_factory=requests.Session, repr=False)

    def __post_init__(self):
        self._session.headers.update({
            "X-Agent-Id": self.agent_id,
            "X-Agent-Role": self.agent_role,
            "Content-Type": "application/json",
        })
        # Lazily-initialised namespaces — call ``client.knowledge.search(...)`` etc.
        self._knowledge: Optional["KnowledgeNamespace"] = None
        self._knowledge_sources: Optional["KnowledgeSourceNamespace"] = None
        self._proposals: Optional["ProposalsNamespace"] = None
        self._functions: Optional["FunctionsNamespace"] = None
        self._audit: Optional["AuditNamespace"] = None
        self._resources: Optional["ResourcesNamespace"] = None
        self._actions: Optional["ActionsNamespace"] = None
        self._nlq: Optional["NLQNamespace"] = None
        self._graph: Optional["GraphNamespace"] = None

    # --- Namespace accessors ---

    @property
    def knowledge(self) -> "KnowledgeNamespace":
        if self._knowledge is None:
            self._knowledge = KnowledgeNamespace(self)
        return self._knowledge

    @property
    def knowledge_sources(self) -> "KnowledgeSourceNamespace":
        if self._knowledge_sources is None:
            self._knowledge_sources = KnowledgeSourceNamespace(self)
        return self._knowledge_sources

    @property
    def proposals(self) -> "ProposalsNamespace":
        if self._proposals is None:
            self._proposals = ProposalsNamespace(self)
        return self._proposals

    @property
    def functions(self) -> "FunctionsNamespace":
        if self._functions is None:
            self._functions = FunctionsNamespace(self)
        return self._functions

    @property
    def audit(self) -> "AuditNamespace":
        if self._audit is None:
            self._audit = AuditNamespace(self)
        return self._audit

    @property
    def resources(self) -> "ResourcesNamespace":
        if self._resources is None:
            self._resources = ResourcesNamespace(self)
        return self._resources

    @property
    def actions(self) -> "ActionsNamespace":
        if self._actions is None:
            self._actions = ActionsNamespace(self)
        return self._actions

    @property
    def nlq(self) -> "NLQNamespace":
        if self._nlq is None:
            self._nlq = NLQNamespace(self)
        return self._nlq

    @property
    def graph(self) -> "GraphNamespace":
        if self._graph is None:
            self._graph = GraphNamespace(self)
        return self._graph

    # === Discovery ===

    def discover(self) -> List[Dict[str, Any]]:
        """List available ResourceTypes (Role-filtered by Perspective Engine)."""
        r = self._session.get(f"{self.base_url}/v1/resourceTypes")
        r.raise_for_status()
        return r.json()

    def get_type(self, name: str) -> Dict[str, Any]:
        """Get a specific ResourceType by name."""
        r = self._session.get(f"{self.base_url}/v1/resourceTypes/name/{name}")
        r.raise_for_status()
        return r.json()

    # === Query ===

    def query(self, json_dsl: Dict[str, Any]) -> Dict[str, Any]:
        """Execute a semantic query. Returns data + metadata context."""
        r = self._session.post(f"{self.base_url}/v1/query", json=json_dsl)
        r.raise_for_status()
        return r.json()

    def search_semantic(self, query: str, limit: int = 10) -> List[Dict[str, Any]]:
        """Hybrid (full-text + vector) search across the semantic index.

        Backed by ``GET /v1/search?q=...&limit=...``. Returns ranked hits
        mixing keyword (FTS) and embedding-similarity scores via RRF.
        """
        r = self._session.get(
            f"{self.base_url}/v1/search",
            params={"q": query, "limit": limit},
        )
        r.raise_for_status()
        return r.json()

    # === Discovery Triggers ===

    def create_discovery_source(self, config: Dict[str, Any]) -> Dict[str, Any]:
        """Register a new data source for automated discovery."""
        r = self._session.post(f"{self.base_url}/v1/discovery/sources", json=config)
        r.raise_for_status()
        return r.json()

    def run_discovery(self, source_fqn: str) -> Dict[str, Any]:
        """Trigger a discovery scan for a configured source."""
        r = self._session.post(f"{self.base_url}/v1/discovery/sources/{source_fqn}/run")
        r.raise_for_status()
        return r.json()

    def get_discovery_reports(self, source_fqn: str) -> List[Dict[str, Any]]:
        """Get scan history for a discovery source."""
        r = self._session.get(f"{self.base_url}/v1/discovery/reports", params={"source": source_fqn})
        r.raise_for_status()
        return r.json()

    # === Resource Type Management ===

    def create_resource_type(self, type_def: Dict[str, Any]) -> Dict[str, Any]:
        """Create a ResourceType definition."""
        r = self._session.post(f"{self.base_url}/v1/resourceTypes", json=type_def)
        r.raise_for_status()
        return r.json()

    def delete_resource_type(self, type_id: int) -> None:
        """Delete a ResourceType by ID."""
        r = self._session.delete(f"{self.base_url}/v1/resourceTypes/{type_id}")
        r.raise_for_status()

    # === Role Template Helpers (Phase 3.1) ===

    @staticmethod
    def role_template(name: str) -> Dict[str, Any]:
        """Return a copy of a built-in role template by short name.

        Available: ``data_analyst``, ``data_steward``, ``supply_chain_analyst``,
        ``notification_only``. Raises ``KeyError`` if name is unknown.
        """
        return copy.deepcopy(ROLE_TEMPLATES[name])

    @staticmethod
    def list_role_templates() -> List[str]:
        """Return short names of all built-in role templates."""
        return list(ROLE_TEMPLATES.keys())

    # === Utilities ===

    def close(self):
        """Close the underlying HTTP session."""
        self._session.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


@dataclass
class KnowledgeNamespace:
    """Knowledge Base operations — ``client.knowledge.*``."""

    _client: HeirloomClient

    def list(self) -> List[Dict[str, Any]]:
        """List all indexed KnowledgeArticles."""
        r = self._client._session.get(f"{self._client.base_url}/v1/knowledge")
        r.raise_for_status()
        return r.json()

    def get(self, fqn: str) -> Dict[str, Any]:
        """Get a KnowledgeArticle by fully-qualified name."""
        r = self._client._session.get(f"{self._client.base_url}/v1/knowledge/name/{fqn}")
        r.raise_for_status()
        return r.json()

    def search(self, query: str, limit: int = 10) -> List[Dict[str, Any]]:
        """Full-text search across KnowledgeArticle bodies.

        Backed by ``GET /v1/knowledge/search?q=...&limit=...``. For hybrid
        (full-text + vector) search, use ``client.search_semantic(...)``.
        """
        r = self._client._session.get(
            f"{self._client.base_url}/v1/knowledge/search",
            params={"q": query, "limit": limit},
        )
        r.raise_for_status()
        return r.json()

    def traverse(self, root_fqn: str, max_depth: int = 3,
                 relation_types: Optional[Iterable[str]] = None) -> Dict[str, Any]:
        """Graph traversal from a KnowledgeArticle root.

        ``relation_types`` filters by reference-edge type (e.g.
        ``["references", "cites"]``). Returns a tree-shaped structure of
        reachable articles with depth metadata.
        """
        params: Dict[str, Any] = {"maxDepth": max_depth}
        if relation_types:
            params["relations"] = ",".join(relation_types)
        r = self._client._session.get(
            f"{self._client.base_url}/v1/knowledge/graph/traverse",
            params={**params, "root": root_fqn},
        )
        r.raise_for_status()
        return r.json()

    def quality(self, article_id: int) -> Dict[str, Any]:
        """Get quality-scoring report for a KnowledgeArticle.

        Returns freshness, reference density, completeness, and overall score.
        """
        r = self._client._session.get(
            f"{self._client.base_url}/v1/knowledge/{article_id}/quality"
        )
        r.raise_for_status()
        return r.json()

    def promote(self, article_id: int, target_status: str) -> Dict[str, Any]:
        """Submit a promotion request (e.g. draft→review or review→published).

        Promotion may require human approval via the Proposal workflow
        depending on the server-side governance configuration.
        """
        r = self._client._session.post(
            f"{self._client.base_url}/v1/knowledge/promote",
            json={"articleId": article_id, "targetStatus": target_status},
        )
        r.raise_for_status()
        return r.json()


@dataclass
class KnowledgeSourceNamespace:
    """KnowledgeSource registry — ``client.knowledge_sources.*``.

    Sources are file-system directories (or external systems) that the sync
    engine scans and indexes into KnowledgeArticles.
    """

    _client: HeirloomClient

    def list(self) -> List[Dict[str, Any]]:
        """List all configured KnowledgeSources."""
        r = self._client._session.get(f"{self._client.base_url}/v1/knowledge/sources")
        r.raise_for_status()
        return r.json()

    def get(self, source_id: int) -> Dict[str, Any]:
        """Get one KnowledgeSource by ID."""
        r = self._client._session.get(f"{self._client.base_url}/v1/knowledge/sources/{source_id}")
        r.raise_for_status()
        return r.json()

    def create(self, config: Dict[str, Any]) -> Dict[str, Any]:
        """Register a new KnowledgeSource.

        ``config`` typically includes ``name``, ``rootPath``, ``globPattern``,
        and optional ``webhookSecret`` for git-triggered sync.
        """
        r = self._client._session.post(
            f"{self._client.base_url}/v1/knowledge/sources", json=config
        )
        r.raise_for_status()
        return r.json()

    def update(self, source_id: int, config: Dict[str, Any]) -> Dict[str, Any]:
        """Update an existing KnowledgeSource."""
        r = self._client._session.put(
            f"{self._client.base_url}/v1/knowledge/sources/{source_id}", json=config
        )
        r.raise_for_status()
        return r.json()

    def delete(self, source_id: int) -> None:
        """Remove a KnowledgeSource. Does not delete indexed articles."""
        r = self._client._session.delete(
            f"{self._client.base_url}/v1/knowledge/sources/{source_id}"
        )
        r.raise_for_status()

    def sync(self, source_id: int) -> Dict[str, Any]:
        """Manually trigger a sync scan. Returns SyncReport with diff summary."""
        r = self._client._session.post(
            f"{self._client.base_url}/v1/knowledge/sources/{source_id}/sync"
        )
        r.raise_for_status()
        return r.json()

    def export(self, source_id: int) -> bytes:
        """Export the source's on-disk knowledge tree as a tar.gz archive.

        Used for OKF (Open Knowledge Format) portability — round-trip a
        knowledge tree between Heirloom instances.
        """
        r = self._client._session.get(
            f"{self._client.base_url}/v1/knowledge/sources/{source_id}/export"
        )
        r.raise_for_status()
        return r.content

    def webhook(self, source_name: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        """Fire a git-webhook-style payload to trigger on-commit sync.

        Server validates against the source's stored secret.
        """
        r = self._client._session.post(
            f"{self._client.base_url}/v1/knowledge/sources/webhook/{source_name}",
            json=payload,
        )
        r.raise_for_status()
        return r.json()

    def import_articles(self, source_id: int, archive: bytes) -> Dict[str, Any]:
        """Import an OKF tar.gz archive into the given source."""
        r = self._client._session.post(
            f"{self._client.base_url}/v1/knowledge/sources/{source_id}/import",
            data=archive,
            headers={"Content-Type": "application/gzip"},
        )
        r.raise_for_status()
        return r.json()


@dataclass
class ProposalsNamespace:
    """Governance Proposals — ``client.proposals.*``.

    Proposals are the workflow primitive for Schema changes, Knowledge
    promotion, and other agent-initiated mutations requiring review.
    """

    _client: HeirloomClient

    def list(self, status: Optional[str] = None) -> List[Dict[str, Any]]:
        """List proposals, optionally filtered by status (pending/approved/rejected)."""
        params = {"status": status} if status else {}
        r = self._client._session.get(
            f"{self._client.base_url}/v1/proposals", params=params
        )
        r.raise_for_status()
        return r.json()

    def get(self, proposal_id: int) -> Dict[str, Any]:
        """Get one proposal by ID."""
        r = self._client._session.get(
            f"{self._client.base_url}/v1/proposals/{proposal_id}"
        )
        r.raise_for_status()
        return r.json()

    def approve(self, proposal_id: int, comment: Optional[str] = None) -> Dict[str, Any]:
        """Approve a pending proposal. May trigger downstream effects (e.g.
        schema change application, knowledge article status transition)."""
        r = self._client._session.post(
            f"{self._client.base_url}/v1/proposals/{proposal_id}/approve",
            json={"comment": comment} if comment else {},
        )
        r.raise_for_status()
        return r.json()

    def reject(self, proposal_id: int, reason: str) -> Dict[str, Any]:
        """Reject a pending proposal with a reason."""
        r = self._client._session.post(
            f"{self._client.base_url}/v1/proposals/{proposal_id}/reject",
            json={"reason": reason},
        )
        r.raise_for_status()
        return r.json()


@dataclass
class FunctionsNamespace:
    """Function engine — ``client.functions.*``.

    Functions are sandboxed, read-only computations defined by a SpEL expression.
    Invoke them by name with a JSON-serialisable input map; the server returns
    the computed result. Functions may emit audit events depending on their
    ``auditEnabled`` flag.
    """

    _client: HeirloomClient

    def list(self) -> List[Dict[str, Any]]:
        """List all registered Functions."""
        r = self._client._session.get(f"{self._client.base_url}/v1/functions")
        r.raise_for_status()
        return r.json()

    def get(self, name: str) -> Dict[str, Any]:
        """Get a Function definition by name."""
        r = self._client._session.get(f"{self._client.base_url}/v1/functions/name/{name}")
        r.raise_for_status()
        return r.json()

    def invoke(self, name: str, inputs: Optional[Dict[str, Any]] = None) -> Any:
        """Execute a Function in the server-side sandbox.

        ``inputs`` is bound as SpEL variables referenced via ``#name`` in the
        function's expression. Returns the raw result; Function output type
        is documented in the function definition (``NUMBER`` / ``STRING`` /
        ``BOOLEAN`` / ``OBJECT``).
        """
        r = self._client._session.post(
            f"{self._client.base_url}/v1/functions/name/{name}/invoke",
            json={"inputs": inputs or {}},
        )
        r.raise_for_status()
        return r.json().get("result")

    def create(self, definition: Dict[str, Any]) -> Dict[str, Any]:
        """Register a new Function.

        ``definition`` keys: ``name``, ``description``, ``code`` (SpEL expression),
        ``inputType``, ``outputType``, optional ``timeoutMs`` and ``auditEnabled``.
        """
        r = self._client._session.post(
            f"{self._client.base_url}/v1/functions", json=definition
        )
        r.raise_for_status()
        return r.json()


@dataclass
class AuditNamespace:
    """Agent audit & monitoring — ``client.audit.*``.

    Read-side projections over the Event Log for dashboard widgets and
    operator debugging. Times are ISO-8601 (e.g. ``2026-06-22T00:00:00Z``);
    ``since`` and ``until`` default to the last 24 hours.
    """

    _client: HeirloomClient

    def activity(self, actor: str, since: Optional[str] = None,
                 until: Optional[str] = None) -> Dict[str, Any]:
        """Event counts for an actor grouped by type. Dashboard widget feed."""
        params = {k: v for k, v in {"since": since, "until": until}.items() if v}
        r = self._client._session.get(
            f"{self._client.base_url}/v1/audit/actors/{actor}/activity",
            params=params,
        )
        r.raise_for_status()
        return r.json()

    def anomaly(self, actor: str, since: Optional[str] = None,
                until: Optional[str] = None) -> Dict[str, Any]:
        """Denial-rate verdict for an actor. Returns ``flagged`` boolean +
        ``deniedRate`` float + ``reason`` string. Use this to drive alerting."""
        params = {k: v for k, v in {"since": since, "until": until}.items() if v}
        r = self._client._session.get(
            f"{self._client.base_url}/v1/audit/actors/{actor}/anomaly",
            params=params,
        )
        r.raise_for_status()
        return r.json()

    def replay(self, actor: str, since: Optional[str] = None,
               until: Optional[str] = None) -> Dict[str, Any]:
        """Chronological event list for an actor — reconstructs the
        decision chain in a session. For debugging and postmortem review."""
        params = {k: v for k, v in {"since": since, "until": until}.items() if v}
        r = self._client._session.get(
            f"{self._client.base_url}/v1/audit/actors/{actor}/replay",
            params=params,
        )
        r.raise_for_status()
        return r.json()

    def entity_history(self, entity_fqn: str, since: Optional[str] = None,
                       until: Optional[str] = None) -> Dict[str, Any]:
        """All events touching one entity. Forensics / lineage debugging."""
        params = {k: v for k, v in {"since": since, "until": until}.items() if v}
        r = self._client._session.get(
            f"{self._client.base_url}/v1/audit/entities/{entity_fqn}/history",
            params=params,
        )
        r.raise_for_status()
        return r.json()


@dataclass
class ResourcesNamespace:
    """Resource instance operations — ``client.resources.*``."""

    _client: HeirloomClient

    def create(self, resource_type: str, fields: Dict[str, Any],
               owner: str = "agent") -> Dict[str, Any]:
        """Create a new Resource instance. RID is auto-generated."""
        r = self._client._session.post(
            f"{self._client.base_url}/v1/resources",
            json={"resourceType": resource_type, "owner": owner, "fields": fields},
        )
        r.raise_for_status()
        return r.json()

    def get(self, rid: str) -> Dict[str, Any]:
        """Get a Resource by RID."""
        r = self._client._session.get(f"{self._client.base_url}/v1/resources/{rid}")
        r.raise_for_status()
        return r.json()

    def list(self, type: Optional[str] = None, state: Optional[str] = None,
             limit: int = 20, offset: int = 0) -> Dict[str, Any]:
        """List resources with optional type/state filters."""
        params = {"limit": limit, "offset": offset}
        if type:
            params["type"] = type
        if state:
            params["state"] = state
        r = self._client._session.get(
            f"{self._client.base_url}/v1/resources", params=params
        )
        r.raise_for_status()
        return r.json()

    def update(self, rid: str, fields: Dict[str, Any],
               expected_version: int = 0) -> Dict[str, Any]:
        """Update fields on a Resource (partial merge). Uses optimistic locking."""
        headers = {"If-Match": str(expected_version)}
        r = self._client._session.put(
            f"{self._client.base_url}/v1/resources/{rid}",
            json={"fields": fields},
            headers=headers,
        )
        r.raise_for_status()
        return r.json()

    def transition_state(self, rid: str, target_state: str) -> Dict[str, Any]:
        """Transition a Resource to a new lifecycle state."""
        r = self._client._session.patch(
            f"{self._client.base_url}/v1/resources/{rid}/state",
            json={"targetState": target_state},
        )
        r.raise_for_status()
        return r.json()


@dataclass
class ActionsNamespace:
    """Action execution — ``client.actions.*``."""

    _client: HeirloomClient

    def execute(self, action_name: str, target_resource_id: str,
                params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """Execute an Action through the nine-step pipeline.

        Returns a PipelineResult with step-level status and timing.
        """
        r = self._client._session.post(
            f"{self._client.base_url}/v1/actions/{action_name}/execute",
            json={
                "targetResourceId": target_resource_id,
                "params": params or {},
            },
        )
        r.raise_for_status()
        return r.json()

@dataclass
class NLQNamespace:
    """Natural Language Query — ``client.nlq.*``.

    Phase 3.3: Translates natural language questions into Heirloom JSON DSL
    via an LLM (OpenAI), then executes the query against registered ResourceTypes.
    """

    _client: HeirloomClient

    def ask(self, question: str, mode: Optional[str] = None) -> Dict[str, Any]:
        """Ask a natural language question and get executed results back.

        ``mode`` can be "semantic" (default), "raw", or "hybrid".
        Returns ``{"success": bool, "generatedQuery": str, "result": {...}}``.
        """
        payload: Dict[str, Any] = {"question": question}
        if mode:
            payload["mode"] = mode
        r = self._client._session.post(
            f"{self._client.base_url}/v1/query/nlq",
            json=payload,
        )
        r.raise_for_status()
        return r.json()

    def translate(self, question: str) -> Dict[str, Any]:
        """Translate a question to JSON DSL without executing.

        Returns ``{"success": bool, "generatedQuery": str}``.
        Use this to preview what the LLM generated before running it.
        """
        r = self._client._session.post(
            f"{self._client.base_url}/v1/query/nlq/translate",
            json={"question": question},
        )
        r.raise_for_status()
        return r.json()


@dataclass
class GraphNamespace:
    """Graph Store — ``client.graph.*``.

    Phase 2.5: Manages instance-level resource relationships and graph traversal.
    Supports relationship CRUD, BFS traversal, and ownership chain resolution.
    """

    _client: HeirloomClient

    def create_relationship(self, source_rid: str, target_rid: str,
                            relationship_type: str, semantics: str = "REFERENCE",
                            created_by: str = "agent") -> Dict[str, Any]:
        """Create a relationship between two resources.

        ``semantics``: ``OWNERSHIP`` (cascade delete), ``REFERENCE`` (weak link),
        or ``ASSOCIATION`` (loose coupling).
        """
        r = self._client._session.post(
            f"{self._client.base_url}/v1/graph/relationships",
            json={
                "sourceRid": source_rid,
                "targetRid": target_rid,
                "relationshipType": relationship_type,
                "semantics": semantics,
                "createdBy": created_by,
            },
        )
        r.raise_for_status()
        return r.json()

    def delete_relationship(self, relationship_id: int) -> None:
        """Soft-delete a relationship by ID."""
        r = self._client._session.delete(
            f"{self._client.base_url}/v1/graph/relationships/{relationship_id}"
        )
        r.raise_for_status()

    def outgoing(self, rid: str) -> List[Dict[str, Any]]:
        """Get all outgoing relationships from a resource."""
        r = self._client._session.get(
            f"{self._client.base_url}/v1/graph/outgoing/{rid}"
        )
        r.raise_for_status()
        return r.json()

    def incoming(self, rid: str) -> List[Dict[str, Any]]:
        """Get all incoming relationships to a resource."""
        r = self._client._session.get(
            f"{self._client.base_url}/v1/graph/incoming/{rid}"
        )
        r.raise_for_status()
        return r.json()

    def traverse(self, rid: str, depth: int = 3) -> List[Dict[str, Any]]:
        """BFS graph traversal from a resource up to ``depth`` hops."""
        r = self._client._session.get(
            f"{self._client.base_url}/v1/graph/traverse/{rid}",
            params={"depth": depth},
        )
        r.raise_for_status()
        return r.json()

    def ultimate_owner(self, rid: str) -> Dict[str, Any]:
        """Resolve the ultimate owner of a resource via the OWNERSHIP chain."""
        r = self._client._session.get(
            f"{self._client.base_url}/v1/graph/owner/{rid}"
        )
        r.raise_for_status()
        return r.json()
