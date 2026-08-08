package com.app.qa.config;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.qa.understanding")
public class QaUnderstandingProperties implements InitializingBean {

    private int maxSubQuestions = 3;
    private int maxTotalIntents = 3;
    private int historyKeepTurns = 8;
    private int summaryStartTurns = 12;
    private int maxIntentPromptBytes = 65_536;
    private Duration processingStaleAfter = Duration.ofMinutes(3);
    private final Thresholds thresholds = new Thresholds();

    @Override
    public void afterPropertiesSet() {
        if (maxSubQuestions != 3) {
            throw new IllegalArgumentException("app.qa.understanding.max-sub-questions must equal 3");
        }
        if (maxTotalIntents < maxSubQuestions) {
            throw new IllegalArgumentException(
                    "app.qa.understanding.max-total-intents must be greater than or equal to "
                            + "app.qa.understanding.max-sub-questions");
        }
        if (historyKeepTurns < 1) {
            throw new IllegalArgumentException("app.qa.understanding.history-keep-turns must be positive");
        }
        if (summaryStartTurns <= historyKeepTurns) {
            throw new IllegalArgumentException(
                    "app.qa.understanding.summary-start-turns must be greater than "
                            + "app.qa.understanding.history-keep-turns");
        }
        if (maxIntentPromptBytes < 1) {
            throw new IllegalArgumentException("app.qa.understanding.max-intent-prompt-bytes must be positive");
        }
        if (processingStaleAfter == null || processingStaleAfter.isZero() || processingStaleAfter.isNegative()) {
            throw new IllegalArgumentException("app.qa.understanding.processing-stale-after must be positive");
        }
        thresholds.validate();
    }

    public int getMaxSubQuestions() {
        return maxSubQuestions;
    }

    public void setMaxSubQuestions(int maxSubQuestions) {
        this.maxSubQuestions = maxSubQuestions;
    }

    public int getMaxTotalIntents() {
        return maxTotalIntents;
    }

    public void setMaxTotalIntents(int maxTotalIntents) {
        this.maxTotalIntents = maxTotalIntents;
    }

    public int getHistoryKeepTurns() {
        return historyKeepTurns;
    }

    public void setHistoryKeepTurns(int historyKeepTurns) {
        this.historyKeepTurns = historyKeepTurns;
    }

    public int getSummaryStartTurns() {
        return summaryStartTurns;
    }

    public void setSummaryStartTurns(int summaryStartTurns) {
        this.summaryStartTurns = summaryStartTurns;
    }

    public int getMaxIntentPromptBytes() {
        return maxIntentPromptBytes;
    }

    public void setMaxIntentPromptBytes(int maxIntentPromptBytes) {
        this.maxIntentPromptBytes = maxIntentPromptBytes;
    }

    public Duration getProcessingStaleAfter() {
        return processingStaleAfter;
    }

    public void setProcessingStaleAfter(Duration processingStaleAfter) {
        this.processingStaleAfter = processingStaleAfter;
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public static final class Thresholds {

        private double retain = 0.35;
        private double targeted = 0.40;
        private double fallback = 0.60;
        private double supplement = 0.80;
        private double ambiguityMargin = 0.15;

        private void validate() {
            if (!unitInterval(retain)
                    || !unitInterval(targeted)
                    || !unitInterval(fallback)
                    || !unitInterval(supplement)) {
                throw new IllegalArgumentException(
                        "app.qa.understanding.thresholds values must be finite and between 0 and 1");
            }
            if (!(retain <= targeted && targeted <= fallback && fallback <= supplement)) {
                throw new IllegalArgumentException(
                        "app.qa.understanding.thresholds must satisfy "
                                + "retain <= targeted <= fallback <= supplement");
            }
            if (!Double.isFinite(ambiguityMargin) || ambiguityMargin <= 0 || ambiguityMargin >= 0.5) {
                throw new IllegalArgumentException(
                        "app.qa.understanding.thresholds.ambiguity-margin must be finite, greater than 0, "
                                + "and less than 0.5");
            }
        }

        private static boolean unitInterval(double value) {
            return Double.isFinite(value) && value >= 0 && value <= 1;
        }

        public double getRetain() {
            return retain;
        }

        public void setRetain(double retain) {
            this.retain = retain;
        }

        public double getTargeted() {
            return targeted;
        }

        public void setTargeted(double targeted) {
            this.targeted = targeted;
        }

        public double getFallback() {
            return fallback;
        }

        public void setFallback(double fallback) {
            this.fallback = fallback;
        }

        public double getSupplement() {
            return supplement;
        }

        public void setSupplement(double supplement) {
            this.supplement = supplement;
        }

        public double getAmbiguityMargin() {
            return ambiguityMargin;
        }

        public void setAmbiguityMargin(double ambiguityMargin) {
            this.ambiguityMargin = ambiguityMargin;
        }
    }
}
