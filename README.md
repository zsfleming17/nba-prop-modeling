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

## API

- `GET /edges?date=YYYY-MM-DD` — ranked edge scores for that slate.
- `GET /player/{id}/history?limit=50` — recent edges and outcomes for a player (NBA player id).

## Scheduled jobs (UTC)

| Time (cron) | Function |
|-------------|----------|
| 04:00 | NBA box score ingestion (default: previous UTC day; override with EventBridge `detail.slateDate` / `detail.gameDate` where applicable) |
| 04:10 | Scoring (`detail.slateDate` or defaults to today UTC) |
| 04:30 | Outcome recorder (`detail.slateDate` or defaults to yesterday UTC) |

EventBridge `detail` for scheduled rules is a **map**; set keys like `gameDate`, `slateDate`, or `date` as needed.

## Cost notes

Template comments call out **no NAT Gateway**. Defaults target **free tier** (512 MB Lambdas, on-demand DynamoDB, small S3 usage).
