package com.app.qa.intent;

import java.util.UUID;

public final class IntentTreeRevisionConflictException extends RuntimeException {

    private final UUID currentRevisionId;
    private final long currentRevisionNo;

    IntentTreeRevisionConflictException(UUID currentRevisionId, long currentRevisionNo) {
        super("intent tree revision conflict");
        this.currentRevisionId = currentRevisionId;
        this.currentRevisionNo = currentRevisionNo;
    }

    public UUID currentRevisionId() {
        return currentRevisionId;
    }

    public long currentRevisionNo() {
        return currentRevisionNo;
    }
}
