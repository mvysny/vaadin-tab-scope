package com.github.mvysny.vaadin.tabscope;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.page.ExtendedClientDetails;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.server.*;
import com.vaadin.flow.shared.Registration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;

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
         * Tracks time since when no UIs point to this tab scope.
         */
        @Nullable
        private Long orphanedSince = null;

        private boolean closed = false;

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
        }

        public void remove(@NotNull UI ui) {
            if (closed) {
                return;
            }
            if (!uis.remove(Objects.requireNonNull(ui))) {
                throw new IllegalStateException("Invalid state: uis doesn't contain given ui");
            }
            updateOrphaned();
        }

        private void updateOrphaned() {
            uis.removeIf(UI::isClosing);
            if (uis.isEmpty() && orphanedSince == null) {
                // orphaned - no active UI points to this tab scope.
                orphanedSince = System.currentTimeMillis();
                log.debug("{} is now orphaned (no UI points to it); will be reaped after {} ms unless a UI reattaches", TabScope.this, CLEANUP_DURATION_MS);
            }
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
               uis.clear();
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
        return Objects.requireNonNull(values, "this scope has been destroyed");
    }

    /**
     * Adds a tab scope destroy listener. The listeners will be called before
     * {@link #getValues() values} are cleared.
     * <br/>
     * Important: don't rely on the listener being called. For example,
     * it may not be called when the session is timed out and closed by the servlet
     * container, since in this case {@link VaadinSession#addSessionDestroyListener(SessionDestroyListener) Vaadin session destroy listeners}
     * aren't being called at all!
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
    private static void init(@NotNull SerializableConsumer<TabScope> tabInitListener) {
        final UI ui = Objects.requireNonNull(UI.getCurrent(), "Must be called from Vaadin UI thread");

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
        final UI ui = Objects.requireNonNull(UI.getCurrent(), "Must be called from Vaadin UI thread");

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
