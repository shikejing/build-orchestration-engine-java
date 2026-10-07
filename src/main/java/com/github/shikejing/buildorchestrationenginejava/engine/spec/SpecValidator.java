package com.github.shikejing.buildorchestrationenginejava.engine.spec;

import com.github.shikejing.buildorchestrationenginejava.engine.model.BuildSpec;
import com.github.shikejing.buildorchestrationenginejava.engine.model.TargetDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a parsed spec. Any problem rejects the whole spec:
 * nothing is loaded partially.
 */
public class SpecValidator {

    public void validate(BuildSpec spec) {
        List<String> problems = new ArrayList<>();

        Map<String, TargetDefinition> byName = new LinkedHashMap<>();
        Map<String, String> outputOwner = new HashMap<>();

        for (TargetDefinition t : spec.getTargets()) {
            String name = t.getName();
            if (name == null || name.isBlank()) {
                problems.add("target: name must not be blank");
                continue;
            }
            if (byName.putIfAbsent(name, t) != null) {
                // keep the first definition so later checks still see it
                problems.add("target '" + name + "': duplicate definition");
            }
            if (t.getOutputs().isEmpty()) {
                problems.add("target '" + name + "': declares no outputs");
            }
            Set<String> seenOutputs = new HashSet<>();
            for (String out : t.getOutputs()) {
                if (!seenOutputs.add(out)) {
                    problems.add("target '" + name + "': duplicate output '" + out + "'");
                }
                String owner = outputOwner.putIfAbsent(out, name);
                if (owner != null && !owner.equals(name)) {
                    problems.add("target '" + name + "': output '" + out
                            + "' already owned by target '" + owner + "'");
                }
            }
            String toolchain = t.getToolchain();
            if (toolchain != null && !toolchain.equals("default")
                    && !spec.getToolchains().containsKey(toolchain)) {
                problems.add("target '" + name + "': unknown toolchain '" + toolchain + "'");
            }
        }

        // Dangling dependency references, located by referencing target.
        for (TargetDefinition t : byName.values()) {
            for (String dep : t.getDeps()) {
                if (!byName.containsKey(dep)) {
                    problems.add("target '" + t.getName() + "': depends on unknown target '" + dep + "'");
                }
            }
        }

        // Cycle detection over valid references only, reporting the cycle path.
        if (problems.isEmpty()) {
            detectCycles(byName, problems);
        }

        if (!problems.isEmpty()) {
            throw new SpecException(problems);
        }
    }

    private void detectCycles(Map<String, TargetDefinition> byName, List<String> problems) {
        Map<String, Integer> mark = new HashMap<>(); // 0=unvisited 1=in-stack 2=done
        Deque<String> stack = new ArrayDeque<>();
        for (String root : byName.keySet()) {
            if (mark.getOrDefault(root, 0) != 0) {
                continue;
            }
            dfs(root, byName, mark, stack, problems);
            if (!problems.isEmpty()) {
                return; // one precise cycle report is enough; spec is rejected anyway
            }
        }
    }

    private void dfs(String node, Map<String, TargetDefinition> byName,
                     Map<String, Integer> mark, Deque<String> stack, List<String> problems) {
        mark.put(node, 1);
        stack.addLast(node);
        for (String dep : byName.get(node).getDeps()) {
            int m = mark.getOrDefault(dep, 0);
            if (m == 0) {
                dfs(dep, byName, mark, stack, problems);
                if (!problems.isEmpty()) {
                    return;
                }
            } else if (m == 1) {
                List<String> cycle = new ArrayList<>();
                boolean inCycle = false;
                for (String s : stack) {
                    if (s.equals(dep)) {
                        inCycle = true;
                    }
                    if (inCycle) {
                        cycle.add(s);
                    }
                }
                cycle.add(dep);
                problems.add("circular dependency detected: " + String.join(" -> ", cycle));
                return;
            }
        }
        stack.removeLast();
        mark.put(node, 2);
    }
}
