package com.pcbuilder.ai.service;

import com.pcbuilder.ai.dto.request.ChatRequest;
import com.pcbuilder.ai.dto.response.ChatResponse;
import com.pcbuilder.ai.entity.ChatMessage;
import com.pcbuilder.ai.entity.ChatSession;
import com.pcbuilder.ai.entity.MessageRole;
import com.pcbuilder.ai.repository.ChatMessageRepository;
import com.pcbuilder.ai.repository.ChatSessionRepository;
import com.pcbuilder.auth.entity.User;
import com.pcbuilder.auth.repository.UserRepository;
import com.pcbuilder.bundle.dto.CompatibilityResult;
import com.pcbuilder.bundle.service.CompatibilityService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiChatService {

    /** Must be a record so Spring AI can generate a JSON schema for it. */
    private record ChatAiResult(String reply, List<Long> mentionedProductIds) {}

    private static final String SYSTEM_INSTRUCTION = """
        You are a helpful hardware assistant for a PC-building website called pcbuilder.
        Your ONLY job is to help users with PC hardware: choosing components, checking
        compatibility, comparing builds, and explaining hardware concepts. Politely
        decline and redirect if asked about anything unrelated to PC hardware.

        YOU HAVE TWO TOOLS:

        1. "buildPcForBudget" - use this whenever the user wants a FULL PC BUILD.
           - If the user has NOT stated a budget, ASK them for one first
             (e.g. "What's your budget in EGP?"). Do not call the tool yet.
           - If the user says they don't have a specific budget in mind, or asks
             you to just pick one, assume 30000 EGP and tell them you're using
             that as a default.
           - If the user asks for a bigger/higher-end build afterward, increase
             the previous budget by 5000 EGP increments unless they give an
             exact new number.
           - If the user says something ambiguous like "I need higher than that"
             and no concrete budget was established yet in this conversation,
             ask them to state a specific budget instead of guessing.
           - Once you know the budget, call buildPcForBudget EXACTLY ONCE with
             that number. Do not call searchProducts for a full build request.
           - The tool already picks compatible, real, in-budget components for
             you. Simply describe what it returns - do not change, add, or
             remove any component it gives you.
           - When describing a build, ALWAYS state the exact "totalPrice" value
             returned by the tool, copied exactly - never recalculate it yourself.
           - If you called the tool without an exact user-given number, clearly
             say so (e.g. "Since you didn't specify a budget, I used a default
             of 30,000 EGP"). Never present a build while still asking how to
             proceed - if you already built it, commit to having built it.
           - Always refer to products using the exact "name" field the tool
             returned - never rename, shorten, or substitute a different
             product name of your own.

        2. "searchProducts" - use this ONLY for questions about a SPECIFIC
           category, not a full build (e.g. "what CPUs do you have under 3000 EGP").

        CRITICAL RULES:
        - Never invent product names, IDs, or prices. Use only what a tool returns.
        - If a tool returns no results, honestly state that.
        - mentionedProductIds must contain ONLY ids that were actually part of
          your final answer - never list every id a tool showed you if you did
          not actually recommend/use all of them.
        """;

    private final ChatClient chatClient;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ProductCatalogCache productCatalogCache;
    private final CompatibilityService compatibilityService;
    private final ProductMapper productMapper;

    /** All product ids shown to the model as candidates this request (search tool). */
    private final ThreadLocal<Set<Long>> seenProductIds = ThreadLocal.withInitial(LinkedHashSet::new);

    /** The exact, final products chosen by buildPcForBudget this request, if called. */
    private final ThreadLocal<List<Product>> chosenBuildProducts = ThreadLocal.withInitial(ArrayList::new);

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
                .findTop8BySessionIdOrderByCreatedAtDesc(session.getId());
        Collections.reverse(history);

        List<Message> conversation = history.stream()
                .map(m -> m.getRole() == MessageRole.USER
                        ? (Message) new UserMessage(m.getContent())
                        : new AssistantMessage(m.getContent()))
                .collect(Collectors.toList());

        seenProductIds.get().clear();
        chosenBuildProducts.get().clear();

        BeanOutputConverter<ChatAiResult> converter = new BeanOutputConverter<>(ChatAiResult.class);
        String promptWithFormat = SYSTEM_INSTRUCTION + "\n\n" + converter.getFormat();

        String rawJson = chatClient.prompt()
                .system(promptWithFormat)
                .messages(conversation)
                .tools(this)
                .call()
                .content();

        log.info("[AI:rawFinalResponse] {}", rawJson);

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

        List<ProductDto> mentionedProducts;

        List<Product> builtProducts = chosenBuildProducts.get();
        if (!builtProducts.isEmpty()) {
            // Deterministic build tool was used this turn - this IS the ground
            // truth for what was recommended, no need to trust the model's
            // self-reported ids at all.
            mentionedProducts = builtProducts.stream()
                    .map(productMapper::toDto)
                    .collect(Collectors.toList());
        } else {
            // General search path: only trust ids the model explicitly listed,
            // validated against what was actually shown to it.
            Set<Long> validSeen = seenProductIds.get();
            List<Long> validIds = parsed.mentionedProductIds().stream()
                    .filter(validSeen::contains)
                    .collect(Collectors.toList());

            mentionedProducts = validIds.isEmpty()
                    ? List.of()
                    : productRepository.findByIdIn(validIds).stream()
                    .map(productMapper::toDto)
                    .collect(Collectors.toList());
        }

        seenProductIds.remove();
        chosenBuildProducts.remove();

        return new ChatResponse(session.getId(), parsed.reply(), mentionedProducts, LocalDateTime.now());
    }

    private ChatAiResult parseRobustly(String rawOutput, BeanOutputConverter<ChatAiResult> converter) {
        try {
            return converter.convert(rawOutput);
        } catch (Exception e) {
            log.warn("LLM generated invalid JSON. Salvaging text. Raw: {}", rawOutput);
            String cleanText = rawOutput;

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
                    // ignore, fall through to next fallback
                }
            }

            if (cleanText.startsWith("{")) {
                cleanText = cleanText.replaceAll("[{}]", "").trim();
            }

            return new ChatAiResult(cleanText, new ArrayList<>());
        }
    }

    /**
     * Deterministic budget-based build tool. Splits the given total budget
     * across categories by fixed percentages, picks the best-fitting real,
     * in-stock product per category (from the in-memory catalog cache - no
     * database hit here), and returns exactly those chosen components. The
     * model narrates this result; it never chooses the products itself.
     *
     * Uses rawName (the actual scraped product name) consistently, never
     * matchedGlobalName - matchedGlobalName can be an incorrect/mismatched
     * global-catalog guess, and using it here would make the model reason
     * about a different product than the one actually in the database.
     */
    @Tool(description = "Build a complete PC (CPU, Motherboard, GPU, Memory, PSU, Case, Cooler) for a given " +
            "total budget in EGP. Allocates the budget across categories and picks real, in-stock, " +
            "in-budget components automatically. Call this ONCE, only after you know the user's budget.")
    public String buildPcForBudget(
            @ToolParam(description = "Total budget in EGP for the whole PC build") Double budget) {

        if (budget == null || budget <= 0) {
            return "{\"error\":\"invalid budget\"}";
        }

        Map<ProductCategory, Double> allocation = new LinkedHashMap<>();
        allocation.put(ProductCategory.CPU, 0.22);
        allocation.put(ProductCategory.MOTHERBOARD, 0.14);
        allocation.put(ProductCategory.GPU, 0.32);
        allocation.put(ProductCategory.MEMORY, 0.10);
        allocation.put(ProductCategory.PSU, 0.09);
        allocation.put(ProductCategory.CASE, 0.08);
        allocation.put(ProductCategory.COOLER, 0.05);

        List<Product> picks = new ArrayList<>();

        for (Map.Entry<ProductCategory, Double> entry : allocation.entrySet()) {
            ProductCategory cat = entry.getKey();
            double subBudget = budget * entry.getValue();

            List<Product> candidates = productCatalogCache.getByCategory(cat).stream()
                    .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                    .filter(p -> looksLikeValidCategoryMatch(p, cat))
                    .sorted(Comparator.comparing(Product::getPriceEgp))
                    .collect(Collectors.toList());

            if (candidates.isEmpty()) continue;

            Product chosen = null;
            for (Product p : candidates) {
                if (p.getPriceEgp().doubleValue() <= subBudget) {
                    chosen = p;
                } else {
                    break;
                }
            }
            if (chosen == null) {
                chosen = candidates.get(0);
            }

            picks.add(chosen);
        }

        if (picks.isEmpty()) {
            return "{\"error\":\"no products available to build\"}";
        }

        chosenBuildProducts.get().clear();
        chosenBuildProducts.get().addAll(picks);
        picks.forEach(p -> seenProductIds.get().add(p.getId()));

        CompatibilityResult compatibilityResult = compatibilityService.evaluate(picks);

        BigDecimal total = picks.stream()
                .map(Product::getPriceEgp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"budget\":").append(budget)
                .append(",\"totalPrice\":").append(total)
                .append(",\"compatible\":").append(compatibilityResult.isCompatible())
                .append(",\"components\":[");

        for (int i = 0; i < picks.size(); i++) {
            Product p = picks.get(i);
            if (i > 0) sb.append(",");
            String name = p.getRawName().replace("\"", "'");
            sb.append(String.format(
                    "{\"id\":%d,\"category\":\"%s\",\"name\":\"%s\",\"price\":%s}",
                    p.getId(), p.getCategory(), name, p.getPriceEgp()
            ));
        }
        sb.append("]}");

        String result = sb.toString();
        log.info("[TOOL:buildPcForBudget] budget={} -> {}", budget, result);

        return result;
    }

    /**
     * General single-category search tool, used only for specific questions
     * that aren't a full build request (e.g. "what CPUs under 3000 EGP").
     * Uses rawName consistently, same reasoning as buildPcForBudget above.
     */
    @Tool(description = "Search the real-time product catalog for ONE OR MORE hardware categories. " +
            "Use only for specific category questions, not for full build requests.")
    public String searchProducts(
            @ToolParam(description = "List of categories to search (e.g. ['CPU', 'GPU']). Valid values: CPU, MOTHERBOARD, GPU, PSU, CASE, COOLER, MEMORY")
            List<String> categories) {

        if (categories == null || categories.isEmpty()) {
            return "{}";
        }

        List<ProductCategory> validCats = categories.stream()
                .map(c -> {
                    try {
                        return ProductCategory.valueOf(c.toUpperCase());
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (validCats.isEmpty()) {
            return "{}";
        }

        List<Product> allProducts = productCatalogCache.getByCategories(validCats);

        Map<ProductCategory, List<Product>> groupedProducts = allProducts.stream()
                .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                .collect(Collectors.groupingBy(Product::getCategory));

        StringBuilder combinedResults = new StringBuilder("{");
        boolean firstCat = true;

        for (ProductCategory cat : validCats) {
            List<Product> sorted = groupedProducts.getOrDefault(cat, List.of()).stream()
                    .sorted(Comparator.comparing(Product::getPriceEgp))
                    .collect(Collectors.toList());
            List<Product> products = selectSpreadSample(sorted, 5);

            if (products.isEmpty()) continue;

            if (!firstCat) combinedResults.append(",");
            firstCat = false;

            combinedResults.append("\"").append(cat.name()).append("\":[");

            for (int i = 0; i < products.size(); i++) {
                Product p = products.get(i);
                seenProductIds.get().add(p.getId());

                if (i > 0) combinedResults.append(",");

                String name = p.getRawName().replace("\"", "'");

                combinedResults.append(String.format(
                        "{\"id\":%d,\"name\":\"%s\",\"price\":%s}",
                        p.getId(), name, p.getPriceEgp()
                ));
            }
            combinedResults.append("]");
        }

        combinedResults.append("}");
        String result = combinedResults.toString();

        log.info("[TOOL:searchProducts] categories={} -> result={}", validCats, result);

        return result;
    }

    private List<Product> selectSpreadSample(List<Product> sorted, int limit) {
        if (sorted.size() <= limit) {
            return sorted;
        }
        List<Product> result = new ArrayList<>();
        double step = (double) sorted.size() / limit;
        for (int i = 0; i < limit; i++) {
            int index = (int) Math.round(i * step);
            if (index >= sorted.size()) index = sorted.size() - 1;
            result.add(sorted.get(index));
        }
        return result;
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

    private boolean looksLikeValidCategoryMatch(Product p, ProductCategory expectedCategory) {
        String name = p.getRawName().toLowerCase();
        return switch (expectedCategory) {
            case GPU -> name.contains("rtx") || name.contains("gtx") || name.contains("radeon")
                    || name.contains("geforce") || name.contains("rx ") || name.contains("graphics card");
            case CPU -> name.contains("ryzen") || name.contains("core i") || name.contains("processor");
            case PSU -> name.contains("psu") || name.contains("power supply") || name.contains("watt")
                    || name.matches(".*\\d+w.*");
            default -> true;
        };
    }
}