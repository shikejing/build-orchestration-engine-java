package com.github.shikejing.buildorchestrationenginejava.execution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class ResourceManager {
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final Map<String, Long> owners = new HashMap<>();
    private long nextTicket;

    public void acquire(Set<String> resources) {
        Set<String> normalized = normalize(resources);
        long ticket = nextTicket++;
        lock.lock();
        try {
            while (!available(normalized)) {
                changed.await();
            }
            for (String resource : normalized) {
                owners.put(resource, ticket);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for exclusive resources", e);
        } finally {
            lock.unlock();
        }
    }

    public boolean tryAcquire(Set<String> resources) {
        Set<String> normalized = normalize(resources);
        long ticket = nextTicket++;
        lock.lock();
        try {
            if (!available(normalized)) {
                return false;
            }
            normalized.forEach(resource -> owners.put(resource, ticket));
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void release(Set<String> resources) {
        Set<String> normalized = normalize(resources);
        lock.lock();
        try {
            normalized.forEach(owners::remove);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private boolean available(Set<String> resources) {
        return resources.stream().noneMatch(owners::containsKey);
    }

    private Set<String> normalize(Set<String> resources) {
        Set<String> normalized = new java.util.TreeSet<>();
        if (resources != null) {
            resources.stream()
                    .filter(resource -> resource != null && !resource.isBlank())
                    .map(String::trim)
                    .forEach(normalized::add);
        }
        return normalized;
    }
}
