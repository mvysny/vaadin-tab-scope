package testapp;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import org.junit.jupiter.api.Test;

import static com.github.mvysny.kaributesting.v10.LocatorJ._assertOne;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.junit.jupiter.api.Assertions.assertNotSame;

public class MainViewTest extends AbstractAppTest {
    @Test
    public void smokeTest() {
        _assertOne(Span.class, spec -> spec.withText("Value: 1"));
    }

    @Test
    public void pageReloadShouldPreserveTheValue() {
        final MainView mainView = _get(MainView.class);
        _assertOne(Span.class, spec -> spec.withText("Value: 1"));
        UI.getCurrent().getPage().reload();

        // check that we have a new instance of main view
        assertNotSame(_get(MainView.class), mainView);
        _assertOne(Span.class, spec -> spec.withText("Value: 1"));
    }
}
