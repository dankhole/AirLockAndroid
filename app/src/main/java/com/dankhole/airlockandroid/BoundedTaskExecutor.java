package com.dankhole.airlockandroid;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class BoundedTaskExecutor {
    private final ThreadPoolExecutor executor;

    BoundedTaskExecutor(int maximumWorkers, long idleTimeoutMs, ThreadFactory threadFactory) {
        if (maximumWorkers < 1) {
            throw new IllegalArgumentException("maximumWorkers must be positive");
        }
        executor = new ThreadPoolExecutor(
                0,
                maximumWorkers,
                Math.max(1L, idleTimeoutMs),
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    boolean tryExecute(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    void shutdownNow() {
        executor.shutdownNow();
    }
}
