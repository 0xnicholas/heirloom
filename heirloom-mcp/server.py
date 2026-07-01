"""Heirloom MCP Server — exposes Heirloom API as MCP tools for AI agents.

Tools:
  - heirloom_query: Execute semantic queries
  - heirloom_search: Hybrid knowledge search
  - heirloom_discover: List available resource types
  - heirloom_knowledge_list: List knowledge articles
  - heirloom_knowledge_get: Get a knowledge article
  - heirloom_resources_create: Create a resource instance
  - heirloom_resources_get: Get a resource by RID
  - heirloom_actions_execute: Execute an Action through the nine-step pipeline

Usage:
  python server.py --base-url http://localhost:8080
"""

import argparse
import json
import os
import sys
from typing import Any

# Try heirloom-sdk first, fall back to local client
try:
    from heirloom_sdk import HeirloomClient
except ImportError:
    # Fallback: inline minimal client (keeps MCP server self-contained)
    import requests

    class HeirloomClient:
        def __init__(self, base_url: str, agent_id: str = "mcp-agent", agent_role: str = "default"):
            self.base_url = base_url
            self.session = requests.Session()
            self.session.headers.update({
                "X-Agent-Id": agent_id,
                "X-Agent-Role": agent_role,
                "Content-Type": "application/json",
            })
            self.knowledge = _KnowledgeNS(self)
            self.resources = _ResourcesNS(self)
            self.actions = _ActionsNS(self)

        def query(self, dsl: dict) -> Any:
            r = self.session.post(f"{self.base_url}/v1/query", json=dsl)
            r.raise_for_status()
            return r.json()

        def discover(self) -> Any:
            r = self.session.get(f"{self.base_url}/v1/resourceTypes")
            r.raise_for_status()
            return r.json()

        def search_semantic(self, query: str, limit: int = 10) -> Any:
            r = self.session.get(f"{self.base_url}/v1/search", params={"q": query, "limit": limit})
            r.raise_for_status()
            return r.json()

    class _KnowledgeNS:
        def __init__(self, client):
            self._c = client
        def list(self) -> Any:
            r = self._c.session.get(f"{self._c.base_url}/v1/knowledge")
            r.raise_for_status(); return r.json()
        def get(self, fqn: str) -> Any:
            r = self._c.session.get(f"{self._c.base_url}/v1/knowledge/name/{fqn}")
            r.raise_for_status(); return r.json()
        def search(self, q: str, limit: int = 10) -> Any:
            r = self._c.session.get(f"{self._c.base_url}/v1/knowledge/search", params={"q": q, "limit": limit})
            r.raise_for_status(); return r.json()

    class _ResourcesNS:
        def __init__(self, client):
            self._c = client
        def create(self, resource_type: str, fields: dict, owner: str = "agent") -> Any:
            r = self._c.session.post(f"{self._c.base_url}/v1/resources",
                json={"resourceType": resource_type, "owner": owner, "fields": fields})
            r.raise_for_status(); return r.json()
        def get(self, rid: str) -> Any:
            r = self._c.session.get(f"{self._c.base_url}/v1/resources/{rid}")
            r.raise_for_status(); return r.json()
        def list(self, type: str = None, state: str = None, limit: int = 20) -> Any:
            params = {"limit": limit}
            if type: params["type"] = type
            if state: params["state"] = state
            r = self._c.session.get(f"{self._c.base_url}/v1/resources", params=params)
            r.raise_for_status(); return r.json()
        def update(self, rid: str, fields: dict, expected_version: int = 0) -> Any:
            r = self._c.session.put(f"{self._c.base_url}/v1/resources/{rid}",
                json={"fields": fields}, headers={"If-Match": str(expected_version)})
            r.raise_for_status(); return r.json()
        def transition_state(self, rid: str, target_state: str) -> Any:
            r = self._c.session.patch(f"{self._c.base_url}/v1/resources/{rid}/state",
                json={"targetState": target_state})
            r.raise_for_status(); return r.json()

    class _ActionsNS:
        def __init__(self, client):
            self._c = client
        def execute(self, action_name: str, target_resource_id: str, params: dict = None) -> Any:
            r = self._c.session.post(f"{self._c.base_url}/v1/actions/{action_name}/execute",
                json={"targetResourceId": target_resource_id, "params": params or {}})
            r.raise_for_status(); return r.json()


def main():
    parser = argparse.ArgumentParser(description="Heirloom MCP Server")
    parser.add_argument("--base-url", default=os.environ.get("HEIRLOOM_URL", "http://localhost:8080"))
    parser.add_argument("--agent-id", default=os.environ.get("HEIRLOOM_AGENT_ID", "mcp-agent"))
    parser.add_argument("--agent-role", default=os.environ.get("HEIRLOOM_AGENT_ROLE", "default"))
    args = parser.parse_args()

    client = HeirloomClient(args.base_url, agent_id=args.agent_id, agent_role=args.agent_role)

    # MCP stdio protocol — read JSON-RPC requests from stdin, write responses to stdout
    for line in sys.stdin:
        try:
            request = json.loads(line)
            response = handle_request(request, client)
            sys.stdout.write(json.dumps(response) + "\n")
            sys.stdout.flush()
        except Exception as e:
            err = {
                "jsonrpc": "2.0",
                "id": request.get("id") if 'request' in dir() else None,
                "error": {"code": -32603, "message": str(e)},
            }
            sys.stdout.write(json.dumps(err) + "\n")
            sys.stdout.flush()


def handle_request(request: dict, client: HeirloomClient) -> dict:
    method = request.get("method", "")
    req_id = request.get("id")

    if method == "tools/list":
        return {
            "jsonrpc": "2.0", "id": req_id,
            "result": {"tools": [
                {
                    "name": "heirloom_query",
                    "description": "Execute a semantic query against Heirloom. Provide type, fields, and optional filter/traverse/aggregate.",
                    "inputSchema": {"type": "object", "properties": {"dsl": {"type": "object"}}, "required": ["dsl"]},
                },
                {
                    "name": "heirloom_search",
                    "description": "Hybrid (full-text + vector) search across the knowledge base.",
                    "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}, "limit": {"type": "integer"}}, "required": ["query"]},
                },
                {
                    "name": "heirloom_discover",
                    "description": "List available ResourceTypes in the Schema Registry.",
                    "inputSchema": {"type": "object", "properties": {}},
                },
                {
                    "name": "heirloom_knowledge_list",
                    "description": "List all indexed KnowledgeArticles.",
                    "inputSchema": {"type": "object", "properties": {}},
                },
                {
                    "name": "heirloom_knowledge_get",
                    "description": "Get a KnowledgeArticle by fully-qualified name.",
                    "inputSchema": {"type": "object", "properties": {"fqn": {"type": "string"}}, "required": ["fqn"]},
                },
                {
                    "name": "heirloom_resources_create",
                    "description": "Create a new Resource instance. Returns the generated RID.",
                    "inputSchema": {"type": "object", "properties": {
                        "resourceType": {"type": "string"},
                        "fields": {"type": "object"},
                        "owner": {"type": "string"},
                    }, "required": ["resourceType", "fields"]},
                },
                {
                    "name": "heirloom_resources_get",
                    "description": "Get a Resource instance by RID.",
                    "inputSchema": {"type": "object", "properties": {"rid": {"type": "string"}}, "required": ["rid"]},
                },
                {
                    "name": "heirloom_actions_execute",
                    "description": "Execute an Action through the nine-step pipeline. Returns step-by-step results.",
                    "inputSchema": {"type": "object", "properties": {
                        "actionName": {"type": "string"},
                        "targetResourceId": {"type": "string"},
                        "params": {"type": "object"},
                    }, "required": ["actionName", "targetResourceId"]},
                },
            ]},
        }

    if method == "tools/call":
        tool_name = request["params"]["name"]
        args = request["params"].get("arguments", {})

        result = None
        if tool_name == "heirloom_query":
            result = client.query(args["dsl"])
        elif tool_name == "heirloom_search":
            result = client.search_semantic(args["query"], args.get("limit", 10))
        elif tool_name == "heirloom_discover":
            result = client.discover()
        elif tool_name == "heirloom_knowledge_list":
            result = client.knowledge.list()
        elif tool_name == "heirloom_knowledge_get":
            result = client.knowledge.get(args["fqn"])
        elif tool_name == "heirloom_resources_create":
            result = client.resources.create(
                args["resourceType"], args["fields"], args.get("owner", "agent"))
        elif tool_name == "heirloom_resources_get":
            result = client.resources.get(args["rid"])
        elif tool_name == "heirloom_actions_execute":
            result = client.actions.execute(
                args["actionName"], args["targetResourceId"], args.get("params"))
        else:
            return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32601, "message": f"Unknown tool: {tool_name}"}}

        return {
            "jsonrpc": "2.0", "id": req_id,
            "result": {"content": [{"type": "text", "text": json.dumps(result, indent=2, default=str)}]},
        }

    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32601, "message": f"Unknown method: {method}"}}


if __name__ == "__main__":
    main()
