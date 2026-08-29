import csv
import importlib.util
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("extract_hannom_rcv", ROOT / "tools" / "extract_hannom_rcv.py")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

class ExtractorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.html = (ROOT / "tools" / "hannom_verified_page.html").read_bytes()
        cls.records, cls.anomalies, cls.duplicates, cls.code_points = MODULE.parse_html(cls.html)
        cls.csv_path = ROOT / "app" / "src" / "main" / "assets" / "hannom_rcv_standard_nom.csv"
        cls.metadata = json.loads((ROOT / "app" / "src" / "main" / "assets" / "hannom_rcv_metadata.json").read_text(encoding="utf-8"))

    def test_correct_main_table_and_nonzero_result(self):
        table, _, headers = MODULE.locate_main_table(MODULE.BeautifulSoup(self.html, "html.parser"))
        self.assertTrue(table.name == "table")
        self.assertEqual(set(MODULE.REQUIRED_HEADERS), set(headers))
        self.assertGreater(len(self.records), 0)

    def test_csv_count_and_round_trip(self):
        with self.csv_path.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle))
        self.assertEqual(len(self.records), len(rows))
        self.assertEqual(len(rows), self.metadata["extractedRowCount"])
        self.assertEqual(self.records[0].nom_raw, rows[0]["nom_raw"])

if __name__ == "__main__": unittest.main()