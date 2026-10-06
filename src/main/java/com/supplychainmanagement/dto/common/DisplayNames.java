package com.supplychainmanagement.dto.common;

import com.supplychainmanagement.entity.users.User;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a person is called in a response: first and last name where there is one, the login name only
 * when there is neither - never an empty string.
 * <p>
 * It lived as a private helper of {@code RequestComponentResponse} and was about to be written a
 * third time for the order history. One rule, so a supplier, a requester and the person who changed an
 * order's status are named the same way wherever they appear.
 */
public final class DisplayNames {

    private DisplayNames() {
    }

    public static String of(User user) {
        String full = Stream.of(user.getFirstName(), user.getLastName())
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));
        return full.isBlank() ? user.getUsername() : full;
    }
}
