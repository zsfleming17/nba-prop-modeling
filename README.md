# Sports Prop Modeling (NBA V1)

Java 21 + AWS SAM: nightly NBA stats ingestion, PrizePicks lines from S3, DynamoDB storage, edge scoring (points / rebounds / assists), outcome recording, and an HTTP API.

## Prerequisites

- JDK 21
- [AWS SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html)
- AWS credentials for `sam deploy`

## Build and test

```bash
./mvnw -B verify
sam validate --lint
sam build
```

## Deploy

```bash
sam deploy --guided
```

- **BillingMode** parameter: `PAY_PER_REQUEST` (default) or `PROVISIONED` (1 RCU / 1 WCU per table and GSI when set).

Stack outputs include the **HTTP API base URL** and **data bucket name**.

### Deployed Lambda names (stable)

Each function sets `FunctionName: ${AWS::StackName}-<LogicalId>` in `template.yaml` (no random suffix). With the default stack name in `samconfig.toml` (`nba-prop-modeling`), function names are:

| Logical ID | AWS function name |
|------------|-------------------|
| `NbaIngestionFunctionV2` | `nba-prop-modeling-NbaIngestionFunctionV2` |
| `PpLineIngestionFunction` | `nba-prop-modeling-PpLineIngestionFunction` |
| `ScoringFunction` | `nba-prop-modeling-ScoringFunction` |
| `OutcomeRecorderFunction` | `nba-prop-modeling-OutcomeRecorderFunction` |
| `ApiFunction` | `nba-prop-modeling-ApiFunction` |

If you change `stack_name` in `samconfig.toml`, replace the prefix accordingly. **Deploy note:** adding explicit names may replace existing Lambdas in the first changeset after this template change — review before confirming.

Example invoke:

```bash
aws lambda invoke --region us-east-1 --function-name nba-prop-modeling-NbaIngestionFunctionV2 \
  --cli-binary-format raw-in-base64-out \
  --payload '{"detail":{"gameDate":"2026-03-25"}}' /tmp/out.json
```

## PrizePicks lines (S3)

External automation should write:

`s3://<DataBucket>/prizepicks/lines/YYYY-MM-DD.json`

Example payload:

```json
{
  "date": "2025-03-20",
  "lines": [
    { "playerId": 2544, "playerName": "LeBron James", "statType": "POINTS", "line": 24.5 }
  ]
}
```

Supported `statType` values resolve to **POINTS**, **REBOUNDS**, or **ASSISTS** (aliases include `PTS`, `REB`, `AST`).

`PpLineIngestionFunction` uses **AmazonS3ReadOnlyAccess** so the template avoids a CloudFormation cycle between the bucket notification and the Lambda role. Scope down to your bucket in a follow-up if you want least privilege.

### PrizePicks fetch helper (GitHub Actions / local)

Use `scripts/fetch_pp_lines.py` to fetch projections from PrizePicks with browser impersonation fallback, normalize to the parser contract, and optionally upload to S3.

Install dependencies:

```bash
pip install curl_cffi boto3
```

Local smoke test (fetch + normalize only):

```bash
python3 scripts/fetch_pp_lines.py --league-id 7 --per-page 50 --timeout-seconds 30 --log-level INFO --allow-empty-lines
```

Write normalized output locally:

```bash
python3 scripts/fetch_pp_lines.py --league-id 7 --output /tmp/pp_lines.json
```

Upload canonical daily file for Lambda trigger:

```bash
python3 scripts/fetch_pp_lines.py --league-id 7 --s3-bucket <DataBucket> --s3-key-prefix prizepicks/lines
```

## NBA box scores (S3 + `nba_api`)

`stats.nba.com` is often slow or stalls from Lambda. Ingestion **`NbaIngestionFunctionV2`** reads the raw league game log JSON from S3 first (same `resultSets` shape as the live API), then falls back to HTTP only if configured.

GitHub Actions should upload **before** the 04:00 UTC ingestion schedule:

`s3://<DataBucket>/nba/api/YYYY-MM-DD.json`

Local fetch + upload (requires `pip install boto3 nba_api`):

```bash
python3 scripts/fetch_nba_api_stats.py --game-date 2026-03-25 --s3-bucket <DataBucket>
```

Dry run (no S3):

```bash
python3 scripts/fetch_nba_api_stats.py --game-date 2026-03-25 --output /tmp/nba_api.json
```

Lambda env **`NBA_INGEST_SOURCE`** (set in `template.yaml` or override on the function):

| Value | Behavior |
|--------|----------|
| `S3_THEN_HTTP` (default) | Read `nba/api/{date}.json`; if missing, call stats.nba.com with short HTTP timeouts |
| `S3` | S3 only; fails if the object is missing |
| `HTTP` | Live HTTP only (legacy) |

Workflow `.github/workflows/fetch-nba-stats.yml` uses the same repo secrets as PrizePicks (`PP_S3_BUCKET`, AWS keys).

## API

- `GET /edges?date=YYYY-MM-DD` — ranked edge scores for that slate.
- `GET /player/{id}/history?limit=50` — recent edges and outcomes for a player (NBA player id).

## Scheduled jobs (UTC)

| Time (cron) | Function |
|-------------|----------|
| 04:00 | NBA box score ingestion — prefers `s3://…/nba/api/YYYY-MM-DD.json` from Actions; override date with EventBridge `detail.gameDate` |
| 04:10 | Scoring (`detail.slateDate` or defaults to today UTC) |
| 04:30 | Outcome recorder (`detail.slateDate` or defaults to yesterday UTC) |

EventBridge `detail` for scheduled rules is a **map**; set keys like `gameDate`, `slateDate`, or `date` as needed.

## Cost notes

Template comments call out **no NAT Gateway**. Defaults target **free tier** (512 MB Lambdas, on-demand DynamoDB, small S3 usage).
