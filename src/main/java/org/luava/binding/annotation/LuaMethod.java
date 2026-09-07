package org.luava.binding.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LuaMethod {
    String name() default "";
    String description() default "";
    boolean isMethod() default false; // true if 'self' is passed as first argument
}
