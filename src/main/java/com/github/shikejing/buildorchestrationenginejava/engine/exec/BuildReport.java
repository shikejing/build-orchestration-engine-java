package com.github.shikejing.buildorchestrationenginejava.engine.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Queryable result of a whole run: status, duration and reason per target. */
public class BuildReport {

    private final Map<String, TargetResult> results;
    private final List<String> executionOrder;

    public BuildReport(Map<String, TargetResult> results, List<String> executionOrder) {
        this.results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
        this.executionOrder = List.copyOf(executionOrder);
    }

    public Optional<TargetResult> target(String name) {
        return Optional.ofNullable(results.get(name));
    }

    /** Results in the deterministic execution order of this run. */
    public List<TargetResult> resultsInOrder() {
        List<TargetResult> ordered = new ArrayList<>();
        for (String name : executionOrder) {
            TargetResult r = results.get(name);
            if (r != null) {
                ordered.add(r);
            }
        }
        return ordered;
    }

    public boolean success() {
        return results.values().stream().noneMatch(r ->
                r.getStatus() == TargetStatus.FAILED || r.getStatus() == TargetStatus.CANCELLED);
    }
}
