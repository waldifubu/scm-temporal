package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.component.ComponentResponseDto;
import com.supplychainmanagement.dto.mapper.ComponentMapper;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.service.ComponentService;
import lombok.AllArgsConstructor;
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

    @GetMapping
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

    @GetMapping("/sku/{sku}")
    public ComponentResponseDto getComponentBySku(@PathVariable UUID sku) {
        return componentMapper.mapToDto(componentService.findBySku(sku));
    }

    @GetMapping("/article/{articleNo}")
    public ComponentResponseDto getComponentByArticleNo(@PathVariable String articleNo) {
        return componentMapper.mapToDto(componentService.findByArticleNo(articleNo));
    }

    @PostMapping("/")
    public ComponentResponseDto createComponent(@RequestBody Component component) {
        return componentMapper.mapToDto(componentService.create(component));
    }

    @PutMapping("/{id}")
    public ComponentResponseDto updateComponent(@PathVariable Long id, @RequestBody Component component) {
        return componentMapper.mapToDto(componentService.update(id, component));
    }

    @DeleteMapping("/{id}")
    public void deleteComponent(@PathVariable Long id) {
        componentService.deleteById(id);
    }
}
