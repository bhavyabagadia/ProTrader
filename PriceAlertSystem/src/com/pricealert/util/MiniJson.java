package com.pricealert.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny JSON parser so the project needs NO external libraries.
 *
 * Objects become Map, arrays become List, numbers become Double,
 * strings become String, true/false become Boolean, null becomes null.
 *
 * It is a "recursive descent" parser: value() looks at the next character and
 * calls object(), array(), string() or number() - and those call value() again.
 */
public final class MiniJson {
    private final String s;
    private int i = 0;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("Invalid JSON: text is null");
        MiniJson p = new MiniJson(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.i != p.s.length()) throw p.error("Unexpected extra text");
        return v;
    }

    /**
     * Walk into parsed JSON, e.g. path(root, "chart", "result", 0, "meta").
     * String keys go into objects, Integer keys go into arrays. Returns null if anything is missing.
     */
    public static Object path(Object root, Object... keys) {
        Object cur = root;
        for (Object k : keys) {
            if (cur instanceof Map<?, ?> m && k instanceof String) {
                cur = m.get(k);
            } else if (cur instanceof List<?> l && k instanceof Integer idx) {
                cur = (idx >= 0 && idx < l.size()) ? l.get(idx) : null;
            } else {
                return null;
            }
        }
        return cur;
    }

    /** A number, or text that looks like a number, as a double. Anything else gives NaN. */
    public static double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String str) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    // ---------------------------------------------------------------- parser

    private Object value() {
        if (i >= s.length()) throw error("Unexpected end of text");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expectWord("true"); return Boolean.TRUE;
            case 'f': expectWord("false"); return Boolean.FALSE;
            case 'n': expectWord("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("Unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++; // skip {
        skipWs();
        if (peek() == '}') { i++; return map; }
        while (true) {
            skipWs();
            if (peek() != '"') throw error("Expected a text key");
            String key = string();
            skipWs();
            if (peek() != ':') throw error("Expected ':'");
            i++;
            skipWs();
            map.put(key, value());
            skipWs();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; return map; }
            throw error("Expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++; // skip [
        skipWs();
        if (peek() == ']') { i++; return list; }
        while (true) {
            skipWs();
            list.add(value());
            skipWs();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; return list; }
            throw error("Expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++; // skip opening quote
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> sb.append(unicode());
                    default -> sb.append(e); // \" \\ \/
                }
            } else {
                sb.append(c);
            }
        }
        throw error("Unterminated string");
    }

    private char unicode() {
        if (i + 4 > s.length()) throw error("Bad \\u escape");
        try {
            char ch = (char) Integer.parseInt(s.substring(i, i + 4), 16);
            i += 4;
            return ch;
        } catch (NumberFormatException e) {
            throw error("Bad \\u escape");
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return Double.valueOf(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw error("Bad number");
        }
    }

    private void expectWord(String w) {
        if (!s.startsWith(w, i)) throw error("Expected '" + w + "'");
        i += w.length();
    }

    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("Invalid JSON at position " + i + ": " + msg);
    }
}
