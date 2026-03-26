package com.sportsprop.common.scoring;

/**
 * Standard normal CDF via error function approximation (Abramowitz and Stegun 7.1.26).
 */
public final class NormalDistributionApprox {

    private NormalDistributionApprox() {
    }

    public static double standardNormalCdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    static double erf(double x) {
        double sign = x < 0 ? -1.0 : 1.0;
        x = Math.abs(x);
        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;
        double p = 0.3275911;
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        return sign * y;
    }
}
