package testapp;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.theme.lumo.LumoUtility;
import org.jetbrains.annotations.NotNull;

/**
 * The main layout of the app, adds navigation menu.
 */
public class MainLayout extends AppLayout {
    // retrieveExtendedClientDetails is deprecated in Flow 25.x but kept for the same
    // cross-version reason as the library's TabScope.init — see INTERNALS ("ECD API").
    @SuppressWarnings("deprecation")
    public MainLayout() {
        DrawerToggle toggle = new DrawerToggle();

        H1 title = new H1("Tab Scope Demo App");
        title.getStyle().set("font-size", "var(--lumo-font-size-l)")
                .set("margin", "0");

        SideNav nav = getSideNav();

        Scroller scroller = new Scroller(nav);
        scroller.setClassName(LumoUtility.Padding.SMALL);

        addToDrawer(scroller);
        addToNavbar(toggle, title);

        UI.getCurrent().getPage().retrieveExtendedClientDetails(e -> addToDrawer(new Span("Browser tab ID: " + e.getWindowName())));
    }

    @NotNull
    private SideNav getSideNav() {
        SideNav sideNav = new SideNav();
        sideNav.addItem(
                new SideNavItem("Main View", MainView.class),
                new SideNavItem("Main View (No App Layout)", MainViewNoAppLayout.class),
                new SideNavItem("Tab Scoped View", TabScopedView.class),
                new SideNavItem("Tab Scoped View (No App Layout)", TabScopedViewNoAppLayout.class),
                new SideNavItem("@PreserveOnRefresh View", PreserveView.class)
        );
        return sideNav;
    }
}
