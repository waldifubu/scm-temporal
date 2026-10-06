package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.user.UserDto;
import com.supplychainmanagement.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /me}: who is logged in, with <strong>every</strong> role they hold.
 * <p>
 * It exists for a browser client. The token lives in an {@code HttpOnly} cookie, which JavaScript
 * cannot read, so after a page reload the only way to learn whether there is a session - and who it
 * belongs to - is to ask. The login response cannot stand in for it: its {@code role} is one
 * authority picked by {@code iterator().next()}, so a user holding WAREHOUSE and LOGISTICS got
 * {@code logistics} and the menu built from it showed half their workplace. Role names are the enum
 * names, exactly what {@code @PreAuthorize("hasAnyAuthority(...)")} compares against.
 * <p>
 * It is a resource of its own and deliberately <strong>not</strong> under {@code /auth}: everything
 * there is public (register, login and logout have to work without a token), so the JWT filter does
 * not run there and this method would have been called with no principal at all. Outside that space
 * it needs no special treatment - the filter runs and {@code anyRequest().authenticated()} applies.
 * <p>
 * No token, an expired one or a forged one is a 401 - the answer of every other protected endpoint.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}/me"})
public class MeController {

    private final UserService userService;

    @GetMapping(path = "", version = "1.0")
    @PreAuthorize("isAuthenticated()")
    public UserDto me(@AuthenticationPrincipal org.springframework.security.core.userdetails.User authUser) {
        return userService.findCurrentUser(authUser.getUsername());
    }
}
