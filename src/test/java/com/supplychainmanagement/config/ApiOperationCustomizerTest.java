package com.supplychainmanagement.config;

import io.swagger.v3.oas.models.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.method.HandlerMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the handler method says that springdoc does not: which version it is for and who may call it.
 */
class ApiOperationCustomizerTest {

    private final ApiOperationCustomizer customizer = new ApiOperationCustomizer("1.0");

    /** The shapes of mapping and authorisation this code base uses. */
    static class Plain {
        @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
        @GetMapping(path = "/a", version = "1.0")
        public void twoRoles() {
        }

        @PreAuthorize("hasAuthority('MANAGER')")
        @GetMapping(path = "/b", version = "1.0")
        public void oneRole() {
        }

        @PreAuthorize("isAuthenticated()")
        @GetMapping(path = "/c", version = "1.0")
        public void anyone() {
        }

        @GetMapping(path = "/d", version = "1.0")
        public void unannotated() {
        }

        @PreAuthorize("hasAnyAuthority('ADMIN')")
        @GetMapping(path = "/e", version = "2.0")
        public void otherVersion() {
        }

        @GetMapping("/f")
        public void unversioned() {
        }

        @PreAuthorize("hasAnyAuthority('ADMIN', 'LOGISTICS',  'DISTRIBUTOR')")
        @GetMapping(path = "/g", version = "1.0")
        public void sloppySpacing() {
        }
    }

    @PreAuthorize("hasAnyAuthority('CUSTOMER','MANAGER')")
    static class ClassLevel {
        @GetMapping(path = "/h", version = "1.0")
        public void inherits() {
        }

        @PreAuthorize("hasAuthority('ADMIN')")
        @GetMapping(path = "/i", version = "1.0")
        public void overrides() {
        }
    }

    private Operation customise(Object controller, String method) throws Exception {
        return customizer.customize(new Operation(), new HandlerMethod(controller, controller.getClass().getMethod(method)));
    }

    @SuppressWarnings("unchecked")
    private static List<String> rolesOf(Operation operation) {
        return (List<String>) operation.getExtensions().get(ApiOperationCustomizer.ROLES_EXTENSION);
    }

    // ------------------------------------------------------------------ roles

    @Test
    void readsEveryRoleOutOfHasAnyAuthority() throws Exception {
        assertThat(rolesOf(customise(new Plain(), "twoRoles"))).containsExactly("ADMIN", "WAREHOUSE");
    }

    @Test
    void readsTheRoleOutOfHasAuthority() throws Exception {
        assertThat(rolesOf(customise(new Plain(), "oneRole"))).containsExactly("MANAGER");
    }

    @Test
    void toleratesSpacesBetweenTheNames() throws Exception {
        assertThat(rolesOf(customise(new Plain(), "sloppySpacing"))).containsExactly("ADMIN", "LOGISTICS", "DISTRIBUTOR");
    }

    /** "Any logged-in user" is the absence of the extension, not an empty list a client would read as "nobody". */
    @Test
    void writesNoRolesForAnyLoggedInUser() throws Exception {
        Operation operation = customise(new Plain(), "anyone");

        assertThat(operation.getExtensions()).isNull();
        assertThat(operation.getDescription()).isNull();
    }

    @Test
    void writesNoRolesWhereNothingIsDeclared() throws Exception {
        assertThat(customise(new Plain(), "unannotated").getExtensions()).isNull();
    }

    @Test
    void writesTheRolesIntoTheDescriptionToo() throws Exception {
        assertThat(customise(new Plain(), "twoRoles").getDescription()).isEqualTo("**Roles:** ADMIN, WAREHOUSE");
    }

    @Test
    void appendsToADescriptionThatIsThere() throws Exception {
        Plain controller = new Plain();
        Operation operation = new Operation().description("Lists the storehouses.");

        customizer.customize(operation, new HandlerMethod(controller, Plain.class.getMethod("oneRole")));

        assertThat(operation.getDescription()).isEqualTo("Lists the storehouses.\n\n**Roles:** MANAGER");
    }

    @Test
    void fallsBackToTheClassWhenTheMethodSaysNothing() throws Exception {
        assertThat(rolesOf(customise(new ClassLevel(), "inherits"))).containsExactly("CUSTOMER", "MANAGER");
    }

    /** As in Spring Security: the method's annotation wins, it does not add to the class's. */
    @Test
    void letsTheMethodOverrideTheClass() throws Exception {
        assertThat(rolesOf(customise(new ClassLevel(), "overrides"))).containsExactly("ADMIN");
    }

    // ------------------------------------------------------------------ versions

    /** Returning null is what removes the operation from the description. */
    @Test
    void leavesOutAMethodMappedForAnotherVersion() throws Exception {
        assertThat(customise(new Plain(), "otherVersion")).isNull();
    }

    @Test
    void keepsAMethodMappedForTheDescribedVersion() throws Exception {
        assertThat(customise(new Plain(), "twoRoles")).isNotNull();
    }

    @Test
    void keepsAMethodMappedForNoVersionAtAll() throws Exception {
        assertThat(customise(new Plain(), "unversioned")).isNotNull();
    }

    @Test
    void describesWhicheverVersionItIsGiven() throws Exception {
        ApiOperationCustomizer second = new ApiOperationCustomizer("2.0");
        Plain controller = new Plain();

        assertThat(second.customize(new Operation(), new HandlerMethod(controller, Plain.class.getMethod("otherVersion")))).isNotNull();
        assertThat(second.customize(new Operation(), new HandlerMethod(controller, Plain.class.getMethod("twoRoles")))).isNull();
    }
}
