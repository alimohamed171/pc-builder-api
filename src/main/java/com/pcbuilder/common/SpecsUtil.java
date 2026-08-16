package com.pcbuilder.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcbuilder.product.entity.Product;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small helper shared by the product and bundle modules to safely read the
 * free-form "specs" JSON column (e.g. {"socket":"AM5","tdp":"65"}).
 */
public final class SpecsUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern WATTAGE_PATTERN = Pattern.compile("(\\d{3,4})\\s*W");

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

    public static String extractSocket(Product p) {
        if (p == null) return null;
        Map<String, String> specs = parse(p.getSpecs());
        String socketSpec = get(specs, "socket");
        if (socketSpec == null || socketSpec.isBlank()) {
            socketSpec = get(specs, "cpu_socket");
        }
        if (socketSpec != null && !socketSpec.isBlank()) {
            String norm = normalizeSocket(socketSpec);
            if (norm != null) return norm;
        }

        String text = ((p.getRawName() != null ? p.getRawName() : "") + " "
                + (p.getMatchedGlobalName() != null ? p.getMatchedGlobalName() : "") + " "
                + (p.getSourceUrl() != null ? p.getSourceUrl() : "") + " "
                + (p.getSpecs() != null ? p.getSpecs() : "")).toUpperCase();

        return normalizeSocket(text);
    }

    public static String normalizeSocket(String text) {
        if (text == null || text.isBlank()) return null;
        String upper = text.toUpperCase();

        if (upper.matches(".*\\bAM5\\b.*") || upper.contains("SOCKET AM5") || upper.contains("AMD AM5")
                || upper.contains("B650") || upper.contains("X670") || upper.contains("A620")
                || upper.contains("X870") || upper.contains("B850")) {
            return "AM5";
        }
        if (upper.matches(".*\\bAM4\\b.*") || upper.contains("SOCKET AM4") || upper.contains("AMD AM4")
                || upper.contains("B450") || upper.contains("B550") || upper.contains("A520") || upper.contains("A320") || upper.contains("X570")
                || upper.contains("2100GE") || upper.contains("3200G") || upper.contains("3400G")
                || upper.contains("3600") || upper.contains("5600") || upper.contains("5700")) {
            return "AM4";
        }
        if (upper.contains("LGA1851") || upper.contains("LGA 1851") || upper.contains("1851")
                || upper.contains("Z890") || upper.contains("B860")) {
            return "LGA1851";
        }
        if (upper.contains("LGA1700") || upper.contains("LGA 1700") || upper.contains("1700")
                || upper.contains("H610") || upper.contains("B660") || upper.contains("B760")
                || upper.contains("Z690") || upper.contains("Z790")) {
            return "LGA1700";
        }
        if (upper.contains("LGA1200") || upper.contains("LGA 1200") || upper.contains("1200")
                || upper.contains("H410") || upper.contains("B460") || upper.contains("H510")
                || upper.contains("B560") || upper.contains("Z490") || upper.contains("Z590")) {
            return "LGA1200";
        }
        if (upper.contains("LGA1151") || upper.contains("LGA 1151") || upper.contains("1151")
                || upper.contains("H110") || upper.contains("B250") || upper.contains("B360")
                || upper.contains("B365") || upper.contains("Z370") || upper.contains("Z390")) {
            return "LGA1151";
        }
        if (upper.contains("LGA1150") || upper.contains("LGA 1150") || upper.contains("1150")) {
            return "LGA1150";
        }
        if (upper.contains("LGA1155") || upper.contains("LGA 1155") || upper.contains("1155")) {
            return "LGA1155";
        }
        if (upper.contains("LGA2066") || upper.contains("LGA 2066") || upper.contains("2066")) {
            return "LGA2066";
        }
        if (upper.contains("STRX4") || upper.contains("S-TRX4")) {
            return "STRX4";
        }
        if (upper.contains("TR4")) {
            return "TR4";
        }
        return null;
    }

    public static String extractRamType(Product p) {
        if (p == null) return null;
        Map<String, String> specs = parse(p.getSpecs());
        String ramSpec = get(specs, "memory_type");
        if (ramSpec == null || ramSpec.isBlank()) {
            ramSpec = get(specs, "ram_type");
        }
        if (ramSpec == null || ramSpec.isBlank()) {
            ramSpec = get(specs, "type");
        }
        if (ramSpec != null && !ramSpec.isBlank()) {
            String upper = ramSpec.toUpperCase();
            if (upper.contains("DDR5")) return "DDR5";
            if (upper.contains("DDR4")) return "DDR4";
            if (upper.contains("DDR3")) return "DDR3";
        }

        String text = ((p.getRawName() != null ? p.getRawName() : "") + " "
                + (p.getMatchedGlobalName() != null ? p.getMatchedGlobalName() : "") + " "
                + (p.getSpecs() != null ? p.getSpecs() : "")).toUpperCase();

        if (text.contains("DDR5")) return "DDR5";
        if (text.contains("DDR4")) return "DDR4";
        if (text.contains("DDR3")) return "DDR3";

        String socket = extractSocket(p);
        if ("AM5".equals(socket) || "LGA1851".equals(socket)) {
            return "DDR5";
        }
        if ("AM4".equals(socket)) {
            return "DDR4";
        }
        return null;
    }

    public static String extractFormFactor(Product p) {
        if (p == null) return null;
        Map<String, String> specs = parse(p.getSpecs());
        String ff = get(specs, "form_factor");
        if (ff == null || ff.isBlank()) {
            ff = get(specs, "type");
        }
        String text = ((ff != null ? ff : "") + " "
                + (p.getRawName() != null ? p.getRawName() : "") + " "
                + (p.getMatchedGlobalName() != null ? p.getMatchedGlobalName() : "") + " "
                + (p.getSpecs() != null ? p.getSpecs() : "")).toUpperCase();

        if (text.contains("EATX") || text.contains("E-ATX") || text.contains("FULL TOWER")) return "EATX";
        if (text.contains("MICRO ATX") || text.contains("MICRO-ATX") || text.contains("MICROATX")
                || text.contains("MATX") || text.contains("M-ATX") || text.contains("MINI TOWER")) return "MICRO ATX";
        if (text.contains("MINI ITX") || text.contains("MINI-ITX") || text.contains("MINIITX") || text.contains("ITX")) return "MINI ITX";
        if (text.contains("ATX") || text.contains("MID TOWER")) return "ATX";
        return null;
    }

    public static Double extractWattage(Product p) {
        if (p == null) return null;
        Map<String, String> specs = parse(p.getSpecs());
        Double w = getDouble(specs, "wattage");
        if (w != null) return w;
        String text = ((p.getRawName() != null ? p.getRawName() : "") + " "
                + (p.getMatchedGlobalName() != null ? p.getMatchedGlobalName() : "")).toUpperCase();
        Matcher m = WATTAGE_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public static boolean coolerSupportsSocket(Product cooler, String targetSocket) {
        if (cooler == null || targetSocket == null) return true;
        String text = ((cooler.getRawName() != null ? cooler.getRawName() : "") + " "
                + (cooler.getMatchedGlobalName() != null ? cooler.getMatchedGlobalName() : "") + " "
                + (cooler.getSpecs() != null ? cooler.getSpecs() : "")).toUpperCase();
        String normalizedText = text.replace(" ", "");
        String normTarget = targetSocket.replace(" ", "").toUpperCase();

        if (normTarget.equals("AM5")) {
            return normalizedText.contains("AM5") || normalizedText.contains("AM4") || normalizedText.contains("AMD");
        }
        if (normTarget.equals("AM4")) {
            return normalizedText.contains("AM4") || normalizedText.contains("AM5") || normalizedText.contains("AMD");
        }
        if (normTarget.equals("LGA1700") || normTarget.equals("1700")) {
            return normalizedText.contains("1700") || normalizedText.contains("INTEL");
        }
        if (normTarget.equals("LGA1851") || normTarget.equals("1851")) {
            return normalizedText.contains("1851") || normalizedText.contains("1700") || normalizedText.contains("INTEL");
        }
        if (normTarget.equals("LGA1200") || normTarget.equals("1200")) {
            return normalizedText.contains("1200") || normalizedText.contains("115") || normalizedText.contains("INTEL");
        }
        if (normTarget.contains("115")) {
            return normalizedText.contains("115") || normalizedText.contains("1200") || normalizedText.contains("INTEL");
        }
        return normalizedText.contains(normTarget);
    }
}
