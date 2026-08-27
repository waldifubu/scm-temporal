package com.supplychainmanagement.exception;

import com.supplychainmanagement.model.enums.RoleEnum;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;

/**
 * The caller is authenticated but lacks the required authority.
 * <p>
 * Mapped by {@link GlobalExceptionHandler} to HTTP 403 with the error code {@code WRONG_ROLE} -
 * both when thrown directly (see {@code RoleService#requireAnyAuthority}) and as the translation
 * of the {@code AuthorizationDeniedException} that {@code @PreAuthorize} throws.
 */
@Getter
public class WrongRoleException extends APIException {

    private final transient List<String> requiredAuthorities;

    public WrongRoleException(String message, List<String> requiredAuthorities) {
        super(HttpStatus.FORBIDDEN, message);
        this.requiredAuthorities = List.copyOf(requiredAuthorities);
    }

    /** For callers that know the required roles (manual check in a service or controller). */
    public static WrongRoleException requiring(RoleEnum... requiredRoles) {
        List<String> required = Arrays.stream(requiredRoles).map(RoleEnum::name).toList();
        return new WrongRoleException(
                "Access denied: this operation requires one of the following roles: " + String.join(", ", required),
                required);
    }

    /** For cases where the concrete rule is unknown (e.g. a @PreAuthorize expression). */
    public static WrongRoleException withoutKnownRequirement() {
        return new WrongRoleException(
                "Access denied: your role is not permitted to access this resource",
                List.of());
    }
}
