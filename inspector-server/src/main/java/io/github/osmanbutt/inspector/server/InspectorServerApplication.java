package io.github.osmanbutt.inspector.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.HttpEncodingAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

import java.util.Map;

/**
 * The inspector, run standalone or started by the starter inside an application. It imports
 * only the web stack it needs instead of enabling all auto-configuration, so whatever else is on
 * a host application's classpath (a DataSource, Spring AI, Security, ...) is left alone.
 */
@SpringBootConfiguration
@ComponentScan
@ConfigurationPropertiesScan
@ImportAutoConfiguration({
        PropertyPlaceholderAutoConfiguration.class,
        ConfigurationPropertiesAutoConfiguration.class,
        TaskExecutionAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        ErrorMvcAutoConfiguration.class,
        HttpEncodingAutoConfiguration.class})
public class InspectorServerApplication {

    /**
     * Defaults for running the inspector; any other property source overrides them. Kept in code
     * rather than in application.properties, which would clash with a host application's own.
     */
    public static final Map<String, Object> DEFAULTS = Map.of(
            "spring.application.name", "agent-inspector",
            // Captured traffic contains prompts and tool results: listen on localhost only.
            "server.port", 7070,
            "server.address", "127.0.0.1",
            // The proxy blocks while it waits for the model, so give every request its own virtual thread.
            "spring.threads.virtual.enabled", true);

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(InspectorServerApplication.class);
        application.setDefaultProperties(DEFAULTS);
        application.run(args);
    }
}
