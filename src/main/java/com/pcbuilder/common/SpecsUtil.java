package com.pcbuilder.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small helper shared by the product and bundle modules to safely read the
 * free-form "specs" JSON column (e.g. {"socket":"AM5","tdp":"65"}).
 */
public final class SpecsUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpecsUtil() {
    }

    public static Map<String, String> parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(rawJson, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public static String get(Map<String, String> specs, String key) {
        if (specs == null) {
            return null;
        }
        return specs.get(key);
    }

    public static Double getDouble(Map<String, String> specs, String key) {
        String value = get(specs, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
