package testapp;

import com.github.mvysny.vaadin.tabscope.TabScoped;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demoes a prototype-scoped route (new instance created on page reload), fetching a tab-scoped value.
 */
@Route(value = "tab-scoped-route-no-app-layout")
@TabScoped
public class TabScopedViewNoAppLayout extends VerticalLayout {

    static final AtomicInteger counter = new AtomicInteger();

    public TabScopedViewNoAppLayout() {
        add(new H3("Tab-Scoped Route"));
        add(new Span("This route is tab-scoped (new instance created per tab). The value below not change when the page is reloaded or navigated elsewhere, but should change when the route is opened in another browser tab."));
        add(new Span("Value: " + counter.incrementAndGet()));
    }
}
