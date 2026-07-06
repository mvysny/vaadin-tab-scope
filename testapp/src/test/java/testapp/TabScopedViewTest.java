package testapp;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.mvysny.kaributesting.v10.LocatorJ._assertOne;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TabScopedViewTest extends AbstractAppTest {
    @BeforeEach
    public void navigate() {
        TabScopedView.counter.set(0); // start all tests with a known counter value
        UI.getCurrent().navigate(TabScopedView.class);
        _assertOne(TabScopedView.class);
    }

    @Test
    public void smokeTest() {
        _assertOne(Span.class, spec -> spec.withText("Value: 1"));
    }

    @Test
    public void pageReloadShouldPreserveTheValue() {
        final TabScopedView mainView = _get(TabScopedView.class);
        _assertOne(Span.class, spec -> spec.withText("Value: 1"));
        UI.getCurrent().getPage().reload();

        // check that we have the same instance of the view
        assertSame(_get(TabScopedView.class), mainView);
        _assertOne(Span.class, spec -> spec.withText("Value: 1"));
    }
}
