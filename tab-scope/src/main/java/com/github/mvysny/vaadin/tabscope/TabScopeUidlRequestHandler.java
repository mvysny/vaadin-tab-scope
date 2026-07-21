package com.github.mvysny.vaadin.tabscope;

import com.vaadin.flow.server.communication.ServerRpcHandler;
import com.vaadin.flow.server.communication.UidlRequestHandler;

/**
 * A {@link UidlRequestHandler} that installs {@link TabScopeServerRpcHandler}, the only supported way
 * to intercept the unload beacon (Flow builds the {@link ServerRpcHandler} through the protected
 * {@link #createRpcHandler()} factory).
 * <br/>
 * The library ships this class but never registers it; install it via
 * {@link TabScope#installTabCloseBeacon(java.util.List)} from your own
 * {@code VaadinService.createRequestHandlers()} — see README.
 */
public class TabScopeUidlRequestHandler extends UidlRequestHandler {
    @Override
    protected ServerRpcHandler createRpcHandler() {
        return new TabScopeServerRpcHandler();
    }
}
