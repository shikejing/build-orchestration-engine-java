package com.github.shikejing.buildorchestrationenginejava.execution;

import com.github.shikejing.buildorchestrationenginejava.cache.ArtifactCache;
import com.github.shikejing.buildorchestrationenginejava.cache.CacheEntry;
import com.github.shikejing.buildorchestrationenginejava.cache.CacheKey;
import com.github.shikejing.buildorchestrationenginejava.cache.CacheKeyFactory;
import com.github.shikejing.buildorchestrationenginejava.cache.CacheLookupResult;
import com.github.shikejing.buildorchestrationenginejava.cache.LocalArtifactCache;
import com.github.shikejing.buildorchestrationenginejava.config.EngineConfig;
import com.github.shikejing.buildorchestrationenginejava.config.FailureStrategy;
import com.github.shikejing.buildorchestrationenginejava.fingerprint.FingerprintService;
import com.github.shikejing.buildorchestrationenginejava.fingerprint.TargetFingerprints;
import com.github.shikejing.buildorchestrationenginejava.graph.ExecutionPlan;
import com.github.shikejing.buildorchestrationenginejava.graph.ExecutionPlanner;
import com.github.shikejing.buildorchestrationenginejava.model.BuildDescription;
import com.github.shikejing.buildorchestrationenginejava.model.BuildResult;
import com.github.shikejing.buildorchestrationenginejava.model.DecisionReason;
import com.github.shikejing.buildorchestrationenginejava.model.TargetDeclaration;
import com.github.shikejing.buildorchestrationenginejava.model.TargetResult;
import com.github.shikejing.buildorchestrationenginejava.model.TargetStatus;
import com.github.shikejing.buildorchestrationenginejava.parser.BuildDescriptionParser;
import com.github.shikejing.buildorchestrationenginejava.parser.JsonBuildDescriptionParser;
import com.github.shikejing.buildorchestrationenginejava.state.ExecutionJournal;
import com.github.shikejing.buildorchestrationenginejava.state.ExecutionState;
import com.github.shikejing.buildorchestrationenginejava.state.StateStore;
import com.github.shikejing.buildorchestrationenginejava.state.TargetStateRecord;
import com.github.shikejing.buildorchestrationenginejava.state.TransactionPhase;
import com.github.shikejing.buildorchestrationenginejava.state.TransactionRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class BuildEngine implements AutoCloseable {
    private final Path workspace;
    private final EngineConfig config;
    private final ToolchainRegistry registry;
    private final BuildDescriptionParser parser;
    private final ExecutionPlanner planner;
    private final FingerprintService fingerprintService;
    private final CacheKeyFactory cacheKeyFactory;
    private final ArtifactCache cache;
    private final StateStore stateStore;
    private final ExecutionJournal journal;
    private final AtomicArtifactPublisher publisher;
    private final ResourceManager resourceManager;
    private final Path stagingRoot;
    private final ExecutorService executor;
    private final IncrementalDecider decider;
    private volatile Thread schedulerThread;
    private volatile boolean closed;

    public BuildEngine(Path workspace, EngineConfig config, ToolchainRegistry registry) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace must not be null");
        }
        this.workspace = workspace.toAbsolutePath().normalize();
        this.config = config == null ? EngineConfig.defaults() : config;
        this.registry = registry == null ? new ToolchainRegistry() : registry;
        this.parser = new JsonBuildDescriptionParser();
        this.planner = new ExecutionPlanner();
        this.fingerprintService = new FingerprintService();
        this.cacheKeyFactory = new CacheKeyFactory(fingerprintService);
        Path engineRoot = this.workspace.resolve(".build-engine");
        this.stagingRoot = engineRoot.resolve("staging");
        this.stateStore = new StateStore(engineRoot.resolve("state.json"));
        this.journal = new ExecutionJournal(engineRoot.resolve("journal.json"));
        this.cache = this.config.cacheEnabled() ? new LocalArtifactCache(engineRoot.resolve("cache")) : null;
        this.publisher = new AtomicArtifactPublisher();
        this.resourceManager = new ResourceManager();
        this.executor = Executors.newFixedThreadPool(this.config.concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "build-engine-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.decider = new IncrementalDecider();
        try {
            Files.createDirectories(stagingRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create engine workspace", e);
        }
    }

    public BuildResult run(BuildDescription description) {
        ExecutionPlan plan = planner.plan(description, registry.names());
        Map<String, TargetDeclaration> declarations = new LinkedHashMap<>();
        for (TargetDeclaration target : description.targets()) {
            declarations.put(target.name(), target);
        }
        return executePlan(plan, declarations);
    }

    public BuildResult run(Path descriptionFile) {
        return run(parser.parse(descriptionFile));
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        Thread thread = schedulerThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    private BuildResult executePlan(ExecutionPlan plan, Map<String, TargetDeclaration> declarations) {
        schedulerThread = Thread.currentThread();
        List<String> order = plan.targetNamesInOrder();
        Map<String, Integer> indexes = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> remaining = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            indexes.put(order.get(i), i);
            dependents.put(order.get(i), new ArrayList<>());
            remaining.put(order.get(i), declarations.get(order.get(i)).dependsOn().size());
        }
        for (String name : order) {
            for (String dependency : declarations.get(name).dependsOn()) {
                dependents.get(dependency).add(name);
            }
        }

        Map<String, TargetResult> results = new LinkedHashMap<>();
        Map<String, TargetStateRecord> currentRecords = new ConcurrentHashMap<>(stateStore.load().targets());
        ReentrantLock scheduleLock = new ReentrantLock(true);
        Condition changed = scheduleLock.newCondition();
        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.comparingInt(name -> indexes.get(name)));
        Set<String> started = new HashSet<>();
        Set<String> finished = new HashSet<>();
        boolean[] failed = {false};
        boolean[] interrupted = {false};
        int[] running = {0};
        for (String name : order) {
            results.put(name, null);
            if (remaining.get(name) == 0) {
                ready.add(name);
            }
        }

        while (true) {
            scheduleLock.lock();
            try {
                while (true) {
                    boolean allFinished = finished.size() == order.size();
                    if (allFinished || interrupted[0]) {
                        break;
                    }
                    String selected = selectReady(ready, started, indexes, failed[0], interrupted[0]);
                    if (selected != null && running[0] < config.concurrency()) {
                        resourceManager.acquire(new HashSet<>(declarations.get(selected).exclusiveResources()));
                        ready.remove(selected);
                        started.add(selected);
                        running[0]++;
                        submitTarget(selected, declarations.get(selected), indexes.get(selected), results,
                                currentRecords, () -> onTargetComplete(selected, dependents, remaining, ready,
                                        results, scheduleLock, changed, finished, running, failed));
                        continue;
                    }
                    if (running[0] == 0) {
                        break;
                    }
                    changed.await();
                }
            } catch (InterruptedException e) {
                interrupted[0] = true;
                if (closed) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                scheduleLock.unlock();
            }

            boolean complete;
            scheduleLock.lock();
            try {
                boolean noRunnableReady = selectReady(ready, started, indexes, failed[0], interrupted[0]) == null;
                complete = finished.size() == order.size() || interrupted[0]
                        || (running[0] == 0 && noRunnableReady);
            } finally {
                scheduleLock.unlock();
            }
            if (complete) {
                if (interrupted[0] && closed) {
                    executor.shutdownNow();
                    try {
                        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException awaitInterrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                break;
            }
        }

        finishSkipped(order, declarations, results, interrupted[0]);
        List<TargetResult> finalResults = order.stream()
                .map(results::get)
                .map(result -> result == null
                        ? new TargetResult("unknown", TargetStatus.CANCELLED, DecisionReason.CANCELLED, Duration.ZERO, 0, false, null, null)
                        : result)
                .toList();
        boolean success = finalResults.stream().noneMatch(result ->
                result.status() == TargetStatus.FAILED || result.status() == TargetStatus.SKIPPED
                        || result.status() == TargetStatus.CANCELLED);
        pruneStateToCurrentDescription(order, currentRecords);
        if (cache != null) {
            cache.prune(config.retention());
        }
        return new BuildResult(success && !interrupted[0], interrupted[0], finalResults);
    }

    private String selectReady(PriorityQueue<String> ready, Set<String> started,
                               Map<String, Integer> indexes,
                               boolean failed, boolean interrupted) {
        if (interrupted) {
            return null;
        }
        if (failed && config.failureStrategy() == FailureStrategy.CANCEL) {
            return null;
        }
        String selected = ready.peek();
        return selected != null && !started.contains(selected) ? selected : null;
    }


    private void submitTarget(String name, TargetDeclaration declaration, int index,
                              Map<String, TargetResult> results,
                              Map<String, TargetStateRecord> currentRecords, Runnable onComplete) {
        executor.submit(() -> {
            try {
                TargetResult result = executeTarget(name, declaration, index, currentRecords);
                synchronized (results) {
                    results.put(name, result);
                }
            } finally {
                resourceManager.release(new HashSet<>(declaration.exclusiveResources()));
                onComplete.run();
            }
        });
    }

    private void onTargetComplete(String name, Map<String, List<String>> dependents,
                                  Map<String, Integer> remaining, PriorityQueue<String> ready,
                                  Map<String, TargetResult> results, ReentrantLock lock,
                                  Condition changed, Set<String> finished, int[] running,
                                  boolean[] failed) {
        lock.lock();
        try {
            finished.add(name);
            running[0]--;
            TargetResult result;
            synchronized (results) {
                result = results.get(name);
            }
            if (result != null && result.status() == TargetStatus.FAILED) {
                failed[0] = true;
            }
            boolean dependencySucceeded = result != null && (result.status() == TargetStatus.SUCCESS
                    || result.status() == TargetStatus.CACHED
                    || result.status() == TargetStatus.UP_TO_DATE);
            if (dependencySucceeded && (!failed[0] || config.failureStrategy() == FailureStrategy.CONTINUE)) {
                for (String dependent : dependents.get(name)) {
                    int count = remaining.merge(dependent, -1, Integer::sum);
                    if (count == 0) {
                        ready.add(dependent);
                    }
                }
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private TargetResult executeTarget(String name, TargetDeclaration target, int index,
                                       Map<String, TargetStateRecord> currentRecords) {
        Instant startedAt = Instant.now();
        int attempts = 0;
        try {
            TargetFingerprints fingerprints = calculateFingerprints(name, target, currentRecords);
            CacheKey cacheKey = cacheKeyFactory.create(target, fingerprints);
            TransactionRecord pending = journal.find(name);
            TargetResult recovered = recoverPending(name, target, index, pending, fingerprints,
                    cacheKey, currentRecords, startedAt);
            if (recovered != null) {
                return recovered;
            }

            TargetStateRecord previous = currentRecords.get(name);
            IncrementalDecision decision = decider.decide(previous, fingerprints, config.forceRebuild());
            if (!decision.execute()) {
                return new TargetResult(name, TargetStatus.UP_TO_DATE, DecisionReason.UP_TO_DATE,
                        elapsed(startedAt), 0, false, previous.outputFingerprint(), null);
            }

            if (!config.forceRebuild() && cache != null && decision.reason() != DecisionReason.OUTPUT_MODIFIED) {
                CacheLookupResult lookup = cache.inspect(cacheKey, name);
                if (lookup.corrupted()) {
                    TargetResult cached = runAndPublish(name, target, index, fingerprints, cacheKey,
                            currentRecords, startedAt, DecisionReason.CORRUPT_CACHE_FALLBACK, 1);
                    return cached;
                }
                if (lookup.entry().isPresent()) {
                    return restoreAndPublish(name, target, index, lookup.entry().get(), fingerprints,
                            cacheKey, currentRecords, startedAt);
                }
            }
            return runAndPublish(name, target, index, fingerprints, cacheKey, currentRecords,
                    startedAt, decision.reason(), 1);
        } catch (Exception e) {
            if (closed || Thread.currentThread().isInterrupted() || e instanceof InterruptedException) {
                TransactionRecord record = journal.find(name);
                if (record != null && record.phase() == TransactionPhase.RUNNING
                        && record.stagingDirectory() != null) {
                    try {
                        deleteRecursively(Path.of(record.stagingDirectory()));
                    } catch (IOException ignored) {
                    }
                }
                if (record == null || record.phase() == TransactionPhase.RUNNING) {
                    journal.complete(name);
                }
                Thread.currentThread().interrupt();
                return new TargetResult(name, TargetStatus.CANCELLED, DecisionReason.INTERRUPTED,
                        elapsed(startedAt), Math.max(attempts, 1), false, null, "Build was interrupted");
            }
            return new TargetResult(name, TargetStatus.FAILED, DecisionReason.NONE,
                    elapsed(startedAt), Math.max(attempts, 1), false, null, rootMessage(e));
        }
    }

    private TargetResult runAndPublish(String name, TargetDeclaration target, int index,
                                       TargetFingerprints fingerprints, CacheKey cacheKey,
                                       Map<String, TargetStateRecord> currentRecords,
                                       Instant startedAt, DecisionReason initialReason, int initialAttempt)
            throws Exception {
        int maxAttempts = 1 + resolveRetries(target);
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Path staging = stagingDirectory(index);
            deleteRecursively(staging);
            Files.createDirectories(staging);
            journal.begin(name, cacheKey.value(), staging);
            try {
                executeActionOnce(target, staging, attempt);
                TargetFingerprints produced = fingerprintsWithOutputs(target, currentRecords, staging);
                CacheKey producedKey = cacheKeyFactory.create(target, produced);
                if (cache != null) {
                    cache.store(producedKey, name, staging, target.outputs());
                }
                publishAndCommit(name, target, staging, produced, producedKey, currentRecords);
                TargetStatus status = initialReason == DecisionReason.FORCED_REBUILD
                        ? TargetStatus.SUCCESS : TargetStatus.SUCCESS;
                return new TargetResult(name, status, initialReason, elapsed(startedAt), attempt, false,
                        produced.outputFingerprint(), null);
            } catch (Exception e) {
                last = e;
                deleteRecursively(staging);
                journal.begin(name, cacheKey.value(), staging);
                if (closed || Thread.currentThread().isInterrupted() || e instanceof InterruptedException) {
                    break;
                }
            }
        }
        throw last == null ? new IllegalStateException("Target failed without an exception") : last;
    }

    private TargetResult restoreAndPublish(String name, TargetDeclaration target, int index,
                                           CacheEntry entry, TargetFingerprints current,
                                           CacheKey cacheKey,
                                           Map<String, TargetStateRecord> currentRecords,
                                           Instant startedAt) throws IOException {
        Path staging = stagingDirectory(index);
        deleteRecursively(staging);
        Files.createDirectories(staging);
        cache.restore(entry, staging);
        TargetFingerprints produced = fingerprintsWithOutputs(target, currentRecords, staging);
        CacheKey restoredKey = new CacheKey(entry.key(), cacheKey.components());
        publishAndCommit(name, target, staging, produced, restoredKey, currentRecords);
        return new TargetResult(name, TargetStatus.CACHED, DecisionReason.CACHE_HIT,
                elapsed(startedAt), 0, true, produced.outputFingerprint(), null);
    }

    private TargetResult recoverPending(String name, TargetDeclaration target, int index,
                                        TransactionRecord pending, TargetFingerprints current,
                                        CacheKey cacheKey,
                                        Map<String, TargetStateRecord> currentRecords,
                                        Instant startedAt) throws IOException {
        if (pending == null) {
            return null;
        }
        if (pending.phase() == TransactionPhase.PUBLISHED
                && fingerprintsMatchPending(pending, current, cacheKey, workspace, target)) {
            TargetFingerprints verified = fingerprintsWithOutputs(target, currentRecords, workspace);
            CacheKey key = new CacheKey(pending.cacheKey(), cacheKey.components());
            commitState(name, pending, verified, key, currentRecords);
            journal.complete(name);
            return new TargetResult(name, TargetStatus.SUCCESS, DecisionReason.RECOVERED_PUBLISHED,
                    elapsed(startedAt), 0, false, verified.outputFingerprint(), null);
        }
        if (pending.phase() == TransactionPhase.STAGED) {
            Path staging = Path.of(pending.stagingDirectory());
            if (fingerprintsMatchPending(pending, current, cacheKey, staging, target)) {
                TargetFingerprints verified = fingerprintsWithOutputs(target, currentRecords, staging);
                CacheKey key = new CacheKey(pending.cacheKey(), cacheKey.components());
                publisher.publish(staging, workspace, target.outputs());
                TransactionRecord published = journal.published(new TransactionRecord(
                        name, TransactionPhase.PUBLISHED, key.value(), staging.toString(),
                        pending.outputHashes(), Instant.now(), verified.inputFingerprint(),
                        verified.outputFingerprint(), verified.actionFingerprint(),
                        verified.dependencyFingerprint()));
                commitState(name, published, verified, key, currentRecords);
                journal.complete(name);
                return new TargetResult(name, TargetStatus.SUCCESS, DecisionReason.RECOVERED_PUBLISHED,
                        elapsed(startedAt), 0, false, verified.outputFingerprint(), null);
            }
        }
        deleteRecursively(Path.of(pending.stagingDirectory()));
        journal.complete(name);
        return null;
    }

    private void publishAndCommit(String name, TargetDeclaration target, Path staging,
                                  TargetFingerprints fingerprints, CacheKey cacheKey,
                                  Map<String, TargetStateRecord> currentRecords) throws IOException {
        TransactionRecord record = journal.begin(name, cacheKey.value(), staging);
        Map<String, String> hashes = outputHashes(staging, target.outputs());
        record = new TransactionRecord(name, TransactionPhase.STAGED, cacheKey.value(),
                staging.toString(), hashes, Instant.now(), fingerprints.inputFingerprint(),
                fingerprints.outputFingerprint(), fingerprints.actionFingerprint(),
                fingerprints.dependencyFingerprint());
        record = journal.staged(record);
        publisher.publish(staging, workspace, target.outputs());
        TargetFingerprints publishedFingerprints = fingerprintsWithOutputs(target, currentRecords, workspace);
        if (!publishedFingerprints.outputFingerprint().equals(fingerprints.outputFingerprint())) {
            throw new IOException("Published output fingerprint differs from staged output");
        }
        record = journal.published(new TransactionRecord(name, TransactionPhase.PUBLISHED,
                cacheKey.value(), staging.toString(), hashes, Instant.now(),
                publishedFingerprints.inputFingerprint(), publishedFingerprints.outputFingerprint(),
                publishedFingerprints.actionFingerprint(), publishedFingerprints.dependencyFingerprint()));
        commitState(name, record, publishedFingerprints, cacheKey, currentRecords);
        journal.complete(name);
        deleteRecursively(staging);
    }

    private void commitState(String name, TransactionRecord record, TargetFingerprints fingerprints,
                             CacheKey cacheKey, Map<String, TargetStateRecord> currentRecords) {
        TargetStateRecord stateRecord = new TargetStateRecord(
                name,
                fingerprints.inputFingerprint(),
                fingerprints.outputFingerprint(),
                fingerprints.actionFingerprint(),
                fingerprints.dependencyFingerprint(),
                cacheKey.value(),
                Instant.now()
        );
        synchronized (stateStore) {
            Map<String, TargetStateRecord> merged = new LinkedHashMap<>(currentRecords);
            merged.put(name, stateRecord);
            currentRecords.put(name, stateRecord);
            stateStore.save(new ExecutionState(merged));
        }
    }

    private void executeActionOnce(TargetDeclaration target, Path staging, int attempt) throws Exception {
        Toolchain toolchain = registry.find(target.toolchain())
                .orElseThrow(() -> new IllegalStateException("Unknown toolchain " + target.toolchain()));
        TaskExecutionContext context = new DefaultTaskExecutionContext(
                target.name(), workspace, staging, target.inputs(), target.outputs(),
                target.arguments(), target.environment());
        Duration timeout = target.timeout() != null ? target.timeout() : config.defaultTimeout();
        ExecutorService one = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "target-" + target.name() + "-attempt-" + attempt);
            thread.setDaemon(true);
            return thread;
        });
        Future<?> future = one.submit(() -> {
            try {
                toolchain.execute(context);
            } catch (Exception e) {
                throw e instanceof RuntimeException runtimeException
                        ? runtimeException : new RuntimeException(e);
            }
        });
        try {
            if (timeout == null) {
                future.get();
            } else {
                try {
                    future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(true);
                    throw new IOException("Action timed out after " + timeout, e);
                }
            }
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException
                    && runtimeException.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw cause instanceof Exception exception ? exception : e;
        } finally {
            one.shutdownNow();
        }
        for (String output : target.outputs()) {
            Path file = staging.resolve(output).normalize();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Toolchain did not produce required output: " + output);
            }
        }
    }

    private TargetFingerprints calculateFingerprints(String name, TargetDeclaration target,
                                                     Map<String, TargetStateRecord> currentRecords) {
        String input = fingerprintService.hashFiles(workspace, target.inputs());
        String action = actionFingerprint(target);
        String dependency = dependencyFingerprint(target, currentRecords);
        boolean outputsPresent = allOutputsPresent(workspace, target.outputs());
        String output = fingerprintService.hashFiles(workspace, target.outputs());
        TargetFingerprints fingerprints = new TargetFingerprints(input, output, action, dependency, null, outputsPresent);
        return new TargetFingerprints(input, output, action, dependency,
                cacheKeyFactory.create(target, fingerprints).value(), outputsPresent);
    }

    private TargetFingerprints fingerprintsWithOutputs(TargetDeclaration target,
                                                       Map<String, TargetStateRecord> currentRecords,
                                                       Path outputRoot) {
        String input = fingerprintService.hashFiles(workspace, target.inputs());
        String action = actionFingerprint(target);
        String dependency = dependencyFingerprint(target, currentRecords);
        boolean present = allOutputsPresent(outputRoot, target.outputs());
        String output = fingerprintService.hashFiles(outputRoot, target.outputs());
        TargetFingerprints fingerprints = new TargetFingerprints(input, output, action, dependency, null, present);
        return new TargetFingerprints(input, output, action, dependency,
                cacheKeyFactory.create(target, fingerprints).value(), present);
    }

    private String dependencyFingerprint(TargetDeclaration target, Map<String, TargetStateRecord> records) {
        StringBuilder canonical = new StringBuilder();
        target.dependsOn().stream().sorted().forEach(dependency -> {
            TargetStateRecord record = records.get(dependency);
            canonical.append(dependency)
                    .append('=')
                    .append(record == null ? "missing" : record.outputFingerprint())
                    .append('\n');
        });
        return fingerprintService.hashText(canonical.toString());
    }

    private String actionFingerprint(TargetDeclaration target) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("toolchain=").append(target.toolchain()).append('\n');
        canonical.append("arguments=").append(String.join("\u0000", target.arguments())).append('\n');
        new java.util.TreeMap<>(target.environment()).forEach((key, value) ->
                canonical.append("env:").append(key).append('=').append(value).append('\n'));
        canonical.append("outputs=").append(String.join("\u0000", target.outputs())).append('\n');
        canonical.append("resources=").append(String.join("\u0000",
                target.exclusiveResources().stream().sorted().toList())).append('\n');
        return fingerprintService.hashText(canonical.toString());
    }

    private boolean allOutputsPresent(Path root, List<String> outputs) {
        return outputs.stream().allMatch(output -> Files.isRegularFile(root.resolve(output), LinkOption.NOFOLLOW_LINKS));
    }

    private Map<String, String> outputHashes(Path root, List<String> outputs) throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String output : outputs.stream().sorted().toList()) {
            hashes.put(output.replace('\\', '/'), fingerprintService.fingerprintFile(root, output).sha256());
        }
        return Map.copyOf(hashes);
    }

    private boolean fingerprintsMatchPending(TransactionRecord pending, TargetFingerprints current,
                                             CacheKey cacheKey, Path outputRoot,
                                             TargetDeclaration target) throws IOException {
        if (!cacheKey.value().equals(pending.cacheKey())) {
            return false;
        }
        if (!safeEquals(current.inputFingerprint(), pending.inputFingerprint())
                || !safeEquals(current.actionFingerprint(), pending.actionFingerprint())
                || !safeEquals(current.dependencyFingerprint(), pending.dependencyFingerprint())) {
            return false;
        }
        if (!allOutputsPresent(outputRoot, target.outputs())) {
            return false;
        }
        Map<String, String> actual = outputHashes(outputRoot, target.outputs());
        return actual.equals(pending.outputHashes());
    }

    private boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }

    private int resolveRetries(TargetDeclaration target) {
        return target.retries() == null ? config.defaultRetries() : target.retries();
    }

    private Duration elapsed(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = current.getMessage();
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
        }
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private Path stagingDirectory(int index) {
        return stagingRoot.resolve("target-" + index);
    }

    private void finishSkipped(List<String> order, Map<String, TargetDeclaration> declarations,
                               Map<String, TargetResult> results, boolean interrupted) {
        Set<String> blocked = new HashSet<>();
        for (String name : order) {
            TargetResult result = results.get(name);
            if (result != null && (result.status() == TargetStatus.FAILED
                    || result.status() == TargetStatus.SKIPPED
                    || result.status() == TargetStatus.CANCELLED)) {
                blocked.add(name);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String name : order) {
                if (results.get(name) != null || blocked.contains(name)) {
                    continue;
                }
                boolean dependencyBlocked = declarations.get(name).dependsOn().stream().anyMatch(blocked::contains);
                if (dependencyBlocked) {
                    blocked.add(name);
                    changed = true;
                }
            }
        }
        for (String name : order) {
            if (results.get(name) == null) {
                TargetStatus status = interrupted ? TargetStatus.CANCELLED
                        : blocked.contains(name) ? TargetStatus.SKIPPED : TargetStatus.CANCELLED;
                DecisionReason reason = interrupted ? DecisionReason.INTERRUPTED
                        : status == TargetStatus.SKIPPED ? DecisionReason.DEPENDENCY_FAILED
                        : DecisionReason.CANCELLED;
                results.put(name, new TargetResult(name, status, reason, Duration.ZERO, 0, false, null, null));
            }
        }
    }

    private void pruneStateToCurrentDescription(List<String> currentTargets,
                                                 Map<String, TargetStateRecord> currentRecords) {
        Map<String, TargetStateRecord> filtered = new LinkedHashMap<>();
        for (String target : currentTargets) {
            TargetStateRecord record = currentRecords.get(target);
            if (record != null) {
                filtered.put(target, record);
            }
        }
        synchronized (stateStore) {
            if (!filtered.equals(currentRecords)) {
                currentRecords.clear();
                currentRecords.putAll(filtered);
                stateStore.save(new ExecutionState(filtered));
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Path workspace;
        private EngineConfig config;
        private final ToolchainRegistry registry = new ToolchainRegistry()
                .register(new CopyFileToolchain())
                .register(new WriteTextToolchain())
                .register(new CommandToolchain());

        public Builder workspace(Path workspace) {
            this.workspace = workspace;
            return this;
        }

        public Builder config(EngineConfig config) {
            this.config = config;
            return this;
        }

        public Builder toolchain(Toolchain toolchain) {
            registry.register(toolchain);
            return this;
        }

        public BuildEngine build() {
            return new BuildEngine(workspace, config, registry);
        }
    }
}
