package com.github.mvysny.vaadin.tabscope;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Test double for {@link TabScope.ReapScheduler}: instead of scheduling on a real executor, it
 * captures the reap tasks so a test can fire them (or observe that they were cancelled) at a
 * deterministic moment, with no real sleeps — matching the suite's {@code CLEANUP_DURATION_MS}
 * seam philosophy. Install it via {@code TabScope.reapScheduler = ...} in {@code @BeforeEach} and
 * clear it in {@code @AfterEach}; while installed, no real reaper thread is ever created.
 */
final class ManualReapScheduler implements TabScope.ReapScheduler {
    private final List<Runnable> pending = new ArrayList<>();

    @NotNull
    @Override
    public Cancellation schedule(@NotNull Runnable task, long delayMs) {
        pending.add(task);
        return () -> pending.remove(task);
    }

    /** Number of armed-but-not-yet-fired reaps; 0 after every armed reap has fired or been cancelled. */
    int pendingCount() {
        return pending.size();
    }

    /** Fires every armed reap, in arm order, as if their delays had elapsed. */
    void fireAll() {
        final List<Runnable> snapshot = new ArrayList<>(pending);
        pending.clear();
        snapshot.forEach(Runnable::run);
    }
}
