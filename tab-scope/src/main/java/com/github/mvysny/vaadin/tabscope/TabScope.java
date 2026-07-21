package com.github.mvysny.vaadin.tabscope;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.page.ExtendedClientDetails;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.server.*;
import com.vaadin.flow.server.communication.UidlRequestHandler;
import com.vaadin.flow.shared.Registration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Stores values in a browser tab scope - all values inserted into {@link #getValues()} are preserved per browser tab.
 * The tab scope survives page reloads and navigation.
 * <br/>
 * To use this you need to:
 * <ul>
 *     <li>Call {@link #setup(SerializableConsumer)} from {@link VaadinServiceInitListener#serviceInit(ServiceInitEvent)}</li>
 *     <li>Call {@link #getCurrent()} from everywhere else from your app: from your routes and layouts etc</li>
 * </ul>
 * <h3>Vaadin 8</h3>
 * This is how the Vaadin 8 UI scope used to work. When migrating, just store your values to
 * {@link #getValues()} instead to Vaadin 8 UI; perform any initialization in the <code>tab init listener</code>,
 * passed to the {@link #setup(SerializableConsumer)}.
 */
public final class TabScope implements Serializable {
    private static final Logger log = LoggerFactory.getLogger(TabScope.class);

    @NotNull
    private final String windowName;

    private TabScope(@NotNull String windowName) {
        // prevent instantiation by the app itself.
        this.windowName = Objects.requireNonNull(windowName);
    }

    @Override
    public String toString() {
        return "TabScope{" + windowName + '}';
    }


    /**
     * Holds all tab-scoped values stored by the app.
     * Set to null when the scope has been closed.
     */
    @Nullable
    private Attributes values = new Attributes();

    /**
     * Once this many milliseconds pass since the last UI of a tab scope is closed, the tab scope is
     * considered orphaned and will be destroyed at some point. This timeout is critical:
     * on page reload, the old UI is closed first before a new UI is created,
     * creating a situation where zero UIs point to a tab scope.
     * <br/>
     * Without the timeout, the tab scope would have been closed immediately.
     * <br/>
     * Package-private and non-final <em>solely</em> so tests can shrink the grace period and
     * exercise the orphan-reaping branch (see {@code TabScopeLifecycleTest}); treat it as a
     * constant (60&nbsp;seconds) in production.
     */
    static long CLEANUP_DURATION_MS = 60 * 1000L;

    /**
     * Whether an orphaned scope is reaped promptly by the background {@link ScheduledExecutorService}
     * (feature B). {@code true} by default.
     * <br/>
     * Set to {@code false} to disable the reaper thread entirely and ride only Vaadin's default
     * UI-closing plus the request-driven sweep and session-destroy backstops — the pre-feature-B
     * behavior, where a <em>sole last tab</em>'s scope lingers until the session ends rather than
     * being reaped ~60&nbsp;s after close, and no {@code tab-scope-reaper} thread is ever created.
     * Everything else (per-tab values, {@code @TabScoped} caching, reaping while other tabs are
     * active) is unaffected. Read once per orphan, in {@code armReap()}; set it before your app
     * serves requests.
     */
    public static volatile boolean scheduledReapEnabled = true;

    /**
     * Schedules the one-shot orphan reap that fires {@link #CLEANUP_DURATION_MS} after a scope
     * orphans, so a sole last tab is reaped without waiting for another request. Production uses a
     * shared daemon {@link ScheduledExecutorService}; the seam exists <em>solely</em> so tests can
     * inject a manual scheduler and fire (or assert cancellation of) the reap deterministically,
     * without real sleeps (see {@code TabScopeLifecycleTest}).
     */
    interface ReapScheduler {
        /**
         * @param task     the reap, to run after {@code delayMs}
         * @param delayMs  delay in milliseconds
         * @return a handle whose {@link Cancellation#cancel()} prevents the task if it hasn't run yet
         */
        @NotNull
        Cancellation schedule(@NotNull Runnable task, long delayMs);

        interface Cancellation {
            void cancel();
        }
    }

    /**
     * Test seam: when non-null, used instead of the default daemon executor. Set by tests only.
     */
    @Nullable
    static ReapScheduler reapScheduler = null;

    /**
     * The shared daemon executor backing the default {@link #scheduler()}. Lazily created, and shut
     * down + nulled on {@link VaadinService} destroy so a redeploy doesn't leak the thread.
     */
    @Nullable
    private static ScheduledExecutorService reapExecutor = null;

    @NotNull
    private static synchronized ReapScheduler scheduler() {
        if (reapScheduler != null) {
            return reapScheduler;
        }
        if (reapExecutor == null) {
            reapExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "tab-scope-reaper");
                t.setDaemon(true);
                return t;
            });
        }
        final ScheduledExecutorService exec = reapExecutor;
        return (task, delayMs) -> {
            final ScheduledFuture<?> future = exec.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        };
    }

    private static synchronized void shutdownReaper() {
        if (reapExecutor != null) {
            reapExecutor.shutdownNow();
            reapExecutor = null;
        }
    }

    @NotNull
    private final Lifecycle lifecycle = new Lifecycle();

    /**
     * Tracks lifecycle of the owner tab scope.
     */
    private class Lifecycle implements Serializable {
        /**
         * A set of UIs hooked to this tab scope. Overwhelmingly contains exactly
         * one UI, but on page refresh, it may contain zero or two UIs, based on
         * the ordering of old-UI-destroy and new-UI-create events.
         * <br/>
         * This set is only used to track whether a tab scope is active.
         */
        private final Set<UI> uis = new HashSet<>();

        /**
         * UIs whose tab reported a closing unload beacon (via {@link #onUnloadBeacon(UI)}) but which
         * Flow keeps attached — the {@code @PreserveOnRefresh} case. Such a UI stays in {@link #uis}
         * (so its eventual real detach is handled normally) but does <em>not</em> count as keeping the
         * scope alive, so the grace clock can start without closing the UI. Always a subset of {@link #uis}.
         */
        private final Set<UI> beaconClosed = new HashSet<>();

        /**
         * Tracks time since when no UIs point to this tab scope.
         */
        @Nullable
        private Long orphanedSince = null;

        private boolean closed = false;

        /**
         * Handle to the pending one-shot reap armed when this scope orphaned; cancelled if a UI
         * reattaches. Transient: a scheduled task is not meaningful across passivate/activate, and
         * the request-driven sweep + session-destroy backstop still cover a deserialized scope.
         */
        @Nullable
        private transient ReapScheduler.Cancellation pendingReap = null;

        private void requireNotClosed() {
            if (closed) {
                throw new IllegalStateException("Invalid state: closed");
            }
        }

        public void add(@NotNull UI ui) {
            Objects.requireNonNull(ui);
            requireNotClosed();
            uis.add(ui);
            orphanedSince = null;
            cancelReap();
        }

        public void remove(@NotNull UI ui) {
            if (closed) {
                return;
            }
            if (!uis.remove(Objects.requireNonNull(ui))) {
                throw new IllegalStateException("Invalid state: uis doesn't contain given ui");
            }
            beaconClosed.remove(ui);
            updateOrphaned();
        }

        /**
         * Marks {@code ui} as beacon-closed (see {@link #beaconClosed}) so the grace clock starts
         * without closing the UI. No-op unless {@code ui} is actually one of this scope's UIs.
         */
        private void markBeaconClosed(@NotNull UI ui) {
            if (closed || !uis.contains(ui)) {
                return;
            }
            if (beaconClosed.add(ui)) {
                log.debug("{}: unload beacon for {}; starting the grace clock without closing it", TabScope.this, ui);
                updateOrphaned();
            }
        }

        private void updateOrphaned() {
            uis.removeIf(UI::isClosing);
            beaconClosed.retainAll(uis);
            final boolean hasLiveUI = uis.stream().anyMatch(ui -> !beaconClosed.contains(ui));
            if (!hasLiveUI && orphanedSince == null) {
                // orphaned - no live UI points to this tab scope.
                orphanedSince = System.currentTimeMillis();
                log.debug("{} is now orphaned (no live UI points to it); will be reaped after {} ms unless a UI reattaches", TabScope.this, CLEANUP_DURATION_MS);
                armReap();
            }
        }

        /**
         * Schedules the one-shot reap that fires after the grace period even if no other request
         * arrives — the mechanism that makes a sole last tab's scope destroy promptly.
         */
        private void armReap() {
            if (!scheduledReapEnabled) {
                return; // reaper disabled: fall back to request-driven sweep + session-destroy
            }
            final VaadinSession session = VaadinSession.getCurrent();
            if (session == null) {
                // No session to capture. Orphaning normally runs under the lock, so this is not
                // expected; the request-driven sweep + session-destroy backstop still cover us.
                return;
            }
            cancelReap();
            pendingReap = scheduler().schedule(() -> reap(session), CLEANUP_DURATION_MS);
        }

        private void cancelReap() {
            if (pendingReap != null) {
                pendingReap.cancel();
                pendingReap = null;
            }
        }

        /**
         * Runs off-request on the scheduler thread. Hops onto the session lock via
         * {@link VaadinSession#access} (which self-purges its queue when no thread holds the lock,
         * so this runs without any client request) and reaps the scope if it is still orphaned.
         */
        private void reap(@NotNull VaadinSession session) {
            session.access(this::closeIfOrphaned);
        }

        @Override
        public String toString() {
            return "Lifecycle{" + windowName + ", " +
                    "uis=" + uis +
                    ", orphanedSince=" + orphanedSince +
                    '}';
        }

        @NotNull
        private final List<SerializableConsumer<TabScope>> destroyListeners = new ArrayList<>();

        public void closeIfOrphaned() {
            if (closed) {
                return;
            }
            updateOrphaned();
            if (orphanedSince != null && System.currentTimeMillis() - orphanedSince > CLEANUP_DURATION_MS) {
                close(true);
            }
        }

        private void close(boolean removeFromScopeMap) {
           if (!closed) {
               log.debug("Destroying {}", TabScope.this);
               closed = true;
               cancelReap();
               uis.clear();
               beaconClosed.clear();
               destroyListeners.forEach(it -> it.accept(TabScope.this));
               destroyListeners.clear();
               values = null;
               if (removeFromScopeMap) {
                   removeFromScopeMap();
               }
           }
        }

        private void removeFromScopeMap() {
            @SuppressWarnings("unchecked")
            Map<String, TabScope> instances = (Map<String, TabScope>) VaadinSession.getCurrent().getAttribute("tab-scopes");
            if (instances != null) {
                instances.remove(windowName);
            }
        }

        @NotNull
        public Registration addDestroyListener(@NotNull SerializableConsumer<TabScope> listener) {
            requireNotClosed();
            return Registration.addAndRemove(destroyListeners, Objects.requireNonNull(listener));
        }
    }

    /**
     * Returns a map which holds all tab-scoped values stored by the app.
     *
     * @return a map which holds all tab-scoped values stored by the app.
     */
    @NotNull
    public Attributes getValues() {
        if (values == null) {
            throw new IllegalStateException("this scope has been destroyed");
        }
        return values;
    }

    /**
     * Adds a tab scope destroy listener, called before {@link #getValues() values} are cleared.
     * <br/>
     * Fires reliably on every graceful teardown — explicit session close, tab close, and idle
     * session timeout alike; the timeout path reaches us through {@link VaadinSession}'s
     * {@code HttpSessionBindingListener} (no listener registration needed), verified on embedded
     * Jetty and Tomcat in <a href="https://github.com/mvysny/vaadin-boot/issues/39">mvysny/vaadin-boot#39</a>.
     * Only an abrupt {@code kill -9} / power loss skips it, as it would any shutdown hook.
     * <br/>
     * A <strong>sole-last-tab close</strong> fires this promptly too (within the grace period,
     * ~60&nbsp;s), via the always-on scheduled reap — for {@code @PreserveOnRefresh} routes once the
     * app wires {@link #onUnloadBeacon(UI)} (see {@link #installTabCloseBeacon(java.util.List)}).
     * What remains container-paced is only a genuine idle timeout with the tab left open (see
     * <a href="https://github.com/mvysny/vaadin-tab-scope/issues/3">issue #3</a>).
     *
     * @param listener scope destroy listener to call.
     * @return registration
     */
    @NotNull
    public Registration addDestroyListener(@NotNull SerializableConsumer<TabScope> listener) {
        return lifecycle.addDestroyListener(listener);
    }

    /**
     * Returns a map holding all tab scopes in a session.
     *
     * @return a map, mapping {@link ExtendedClientDetails#getWindowName()} (a unique ID of a browser tab)
     * to the TabScope instance, holding all tab-scoped values.
     */
    @NotNull
    private static Map<String, TabScope> getInstances() {
        @SuppressWarnings("unchecked")
        Map<String, TabScope> instances = (Map<String, TabScope>) VaadinSession.getCurrent().getAttribute("tab-scopes");
        if (instances == null) {
            instances = new HashMap<>();
            VaadinSession.getCurrent().setAttribute("tab-scopes", instances);
        }
        return instances;
    }

    /**
     * Sets up the tab scope mechanism. Call this from {@link com.vaadin.flow.server.VaadinServiceInitListener#serviceInit(ServiceInitEvent)}.
     *
     * @param tabInitListener invoked when the tab scope is ready to be used. Invoked exactly once for a browser tab,
     *                        before any route or layout is created or initialized. In the listener,
     *                        you can store any init values to {@link #getValues()}, or perform any
     *                        kind of initialization that only needs to be done once per browser tab.
     */
    public static void setup(@NotNull SerializableConsumer<TabScope> tabInitListener) {
        Objects.requireNonNull(tabInitListener);
        var service = Objects.requireNonNull(VaadinService.getCurrent());
        service.addUIInitListener(event -> init(tabInitListener));
        service.addSessionInitListener(event -> {
            event.getSession().addSessionDestroyListener(e2 -> destroyAllTabScopes(e2.getSession()));
        });
        // Shut the shared reaper thread down with the service, so a servlet-container redeploy
        // doesn't leak it (and its classloader).
        service.addServiceDestroyListener(e -> shutdownReaper());
        // The UI destroy listeners are added in the init() function.
    }

    private static void destroyAllTabScopes(@NotNull VaadinSession session) {
        Objects.requireNonNull(session);
        if (VaadinSession.getCurrent() != session) {
            throw new IllegalStateException("Invalid state: current session != session being destroyed");
        }
        if (!session.hasLock()) {
            throw new IllegalStateException("Invalid state: session not locked");
        }
        @SuppressWarnings("unchecked")
        Map<String, TabScope> instances = (Map<String, TabScope>) session.getAttribute("tab-scopes");
        if (instances != null) {
            log.debug("Session destroyed; closing {} tab scope(s)", instances.size());
            instances.values().forEach(it -> it.lifecycle.close(false));
            instances.clear();
            session.setAttribute("tab-scopes", null);
        }
    }

    /**
     * Initializes the tab scope.
     *
     * @param tabInitListener when the tab scope has been initialized, this listener is called. Invoked exactly once for a browser tab,
     *                        before any route or layout is created or initialized.
     *                        Serves as a replacement for Vaadin 8 UIInitListener.
     */
    // Deprecated in Flow 25.x in favor of getExtendedClientDetails()/refresh(), but kept on
    // purpose: retrieveExtendedClientDetails is @since 2.0 and works on every Flow version, while
    // the replacements are @since 25.0 and eager v-wn-on-bootstrap only @since 25.2. This add-on is
    // compileOnly against Vaadin so consumers bring their own version — migrating would swap a
    // deprecation warning for a NoSuchMethodError on pre-25 Vaadins. See INTERNALS ("ECD API: why
    // the deprecated retrieveExtendedClientDetails").
    @SuppressWarnings("deprecation")
    private static void init(@NotNull SerializableConsumer<TabScope> tabInitListener) {
        final UI ui = UI.getCurrent();
        if (ui == null) {
            throw new IllegalStateException("Must be called from Vaadin UI thread");
        }

        // We need to fetch the Window Name (=browser tab identifier).
        // That can be retrieved from the ExtendedClientDetails (ECD).
        // Fetch the Window Name, create a new tab scope for it, and fire tabInitListener.
        ui.getPage().retrieveExtendedClientDetails(ecd -> {
            cleanupOrphans();
            TabScope tabScope = getInstances().get(ecd.getWindowName());
            if (tabScope == null) {
                tabScope = new TabScope(ecd.getWindowName());
                getInstances().put(ecd.getWindowName(), tabScope);
                log.debug("Created {}", tabScope);
                tabInitListener.accept(tabScope);
            }
            tabScope.lifecycle.add(ui);
            final TabScope finalTabScope = tabScope;

            // On tab close the beacon detaches the UI, starting the orphan grace period; a
            // reopened tab arrives with a fresh window.name, so nothing needs reconnecting.
            // See INTERNALS.md ("Tab close needs no special handling").
            ui.addDetachListener(e -> removeUI(finalTabScope, ui));
        });

        // The "before any route or layout is created or initialized" guarantee is NOT enforced
        // here; it relies on Vaadin deferring navigation until ExtendedClientDetails is fetched.
        // This is fragile — see INTERNALS.md ("Ordering") and vaadin/flow#13468.
    }

    /**
     * Returns the current tab scope.
     * Can be called from your routes, layouts and components, or generally any other code which runs in
     * Vaadin UI thread.
     * <br/>
     * Can not be called from the UI init listener itself, or before the UI init listener has been run.
     *
     * @return the tab scope, not null.
     */
    @NotNull
    public static TabScope getCurrent() {
        final UI ui = UI.getCurrent();
        if (ui == null) {
            throw new IllegalStateException("Must be called from Vaadin UI thread");
        }

        final Map<String, TabScope> instances = getInstances();
        final ExtendedClientDetails extendedClientDetails = ui.getInternals().getExtendedClientDetails();
        if (extendedClientDetails != null) {
            final TabScope tabScope = instances.get(extendedClientDetails.getWindowName());
            if (tabScope == null) {
                throw new IllegalStateException("The TabScope instance is not available for this tab. That's an error since this shouldn't happen - the TabScope should have been created when ExtendedClientDetails were fetched.");
            }
            return tabScope;
        }
        throw new IllegalStateException("Trying to retrieve TabScope too early");
    }

    /**
     * Notifies the tab scope that the browser tab owning {@code ui} is closing — its unload beacon
     * has arrived. Starts the scope's grace clock <em>without</em> closing {@code ui}, so a
     * {@code @PreserveOnRefresh} tab (whose beacon Flow otherwise ignores, leaving the scope
     * unorphaned until session-destroy) is reaped promptly on a real close, while a genuine F5 still
     * re-attaches a UI within the grace period and keeps the scope alive. A no-op for a UI that is
     * not (yet) tab-scoped.
     * <br/>
     * The library does not observe the beacon itself — Vaadin exposes no clean hook, and
     * self-registering a {@link VaadinService} would break Spring apps. Wire this from your own
     * {@link com.vaadin.flow.server.communication.ServerRpcHandler}; the ready-made
     * {@link TabScopeServerRpcHandler} + {@link #installTabCloseBeacon(java.util.List)} do exactly
     * that. Must be called under the session lock, as beacon handling is.
     *
     * @param ui the UI whose browser tab is closing.
     */
    public static void onUnloadBeacon(@NotNull UI ui) {
        Objects.requireNonNull(ui, "ui");
        final VaadinSession session = ui.getSession();
        if (session == null) {
            return; // already detached: nothing to do
        }
        if (!session.hasLock()) {
            throw new IllegalStateException("Invalid state: session not locked");
        }
        final ExtendedClientDetails ecd = ui.getInternals().getExtendedClientDetails();
        if (ecd == null) {
            return; // window.name not yet known: this UI has no tab scope yet
        }
        @SuppressWarnings("unchecked")
        final Map<String, TabScope> instances = (Map<String, TabScope>) session.getAttribute("tab-scopes");
        if (instances == null) {
            return;
        }
        final TabScope tabScope = instances.get(ecd.getWindowName());
        if (tabScope != null) {
            tabScope.lifecycle.markBeaconClosed(ui);
        }
    }

    /**
     * Replaces the stock {@link UidlRequestHandler} in {@code handlers} with
     * {@link TabScopeUidlRequestHandler}, so unload beacons reach {@link #onUnloadBeacon(UI)}. Call
     * this from your own {@code VaadinService.createRequestHandlers()} on the list returned by
     * {@code super.createRequestHandlers()}. Enables prompt reap for {@code @PreserveOnRefresh}
     * tabs; without it, feature B still reaps plain routes promptly (see
     * <a href="https://github.com/mvysny/vaadin-tab-scope/issues/3">issue #3</a>).
     * <br/>
     * No-op if no stock {@link UidlRequestHandler} is present — already installed, or the app uses
     * its own {@code UidlRequestHandler} subclass (which it must then extend from
     * {@link TabScopeUidlRequestHandler} instead).
     *
     * @param handlers the mutable request-handler list from {@code createRequestHandlers()}.
     */
    public static void installTabCloseBeacon(@NotNull List<RequestHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers");
        for (int i = 0; i < handlers.size(); i++) {
            // Exact-type match: swap Flow's stock handler, but never stomp an existing custom subclass.
            if (handlers.get(i).getClass() == UidlRequestHandler.class) {
                handlers.set(i, new TabScopeUidlRequestHandler());
                log.debug("Installed TabScopeUidlRequestHandler for tab-close beacon capture");
                return;
            }
        }
        log.warn("installTabCloseBeacon: no stock UidlRequestHandler found; tab-close beacon capture NOT installed");
    }

    private static void removeUI(@NotNull TabScope tabScope, @NotNull UI ui) {
        if (!VaadinSession.getCurrent().hasLock()) {
            throw new IllegalStateException("Invalid state: no session lock");
        }
        tabScope.lifecycle.remove(ui);
        cleanupOrphans();
    }

    private static void cleanupOrphans() {
        if (!VaadinSession.getCurrent().hasLock()) {
            throw new IllegalStateException("Invalid state: no session lock");
        }
        final List<TabScope> scopes = new ArrayList<>(getInstances().values());
        scopes.forEach(it -> it.lifecycle.closeIfOrphaned());
    }
}
