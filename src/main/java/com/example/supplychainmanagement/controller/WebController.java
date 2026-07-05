package com.example.supplychainmanagement.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebController {

    @Value("${app.name}")
    private String appName;

    private static final String TITLE_ATTRIBUTE = "title";

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute(TITLE_ATTRIBUTE, appName);

        return "index";
    }

    @GetMapping("/install")
    public String install() {
        return "install";
    }
}
