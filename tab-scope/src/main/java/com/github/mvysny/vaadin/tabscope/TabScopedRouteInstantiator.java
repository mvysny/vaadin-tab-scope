package com.github.mvysny.vaadin.tabscope;

import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.di.DefaultInstantiator;
import com.vaadin.flow.di.Instantiator;
import com.vaadin.flow.di.InstantiatorFactory;
import com.vaadin.flow.server.VaadinService;
import org.jetbrains.annotations.NotNull;

/**
 * A custom instantiator which adds support for tab-scoped routes (annotated with
 * {@link TabScoped}). See <a href="https://vaadin.com/docs/latest/flow/advanced/custom-instantiators">Custom Instantiator</a>
 * Vaadin documentation for more details.
 * <br/>
 * Register this via the {@link Factory} in a
 * <code>META-INF/services/com.vaadin.flow.di.InstantiatorFactory</code> file in your app. This
 * library deliberately does <b>not</b> ship that service file: Vaadin resolves exactly one
 * {@link InstantiatorFactory}, and Spring ships its own, so shipping ours would break Spring apps.
 */
public class TabScopedRouteInstantiator extends DefaultInstantiator {
    /**
     * Creates a new instantiator for the given service.
     *
     * @param service the service to use
     */
    public TabScopedRouteInstantiator(VaadinService service) {
        super(service);
    }

    @Override
    @NotNull
    public <T> T getOrCreate(@NotNull Class<T> type) {
        if (type.getAnnotation(TabScoped.class) != null) {
            T instance = TabScope.getCurrent().getValues().getAttribute(type);
            if (instance == null) {
                instance = super.getOrCreate(type);
                TabScope.getCurrent().getValues().setAttribute(type, instance);
            }
            if (instance instanceof HasElement) {
                // fix the Exception
                // "Can't move a node from one state tree to another. If this is intentional, first remove the node from its current state tree by calling removeFromTree."
                // for TabScoped routes.
                ((HasElement) instance).getElement().removeFromTree();
            }
            return instance;
        }
        return super.getOrCreate(type);
    }

    public static class Factory implements InstantiatorFactory {
        @Override
        public Instantiator createInstantitor(VaadinService service) {
            return new TabScopedRouteInstantiator(service);
        }
    }
}
