package testapp;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.vaadin.tabscope.TabScope;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public abstract class AbstractAppTest {
    private static Routes routes;

    @BeforeAll
    public static void createRoutes() {
        routes = new Routes().autoDiscoverViews("testapp");
    }

    @BeforeEach
    public void setupVaadin() {
        MockVaadin.setup(routes);
        ApplicationServiceInitListener.counter.set(0); // start all tests with a known counter value
    }

    @AfterEach
    public void teardownVaadin() {
        MockVaadin.tearDown();
    }

    @Test
    public void scopeSurvivesPageReload() {
        final TabScope current = TabScope.getCurrent();
        assertNotNull(current);
        UI.getCurrent().getPage().reload();
        assertSame(TabScope.getCurrent(), current);
    }
}
