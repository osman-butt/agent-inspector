package ek.osnb.inspector.sample;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder, ClockTools clockTools, WeatherTools weatherTools) {
        this.chatClient = builder
                .defaultSystem("You are a helpful assistant. Use the tools when they help. Answer briefly.")
                .defaultTools(clockTools, weatherTools)
                .build();
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        return chatClient.prompt().user(q).call().content();
    }

    @GetMapping(path = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> askStream(@RequestParam String q) {
        return chatClient.prompt().user(q).stream().content();
    }
}
