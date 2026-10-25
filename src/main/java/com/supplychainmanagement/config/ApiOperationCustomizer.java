package com.supplychainmanagement.config;

import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two things springdoc cannot know, both found on the handler method.
 * <p>
 * <strong>Which version it describes.</strong> A method mapped with {@code version = "2.0"} shares its
 * path with the 1.0 one (the version is not part of the path pattern's identity for springdoc), so the
 * two merged into a single operation with the mixed-up halves of both. The description is of one
 * version, so a method for any other is left out - returning {@code null} removes the operation.
 * <p>
 * <strong>Who may call it.</strong> {@code @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")} is
 * the whole authorisation of this API, and nothing of it reaches the description. It is written as
 * {@code x-roles} (what a client builds its menu from) and into the text. The names are read out of the
 * expression by their quotes, which is all the expressions in this code base are made of; one that
 * needs more than names ({@code isAuthenticated()}, say) simply yields none, and an operation without
 * {@code x-roles} reads as "any logged-in user".
 */
public class ApiOperationCustomizer implements OperationCustomizer {

    static final String ROLES_EXTENSION = "x-roles";

    private static final Pattern QUOTED_NAME = Pattern.compile("'([A-Z][A-Z_]*)'");

    private final String version;

    public ApiOperationCustomizer(String version) {
        this.version = version;
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequestMapping.class);
        if (mapping != null && !mapping.version().isEmpty() && !version.equals(mapping.version())) {
            return null;
        }

        List<String> roles = rolesOf(handlerMethod);
        if (!roles.isEmpty()) {
            operation.addExtension(ROLES_EXTENSION, roles);
            String text = "**Roles:** " + String.join(", ", roles);
            operation.setDescription(operation.getDescription() == null
                    ? text : operation.getDescription() + "\n\n" + text);
        }
        return operation;
    }

    /** The method's own annotation wins over the class's, as it does in Spring Security. */
    static List<String> rolesOf(HandlerMethod handlerMethod) {
        PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class);
        }
        if (annotation == null) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        Matcher matcher = QUOTED_NAME.matcher(annotation.value());
        while (matcher.find()) {
            roles.add(matcher.group(1));
        }
        return roles;
    }
}
