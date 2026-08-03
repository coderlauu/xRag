package com.app.ai.breaker;

import com.app.ai.model.Capability;
import com.app.ai.model.ExecutionTarget;
import java.util.List;

public interface ModelBreakerRegistry {

    BreakerAdmission tryAcquire(ExecutionTarget target, Capability capability);

    BreakerSnapshot snapshot(ExecutionTarget target, Capability capability);

    List<BreakerSnapshot> snapshots();
}
