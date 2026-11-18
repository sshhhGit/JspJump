package com.sshhhdev.jspjump;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.ide.BuiltInServerManager;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * IDE balloon "allow this origin?" for pages served from a LAN / dev host name.
 */
final class JspJumpNotifications {

    static final String GROUP = "JSP Jump";
    private static final Set<String> ASKED_ORIGINS = ConcurrentHashMap.newKeySet();
    private static final Pattern PRIVATE_HOST = Pattern.compile(
            "localhost|127(\\.\\d+){3}|\\[::1]|10(\\.\\d+){3}|192\\.168(\\.\\d+){2}|172\\.(1[6-9]|2\\d|3[01])(\\.\\d+){2}"
                    + "|[a-z0-9-]+|.+\\.(local|localhost|test|internal|lan|corp|intra)");

    private JspJumpNotifications() {
    }

    static String installPageUrl() {
        return "http://localhost:" + BuiltInServerManager.getInstance().getPort() + "/jspjump/install";
    }

    /** Asked once per origin per IDE session, and only for hosts that look like a local / LAN dev server. */
    static void askToAllowOrigin(@Nullable Project project, String origin) {
        String host;
        try {
            host = URI.create(origin).getHost();
        } catch (IllegalArgumentException e) {
            return;
        }
        if (host == null || !PRIVATE_HOST.matcher(host.toLowerCase(Locale.ROOT)).matches()) return;
        if (!ASKED_ORIGINS.add(origin)) return;

        String pattern = origin.replaceFirst(":\\d+$", "") + ":*";
        Notification n = NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(
                "JSP Jump: request from " + origin,
                "This page origin is not allowed yet. Allow <b>" + pattern + "</b>?<br/>"
                        + "허용하면 이 주소의 화면에서도 점프할 수 있습니다.",
                NotificationType.WARNING);
        n.addAction(NotificationAction.createSimpleExpiring("Allow " + pattern, () -> {
            JspJumpSettings.State s = JspJumpSettings.getInstance().getState();
            s.allowedOrigins = s.allowedOrigins.stripTrailing() + "\n" + pattern;
        }));
        n.notify(project);
    }
}
