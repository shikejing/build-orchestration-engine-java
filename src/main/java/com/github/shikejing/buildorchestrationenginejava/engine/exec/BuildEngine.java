package com.github.shikejing.buildorchestrationenginejava.engine.exec;

import com.github.shikejing.buildorchestrationenginejava.engine.cache.BuildCache;
import com.github.shikejing.buildorchestrationenginejava.engine.cache.CacheEntry;
import com.github.shikejing.buildorchestrationenginejava.engine.cache.CacheLookup;
import com.github.shikejing.buildorchestrationenginejava.engine.fingerprint.Fingerprint;
import com.github.shikejing.buildorchestrationenginejava.engine.fingerprint.InputSource;
import com.github.shikejing.buildorchestrationenginejava.engine.graph.DependencyGraph;
import com.github.shikejing.buildorchestrationenginejava.engine.model.BuildSpec;
import com.github.shikejing.buildorchestrationenginejava.engine.model.TargetDefinition;
import com.github.shikejing.buildorchestrationenginejava.engine.state.BuildStateStore;
import com.github.shikejing.buildorchestrationenginejava.engine.state.TargetState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Orchestrates incremental, parallel, recoverable execution of a build spec.
 *
 * <p>Incrementality: a target is re-run only when its fingerprint (own inputs,
 * action, toolchain and dependency fingerprints) changed, its outputs are
 * missing or were tampered with, or a re-run is forced. Cache keys are the
 * same fingerprints, so a hit can never map to stale or wrong outputs.
 *
 * <p>Recovery: only committed states (atomically written) are trusted, so
 * after a crash completed targets are not re-executed and incomplete ones
 * are re-run from scratch with clean staging.
 */
public class BuildEngine {

    private final BuildSpec spec;
    private final DependencyGraph graph;
    private final InputSource inputSource;
    private final Path outputRoot;
    private final BuildStateStore stateStore;
    private final BuildCache cache;
    private final EngineConfig config;
    private final ActionFactory actionFactory = new ActionFactory();
    private final ResourceManager resourceManager = new ResourceManager();
    private final Map<String, TargetDefinition> targetsByName;

    public BuildEngine(BuildSpec spec,
                       DependencyGraph graph,
                       InputSource inputSource,
                       Path outputRoot,
                       BuildStateStore stateStore,
                       BuildCache cache,
                       EngineConfig config) {
        this.spec = spec;
        this.graph = graph;
        this.inputSource = inputSource;
        this.outputRoot = outputRoot;
        this.stateStore = stateStore;
        this.cache = cache;
        this.config = config;
        this.targetsByName = new LinkedHashMap<>();
        for (TargetDefinition t : spec.getTargets()) {
            this.targetsByName.put(t.getName(), t);
        }
    }

    /** Run the given targets (or all when requested is empty). */
    public BuildReport run(Set<String> requested, boolean force) {
        Set<String> runSet = computeRunSet(requested);
        List<String> order = new ArrayList<>();
        for (String name : graph.executionOrder()) {
            if (runSet.contains(name)) {
                order.add(name);
            }
        }

        // Fingerprints are computed in dependency order so dep parts are ready.
        Map<String, String> ownFingerprints = new HashMap<>();
        Map<String, String> fullFingerprints = new HashMap<>();
        for (String name : order) {
            computeFingerprints(name, ownFingerprints, fullFingerprints);
        }

        Map<String, TargetResult> results = new LinkedHashMap<>();
        Map<String, Boolean> mustRun = new HashMap<>();
        for (String name : order) {
            TargetResult result = new TargetResult(name);
            results.put(name, result);
            mustRun.put(name, decide(name, force, fullFingerprints.get(name), result));
        }

        schedule(order, mustRun, results, fullFingerprints);
        return new BuildReport(results, order);
    }

    // ---------------------------------------------------------------- run set

    private Set<String> computeRunSet(Set<String> requested) {
        Set<String> runSet = new LinkedHashSet<>();
        if (requested == null || requested.isEmpty()) {
            runSet.addAll(graph.targets());
            return runSet;
        }
        Deque<String> stack = new ArrayDeque<>();
        for (String name : requested) {
            if (!graph.targets().contains(name)) {
                throw new IllegalArgumentException("unknown target requested: " + name);
            }
            stack.push(name);
        }
        while (!stack.isEmpty()) {
            String n = stack.pop();
            if (runSet.add(n)) {
                graph.depsOf(n).forEach(stack::push);
            }
        }
        return runSet;
    }

    // ------------------------------------------------------------ fingerprint

    private void computeFingerprints(String name,
                                     Map<String, String> ownOut,
                                     Map<String, String> fullOut) {
        TargetDefinition t = targetsByName.get(name);
        List<String> ownParts = new ArrayList<>();
        ownParts.add("name=" + name);
        ownParts.add("toolchain=" + t.getToolchain());
        ownParts.add("toolchainVersion=" + spec.getToolchains().getOrDefault(t.getToolchain(), ""));
        ownParts.add("action=" + t.getAction().getKind());
        new TreeMap<>(t.getAction().getParams())
                .forEach((k, v) -> ownParts.add("param." + k + "=" + v));
        for (String input : t.getInputs()) {
            try {
                ownParts.add("input=" + input + ":" + inputSource.read(input)
                        .map(Fingerprint::ofBytes).orElse("MISSING"));
            } catch (IOException e) {
                ownParts.add("input=" + input + ":UNREADABLE");
            }
        }
        String own = Fingerprint.ofStrings(ownParts);
        ownOut.put(name, own);

        List<String> fullParts = new ArrayList<>();
        fullParts.add(own);
        for (String dep : graph.executionOrder()) {
            // direct deps only; transitives are folded in through their parents
            if (graph.depsOf(name).contains(dep)) {
                fullParts.add("dep=" + dep + ":" + fullOut.get(dep));
            }
        }
        fullOut.put(name, Fingerprint.ofStrings(fullParts));
    }

    // --------------------------------------------------------------- decision

    /** Returns true when the target must (re)execute; fills skip reasons otherwise. */
    private boolean decide(String name, boolean force, String fingerprint, TargetResult result) {
        if (force) {
            result.setReason(DecisionReason.FORCED_RERUN);
            return true;
        }
        var prior = stateStore.load(name);
        if (prior.isEmpty()) {
            result.setReason(DecisionReason.NO_PRIOR_STATE);
            return true;
        }
        TargetState state = prior.get();
        if (!fingerprint.equals(state.getFingerprint())) {
            result.setReason(DecisionReason.INPUTS_CHANGED);
            return true;
        }
        // Outputs must exist and match the committed hashes (tamper detection).
        boolean missing = false;
        boolean tampered = false;
        for (Map.Entry<String, String> out : state.getOutputHashes().entrySet()) {
            Path file = outputRoot.resolve(out.getKey()).normalize();
            if (!Files.isRegularFile(file)) {
                missing = true;
                break;
            }
            try {
                if (!Fingerprint.ofBytes(Files.readAllBytes(file)).equals(out.getValue())) {
                    tampered = true;
                    break;
                }
            } catch (IOException e) {
                tampered = true;
                break;
            }
        }
        if (missing) {
            result.setReason(DecisionReason.OUTPUT_MISSING);
            return true;
        }
        if (tampered) {
            result.setReason(DecisionReason.OUTPUT_TAMPERED);
            return true;
        }
        result.setStatus(TargetStatus.UP_TO_DATE);
        result.setReason(DecisionReason.INPUTS_UNCHANGED);
        result.setDurationMs(0);
        return false;
    }

    // -------------------------------------------------------------- scheduler

    private void schedule(List<String> order,
                          Map<String, Boolean> mustRun,
                          Map<String, TargetResult> results,
                          Map<String, String> fingerprints) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, config.getParallelism()));
        Map<String, Future<?>> running = new LinkedHashMap<>();
        Set<String> done = new HashSet<>();
        Set<String> failed = new HashSet<>();
        boolean cancelAll = false;
        try {
            Set<String> pending = new LinkedHashSet<>(order);
            while (!pending.isEmpty() || !running.isEmpty()) {
                // Complete ready up-to-date targets inline, in deterministic order.
                boolean progressed;
                do {
                    progressed = false;
                    for (String name : new ArrayList<>(pending)) {
                        if (cancelAll || !done.containsAll(graph.depsOf(name))
                                || !failedDeps(name, failed).isEmpty()) {
                            continue;
                        }
                        if (!mustRun.get(name)) {
                            done.add(name);
                            pending.remove(name);
                            progressed = true;
                        }
                    }
                } while (progressed);

                // Submit ready run-targets in declaration (deterministic) order.
                for (String name : new ArrayList<>(pending)) {
                    if (cancelAll) {
                        break;
                    }
                    if (!mustRun.get(name) || !done.containsAll(graph.depsOf(name))
                            || !failedDeps(name, failed).isEmpty()) {
                        continue;
                    }
                    TargetDefinition def = targetsByName.get(name);
                    TargetResult result = results.get(name);
                    String fingerprint = fingerprints.get(name);
                    running.put(name, pool.submit(() -> {
                        executeTarget(def, fingerprint, result);
                        return null;
                    }));
                    pending.remove(name);
                }

                // Apply failure policy to whatever cannot proceed. Targets blocked
                // by failed deps are always marked DEPENDENCY_FAILED, even when the
                // run is being cancelled as a whole.
                boolean progressPossible = applyFailurePolicy(pending, running, failed, results, cancelAll);
                if (cancelAll || !progressPossible) {
                    cancelAll = true;
                }

                if (running.isEmpty()) {
                    if (!pending.isEmpty() && !cancelAll) {
                        // Only failed-dependency targets remain; handled by policy above.
                        applyFailurePolicy(pending, running, failed, results, true);
                        pending.clear();
                    }
                    break;
                }

                // Wait for at least one running task to finish.
                String finishedName = waitAny(running);
                Future<?> future = running.remove(finishedName);
                try {
                    future.get();
                } catch (Exception e) {
                    results.get(finishedName).setStatus(TargetStatus.FAILED);
                    results.get(finishedName).setReason(DecisionReason.ACTION_FAILED);
                    results.get(finishedName).setDetail("scheduler: " + e.getMessage());
                }
                if (results.get(finishedName).getStatus() == TargetStatus.FAILED) {
                    failed.add(finishedName);
                    if (config.getFailurePolicy() == FailurePolicy.CANCEL_REMAINING) {
                        cancelAll = true;
                    }
                } else {
                    done.add(finishedName);
                }
            }
            // Final sweep: everything still pending is cancelled.
            for (String name : pending) {
                TargetResult r = results.get(name);
                r.setStatus(TargetStatus.CANCELLED);
                r.setReason(cancelAll ? DecisionReason.CANCELLED_BY_FAILURE
                        : DecisionReason.DEPENDENCY_FAILED);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private Set<String> failedDeps(String name, Set<String> failed) {
        Set<String> out = new LinkedHashSet<>();
        for (String dep : graph.transitiveDepsOf(name)) {
            if (failed.contains(dep)) {
                out.add(dep);
            }
        }
        return out;
    }

    /** Marks targets blocked by failed deps; returns false when nothing can proceed. */
    private boolean applyFailurePolicy(Set<String> pending,
                                       Map<String, Future<?>> running,
                                       Set<String> failed,
                                       Map<String, TargetResult> results,
                                       boolean cancelAll) {
        boolean anyProgressPossible = false;
        for (String name : new ArrayList<>(pending)) {
            Set<String> badDeps = failedDeps(name, failed);
            if (!badDeps.isEmpty()) {
                TargetResult r = results.get(name);
                r.setStatus(TargetStatus.CANCELLED);
                r.setReason(DecisionReason.DEPENDENCY_FAILED);
                r.setDetail("blocked by failed: " + String.join(",", badDeps));
                pending.remove(name);
            } else {
                anyProgressPossible = true;
            }
        }
        return anyProgressPossible || !running.isEmpty() || cancelAll;
    }

    private String waitAny(Map<String, Future<?>> running) {
        while (true) {
            for (Map.Entry<String, Future<?>> e : running.entrySet()) {
                if (e.getValue().isDone()) {
                    return e.getKey();
                }
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return running.keySet().iterator().next();
            }
        }
    }

    // -------------------------------------------------------------- execution

    private void executeTarget(TargetDefinition def, String fingerprint, TargetResult result) {
        long start = System.nanoTime();
        String name = def.getName();
        Path staging = outputRoot.resolve(".staging").resolve(name);
        try {
            deleteRecursively(staging);
            Files.createDirectories(staging);

            // 1) cache lookup (verified); corrupt entries fall back to execution.
            //    Forced reruns bypass the cache and always execute the action.
            boolean forced = result.getReason() == DecisionReason.FORCED_RERUN;
            if (!forced && config.isCacheEnabled() && cache != null) {
                DecisionReason decisionReason = result.getReason();
                CacheLookup lookup = cache.lookup(fingerprint);
                if (lookup.status() == CacheLookup.Status.HIT) {
                    if (restoreFromCache(lookup.entry(), staging)) {
                        commitOutputs(def, staging);
                        commitState(def, fingerprint);
                        result.setStatus(TargetStatus.CACHE_HIT);
                        result.setReason(DecisionReason.CACHE_RESTORED);
                        result.setDetail("decision=" + decisionReason);
                        return;
                    }
                    result.setReason(DecisionReason.CACHE_CORRUPT);
                } else if (lookup.status() == CacheLookup.Status.CORRUPT) {
                    result.setReason(DecisionReason.CACHE_CORRUPT);
                }
            }

            // 2) execute with retries and timeout, inside exclusive resources
            runWithRetries(def, staging, result);

            // 3) commit outputs atomically, then persist state (recovery point)
            commitOutputs(def, staging);
            commitState(def, fingerprint);

            // 4) populate cache for future runs
            if (config.isCacheEnabled() && cache != null) {
                Map<String, Path> outputs = new LinkedHashMap<>();
                for (String out : def.getOutputs()) {
                    outputs.put(out, outputRoot.resolve(out).normalize());
                }
                cache.put(fingerprint, outputs);
                cache.evictToSize(config.getCacheMaxEntries());
            }
            if (result.getStatus() != TargetStatus.FAILED) {
                result.setStatus(TargetStatus.EXECUTED);
                if (result.getReason() == DecisionReason.CACHE_CORRUPT
                        || result.getReason() == DecisionReason.CACHE_MISS) {
                    // keep cache-related reason; otherwise keep decision reason
                }
            }
        } catch (Exception e) {
            result.setStatus(TargetStatus.FAILED);
            if (result.getReason() != DecisionReason.TIMEOUT) {
                result.setReason(DecisionReason.ACTION_FAILED);
            }
            result.setDetail(String.valueOf(e.getMessage()));
        } finally {
            result.setDurationMs((System.nanoTime() - start) / 1_000_000);
            try {
                deleteRecursively(staging);
            } catch (IOException ignored) {
                // best effort cleanup of staging
            }
        }
    }

    private void runWithRetries(TargetDefinition def, Path staging, TargetResult result)
            throws Exception {
        int retries = def.getRetries() != null ? def.getRetries() : config.getDefaultRetries();
        long timeoutMs = def.getTimeoutMs() != null ? def.getTimeoutMs() : config.getDefaultTimeoutMs();
        Action action = actionFactory.create(def.getAction());
        Exception last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                resourceManager.acquire(def.getResources());
                try {
                    runWithTimeout(def, staging, action, timeoutMs);
                } finally {
                    resourceManager.release(def.getResources());
                }
                return;
            } catch (ActionTimeoutException e) {
                result.setReason(DecisionReason.TIMEOUT);
                last = e;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("action failed");
    }

    /** Runs the action on a dedicated thread so a timeout can interrupt it. */
    private void runWithTimeout(TargetDefinition def, Path staging, Action action, long timeoutMs)
            throws Exception {
        ActionContext ctx = new ActionContext(def, inputSource, staging);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                action.run(ctx);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "action-" + def.getName());
        worker.setDaemon(true);
        worker.start();
        worker.join(timeoutMs);
        if (worker.isAlive()) {
            worker.interrupt();
            throw new ActionTimeoutException("timed out after " + timeoutMs + "ms");
        }
        if (failure.get() != null) {
            Throwable t = failure.get();
            if (t instanceof Exception e) {
                throw e;
            }
            throw new IllegalStateException(t);
        }
        // Verify all declared outputs were produced in staging.
        for (String out : def.getOutputs()) {
            if (!Files.isRegularFile(staging.resolve(out).normalize())) {
                throw new IOException("action did not produce declared output: " + out);
            }
        }
    }

    private boolean restoreFromCache(CacheEntry entry, Path staging) {
        try {
            return cache.restore(entry, staging);
        } catch (IOException e) {
            return false;
        }
    }

    /** Move staged outputs into the output tree; failures clean up staging. */
    private void commitOutputs(TargetDefinition def, Path staging) throws IOException {
        for (String out : def.getOutputs()) {
            Path src = staging.resolve(out).normalize();
            Path dst = outputRoot.resolve(out).normalize();
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void commitState(TargetDefinition def, String fingerprint) throws IOException {
        TargetState state = new TargetState();
        state.setName(def.getName());
        state.setFingerprint(fingerprint);
        state.setCommitted(true);
        state.setFinishedAtEpochMs(System.currentTimeMillis());
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String out : def.getOutputs()) {
            Path file = outputRoot.resolve(out).normalize();
            hashes.put(out, Fingerprint.ofBytes(Files.readAllBytes(file)));
        }
        state.setOutputHashes(hashes);
        stateStore.commit(state);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static final class ActionTimeoutException extends Exception {
        ActionTimeoutException(String message) { super(message); }
    }

}
