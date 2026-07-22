package com.github.mvysny.vaadin.tabscope;

import java.lang.annotation.*;

/**
 * Marks a route or layout as tab-scoped: {@link TabScopedRouteInstantiator} creates a single
 * instance per browser tab, caches it in the {@link TabScope}, and reuses it across navigation and
 * page reload — instead of Flow's default fresh instance per navigation. {@link Inherited @Inherited},
 * so subclasses are tab-scoped too.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@Documented
public @interface TabScoped {
}
