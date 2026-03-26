# Progress

## Status
_Last updated: 2025-03-26_

## What's Working
- API and scoring Lambdas invoke successfully
- Class loading issue resolved (NbaIngestionFunctionV2)

## In Progress
- Diagnosing NbaIngestionFunctionV2 timeout (120s) — likely NBA HTTP path

## Up Next
- Verify successful ingestion end-to-end after timeout fix
- PpLineIngestionFunction testing
- Scoring validation against real lines

## Known Issues / Decisions
- Old NbaIngestionFunction resource was stale — prefer function-level recreation
  over full stack deletion when isolating Lambda resource state issues
- Async invoke (`Event` type) causes overlapping executions during debugging —
  use `RequestResponse` while debugging