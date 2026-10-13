package ek.osnb.inspector.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InspectorServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(InspectorServerApplication.class, args);
    }
}
