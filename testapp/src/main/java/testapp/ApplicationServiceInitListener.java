package testapp;

import com.github.mvysny.vaadin.tabscope.TabScope;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class ApplicationServiceInitListener
        implements VaadinServiceInitListener {
    private static final Logger log = LoggerFactory.getLogger(ApplicationServiceInitListener.class);

    static final AtomicInteger counter = new AtomicInteger();

    @Override
    public void serviceInit(ServiceInitEvent event) {
        TabScope.setup(ts -> {
            Objects.requireNonNull(TabScope.getCurrent()); // this should work as well.
            if (ts.getValues().getAttribute("hello") != null) {
                throw new IllegalStateException("This is unexpected - we're already initialized but we shouldn't be!");
            }
            ts.getValues().setAttribute("hello", counter.incrementAndGet());
            log.info("TabScope created: {}", ts);
            ts.addDestroyListener(e -> log.info("TabScope destroyed: {}", e));
        });
    }
}
