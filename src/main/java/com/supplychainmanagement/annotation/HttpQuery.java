package com.supplychainmanagement.annotation;

import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.Mapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a handler method to the HTTP {@code QUERY} method: a safe, idempotent request that carries its question in a
 * body, the thing {@code GET} cannot do and {@code POST} says the wrong thing about.
 * <p>
 * Spring's {@code RequestMethod} has no {@code QUERY}, so {@code @RequestMapping(method = ...)} cannot name it. This
 * annotation is a {@code @RequestMapping} without a method, and {@code QueryHandlerMapping} adds the one condition
 * that only a request with the method {@code QUERY} passes ({@code QueryMethodCondition}). It takes the same
 * {@code path} and {@code version} as {@code @GetMapping} and the others, so the API versioning applies unchanged.
 * <p>
 * It is kept out of the OpenAPI description: OpenAPI 3.1 has no operation for {@code QUERY}, and a mapping without a
 * method would be listed under every one of the others.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Mapping
@RequestMapping
public @interface HttpQuery {

    @AliasFor(annotation = RequestMapping.class, attribute = "name")
    String name() default "";

    @AliasFor(annotation = RequestMapping.class, attribute = "path")
    String[] path() default {};

    @AliasFor(annotation = RequestMapping.class, attribute = "version")
    String version() default "";

    @AliasFor(annotation = RequestMapping.class, attribute = "consumes")
    String[] consumes() default {};

    @AliasFor(annotation = RequestMapping.class, attribute = "produces")
    String[] produces() default {};
}
