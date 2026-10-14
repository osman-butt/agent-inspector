package io.github.osmanbutt.inspector.server.proxy;

/** A route name that already forwards somewhere else. */
public class RouteTakenException extends RuntimeException {

    public RouteTakenException(String message) {
        super(message);
    }
}
