package com.sshhhdev.jspjump;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reverse lookup for internationalized pages: the text shown in the browser comes from a
 * {@code messages*.properties} value, while the JSP only has the key
 * ({@code <spring:message code="user.name"/>}, {@code <fmt:message key="user.name"/>}, {@code msg('user.name')}).
 */
final class MessageKeys {

    private MessageKeys() {
    }

    /** Keys whose (unescaped, whitespace-normalized) value equals {@code text}. */
    static List<String> keysFor(String propertiesText, String text) {
        List<String> out = new ArrayList<>();
        String want = JspLocator.normalize(text);
        if (want.isEmpty()) return out;
        String[] lines = propertiesText.replace("\r\n", "\n").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // continuation lines
            while (endsWithOddBackslashes(line) && i + 1 < lines.length) {
                line = line.substring(0, line.length() - 1) + lines[++i].stripLeading();
            }
            String t = line.stripLeading();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int sep = separator(t);
            if (sep <= 0) continue;
            String key = unescape(t.substring(0, sep).trim());
            String value = t.substring(sep + 1).replaceFirst("^[\\s=:]+", "");
            if (JspLocator.normalize(unescape(value)).equals(want)) out.add(key);
        }
        return out;
    }

    /** Searches usages of a message key: {@code code="k"}, {@code key="k"}, or any quoted {@code 'k'} / {@code "k"}. */
    static JspLocator.Searcher keySearcher(String key) {
        String q = Pattern.quote(key);
        Pattern attr = Pattern.compile("\\b(?:code|key|titleKey|messageKey|bundleKey)\\s*=\\s*[\"']" + q + "[\"']");
        Pattern literal = Pattern.compile("[\"']" + q + "[\"']");
        return text -> {
            List<JspLocator.Hit> hits = new ArrayList<>();
            if (!text.contains(key)) return hits;
            Matcher m = attr.matcher(text);
            while (m.find()) hits.add(new JspLocator.Hit(m.start(), 78, "message key " + key));
            if (hits.isEmpty()) {
                m = literal.matcher(text);
                while (m.find()) hits.add(new JspLocator.Hit(m.start(), 72, "message key " + key));
            }
            return hits;
        };
    }

    private static boolean endsWithOddBackslashes(String s) {
        int n = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) n++;
        return n % 2 == 1;
    }

    private static int separator(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '=' || c == ':' || Character.isWhitespace(c)) return i;
        }
        return -1;
    }

    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            sb.append('u');
                        }
                    }
                }
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }
}
