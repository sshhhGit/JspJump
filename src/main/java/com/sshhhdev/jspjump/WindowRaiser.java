package com.sshhhdev.jspjump;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.wm.WindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JFrame;
import javax.swing.Timer;
import java.awt.Frame;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.Robot;
import java.awt.event.KeyEvent;

/**
 * Brings the project window to the front after a jump from the browser.
 * <p>
 * Windows refuses {@code SetForegroundWindow} from a background process (the taskbar button only flashes) unless
 * that process produced the last input event, or ALT is held. Without native libraries (JNA moved to an optional
 * plugin in 2026.x) the input is synthesized with {@link Robot}; if the window is still not active, it is raised
 * above the other windows with a short always-on-top toggle so it is at least visible.
 */
final class WindowRaiser {

    private static final Logger LOG = Logger.getInstance(WindowRaiser.class);

    /** How the foreground lock is unlocked on Windows. */
    enum Mode {
        /** Robot moves the mouse to where it already is (no visible effect). */
        NUDGE,
        /** Robot holds ALT while the window is activated, with a dummy key so ALT does not open a menu. */
        ALT,
        /** Only IntelliJ's own focus request (plus the always-on-top fallback). */
        PLAIN
    }

    private WindowRaiser() {
    }

    static @NotNull Mode parse(@Nullable String s, @NotNull Mode def) {
        if (s == null) return def;
        try {
            return Mode.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    /** Must be called on the EDT. */
    static void raise(@NotNull Project project, @NotNull Mode mode) {
        JFrame frame = WindowManager.getInstance().getFrame(project);
        if (frame == null) {
            ProjectUtil.focusProjectWindow(project, true);
            return;
        }
        if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        }
        Robot robot = SystemInfo.isWindows && mode != Mode.PLAIN ? robot() : null;
        boolean altHeld = false;
        try {
            if (robot != null && mode == Mode.NUDGE) {
                PointerInfo pi = MouseInfo.getPointerInfo();
                if (pi != null) robot.mouseMove(pi.getLocation().x, pi.getLocation().y);
            } else if (robot != null && mode == Mode.ALT) {
                robot.keyPress(KeyEvent.VK_ALT);
                altHeld = true;
            }
            ProjectUtil.focusProjectWindow(project, true);
            frame.toFront();
            frame.requestFocus();
        } finally {
            if (altHeld) {
                // ALT+F24 then release: a lone ALT tap would activate the main menu of the newly focused window
                robot.keyPress(KeyEvent.VK_F24);
                robot.keyRelease(KeyEvent.VK_F24);
                robot.keyRelease(KeyEvent.VK_ALT);
            }
        }

        // still in the background → at least put it above the other windows
        Timer check = new Timer(350, e -> {
            if (frame.isActive()) return;
            LOG.info("JSP Jump: window not activated (" + mode + "), raising with always-on-top toggle");
            boolean was = frame.isAlwaysOnTop();
            frame.setAlwaysOnTop(true);
            frame.toFront();
            frame.setAlwaysOnTop(was);
            frame.requestFocus();
        });
        check.setRepeats(false);
        check.start();
    }

    private static @Nullable Robot robot() {
        try {
            PointerInfo pi = MouseInfo.getPointerInfo();
            return pi != null ? new Robot(pi.getDevice()) : new Robot();
        } catch (Exception e) {
            LOG.info("JSP Jump: java.awt.Robot unavailable: " + e);
            return null;
        }
    }
}
