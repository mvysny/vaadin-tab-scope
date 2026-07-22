package testapp;

import com.github.mvysny.vaadin.tabscope.TabScope;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PreserveOnRefresh;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.Attributes;

/**
 * A {@link PreserveOnRefresh} route. Flow ignores the browser's unload beacon for such a route, so
 * on a sole-tab close the tab scope would linger until session-destroy — unless the tab-close beacon
 * hook (wired by {@link TabScopeBeaconServlet}) starts the grace clock. To see it: open
 * this route in the only tab, close the tab, and watch the server log emit "TabScope destroyed"
 * within ~60&nbsp;seconds. See README / issue #3.
 */
@Route(value = "preserve", layout = MainLayout.class)
@PreserveOnRefresh
public class PreserveView extends VerticalLayout {
    public PreserveView() {
        add(new H3("@PreserveOnRefresh + Tab Scope"));
        add(new Span("This route is @PreserveOnRefresh. When it is the only open tab, closing it "
                + "triggers the tab-close beacon hook, which reaps this tab's scope within ~60s — "
                + "watch the server log for 'TabScope destroyed'. A plain reload (F5) keeps the scope."));
        final Attributes values = TabScope.getCurrent().getValues();
        add(new Span("Value: " + values.getAttribute("hello")));
    }
}
