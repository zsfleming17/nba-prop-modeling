package com.sportsprop.common.scoring;

import com.sportsprop.common.model.EdgeScoreRecord;
import com.sportsprop.common.model.PropLineRecord;
import com.sportsprop.common.repo.PlayerGameStatsRepository;

public class EdgeScorer {

    private static final int ROLLING_GAMES = 5;

    private final PlayerGameStatsRepository playerGameStatsRepository;

    public EdgeScorer(PlayerGameStatsRepository playerGameStatsRepository) {
        this.playerGameStatsRepository = playerGameStatsRepository;
    }

    public EdgeScoreRecord score(PropLineRecord line) {
        double mean = playerGameStatsRepository.rollingAverage(line.playerId(), line.statType(), ROLLING_GAMES);
        double sigma = switch (line.statType()) {
            case POINTS -> Math.sqrt(Math.max(1.0, mean) + 2.0);
            case REBOUNDS, ASSISTS -> Math.sqrt(Math.max(0.5, mean) + 0.75);
        };
        double pOver = probabilityOver(mean, sigma, line.lineValue());
        double edge = pOver - 0.5;
        return new EdgeScoreRecord(
                line.slateDate(),
                line.playerId(),
                line.statType(),
                line.lineValue(),
                mean,
                pOver,
                edge
        );
    }

    /**
     * P(actual stat &gt; line) with normal approximation and 0.5 continuity correction on half-lines.
     */
    static double probabilityOver(double mean, double sigma, double line) {
        if (sigma <= 0) {
            sigma = 1.0;
        }
        double z = (mean - line - 0.5) / sigma;
        return NormalDistributionApprox.standardNormalCdf(z);
    }
}
