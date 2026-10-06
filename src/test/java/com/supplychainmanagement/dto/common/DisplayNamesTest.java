package com.supplychainmanagement.dto.common;

import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a person is called in a response. One rule, shared by the supplier list, the component
 * requests and the order history - written once after it had been written twice.
 */
class DisplayNamesTest {

    private static User user(String first, String last, String login) {
        User user = new Customer();
        user.setFirstName(first);
        user.setLastName(last);
        user.setUsername(login);
        return user;
    }

    @Test
    void joinsFirstAndLastName() {
        assertThat(DisplayNames.of(user("Rita", "Rack", "rita"))).isEqualTo("Rita Rack");
    }

    @Test
    void usesTheOneNameThereIs() {
        assertThat(DisplayNames.of(user("Rita", null, "rita"))).isEqualTo("Rita");
        assertThat(DisplayNames.of(user(null, "Rack", "rita"))).isEqualTo("Rack");
    }

    /** Never an empty string: a row that shows nothing for a person is worse than one that shows a login. */
    @Test
    void fallsBackToTheLoginWhenThereIsNoName() {
        assertThat(DisplayNames.of(user(null, null, "rita"))).isEqualTo("rita");
        assertThat(DisplayNames.of(user("  ", "", "rita"))).isEqualTo("rita");
    }

    @Test
    void trimsWhatItJoins() {
        assertThat(DisplayNames.of(user("  Rita ", " Rack  ", "rita"))).isEqualTo("Rita Rack");
    }
}
