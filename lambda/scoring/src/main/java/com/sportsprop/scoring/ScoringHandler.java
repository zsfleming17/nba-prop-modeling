package com.sportsprop.scoring;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.sportsprop.common.model.EdgeScoreRecord;
import com.sportsprop.common.model.PropLineRecord;
import com.sportsprop.common.nba.NbaSeasonUtil;
import com.sportsprop.common.repo.EdgeScoresRepository;
import com.sportsprop.common.repo.PlayerGameStatsRepository;
import com.sportsprop.common.repo.PropLinesRepository;
import com.sportsprop.common.schedule.ScheduleDetailUtil;
import com.sportsprop.common.scoring.EdgeScorer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ScoringHandler implements RequestHandler<ScheduledEvent, String> {

    private static final Logger LOG = Logger.getLogger(ScoringHandler.class.getName());

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        try {
            return run(event);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "ScoringHandler failed", e);
            throw new IllegalStateException("ScoringHandler failed", e);
        }
    }

    private String run(ScheduledEvent event) {
        String playerTable = requiredEnv("PLAYER_GAME_STATS_TABLE");
        String linesTable = requiredEnv("PROP_LINES_TABLE");
        String edgesTable = requiredEnv("EDGE_SCORES_TABLE");

        String slateOverride = ScheduleDetailUtil.readDetailField(event, "slateDate");
        if (slateOverride == null) {
            slateOverride = ScheduleDetailUtil.readDetailField(event, "date");
        }
        String slateDate = slateOverride != null && !slateOverride.isBlank()
                ? slateOverride
                : NbaSeasonUtil.utcToday().format(DateTimeFormatter.ISO_LOCAL_DATE);

        Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
        try (DynamoDbClient ddb = DynamoDbClient.builder().region(region).build()) {
            PropLinesRepository propLines = new PropLinesRepository(ddb, linesTable);
            PlayerGameStatsRepository stats = new PlayerGameStatsRepository(ddb, playerTable);
            EdgeScoresRepository edges = new EdgeScoresRepository(ddb, edgesTable);
            EdgeScorer scorer = new EdgeScorer(stats);

            List<PropLineRecord> lines = propLines.queryByDate(slateDate);
            int n = 0;
            for (PropLineRecord line : lines) {
                EdgeScoreRecord edge = scorer.score(line);
                edges.put(edge);
                n++;
            }
            LOG.info("Scored " + n + " edges for " + slateDate);
            return "OK " + n + " edges " + slateDate;
        }
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing env " + name);
        }
        return v;
    }
}
