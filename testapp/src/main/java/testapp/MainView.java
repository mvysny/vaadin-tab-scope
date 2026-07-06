package testapp;

import com.github.mvysny.vaadin.tabscope.TabScope;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.Attributes;

/**
 * Demoes a prototype-scoped route (new instance created on page reload), fetching a tab-scoped value.
 */
@Route(value = "", layout = MainLayout.class)
public class MainView extends VerticalLayout {

    public MainView() {
        add(new H3("Tab Scope"));
        add(new Span("This route is prototype-scoped (new instance created on page reload), but the value displayed below is tab-scoped. The value should not change when the page is reloaded or navigated elsewhere, but should change when the route is opened in another browser tab."));
        final Attributes values = TabScope.getCurrent().getValues();
        Integer value = (Integer) values.getAttribute("hello");
        add(new Span("Value: " + value));
    }
}
