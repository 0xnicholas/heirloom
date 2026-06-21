"""Heirloom Python Agent SDK — Phase 0"""

import requests
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any

@dataclass
class HeirloomClient:
    base_url: str
    agent_id: str = "anonymous"
    agent_role: str = "default"

    def discover(self) -> List[Dict[str, Any]]:
        """List available ResourceTypes (Role-filtered by Perspective Engine)."""
        r = requests.get(f"{self.base_url}/v1/resourceTypes",
                        headers={"X-Agent-Id": self.agent_id})
        r.raise_for_status()
        return r.json()

    def query(self, json_dsl: Dict[str, Any]) -> Dict[str, Any]:
        """Execute a semantic query. Returns data + metadata context."""
        r = requests.post(f"{self.base_url}/v1/query",
                         json=json_dsl,
                         headers={"X-Agent-Id": self.agent_id})
        r.raise_for_status()
        return r.json()
