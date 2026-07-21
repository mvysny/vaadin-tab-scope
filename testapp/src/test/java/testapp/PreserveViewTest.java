package testapp;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import org.junit.jupiter.api.Test;

import static com.github.mvysny.kaributesting.v10.LocatorJ._assertOne;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The {@code @PreserveOnRefresh} route renders and reads its tab-scoped value, and reuses the same
 * instance across an F5. (The tab-close beacon reap it demonstrates is verified against a real
 * browser, not here — Karibu never runs a real {@code ServerRpcHandler}; see CLAUDE.md "Testing".)
 */
public class PreserveViewTest extends AbstractAppTest {
    @Test
    public void rendersAndReadsScopedValue() {
        UI.getCurrent().navigate(PreserveView.class);
        _assertOne(Span.class, spec -> spec.withText("Value: 1"));
    }

    @Test
    public void reloadPreservesTheSameInstance() {
        UI.getCurrent().navigate(PreserveView.class);
        final PreserveView view = _get(PreserveView.class);
        UI.getCurrent().getPage().reload();
        assertSame(view, _get(PreserveView.class), "@PreserveOnRefresh reuses the instance across F5");
    }
}
