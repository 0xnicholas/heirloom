"""Tests for Heirloom Python SDK"""

import pytest
from heirloom_sdk import HeirloomClient
from unittest.mock import Mock, patch

class TestHeirloomClient:
    def test_discover(self):
        client = HeirloomClient("http://localhost:8080")
        mock_response = Mock()
        mock_response.json.return_value = [{"name": "Customer", "fullyQualifiedName": "crm.Customer"}]
        mock_response.raise_for_status = Mock()
        client._session.get = Mock(return_value=mock_response)

        result = client.discover()
        assert len(result) == 1
        assert result[0]["name"] == "Customer"
        client._session.get.assert_called_with("http://localhost:8080/v1/resourceTypes")

    def test_query(self):
        client = HeirloomClient("http://localhost:8080")
        mock_response = Mock()
        mock_response.json.return_value = {"data": [{"name": "Acme"}], "context": {}}
        mock_response.raise_for_status = Mock()
        client._session.post = Mock(return_value=mock_response)

        result = client.query({"type": "Customer", "fields": ["name"]})
        assert result["data"][0]["name"] == "Acme"

    def test_run_discovery(self):
        client = HeirloomClient("http://localhost:8080")
        mock_response = Mock()
        mock_response.json.return_value = {"status": "SUCCESS", "tablesScanned": 5}
        mock_response.raise_for_status = Mock()
        client._session.post = Mock(return_value=mock_response)

        result = client.run_discovery("prod.postgres-analytics")
        assert result["status"] == "SUCCESS"
        client._session.post.assert_called_with(
            "http://localhost:8080/v1/discovery/sources/prod.postgres-analytics/run")
