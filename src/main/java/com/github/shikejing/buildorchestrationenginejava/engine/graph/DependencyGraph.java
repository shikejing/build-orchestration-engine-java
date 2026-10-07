package com.github.shikejing.buildorchestrationenginejava.engine.graph;

import com.github.shikejing.buildorchestrationenginejava.engine.model.BuildSpec;
import com.github.shikejing.buildorchestrationenginejava.engine.model.TargetDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Dependency graph with a deterministic topological order.
 * Ties between ready targets are broken by declaration index, so the order
 * never depends on hash-map iteration, dictionary order or file-system order.
 */
public class DependencyGraph {

    private final Map<String, Integer> declarationIndex;
    private final Map<String, Set<String>> deps;      // target -> direct deps
    private final Map<String, Set<String>> dependents; // target -> direct dependents
    private final List<String> executionOrder;

    public DependencyGraph(BuildSpec spec) {
        Map<String, Integer> index = new LinkedHashMap<>();
        Map<String, Set<String>> depsMap = new LinkedHashMap<>();
        Map<String, Set<String>> dependentsMap = new LinkedHashMap<>();
        int i = 0;
        for (TargetDefinition t : spec.getTargets()) {
            index.put(t.getName(), i++);
            depsMap.put(t.getName(), new LinkedHashSet<>(t.getDeps()));
            dependentsMap.put(t.getName(), new LinkedHashSet<>());
        }
        for (TargetDefinition t : spec.getTargets()) {
            for (String dep : t.getDeps()) {
                dependentsMap.get(dep).add(t.getName());
            }
        }
        this.declarationIndex = Collections.unmodifiableMap(index);
        this.deps = Collections.unmodifiableMap(depsMap);
        this.dependents = Collections.unmodifiableMap(dependentsMap);
        this.executionOrder = Collections.unmodifiableList(computeOrder());
    }

    /** Kahn's algorithm; among ready targets always pick the lowest declaration index. */
    private List<String> computeOrder() {
        Map<String, Integer> remaining = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            remaining.put(e.getKey(), e.getValue().size());
        }
        PriorityQueue<String> ready = new PriorityQueue<>(
                (a, b) -> Integer.compare(declarationIndex.get(a), declarationIndex.get(b)));
        for (Map.Entry<String, Integer> e : remaining.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String next = ready.poll();
            order.add(next);
            for (String dependent : dependents.get(next)) {
                int left = remaining.merge(dependent, -1, Integer::sum);
                if (left == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (order.size() != deps.size()) {
            throw new IllegalStateException("graph still contains a cycle; validate the spec first");
        }
        return order;
    }

    /** Stable execution order independent of concurrency and iteration order. */
    public List<String> executionOrder() {
        return executionOrder;
    }

    public Set<String> depsOf(String target) {
        return deps.getOrDefault(target, Set.of());
    }

    public Set<String> dependentsOf(String target) {
        return dependents.getOrDefault(target, Set.of());
    }

    /** All transitive dependencies, in deterministic execution order. */
    public List<String> transitiveDepsOf(String target) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(depsOf(target));
        while (!stack.isEmpty()) {
            String n = stack.pollLast();
            if (seen.add(n)) {
                stack.addAll(depsOf(n));
            }
        }
        List<String> result = new ArrayList<>();
        for (String s : executionOrder) {
            if (seen.contains(s)) {
                result.add(s);
            }
        }
        return result;
    }

    public Map<String, Integer> declarationIndex() {
        return declarationIndex;
    }

    public Set<String> targets() {
        return deps.keySet();
    }
}
