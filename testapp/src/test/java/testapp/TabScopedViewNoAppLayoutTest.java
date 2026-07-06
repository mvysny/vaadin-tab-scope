package testapp;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.mvysny.kaributesting.v10.LocatorJ._assertOne;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TabScopedViewNoAppLayoutTest extends AbstractAppTest {
    @BeforeEach
    public void navigate() {
        TabScopedViewNoAppLayout.counter.set(0); // start all tests with a known counter value
        UI.getCurrent().navigate(TabScopedViewNoAppLayout.class);
        _assertOne(TabScopedViewNoAppLayout.class);
    }

    @Test
    public void smokeTest() {
        _assertOne(Span.class, spec -> spec.withText("Value: 1"));
    }

    @Test
    public void pageReloadShouldPreserveTheValue() {
        final TabScopedViewNoAppLayout mainView = _get(TabScopedViewNoAppLayout.class);
        _assertOne(Span.class, spec -> spec.withText("Value: 1"));
        UI.getCurrent().getPage().reload();

        // check that we have the same instance of the view
        assertSame(_get(TabScopedViewNoAppLayout.class), mainView);
        _assertOne(Span.class, spec -> spec.withText("Value: 1"));
    }
}
