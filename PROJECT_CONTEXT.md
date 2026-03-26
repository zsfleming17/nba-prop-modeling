# Project Context

## Purpose
NBA props pipeline on AWS (Java + SAM): ingest NBA stats, ingest PrizePicks lines, compute edges, record outcomes, and expose read APIs.

## Stack Overview
- Runtime: Java 21 Lambdas deployed with AWS SAM.
- Storage:
  - DynamoDB: `PlayerGameStats`, `PropLines`, `EdgeScores`, `Outcomes`
  - S3: raw data and line file ingestion (`prizepicks/lines/YYYY-MM-DD.json`)
- API:
  - `GET /edges?date=YYYY-MM-DD`
  - `GET /player/{id}/history?limit=50`
- Schedules (UTC): ingestion `04:00`, scoring `04:10`, outcomes `04:30`.

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
- This indicates the old ingestion function resource was likely stale/corrupted at the Lambda resource level.
- Remaining issue is now runtime behavior, not classloading:
  - `NbaIngestionFunctionV2` currently times out at 120s under load path.
  - Other functions (API, scoring) invoke successfully.

## Immediate Next Goal
Reduce/diagnose ingestion timeout (likely NBA HTTP path), then verify successful ingestion completion end-to-end.

## Practical Notes
- If `pom.xml` changes, allow IDE classpath sync.
- Prefer function-level recreation over full stack deletion when isolating Lambda resource state issues.
- Use async invoke carefully while debugging (`Event` retries can create overlapping executions/timeouts).
