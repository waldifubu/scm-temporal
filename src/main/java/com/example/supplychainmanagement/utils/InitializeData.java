package com.example.supplychainmanagement.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class InitializeData {

    private final DataSource dataSource;

    private final String FIRSTRUN = "firstrun";

    @Value("${app.mysqldatafile}")
    private String sqlDataFile;

    public InitializeData(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    //@EventListener(ApplicationReadyEvent.class)
    //@Order(Ordered.LOWEST_PRECEDENCE - 1)
    public boolean runAfterStartup() throws Exception {
        if (isFirstRunMarkerPresent()) {
            System.out.println("* File firstrun file found. Let's run SQL scripts to initialize the database.");
            runScripts();
            return true;
        }

        return false;
    }

    private boolean isFirstRunMarkerPresent() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(FIRSTRUN))) {
                return true;
            }
            current = current.getParent();
        }
        return new ClassPathResource(FIRSTRUN).exists();
    }

    private void runScripts() {
        ResourceDatabasePopulator resourceDatabasePopulator = new ResourceDatabasePopulator();
        resourceDatabasePopulator.setSqlScriptEncoding("UTF-8");
        resourceDatabasePopulator.setContinueOnError(false);
        resourceDatabasePopulator.setIgnoreFailedDrops(false);
        resourceDatabasePopulator.addScript(new ClassPathResource(sqlDataFile));

        try {
            resourceDatabasePopulator.execute(dataSource);
            System.out.println("* Delete firstrun file now");
//            Path path = Paths.get(firstrun);
//            Files.delete(path);
        } catch (Exception exception) {
            System.err.println("Source error: " + exception.getCause());
            System.err.println("Message: " + exception.getMessage());
        }
    }
}
