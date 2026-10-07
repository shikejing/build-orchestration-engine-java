package com.github.shikejing.buildorchestrationenginejava.engine.exec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Guards named exclusive resources. Actions declaring the same resource
 * never run concurrently. Locks are acquired in sorted name order to
 * avoid deadlocks between actions needing multiple resources.
 */
public class ResourceManager {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public void acquire(Collection<String> resources) throws InterruptedException {
        List<ReentrantLock> acquired = new ArrayList<>();
        try {
            for (String name : new TreeSet<>(resources)) {
                ReentrantLock lock = locks.computeIfAbsent(name, n -> new ReentrantLock(true));
                lock.lockInterruptibly();
                acquired.add(lock);
            }
        } catch (InterruptedException e) {
            for (ReentrantLock lock : acquired) {
                lock.unlock();
            }
            throw e;
        }
    }

    public void release(Collection<String> resources) {
        for (String name : new TreeSet<>(resources)) {
            ReentrantLock lock = locks.get(name);
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
