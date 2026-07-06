package com.github.mvysny.vaadin.tabscope;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.PreserveOnRefresh;
import com.vaadin.flow.router.Route;

/**
 * A {@link PreserveOnRefresh} route that is <em>not</em> {@link TabScoped}. Real Flow ignores the
 * browser unload beacon for such routes and instead teleports the old UI's chain onto the new UI,
 * so the reload never produces a zero-UI gap. This view lets tests assert that tab-scoped values
 * survive that beacon-ignored path too — the tab scope is annotation-agnostic (see INTERNALS.md,
 * "Relationship to {@code @PreserveOnRefresh}").
 */
@Route("preserve")
@PreserveOnRefresh
public class PreserveOnRefreshTestView extends Div {
    final Integer value;

    public PreserveOnRefreshTestView() {
        value = (Integer) TabScope.getCurrent().getValues().getAttribute("counter");
    }
}
