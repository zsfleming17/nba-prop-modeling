#!/usr/bin/env python3
"""Fetch NBA league game log JSON via nba_api and upload raw API response to S3 for Lambda ingestion."""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from datetime import date, datetime, timedelta, timezone
from typing import Any

import boto3
from nba_api.stats.endpoints import leaguegamelog

LOG = logging.getLogger("fetch_nba_api_stats")


def season_for_date(d: date) -> str:
    """Match Java NbaSeasonUtil.seasonForDate (NBA season starts October)."""
    y, m = d.year, d.month
    start_year = y if m >= 10 else y - 1
    end_short = (start_year + 1) % 100
    return f"{start_year}-{end_short:02d}"


def iso_to_nba_date_param(iso_day: str) -> str:
    """LeagueGameLog expects US-style dates (e.g. 3/25/2026)."""
    d = datetime.strptime(iso_day, "%Y-%m-%d").date()
    return f"{d.month}/{d.day}/{d.year}"


def fetch_raw_json(game_date_iso: str, season: str) -> str:
    date_param = iso_to_nba_date_param(game_date_iso)
    raw = leaguegamelog.LeagueGameLog(
        counter="0",
        direction="DESC",
        player_or_team_abbreviation="P",
        season=season,
        season_type_all_star="Regular Season",
        date_from_nullable=date_param,
        date_to_nullable=date_param,
    )
    return raw.get_json()


def parse_args(argv: list[str]) -> argparse.Namespace:
    utc_today = datetime.now(timezone.utc).date()
    default_game = (utc_today - timedelta(days=1)).isoformat()
    parser = argparse.ArgumentParser(description="Fetch NBA league game log via nba_api and upload to S3")
    parser.add_argument(
        "--game-date",
        default=os.getenv("NBA_GAME_DATE", default_game),
        help="Game date in YYYY-MM-DD (default: yesterday UTC)",
    )
    parser.add_argument("--output", default="", help="Optional local path to write raw JSON")
    parser.add_argument("--s3-bucket", default=os.getenv("NBA_S3_BUCKET", ""), help="S3 bucket (DataBucket)")
    parser.add_argument("--s3-region", default=os.getenv("NBA_S3_REGION", "us-east-1"), help="S3 region")
    parser.add_argument(
        "--s3-key-prefix",
        default=os.getenv("NBA_S3_API_KEY_PREFIX", "nba/api"),
        help="Key prefix; object will be {prefix}/{game-date}.json",
    )
    parser.add_argument("--log-level", default=os.getenv("NBA_LOG_LEVEL", "INFO"), choices=["DEBUG", "INFO", "WARNING", "ERROR"])
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    logging.basicConfig(level=getattr(logging, args.log_level), format="%(levelname)s: %(message)s")
    game_date = args.game_date.strip()
    season = season_for_date(datetime.strptime(game_date, "%Y-%m-%d").date())

    LOG.info("Fetching LeagueGameLog season=%s game_date=%s", season, game_date)
    body = fetch_raw_json(game_date, season)
    root: dict[str, Any] = json.loads(body)
    result_sets = root.get("resultSets") or []
    row_count = 0
    if isinstance(result_sets, list) and result_sets:
        rs0 = result_sets[0]
        if isinstance(rs0, dict):
            row_count = len(rs0.get("rowSet") or [])
    LOG.info("Fetched raw JSON bytes=%s parsed_rows=%s", len(body.encode("utf-8")), row_count)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(body)
        LOG.info("Wrote %s", args.output)

    if args.s3_bucket:
        prefix = args.s3_key_prefix.strip("/")
        key = f"{prefix}/{game_date}.json"
        s3 = boto3.client("s3", region_name=args.s3_region)
        s3.put_object(
            Bucket=args.s3_bucket,
            Key=key,
            Body=body.encode("utf-8"),
            ContentType="application/json",
        )
        LOG.info("Uploaded s3://%s/%s", args.s3_bucket, key)

    print(f"OK game_date={game_date} season={season} rows={row_count}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
