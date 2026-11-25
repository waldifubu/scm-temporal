package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.component.ComponentDetailDto;
import com.supplychainmanagement.dto.component.ComponentPatchRequest;
import com.supplychainmanagement.dto.component.ComponentRequestDto;
import com.supplychainmanagement.dto.component.ComponentResponseDto;
import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
import com.supplychainmanagement.dto.component.SupplierResolution;
import com.supplychainmanagement.dto.mapper.ComponentMapper;
import com.supplychainmanagement.model.common.PageRequests;
import com.supplychainmanagement.model.enums.RequestStatus;
import com.supplychainmanagement.service.ComponentService;
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
import java.util.Set;

@RestController
@RequestMapping({"/api/{version}/components"})
@AllArgsConstructor
public class ComponentController {

    /**
     * What {@code sort} may name on the warehouse's request list: the request's own columns, under the
     * entity's names ({@code requestStatus}, not {@code status}). Package private, so a test can walk
     * every entry against the real query.
     */
    static final Set<String> REQUEST_SORTABLE = Set.of("id", "qty", "requestDate", "requestStatus", "updated");

    /**
     * What the component catalogue can be sorted by: the component's own columns and the name of its product
     * (every component has one, so the join drops nothing). Package private, so a test walks every entry.
     */
    static final Set<String> COMPONENT_SORTABLE = Set.of("id", "name", "manufacturer", "externalId", "qty", "product.name");

    /** What the supplier list may be sorted by - see {@link PageRequests}. */
    private final ComponentService componentService;
    private final ComponentMapper componentMapper;
    private final UserService userService;

    // WAREHOUSE reads as well: it may order components through /request, and without
    // the catalogue it would have to get the SKU from somewhere else. Reading only - creating and
    // changing a component stays with ADMIN/MANAGER.
    @GetMapping(path = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE', 'LOGISTICS')")
    public PageResponse<ComponentResponseDto> getComponents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order) {
        var pageable = PageRequests.of(page, size, sort, order, COMPONENT_SORTABLE);
        return PageResponse.of(componentService.findPage(pageable).map(componentMapper::mapToDto));
    }

    /**
     * Component requests, all of them or those in one status - the warehouse's work list.
     * <p>
     * It exists because the goods receipt takes a request id and nothing told the warehouse which
     * ids there are: {@code GET /my-requests} in {@link InboundController} is the supplier's own
     * list, filtered by
     * {@code supplier_id}, so WAREHOUSE could not read it and an ADMIN asking it got their own empty
     * one. {@code ?status=DELIVERED} is the pile waiting at the dock; {@code IN_TRANSIT} is what is
     * coming. Same arrangement as {@code GET /shipments/distributor} and
     * {@code GET /shipments/packages} - a role gets its own view rather than read access to
     * another's.
     * <p>
     * Paged like every other list, {@code sort} over the fields of the request itself. Oldest first
     * by default, because a work list is worked off in the order things arrived - not {@code id},
     * which the other lists use.
     * <p>
     * Plural, next to the singular {@code POST /request} that creates one: the path of the existing
     * endpoint is left as clients know it.
     */
    /**
     * Whom a request for these components would go to, by the supplier rules - what the order form shows
     * next to each line before it is sent. {@code ?sku=a&sku=b}; a component no rule covers comes back
     * with empty supplier fields, an unknown SKU is a 404. Same roles as placing the request.
     */
    @GetMapping(path = "/suppliers/resolve", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE')")
    public List<SupplierResolution> resolveSuppliers(@RequestParam("sku") List<UUID> skus) {
        return componentService.resolveSuppliers(skus);
    }

    @GetMapping(path = "/requests", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public PageResponse<RequestComponentResponse> getRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "requestDate") String sort,
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(componentService.findRequests(
                status, PageRequests.of(page, size, sort, order, REQUEST_SORTABLE)));
    }

/*
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE')")
    public ComponentResponseDto getComponent(@PathVariable Long id) {
        return componentMapper.mapToDto(componentService.findById(id));
    }
*/

    @GetMapping(path = "/{sku}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE', 'LOGISTICS')")
    public ComponentDetailDto getComponentBySku(@PathVariable UUID sku) {
        return componentMapper.mapToDetailDto(componentService.findBySku(sku));
    }

    @GetMapping(path = "/external/{externalId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE', 'LOGISTICS')")
    public ComponentResponseDto getComponentByExternalId(@PathVariable String externalId) {
        return componentMapper.mapToDto(componentService.findByExternalId(externalId));
    }

    /**
     * Orders components; the supplier of each line is given by the supplier rules (see
     * {@code ComponentService.requestComponents}), a component no rule covers refuses the whole request
     * with 409. Every entry of the body becomes its own row in
     * {@code request_components}, in status OPEN - the same component may appear twice, and then it
     * is two requests rather than one of double the quantity.
     * <p>
     * {@code componentId} carries the component's <strong>SKU</strong>, not its numeric id. The body
     * is the bare array or the wrapped {@code {"items": [...]}}, see
     * {@link RequestComponentsRequest}. An unknown SKU refuses the whole request with 404.
     */
    // WAREHOUSE on top of the class-level ADMIN/MANAGER, and a method annotation because it widens
    // them: the warehouse owns the stock (StockController is ADMIN/WAREHOUSE) and already consumes
    // components through POST /produce, so it is the role that sees a shelf run empty. Note there is
    // no internal release step - RequestStatus.APPROVED is the supplier answering, not a manager
    // signing off - so whoever may call this orders straight away.
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE')")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(path = "/request", version = "1.0")
    public List<RequestComponentResponse> requestComponents(@Valid @RequestBody RequestComponentsRequest request,
                                                            @AuthenticationPrincipal User authUser) {
        return componentService.requestComponents(request, userService.getAuthenticatedUserId(authUser));
    }


    /**
     * The warehouse books a delivery in: DELIVERED to IN_STOCK, and the requested quantity is added
     * to the stock of this component's SKU in the given storehouse. Only from DELIVERED - the
     * supplier reports the handover first. The storehouse is named here
     * because the request does not carry one - booked in is where the goods actually arrived.
     * <p>
     * WAREHOUSE and ADMIN: it is a physical receipt at the dock, which the supplier cannot report and
     * which belongs to the role that owns the stock. Unknown storehouse 404, any other status 409.
     */
    @PostMapping(path = {"/warehouse/{requestId}/in-stock", "/warehouse/{requestId}/in-stock/{storehouseId}"}, version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public RequestComponentResponse receiveRequest(@PathVariable Long requestId,
                                                   @PathVariable(required = false) Long storehouseId,
                                                   @AuthenticationPrincipal User authUser) {
        return componentService.receiveRequest(requestId, storehouseId,
                userService.getAuthenticatedUserId(authUser));
    }

    /**
     * Creates a component - a bill-of-materials line of one product, named by its {@code articleNo}.
     * See {@link ComponentRequestDto} for what a client may send, and why it is not the entity.
     */
    @PostMapping(path = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public ComponentResponseDto createComponent(@Valid @RequestBody ComponentRequestDto component) {
        return componentMapper.mapToDto(componentService.create(component));
    }

    /**
     * Changes name, manufacturer, description, externalId, weight and/or qty of one component, addressed by its SKU. Fields left out
     * stay as they are; see {@link ComponentPatchRequest}.
     */
    @PatchMapping(path = "/{sku}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER', 'WAREHOUSE')")
    @ResponseStatus(HttpStatus.OK)
    public ComponentDetailDto patchComponent(@PathVariable UUID sku,
                                             @Valid @RequestBody ComponentPatchRequest request) {
        return componentMapper.mapToDetailDto(componentService.patch(sku, request));
    }

    @DeleteMapping(path = "/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    public void deleteComponent(@PathVariable Long id) {
        componentService.deleteById(id);
    }
}
