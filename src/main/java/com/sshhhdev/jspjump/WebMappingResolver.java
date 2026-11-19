package com.sshhhdev.jspjump;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-only resolution of "which JSP renders this URL" for common Java web stacks (no PSI, works in any IDE):
 * <ul>
 *   <li>Spring MVC controllers: class + method {@code @RequestMapping / @GetMapping ...} → {@code return "view"},
 *       {@code new ModelAndView("view")}, {@code setViewName("view")}, or the URL itself for {@code void} handlers</li>
 *   <li>Tiles definitions (including wildcards such as {@code name="*}{@code /*"} with {@code {1}}) → JSP attributes</li>
 *   <li>Struts 1/2 style XML: {@code <action path="/x">} / {@code <action name="x">} → {@code *.jsp} forwards/results</li>
 *   <li>Direct {@code *.jsp} URLs</li>
 * </ul>
 * The context path is unknown, so every suffix of the request path is tried.
 */
final class WebMappingResolver {

    private WebMappingResolver() {
    }

    /** A view name found for the URL, and where it was found. */
    record ViewRef(String view, int offset, String how) {
    }

    // ------------------------------------------------------------------ URL helpers

    /** "/ctx/board/list.do" → ["/ctx/board/list.do", "/board/list.do", "/list.do"] */
    static List<String> pathSuffixes(String urlPath) {
        List<String> out = new ArrayList<>();
        String p = urlPath == null ? "" : urlPath;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (!p.startsWith("/")) p = "/" + p;
        out.add(p);
        int i = p.indexOf('/', 1);
        while (i > 0) {
            out.add(p.substring(i));
            i = p.indexOf('/', i + 1);
        }
        return out;
    }

    static String stripExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(0, dot) : path;
    }

    /** Words worth looking up in the word index: last segment without extension, then the one before it. */
    static List<String> searchTokens(String urlPath) {
        List<String> out = new ArrayList<>();
        String p = stripExtension(pathSuffixes(urlPath).get(0));
        String[] segs = p.split("/");
        for (int i = segs.length - 1; i >= 0 && out.size() < 2; i--) {
            String s = segs[i];
            if (s.length() >= 2 && s.matches("[A-Za-z_][\\w-]*")) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ Spring MVC

    private static final Pattern CLASS_DECL = Pattern.compile("\\b(class|interface)\\s+\\w+");
    private static final Pattern MAPPING = Pattern.compile(
            "@(Request|Get|Post|Put|Delete|Patch)Mapping\\b");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern RETURN_VIEW = Pattern.compile("\\breturn\\s+\"([^\"]+)\"\\s*;");
    private static final Pattern MAV = Pattern.compile("new\\s+ModelAndView\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern SET_VIEW = Pattern.compile("\\.setViewName\\s*\\(\\s*\"([^\"]+)\"");

    /** Views returned by the handler methods of {@code javaText} that map {@code urlPath}. */
    static @NotNull List<ViewRef> viewsFromJava(String urlPath, String javaText) {
        List<ViewRef> out = new ArrayList<>();
        int bestVars = Integer.MAX_VALUE;
        if (!javaText.contains("Mapping")) return out;
        Matcher cd = CLASS_DECL.matcher(javaText);
        int classStart = cd.find() ? cd.start() : 0;

        // class level prefixes (only @RequestMapping before the class declaration)
        List<String> prefixes = new ArrayList<>();
        Matcher m = MAPPING.matcher(javaText);
        while (m.find() && m.start() < classStart) {
            if (!"Request".equals(m.group(1))) continue;
            prefixes.addAll(mappingValues(javaText, m.end()));
        }
        if (prefixes.isEmpty()) prefixes.add("");
        boolean restController = javaText.substring(0, classStart).contains("@RestController");

        m = MAPPING.matcher(javaText);
        while (m.find()) {
            if (m.start() < classStart) continue;
            int argsEnd = argsEnd(javaText, m.end());
            List<String> paths = mappingValues(javaText, m.end());
            if (paths.isEmpty()) paths = List.of("");
            int bodyStart = javaText.indexOf('{', argsEnd);
            int semi = javaText.indexOf(';', argsEnd);
            if (bodyStart < 0 || (semi >= 0 && semi < bodyStart)) continue; // interface / abstract method
            String signature = javaText.substring(argsEnd, bodyStart);
            int bodyEnd = matchBrace(javaText, bodyStart);
            String body = javaText.substring(bodyStart, bodyEnd);
            // the most specific mapping of this method that matches (fewest {vars} / wildcards)
            String full = null;
            int vars = Integer.MAX_VALUE;
            for (String pre : prefixes) {
                for (String p : paths) {
                    String mapped = join(pre, p);
                    int v = variableCount(mapped);
                    if (v < vars && urlMatches(urlPath, mapped)) {
                        full = mapped;
                        vars = v;
                    }
                }
            }
            if (full == null) continue;
            // Spring prefers literal mappings over pattern ones: keep only the most specific handlers
            if (vars > bestVars) continue;
            if (vars < bestVars) {
                bestVars = vars;
                out.clear();
            }
            if (restController || signature.contains("@ResponseBody")) continue; // data, not a page

            boolean any = false;
            for (Pattern vp : List.of(RETURN_VIEW, MAV, SET_VIEW)) {
                Matcher v = vp.matcher(body);
                while (v.find()) {
                    String view = v.group(1);
                    if (view.startsWith("redirect:")) continue;
                    if (view.startsWith("forward:")) view = view.substring("forward:".length());
                    out.add(new ViewRef(view, bodyStart + v.start(), "controller"));
                    any = true;
                }
            }
            if (!any && signature.matches("(?s).*\\bvoid\\s+\\w+\\s*\\(.*")) {
                // RequestToViewNameTranslator: the request path is the view name
                out.add(new ViewRef(stripExtension(full).replaceFirst("^/", ""), m.start(), "controller (void)"));
            }
        }
        return out;
    }

    private static int variableCount(String mapping) {
        int n = 0;
        for (int i = 0; i < mapping.length(); i++) {
            char c = mapping.charAt(i);
            if (c == '{' || c == '*') n++;
        }
        return n;
    }

    /** String values of value= / path= (or the positional value) of an annotation whose name ends at {@code nameEnd}. */
    static List<String> mappingValues(String text, int nameEnd) {
        List<String> out = new ArrayList<>();
        int i = skipWs(text, nameEnd);
        if (i >= text.length() || text.charAt(i) != '(') return out;
        int end = argsEnd(text, nameEnd);
        String args = text.substring(i + 1, Math.max(i + 1, end - 1));
        // split top-level by commas
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        boolean inStr = false;
        for (int k = 0; k < args.length(); k++) {
            char c = args.charAt(k);
            if (c == '"' && (k == 0 || args.charAt(k - 1) != '\\')) inStr = !inStr;
            if (inStr) continue;
            if (c == '{' || c == '(') depth++;
            else if (c == '}' || c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(args.substring(start, k));
                start = k + 1;
            }
        }
        parts.add(args.substring(start));
        for (String part : parts) {
            String t = part.trim();
            int eq = t.indexOf('=');
            boolean named = eq > 0 && t.substring(0, eq).trim().matches("\\w+");
            if (named) {
                String key = t.substring(0, eq).trim();
                if (!key.equals("value") && !key.equals("path")) continue;
                t = t.substring(eq + 1);
            } else if (!t.startsWith("\"") && !t.startsWith("{")) {
                continue;
            }
            Matcher s = STRING.matcher(t);
            while (s.find()) out.add(s.group(1));
        }
        return out;
    }

    private static int argsEnd(String text, int nameEnd) {
        int i = skipWs(text, nameEnd);
        if (i >= text.length() || text.charAt(i) != '(') return nameEnd;
        int depth = 0;
        boolean inStr = false;
        for (int k = i; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '"' && text.charAt(k - 1) != '\\') inStr = !inStr;
            if (inStr) continue;
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return k + 1;
        }
        return text.length();
    }

    private static int matchBrace(String text, int open) {
        int depth = 0;
        boolean inStr = false, inChar = false;
        for (int k = open; k < text.length(); k++) {
            char c = text.charAt(k);
            char prev = k > 0 ? text.charAt(k - 1) : 0;
            if (c == '"' && !inChar && prev != '\\') inStr = !inStr;
            else if (c == '\'' && !inStr && prev != '\\') inChar = !inChar;
            if (inStr || inChar) continue;
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return k + 1;
        }
        return text.length();
    }

    private static int skipWs(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    static String join(String prefix, String path) {
        String a = prefix == null ? "" : prefix.trim();
        String b = path == null ? "" : path.trim();
        String s = ("/" + a + "/" + b).replaceAll("/+", "/");
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Does a mapping pattern (with {vars}, *, **, optional extension) match some suffix of the request path? */
    static boolean urlMatches(String urlPath, String mapping) {
        String mapRe = toRegex(mapping);
        String mapReNoExt = toRegex(stripExtension(mapping));
        for (String cand : pathSuffixes(urlPath)) {
            if (cand.matches(mapRe)) return true;
            String noExt = stripExtension(cand);
            if (noExt.matches(mapReNoExt)) return true; // "/list.do" vs "/list", "/x.qms" vs "x.qms"
        }
        return false;
    }

    private static String toRegex(String mapping) {
        String m = mapping.startsWith("/") ? mapping : "/" + mapping;
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < m.length(); i++) {
            char c = m.charAt(i);
            if (c == '{') {
                int close = m.indexOf('}', i);
                if (close > i) {
                    re.append("[^/]+");
                    i = close;
                    continue;
                }
            }
            if (c == '*') {
                if (i + 1 < m.length() && m.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                } else {
                    re.append("[^/]*");
                }
                continue;
            }
            re.append(Pattern.quote(String.valueOf(c)));
        }
        return re.toString();
    }

    // ------------------------------------------------------------------ view → JSP

    /** JSP paths (as written in tiles XML) for a view name; handles wildcard definitions. */
    static List<String> tilesJsps(String view, String tilesXml) {
        List<String> out = new ArrayList<>();
        if (!tilesXml.contains("<definition")) return out;
        Matcher d = Pattern.compile("<definition\\b([^>]*?)(/?)>").matcher(tilesXml);
        while (d.find()) {
            String attrs = d.group(1);
            Matcher n = Pattern.compile("\\bname\\s*=\\s*\"([^\"]+)\"").matcher(attrs);
            if (!n.find()) continue;
            String name = n.group(1);
            String[] groups = matchWildcard(name, view);
            if (groups == null) continue;
            int end = d.group(2).isEmpty() ? tilesXml.indexOf("</definition>", d.end()) : d.end();
            String block = attrs + (end > d.end() ? tilesXml.substring(d.end(), end) : "");
            Matcher j = Pattern.compile("[\"'>]\\s*([^\"'<>\\s]+\\.jspf?)").matcher(block);
            while (j.find()) {
                String jsp = j.group(1);
                for (int g = 0; g < groups.length; g++) jsp = jsp.replace("{" + (g + 1) + "}", groups[g]);
                out.add(jsp);
            }
        }
        return out;
    }

    /** null if the tiles definition name does not match; otherwise the wildcard captures. */
    private static String[] matchWildcard(String name, String view) {
        if (name.equals(view)) return new String[0];
        if (!name.contains("*")) return null;
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '*') {
                if (i + 1 < name.length() && name.charAt(i + 1) == '*') {
                    re.append("(.*)");
                    i++;
                } else {
                    re.append("([^/]*)");
                }
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        Matcher m = Pattern.compile(re.toString()).matcher(view);
        if (!m.matches()) return null;
        String[] g = new String[m.groupCount()];
        for (int i = 0; i < g.length; i++) g[i] = m.group(i + 1);
        return g;
    }

    /** Struts 1 ({@code <action path="/board/list">}) and Struts 2 ({@code <action name="list">}) → JSPs inside. */
    static List<String> actionXmlJsps(String urlPath, String xml) {
        List<String> out = new ArrayList<>();
        if (!xml.contains("<action")) return out;
        Set<String> keys = new LinkedHashSet<>();
        for (String s : pathSuffixes(urlPath)) {
            String noExt = stripExtension(s);
            keys.add(noExt);
            keys.add(noExt.substring(noExt.lastIndexOf('/') + 1));
        }
        Matcher a = Pattern.compile("<action\\b([^>]*?)(/?)>").matcher(xml);
        while (a.find()) {
            String attrs = a.group(1);
            Matcher p = Pattern.compile("\\b(path|name)\\s*=\\s*\"([^\"]+)\"").matcher(attrs);
            boolean hit = false;
            while (p.find()) {
                String v = stripExtension(p.group(2));
                if (keys.contains(v) || keys.contains("/" + v.replaceFirst("^/", ""))) hit = true;
            }
            if (!hit) continue;
            int end = a.group(2).isEmpty() ? xml.indexOf("</action>", a.end()) : a.end();
            String block = attrs + (end > a.end() ? xml.substring(a.end(), end) : "");
            Matcher j = Pattern.compile("([^\"'<>\\s]+\\.jspf?)").matcher(block);
            while (j.find()) out.add(j.group(1));
        }
        return out;
    }

    /** Project-relative JSP path (forward slashes) matches a view name / JSP path from a mapping. */
    static boolean jspMatches(String relativePath, String viewOrJsp) {
        String rel = "/" + relativePath.replace('\\', '/').replaceFirst("^/", "").toLowerCase(Locale.ROOT);
        String v = viewOrJsp.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!v.startsWith("/")) v = "/" + v;
        if (v.endsWith(".jsp") || v.endsWith(".jspf")) return rel.endsWith(v);
        return rel.endsWith(v + ".jsp");
    }
}
