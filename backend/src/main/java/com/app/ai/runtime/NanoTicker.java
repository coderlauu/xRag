package com.app.ai.runtime;

@FunctionalInterface
public interface NanoTicker {

    long read();

    static NanoTicker system() {
        return System::nanoTime;
    }
}
