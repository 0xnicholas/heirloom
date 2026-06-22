"""Heirloom MCP Server — exposes Heirloom platform as MCP tools for AI agents."""

import json
import os
from mcp.server import Server, NotificationOptions
from mcp.server.models import InitializationCapabilities
from mcp.server.stdio import stdio_server
from heirloom_sdk import HeirloomClient

server = Server("heirloom")
client = HeirloomClient(
    base_url=os.environ.get("HEIRLOOM_URL", "http://localhost:8080"),
    agent_id=os.environ.get("HEIRLOOM_AGENT_ID", "mcp-agent"),
    agent_role=os.environ.get("HEIRLOOM_AGENT_ROLE", "default"),
)


@server.list_tools()
async def list_tools():
    return [
        {
            "name": "discover_resources",
            "description": "List all available ResourceTypes the agent can query",
            "inputSchema": {"type": "object", "properties": {}},
        },
        {
            "name": "query_data",
            "description": "Execute a semantic query against Heirloom. Returns data with metadata context (freshness, null rates, lineage).",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "resource_type": {"type": "string", "description": "ResourceType name, e.g. Customer"},
                    "fields": {"type": "array", "items": {"type": "string"}, "description": "Fields to return"},
                    "filter_field": {"type": "string", "description": "Field to filter on"},
                    "filter_op": {"type": "string", "description": "$eq, $gt, $lt, $like, etc."},
                    "filter_value": {"type": "string", "description": "Filter value"},
                    "limit": {"type": "integer", "description": "Max results"},
                },
            },
        },
        {
            "name": "get_type_definition",
            "description": "Get the full definition of a ResourceType including abilities, state machine, and relationships",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "type_name": {"type": "string", "description": "ResourceType name"},
                },
                "required": ["type_name"],
            },
        },
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict):
    if name == "discover_resources":
        types = client.discover()
        return [{"type": "text", "text": json.dumps(types, indent=2)}]

    elif name == "query_data":
        query = {
            "type": arguments["resource_type"],
            "fields": arguments.get("fields", ["name"]),
        }
        if "filter_field" in arguments:
            query["filter"] = {
                "field": arguments["filter_field"],
                "op": arguments.get("filter_op", "$eq"),
                "value": arguments["filter_value"],
            }
        if "limit" in arguments:
            query["limit"] = arguments["limit"]

        result = client.query(query)
        return [{"type": "text", "text": json.dumps(result, indent=2)}]

    elif name == "get_type_definition":
        type_def = client.get_type(arguments["type_name"])
        return [{"type": "text", "text": json.dumps(type_def, indent=2)}]

    return [{"type": "text", "text": f"Unknown tool: {name}"}]


async def main():
    async with stdio_server() as (read_stream, write_stream):
        await server.run(
            read_stream,
            write_stream,
            InitializationCapabilities(
                sampling=None,
                experimental=None,
                roots=None,
            ),
            NotificationOptions(),
        )


if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
