package com.github.mvysny.vaadin.tabscope;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.communication.ServerRpcHandler;
import com.vaadin.flow.shared.ApplicationConstants;

/**
 * A {@link ServerRpcHandler} that forwards the browser's unload beacon to
 * {@link TabScope#onUnloadBeacon(UI)} before delegating to Flow's default handling (which closes the
 * UI for a plain route and ignores the beacon for a {@code @PreserveOnRefresh} route). This is what
 * makes a {@code @PreserveOnRefresh} sole-tab close reap promptly.
 * <br/>
 * The library ships this class but never registers it (that would break Spring apps). Install it via
 * {@link TabScopeUidlRequestHandler} + {@link TabScope#installTabCloseBeacon(java.util.List)} from
 * your own {@code VaadinService.createRequestHandlers()} — see README.
 */
public class TabScopeServerRpcHandler extends ServerRpcHandler {
    @Override
    protected void handleUnloadBeaconRequest(UI ui, RpcRequest rpcRequest) {
        // handleUnloadBeaconRequest is invoked for every RPC request; act only on the actual beacon.
        // isUnloadBeaconRequest() is private, so replicate it via the public getRawJson().
        if (rpcRequest.getRawJson().has(ApplicationConstants.UNLOAD_BEACON)) {
            TabScope.onUnloadBeacon(ui);
        }
        super.handleUnloadBeaconRequest(ui, rpcRequest);
    }
}
