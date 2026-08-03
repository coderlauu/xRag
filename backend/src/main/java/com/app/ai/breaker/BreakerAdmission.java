package com.app.ai.breaker;

import com.app.ai.diagnostic.BreakerState;
import java.time.OffsetDateTime;

public sealed interface BreakerAdmission
        permits BreakerPermission, BreakerPermissionRejected {

    BreakerKey key();

    BreakerState state();

    OffsetDateTime occurredAt();
}
