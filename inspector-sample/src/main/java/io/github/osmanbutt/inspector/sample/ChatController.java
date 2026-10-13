package io.github.osmanbutt.inspector.sample;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder, ClockTools clockTools, WeatherTools weatherTools) {
        // Chat memory adds the earlier turns of a conversation to the prompt: the inspector shows it doing so.
        ChatMemory memory = MessageWindowChatMemory.builder().build();
        this.chatClient = builder
                .defaultSystem("You are a helpful assistant. Use the tools when they help. Answer briefly.")
                .defaultTools(clockTools, weatherTools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }

    /** Pass the same {@code conversation} again to continue it; without one, each question starts fresh. */
    @GetMapping("/ask")
    public String ask(@RequestParam String q, @RequestParam(required = false) String conversation) {
        return chatClient.prompt()
                .user(q)
                .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId(conversation)))
                .call()
                .content();
    }

    @GetMapping(path = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> askStream(@RequestParam String q, @RequestParam(required = false) String conversation) {
        return chatClient.prompt()
                .user(q)
                .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId(conversation)))
                .stream()
                .content();
    }

    private static String conversationId(String conversation) {
        return conversation != null ? conversation : UUID.randomUUID().toString();
    }
}
