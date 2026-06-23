"""Heirloom CLI — terminal interface to the Heirloom HTTP API.

Built on top of the Heirloom Python SDK. Subcommands cover the same
operations the SDK namespaces expose: type, article, knowledge, proposal,
audit, function.

Output is JSON by default for piping into jq / grep. Use ``--format text``
for a human-readable summary.

Environment variables (override CLI flags):
    HEIRLOOM_BASE_URL   default http://localhost:8080
    HEIRLOOM_AGENT_ID   sent as X-Agent-Id header (used for audit trail)
    HEIRLOOM_AGENT_ROLE sent as X-Agent-Role header (Perspective / Knowledge)
"""

import argparse
import json
import os
import sys

from heirloom_sdk import HeirloomClient


def _make_client(args: argparse.Namespace) -> HeirloomClient:
    base_url = args.base_url or os.environ.get("HEIRLOOM_BASE_URL", "http://localhost:8080")
    agent_id = args.agent_id or os.environ.get("HEIRLOOM_AGENT_ID", "cli")
    agent_role = args.agent_role or os.environ.get("HEIRLOOM_AGENT_ROLE", "admin")
    return HeirloomClient(base_url=base_url, agent_id=agent_id, agent_role=agent_role)


def _print(data, fmt: str) -> None:
    if fmt == "json":
        print(json.dumps(data, indent=2, default=str))
    else:
        # Text format: nested dicts get a brief summary per line.
        if isinstance(data, list):
            for item in data:
                _print(item, fmt)
            return
        if isinstance(data, dict):
            for k, v in data.items():
                if isinstance(v, (dict, list)):
                    print(f"{k}:")
                    _print(v, fmt)
                else:
                    print(f"{k}: {v}")
            return
        print(data)


# === Subcommand handlers ===
# Each handler receives (client, args) and returns 0 on success / non-zero on error.

def cmd_type_list(client, args):
    _print(client.discover(), args.format)
    return 0


def cmd_type_get(client, args):
    _print(client.get_type(args.name), args.format)
    return 0


def cmd_type_create(client, args):
    definition = {"name": args.name}
    if args.description:
        definition["description"] = args.description
    if args.domain:
        definition["domain"] = args.domain
    _print(client.create_resource_type(definition), args.format)
    return 0


def cmd_article_list(client, args):
    _print(client.knowledge.list(), args.format)
    return 0


def cmd_article_get(client, args):
    _print(client.knowledge.get(args.fqn), args.format)
    return 0


def cmd_article_search(client, args):
    _print(client.knowledge.search(args.query, limit=args.limit), args.format)
    return 0


def cmd_knowledge_coverage(client, args):
    # The SDK doesn't expose coverage directly; use the REST endpoint via
    # the SDK's raw session.
    import requests
    r = client._session.get(f"{client.base_url}/v1/knowledge/coverage")
    r.raise_for_status()
    _print(r.json(), args.format)
    return 0


def cmd_knowledge_stale_scan(client, args):
    import requests
    r = client._session.post(
        f"{client.base_url}/v1/knowledge/stale-articles/scan",
        params={
            "staleAfterDays": args.stale_after_days,
            "maxReferences": args.max_references,
            "dryRun": str(args.dry_run).lower(),
        },
    )
    r.raise_for_status()
    _print(r.json(), args.format)
    return 0


def cmd_proposal_list(client, args):
    _print(client.proposals.list(status=args.status), args.format)
    return 0


def cmd_proposal_approve(client, args):
    _print(client.proposals.approve(args.id, comment=args.comment), args.format)
    return 0


def cmd_proposal_reject(client, args):
    _print(client.proposals.reject(args.id, reason=args.reason), args.format)
    return 0


def cmd_audit_activity(client, args):
    _print(client.audit.activity(args.actor, since=args.since, until=args.until), args.format)
    return 0


def cmd_audit_replay(client, args):
    _print(client.audit.replay(args.actor, since=args.since, until=args.until), args.format)
    return 0


def cmd_function_list(client, args):
    _print(client.functions.list(), args.format)
    return 0


def cmd_function_invoke(client, args):
    inputs = {}
    for kv in args.input or []:
        if "=" not in kv:
            print(f"error: --input must be key=value, got: {kv}", file=sys.stderr)
            return 2
        k, v = kv.split("=", 1)
        # Lightweight coercion: numbers / booleans / JSON.
        try:
            inputs[k] = json.loads(v)
        except json.JSONDecodeError:
            inputs[k] = v
    _print(client.functions.invoke(args.name, inputs), args.format)
    return 0


# === Top-level parser ===

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="heirloom",
        description="Heirloom CLI — terminal interface to the Heirloom API.",
    )
    p.add_argument("--base-url", help="Heirloom base URL (default: $HEIRLOOM_BASE_URL or http://localhost:8080)")
    p.add_argument("--agent-id", help="X-Agent-Id header (default: $HEIRLOOM_AGENT_ID or 'cli')")
    p.add_argument("--agent-role", help="X-Agent-Role header (default: $HEIRLOOM_AGENT_ROLE or 'admin')")
    p.add_argument("--format", choices=["json", "text"], default="json",
                   help="Output format (default: json)")

    sub = p.add_subparsers(dest="command", required=True, metavar="COMMAND")

    # type
    t = sub.add_parser("type", help="Resource Type operations")
    t_sub = t.add_subparsers(dest="action", required=True)
    t_sub.add_parser("list", help="List all resource types")
    t_get = t_sub.add_parser("get", help="Get a resource type")
    t_get.add_argument("name")
    t_create = t_sub.add_parser("create", help="Create a resource type")
    t_create.add_argument("--name", required=True)
    t_create.add_argument("--description")
    t_create.add_argument("--domain")

    # article
    a = sub.add_parser("article", help="Knowledge Article operations")
    a_sub = a.add_subparsers(dest="action", required=True)
    a_sub.add_parser("list", help="List articles")
    a_get = a_sub.add_parser("get", help="Get article by FQN")
    a_get.add_argument("fqn")
    a_search = a_sub.add_parser("search", help="Full-text search")
    a_search.add_argument("query")
    a_search.add_argument("--limit", type=int, default=10)

    # knowledge
    k = sub.add_parser("knowledge", help="Knowledge Base aggregate views")
    k_sub = k.add_subparsers(dest="action", required=True)
    k_sub.add_parser("coverage", help="Coverage snapshot")
    k_stale = k_sub.add_parser("stale-scan", help="Scan for stale articles")
    k_stale.add_argument("--stale-after-days", type=int, default=180)
    k_stale.add_argument("--max-references", type=int, default=1)
    k_stale.add_argument("--dry-run", dest="dry_run", action="store_true", default=True)
    k_stale.add_argument("--commit", dest="dry_run", action="store_false")

    # proposal
    pr = sub.add_parser("proposal", help="Governance proposal operations")
    pr_sub = pr.add_subparsers(dest="action", required=True)
    pr_list = pr_sub.add_parser("list", help="List proposals")
    pr_list.add_argument("--status", choices=["pending", "approved", "rejected"])
    pr_approve = pr_sub.add_parser("approve", help="Approve a proposal")
    pr_approve.add_argument("id", type=int)
    pr_approve.add_argument("--comment")
    pr_reject = pr_sub.add_parser("reject", help="Reject a proposal")
    pr_reject.add_argument("id", type=int)
    pr_reject.add_argument("--reason", required=True)

    # audit
    au = sub.add_parser("audit", help="Event log audit views")
    au_sub = au.add_subparsers(dest="action", required=True)
    au_act = au_sub.add_parser("activity", help="Event counts per actor")
    au_act.add_argument("actor")
    au_act.add_argument("--since")
    au_act.add_argument("--until")
    au_replay = au_sub.add_parser("replay", help="Chronological event list")
    au_replay.add_argument("actor")
    au_replay.add_argument("--since")
    au_replay.add_argument("--until")

    # function
    fn = sub.add_parser("function", help="Function engine operations")
    fn_sub = fn.add_subparsers(dest="action", required=True)
    fn_sub.add_parser("list", help="List registered Functions")
    fn_invoke = fn_sub.add_parser("invoke", help="Invoke a Function")
    fn_invoke.add_argument("name")
    fn_invoke.add_argument("--input", action="append", help="key=value (repeatable)")

    return p


def main(argv=None) -> int:
    parser = build_parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as e:
        # argparse raises SystemExit on --help (code 0) or on argument errors
        # (code 2). Convert to a plain return so callers can treat main()
        # as an ordinary function returning an exit code.
        return e.code if isinstance(e.code, int) else (0 if e.code is None else 1)
    client = _make_client(args)

    # Dispatch table — keeps the CLI handlers independent of argparse details.
    dispatch = {
        ("type", "list"): cmd_type_list,
        ("type", "get"): cmd_type_get,
        ("type", "create"): cmd_type_create,
        ("article", "list"): cmd_article_list,
        ("article", "get"): cmd_article_get,
        ("article", "search"): cmd_article_search,
        ("knowledge", "coverage"): cmd_knowledge_coverage,
        ("knowledge", "stale-scan"): cmd_knowledge_stale_scan,
        ("proposal", "list"): cmd_proposal_list,
        ("proposal", "approve"): cmd_proposal_approve,
        ("proposal", "reject"): cmd_proposal_reject,
        ("audit", "activity"): cmd_audit_activity,
        ("audit", "replay"): cmd_audit_replay,
        ("function", "list"): cmd_function_list,
        ("function", "invoke"): cmd_function_invoke,
    }
    handler = dispatch.get((args.command, args.action))
    if handler is None:
        print(f"error: unknown command/action: {args.command} {args.action}", file=sys.stderr)
        return 2
    try:
        return handler(client, args)
    except Exception as e:
        # Surface a compact error and a non-zero exit code so CI / scripts
        # can detect failures without parsing stack traces.
        err = {"error": type(e).__name__, "message": str(e)}
        print(json.dumps(err, indent=2), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())