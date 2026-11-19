package com.sshhhdev.jspjump;

import com.intellij.ide.BrowserUtil;
import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URI;
import java.net.URL;

/**
 * The plugin is useless until the browser bookmark exists, and an IDE plugin cannot add a bookmark itself.
 * So when JSP Jump is installed (also re-installed), a sticky notification asks to add the bookmark and opens the
 * bookmark page when its button is clicked. Nothing is shown on a normal IDE start or after a version update.
 * <p>
 * Installation is detected by the plugin jar (path + modification time) differing from the one recorded last time:
 * <ul>
 *   <li>installed without restart → {@link #pluginLoaded}</li>
 *   <li>installed with restart → first {@link #execute} after the restart</li>
 * </ul>
 */
public final class JspJumpOnboarding implements ProjectActivity, DynamicPluginListener {

    static final String GROUP = "JSP Jump Setup";
    private static final String PLUGIN_ID = "com.sshhhdev.jspjump";
    private static boolean checkedThisSession;
    private static volatile Notification current;

    /** IDE start / project open: shows the guide only on the first start after an installation. */
    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        checkInstalled(project);
        return Unit.INSTANCE;
    }

    /** Installed without restart. */
    @Override
    public void pluginLoaded(@NotNull IdeaPluginDescriptor pluginDescriptor) {
        if (!PLUGIN_ID.equals(pluginDescriptor.getPluginId().getIdString())) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            Project[] open = ProjectManager.getInstance().getOpenProjects();
            checkInstalled(open.length > 0 ? open[0] : null);
        });
    }

    private static synchronized void checkInstalled(@Nullable Project project) {
        if (checkedThisSession) return;
        checkedThisSession = true;

        JspJumpSettings.State s = JspJumpSettings.getInstance().getState();
        String version = JspJumpHttpHandler.pluginVersion();
        String marker = installMarker();
        if (marker.equals(s.installMarker)) return; // same installation as before → normal start

        boolean update = s.installedVersion != null && !s.installedVersion.isEmpty()
                && !s.installedVersion.equals(version);
        s.installMarker = marker;
        s.installedVersion = version;
        if (!update) showGuide(project);
    }

    /** The page is opened only when the user clicks the button (nothing is opened without user action). */
    static void showGuide(@Nullable Project project) {
        Notification n = NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(
                "JSP Jump: 브라우저 북마크를 추가하세요",
                "북마크가 있어야 브라우저 화면에서 점프할 수 있습니다. 아래 <b>북마크 추가 페이지 열기</b>를 누르고, "
                        + "열린 페이지의 파란 <b>JSP Jump</b> 버튼을 브라우저 북마크바로 끌어다 놓으세요.<br/>"
                        + "Add the <b>JSP Jump</b> bookmark: click <b>Add bookmark</b> below and drag the blue button "
                        + "on that page onto the bookmarks bar.",
                NotificationType.INFORMATION);
        n.addAction(NotificationAction.createSimple("북마크 추가 페이지 열기 (Add bookmark)",
                () -> BrowserUtil.browse(JspJumpNotifications.installPageUrl())));
        n.addAction(NotificationAction.createSimpleExpiring("닫기 (Close)", () -> {
        }));
        current = n;
        n.notify(project);
    }

    /** Called by the HTTP handler when the bookmarklet is used: the guide is no longer needed. */
    static void markUsed() {
        Notification n = current;
        if (n != null && !n.isExpired()) {
            current = null;
            ApplicationManager.getApplication().invokeLater(n::expire);
        }
    }

    /** Path and modification time of the plugin jar; changes on every (re)installation. */
    private static String installMarker() {
        try {
            URL url = JspJumpOnboarding.class.getResource("JspJumpOnboarding.class");
            if (url == null) return "?";
            String s = url.toString();
            if (s.startsWith("jar:")) {
                s = s.substring(4, s.indexOf("!/"));
            }
            File f = new File(new URI(s));
            return f.getAbsolutePath() + "|" + f.lastModified();
        } catch (Exception e) {
            return "?";
        }
    }
}
