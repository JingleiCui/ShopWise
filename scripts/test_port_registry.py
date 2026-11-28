import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import port_registry


class PortReservationsTest(unittest.TestCase):
    def test_shopwise_cannot_claim_scholarship_defaults_even_offline(self):
        for name, port in (("web", 15173), ("api", 18081)):
            with self.subTest(port=port), self.assertRaisesRegex(ValueError, "ScholarShip/"):
                port_registry.check_reservations({name: port}, "ShopWise")

    def test_defaults_match_registry(self):
        port_registry.check_reservations(
            {"web": 20173, "api": 20174, "agent": 18082, "db": 15439}, "ShopWise")

    def test_duplicates_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ports.json"
            binding = {"services": {"web": {"port": 20173}}}
            path.write_text(json.dumps({"first": binding, "second": binding}), encoding="utf-8")
            with patch.object(port_registry, "REGISTRY", path):
                with self.assertRaisesRegex(ValueError, "first/web、second/web"):
                    port_registry.assignments()
