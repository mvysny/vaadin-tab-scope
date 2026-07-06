package com.github.mvysny.vaadin.tabscope;

import java.lang.annotation.*;

/**
 * If a layout or a route has this annotation, it is tab-scoped.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@Documented
public @interface TabScoped {
}
