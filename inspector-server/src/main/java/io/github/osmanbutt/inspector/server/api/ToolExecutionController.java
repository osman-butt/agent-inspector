package io.github.osmanbutt.inspector.server.api;

import io.github.osmanbutt.inspector.server.tools.ToolExecution;
import io.github.osmanbutt.inspector.server.tools.ToolExecutionStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The starter reports every tool execution here. */
@RestController
@RequestMapping("/api/tool-executions")
public class ToolExecutionController {

    private final ToolExecutionStore store;

    public ToolExecutionController(ToolExecutionStore store) {
        this.store = store;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@RequestBody ToolExecution execution) {
        store.save(execution);
    }

    /** Newest first. */
    @GetMapping
    public List<ToolExecution> list() {
        return store.findAll();
    }
}
