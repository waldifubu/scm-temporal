package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.auth.RegisterDto;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.security.AuthService;
import com.supplychainmanagement.utils.InitializeData;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;
    @Value("${app.name}")
    private String appName;

    public WebController(InitializeData initializeData, AuthService authService, UserRepository userRepository,
                         ObjectMapper objectMapper) {
        this.initializeData = initializeData;
        this.authService = authService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
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
        JsonNode root;
        try (var configInputStream = new ClassPathResource("configuration.json").getInputStream()) {
            root = objectMapper.readTree(configInputStream);
        }
        JsonNode users = root.path("users");
        if (!users.isArray()) {
            throw new IllegalStateException("configuration.json: 'users' must be an array.");
        }

        int createdUsers = 0;
        for (JsonNode userEntry : users) {
            String username = userEntry.path("username").asString();
            String email = userEntry.path("email").asString();

            if (username.isBlank() || email.isBlank() || userRepository.existsByUsername(username) || userRepository.existsByEmail(email)) {
                continue;
            }

            String roleLabel = userEntry.path("role").asString();
            RoleEnum roleEnum = RoleEnum.valueOfLabel(roleLabel.toUpperCase(Locale.ROOT));
            if (roleEnum == null) {
                throw new IllegalArgumentException("Unknown role in configuration.json: " + roleLabel);
            }

            RegisterDto registerDto = new RegisterDto();
            registerDto.setFirstname(userEntry.path("firstname").asString());
            registerDto.setLastname(userEntry.path("lastname").asString());
            registerDto.setUsername(username);
            registerDto.setEmail(email);
            registerDto.setPassword(userEntry.path("password").asString());
            registerDto.setRole(roleEnum.name());
            registerDto.setColor(userEntry.path("color").asString());

            authService.register(registerDto);
            createdUsers++;
        }

        return createdUsers;
    }
}
