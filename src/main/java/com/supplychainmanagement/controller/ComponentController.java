package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.component.ComponentResponseDto;
import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentResponseDto;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
import com.supplychainmanagement.dto.mapper.ComponentMapper;
import com.supplychainmanagement.dto.mapper.RequestComponentMapper;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.service.ComponentService;
import com.supplychainmanagement.service.RequestComponentService;
import com.supplychainmanagement.service.UserService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/{version}/components"})
@AllArgsConstructor
// hasAnyAuthority, NOT hasAnyRole: hasAnyRole('ADMIN') checks for the authority "ROLE_ADMIN",
// but this project's authorities are prefix-free "ADMIN"/"MANAGER" (RoleEnum.name()).
// With hasAnyRole the condition was always false, making the whole controller unreachable for everyone.
@PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
public class ComponentController {
    private final ComponentService componentService;
    private final ComponentMapper componentMapper;
    private final UserService userService;
    private final RequestComponentService requestComponentService;
    private final RequestComponentMapper requestComponentMapper;

    // WAREHOUSE reads as well: it may order components through /request/{supplierId}, and without
    // the catalogue it would have to get the SKU from somewhere else. Reading only - creating and
    // changing a component stays with ADMIN/MANAGER.
    @GetMapping(path = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE')")
    public List<ComponentResponseDto> getComponents() {
        return componentService.findAll().stream().map(componentMapper::mapToDto).toList();
    }

    @GetMapping(path = "/my-requests", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','SUPPLIER')")
    public List<RequestComponentResponseDto> getMyRequests(@AuthenticationPrincipal User user) {
        return requestComponentService.findMyRequests(userService.getAuthenticatedUserId(user)).stream().map(requestComponentMapper::mapToDto).toList();
    }

    /*
    @GetMapping("/{id}")
    public Mono<ComponentResponseDto> getComponent(@PathVariable Long id) {
        return componentService.findById(id).map(componentMapper::mapToDto)
                .onErrorResume(ResourceNotFoundException.class, Mono::error);
    }
     */

    @GetMapping(path = "/{sku}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE')")
    public ComponentResponseDto getComponentBySku(@PathVariable UUID sku) {
        return componentMapper.mapToDto(componentService.findBySku(sku));
    }

    @GetMapping(path = "/article/{articleNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE')")
    public ComponentResponseDto getComponentByArticleNo(@PathVariable String articleNo) {
        return componentMapper.mapToDto(componentService.findByArticleNo(articleNo));
    }

    /**
     * Orders components from one supplier. Every entry of the body becomes its own row in
     * {@code request_components}, in status OPEN - the same component may appear twice, and then it
     * is two requests rather than one of double the quantity.
     * <p>
     * {@code componentId} carries the component's <strong>SKU</strong>, not its numeric id. The body
     * is the bare array or the wrapped {@code {"items": [...]}}, see
     * {@link RequestComponentsRequest}. An unknown SKU refuses the whole request with 404, a
     * supplierId that is no supplier with 400.
     */
    @PostMapping(path = "/request/{supplierId}", version = "1.0")
    // WAREHOUSE on top of the class-level ADMIN/MANAGER, and a method annotation because it widens
    // them: the warehouse owns the stock (StockController is ADMIN/WAREHOUSE) and already consumes
    // components through POST /produce, so it is the role that sees a shelf run empty. Note there is
    // no internal release step - RequestStatus.APPROVED is the supplier answering, not a manager
    // signing off - so whoever may call this orders straight away.
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE')")
    @ResponseStatus(HttpStatus.CREATED)
    public List<RequestComponentResponse> requestComponents(@PathVariable Long supplierId,
                                                            @Valid @RequestBody RequestComponentsRequest request) {
        return componentService.requestComponents(supplierId, request);
    }

    

    /**
     * The supplier accepts the request: OPEN to APPROVED. Only the supplier it was placed with may
     * answer it - anybody else is a 403, ADMIN excepted - and any other status is a 409.
     */
    @PostMapping(path = "/supplier/{requestId}/approve", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','SUPPLIER')")
    public RequestComponentResponse approveRequest(@PathVariable Long requestId,
                                                   @AuthenticationPrincipal User authUser) {
        return componentService.approveRequest(requestId, userService.getAuthenticatedUserId(authUser));
    }

    /** The supplier has sent the goods: APPROVED to IN_TRANSIT. */
    @PostMapping(path = "/supplier/{requestId}/in-transit", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','SUPPLIER')")
    public RequestComponentResponse requestInTransit(@PathVariable Long requestId,
                                                     @AuthenticationPrincipal User authUser) {
        return componentService.requestInTransit(requestId, userService.getAuthenticatedUserId(authUser));
    }

    /**
     * The supplier reports the goods handed over: IN_TRANSIT to DELIVERED - their last step. Nothing
     * is booked here; the warehouse answers it with the goods receipt.
     */
    @PostMapping(path = "/supplier/{requestId}/delivered", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','SUPPLIER')")
    public RequestComponentResponse requestDelivered(@PathVariable Long requestId,
                                                     @AuthenticationPrincipal User authUser) {
        return componentService.requestDelivered(requestId, userService.getAuthenticatedUserId(authUser));
    }

    /**
     * The warehouse books a delivery in: IN_TRANSIT to IN_STOCK, and the requested quantity is added
     * to the stock of this component's SKU in the given storehouse. Only from DELIVERED - the
     * supplier reports the handover first. The storehouse is named here
     * because the request does not carry one - booked in is where the goods actually arrived.
     * <p>
     * WAREHOUSE and ADMIN: it is a physical receipt at the dock, which the supplier cannot report and
     * which belongs to the role that owns the stock. Unknown storehouse 404, any other status 409.
     */
    @PostMapping(path = "/warehouse/{requestId}/in-stock/{storehouseId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public RequestComponentResponse receiveRequest(@PathVariable Long requestId,
                                                   @PathVariable Long storehouseId,
                                                   @AuthenticationPrincipal User authUser) {
        return componentService.receiveRequest(requestId, storehouseId,
                userService.getAuthenticatedUserId(authUser));
    }

    @PostMapping(path = "/", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public ComponentResponseDto createComponent(@RequestBody Component component) {
        return componentMapper.mapToDto(componentService.create(component));
    }

    @PutMapping(path = "/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public ComponentResponseDto updateComponent(@PathVariable Long id, @RequestBody Component component) {
        return componentMapper.mapToDto(componentService.update(id, component));
    }

    @DeleteMapping(path = "/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public void deleteComponent(@PathVariable Long id) {
        componentService.deleteById(id);
    }
}
