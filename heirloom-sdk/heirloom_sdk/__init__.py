"""Heirloom Python Agent SDK — Phase 1"""

import requests
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any
import logging

logger = logging.getLogger("heirloom")

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

    # === Utilities ===

    def close(self):
        """Close the underlying HTTP session."""
        self._session.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
