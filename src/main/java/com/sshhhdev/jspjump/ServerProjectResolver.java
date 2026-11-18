package com.sshhhdev.jspjump;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the open project that serves a page, so a jump from {@code http://localhost:8084/...} opens the project
 * whose Tomcat listens on 8084 even when several similar projects are open.
 * <p>
 * IntelliJ runs local Tomcat configurations with their own CATALINA_BASE under {@code <system>/tomcat/<id>/}:
 * {@code conf/server.xml} holds the HTTP connector port and {@code conf/Catalina/localhost/*.xml} the deployed
 * {@code docBase} (an artifact directory inside the project). No Java EE plugin API is needed.
 */
final class ServerProjectResolver {

    private static final Pattern CONNECTOR = Pattern.compile("<Connector\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PORT = Pattern.compile("\\bport\\s*=\\s*\"(\\d+)\"");
    private static final Pattern PROTOCOL = Pattern.compile("\\bprotocol\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern DOC_BASE = Pattern.compile("\\bdocBase\\s*=\\s*\"([^\"]+)\"");

    record Hit(Project project, String reason) {
    }

    private ServerProjectResolver() {
    }

    static @Nullable Hit projectForOrigin(@Nullable String origin) {
        if (origin == null) return null;
        int port;
        try {
            URI uri = URI.create(origin);
            port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Path tomcatDirs = Path.of(PathManager.getSystemPath(), "tomcat");
        if (!Files.isDirectory(tomcatDirs)) return null;

        // CATALINA_BASE directories whose HTTP connector uses this port, most recently started first
        List<Path> bases = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(tomcatDirs)) {
            for (Path base : ds) {
                Path serverXml = base.resolve("conf").resolve("server.xml");
                if (Files.isRegularFile(serverXml) && hasHttpPort(read(serverXml), port)) bases.add(base);
            }
        } catch (IOException e) {
            return null;
        }
        bases.sort(Comparator.comparingLong(ServerProjectResolver::lastModified).reversed());

        Project[] open = ProjectManager.getInstance().getOpenProjects();
        for (Path base : bases) {
            for (String docBase : docBases(base)) {
                String doc = norm(docBase);
                for (Project p : open) {
                    if (p.isDisposed() || p.isDefault()) continue;
                    if (contains(p, doc)) return new Hit(p, "Tomcat :" + port + " → " + docBase);
                }
            }
        }
        return null;
    }

    private static boolean hasHttpPort(String serverXml, int port) {
        Matcher c = CONNECTOR.matcher(serverXml);
        while (c.find()) {
            String attrs = c.group(1);
            Matcher pm = PORT.matcher(attrs);
            if (!pm.find() || Integer.parseInt(pm.group(1)) != port) continue;
            Matcher pr = PROTOCOL.matcher(attrs);
            if (pr.find() && pr.group(1).toUpperCase(Locale.ROOT).contains("AJP")) continue;
            return true;
        }
        return false;
    }

    private static List<String> docBases(Path base) {
        List<String> out = new ArrayList<>();
        Path ctxRoot = base.resolve("conf").resolve("Catalina");
        if (!Files.isDirectory(ctxRoot)) return out;
        try (var hosts = Files.newDirectoryStream(ctxRoot)) {
            for (Path host : hosts) {
                if (!Files.isDirectory(host)) continue;
                try (var ctxs = Files.newDirectoryStream(host, "*.xml")) {
                    for (Path ctx : ctxs) {
                        Matcher m = DOC_BASE.matcher(read(ctx));
                        if (m.find()) out.add(m.group(1));
                    }
                }
            }
        } catch (IOException ignored) {
            // unreadable → no hint
        }
        return out;
    }

    /** docBase (usually target/&lt;artifact&gt; or an exploded dir) lies inside the project or one of its content roots. */
    private static boolean contains(Project p, String doc) {
        List<String> roots = new ArrayList<>();
        if (p.getBasePath() != null) roots.add(norm(p.getBasePath()));
        for (VirtualFile r : ProjectRootManager.getInstance(p).getContentRoots()) roots.add(norm(r.getPath()));
        for (String r : roots) {
            if (doc.equals(r) || doc.startsWith(r + "/")) return true;
        }
        return false;
    }

    private static String norm(String path) {
        String s = path.replace('\\', '/');
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            try {
                return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            } catch (IOException ex) {
                return "";
            }
        }
    }

    private static long lastModified(Path base) {
        try {
            return Files.getLastModifiedTime(base.resolve("conf").resolve("server.xml")).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
