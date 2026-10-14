package io.github.osmanbutt.inspector.starter;

import io.github.osmanbutt.inspector.server.InspectorServerApplication;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Runs the inspector inside the application's JVM, as a separate Spring application on its own
 * port, when no inspector is running. One per JVM: it outlives the application context, so a
 * DevTools restart finds it again and the recorded traffic stays.
 *
 * <p>Its web server threads are not daemons, so they would keep the JVM alive after a
 * command-line application is done. They all live in {@link #GROUP}, and {@link #stopWhenHostEnds()}
 * closes the inspector once no other non-daemon thread is left.
 */
final class EmbeddedInspector {

    /** Threads of the inspector's web server inherit this group from the thread that starts it. */
    static final ThreadGroup GROUP = new ThreadGroup("agent-inspector");

    private static final Duration POLL = Duration.ofMillis(500);

    private static ConfigurableApplicationContext running;
    private static Thread watcher;

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
        running = runInGroup(application, "--server.port=" + port, "--server.address=127.0.0.1",
                "--agent.inspector.embedded=true");
        return true;
    }

    private static ConfigurableApplicationContext runInGroup(SpringApplication application, String... args) {
        AtomicReference<ConfigurableApplicationContext> context = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = new Thread(GROUP, () -> {
            try {
                context.set(application.run(args));
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "agent-inspector-starter");
        starter.start();
        // Waits even when interrupted: the starter finishes anyway, and an inspector nobody holds
        // could never be stopped.
        boolean interrupted = false;
        while (starter.isAlive()) {
            try {
                starter.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure.get() instanceof RuntimeException e) {
            throw e;
        } else if (failure.get() != null) {
            throw new IllegalStateException(failure.get());
        }
        return context.get();
    }

    /**
     * Closes the inspector when the application has nothing left running, so a command-line
     * application can exit. A web application keeps its own non-daemon threads, so it never
     * triggers this. Does nothing when no inspector runs in this JVM; safe to call repeatedly.
     */
    static void stopWhenHostEnds() {
        stopWhenHostEnds(EmbeddedInspector::hostThreadsAlive, POLL);
    }

    static synchronized void stopWhenHostEnds(BooleanSupplier hostAlive, Duration poll) {
        if (running == null || watcher != null) {
            return;
        }
        ConfigurableApplicationContext context = running;
        watcher = new Thread(() -> {
            // Two polls in a row, so the application's last reports are posted before it goes away.
            int idle = 0;
            while (idle < 2) {
                idle = hostAlive.getAsBoolean() ? 0 : idle + 1;
                try {
                    Thread.sleep(poll);
                } catch (InterruptedException e) {
                    return;
                }
            }
            stop(context);
        }, "agent-inspector-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static boolean hostThreadsAlive() {
        ThreadGroup root = GROUP;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        Thread[] threads = new Thread[root.activeCount() + 16];
        int count = root.enumerate(threads, true);
        for (int i = 0; i < count; i++) {
            Thread thread = threads[i];
            if (thread.isAlive() && !thread.isDaemon() && !"DestroyJavaVM".equals(thread.getName())
                    && !inInspector(thread.getThreadGroup())) {
                return true;
            }
        }
        return false;
    }

    private static boolean inInspector(ThreadGroup group) {
        for (ThreadGroup g = group; g != null; g = g.getParent()) {
            if (g == GROUP) {
                return true;
            }
        }
        return false;
    }

    static synchronized boolean isRunning() {
        return running != null && running.isActive();
    }

    static synchronized boolean isWatching() {
        return watcher != null;
    }

    /** For tests; otherwise the inspector stops with the JVM or when the host ends. */
    static synchronized void stop() {
        if (watcher != null) {
            if (watcher != Thread.currentThread()) {
                watcher.interrupt(); // the watcher stopping the inspector itself must still close it cleanly
            }
            watcher = null;
        }
        if (running != null) {
            running.close();
            running = null;
        }
    }

    /** Stops the inspector a watcher was started for, never one started after it. */
    private static synchronized void stop(ConfigurableApplicationContext context) {
        if (running == context) {
            stop();
        }
    }
}
