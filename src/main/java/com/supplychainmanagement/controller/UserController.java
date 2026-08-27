package com.supplychainmanagement.controller;

import com.supplychainmanagement.annotation.NoCheck;
import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.mapper.UserMapper;
import com.supplychainmanagement.dto.user.UserDto;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}/users"})
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    @NoCheck
    @GetMapping(path = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public PageResponse<UserDto> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order) {

        Sort.Direction dir = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sort));
        return toPageResponse(userService.findAll(pageable));
    }

    @NoCheck
    @GetMapping(path = "/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public UserDto getById(@PathVariable Long id) {
        return userMapper.mapToDto(userService.findById(id));
    }

    @NoCheck
    @PostMapping(path = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public ResponseEntity<UserDto> create(@RequestBody User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userMapper.mapToDto(userService.create(user)));
    }

    @NoCheck
    @PutMapping(path = "/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public UserDto update(@PathVariable Long id, @RequestBody User user) {
        return userMapper.mapToDto(userService.update(id, user));
    }

    @NoCheck
    @DeleteMapping(path = "/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public void delete(@PathVariable Long id) {
        userService.deleteById(id);
    }

    private PageResponse<UserDto> toPageResponse(Page<User> page) {
        return new PageResponse<>(
                page.getContent().stream().map(userMapper::mapToDto).toList(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize()
        );
    }
}
