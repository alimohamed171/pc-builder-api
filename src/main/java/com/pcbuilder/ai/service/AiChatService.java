package com.pcbuilder.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcbuilder.ai.dto.request.ChatRequest;
import com.pcbuilder.ai.dto.response.ChatResponse;
import com.pcbuilder.ai.entity.ChatMessage;
import com.pcbuilder.ai.entity.ChatSession;
import com.pcbuilder.ai.entity.MessageRole;
import com.pcbuilder.ai.repository.ChatMessageRepository;
import com.pcbuilder.ai.repository.ChatSessionRepository;
import com.pcbuilder.auth.entity.User;
import com.pcbuilder.auth.repository.UserRepository;
import com.pcbuilder.exception.ResourceNotFoundException;
import com.pcbuilder.product.dto.ProductDto;
import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.entity.ProductCategory;
import com.pcbuilder.product.mapper.ProductMapper;
import com.pcbuilder.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

// 1. Must be a record so Spring AI can generate a schema for it
record ChatAiResult(String reply, List<Long> mentionedProductIds) {}

@Service
@RequiredArgsConstructor
@Slf4j
public class AiChatService {

    // 2. Optimized prompt explicitly telling the AI to batch its requests
    private static final String SYSTEM_INSTRUCTION = """
        You are a helpful hardware assistant for a PC-building website called pcbuilder.
        Your ONLY job is to help users with PC hardware: choosing components, checking
        compatibility, comparing builds, and explaining hardware concepts.

        CRITICAL TOOL USE RULES:
        1. You have a "searchProducts" tool that accepts a LIST of categories.
        2. When asked to build a PC, you MUST call the tool ONE TIME, passing ALL required categories at once (e.g., ["CPU", "MOTHERBOARD", "GPU", "MEMORY"]).
        3. Never invent product names, IDs, or prices. Use only what the tool returns.
        4. If the tool returns no results, honestly state that.
        """;

    private final ChatClient chatClient;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    // Tracks product ids returned by the tool during the current request only
    private final ThreadLocal<Set<Long>> seenProductIds = ThreadLocal.withInitial(LinkedHashSet::new);

    @Transactional
    public ChatResponse chat(ChatRequest request, Long userId) {
        ChatSession session = resolveSession(request.getSessionId(), userId);

        ChatMessage userMessage = ChatMessage.builder()
                .session(session)
                .role(MessageRole.USER)
                .content(request.getMessage())
                .build();
        messageRepository.save(userMessage);

        List<ChatMessage> history = messageRepository
                .findTop20BySessionIdOrderByCreatedAtDesc(session.getId());
        Collections.reverse(history);

        List<Message> conversation = history.stream()
                .map(m -> m.getRole() == MessageRole.USER
                        ? (Message) new UserMessage(m.getContent())
                        : new AssistantMessage(m.getContent()))
                .collect(Collectors.toList());

        seenProductIds.get().clear();

        // Let Spring AI handle the JSON formatting and schema generation
        BeanOutputConverter<ChatAiResult> converter = new BeanOutputConverter<>(ChatAiResult.class);
        String promptWithFormat = SYSTEM_INSTRUCTION + "\n\n" + converter.getFormat();

        // Get the raw string first so we can salvage it if it breaks
        String rawJson = chatClient.prompt()
                .system(promptWithFormat)
                .messages(conversation)
                .tools(this)
                .call()
                .content();

        // Parse using our bulletproof fallback method
        ChatAiResult parsed = parseRobustly(rawJson, converter);

        ChatMessage assistantMessage = ChatMessage.builder()
                .session(session)
                .role(MessageRole.ASSISTANT)
                .content(parsed.reply())
                .build();
        messageRepository.save(assistantMessage);

        if (session.getTitle() == null) {
            session.setTitle(truncate(request.getMessage(), 60));
        }
        sessionRepository.save(session);

        Set<Long> validSeen = seenProductIds.get();
        List<Long> validIds = parsed.mentionedProductIds().stream()
                .filter(validSeen::contains)
                .collect(Collectors.toList());

        // THE SMART FALLBACK: If the model broke the JSON but DID use the tool,
        // automatically attach the products it looked at!
        if (validIds.isEmpty() && !validSeen.isEmpty()) {
            validIds = new ArrayList<>(validSeen);
        }

        List<ProductDto> mentionedProducts = validIds.isEmpty()
                ? List.of()
                : productRepository.findByIdIn(validIds).stream()
                .map(productMapper::toDto)
                .collect(Collectors.toList());

        seenProductIds.remove();

        return new ChatResponse(session.getId(), parsed.reply(), mentionedProducts, LocalDateTime.now());
    }

    /**
     * Attempts standard JSON parsing, but falls back to aggressive text scraping if the LLM mangles it.
     */
    private ChatAiResult parseRobustly(String rawOutput, BeanOutputConverter<ChatAiResult> converter) {
        try {
            return converter.convert(rawOutput);
        } catch (Exception e) {
            log.warn("LLM generated invalid JSON. Salvaging text. Raw: {}", rawOutput);
            String cleanText = rawOutput;

            // Aggressive fallback to rip out just the reply text for the user
            if (rawOutput.contains("\"reply\"")) {
                try {
                    String[] parts = rawOutput.split("\"reply\"\\s*:\\s*\"", 2);
                    if (parts.length > 1) {
                        cleanText = parts[1];
                        int end = cleanText.lastIndexOf("\",");
                        if (end != -1) cleanText = cleanText.substring(0, end);
                        cleanText = cleanText.replace("\\n", "\n").replace("\\\"", "\"");
                    }
                } catch (Exception ex) {
                    // Ignore regex errors
                }
            }

            // If all else fails, just strip out the curly braces
            if (cleanText.startsWith("{")) {
                cleanText = cleanText.replaceAll("[{}]", "").trim();
            }

            return new ChatAiResult(cleanText, new ArrayList<>());
        }
    }

    /**
     * MULTI-SEARCH TOOL: Drastically reduces API round-trips and token usage
     * by allowing the LLM to request multiple categories at the exact same time.
     */
    @Tool(description = "Search the real-time product catalog for ONE OR MORE hardware categories at the same time.")
    public String searchProducts(
            @ToolParam(description = "List of categories to search (e.g. ['CPU', 'GPU', 'MOTHERBOARD']). Valid values: CPU, MOTHERBOARD, GPU, PSU, CASE, COOLER, MEMORY, STORAGE")
            List<String> categories) {

        if (categories == null || categories.isEmpty()) {
            return "{}";
        }

        // 1. Convert strings to valid enums safely
        List<ProductCategory> validCats = categories.stream()
                .map(c -> {
                    try { return ProductCategory.valueOf(c.toUpperCase()); }
                    catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (validCats.isEmpty()) {
            return "{}";
        }

        // 2. THE MEGA-OPTIMIZATION: Call your new Repository method!
        // This hits the database EXACTLY ONCE using the IN (?, ?) clause.
        List<Product> allProducts = productRepository.findByCategoryIn(validCats);

        // 3. Group the results by category in Java memory
        Map<ProductCategory, List<Product>> groupedProducts = allProducts.stream()
                .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                .collect(Collectors.groupingBy(Product::getCategory));

        // 4. Build the JSON for the AI
        StringBuilder combinedResults = new StringBuilder("{");
        boolean firstCat = true;

        for (ProductCategory cat : validCats) {
            List<Product> products = groupedProducts.getOrDefault(cat, List.of()).stream()
                    .sorted(Comparator.comparing(Product::getPriceEgp))
                    .limit(4) // Limit to 4 per category to save AI tokens
                    .collect(Collectors.toList());

            if (products.isEmpty()) continue;

            if (!firstCat) combinedResults.append(",");
            firstCat = false;

            combinedResults.append("\"").append(cat.name()).append("\":[");

            for (int i = 0; i < products.size(); i++) {
                Product p = products.get(i);
                seenProductIds.get().add(p.getId());

                if (i > 0) combinedResults.append(",");

                String name = (p.getMatchedGlobalName() != null ? p.getMatchedGlobalName() : p.getRawName())
                        .replace("\"", "'");

                // Lightweight JSON to save tokens
                combinedResults.append(String.format(
                        "{\"id\":%d,\"name\":\"%s\",\"price\":%s}",
                        p.getId(), name, p.getPriceEgp()
                ));
            }
            combinedResults.append("]");
        }

        combinedResults.append("}");
        return combinedResults.toString();
    }
    private ChatSession resolveSession(Long sessionId, Long userId) {
        if (sessionId == null) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
            ChatSession newSession = ChatSession.builder().user(user).build();
            return sessionRepository.save(newSession);
        }
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found"));
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}