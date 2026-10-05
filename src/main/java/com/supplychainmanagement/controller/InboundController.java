package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentResponseDto;
import com.supplychainmanagement.service.ComponentService;
import com.supplychainmanagement.service.RequestComponentService;
import com.supplychainmanagement.service.UserService;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.*;

import java.util.List;



@RestController
@RequestMapping({"/api/{version}/supplier"})
@AllArgsConstructor
public class InboundController {

    private final ComponentService componentService;
    private final RequestComponentService requestComponentService;
    private final UserService userService;

    /**
     * The supplier's own requests - filtered by {@code supplier_id}, so an ADMIN asking gets their
     * own (empty) list. The warehouse's view of the same rows is {@code GET /requests} above.
     * <p>
     * Answers {@link RequestComponentResponseDto}, mapped in the service while the transaction is
     * open. That DTO used to carry the {@code Component} <strong>entity</strong> in a field, which
     * serialized the component's product along and resolved the graph through the open-in-view
     * session, and its {@code qty} was an {@code Integer} while the entity's is a {@code Long} - two
     * things MapStruct did silently.
     */
    @GetMapping(path = "/my-requests", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','SUPPLIER')")
    public List<RequestComponentResponseDto> getMyRequests(@AuthenticationPrincipal User user) {
        return requestComponentService.findMyRequests(userService.getAuthenticatedUserId(user));
    }


    /**
     * The supplier accepts the request: OPEN to APPROVED. Only the supplier it was placed with may
     * answer it - anybody else is a 403, ADMIN excepted - and any other status is a 409.
     */
    @PostMapping(path = "/{requestId}/approve", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','SUPPLIER')")
    public RequestComponentResponse approveRequest(@PathVariable Long requestId,
                                                   @AuthenticationPrincipal User authUser) {
        return componentService.approveRequest(requestId, userService.getAuthenticatedUserId(authUser));
    }

    /** The supplier has sent the goods: APPROVED to IN_TRANSIT. */
    @PostMapping(path = "/{requestId}/intransit", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','SUPPLIER')")
    public RequestComponentResponse requestInTransit(@PathVariable Long requestId,
                                                     @AuthenticationPrincipal User authUser) {
        return componentService.requestInTransit(requestId, userService.getAuthenticatedUserId(authUser));
    }

    /**
     * The supplier reports the goods handed over: IN_TRANSIT to DELIVERED - their last step. Nothing
     * is booked here; the warehouse answers it with the goods receipt.
     */
    @PostMapping(path = "/{requestId}/delivered", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','SUPPLIER')")
    public RequestComponentResponse requestDelivered(@PathVariable Long requestId,
                                                     @AuthenticationPrincipal User authUser) {
        return componentService.requestDelivered(requestId, userService.getAuthenticatedUserId(authUser));
    }

    /**
     * The supplier declines a request they have not taken on: OPEN to REJECTED. An end state -
     * nothing was promised, so nothing has to be undone.
     */
    @PostMapping(path = "/{requestId}/reject", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','SUPPLIER')")
    public RequestComponentResponse rejectRequest(@PathVariable Long requestId,
                                                  @AuthenticationPrincipal User authUser) {
        return componentService.rejectRequest(requestId, userService.getAuthenticatedUserId(authUser));
    }

    /**
     * The supplier calls off a request they had taken on: APPROVED or IN_TRANSIT to CANCELLED. Not
     * from DELIVERED - the goods are at our dock then, and that would be a return.
     * <p>
     * No body: there is no column for a reason, and {@code comment} belongs to whoever ordered the
     * part - overwriting it would throw away why it was needed.
     */
    @PostMapping(path = "/{requestId}/cancel", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','SUPPLIER')")
    public RequestComponentResponse cancelRequest(@PathVariable Long requestId,
                                                  @AuthenticationPrincipal User authUser) {
        return componentService.cancelRequest(requestId, userService.getAuthenticatedUserId(authUser));
    }
}
