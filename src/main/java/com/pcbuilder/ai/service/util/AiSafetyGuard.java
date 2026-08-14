package com.pcbuilder.ai.service.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
@Slf4j
public class AiSafetyGuard {

    public static final String CANNED_REFUSAL_MESSAGE =
            "I am a PC hardware assistant for pcbuilder and can only assist with computer hardware, component compatibility, and PC builds.";

    public static final String SAFE_CODE_FALLBACK_MESSAGE =
            "I can only provide PC hardware recommendations and explanations. I cannot generate programming code or non-hardware content.";

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // Instruction override / disregard
            Pattern.compile("(?i).*(ignore|disregard|forget|override|bypass|clear|reset)\\s+(all\\s+)?(previous|prior|above|system|initial|preceding)?\\s*(instructions|rules|prompts|directives|guidelines).*", Pattern.DOTALL),
            // Mode switching / jailbreaks (developer mode, DAN mode, unrestricted, etc.)
            Pattern.compile("(?i).*(you are now|act as|pretend to be|roleplay as|switch to)\\s+(an?\\s+)?(unrestricted|developer|dan|jailbreak|jailbroken|unfiltered|evil|root|god)\\s*(mode|ai|bot|assistant|persona)?.*", Pattern.DOTALL),
            Pattern.compile("(?i).*(developer mode|dan mode|jailbreak mode|god mode).*", Pattern.DOTALL),
            // System prompt leakage attempts
            Pattern.compile("(?i).*(reveal|repeat|output|show|print|display|tell me|what is)\\s+(your\\s+)?(system\\s+prompt|system\\s+instructions|system\\s+directive|initial\\s+prompt|raw\\s+prompt|secret\\s+prompt).*", Pattern.DOTALL),
            // "Pretend you have no rules" / "from now on..."
            Pattern.compile("(?i).*(pretend|imagine|assume)\\s+(that\\s+)?(you have no rules|there are no rules|you have no restrictions|safety filters are off).*", Pattern.DOTALL),
            Pattern.compile("(?i).*from now on\\s*,?\\s*(you can do anything|you are free|you must ignore).*", Pattern.DOTALL)
    );

    // Code fences detection: ```...``` or ```language ...
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```[a-zA-Z0-9_-]*\\s*.*\\s*```", Pattern.DOTALL);

    // Common programming code constructs to prevent raw code generation leaks
    private static final List<Pattern> CODE_KEYWORD_PATTERNS = List.of(
            Pattern.compile("(?m)^\\s*(public\\s+class|public\\s+static\\s+void\\s+main|import\\s+[a-z0-9_.]+\\s*;|def\\s+[a-zA-Z0-9_]+\\s*\\(.*\\):|function\\s+[a-zA-Z0-9_]+\\s*\\(.*\\)\\s*\\{)"),
            Pattern.compile("(?m)^\\s*(const|let|var)\\s+[a-zA-Z0-9_]+\\s*=\\s*(\\([^)]*\\)|function|require\\()"),
            Pattern.compile("(?m)^\\s*(#include\\s*<[a-z0-9_.]+>|using\\s+namespace\\s+std;)")
    );

    /**
     * Checks if the user message matches known prompt injection or adversarial jailbreak patterns.
     */
    public boolean isAdversarialInput(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String trimmed = input.trim();
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(trimmed).matches()) {
                log.warn("Adversarial prompt detected matching pattern [{}]: {}", pattern.pattern(), trimmed);
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if output contains markdown code blocks or obvious source code generation.
     */
    public boolean containsForbiddenCode(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        if (CODE_BLOCK_PATTERN.matcher(output).find()) {
            return true;
        }
        for (Pattern pattern : CODE_KEYWORD_PATTERNS) {
            if (pattern.matcher(output).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sanitizes AI response before saving or returning to client.
     */
    public String sanitizeOutput(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }
        if (containsForbiddenCode(output)) {
            log.warn("Forbidden code block detected in AI output. Substituting safe fallback message.");
            return SAFE_CODE_FALLBACK_MESSAGE;
        }
        return output;
    }

    public String getCannedRefusal() {
        return CANNED_REFUSAL_MESSAGE;
    }
}
