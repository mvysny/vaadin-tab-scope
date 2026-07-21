package testapp;

import com.github.mvysny.vaadin.tabscope.TabScope;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.RequestHandler;
import com.vaadin.flow.server.ServiceException;
import com.vaadin.flow.server.VaadinServlet;
import com.vaadin.flow.server.VaadinServletService;
import jakarta.servlet.annotation.WebServlet;

import java.util.ArrayList;
import java.util.List;

/**
 * Installs tab-scope's unload-beacon capture (feature A of issue #3) into the request-handler chain,
 * so a {@code @PreserveOnRefresh} sole-tab close reaps its scope promptly.
 * <br/>
 * This glue is environment-specific and belongs to the app: the library ships
 * {@code TabScopeUidlRequestHandler}/{@code TabScopeServerRpcHandler} but registers nothing (that
 * would break Spring apps). Vaadin Boot auto-discovers this {@code @WebServlet}, so declaring it is
 * all the wiring needed; it replaces Vaadin's auto-registered default servlet.
 */
@WebServlet(urlPatterns = "/*", name = "tab-scope-servlet", asyncSupported = true)
public class TabScopeBeaconServlet extends VaadinServlet {
    @Override
    protected VaadinServletService createServletService(DeploymentConfiguration configuration)
            throws ServiceException {
        final VaadinServletService service = new VaadinServletService(this, configuration) {
            @Override
            protected List<RequestHandler> createRequestHandlers() throws ServiceException {
                final List<RequestHandler> handlers = new ArrayList<>(super.createRequestHandlers());
                TabScope.installTabCloseBeacon(handlers);
                return handlers;
            }
        };
        service.init();
        return service;
    }
}
