package com.supplychainmanagement.controller;

import com.supplychainmanagement.annotation.NoCheck;
import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.mapper.UserMapper;
import com.supplychainmanagement.dto.user.UserDto;
import com.supplychainmanagement.dto.user.UserRequestDto;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}/users"})
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    /**
     * What {@code sort} may name on the user list. {@code password} is deliberately not among them: a page
     * ordered by the hash says something about the hash. Package private, so a test can walk every entry
     * against the real query.
     */
    static final Set<String> USER_SORTABLE = Set.of("id", "username", "email", "firstName", "lastName", "userType",
            "isActive", "color", "lastLogin", "createdAt", "updatedAt");

    @NoCheck
    @GetMapping(path = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public PageResponse<UserDto> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order) {

        Pageable pageable = PageRequests.of(page, size, sort, order, USER_SORTABLE);
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
    public ResponseEntity<UserDto> create(@RequestBody UserRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userMapper.mapToDto(userService.create(request)));
    }

    @NoCheck
    @PutMapping(path = "/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    public UserDto update(@PathVariable Long id, @RequestBody UserRequestDto request) {
        return userMapper.mapToDto(userService.update(id, request));
    }

    @NoCheck
    @DeleteMapping(path = "/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
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
