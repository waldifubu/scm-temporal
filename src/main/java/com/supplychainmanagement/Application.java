package com.supplychainmanagement;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.server.AppShellSettings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@StyleSheet("css/styles.css")
public class Application implements AppShellConfigurator {

    static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Override
    public void configurePage(AppShellSettings settings) {
        // Absolute path: Vaadin is mounted under /app/*, a relative href would resolve against /app/
        settings.addFavIcon("icon", "/icons/favicon.ico", "32x32");
    }
}
