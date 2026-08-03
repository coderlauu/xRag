package com.app.ai.model;

public interface CancellationToken {

    boolean isCancelled();

    CancellationRegistration onCancel(Runnable listener);

    static CancellationToken none() {
        return NoCancellationToken.INSTANCE;
    }

    enum NoCancellationToken implements CancellationToken {
        INSTANCE;

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public CancellationRegistration onCancel(Runnable listener) {
            return () -> { };
        }
    }
}
