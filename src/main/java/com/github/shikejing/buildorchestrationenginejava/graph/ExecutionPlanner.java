package com.github.shikejing.buildorchestrationenginejava.graph;

import com.github.shikejing.buildorchestrationenginejava.error.BuildIssue;
import com.github.shikejing.buildorchestrationenginejava.error.BuildValidationException;
import com.github.shikejing.buildorchestrationenginejava.model.BuildDescription;
import com.github.shikejing.buildorchestrationenginejava.model.TargetDeclaration;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ExecutionPlanner {
    public ExecutionPlan plan(BuildDescription description, Set<String> toolchains) {
        if (description == null) {
            throw new BuildValidationException(List.of(new BuildIssue(null, "Build description must not be null")));
        }
        List<BuildIssue> issues = new ArrayList<>();
        Map<String, TargetDeclaration> targetsByName = new LinkedHashMap<>();
        Map<String, Integer> declarations = new HashMap<>();
        Set<String> knownToolchains = toolchains == null ? Set.of() : Set.copyOf(toolchains);
        Set<String> outputOwners = new HashSet<>();

        for (int i = 0; i < description.targets().size(); i++) {
            TargetDeclaration target = description.targets().get(i);
            if (target == null) {
                issues.add(new BuildIssue("#" + i, "Target declaration must not be null"));
                continue;
            }
            String name = target.name();
            if (name == null || name.isBlank()) {
                issues.add(new BuildIssue("#" + i, "Target name must not be blank"));
                continue;
            }
            if (targetsByName.putIfAbsent(name, target) != null) {
                issues.add(new BuildIssue(name, "Target is defined more than once"));
                continue;
            }
            declarations.put(name, i);
            validateTarget(target, knownToolchains, outputOwners, issues);
        }

        for (TargetDeclaration target : targetsByName.values()) {
            Set<String> seenDependencies = new LinkedHashSet<>();
            for (String dependency : target.dependsOn()) {
                if (!seenDependencies.add(dependency)) {
                    issues.add(new BuildIssue(target.name(), "Duplicate dependency '" + dependency + "'"));
                }
                if (dependency.equals(target.name())) {
                    issues.add(new BuildIssue(target.name(), "Target depends on itself"));
                } else if (!targetsByName.containsKey(dependency)) {
                    issues.add(new BuildIssue(target.name(), "Dependency '" + dependency + "' does not exist"));
                }
            }
        }

        List<String> cycleNodes = findCycleNodes(targetsByName);
        for (String targetName : cycleNodes) {
            issues.add(new BuildIssue(targetName, "Target participates in a dependency cycle"));
        }

        if (!issues.isEmpty()) {
            throw new BuildValidationException(issues);
        }

        return new ExecutionPlan(stableTopologicalOrder(targetsByName, declarations));
    }

    private void validateTarget(TargetDeclaration target, Set<String> knownToolchains,
                                Set<String> outputOwners, List<BuildIssue> issues) {
        if (target.toolchain() == null || target.toolchain().isBlank()) {
            issues.add(new BuildIssue(target.name(), "Toolchain must not be blank"));
        } else if (!knownToolchains.contains(target.toolchain())) {
            issues.add(new BuildIssue(target.name(), "Unknown toolchain '" + target.toolchain() + "'"));
        }
        if (target.outputs().isEmpty()) {
            issues.add(new BuildIssue(target.name(), "Target must declare at least one output"));
        }
        validatePaths(target, target.inputs(), "input", issues);
        validatePaths(target, target.outputs(), "output", issues);
        for (String output : target.outputs()) {
            if (output != null && !output.isBlank() && !outputOwners.add(normalize(output))) {
                issues.add(new BuildIssue(target.name(), "Output '" + output + "' is already declared by another target"));
            }
        }
        if (target.retries() != null && target.retries() < 0) {
            issues.add(new BuildIssue(target.name(), "Retries must not be negative"));
        }
        if (target.timeout() != null && target.timeout().isNegative()) {
            issues.add(new BuildIssue(target.name(), "Timeout must not be negative"));
        }
    }

    private void validatePaths(TargetDeclaration target, List<String> paths, String kind,
                               List<BuildIssue> issues) {
        for (String pathValue : paths) {
            if (pathValue == null || pathValue.isBlank()) {
                issues.add(new BuildIssue(target.name(), capitalize(kind) + " path must not be blank"));
                continue;
            }
            Path path = Path.of(pathValue);
            if (path.isAbsolute()) {
                issues.add(new BuildIssue(target.name(), capitalize(kind) + " path '" + pathValue + "' must be relative"));
            } else if (path.normalize().startsWith("..")) {
                issues.add(new BuildIssue(target.name(), capitalize(kind) + " path '" + pathValue + "' must not escape workspace"));
            }
        }
    }

    private List<String> findCycleNodes(Map<String, TargetDeclaration> targetsByName) {
        Set<String> finished = new HashSet<>();
        Set<String> active = new HashSet<>();
        Set<String> cycleNodes = new LinkedHashSet<>();
        for (TargetDeclaration target : targetsByName.values()) {
            if (!finished.contains(target.name())) {
                visitForCycle(target.name(), targetsByName, finished, active,
                        new ArrayDeque<>(), cycleNodes);
            }
        }
        return List.copyOf(cycleNodes);
    }

    private void visitForCycle(String name, Map<String, TargetDeclaration> targetsByName,
                               Set<String> finished, Set<String> active,
                               Deque<String> path, Set<String> cycleNodes) {
        if (active.contains(name)) {
            boolean inCycle = false;
            for (String pathNode : path) {
                if (pathNode.equals(name)) {
                    inCycle = true;
                }
                if (inCycle) {
                    cycleNodes.add(pathNode);
                }
            }
            return;
        }
        if (finished.contains(name) || !targetsByName.containsKey(name)) {
            return;
        }
        active.add(name);
        path.addLast(name);
        for (String dependency : targetsByName.get(name).dependsOn()) {
            visitForCycle(dependency, targetsByName, finished, active, path, cycleNodes);
        }
        path.removeLast();
        active.remove(name);
        finished.add(name);
    }

    private List<String> stableTopologicalOrder(Map<String, TargetDeclaration> targetsByName,
                                                Map<String, Integer> declarations) {
        Map<String, Integer> remainingDependencies = new HashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (String name : targetsByName.keySet()) {
            remainingDependencies.put(name, 0);
            dependents.put(name, new ArrayList<>());
        }
        for (TargetDeclaration target : targetsByName.values()) {
            remainingDependencies.put(target.name(), target.dependsOn().size());
            for (String dependency : target.dependsOn()) {
                dependents.get(dependency).add(target.name());
            }
        }

        List<String> ready = new ArrayList<>();
        for (String name : targetsByName.keySet()) {
            if (remainingDependencies.get(name) == 0) {
                ready.add(name);
            }
        }
        ready.sort((a, b) -> Integer.compare(declarations.get(a), declarations.get(b)));

        List<String> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.remove(0);
            ordered.add(current);
            List<String> newlyReady = new ArrayList<>();
            for (String dependent : dependents.get(current)) {
                int remaining = remainingDependencies.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    newlyReady.add(dependent);
                }
            }
            newlyReady.sort((a, b) -> Integer.compare(declarations.get(a), declarations.get(b)));
            ready.addAll(newlyReady);
            ready.sort((a, b) -> Integer.compare(declarations.get(a), declarations.get(b)));
        }
        return List.copyOf(ordered);
    }

    private String normalize(String path) {
        return Path.of(path).normalize().toString().replace('\\', '/');
    }

    private String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
