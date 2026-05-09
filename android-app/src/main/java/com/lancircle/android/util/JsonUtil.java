package com.lancircle.android.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class JsonUtil {
    private JsonUtil() {}

    public static String object(Map<String, ?> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(escape(entry.getKey())).append('"').append(':');
            appendValue(json, entry.getValue());
        }
        return json.append('}').toString();
    }

    public static Map<String, String> parseObject(String json) {
        return new Parser(json).parseObject();
    }

    public static List<String> parseStringArray(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        return new Parser(raw).parseArray();
    }

    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static void appendValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof List<?> list) {
            json.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) json.append(',');
                appendValue(json, list.get(i));
            }
            json.append(']');
        } else {
            json.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text == null ? "" : text.trim();
        }

        Map<String, String> parseObject() {
            java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
            skipWhitespace();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return values;
            }
            while (pos < text.length()) {
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                String value = parseValueAsRaw();
                values.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw error("Expected ',' or '}'");
                skipWhitespace();
            }
            return values;
        }

        List<String> parseArray() {
            List<String> values = new ArrayList<>();
            skipWhitespace();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return values;
            }
            while (pos < text.length()) {
                values.add(parseString());
                skipWhitespace();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw error("Expected ',' or ']'");
                skipWhitespace();
            }
            return values;
        }

        private String parseValueAsRaw() {
            char c = peek();
            if (c == '"') return parseString();
            if (c == '[') return parseRawBracketed('[', ']');
            if (c == '{') return parseRawBracketed('{', '}');
            int start = pos;
            while (pos < text.length()) {
                c = text.charAt(pos);
                if (c == ',' || c == '}') break;
                pos++;
            }
            return text.substring(start, pos).trim();
        }

        private String parseRawBracketed(char open, char close) {
            int start = pos;
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inString = false;
                } else if (c == '"') {
                    inString = true;
                } else if (c == open) {
                    depth++;
                } else if (c == close && --depth == 0) {
                    break;
                }
            }
            return text.substring(start, pos);
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = next();
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char esc = next();
                switch (esc) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        String hex = text.substring(pos, Math.min(pos + 4, text.length()));
                        if (hex.length() != 4) throw error("Invalid unicode escape");
                        out.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    }
                    default -> throw error("Invalid escape");
                }
            }
            throw error("Unterminated string");
        }

        private void expect(char expected) {
            char actual = next();
            if (actual != expected) throw error("Expected '" + expected + "'");
        }

        private char next() {
            if (pos >= text.length()) throw error("Unexpected end of JSON");
            return text.charAt(pos++);
        }

        private char peek() {
            if (pos >= text.length()) throw error("Unexpected end of JSON");
            return text.charAt(pos);
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at " + pos);
        }
    }
}
