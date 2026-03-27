# Progress

## Status
_Last updated: 2026-03-27_

## What's Working
- API and scoring Lambdas invoke successfully
- SAM template sets explicit Lambda `FunctionName` (`${StackName}-<LogicalId>`) for stable CLI/invoke names
- Class loading issue resolved (NbaIngestionFunctionV2)
- PrizePicks external fetch via `scripts/fetch_pp_lines.py` + workflow
- NBA league game log fetch validated locally with `nba_api` (fast vs stalled `curl` to stats.nba.com)
- **NBA ingest path:** Lambda prefers S3 `nba/api/YYYY-MM-DD.json`; HTTP fallback uses short timeouts; `scripts/fetch_nba_api_stats.py` + `fetch-nba-stats` workflow upload raw JSON

## In Progress
- End-to-end verify after deploy: Actions → S3 → `NbaIngestionFunctionV2` → DynamoDB under real schedule
- PpLineIngestionFunction end-to-end from S3 trigger through DynamoDB writes
- Scoring validation against real lines

## Up Next
- Tune `NBA_INGEST_SOURCE` in prod (`S3` only) once S3 path is proven stable
- Scope down S3 IAM policies from managed read-all where practical

## Known Issues / Decisions
- Old NbaIngestionFunction resource was stale — prefer function-level recreation
  over full stack deletion when isolating Lambda resource state issues
- Async invoke (`Event` type) causes overlapping executions during debugging —
  use `RequestResponse` while debugging
- PP API payload does not expose NBA player IDs directly; name-to-ID resolution is
  required during normalization before line ingestion
- `stats.nba.com` from Lambda often stalled; primary ingest source is now **S3** filled by GitHub Actions + `nba_api`
