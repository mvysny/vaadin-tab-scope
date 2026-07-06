package com.github.mvysny.vaadin.tabscope;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.Route;

/**
 * A prototype-scoped route (not {@link TabScoped}): a new instance is created on every page reload,
 * but it reads the tab-scoped {@code counter} value which must stay stable across reloads.
 */
@Route("")
public class PlainTestView extends Div {
    final Integer value;

    public PlainTestView() {
        value = (Integer) TabScope.getCurrent().getValues().getAttribute("counter");
    }
}
