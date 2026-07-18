package com.pcbuilder.ai.service;

import com.pcbuilder.ai.client.GeminiClient;
import com.pcbuilder.ai.dto.gemini.GeminiContent;
import com.pcbuilder.ai.dto.request.ChatRequest;
import com.pcbuilder.ai.dto.response.ChatResponse;
import com.pcbuilder.ai.entity.ChatMessage;
import com.pcbuilder.ai.entity.ChatSession;
import com.pcbuilder.ai.entity.MessageRole;
import com.pcbuilder.ai.repository.ChatMessageRepository;
import com.pcbuilder.ai.repository.ChatSessionRepository;
import com.pcbuilder.auth.entity.User;
import com.pcbuilder.auth.repository.UserRepository;
import com.pcbuilder.common.SpecsUtil;
import com.pcbuilder.exception.ResourceNotFoundException;
import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.entity.ProductCategory;
import com.pcbuilder.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiChatService {

    private static final String SYSTEM_INSTRUCTION = """
    You are a helpful hardware assistant for a PC-building website called pcbuilder.
    Your ONLY job is to help users with PC hardware: choosing components, checking
    compatibility, comparing builds, and explaining hardware concepts.

    SCOPE RULE (critical):
    - If the user asks about anything unrelated to PC hardware/building (cooking,
      recipes, general life advice, unrelated coding help, etc.), politely decline
      and redirect them back to PC-related topics. Do NOT answer the off-topic
      question, even partially. Example: "I'm here to help with PC builds and
      hardware questions - I can't help with recipes, but let me know if you need
      help picking components!"

    IMPORTANT: If a "REAL-TIME CATALOG DATA" section is provided below, you MUST
    use ONLY those exact products and EGP prices when recommending specific items
    or quoting prices. Do NOT invent product names or prices from your own training
    data. If the catalog data doesn't contain anything relevant to the question,
    say so honestly and give general advice instead, without making up prices.

    BUDGET RULES (critical):
    - If the user has stated a budget anywhere earlier in this conversation, you
      MUST remember it and check every subsequent recommendation against it.
    - Before presenting any build or total price, explicitly state whether it fits
      within the previously mentioned budget.
    - If a full build or component exceeds the stated budget, you MUST say so
      clearly at the very start of your reply (e.g. "Note: this build is X EGP
      over your stated budget of Y EGP") - do not bury this in a footnote.
    - If asked for "a full build" after a budget was given for a single component,
      clarify whether the user means that component alone or the full PC budget,
      but still flag clearly if your suggestion exceeds whichever budget applies.
    - Never silently exceed a stated budget without a clear warning.

    Keep answers concise and practical.
    """;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final GeminiClient geminiClient;

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

        List<GeminiContent> contents = history.stream()
                .map(m -> GeminiContent.of(
                        m.getRole() == MessageRole.USER ? "user" : "model",
                        m.getContent()))
                .collect(Collectors.toList());

        // inject real catalog data relevant to this specific message
        String catalogContext = buildCatalogContext(request.getMessage());
        if (!catalogContext.isEmpty()) {
            // append as an extra "user" turn right before the actual message context,
            // simplest way to ground the model without touching stored history
            String lastUserContent = contents.get(contents.size() - 1).getParts().get(0).getText();
            contents.get(contents.size() - 1).getParts().get(0)
                    .setText(lastUserContent + "\n\n[REAL-TIME CATALOG DATA]\n" + catalogContext);
        }

        String reply = geminiClient.generateText(SYSTEM_INSTRUCTION, contents);

        ChatMessage assistantMessage = ChatMessage.builder()
                .session(session)
                .role(MessageRole.ASSISTANT)
                .content(reply)
                .build();
        messageRepository.save(assistantMessage);

        if (session.getTitle() == null) {
            session.setTitle(truncate(request.getMessage(), 60));
        }
        sessionRepository.save(session);

        return new ChatResponse(session.getId(), reply, LocalDateTime.now());
    }

    /**
     * Very simple keyword-based retrieval: detect which category(ies) the user
     * is asking about and pull the cheapest in-stock products from that category.
     * This is intentionally simple (no embeddings/vector search) - good enough
     * for a graduation project demo.
     */
    private String buildCatalogContext(String userMessage) {
        String lower = userMessage.toLowerCase();
        Set<ProductCategory> matchedCategories = new LinkedHashSet<>();

        if (containsAny(lower, "cpu", "processor", "معالج")) matchedCategories.add(ProductCategory.CPU);
        if (containsAny(lower, "gpu", "graphics card", "كارت شاشة", "vga")) matchedCategories.add(ProductCategory.GPU);
        if (containsAny(lower, "motherboard", "mobo", "لوحة")) matchedCategories.add(ProductCategory.MOTHERBOARD);
        if (containsAny(lower, "ram", "memory", "رامات")) matchedCategories.add(ProductCategory.MEMORY);
        if (containsAny(lower, "psu", "power supply")) matchedCategories.add(ProductCategory.PSU);
        if (containsAny(lower, "case", "casing")) matchedCategories.add(ProductCategory.CASE);
        if (containsAny(lower, "cooler", "cooling", "fan")) matchedCategories.add(ProductCategory.COOLER);

        // fallback: mentions "build" / "pc" / "gaming" with no specific part -> include a bit of everything
        if (matchedCategories.isEmpty() &&
                containsAny(lower, "build", "pc", "gaming", "budget", "recommend")) {
            matchedCategories.addAll(List.of(ProductCategory.values()));
        }

        if (matchedCategories.isEmpty()) {
            return ""; // question isn't about specific hardware, skip catalog injection
        }

        StringBuilder sb = new StringBuilder();
        for (ProductCategory category : matchedCategories) {
            List<Product> products = productRepository.findByCategory(category).stream()
                    .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                    .sorted(Comparator.comparing(Product::getPriceEgp))
                    .limit(8)
                    .collect(Collectors.toList());

            if (products.isEmpty()) continue;

            sb.append(category).append(":\n");
            for (Product p : products) {
                sb.append("- ")
                        .append(p.getMatchedGlobalName() != null ? p.getMatchedGlobalName() : p.getRawName())
                        .append(" | ").append(p.getPriceEgp()).append(" EGP")
                        .append(" | store: ").append(p.getStore())
                        .append(" | specs: ").append(SpecsUtil.parse(p.getSpecs()))
                        .append("\n");
            }
        }
        return sb.toString();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    private ChatSession resolveSession(Long sessionId, Long userId) {
        if (sessionId == null) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
            ChatSession newSession = ChatSession.builder()
                    .user(user)
                    .build();
            return sessionRepository.save(newSession);
        }
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found"));
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}