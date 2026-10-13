package ek.osnb.inspector.server.api;

import ek.osnb.inspector.server.chatclient.ChatClientCall;
import ek.osnb.inspector.server.chatclient.ChatClientCallStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The starter reports every finished ChatClient call here. */
@RestController
@RequestMapping("/api/chat-client-calls")
public class ChatClientCallController {

    private final ChatClientCallStore store;

    public ChatClientCallController(ChatClientCallStore store) {
        this.store = store;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@RequestBody ChatClientCall call) {
        store.save(call);
    }

    /** Newest first. */
    @GetMapping
    public List<ChatClientCall> list() {
        return store.findAll();
    }
}
