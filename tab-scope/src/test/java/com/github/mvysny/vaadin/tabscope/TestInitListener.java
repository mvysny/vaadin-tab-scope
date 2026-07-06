package com.github.mvysny.vaadin.tabscope;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only wiring: registered via {@code META-INF/services} on the test classpath, it calls
 * {@link TabScope#setup} and seeds a per-tab counter so tests can assert the value survives reload.
 */
public class TestInitListener implements VaadinServiceInitListener {
    /** Incremented once per browser tab (i.e. once per {@link TabScope}). */
    static final AtomicInteger COUNTER = new AtomicInteger();

    @Override
    public void serviceInit(ServiceInitEvent event) {
        TabScope.setup(ts -> ts.getValues().setAttribute("counter", COUNTER.incrementAndGet()));
    }
}
