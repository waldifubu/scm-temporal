package com.example.supplychainmanagement.controller;

import com.example.supplychainmanagement.dto.auth.RegisterDto;
import com.example.supplychainmanagement.model.enums.RoleEnum;
import com.example.supplychainmanagement.repository.UserRepository;
import com.example.supplychainmanagement.security.AuthService;
import com.example.supplychainmanagement.utils.InitializeData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Locale;

@Controller
public class WebController {

    private static final String TITLE_ATTRIBUTE = "title";
    private static final String MESSAGE_ATTRIBUTE = "message";
    private static final String ERROR_ATTRIBUTE = "error";
    private final InitializeData initializeData;
    private final AuthService authService;
    private final UserRepository userRepository;
    @Value("${app.name}")
    private String appName;

    public WebController(InitializeData initializeData, AuthService authService, UserRepository userRepository) {
        this.initializeData = initializeData;
        this.authService = authService;
        this.userRepository = userRepository;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute(TITLE_ATTRIBUTE, appName);

        return "index";
    }

    @GetMapping("/install")
    public String install(Model model) {
        int createdUsers = 0;
        boolean runscript = false;
        try {
            runscript = initializeData.runAfterStartup();
            createdUsers = createUsers();
        } catch (Exception e) {
            model.addAttribute(ERROR_ATTRIBUTE, e.getMessage());
        }
        model.addAttribute("runscript", runscript);
        model.addAttribute("createdUsers", createdUsers);

        return "install";
    }

    private int createUsers() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode root;
        try (var configInputStream = new ClassPathResource("configuration.json").getInputStream()) {
            root = objectMapper.readTree(configInputStream);
        }
        JsonNode roles = root.path("roles");
        if (!roles.isArray()) {
            throw new IllegalStateException("configuration.json: 'roles' must be an array.");
        }

        int createdUsers = 0;
        for (JsonNode roleEntry : roles) {
            String username = roleEntry.path("username").asText();
            String email = roleEntry.path("email").asText();

            if (username.isBlank() || email.isBlank() || userRepository.existsByUsername(username) || userRepository.existsByEmail(email)) {
                continue;
            }

            String roleLabel = roleEntry.path("role").asText();
            RoleEnum roleEnum = RoleEnum.valueOfLabel(roleLabel.toLowerCase(Locale.ROOT));
            if (roleEnum == null) {
                throw new IllegalArgumentException("Unknown role in configuration.json: " + roleLabel);
            }

            RegisterDto registerDto = new RegisterDto();
            registerDto.setFirstname(roleEntry.path("firstname").asText());
            registerDto.setLastname(roleEntry.path("lastname").asText());
            registerDto.setUsername(username);
            registerDto.setEmail(email);
            registerDto.setPassword(roleEntry.path("password").asText());
            registerDto.setRole(roleEnum.label);
            registerDto.setColor(roleEntry.path("color").asText());

            authService.register(registerDto);
            createdUsers++;
        }

        return createdUsers;
    }
}
