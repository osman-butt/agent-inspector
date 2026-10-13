package io.github.osmanbutt.inspector.server.api;

import io.github.osmanbutt.inspector.server.session.SessionDetail;
import io.github.osmanbutt.inspector.server.session.SessionSummary;
import io.github.osmanbutt.inspector.server.session.SessionViews;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionViews views;

    public SessionController(SessionViews views) {
        this.views = views;
    }

    /** Newest first. */
    @GetMapping
    public List<SessionSummary> list() {
        return views.summaries();
    }

    @GetMapping("/{id}")
    public SessionDetail get(@PathVariable String id) {
        return views.detail(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No session " + id));
    }
}
