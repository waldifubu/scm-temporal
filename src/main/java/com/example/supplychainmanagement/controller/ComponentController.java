package com.example.supplychainmanagement.controller;

import com.example.supplychainmanagement.entity.Component;
import com.example.supplychainmanagement.exception.ResourceNotFoundException;
import com.example.supplychainmanagement.dto.mapper.ComponentMapper;
import com.example.supplychainmanagement.dto.component.ComponentResponseDto;
import com.example.supplychainmanagement.service.ComponentService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/components")
@AllArgsConstructor
public class ComponentController {
    private final ComponentService componentService;
    private final ComponentMapper componentMapper;

    @GetMapping
    public Flux<ComponentResponseDto> getComponents() {
        return componentService.findAll().map(componentMapper::mapToDto);
    }

    @GetMapping("/{id}")
    public Mono<ComponentResponseDto> getComponent(@PathVariable Long id) {
        return componentService.findById(id).map(componentMapper::mapToDto)
                .onErrorResume(ResourceNotFoundException.class, Mono::error);
    }

    @GetMapping("/sku/{sku}")
    public Mono<ComponentResponseDto> getComponentBySku(@PathVariable String sku) {
        return componentService.findBySku(sku).map(componentMapper::mapToDto);
    }

    @GetMapping("/article/{articleNo}")
    public Mono<ComponentResponseDto> getComponentByArticleNo(@PathVariable String articleNo) {
        return componentService.findByArticleNo(articleNo).map(componentMapper::mapToDto);
    }

    @PostMapping("/")
    public Mono<ComponentResponseDto> createComponent(@RequestBody Component component) {
        return componentService.create(component).map(componentMapper::mapToDto);
    }

    @PutMapping("/{id}")
    public Mono<ComponentResponseDto> updateComponent(@PathVariable Long id, @RequestBody Component component) {
        return componentService.update(id, component).map(componentMapper::mapToDto);
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteComponent(@PathVariable Long id) {
        return componentService.deleteById(id);
    }
}
