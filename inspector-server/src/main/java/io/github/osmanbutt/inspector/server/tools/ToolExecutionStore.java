package io.github.osmanbutt.inspector.server.tools;

import io.github.osmanbutt.inspector.server.InspectorProperties;
import io.github.osmanbutt.inspector.server.capture.ExchangeStore.StoreCleared;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recent tool executions, keyed by the model's tool call id. */
@Component
public class ToolExecutionStore {

    public record ToolExecutionSaved(ToolExecution execution) {
    }

    private final LinkedHashMap<String, ToolExecution> executions;
    private final ApplicationEventPublisher events;

    public ToolExecutionStore(InspectorProperties properties, ApplicationEventPublisher events) {
        int capacity = properties.maxExchanges() * 4;
        this.executions = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ToolExecution> eldest) {
                return size() > capacity;
            }
        };
        this.events = events;
    }

    public void save(ToolExecution execution) {
        String key = execution.toolCallId() != null ? execution.toolCallId() : UUID.randomUUID().toString();
        synchronized (this) {
            executions.put(key, execution);
        }
        events.publishEvent(new ToolExecutionSaved(execution));
    }

    /** The executions of the given tool calls; calls without a reported execution are left out. */
    public synchronized Map<String, ToolExecution> forToolCalls(Collection<String> toolCallIds) {
        Map<String, ToolExecution> found = new LinkedHashMap<>();
        for (String id : toolCallIds) {
            if (id != null && executions.containsKey(id)) {
                found.put(id, executions.get(id));
            }
        }
        return found;
    }

    /** Newest first. */
    public synchronized List<ToolExecution> findAll() {
        return new ArrayList<>(executions.sequencedValues().reversed());
    }

    @EventListener
    synchronized void onCleared(StoreCleared cleared) {
        executions.clear();
    }
}
