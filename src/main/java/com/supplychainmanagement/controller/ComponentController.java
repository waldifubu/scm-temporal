package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.component.ComponentResponseDto;
import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
import com.supplychainmanagement.dto.mapper.ComponentMapper;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.service.ComponentService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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

    @GetMapping(version = "1.0")
    public List<ComponentResponseDto> getComponents() {
        return componentService.findAll().stream().map(componentMapper::mapToDto).toList();
    }

    /*
    @GetMapping("/{id}")
    public Mono<ComponentResponseDto> getComponent(@PathVariable Long id) {
        return componentService.findById(id).map(componentMapper::mapToDto)
                .onErrorResume(ResourceNotFoundException.class, Mono::error);
    }
     */

    @GetMapping(path = "/sku/{sku}", version = "1.0")
    public ComponentResponseDto getComponentBySku(@PathVariable UUID sku) {
        return componentMapper.mapToDto(componentService.findBySku(sku));
    }

    @GetMapping(path = "/article/{articleNo}", version = "1.0")
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
