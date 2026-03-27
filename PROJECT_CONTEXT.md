# Project Context

## Purpose
NBA props pipeline on AWS (Java + SAM): ingest NBA stats, ingest PrizePicks lines, compute edges, record outcomes, and expose read APIs.

## Stack Overview
- Runtime: Java 21 Lambdas deployed with AWS SAM (explicit `FunctionName: ${AWS::StackName}-<LogicalId>` — no random suffix).
- Storage:
  - DynamoDB: `PlayerGameStats`, `PropLines`, `EdgeScores`, `Outcomes`
  - S3: raw data — `prizepicks/lines/YYYY-MM-DD.json`, **`nba/api/YYYY-MM-DD.json`** (raw `nba_api` / stats.nba league game log JSON), `nba/raw/YYYY-MM-DD.json` (normalized player lines after ingest)
- API:
  - `GET /edges?date=YYYY-MM-DD`
  - `GET /player/{id}/history?limit=50`
- Schedules (UTC): ingestion `04:00`, scoring `04:10`, outcomes `04:30`.
- External automation: GitHub Actions uploads PP lines and NBA league game log JSON to the data bucket (see `scripts/` and `.github/workflows/`).

## Repo Layout (high signal)
- `template.yaml` - SAM resources and wiring.
- `Makefile` - SAM makefile builder targets (`build-<LogicalId>`).
- `common/` - shared models, repos, NBA client utilities.
- `lambda/nba-ingestion/` - NBA ingestion handler.
- `lambda/pp-line-ingestion/`, `lambda/scoring/`, `lambda/outcome-recorder/`, `lambda/api/`.

## Build/Deploy
- Build: `sam build`
- Deploy: `sam deploy --force-upload`
- Maven modules are in root `pom.xml`.

## Current Status (important)
- Class loading issue was resolved by recreating ingestion Lambda as a new resource:
  - `NbaIngestionFunction` -> `NbaIngestionFunctionV2`
  - Matching Makefile target renamed to `build-NbaIngestionFunctionV2`.
- NBA ingestion: **`NbaIngestionFunctionV2` reads `s3://<DataBucket>/nba/api/YYYY-MM-DD.json` first** (populated by GitHub Actions + `scripts/fetch_nba_api_stats.py`). Falls back to direct `stats.nba.com` only when `NBA_INGEST_SOURCE` allows it; Java HTTP uses **bounded connect/read timeouts** to avoid 120s hangs.
- PrizePicks: external fetch via `scripts/fetch_pp_lines.py` + workflow.

## Immediate Next Goal
Verify nightly flow: Actions upload `nba/api/` + PP lines → Lambdas ingest → scoring → API.

## Practical Notes
- If `pom.xml` changes, allow IDE classpath sync.
- Prefer function-level recreation over full stack deletion when isolating Lambda resource state issues.
- Use async invoke carefully while debugging (`Event` retries can create overlapping executions/timeouts).
- Schedule **NBA fetch workflow before 04:00 UTC** so `nba/api/{date}.json` exists before `NbaIngestionFunctionV2` runs.
