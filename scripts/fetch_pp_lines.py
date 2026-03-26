#!/usr/bin/env python3
"""Fetch PrizePicks projections, normalize to parser-ready lines, and optionally upload to S3."""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

import boto3
from curl_cffi import requests

LOG = logging.getLogger("fetch_pp_lines")

DEFAULT_BROWSERS: tuple[str, ...] = (
    "chrome120",
    "chrome116",
    "chrome110",
    "chrome",
    "safari",
    "safari_ios",
    "edge99",
)


@dataclass(frozen=True)
class FetchConfig:
    league_id: str
    per_page: int
    timeout_seconds: int
    single_stat: bool

    @property
    def url(self) -> str:
        single_stat_value = "true" if self.single_stat else "false"
        return (
            "https://api.prizepicks.com/projections"
            f"?league_id={self.league_id}&per_page={self.per_page}&single_stat={single_stat_value}"
        )


@dataclass(frozen=True)
class UploadConfig:
    bucket: str
    region: str
    key_prefix: str
    write_archive_copy: bool


@dataclass(frozen=True)
class NormalizeResult:
    payload: dict[str, Any]
    total_projections: int
    accepted_lines: int
    skipped_unsupported_stat: int
    skipped_missing_player: int
    skipped_missing_line: int


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Fetch PrizePicks projections with curl_cffi impersonation")
    parser.add_argument("--league-id", default=os.getenv("PP_LEAGUE_ID", "7"), help="PrizePicks league id")
    parser.add_argument(
        "--per-page", type=int, default=int(os.getenv("PP_PER_PAGE", "250")), help="Records per API request"
    )
    parser.add_argument(
        "--timeout-seconds",
        type=int,
        default=int(os.getenv("PP_TIMEOUT_SECONDS", "30")),
        help="HTTP timeout in seconds",
    )
    parser.add_argument(
        "--single-stat",
        action=argparse.BooleanOptionalAction,
        default=os.getenv("PP_SINGLE_STAT", "true").lower() == "true",
        help="Filter to single-stat projections",
    )
    parser.add_argument(
        "--output",
        default="",
        help="Optional output path for normalized JSON payload",
    )
    parser.add_argument(
        "--raw-output",
        default="",
        help="Optional output path for raw PrizePicks API payload",
    )
    parser.add_argument(
        "--s3-bucket",
        default=os.getenv("PP_S3_BUCKET", ""),
        help="Optional S3 bucket for upload (if omitted, no upload happens)",
    )
    parser.add_argument(
        "--s3-region",
        default=os.getenv("PP_S3_REGION", "us-east-1"),
        help="S3 region for uploads",
    )
    parser.add_argument(
        "--s3-key-prefix",
        default=os.getenv("PP_S3_KEY_PREFIX", "prizepicks/lines"),
        help="S3 key prefix for canonical output",
    )
    parser.add_argument(
        "--write-archive-copy",
        action=argparse.BooleanOptionalAction,
        default=os.getenv("PP_WRITE_ARCHIVE_COPY", "true").lower() == "true",
        help="Also write timestamped archive copy to S3",
    )
    parser.add_argument(
        "--allow-empty-lines",
        action=argparse.BooleanOptionalAction,
        default=os.getenv("PP_ALLOW_EMPTY_LINES", "false").lower() == "true",
        help="Allow zero normalized lines without non-zero exit",
    )
    parser.add_argument(
        "--log-level",
        default=os.getenv("PP_LOG_LEVEL", "INFO"),
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Logger level",
    )
    return parser.parse_args(argv)


def default_headers() -> dict[str, str]:
    return {
        "Accept": "application/json",
        "Accept-Language": "en-US,en;q=0.9",
        "Connection": "keep-alive",
        "Origin": "https://app.prizepicks.com",
        "Referer": "https://app.prizepicks.com/",
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-site",
    }


def nba_stats_headers() -> dict[str, str]:
    return {
        "Accept": "application/json",
        "Accept-Language": "en-US,en;q=0.9",
        "Connection": "keep-alive",
        "Host": "stats.nba.com",
        "Origin": "https://www.nba.com",
        "Referer": "https://www.nba.com/",
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-site",
        "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
    }


def fetch_payload(config: FetchConfig, browsers: tuple[str, ...]) -> dict[str, Any]:
    headers = default_headers()
    last_error: Exception | None = None
    for browser in browsers:
        LOG.info("Trying browser impersonation: %s", browser)
        try:
            response = requests.get(
                config.url,
                impersonate=browser,
                headers=headers,
                timeout=config.timeout_seconds,
            )
            LOG.info("Status for %s: %s", browser, response.status_code)
            if response.status_code != 200:
                LOG.warning("Non-200 for %s, body prefix: %s", browser, response.text[:250])
                continue
            payload = response.json()
            payload["fetched_at"] = datetime.now(timezone.utc).isoformat()
            payload["fetch_source"] = f"curl_cffi_{browser}"
            return payload
        except requests.RequestsError as exc:
            last_error = exc
            LOG.error("Network error for %s: %s", browser, exc)
        except json.JSONDecodeError as exc:
            last_error = exc
            LOG.error("JSON decode error for %s: %s", browser, exc)
    raise RuntimeError(f"All browser impersonations failed. last_error={last_error!r}")


def stat_type_to_supported(raw: str | None) -> str | None:
    if raw is None:
        return None
    normalized = raw.strip().upper().replace(" ", "_")
    if normalized in ("PTS", "POINTS"):
        return "POINTS"
    if normalized in ("REB", "REBOUNDS", "TOTAL_REBOUNDS"):
        return "REBOUNDS"
    if normalized in ("AST", "ASSISTS"):
        return "ASSISTS"
    return None


def load_nba_player_id_lookup() -> dict[str, int]:
    """Build name->NBA player id map using nba_api static player list."""
    try:
        from nba_api.stats.static import players as nba_players  # type: ignore
    except ImportError as exc:
        LOG.warning("nba_api not installed; falling back to stats.nba.com player index: %s", exc)
        return load_nba_player_id_lookup_from_endpoint()

    lookup: dict[str, int] = {}
    for player in nba_players.get_players():
        full_name = str(player.get("full_name", "")).strip()
        player_id = player.get("id")
        if not full_name or not isinstance(player_id, int):
            continue
        lookup[full_name.lower()] = player_id
    return lookup


def load_nba_player_id_lookup_from_endpoint() -> dict[str, int]:
    """Build name->NBA player id map from stats.nba.com commonallplayers endpoint."""
    url = (
        "https://stats.nba.com/stats/commonallplayers"
        "?IsOnlyCurrentSeason=1&LeagueID=00&Season=2025-26"
    )
    response = requests.get(
        url,
        impersonate="chrome120",
        headers=nba_stats_headers(),
        timeout=30,
    )
    if response.status_code != 200:
        raise RuntimeError(f"NBA player lookup endpoint failed with status {response.status_code}")
    payload = response.json()
    result_sets = payload.get("resultSets")
    if not isinstance(result_sets, list) or not result_sets:
        raise RuntimeError("NBA player lookup response missing resultSets")
    first_set = result_sets[0]
    headers = first_set.get("headers", [])
    rows = first_set.get("rowSet", [])
    if not isinstance(headers, list) or not isinstance(rows, list):
        raise RuntimeError("NBA player lookup response missing headers/rowSet")
    index_player_id = headers.index("PERSON_ID") if "PERSON_ID" in headers else -1
    index_name = headers.index("DISPLAY_FIRST_LAST") if "DISPLAY_FIRST_LAST" in headers else -1
    if index_player_id < 0 or index_name < 0:
        raise RuntimeError("NBA player lookup headers missing PERSON_ID/DISPLAY_FIRST_LAST")

    lookup: dict[str, int] = {}
    for row in rows:
        if not isinstance(row, list):
            continue
        if len(row) <= max(index_player_id, index_name):
            continue
        player_name = row[index_name]
        player_id = row[index_player_id]
        if isinstance(player_name, str) and isinstance(player_id, int):
            lookup[player_name.lower()] = player_id
    if not lookup:
        raise RuntimeError("NBA player lookup returned zero players")
    LOG.info("Loaded %d players from stats.nba.com lookup endpoint", len(lookup))
    return lookup


def normalize_payload(raw_payload: dict[str, Any], league_id: str) -> NormalizeResult:
    player_lookup = load_nba_player_id_lookup()
    included = raw_payload.get("included", [])
    included_players: dict[str, dict[str, Any]] = {}
    if isinstance(included, list):
        for item in included:
            if not isinstance(item, dict):
                continue
            if item.get("type") != "new_player":
                continue
            player_id = item.get("id")
            if isinstance(player_id, str):
                included_players[player_id] = item

    data = raw_payload.get("data", [])
    total = 0
    accepted = 0
    skip_stat = 0
    skip_player = 0
    skip_line = 0
    lines: list[dict[str, Any]] = []
    utc_now = datetime.now(timezone.utc)
    slate_date = utc_now.strftime("%Y-%m-%d")
    for projection in data if isinstance(data, list) else []:
        if not isinstance(projection, dict):
            continue
        total += 1
        attributes = projection.get("attributes", {})
        relationships = projection.get("relationships", {})
        if not isinstance(attributes, dict):
            continue
        if not isinstance(relationships, dict):
            relationships = {}
        stat_type = stat_type_to_supported(attributes.get("stat_type"))
        if stat_type is None:
            skip_stat += 1
            continue

        line_value_raw = attributes.get("line_score")
        if not isinstance(line_value_raw, (int, float)):
            skip_line += 1
            continue
        line_value = float(line_value_raw)

        player_rel = relationships.get("new_player", {})
        player_rel_data = player_rel.get("data", {}) if isinstance(player_rel, dict) else {}
        projection_player_id = player_rel_data.get("id") if isinstance(player_rel_data, dict) else None
        player_obj = included_players.get(projection_player_id) if isinstance(projection_player_id, str) else None
        player_attributes = player_obj.get("attributes", {}) if isinstance(player_obj, dict) else {}
        player_name = player_attributes.get("name") if isinstance(player_attributes, dict) else None
        if not isinstance(player_name, str) or not player_name.strip():
            skip_player += 1
            continue

        nba_player_id = player_lookup.get(player_name.strip().lower())
        if nba_player_id is None:
            skip_player += 1
            continue

        lines.append(
            {
                "playerId": nba_player_id,
                "playerName": player_name.strip(),
                "statType": stat_type,
                "line": line_value,
            }
        )
        accepted += 1

    normalized = {
        "date": slate_date,
        "leagueId": league_id,
        "source": raw_payload.get("fetch_source", "curl_cffi"),
        "fetched_at": raw_payload.get("fetched_at", utc_now.isoformat()),
        "lines": lines,
    }
    return NormalizeResult(
        payload=normalized,
        total_projections=total,
        accepted_lines=accepted,
        skipped_unsupported_stat=skip_stat,
        skipped_missing_player=skip_player,
        skipped_missing_line=skip_line,
    )


def save_payload(payload: dict[str, Any], output_path: str) -> None:
    if not output_path:
        return
    serialized = json.dumps(payload, indent=2)
    with open(output_path, "w", encoding="utf-8") as file:
        file.write(serialized)
    LOG.info("Wrote payload to %s", output_path)


def upload_to_s3(payload: dict[str, Any], config: UploadConfig, date_iso: str, fetched_at: str) -> list[str]:
    s3 = boto3.client("s3", region_name=config.region)
    body = json.dumps(payload).encode("utf-8")
    key_prefix = config.key_prefix.strip("/")
    canonical_key = f"{key_prefix}/{date_iso}.json"
    s3.put_object(
        Bucket=config.bucket,
        Key=canonical_key,
        Body=body,
        ContentType="application/json",
    )
    keys = [canonical_key]

    if config.write_archive_copy:
        compact_ts = fetched_at.replace("-", "").replace(":", "").replace("+00:00", "Z").replace(".", "")
        archive_key = f"{key_prefix}/archive/{date_iso}/pp_{compact_ts}.json"
        s3.put_object(
            Bucket=config.bucket,
            Key=archive_key,
            Body=body,
            ContentType="application/json",
        )
        keys.append(archive_key)
    return keys


def summarize(raw_payload: dict[str, Any], normalized: NormalizeResult) -> str:
    data_count = len(raw_payload.get("data", [])) if isinstance(raw_payload.get("data"), list) else 0
    included_count = len(raw_payload.get("included", [])) if isinstance(raw_payload.get("included"), list) else 0
    source = raw_payload.get("fetch_source", "unknown")
    fetched_at = raw_payload.get("fetched_at", "unknown")
    return (
        f"Fetched data={data_count}, included={included_count}, normalized_lines={normalized.accepted_lines}, "
        f"skipped_stat={normalized.skipped_unsupported_stat}, skipped_player={normalized.skipped_missing_player}, "
        f"skipped_line={normalized.skipped_missing_line}, source={source}, fetched_at={fetched_at}"
    )


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    logging.basicConfig(level=getattr(logging, args.log_level), format="%(levelname)s: %(message)s")
    config = FetchConfig(
        league_id=args.league_id,
        per_page=args.per_page,
        timeout_seconds=args.timeout_seconds,
        single_stat=args.single_stat,
    )
    raw_payload = fetch_payload(config, DEFAULT_BROWSERS)
    normalized = normalize_payload(raw_payload, config.league_id)

    save_payload(raw_payload, args.raw_output)
    save_payload(normalized.payload, args.output)

    if args.s3_bucket:
        fetched_at = str(normalized.payload.get("fetched_at", datetime.now(timezone.utc).isoformat()))
        date_iso = str(normalized.payload["date"])
        upload_config = UploadConfig(
            bucket=args.s3_bucket,
            region=args.s3_region,
            key_prefix=args.s3_key_prefix,
            write_archive_copy=args.write_archive_copy,
        )
        uploaded_keys = upload_to_s3(normalized.payload, upload_config, date_iso, fetched_at)
        LOG.info("Uploaded %d object(s) to s3://%s", len(uploaded_keys), args.s3_bucket)
        for key in uploaded_keys:
            LOG.info("Uploaded key: %s", key)

    print(summarize(raw_payload, normalized))
    if normalized.accepted_lines == 0 and not args.allow_empty_lines:
        LOG.error("No normalized lines generated. Use --allow-empty-lines only for non-slate debug runs.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
