"""Tests for the Heirloom CLI. Mocks the SDK so the parser + dispatch
are exercised without hitting a real server."""

import io
import json
import sys
from contextlib import redirect_stdout, redirect_stderr
from unittest.mock import MagicMock, patch

import pytest

from heirloom_sdk.cli import build_parser, main


@pytest.fixture
def fake_client():
    client = MagicMock()
    client.base_url = "http://localhost:8080"
    client._session = MagicMock()
    return client


def _run_cli(argv, fake_client):
    """Invoke main(argv) with the SDK patched to return fake_client."""
    with patch("heirloom_sdk.cli.HeirloomClient", return_value=fake_client):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = main(argv)
    return rc, out.getvalue(), err.getvalue()


def test_help_exits_zero(capsys):
    rc = main(["--help"])  # argparse exits via SystemExit
    out = capsys.readouterr().out
    assert rc == 0  # our wrapper caught SystemExit
    assert "usage:" in out.lower() or "heirloom" in out.lower()


def test_type_list_outputs_json(fake_client):
    fake_client.discover.return_value = [{"name": "Customer", "fullyQualifiedName": "crm.Customer"}]
    rc, out, _ = _run_cli(["type", "list"], fake_client)
    assert rc == 0
    parsed = json.loads(out)
    assert parsed == [{"name": "Customer", "fullyQualifiedName": "crm.Customer"}]


def test_type_get(fake_client):
    fake_client.get_type.return_value = {"name": "Customer", "domain": "crm"}
    rc, out, _ = _run_cli(["type", "get", "Customer"], fake_client)
    assert rc == 0
    assert json.loads(out)["name"] == "Customer"
    fake_client.get_type.assert_called_with("Customer")


def test_type_create_passes_flags(fake_client):
    fake_client.create_resource_type.return_value = {"id": 1, "name": "NewType"}
    rc, out, _ = _run_cli(
        ["type", "create", "--name", "NewType", "--description", "A test type",
         "--domain", "engineering"],
        fake_client)
    assert rc == 0
    fake_client.create_resource_type.assert_called_with({
        "name": "NewType", "description": "A test type", "domain": "engineering"})


def test_article_search_with_limit(fake_client):
    fake_client.knowledge.search.return_value = [{"fqn": "crm.Order"}]
    rc, out, _ = _run_cli(["article", "search", "orders", "--limit", "5"], fake_client)
    assert rc == 0
    fake_client.knowledge.search.assert_called_with("orders", limit=5)


def test_knowledge_coverage_calls_rest(fake_client):
    fake_client._session.get.return_value.json.return_value = {
        "totalArticles": 10, "totalTables": 5, "coverageRatio": 0.5,
        "tablesWithCoverage": 3, "orphanTableCount": 2}
    fake_client._session.get.return_value.raise_for_status = MagicMock()
    rc, out, _ = _run_cli(["knowledge", "coverage"], fake_client)
    assert rc == 0
    data = json.loads(out)
    assert data["totalArticles"] == 10
    # Verify the URL hit
    called_url = fake_client._session.get.call_args[0][0]
    assert called_url.endswith("/v1/knowledge/coverage")


def test_knowledge_stale_scan_dry_run_default(fake_client):
    fake_client._session.post.return_value.json.return_value = {
        "dryRun": True, "candidateCount": 3, "candidates": []}
    fake_client._session.post.return_value.raise_for_status = MagicMock()
    rc, out, _ = _run_cli(["knowledge", "stale-scan"], fake_client)
    assert rc == 0
    params = fake_client._session.post.call_args.kwargs["params"]
    assert params["dryRun"] == "true"
    assert params["staleAfterDays"] == 180


def test_knowledge_stale_scan_with_commit_flag(fake_client):
    fake_client._session.post.return_value.json.return_value = {"dryRun": False, "candidateCount": 0}
    fake_client._session.post.return_value.raise_for_status = MagicMock()
    rc, _, _ = _run_cli(
        ["knowledge", "stale-scan", "--stale-after-days", "90", "--max-references", "2",
         "--commit"],
        fake_client)
    params = fake_client._session.post.call_args.kwargs["params"]
    assert params["dryRun"] == "false"
    assert params["staleAfterDays"] == 90
    assert params["maxReferences"] == 2


def test_proposal_approve_with_comment(fake_client):
    fake_client.proposals.approve.return_value = {"id": 7, "status": "approved"}
    rc, out, _ = _run_cli(
        ["proposal", "approve", "7", "--comment", "LGTM"],
        fake_client)
    assert rc == 0
    fake_client.proposals.approve.assert_called_with(7, comment="LGTM")


def test_proposal_reject_requires_reason(fake_client):
    rc, _, err = _run_cli(["proposal", "reject", "7"], fake_client)
    assert rc == 2  # argparse error
    assert "reason" in err.lower()


def test_audit_activity(fake_client):
    fake_client.audit.activity.return_value = {"actor": "agent:007", "eventCounts": {}}
    rc, out, _ = _run_cli(
        ["audit", "activity", "agent:007", "--since", "2026-06-21T00:00:00Z"],
        fake_client)
    assert rc == 0
    fake_client.audit.activity.assert_called_with(
        "agent:007", since="2026-06-21T00:00:00Z", until=None)


def test_function_invoke_coerces_input_values(fake_client):
    fake_client.functions.invoke.return_value = 42
    rc, out, _ = _run_cli(
        ["function", "invoke", "risk_score",
         "--input", "amount=1000", "--input", 'tier="gold"'],
        fake_client)
    assert rc == 0
    inputs_arg = fake_client.functions.invoke.call_args.args[1]
    assert inputs_arg == {"amount": 1000, "tier": "gold"}  # number + parsed JSON string


def test_function_invoke_rejects_malformed_input(fake_client):
    # No "=" should fail with rc=2 (handled in CLI, not via exception)
    rc, _, err = _run_cli(
        ["function", "invoke", "risk_score", "--input", "bogus-no-equals"],
        fake_client)
    assert rc == 2
    assert "key=value" in err


def test_text_format(fake_client):
    fake_client.knowledge.search.return_value = [{"fqn": "crm.Customer"}]
    rc, out, _ = _run_cli(
        ["--format", "text", "article", "search", "customer"],
        fake_client)
    assert rc == 0
    # Text format renders key: value pairs
    assert "fqn: crm.Customer" in out


def test_failure_returns_nonzero_and_json_error(fake_client):
    import requests
    fake_client.discover.side_effect = requests.HTTPError("503 Service Unavailable")
    rc, _, err = _run_cli(["type", "list"], fake_client)
    assert rc == 1
    err_data = json.loads(err)
    assert err_data["error"] == "HTTPError"
    assert "503" in err_data["message"]


def test_parser_accepts_global_flags_before_subcommand():
    parser = build_parser()
    # Should not raise
    args = parser.parse_args(
        ["--base-url", "http://x:1234", "--agent-id", "my-agent",
         "--format", "text", "type", "list"])
    assert args.base_url == "http://x:1234"
    assert args.agent_id == "my-agent"
    assert args.format == "text"
    assert args.command == "type"
    assert args.action == "list"