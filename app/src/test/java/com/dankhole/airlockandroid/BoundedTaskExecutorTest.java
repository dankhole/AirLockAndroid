package com.dankhole.airlockandroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BoundedTaskExecutorTest {
    @Test
    public void rejectsWorkWhenEveryWorkerIsBlocked() throws InterruptedException {
        BoundedTaskExecutor executor = new BoundedTaskExecutor(
                2,
                1_000L,
                Executors.defaultThreadFactory()
        );
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blockingTask = () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };

        try {
            assertTrue(executor.tryExecute(blockingTask));
            assertTrue(executor.tryExecute(blockingTask));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(executor.tryExecute(() -> { }));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void acceptsNewWorkAfterAWorkerReturns() throws InterruptedException {
        BoundedTaskExecutor executor = new BoundedTaskExecutor(
                1,
                1_000L,
                Executors.defaultThreadFactory()
        );
        CountDownLatch firstFinished = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        try {
            assertTrue(executor.tryExecute(firstFinished::countDown));
            assertTrue(firstFinished.await(2, TimeUnit.SECONDS));
            assertTrue(awaitAccepted(executor, secondFinished::countDown));
            assertTrue(secondFinished.await(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean awaitAccepted(BoundedTaskExecutor executor, Runnable task)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadlineNanos) {
            if (executor.tryExecute(task)) {
                return true;
            }
            Thread.sleep(5L);
        }
        return false;
    }
}
