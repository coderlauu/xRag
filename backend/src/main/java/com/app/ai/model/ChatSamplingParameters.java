package com.app.ai.model;

public record ChatSamplingParameters(double temperature, double topP) {

    public ChatSamplingParameters {
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature must be finite and between 0 and 2");
        }
        if (!Double.isFinite(topP) || topP <= 0 || topP > 1) {
            throw new IllegalArgumentException("topP must be finite and greater than 0 and at most 1");
        }
    }
}
