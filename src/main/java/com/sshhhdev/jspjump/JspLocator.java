package com.sshhhdev.jspjump;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the JSP location that defines a clicked element. Plain text / regex search (no PSI).
 * Must be called inside a read action.
 */
public final class JspLocator {

    /** Max candidates returned to the IDE popup / browser. */
    static final int MAX_MATCHES = 30;
    /** Bigger files are library bundles (jquery, exceljs, ...), not screen sources. */
    static final long MAX_FILE_BYTES = 1_000_000;

    public record Match(VirtualFile file, String relativePath, int offset, int line, int column, int score,
                        String kind, String snippet) {
    }

    public record Result(List<Match> matches, String strategy, List<String> tried) {
    }

    record Hit(int offset, int score, String kind) {
    }

    interface Searcher {
        List<Hit> search(String text);
    }

    private static final String WS = "(?:\\s|&nbsp;|<br\\s*/?>)";
    private static final Pattern SCRIPT_SRC = Pattern.compile(
            "<script\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"'?#]+\\.js)(?:[?#][^\"']*)?[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCLUDE = Pattern.compile(
            "(?:<%@\\s*include\\s+file\\s*=\\s*|<jsp:include\\s+page\\s*=\\s*|<c:import\\s+url\\s*=\\s*)[\"']([^\"']+)[\"']");

    private final Project project;
    private final JspJumpSettings.State settings;
    private final Map<VirtualFile, String> textCache = new HashMap<>();
    /** Nearest ancestor ids of the clicked element (used to break ties between several hits). */
    private List<String> ancestorIds = List.of();

    public JspLocator(@NotNull Project project, @NotNull JspJumpSettings.State settings) {
        this.project = project;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ entry point

    /** Diagnostics returned to the browser (ms). */
    long collectMs, searchMs;
    int fileCount;

    /** Phase 1: only the screen's own files (so other open projects are not scanned when it is found there). */
    boolean preferredOnly;

    public @NotNull Result locate(@NotNull JumpRequest req) {
        long t0 = System.nanoTime();
        List<VirtualFile> jsps = cachedFiles(settings.includeGlobs);
        List<VirtualFile> scripts = cachedFiles(settings.scriptGlobs);
        fileCount = jsps.size() + scripts.size();
        long t1 = System.nanoTime();
        collectMs = (t1 - t0) / 1_000_000;
        try {
            return locateIn(req, jsps, scripts);
        } finally {
            searchMs = (System.nanoTime() - t1) / 1_000_000;
        }
    }

    private record FileListKey(Project project, String includes, String excludes) {
    }

    private record FileList(long vfsStamp, List<VirtualFile> files) {
    }

    /** File lists are reused until files are added, deleted, moved or renamed anywhere in the VFS. */
    private static final Map<FileListKey, FileList> FILE_LISTS = new ConcurrentHashMap<>();

    private List<VirtualFile> cachedFiles(String includeGlobs) {
        long stamp = VirtualFileManager.getInstance().getStructureModificationCount();
        FileListKey key = new FileListKey(project, includeGlobs, settings.excludeGlobs);
        FileList cached = FILE_LISTS.get(key);
        if (cached != null && cached.vfsStamp() == stamp && !DumbService.isDumb(project)) return cached.files();
        FILE_LISTS.keySet().removeIf(k -> k.project().isDisposed());
        List<VirtualFile> files = collectFiles(includeGlobs);
        if (!DumbService.isDumb(project)) FILE_LISTS.put(key, new FileList(stamp, files));
        return files;
    }

    private @NotNull Result locateIn(JumpRequest req, List<VirtualFile> jsps, List<VirtualFile> scripts) {
        List<VirtualFile> preferred = preferredFiles(req, jsps, scripts);
        // search order: the screen's JSP (+ its includes and <script src> files) -> other JSPs -> other scripts
        Set<VirtualFile> pref = new LinkedHashSet<>(preferred);
        List<VirtualFile> otherJsps = new ArrayList<>();
        for (VirtualFile f : jsps) if (!pref.contains(f)) otherJsps.add(f);
        List<VirtualFile> otherScripts = new ArrayList<>();
        for (VirtualFile f : scripts) if (!pref.contains(f)) otherScripts.add(f);
        List<List<VirtualFile>> pools = preferredOnly ? List.of(preferred) : List.of(preferred, otherJsps, otherScripts);
        List<String> tried = new ArrayList<>();
        List<String> anc = new ArrayList<>();
        if (req.ancestors != null) {
            for (JumpRequest.Node a : req.ancestors) {
                if (a != null && notBlank(a.id) && anc.size() < 4) anc.add(a.id);
            }
        }
        ancestorIds = anc;

        JumpRequest.Target t = req.target;
        JumpRequest.Grid g = req.grid;

        // 1) AUIGrid column (before id: grid internals may carry generated ids)
        if (g != null && notBlank(g.dataField)) {
            tried.add("dataField=" + g.dataField);
            Result r = run("grid dataField", gridDataFieldSearcher(g.dataField, g.gridId, columnFunctionAlternation(settings.columnFunctions)), pools, tried);
            if (r != null) return r;
        }
        if (g != null && notBlank(g.headerText)) {
            tried.add("headerText=" + g.headerText);
            Result r = run("grid headerText", gridHeaderSearcher(g.headerText, g.gridId, columnFunctionAlternation(settings.columnFunctions)), pools, tried);
            if (r != null) return r;
        }
        // 2) id
        if (t != null && notBlank(t.id)) {
            tried.add("id=" + t.id);
            Result r = run("id", attrSearcher("id", t.id), pools, tried);
            if (r != null) return r;
        }
        // 3) name
        if (t != null && notBlank(t.name)) {
            tried.add("name=" + t.name);
            Result r = run("name", attrSearcher("name", t.name), pools, tried);
            if (r != null) return r;
        }
        // 4) visible text / button value
        if (t != null) {
            for (String text : new LinkedHashSet<>(listOf(t.value, t.text))) {
                String norm = normalize(text);
                if (norm.isEmpty() || norm.length() > 60) continue;
                tried.add("text=" + norm);
                Result r = run("text", textSearcher(norm), pools, tried);
                if (r != null) return r;
            }
        }
        // 5) nearest ancestors with an id
        // (phase 2 only: in phase 1 an ancestor id in the screen file must not beat an exact id in an include)
        if (req.ancestors != null && !preferredOnly) {
            for (JumpRequest.Node a : req.ancestors) {
                if (a == null || !notBlank(a.id)) continue;
                if (t != null && a.id.equals(t.id)) continue;
                tried.add("ancestor id=" + a.id);
                Result r = run("ancestor id", attrSearcher("id", a.id), pools, tried);
                if (r != null) return r;
            }
        }
        return new Result(List.of(), null, tried);
    }

    // ------------------------------------------------------------------ search driver

    /** Searches the pools in order and stops at the first pool with hits. */
    private @Nullable Result run(String strategy, Searcher searcher, List<List<VirtualFile>> pools,
                                 List<String> tried) {
        List<Match> m = List.of();
        for (List<VirtualFile> pool : pools) {
            m = searchFiles(searcher, pool);
            if (!m.isEmpty()) break;
        }
        if (m.isEmpty()) return null;
        int best = m.stream().mapToInt(Match::score).max().orElse(0);
        List<Match> top = new ArrayList<>();
        for (Match x : m) if (x.score() == best && top.size() < MAX_MATCHES) top.add(x);
        return new Result(top, strategy, tried);
    }

    private List<Match> searchFiles(Searcher searcher, List<VirtualFile> files) {
        List<Match> out = new ArrayList<>();
        for (VirtualFile f : files) {
            String text = text(f);
            if (text == null) continue;
            List<Hit> hits = searcher.search(text);
            if (hits.isEmpty()) continue;
            List<List<Integer>> ancestorDefs = null;
            for (Hit h : hits) {
                if (!ancestorIds.isEmpty()) {
                    if (ancestorDefs == null) ancestorDefs = ancestorDefinitions(text);
                    int bonus = enclosureBonus(text, h.offset(), ancestorDefs);
                    if (bonus > 0) h = new Hit(h.offset(), h.score() + bonus, h.kind());
                }
                out.add(toMatch(f, text, h));
            }
        }
        out.sort(Comparator.comparingInt(Match::score).reversed());
        return out;
    }

    private Match toMatch(VirtualFile f, String text, Hit h) {
        int line = 0, lineStart = 0;
        for (int i = 0; i < h.offset() && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = text.length();
        String snippet = text.substring(lineStart, lineEnd).trim();
        if (snippet.length() > 160) snippet = snippet.substring(0, 160) + "…";
        return new Match(f, relativePath(f), h.offset(), line, h.offset() - lineStart, h.score(), h.kind(), snippet);
    }

    /** Offsets of the id definitions of the nearest ancestors, nearest first. */
    private List<List<Integer>> ancestorDefinitions(String text) {
        List<List<Integer>> out = new ArrayList<>();
        for (String id : ancestorIds) {
            List<Integer> offs = new ArrayList<>();
            for (Hit h : attrSearcher("id", id).search(text)) offs.add(h.offset());
            out.add(offs);
        }
        return out;
    }

    /**
     * +4 when the hit lies inside the element defining the nearest ancestor id, +3 for the next one, ...
     * Distinguishes e.g. the same {@code <th>요청 내용</th>} in a search form and in a detail form.
     */
    private static int enclosureBonus(String text, int offset, List<List<Integer>> defs) {
        for (int i = 0; i < defs.size(); i++) {
            for (int d : defs.get(i)) {
                if (encloses(text, d, offset)) return 4 - i;
            }
        }
        return 0;
    }

    private static final Pattern TAG_NAME = Pattern.compile("<([A-Za-z][\\w:-]*)");

    /** Whether the tag containing the attribute at {@code attrOffset} is still open at {@code target}. */
    static boolean encloses(String text, int attrOffset, int target) {
        if (attrOffset >= target) return false;
        int lt = text.lastIndexOf('<', attrOffset);
        if (lt < 0) return false;
        Matcher tm = TAG_NAME.matcher(text);
        tm.region(lt, text.length());
        if (!tm.lookingAt()) return false;
        String tag = Pattern.quote(tm.group(1));
        // scan open / close tags of the same name in document order; closed before target → not enclosing
        Matcher m = Pattern.compile("<(/?)" + tag + "(?=[\\s>/])", Pattern.CASE_INSENSITIVE).matcher(text);
        m.region(lt + 1, target);
        int depth = 1;
        while (m.find()) {
            depth += m.group(1).isEmpty() ? 1 : -1;
            if (depth <= 0) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ searchers

    /**
     * {@code id="x"}, {@code id=x}, {@code id=${prefix}x}, {@code id=\"x\"} (inside JS strings).
     * With an EL / scriptlet prefix the clicked id must end with the literal part.
     */
    static Searcher attrSearcher(String attr, String value) {
        // starts with a literal so the regex engine can skip quickly; the word boundary is checked below
        Pattern p = Pattern.compile(attr
                + "\\s*=\\s*\\\\?[\"']?((?:\\$\\{[^}]*\\}|<%=[^%]*%>)*)([\\w\\-:.\\[\\]]*)");
        // any match ends with the last characters of the value (with or without an EL prefix)
        String tail = value.substring(Math.max(0, value.length() - 2));
        return text -> {
            List<Hit> hits = new ArrayList<>();
            if (!text.contains(tail)) return hits;
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (!wordStart(text, m.start())) continue;
                String el = m.group(1);
                String literal = m.group(2);
                if (el.isEmpty()) {
                    if (literal.equals(value)) hits.add(new Hit(m.start(), 100, attr));
                } else if (!literal.isEmpty() && value.length() > literal.length() && value.endsWith(literal)) {
                    String prefix = value.substring(0, value.length() - literal.length());
                    boolean sameVar = el.equals("${" + prefix + "}");
                    hits.add(new Hit(m.start(), sameVar ? 100 : 90, attr + " (EL prefix)"));
                }
            }
            return hits;
        };
    }

    static Searcher gridDataFieldSearcher(String dataField, String gridId, @Nullable String fns) {
        String q = Pattern.quote(dataField);
        List<Pattern> patterns = new ArrayList<>();
        if (fns != null) {
            patterns.add(Pattern.compile("\\.\\s*(?:" + fns + ")\\s*\\(\\s*\\\\?[\"']" + q + "\\\\?[\"']"));
        }
        patterns.add(Pattern.compile("\\bdataField\\s*:\\s*\\\\?[\"']" + q + "\\\\?[\"']"));
        Searcher s = gridSearcher(patterns, new int[]{100, 95}, gridId, "grid column");
        return text -> text.contains(dataField) ? s.search(text) : List.of();
    }

    static Searcher gridHeaderSearcher(String headerText, String gridId, @Nullable String fns) {
        String h = wordsRegex(normalize(headerText));
        List<Pattern> patterns = new ArrayList<>();
        if (fns != null) {
            patterns.add(Pattern.compile("\\.\\s*(?:" + fns + ")\\s*\\(\\s*[\"'][^\"']*[\"']\\s*,\\s*[\"']" + WS + "*" + h
                    + WS + "*[\"']"));
        }
        patterns.add(Pattern.compile("\\bheaderText\\s*:\\s*[\"']" + WS + "*" + h + WS + "*[\"']"));
        Searcher s = gridSearcher(patterns, new int[]{85, 80}, gridId, "grid header");
        String first = firstWord(normalize(headerText));
        return text -> text.contains(first) ? s.search(text) : List.of();
    }

    /** {@code scores} is aligned to the end of {@code patterns} (the optional function pattern comes first). */
    private static Searcher gridSearcher(List<Pattern> patterns, int[] scores, String gridId, String kind) {
        int shift = scores.length - patterns.size();
        return text -> {
            List<Hit> hits = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                int base = scores[i + shift];
                Matcher m = patterns.get(i).matcher(text);
                while (m.find()) {
                    int score = base;
                    if (notBlank(gridId) && lineOf(text, m.start()).contains(gridId)) score += 3;
                    hits.add(new Hit(m.start(), score, kind));
                }
            }
            return hits;
        };
    }

    /** Button value or element text: {@code value="조회"}, {@code >조회<}. Whitespace / &amp;nbsp; tolerant. */
    static Searcher textSearcher(String norm) {
        String w = wordsRegex(norm);
        Pattern value = Pattern.compile("value\\s*=\\s*\\\\?[\"']" + WS + "*" + w + WS + "*\\\\?[\"']");
        Pattern body = Pattern.compile(">" + WS + "*" + w + WS + "*<");
        String first = firstWord(norm);
        return text -> {
            List<Hit> hits = new ArrayList<>();
            if (!text.contains(first)) return hits;
            Matcher m = value.matcher(text);
            while (m.find()) if (wordStart(text, m.start())) hits.add(new Hit(m.start(), 80, "value text"));
            m = body.matcher(text);
            while (m.find()) hits.add(new Hit(m.start() + 1, 75, "element text"));
            return hits;
        };
    }

    // ------------------------------------------------------------------ files

    private static final Pattern GLOB_EXT = Pattern.compile("\\*\\.([A-Za-z0-9]+)$");

    /**
     * Uses the file name index for the extensions named in the include globs ({@code **}{@code /*.jsp} → jsp), so a
     * request does not walk the whole project tree. Falls back to walking the content while indexing.
     */
    private List<VirtualFile> collectFiles(String includeGlobs) {
        List<PathMatcher> includes = matchers(includeGlobs);
        List<PathMatcher> excludes = matchers(settings.excludeGlobs);
        if (includes.isEmpty()) return new ArrayList<>();
        Set<String> exts = new LinkedHashSet<>();
        boolean extOnly = true;
        for (String g : JspJumpSettings.lines(includeGlobs)) {
            Matcher m = GLOB_EXT.matcher(g);
            if (m.find()) exts.add(m.group(1));
            else extOnly = false;
        }
        List<VirtualFile> out = new ArrayList<>();
        if (extOnly && !exts.isEmpty() && !DumbService.isDumb(project)) {
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
            for (String ext : exts) {
                for (VirtualFile file : FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                    String rel = relativePath(file);
                    if (matchesAny(includes, rel) && !matchesAny(excludes, rel)) out.add(file);
                }
            }
        } else {
            ProjectFileIndex.getInstance(project).iterateContent(file -> {
                if (file.isDirectory()) return true;
                String rel = relativePath(file);
                if (matchesAny(includes, rel) && !matchesAny(excludes, rel)) out.add(file);
                return true;
            });
        }
        out.sort(Comparator.comparing(VirtualFile::getPath));
        return out;
    }

    /**
     * Files named after the nearest ancestor ids (screen root div is usually named like the JSP, e.g.
     * {@code <div id="OrderListM">}) and after the URL ({@code /order/OrderListM.do} → {@code OrderListM.jsp}),
     * plus the files they statically include.
     */
    private List<VirtualFile> preferredFiles(JumpRequest req, List<VirtualFile> all, List<VirtualFile> scripts) {
        Map<String, List<VirtualFile>> byName = new HashMap<>();
        for (VirtualFile f : all) {
            byName.computeIfAbsent(f.getNameWithoutExtension().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(f);
        }
        List<String> names = new ArrayList<>();
        if (req.ancestors != null) {
            for (JumpRequest.Node a : req.ancestors) if (a != null && notBlank(a.id)) names.add(a.id);
        }
        if (req.page != null && notBlank(req.page.path)) {
            String p = req.page.path;
            int slash = p.lastIndexOf('/');
            String base = p.substring(slash + 1);
            int dot = base.indexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            if (!base.isEmpty()) names.add(base);
        }
        LinkedHashMap<VirtualFile, Boolean> result = new LinkedHashMap<>();
        for (String n : names) {
            List<VirtualFile> files = byName.get(n.toLowerCase(Locale.ROOT));
            if (files == null) continue;
            for (VirtualFile f : files) {
                if (result.putIfAbsent(f, Boolean.TRUE) == null) addIncludes(f, byName, result, 0);
            }
        }
        // scripts loaded by those JSPs (<script src="/viewScript/x/Grid.js">), e.g. AUIGrid column definitions
        Map<String, List<VirtualFile>> scriptsByName = new HashMap<>();
        for (VirtualFile s : scripts) {
            scriptsByName.computeIfAbsent(s.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(s);
        }
        List<VirtualFile> referenced = new ArrayList<>();
        for (VirtualFile jsp : new ArrayList<>(result.keySet())) {
            String text = text(jsp);
            if (text == null) continue;
            Matcher m = SCRIPT_SRC.matcher(text);
            while (m.find()) {
                String src = m.group(1);
                if (src.contains("${") || src.contains("<%")) continue;
                String name = src.substring(src.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                List<VirtualFile> cands = scriptsByName.get(name);
                if (cands == null) continue;
                String tail = src.startsWith("/") ? src : "/" + src;
                List<VirtualFile> exact = new ArrayList<>();
                for (VirtualFile c : cands) if (c.getPath().endsWith(tail)) exact.add(c);
                referenced.addAll(exact.isEmpty() ? cands : exact);
            }
        }
        for (VirtualFile s : referenced) result.putIfAbsent(s, Boolean.TRUE);
        return new ArrayList<>(result.keySet());
    }

    private void addIncludes(VirtualFile f, Map<String, List<VirtualFile>> byName,
                             LinkedHashMap<VirtualFile, Boolean> result, int depth) {
        if (depth > 2) return;
        String text = text(f);
        if (text == null) return;
        Matcher m = INCLUDE.matcher(text);
        while (m.find()) {
            String path = m.group(1);
            if (path.contains("${") || path.contains("<%")) continue;
            String base = path.substring(path.lastIndexOf('/') + 1);
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            List<VirtualFile> inc = byName.get(base.toLowerCase(Locale.ROOT));
            if (inc == null) continue;
            for (VirtualFile i : inc) {
                if (result.putIfAbsent(i, Boolean.TRUE) == null) addIncludes(i, byName, result, depth + 1);
            }
        }
    }

    private record CachedText(long docStamp, long fileStamp, String text) {
    }

    /** File texts kept between requests until the file or its open document changes. */
    private static final Map<VirtualFile, CachedText> SHARED_TEXTS = new ConcurrentHashMap<>();

    private @Nullable String text(VirtualFile f) {
        if (textCache.containsKey(f)) return textCache.get(f);
        String text = null;
        try {
            if (f.isValid()) {
                Document doc = FileDocumentManager.getInstance().getCachedDocument(f);
                long docStamp = doc != null ? doc.getModificationStamp() : -1;
                long fileStamp = f.getModificationStamp();
                CachedText c = SHARED_TEXTS.get(f);
                if (c != null && c.docStamp() == docStamp && c.fileStamp() == fileStamp) {
                    text = c.text();
                } else {
                    if (doc != null) text = doc.getText();
                    else if (f.getLength() <= MAX_FILE_BYTES) text = LoadTextUtil.loadText(f).toString();
                    if (text != null) SHARED_TEXTS.put(f, new CachedText(docStamp, fileStamp, text));
                }
            } else {
                SHARED_TEXTS.remove(f);
            }
        } catch (Exception ignored) {
            // unreadable file → skip
        }
        textCache.put(f, text);
        return text;
    }

    private String relativePath(VirtualFile f) {
        VirtualFile root = ProjectFileIndex.getInstance(project).getContentRootForFile(f);
        String rel = root != null ? VfsUtilCore.getRelativePath(f, root, '/') : null;
        return rel != null ? rel : f.getPath();
    }

    private static List<PathMatcher> matchers(String globs) {
        List<PathMatcher> out = new ArrayList<>();
        for (String g : JspJumpSettings.lines(globs)) {
            try {
                out.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
            } catch (IllegalArgumentException ignored) {
                // invalid glob in settings → ignore
            }
        }
        return out;
    }

    private static boolean matchesAny(List<PathMatcher> matchers, String rel) {
        try {
            Path p = Path.of(rel);
            Path nested = Path.of("_", rel); // lets "**/x" also match top-level files
            for (PathMatcher m : matchers) if (m.matches(p) || m.matches(nested)) return true;
        } catch (InvalidPathException ignored) {
            // ignore
        }
        return false;
    }

    // ------------------------------------------------------------------ util

    static @Nullable String columnFunctionAlternation(String setting) {
        List<String> fns = JspJumpSettings.lines(setting);
        if (fns.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String f : fns) {
            if (!f.matches("[\\w$]+")) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(Pattern.quote(f));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** No letter, digit, '_' or '-' right before {@code i} (replaces a slow leading look-behind). */
    static boolean wordStart(String text, int i) {
        if (i == 0) return true;
        char c = text.charAt(i - 1);
        return !(Character.isLetterOrDigit(c) || c == '_' || c == '-');
    }

    private static String firstWord(String norm) {
        int sp = norm.indexOf(' ');
        return sp < 0 ? norm : norm.substring(0, sp);
    }

    static String normalize(String s) {
        if (s == null) return "";
        return s.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String wordsRegex(String norm) {
        StringBuilder sb = new StringBuilder();
        for (String w : norm.split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(WS).append('+');
            sb.append(Pattern.quote(w));
        }
        return sb.toString();
    }

    private static String lineOf(String text, int offset) {
        int s = text.lastIndexOf('\n', offset) + 1;
        int e = text.indexOf('\n', offset);
        return text.substring(s, e < 0 ? text.length() : e);
    }

    private static List<String> listOf(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) if (v != null) out.add(v);
        return out;
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
