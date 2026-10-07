package com.github.shikejing.buildorchestrationenginejava.execution;

import com.github.shikejing.buildorchestrationenginejava.fingerprint.TargetFingerprints;
import com.github.shikejing.buildorchestrationenginejava.model.DecisionReason;
import com.github.shikejing.buildorchestrationenginejava.state.TargetStateRecord;

class IncrementalDecider {
    IncrementalDecision decide(TargetStateRecord previous, TargetFingerprints current, boolean forceRebuild) {
        if (forceRebuild) {
            return new IncrementalDecision(true, DecisionReason.FORCED_REBUILD);
        }
        if (previous == null) {
            return new IncrementalDecision(true, DecisionReason.NO_PREVIOUS_STATE);
        }
        if (!equals(previous.inputFingerprint(), current.inputFingerprint())) {
            return new IncrementalDecision(true, DecisionReason.INPUT_CHANGED);
        }
        if (!equals(previous.actionFingerprint(), current.actionFingerprint())) {
            return new IncrementalDecision(true, DecisionReason.ACTION_CHANGED);
        }
        if (!equals(previous.dependencyFingerprint(), current.dependencyFingerprint())) {
            return new IncrementalDecision(true, DecisionReason.DEPENDENCY_CHANGED);
        }
        if (!current.outputsPresent()) {
            return new IncrementalDecision(true, DecisionReason.OUTPUT_MISSING);
        }
        if (!equals(previous.outputFingerprint(), current.outputFingerprint())) {
            return new IncrementalDecision(true, DecisionReason.OUTPUT_MODIFIED);
        }
        return new IncrementalDecision(false, DecisionReason.UP_TO_DATE);
    }

    private boolean equals(String left, String right) {
        return left != null && left.equals(right);
    }
}
