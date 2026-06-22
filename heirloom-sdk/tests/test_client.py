"""Tests for Heirloom Python SDK"""

import pytest
from heirloom_sdk import HeirloomClient, ROLE_TEMPLATES
from unittest.mock import Mock


def _mock_response(json_value=None, content=None):
    """Build a Mock response with .json() / .raise_for_status() / .content."""
    r = Mock()
    r.json.return_value = json_value
    r.content = content if content is not None else b""
    r.raise_for_status = Mock()
    return r


class TestHeirloomClient:
    def test_discover(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.get = Mock(return_value=_mock_response(
            [{"name": "Customer", "fullyQualifiedName": "crm.Customer"}]))

        result = client.discover()
        assert len(result) == 1
        assert result[0]["name"] == "Customer"
        client._session.get.assert_called_with("http://localhost:8080/v1/resourceTypes")

    def test_query(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response(
            {"data": [{"name": "Acme"}], "context": {}}))

        result = client.query({"type": "Customer", "fields": ["name"]})
        assert result["data"][0]["name"] == "Acme"

    def test_run_discovery(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response(
            {"status": "SUCCESS", "tablesScanned": 5}))

        result = client.run_discovery("prod.postgres-analytics")
        assert result["status"] == "SUCCESS"
        client._session.post.assert_called_with(
            "http://localhost:8080/v1/discovery/sources/prod.postgres-analytics/run")

    def test_agent_headers_sent(self):
        client = HeirloomClient("http://localhost:8080",
                                agent_id="agent-007", agent_role="DataAnalyst")
        assert client._session.headers["X-Agent-Id"] == "agent-007"
        assert client._session.headers["X-Agent-Role"] == "DataAnalyst"

    def test_search_semantic(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.get = Mock(return_value=_mock_response([
            {"fqn": "crm.Customer", "score": 0.91},
            {"fqn": "crm.Order", "score": 0.82},
        ]))

        result = client.search_semantic("customer retention", limit=5)
        assert len(result) == 2
        assert result[0]["fqn"] == "crm.Customer"
        client._session.get.assert_called_with(
            "http://localhost:8080/v1/search",
            params={"q": "customer retention", "limit": 5},
        )

    def test_role_template_lookup(self):
        template = HeirloomClient.role_template("data_analyst")
        assert template["name"] == "DataAnalyst"
        assert "query.read" in template["capabilities"]
        # Mutating the returned dict must not mutate the global table.
        template["capabilities"].append("forbidden")
        assert "forbidden" not in ROLE_TEMPLATES["data_analyst"]["capabilities"]

    def test_role_template_unknown_raises(self):
        with pytest.raises(KeyError):
            HeirloomClient.role_template("not_a_real_template")

    def test_list_role_templates(self):
        names = HeirloomClient.list_role_templates()
        assert {"data_analyst", "data_steward",
                "supply_chain_analyst", "notification_only"} <= set(names)


class TestKnowledgeNamespace:
    def test_list(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.get = Mock(return_value=_mock_response([
            {"fqn": "crm.Customer", "status": "published"}]))

        result = client.knowledge.list()
        assert result[0]["fqn"] == "crm.Customer"
        client._session.get.assert_called_with("http://localhost:8080/v1/knowledge")

    def test_get_by_fqn(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.get = Mock(return_value=_mock_response(
            {"fqn": "crm.Customer", "body": "# Customer"}))
        result = client.knowledge.get("crm.Customer")
        assert result["fqn"] == "crm.Customer"

    def test_search(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.get = Mock(return_value=_mock_response([
            {"fqn": "crm.Customer", "snippet": "...customer..."}]))
        result = client.knowledge.search("customer")
        assert result[0]["fqn"] == "crm.Customer"
        client._session.get.assert_called_with(
            "http://localhost:8080/v1/knowledge/search",
            params={"q": "customer", "limit": 10},
        )

    def test_search_custom_limit(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.get = Mock(return_value=_mock_response([]))
        client.knowledge.search("anything", limit=3)
        _, kwargs = client._session.get.call_args
        assert kwargs["params"]["limit"] == 3

    def test_traverse(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.get = Mock(return_value=_mock_response(
            {"root": "crm.Customer", "depth": 2, "nodes": []}))
        client.knowledge.traverse("crm.Customer", max_depth=2,
                                  relation_types=["references"])
        client._session.get.assert_called_with(
            "http://localhost:8080/v1/knowledge/graph/traverse",
            params={"maxDepth": 2, "relations": "references", "root": "crm.Customer"},
        )

    def test_quality(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.get = Mock(return_value=_mock_response(
            {"articleId": 42, "overallScore": 0.85}))
        result = client.knowledge.quality(42)
        assert result["overallScore"] == 0.85
        client._session.get.assert_called_with(
            "http://localhost:8080/v1/knowledge/42/quality")

    def test_promote(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response(
            {"proposalId": 7, "status": "pending"}))
        result = client.knowledge.promote(42, "review")
        assert result["proposalId"] == 7
        client._session.post.assert_called_with(
            "http://localhost:8080/v1/knowledge/promote",
            json={"articleId": 42, "targetStatus": "review"},
        )


class TestKnowledgeSourceNamespace:
    def test_create(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response(
            {"id": 1, "name": "wiki"}))
        result = client.knowledge_sources.create(
            {"name": "wiki", "rootPath": "/docs", "globPattern": "**/*.md"})
        assert result["name"] == "wiki"

    def test_sync(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response(
            {"added": 3, "updated": 1, "deleted": 0}))
        result = client.knowledge_sources.sync(1)
        assert result["added"] == 3

    def test_export_returns_bytes(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.get = Mock(return_value=_mock_response(
            content=b"\x1f\x8b\x08fake-gzip-bytes"))
        blob = client.knowledge_sources.export(1)
        assert blob == b"\x1f\x8b\x08fake-gzip-bytes"

    def test_webhook(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response(
            {"triggered": True, "scanStartedAt": "2026-06-22T00:00:00Z"}))
        client.knowledge_sources.webhook(
            "wiki", {"ref": "refs/heads/main", "commits": ["abc123"]})
        client._session.post.assert_called_with(
            "http://localhost:8080/v1/knowledge/sources/webhook/wiki",
            json={"ref": "refs/heads/main", "commits": ["abc123"]},
        )

    def test_import_articles_sends_bytes(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response({"imported": 5}))
        client.knowledge_sources.import_articles(1, b"archive-bytes")
        args, kwargs = client._session.post.call_args
        url = args[0]
        assert url.endswith("/v1/knowledge/sources/1/import")
        assert kwargs["data"] == b"archive-bytes"
        assert kwargs["headers"]["Content-Type"] == "application/gzip"


class TestProposalsNamespace:
    def test_list_no_filter(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.get = Mock(return_value=_mock_response([{"id": 1}]))
        client.proposals.list()
        _, kwargs = client._session.get.call_args
        assert kwargs["params"] == {}

    def test_list_with_status(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.get = Mock(return_value=_mock_response([]))
        client.proposals.list(status="pending")
        _, kwargs = client._session.get.call_args
        assert kwargs["params"] == {"status": "pending"}

    def test_approve(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response(
            {"id": 7, "status": "approved"}))
        client.proposals.approve(7, comment="LGTM")
        client._session.post.assert_called_with(
            "http://localhost:8080/v1/proposals/7/approve",
            json={"comment": "LGTM"},
        )

    def test_approve_without_comment(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response({"id": 7}))
        client.proposals.approve(7)
        _, kwargs = client._session.post.call_args
        assert kwargs["json"] == {}

    def test_reject_requires_reason(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response({"id": 7}))
        client.proposals.reject(7, reason="missing evidence")
        client._session.post.assert_called_with(
            "http://localhost:8080/v1/proposals/7/reject",
            json={"reason": "missing evidence"},
        )


class TestFunctionsNamespace:
    def test_invoke(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response(
            {"functionName": "risk_score", "outputType": "NUMBER", "result": 0.87}))
        result = client.functions.invoke(
            "risk_score", inputs={"amount": 1000, "tier": "gold"})
        assert result == 0.87
        client._session.post.assert_called_with(
            "http://localhost:8080/v1/functions/name/risk_score/invoke",
            json={"inputs": {"amount": 1000, "tier": "gold"}},
        )

    def test_invoke_no_inputs(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response(
            {"functionName": "pi", "result": 3.14159}))
        result = client.functions.invoke("pi")
        assert result == 3.14159
        _, kwargs = client._session.post.call_args
        assert kwargs["json"] == {"inputs": {}}

    def test_get(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.get = Mock(return_value=_mock_response(
            {"name": "risk_score", "code": "#amount * 0.1"}))
        fn = client.functions.get("risk_score")
        assert fn["code"] == "#amount * 0.1"

    def test_create(self):
        client = HeirloomClient("http://localhost:8080")
        client._session.post = Mock(return_value=_mock_response(
            {"id": 1, "name": "discount", "code": "#price * 0.9"}))
        created = client.functions.create(
            {"name": "discount", "code": "#price * 0.9", "outputType": "NUMBER"})
        assert created["name"] == "discount"