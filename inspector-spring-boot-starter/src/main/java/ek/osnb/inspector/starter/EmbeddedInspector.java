package ek.osnb.inspector.starter;

import ek.osnb.inspector.server.InspectorServerApplication;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

/**
 * Runs the inspector inside the application's JVM, as a separate Spring application on its own
 * port, when no inspector is running. One per JVM: it outlives the application context, so a
 * DevTools restart finds it again and the recorded traffic stays.
 */
final class EmbeddedInspector {

    private static ConfigurableApplicationContext running;

    private EmbeddedInspector() {
    }

    /** True when the inspector is listening on {@code port} afterwards. */
    static synchronized boolean start(int port) {
        if (running != null && running.isActive()) {
            return false; // already running on another port
        }
        SpringApplication application = new SpringApplication(InspectorServerApplication.class);
        // No listeners and initializers: the host's config files, environment post-processors
        // (ours included), DevTools and logging setup must not run a second time for the inspector.
        application.setListeners(List.of());
        application.setInitializers(List.of());
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setDefaultProperties(InspectorServerApplication.DEFAULTS);
        // Command-line arguments win over the host's environment variables, like SERVER_PORT.
        running = application.run("--server.port=" + port, "--server.address=127.0.0.1",
                "--agent.inspector.embedded=true");
        return true;
    }

    /** For tests; otherwise the inspector stops with the JVM. */
    static synchronized void stop() {
        if (running != null) {
            running.close();
            running = null;
        }
    }
}
