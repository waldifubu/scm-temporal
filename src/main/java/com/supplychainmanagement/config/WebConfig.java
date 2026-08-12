package com.supplychainmanagement.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${spring.mvc.apiversion.default:2.0}")
    String defaultVersion;

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
                // Choose ONE of these approaches (they cannot be mixed)
                //.usePathSegment(1)                              // Path-based: /api/v1/users
                //.useRequestHeader("X-API-Version")              // Header-based
                //.useQueryParam("version")                       // Query parameter-based
                //.useMediaTypeParameter(MediaType.APPLICATION_JSON, "version")  // Media type

                /* Header
                 configurer.addSupportedVersions("1.0", "2.0")
                 .setDefaultVersion("1.0")
                 .useRequestHeader("X-API-Version");
                 */
//                .usePathSegment(1)
//                .addSupportedVersions("1.0", "2.0")
//                .setDefaultVersion("2.0")

//                .addSupportedVersions("1.0", "1.1", "2.0")
//                .setDefaultVersion(defaultVersion)
                .useVersionResolver(request -> {
                    String uri = request.getRequestURI();

                    // 1. Nicht-API-Routen (UI, Thymeleaf, HTML, CSS) sofort ignorieren
                    if (!uri.startsWith("/api/")) {
                        return null; // Liefert keine Version -> Spring verarbeitet die Route als normale Web-Route
                    }

                    // 2. Sichere Pfad-Prüfung für API-Routen
                    String[] segments = uri.split("/");

                    // Beispiel: "/api/1.0/auth/login" -> segments[1] = "api", segments[2] = "1.0"
                    if (segments.length > 2 && "api".equalsIgnoreCase(segments[1])) {
                        String candidate = segments[2];
                        if (candidate.matches("[0-9]+\\.[0-9]+")) {
                            return candidate;
                        }
                    }

                    // Falls /api/ aufgerufen wurde, aber keine Version vorhanden ist
                    return null;
                });
    }

}