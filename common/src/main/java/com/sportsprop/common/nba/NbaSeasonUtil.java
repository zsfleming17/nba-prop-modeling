package com.sportsprop.common.nba;

import java.time.LocalDate;
import java.time.ZoneOffset;

public final class NbaSeasonUtil {

    private NbaSeasonUtil() {
    }

    /**
     * NBA season string e.g. 2024-25 for games from Oct 2024 through Jun 2025.
     */
    public static String seasonForDate(LocalDate date) {
        int y = date.getYear();
        int month = date.getMonthValue();
        int startYear = month >= 10 ? y : y - 1;
        int endYearShort = (startYear + 1) % 100;
        return startYear + "-" + String.format("%02d", endYearShort);
    }

    public static LocalDate utcToday() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
