package org.luava.binding.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface LuaParam {
    String name() default "";
    String type() default "";
    String description() default "";
    boolean optional() default false;
}
