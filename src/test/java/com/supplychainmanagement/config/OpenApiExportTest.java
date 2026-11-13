package com.supplychainmanagement.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Writes the OpenAPI description to a file for the frontend's type generator. Does nothing unless
 * {@code -Dopenapi.export=<file>} is given, so an ordinary {@code mvn test} leaves no file behind:
 * <pre>mvn test -Dtest=OpenApiExportTest -Dopenapi.export=../scm-frontend/openapi/api.json</pre>
 * The file is indented, so a diff of it is a diff of the API.
 */
@SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiExportTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void writesTheDescriptionWhereAskedTo() throws Exception {
        String target = System.getProperty("openapi.export");
        assumeThat(target).as("openapi.export not set").isNotBlank();

        String body = mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs")
                        .header("X-API-KEY", "openapi-export"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Path file = Path.of(target).toAbsolutePath();
        Files.createDirectories(file.getParent());
        ObjectMapper json = new ObjectMapper();
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(body)) + "\n",
                StandardCharsets.UTF_8);
    }
}
