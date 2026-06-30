#!/usr/bin/env python3
"""Offline regression eval for Mem's Search + RAG retrieval core."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from pathlib import Path
from typing import Any


DIMENSIONS = 128
NOW = datetime(2026, 6, 30, tzinfo=timezone.utc)


@dataclass(frozen=True)
class Chunk:
    id: str
    source_id: str
    source_title: str
    source_type: str
    origin_domain: str | None
    author: str | None
    duration_seconds: int | None
    saved_at: int
    chunk_type: str
    text: str
    start_time_ms: int | None = None


@dataclass(frozen=True)
class ParsedQuery:
    phrases: list[str]
    free_terms: list[str]
    soft_terms: list[str]
    negative_terms: list[str]
    types: set[str]
    domains: set[str]
    statuses: set[str]
    required_chunk_types: set[str]
    required_capabilities: set[str]
    duration_ranges: list[range]
    saved_ranges: list[range]
    date_ranges: list[range]

    def has_structured_filters(self) -> bool:
        return any(
            [
                self.types,
                self.domains,
                self.statuses,
                self.required_chunk_types,
                self.required_capabilities,
                self.duration_ranges,
                self.saved_ranges,
                self.date_ranges,
                self.negative_terms,
            ],
        )


@dataclass
class Result:
    chunk: Chunk
    retrieval_mode: str
    score: float
    rank_signals: str


def stable_id(value: str) -> str:
    return sha256(value.encode("utf-8")).hexdigest()[:32]


def tokenize(text: str) -> list[str]:
    stop = {
        "the",
        "and",
        "for",
        "with",
        "that",
        "this",
        "from",
        "into",
        "your",
        "you",
        "are",
        "was",
        "were",
        "have",
        "has",
        "had",
        "not",
        "but",
        "about",
        "what",
        "when",
        "where",
        "how",
        "why",
        "can",
        "will",
    }
    tokens: list[str] = []
    for token in re.split(r"[^a-z0-9]+", text.lower()):
        if len(token) < 3 or token in stop:
            continue
        tokens.append(token)
        stem = simple_stem(token)
        if stem:
            tokens.append(stem)
    return tokens


def simple_stem(token: str) -> str | None:
    if token.endswith("ing") and len(token) > 6:
        return token[:-3]
    if token.endswith("ed") and len(token) > 5:
        return token[:-2]
    if token.endswith("s") and len(token) > 4:
        return token[:-1]
    return None


def local_embedding(text: str) -> list[float]:
    vector = [0.0] * DIMENSIONS
    for token in tokenize(text):
        digest = stable_id(f"embed:{token}")
        bucket = int(digest[:8], 16) % DIMENSIONS
        sign = 1.0 if int(digest[8:10], 16) % 2 == 0 else -1.0
        weight = 1.35 if len(token) > 10 else 1.15 if len(token) > 6 else 1.0
        vector[bucket] += sign * weight
    norm = math.sqrt(sum(value * value for value in vector))
    if norm:
        vector = [value / norm for value in vector]
    return vector


def cosine(left: list[float], right: list[float]) -> float:
    return sum(a * b for a, b in zip(left, right))


def parse_query(query: str) -> ParsedQuery:
    phrases = [match.group(1).strip().lower() for match in re.finditer(r'"([^"]+)"', query) if match.group(1).strip()]
    without_phrases = re.sub(r'"([^"]+)"', " ", query)
    free_terms: list[str] = []
    soft_terms: list[str] = []
    negative_terms: list[str] = []
    types: set[str] = set()
    domains: set[str] = set()
    statuses: set[str] = set()
    required_chunk_types: set[str] = set()
    required_capabilities: set[str] = set()
    duration_ranges: list[range] = []
    saved_ranges: list[range] = []
    date_ranges: list[range] = []

    for raw in re.split(r"\s+", without_phrases.strip()):
        if not raw:
            continue
        token = raw.strip().strip('"')
        if token.startswith("-"):
            if len(token) > 2:
                negative_terms.append(token[1:].lower())
            continue
        if ":" not in token:
            free_terms.append(token.lower())
            continue
        key, value = token.split(":", 1)
        key = key.lower()
        value = value.lower().strip()
        if key == "type":
            types.update(normalize_type_filter(part) for part in re.split(r"[/,]", value))
        elif key in {"site", "domain"}:
            domains.add(value.removeprefix("www."))
        elif key == "status":
            statuses.add(value.replace("-", "_"))
        elif key == "has":
            if value == "transcript":
                required_chunk_types.add("transcript")
            elif value == "visual":
                required_chunk_types.add("visual")
            elif value == "timestamp":
                required_capabilities.add("timestamp")
            elif value == "auth":
                statuses.add("needs_auth")
            else:
                soft_terms.append(value)
        elif key in {"tag", "collection", "author", "channel", "language", "action"}:
            soft_terms.append(value)
        elif key == "duration":
            parsed = parse_duration_range(value)
            if parsed:
                duration_ranges.append(parsed)
        elif key == "saved":
            parsed = parse_date_range(value)
            if parsed:
                saved_ranges.append(parsed)
        elif key == "date":
            parsed = parse_date_range(value)
            if parsed:
                date_ranges.append(parsed)
        else:
            free_terms.append(value)

    return ParsedQuery(
        phrases=phrases,
        free_terms=free_terms,
        soft_terms=soft_terms,
        negative_terms=negative_terms,
        types={item for item in types if item},
        domains={item for item in domains if item},
        statuses={item for item in statuses if item},
        required_chunk_types=required_chunk_types,
        required_capabilities=required_capabilities,
        duration_ranges=duration_ranges,
        saved_ranges=saved_ranges,
        date_ranges=date_ranges,
    )


def normalize_type_filter(value: str) -> str:
    value = value.strip().lower()
    aliases = {
        "doc": "document",
        "docs": "document",
        "videos": "video",
        "articles": "article",
        "notes": "note",
        "images": "image",
    }
    return aliases.get(value, value)


def parse_duration_range(value: str) -> range | None:
    value = value.strip().lower()
    if not value:
        return None
    if value.startswith("<="):
        end = parse_duration_seconds(value[2:])
        return range(0, end + 1) if end is not None else None
    if value.startswith("<"):
        end = parse_duration_seconds(value[1:])
        return range(0, max(end or 0, 0)) if end is not None else None
    if value.startswith(">="):
        start = parse_duration_seconds(value[2:])
        return range(start, sys.maxsize) if start is not None else None
    if value.startswith(">"):
        start = parse_duration_seconds(value[1:])
        return range(start + 1, sys.maxsize) if start is not None else None
    if ".." in value:
        left, right = value.split("..", 1)
        start = parse_duration_seconds(left) if left else 0
        end = parse_duration_seconds(right) if right else sys.maxsize - 1
        if start is None or end is None:
            return None
        return range(min(start, end), max(start, end) + 1)
    seconds = parse_duration_seconds(value)
    return range(seconds, seconds + 1) if seconds is not None else None


def parse_duration_seconds(value: str) -> int | None:
    match = re.fullmatch(r"(\d+)(ms|s|m|h)?", value.strip().lower())
    if not match:
        return None
    amount = int(match.group(1))
    unit = match.group(2) or "s"
    if unit == "ms":
        return amount // 1000
    if unit == "s":
        return amount
    if unit == "m":
        return amount * 60
    if unit == "h":
        return amount * 3600
    return None


def parse_date_range(value: str) -> range | None:
    value = value.strip().lower()
    if value == "today":
        return day_range(NOW)
    if value == "yesterday":
        return day_range(NOW - timedelta(days=1))
    if value.startswith("last") and value.endswith("d"):
        try:
            days = int(value[4:-1])
        except ValueError:
            return None
        start = int((NOW - timedelta(days=max(days, 0))).timestamp() * 1000)
        end = int(NOW.timestamp() * 1000)
        return range(start, end + 1)
    if ".." in value:
        left, right = value.split("..", 1)
        start = parse_date_boundary(left, end=False) if left else 0
        end = parse_date_boundary(right, end=True) if right else sys.maxsize - 1
        if start is None or end is None:
            return None
        return range(min(start, end), max(start, end) + 1)
    start = parse_date_boundary(value, end=False)
    end = parse_date_boundary(value, end=True)
    return range(start, end + 1) if start is not None and end is not None else None


def day_range(value: datetime) -> range:
    start = value.replace(hour=0, minute=0, second=0, microsecond=0)
    end = value.replace(hour=23, minute=59, second=59, microsecond=999000)
    return range(int(start.timestamp() * 1000), int(end.timestamp() * 1000) + 1)


def parse_date_boundary(value: str, end: bool) -> int | None:
    parts = value.split("-")
    try:
        year = int(parts[0])
        month = int(parts[1]) if len(parts) > 1 else (12 if end else 1)
        day = int(parts[2]) if len(parts) > 2 else (month_end_day(year, month) if end else 1)
    except (ValueError, IndexError):
        return None
    if end:
        dt = datetime(year, month, day, 23, 59, 59, 999000, tzinfo=timezone.utc)
    else:
        dt = datetime(year, month, day, 0, 0, 0, 0, tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000)


def month_end_day(year: int, month: int) -> int:
    if month == 12:
        next_month = datetime(year + 1, 1, 1, tzinfo=timezone.utc)
    else:
        next_month = datetime(year, month + 1, 1, tzinfo=timezone.utc)
    return (next_month - timedelta(days=1)).day


def haystack(chunk: Chunk, mode: str = "") -> str:
    return " ".join(
        item
        for item in [
            chunk.source_title,
            chunk.source_type,
            chunk.origin_domain or "",
            chunk.author or "",
            chunk.text,
            chunk.chunk_type,
            mode,
        ]
        if item
    ).lower()


def matches(parsed: ParsedQuery, chunk: Chunk, mode: str = "") -> bool:
    text = haystack(chunk, mode)
    if any(term in text for term in parsed.negative_terms):
        return False
    if parsed.types and chunk.source_type.lower() not in parsed.types:
        return False
    if parsed.domains:
        domain = (chunk.origin_domain or "").lower().removeprefix("www.")
        if not domain or not any(domain == item or domain.endswith(f".{item}") for item in parsed.domains):
            return False
    if parsed.statuses and not any(status in text for status in parsed.statuses):
        return False
    if parsed.required_chunk_types and chunk.chunk_type.lower() not in parsed.required_chunk_types:
        return False
    if "timestamp" in parsed.required_capabilities and chunk.start_time_ms is None:
        return False
    if parsed.duration_ranges:
        if chunk.duration_seconds is None:
            return False
        if not any(chunk.duration_seconds in item for item in parsed.duration_ranges):
            return False
    if parsed.saved_ranges and not any(chunk.saved_at in item for item in parsed.saved_ranges):
        return False
    if parsed.date_ranges and not any(chunk.saved_at in item for item in parsed.date_ranges):
        return False
    return True


def rank_boost(parsed: ParsedQuery, result: Result) -> float:
    text = haystack(result.chunk, result.retrieval_mode)
    boost = 0.0
    boost += sum(0.45 for phrase in parsed.phrases if phrase in text)
    boost += sum(0.12 for term in parsed.free_terms if term in text)
    if result.chunk.chunk_type.lower() in parsed.required_chunk_types:
        boost += 0.35
    if parsed.domains and result.chunk.origin_domain and any(item in result.chunk.origin_domain.lower() for item in parsed.domains):
        boost += 0.25
    if parsed.duration_ranges and result.chunk.duration_seconds is not None:
        boost += 0.18
    if parsed.saved_ranges or parsed.date_ranges:
        boost += 0.12
    if result.chunk.start_time_ms is not None:
        boost += 0.08
    if result.retrieval_mode == "hybrid":
        boost += 0.25
    return boost


def to_fts_terms(parsed: ParsedQuery) -> list[str]:
    terms = parsed.phrases + parsed.free_terms + parsed.soft_terms
    cleaned = []
    for term in terms:
        token = re.sub(r"[^a-z0-9_-]", "", term.lower())
        if len(token) >= 2:
            cleaned.append(token)
    return list(dict.fromkeys(cleaned))


def search(chunks: list[Chunk], query: str) -> list[Result]:
    parsed = parse_query(query)
    fts_terms = to_fts_terms(parsed)
    results: dict[str, Result] = {}
    if fts_terms:
        keyword_candidates = []
        for chunk in chunks:
            text = haystack(chunk)
            matched = sum(1 for term in fts_terms if term in text)
            if matched:
                keyword_candidates.append((matched, chunk))
        keyword_candidates.sort(key=lambda item: (-item[0], -item[1].saved_at))
        for index, (_, chunk) in enumerate(keyword_candidates[:120]):
            result = Result(chunk=chunk, retrieval_mode="keyword", score=1.0 / (index + 1), rank_signals="keyword")
            add_result(results, parsed, result, index)
    elif parsed.has_structured_filters():
        for index, chunk in enumerate(sorted(chunks, key=lambda item: item.saved_at, reverse=True)[:5000]):
            result = Result(chunk=chunk, retrieval_mode="filter", score=1.0 / (index + 1), rank_signals="filter")
            add_result(results, parsed, result, index)

    query_vector = local_embedding(query)
    semantic_candidates = []
    for chunk in chunks:
        score = cosine(query_vector, local_embedding(chunk.text))
        if score >= 0.08:
            semantic_candidates.append((score, chunk))
    semantic_candidates.sort(key=lambda item: item[0], reverse=True)
    for index, (score, chunk) in enumerate(semantic_candidates[:120]):
        result = Result(chunk=chunk, retrieval_mode="semantic", score=score, rank_signals=f"semantic:{score:.2f}")
        add_result(results, parsed, result, index)

    return sorted(results.values(), key=lambda item: item.score, reverse=True)[:40]


def add_result(results: dict[str, Result], parsed: ParsedQuery, result: Result, index: int) -> None:
    if not matches(parsed, result.chunk, result.retrieval_mode):
        return
    source_dupe_penalty = sum(1 for item in results.values() if item.chunk.source_id == result.chunk.source_id) * 0.03
    recency = 1.0 / (1 + index)
    filters = rank_boost(parsed, result)
    score = result.score + recency + filters - source_dupe_penalty
    ranked = Result(
        chunk=result.chunk,
        retrieval_mode=result.retrieval_mode,
        score=score,
        rank_signals=f"mode={result.retrieval_mode}; base={result.score:.2f}; recency={recency:.2f}; filters={filters:.2f}",
    )
    existing = results.get(result.chunk.id)
    if existing is None or ranked.score > existing.score:
        results[result.chunk.id] = ranked
    elif existing.retrieval_mode != ranked.retrieval_mode:
        existing.retrieval_mode = "hybrid"
        existing.score += 0.25
        existing.rank_signals += "; hybrid=true"


def load_fixture(path: Path) -> tuple[list[Chunk], list[dict[str, Any]]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    sources = {source["id"]: source for source in data["sources"]}
    chunks: list[Chunk] = []
    for raw in data["chunks"]:
        source = sources[raw["sourceId"]]
        chunks.append(
            Chunk(
                id=raw["id"],
                source_id=raw["sourceId"],
                source_title=source["title"],
                source_type=source["sourceType"],
                origin_domain=source.get("originDomain"),
                author=source.get("author"),
                duration_seconds=source.get("durationSeconds"),
                saved_at=parse_fixture_time(source["savedAt"]),
                chunk_type=raw["chunkType"],
                text=raw["text"],
                start_time_ms=raw.get("startTimeMs"),
            ),
        )
    return chunks, data["queries"]


def parse_fixture_time(value: str) -> int:
    return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)


def check_expectations(query: dict[str, Any], results: list[Result]) -> list[str]:
    failures: list[str] = []
    expected_top = query.get("expectedTopSourceId")
    if expected_top and (not results or results[0].chunk.source_id != expected_top):
        failures.append(f"top source expected {expected_top}, got {results[0].chunk.source_id if results else 'none'}")
    expected_any = set(query.get("expectedAnySourceIds", []))
    result_sources = {result.chunk.source_id for result in results}
    missing_sources = expected_any - result_sources
    if missing_sources:
        failures.append(f"missing expected source ids: {', '.join(sorted(missing_sources))}")
    for chunk_type in query.get("requiredChunkTypes", []):
        if not any(result.chunk.chunk_type == chunk_type for result in results):
            failures.append(f"missing chunk type {chunk_type}")
    if query.get("requiresTimestamp") and not any(result.chunk.start_time_ms is not None for result in results):
        failures.append("missing timestamped result")
    if query.get("forbidVisualClaims"):
        visual_claim_words = {"falling", "person", "object", "ocr"}
        for result in results:
            text = result.chunk.text.lower()
            negated_detection = "without claiming detected" in text or "not claim detected" in text
            if result.chunk.chunk_type == "visual" and "detected" in text and not negated_detection:
                failures.append(f"visual placeholder overclaims in {result.chunk.id}")
            if result.chunk.chunk_type == "visual" and visual_claim_words.intersection(tokenize(result.chunk.text)):
                failures.append(f"visual placeholder overclaims in {result.chunk.id}")
    hard_type = query.get("allResultsSourceType")
    if hard_type and any(result.chunk.source_type != hard_type for result in results):
        failures.append(f"non-{hard_type} result leaked through hard type filter")
    hard_domain = query.get("allResultsDomain")
    if hard_domain and any((result.chunk.origin_domain or "") != hard_domain for result in results):
        failures.append(f"non-{hard_domain} result leaked through hard domain filter")
    max_duration = query.get("allResultsMaxDurationSeconds")
    if max_duration is not None:
        for result in results:
            if result.chunk.duration_seconds is None or result.chunk.duration_seconds >= max_duration:
                failures.append("duration hard filter leaked a nonmatching result")
                break
    if query.get("allResultsTimestamped") and any(result.chunk.start_time_ms is None for result in results):
        failures.append("timestamp hard filter leaked an untimestamped result")
    if query.get("requiresRankSignals") and any("mode=" not in result.rank_signals for result in results):
        failures.append("rank signals missing mode")
    if len(results) < query.get("minResults", 1):
        failures.append(f"expected at least {query.get('minResults', 1)} results, got {len(results)}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=Path("tools/search_rag_eval_fixture.json"))
    parser.add_argument("--json", action="store_true", help="Emit machine-readable results")
    args = parser.parse_args()

    chunks, queries = load_fixture(args.fixture)
    report = []
    failed = False
    for query in queries:
        results = search(chunks, query["query"])
        failures = check_expectations(query, results)
        failed = failed or bool(failures)
        report.append(
            {
                "name": query["name"],
                "query": query["query"],
                "passed": not failures,
                "failures": failures,
                "topResults": [
                    {
                        "sourceId": result.chunk.source_id,
                        "chunkId": result.chunk.id,
                        "chunkType": result.chunk.chunk_type,
                        "retrievalMode": result.retrieval_mode,
                        "score": round(result.score, 4),
                        "rankSignals": result.rank_signals,
                    }
                    for result in results[:5]
                ],
            },
        )

    if args.json:
        print(json.dumps({"passed": not failed, "queries": report}, indent=2))
    else:
        for item in report:
            state = "PASS" if item["passed"] else "FAIL"
            print(f"{state} {item['name']}: {item['query']}")
            for result in item["topResults"][:3]:
                print(
                    "  "
                    f"{result['sourceId']} / {result['chunkId']} "
                    f"({result['chunkType']}, {result['retrievalMode']}, {result['score']})",
                )
            for failure in item["failures"]:
                print(f"  - {failure}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
