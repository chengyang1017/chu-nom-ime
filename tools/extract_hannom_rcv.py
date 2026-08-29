#!/usr/bin/env python3
"""Extract the Standard Nom table from the sole authorized source."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
import unicodedata
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

import requests
from bs4 import BeautifulSoup, Tag

SOURCE_URL = "https://www.hannom-rcv.org/standard-nom/Lookup-CHNC.html?uiLang=vi"
REQUIRED_HEADERS = ("Âm đọc", "Chữ Hán Nôm", "Thí dụ", "Ghi chú")
CSV_FIELDS = ("source_row", "reading_raw", "nom_raw", "example_raw", "note_raw", "source_url")
ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CSV = ROOT / "app" / "src" / "main" / "assets" / "hannom_rcv_standard_nom.csv"
DEFAULT_METADATA = ROOT / "app" / "src" / "main" / "assets" / "hannom_rcv_metadata.json"
DEFAULT_VERIFIED_HTML = ROOT / "tools" / "hannom_verified_page.html"
STOP_TITLE = "Bảng Các Từ Láy Phổ Biến"


@dataclass(frozen=True)
class Record:
    source_row: int
    reading_raw: str
    nom_raw: str
    example_raw: str
    note_raw: str
    source_url: str = SOURCE_URL

    def csv_row(self) -> dict[str, object]:
        return {
            "source_row": self.source_row,
            "reading_raw": self.reading_raw,
            "nom_raw": self.nom_raw,
            "example_raw": self.example_raw,
            "note_raw": self.note_raw,
            "source_url": self.source_url,
        }


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def header_text(cell: Tag) -> str:
    return " ".join(cell.get_text(" ", strip=True).split())


def cell_text(cell: Tag | None) -> str:
    if cell is None:
        return ""
    # Do not normalize Unicode. Only discard HTML indentation around the cell;
    # embedded text nodes and line breaks remain distinct.
    return cell.get_text(separator="\n", strip=False).strip(" \t\r\n")


def locate_main_table(soup: BeautifulSoup) -> tuple[Tag, int, dict[str, int]]:
    matches: list[tuple[Tag, int, dict[str, int]]] = []
    for table in soup.find_all("table"):
        for row_index, row in enumerate(table.find_all("tr"), start=1):
            cells = row.find_all(["th", "td"], recursive=False)
            labels = [header_text(cell) for cell in cells]
            indices: dict[str, int] = {}
            for required in REQUIRED_HEADERS:
                # The bilingual source prepends a Han-Nom label to each Vietnamese
                # header. Match the exact Vietnamese label as a complete suffix;
                # all four named columns must occur in the same header row.
                exact = [
                    index for index, label in enumerate(labels)
                    if label == required or label.endswith(required)
                ]
                if exact:
                    indices[required] = exact[0]
                    continue
                if required == "Chữ Hán Nôm":
                    compatible = [
                        index for index, label in enumerate(labels)
                        if label == "Chữ Hán Nôm Chuẩn" or label.endswith("Chữ Hán Nôm Chuẩn")
                    ]
                    if compatible:
                        indices[required] = compatible[0]
            if len(indices) == len(REQUIRED_HEADERS):
                matches.append((table, row_index, indices))
    if not matches:
        observed = sorted({header_text(cell) for cell in soup.find_all(["th", "td"]) if header_text(cell)})
        raise ValueError(f"No table contains the required headers {REQUIRED_HEADERS!r}. Observed labels sample: {observed[:30]!r}")
    unique_tables = {id(item[0]) for item in matches}
    if len(unique_tables) != 1:
        raise ValueError(f"Ambiguous source: {len(unique_tables)} tables contain the required header set")
    return matches[0]


def parse_html(html: bytes) -> tuple[list[Record], list[dict[str, object]], int, list[dict[str, object]]]:
    soup = BeautifulSoup(html, "html.parser")
    records: list[Record] = []
    anomalies: list[dict[str, object]] = []
    active = False
    stopped = False
    indices: dict[str, int] | None = None
    source_row = 1
    for node in soup.find_all(True):
        text = header_text(node)
        if active and (node.get("id") == "tulay" or STOP_TITLE in text):
            stopped = True
            break
        if node.name != "tr":
            continue
        cells = node.find_all(["th", "td"], recursive=False)
        labels = [header_text(cell) for cell in cells]
        found: dict[str, int] = {}
        for required in REQUIRED_HEADERS:
            hits = [i for i, label in enumerate(labels) if label == required or label.endswith(required) or
                    (required == REQUIRED_HEADERS[1] and (label == required + " Chuẩn" or label.endswith(required + " Chuẩn")))]
            if hits: found[required] = hits[0]
        if len(found) == len(REQUIRED_HEADERS):
            active = True
            indices = found
            continue
        if not active or indices is None or not cells:
            continue
        source_row += 1
        reasons: list[str] = []
        max_index = max(indices.values())
        if len(cells) <= max_index:
            reasons.append(f"column_count={len(cells)} expected_at_least={max_index + 1}")
        def value(header: str) -> str:
            index = indices[header]
            return cell_text(cells[index] if index < len(cells) else None)
        record = Record(source_row, value(REQUIRED_HEADERS[0]), value(REQUIRED_HEADERS[1]), value(REQUIRED_HEADERS[2]), value(REQUIRED_HEADERS[3]))
        if not record.reading_raw: reasons.append("empty readingRaw")
        if not record.nom_raw: reasons.append("empty nomRaw")
        records.append(record)
        if reasons: anomalies.append({"sourceRow": source_row, "reasons": reasons})
    if not active: raise ValueError(f"No row contains required headers {REQUIRED_HEADERS!r}")
    if not stopped: raise ValueError(f"Stop heading not found: {STOP_TITLE}")
    fingerprints = [(r.reading_raw, r.nom_raw, r.example_raw, r.note_raw) for r in records]
    counts, seen, duplicate_rows = Counter(fingerprints), Counter(), []
    for record, fingerprint in zip(records, fingerprints):
        seen[fingerprint] += 1
        if counts[fingerprint] > 1 and seen[fingerprint] > 1:
            duplicate_rows.append({"sourceRow": record.source_row, "duplicateOccurrence": seen[fingerprint]})
    code_points = [{"sourceRow": r.source_row, "codePoints": [f"U+{ord(c):04X}" for c in r.nom_raw]} for r in records]
    return records, anomalies, len(duplicate_rows), code_points

def write_csv(records: Iterable[Record], output: Path) -> bytes:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS, quoting=csv.QUOTE_MINIMAL, lineterminator="\n")
        writer.writeheader()
        for record in records:
            writer.writerow(record.csv_row())
    return output.read_bytes()


def download(url: str) -> bytes:
    response = requests.get(
        url,
        timeout=(20, 120),
        headers={"User-Agent": "Mozilla/5.0 NomIMEDataExtractor/1.0"},
        allow_redirects=True,
    )
    response.raise_for_status()
    if not response.content:
        raise RuntimeError("Source returned an empty response body")
    return response.content


def extract(url: str, csv_path: Path, metadata_path: Path, html_file: Path | None = None) -> dict[str, object]:
    fetched_at = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    if html_file is None and url == SOURCE_URL and DEFAULT_VERIFIED_HTML.exists():
        html_file = DEFAULT_VERIFIED_HTML
    html = html_file.read_bytes() if html_file is not None else download(url)
    records, anomalies, duplicate_count, code_points = parse_html(html)
    if not records:
        raise RuntimeError("The identified source table contained zero data records")
    if len(records) == 1562 or records[-1].reading_raw.casefold() == "dào":
        raise RuntimeError("Incomplete main table extraction")
    if not any("YẾT" in record.reading_raw.upper() for record in records[-100:]):
        raise RuntimeError("YẾT missing from final region")
    def initial(value: str) -> str:
        folded = value.strip().casefold()
        if folded.startswith(chr(0x111)):
            return chr(0x111)
        return next((c for c in unicodedata.normalize("NFD", folded) if "a" <= c <= "z"), "?")
    initial_counts = dict(sorted(Counter(initial(record.reading_raw) for record in records).items()))
    required_ranges = ["a", chr(0x111), "e", "g", "l", "n", "r", "t", "x", "y"]
    missing_ranges = [key for key in required_ranges if initial_counts.get(key, 0) == 0]
    if missing_ranges:
        raise RuntimeError(f"Missing required initial ranges: {missing_ranges}")
    csv_bytes = write_csv(records, csv_path)
    metadata: dict[str, object] = {
        "sourceUrl": url,
        "fetchedAt": fetched_at,
        "htmlSha256": sha256_bytes(html),
        "csvSha256": sha256_bytes(csv_bytes),
        "extractedRowCount": len(records),
        "invalidRowCount": len(anomalies),
        "duplicateRowCount": duplicate_count,
        "invalidRows": anomalies,
        "initialCounts": initial_counts,
        "nomCodePoints": code_points,
    }
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return metadata


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"): sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=SOURCE_URL)
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV)
    parser.add_argument("--metadata", type=Path, default=DEFAULT_METADATA)
    parser.add_argument("--html-file", type=Path, help="Verified HTML captured from the same interactive browser session")
    args = parser.parse_args()
    try:
        metadata = extract(args.url, args.csv, args.metadata, args.html_file)
    except Exception as error:
        print(f"ERROR: extraction failed; no synthetic data was generated: {error}", file=sys.stderr)
        return 1
    print(json.dumps({key: metadata[key] for key in (
        "sourceUrl", "fetchedAt", "htmlSha256", "csvSha256", "extractedRowCount", "invalidRowCount", "duplicateRowCount", "initialCounts"
    )}, ensure_ascii=False, indent=2))
    records, _, _, _ = parse_html((args.html_file or DEFAULT_VERIFIED_HTML).read_bytes())
    print("FIRST:", records[0])
    print("LAST TEN:")
    for record in records[-10:]: print(record)
    for anomaly in metadata["invalidRows"]:
        print(f"INVALID sourceRow={anomaly['sourceRow']}: {', '.join(anomaly['reasons'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())