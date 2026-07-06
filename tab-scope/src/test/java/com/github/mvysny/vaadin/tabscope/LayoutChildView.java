package com.github.mvysny.vaadin.tabscope;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.Route;

/**
 * A plain (non-{@link TabScoped}) route rendered inside the {@link TabScopedLayout}. Navigating here
 * forces the router to instantiate the tab-scoped parent layout, exercising
 * {@link TabScopedRouteInstantiator}'s layout-caching path.
 */
@Route(value = "layoutchild", layout = TabScopedLayout.class)
public class LayoutChildView extends Div {
}
