package com.sshhhdev.jspjump;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Application-level settings (Settings | Tools | JSP Jump).
 */
@State(name = "JspJumpSettings", storages = @Storage("jspjump.xml"))
public final class JspJumpSettings implements PersistentStateComponent<JspJumpSettings.State> {

    public static final String DEFAULT_ORIGINS = String.join("\n",
            "http://localhost:*",
            "http://127.0.0.1:*",
            "https://localhost:*",
            "https://127.0.0.1:*");
    public static final String DEFAULT_INCLUDES = String.join("\n",
            "**/*.jsp",
            "**/*.jspf",
            "**/*.tag");
    /** Scripts searched after the JSPs (grid column definitions, ids built in JS). */
    public static final String DEFAULT_SCRIPTS = "**/*.js";
    public static final String DEFAULT_EXCLUDES = String.join("\n",
            "**/target/**",
            "**/build/**",
            "**/out/**",
            "**/node_modules/**",
            "**/*.min.js",
            "**/AUIGrid/**",
            "**/ckeditor/**",
            "**/jquery*.js");
    public static final String DEFAULT_COLUMN_FUNCTIONS = "addColumnCustom, addColumn, addColumnInnerColspan";

    public static final class State {
        public boolean enabled = true;
        public String allowedOrigins = DEFAULT_ORIGINS;
        public String includeGlobs = DEFAULT_INCLUDES;
        public String scriptGlobs = DEFAULT_SCRIPTS;
        public String excludeGlobs = DEFAULT_EXCLUDES;
        public String columnFunctions = DEFAULT_COLUMN_FUNCTIONS;
        public boolean bringIdeToFront = true;
        /** Plugin jar path + modification time of the last installation (to show the bookmark guide on install only). */
        public String installMarker = "";
        public String installedVersion = "";
    }

    private State state = new State();

    public static JspJumpSettings getInstance() {
        return ApplicationManager.getApplication().getService(JspJumpSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        XmlSerializerUtil.copyBean(loaded, state);
    }

    // ---- helpers ----

    /** Splits a multi-line / comma separated setting into trimmed, non-empty tokens. */
    public static List<String> lines(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String s : value.split("[\\r\\n,;]+")) {
            String t = s.trim();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    /** Origin patterns use '*' as wildcard, e.g. {@code http://localhost:*}. */
    public boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isEmpty()) return false;
        for (String p : lines(state.allowedOrigins)) {
            StringBuilder re = new StringBuilder();
            String[] parts = p.split("\\*", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) re.append("[^/]*");
                if (!parts[i].isEmpty()) re.append(Pattern.quote(parts[i]));
            }
            if (Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE).matcher(origin).matches()) return true;
        }
        return false;
    }
}
