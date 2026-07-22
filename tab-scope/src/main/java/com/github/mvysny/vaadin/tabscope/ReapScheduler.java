package com.github.mvysny.vaadin.tabscope;

import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.shared.Registration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Schedules the one-shot orphan reap that fires {@link TabScope#CLEANUP_DURATION_MS} after a scope
 * orphans, so a sole last tab is reaped without waiting for another request. The production
 * implementation ({@link ExecutorBacked}) owns a daemon {@link ScheduledExecutorService}; the
 * interface exists <em>solely</em> so tests can swap in a manual scheduler and fire (or assert
 * cancellation of) the reap deterministically, without real sleeps (see {@code TabScopeLifecycleTest}).
 */
interface ReapScheduler extends AutoCloseable {
    /**
     * @param task    the reap, to run after {@code delayMs}
     * @param delayMs delay in milliseconds
     * @return a handle whose {@link Registration#remove()} prevents the task if it hasn't run yet
     */
    @NotNull
    Registration schedule(@NotNull Runnable task, long delayMs);

    /**
     * Releases scheduler resources (e.g. the daemon thread) on {@link VaadinService} destroy.
     * Narrows {@link AutoCloseable#close()} to throw nothing.
     */
    @Override
    void close();

    /**
     * Production {@link ReapScheduler}: owns a single daemon executor, created lazily on first
     * schedule and shut down (and forgotten, so a later schedule recreates it) on service destroy —
     * this way a servlet-container redeploy doesn't leak the thread or its classloader.
     */
    final class ExecutorBacked implements ReapScheduler {
        @Nullable
        private ScheduledExecutorService executor;

        @NotNull
        @Override
        public synchronized Registration schedule(@NotNull Runnable task, long delayMs) {
            if (executor == null) {
                executor = Executors.newSingleThreadScheduledExecutor(r -> {
                    final Thread t = new Thread(r, "tab-scope-reaper");
                    t.setDaemon(true);
                    return t;
                });
            }
            final ScheduledFuture<?> future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        @Override
        public synchronized void close() {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }
}
