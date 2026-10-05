package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.component.ComponentRequestDto;
import com.supplychainmanagement.dto.component.ComponentResponseDto;
import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
import com.supplychainmanagement.dto.mapper.ComponentMapper;
import com.supplychainmanagement.model.enums.RequestStatus;
import com.supplychainmanagement.service.ComponentService;
import com.supplychainmanagement.service.RequestComponentService;
import com.supplychainmanagement.service.UserService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
public class ComponentController {
    private final ComponentService componentService;
    private final ComponentMapper componentMapper;
    private final UserService userService;
    private final RequestComponentService requestComponentService;

    // WAREHOUSE reads as well: it may order components through /request/{supplierId}, and without
    // the catalogue it would have to get the SKU from somewhere else. Reading only - creating and
    // changing a component stays with ADMIN/MANAGER.
    @GetMapping(path = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE')")
    public List<ComponentResponseDto> getComponents() {
        return componentService.findAll().stream().map(componentMapper::mapToDto).toList();
    }

    /**
     * Component requests, all of them or those in one status - the warehouse's work list.
     * <p>
     * It exists because the goods receipt takes a request id and nothing told the warehouse which
     * ids there are: {@code GET /my-requests} below is the supplier's own list, filtered by
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
     * Plural, next to the singular {@code POST /request/{supplierId}} that creates one: the path of
     * the existing endpoint is left as clients know it.
     */
    @GetMapping(path = "/requests", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public PageResponse<RequestComponentResponse> getRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "requestDate") String sort,
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(defaultValue = "ASC") String order) {
        Sort.Direction direction = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageResponse.of(componentService.findRequests(
                status, PageRequest.of(page, size, Sort.by(direction, sort))));
    }


    @GetMapping("/{id}")
    public ComponentResponseDto getComponent(@PathVariable Long id) {
        return componentMapper.mapToDto(componentService.findById(id));
    }


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
                                                            @Valid @RequestBody RequestComponentsRequest request,
                                                            @AuthenticationPrincipal User authUser) {
        return componentService.requestComponents(supplierId, request,
                userService.getAuthenticatedUserId(authUser));
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
    @PostMapping(path = "/warehouse/{requestId}/in-stock/{storehouseId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public RequestComponentResponse receiveRequest(@PathVariable Long requestId,
                                                   @PathVariable Long storehouseId,
                                                   @AuthenticationPrincipal User authUser) {
        return componentService.receiveRequest(requestId, storehouseId,
                userService.getAuthenticatedUserId(authUser));
    }

    /**
     * Creates a component - a bill-of-materials line of one product, named by its {@code articleNo}.
     * See {@link ComponentRequestDto} for what a client may send, and why it is not the entity.
     */
    @PostMapping(path = "/", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public ComponentResponseDto createComponent(@Valid @RequestBody ComponentRequestDto component) {
        return componentMapper.mapToDto(componentService.create(component));
    }

    @PutMapping(path = "/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    /** Changes a component. The id comes from the path; the body never carries one. */
    public ComponentResponseDto updateComponent(@PathVariable Long id,
                                                @Valid @RequestBody ComponentRequestDto component) {
        return componentMapper.mapToDto(componentService.update(id, component));
    }

    @DeleteMapping(path = "/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    public void deleteComponent(@PathVariable Long id) {
        componentService.deleteById(id);
    }
}
