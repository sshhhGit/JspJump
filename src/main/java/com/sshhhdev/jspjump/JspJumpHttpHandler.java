package com.sshhhdev.jspjump;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.ide.BuiltInServerManager;
import org.jetbrains.ide.HttpRequestHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in server endpoint ({@code http://localhost:63342/jspjump}).
 * <ul>
 *   <li>{@code POST /jspjump} – JSON clues from the bookmarklet → open the JSP line</li>
 *   <li>{@code GET /jspjump/ping} – health check</li>
 *   <li>{@code GET /jspjump/bookmarklet.js} – the bookmarklet script (used by the small loader bookmarklet)</li>
 * </ul>
 */
public final class JspJumpHttpHandler extends HttpRequestHandler {

    private static final Logger LOG = Logger.getInstance(JspJumpHttpHandler.class);
    private static final String PREFIX = "/jspjump";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @Override
    public boolean isSupported(@NotNull FullHttpRequest request) {
        HttpMethod m = request.method();
        if (m != HttpMethod.GET && m != HttpMethod.POST && m != HttpMethod.OPTIONS) return false;
        String path = new QueryStringDecoder(request.uri()).path();
        return path.equals(PREFIX) || path.startsWith(PREFIX + "/");
    }

    /**
     * The default implementation only allows same-host origins (it rejects {@code http://localhost:8080}).
     * Origins are checked against the plugin settings in {@link #process} instead, so a rejected page gets a
     * readable error. {@link HttpRequestHandler#isAccessible} still requires the Host header to be local.
     */
    @Override
    protected @NotNull OriginCheckResult isOriginAllowed(@NotNull HttpRequest request) {
        return OriginCheckResult.ALLOW;
    }

    @Override
    public boolean process(@NotNull QueryStringDecoder urlDecoder, @NotNull FullHttpRequest request,
                           @NotNull ChannelHandlerContext context) throws IOException {
        String path = urlDecoder.path();
        String origin = request.headers().get("Origin");
        JspJumpSettings settings = JspJumpSettings.getInstance();
        boolean originAllowed = origin == null || settings.isOriginAllowed(origin);
        HttpHeaders headers = corsHeaders(origin);

        if (request.method() == HttpMethod.OPTIONS) {
            headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.set("Access-Control-Allow-Headers", "Content-Type");
            headers.set("Access-Control-Allow-Private-Network", "true");
            headers.set("Access-Control-Max-Age", "600");
            sendData(new byte[0], "preflight.txt", request, context.channel(), headers);
            return true;
        }

        if (request.method() == HttpMethod.GET && path.equals(PREFIX + "/bookmarklet.js")) {
            try (InputStream in = JspJumpHttpHandler.class.getResourceAsStream("/jspjump/jspjump.js")) {
                if (in == null) return false;
                if (originAllowed) JspJumpOnboarding.markUsed();
                sendData(in.readAllBytes(), "bookmarklet.js", request, context.channel(), headers);
            }
            return true;
        }

        if (request.method() == HttpMethod.GET && path.equals(PREFIX + "/install")) {
            sendData(installPage(request).getBytes(StandardCharsets.UTF_8), "install.html", request, context.channel(),
                    headers);
            return true;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        if (request.method() == HttpMethod.GET) {
            out.put("ok", true);
            out.put("plugin", "JSP Jump");
            out.put("version", pluginVersion());
            out.put("ide", ApplicationInfo.getInstance().getFullApplicationName());
            out.put("enabled", settings.getState().enabled);
            out.put("originAllowed", originAllowed);
            if (originAllowed) {
                List<String> names = new ArrayList<>();
                for (Project p : ProjectManager.getInstance().getOpenProjects()) names.add(p.getName());
                out.put("projects", names);
            }
            return sendJson(out, request, context, headers);
        }

        // POST
        if (!originAllowed) {
            out.put("ok", false);
            out.put("originRejected", true);
            out.put("error", "Origin not allowed: " + origin + " (Settings | Tools | JSP Jump)");
            Project[] open = ProjectManager.getInstance().getOpenProjects();
            JspJumpNotifications.askToAllowOrigin(open.length > 0 ? open[0] : null, origin);
            return sendJson(out, request, context, headers);
        }
        if (!settings.getState().enabled) {
            out.put("ok", false);
            out.put("error", "JSP Jump is disabled (Settings | Tools | JSP Jump)");
            return sendJson(out, request, context, headers);
        }

        JspJumpOnboarding.markUsed();
        JumpRequest req;
        try {
            req = GSON.fromJson(request.content().toString(StandardCharsets.UTF_8), JumpRequest.class);
        } catch (JsonSyntaxException e) {
            req = null;
        }
        if (req == null) {
            out.put("ok", false);
            out.put("error", "Invalid JSON body");
            return sendJson(out, request, context, headers);
        }

        long started = System.nanoTime();
        List<Map<String, Object>> timing = new ArrayList<>();
        Project bestProject = null;
        JspLocator.Result best = null;
        List<String> tried = List.of();

        // Which project serves this page? (Tomcat started from IntelliJ: port → docBase → project)
        ServerProjectResolver.Hit served = req.project == null || req.project.isBlank()
                ? ServerProjectResolver.projectForOrigin(origin) : null;
        if (served != null) out.put("projectBy", served.reason());
        // groups searched in order; a later group is only searched when nothing was found before
        List<List<Project>> groups = new ArrayList<>();
        List<Project> ordered = orderedProjects(served != null ? served.project().getName() : req.project);
        if (served != null) {
            groups.add(List.of(served.project()));
            groups.add(ordered.subList(1, ordered.size()));
        } else {
            groups.add(ordered);
        }

        // per group: phase 1 = each project's screen JSP (+ includes, <script src>) only; phase 2 = everything
        for (List<Project> group : groups)
        for (int phase = 1; phase <= 2 && best == null; phase++)
        for (Project p : group) {
            if (p.isDisposed() || p.isDefault()) continue;
            final JumpRequest r = req;
            JspLocator.Result res;
            JspLocator locator = new JspLocator(p, settings.getState());
            locator.preferredOnly = phase == 1;
            long before = System.nanoTime();
            long[] lockAcquired = new long[1];
            try {
                res = ApplicationManager.getApplication().runReadAction((Computable<JspLocator.Result>) () -> {
                    lockAcquired[0] = System.nanoTime();
                    return locator.locate(r);
                });
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("project", p.getName());
                t.put("phase", phase);
                t.put("lockWaitMs", (lockAcquired[0] - before) / 1_000_000);
                t.put("collectMs", locator.collectMs);
                t.put("searchMs", locator.searchMs);
                t.put("files", locator.fileCount);
                timing.add(t);
            } catch (RuntimeException e) {
                LOG.warn("JSP Jump search failed in " + p.getName(), e);
                continue;
            }
            tried = res.tried();
            if (res.matches().isEmpty()) continue;
            if (best == null || res.matches().get(0).score() > best.matches().get(0).score()) {
                best = res;
                bestProject = p;
            }
        }

        out.put("tried", tried);
        out.put("ms", (System.nanoTime() - started) / 1_000_000);
        out.put("timing", timing);
        if (best == null) {
            out.put("ok", true);
            out.put("found", 0);
            return sendJson(out, request, context, headers);
        }

        List<Map<String, Object>> cands = new ArrayList<>();
        for (JspLocator.Match m : best.matches()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("file", m.relativePath());
            c.put("line", m.line() + 1);
            c.put("kind", m.kind());
            c.put("snippet", m.snippet());
            cands.add(c);
        }
        out.put("ok", true);
        out.put("found", best.matches().size());
        out.put("project", bestProject.getName());
        out.put("strategy", best.strategy());
        out.put("tried", best.tried());
        out.put("file", cands.get(0).get("file"));
        out.put("line", cands.get(0).get("line"));
        out.put("score", best.matches().get(0).score());
        out.put("candidates", cands);
        if (!req.dryRun) {
            WindowRaiser.Mode mode = WindowRaiser.parse(req.raise,
                    WindowRaiser.parse(settings.getState().raiseMode, WindowRaiser.Mode.NUDGE));
            JspNavigator.show(bestProject, best.matches(), best.strategy(), mode);
        }
        return sendJson(out, request, context, headers);
    }

    private boolean sendJson(Map<String, Object> body, FullHttpRequest request, ChannelHandlerContext context,
                             HttpHeaders headers) {
        sendData(GSON.toJson(body).getBytes(StandardCharsets.UTF_8), "result.json", request, context.channel(),
                headers);
        return true;
    }

    private static HttpHeaders corsHeaders(String origin) {
        HttpHeaders h = new DefaultHttpHeaders();
        if (origin != null) {
            h.set("Access-Control-Allow-Origin", origin);
            h.set("Vary", "Origin");
        }
        return h;
    }

    private static List<Project> orderedProjects(String hint) {
        List<Project> out = new ArrayList<>();
        Project[] open = ProjectManager.getInstance().getOpenProjects();
        if (hint != null && !hint.isBlank()) {
            for (Project p : open) if (p.getName().equalsIgnoreCase(hint)) out.add(p);
        }
        for (Project p : open) if (!out.contains(p)) out.add(p);
        return out;
    }

    /** Small page with a bookmarklet link that can be dragged to the bookmarks bar. */
    private static String installPage(FullHttpRequest request) throws IOException {
        String loader = "javascript:" + resource("/jspjump/loader.js").trim();
        String template = resource("/jspjump/install.html");
        return template
                .replace("{{VERSION}}", esc(pluginVersion()))
                .replace("{{PORT}}", String.valueOf(BuiltInServerManager.getInstance().getPort()))
                .replace("{{LOADER_HREF}}", esc(loader))
                .replace("{{LOADER_TEXT}}", esc(loader));
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = JspJumpHttpHandler.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String pluginVersion() {
        try (InputStream in = JspJumpHttpHandler.class.getResourceAsStream("/jspjump/version.properties")) {
            java.util.Properties p = new java.util.Properties();
            if (in != null) p.load(in);
            return p.getProperty("version", "?");
        } catch (IOException e) {
            return "?";
        }
    }
}
