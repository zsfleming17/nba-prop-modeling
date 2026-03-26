package com.sportsprop.outcomes;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.sportsprop.common.model.EdgeScoreRecord;
import com.sportsprop.common.model.OutcomeRecord;
import com.sportsprop.common.model.PlayerGameStatLine;
import com.sportsprop.common.model.PropLineRecord;
import com.sportsprop.common.model.StatType;
import com.sportsprop.common.nba.NbaSeasonUtil;
import com.sportsprop.common.repo.EdgeScoresRepository;
import com.sportsprop.common.repo.OutcomesRepository;
import com.sportsprop.common.repo.PlayerGameStatsRepository;
import com.sportsprop.common.repo.PropLinesRepository;
import com.sportsprop.common.schedule.ScheduleDetailUtil;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OutcomeRecorderHandler implements RequestHandler<ScheduledEvent, String> {

    private static final Logger LOG = Logger.getLogger(OutcomeRecorderHandler.class.getName());
    private static final int PLAYER_SCAN_DEPTH = 40;

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        try {
            return run(event);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "OutcomeRecorderHandler failed", e);
            throw new IllegalStateException("OutcomeRecorderHandler failed", e);
        }
    }

    private String run(ScheduledEvent event) {
        String playerTable = requiredEnv("PLAYER_GAME_STATS_TABLE");
        String linesTable = requiredEnv("PROP_LINES_TABLE");
        String edgesTable = requiredEnv("EDGE_SCORES_TABLE");
        String outcomesTable = requiredEnv("OUTCOMES_TABLE");

        String slateOverride = ScheduleDetailUtil.readDetailField(event, "slateDate");
        if (slateOverride == null) {
            slateOverride = ScheduleDetailUtil.readDetailField(event, "date");
        }
        String slateDate = slateOverride != null && !slateOverride.isBlank()
                ? slateOverride
                : NbaSeasonUtil.utcToday().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        LocalDate slate = LocalDate.parse(slateDate, DateTimeFormatter.ISO_LOCAL_DATE);

        Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
        try (DynamoDbClient ddb = DynamoDbClient.builder().region(region).build()) {
            PropLinesRepository propLines = new PropLinesRepository(ddb, linesTable);
            PlayerGameStatsRepository statsRepo = new PlayerGameStatsRepository(ddb, playerTable);
            EdgeScoresRepository edgesRepo = new EdgeScoresRepository(ddb, edgesTable);
            OutcomesRepository outcomesRepo = new OutcomesRepository(ddb, outcomesTable);

            List<PropLineRecord> lines = propLines.queryByDate(slateDate);
            int recorded = 0;
            for (PropLineRecord line : lines) {
                Optional<PlayerGameStatLine> game = statsRepo.findMostRecentOnDate(line.playerId(), slate, PLAYER_SCAN_DEPTH);
                if (game.isEmpty()) {
                    continue;
                }
                int actual = actualStat(game.get(), line.statType());
                boolean overHit = actual > line.lineValue();
                Optional<EdgeScoreRecord> edge = edgesRepo.get(slateDate, line.playerId(), line.statType());
                Double pred = edge.map(EdgeScoreRecord::modelProbabilityOver).orElse(null);
                outcomesRepo.put(new OutcomeRecord(
                        game.get().gameId(),
                        slateDate,
                        line.playerId(),
                        line.statType(),
                        line.lineValue(),
                        actual,
                        overHit,
                        pred
                ));
                recorded++;
            }
            LOG.info("Recorded " + recorded + " outcomes for " + slateDate);
            return "OK " + recorded + " outcomes " + slateDate;
        }
    }

    private static int actualStat(PlayerGameStatLine g, StatType type) {
        return switch (type) {
            case POINTS -> g.points();
            case REBOUNDS -> g.rebounds();
            case ASSISTS -> g.assists();
        };
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing env " + name);
        }
        return v;
    }
}
