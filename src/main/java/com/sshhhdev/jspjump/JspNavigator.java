package com.sshhhdev.jspjump;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;

import javax.swing.JList;
import java.util.List;

/**
 * Opens a match in the editor, or lets the user choose among several candidates.
 */
final class JspNavigator {

    private JspNavigator() {
    }

    static void show(@NotNull Project project, @NotNull List<JspLocator.Match> matches, String strategy,
                     @NotNull WindowRaiser.Mode raise) {
        if (matches.isEmpty()) return;
        boolean toFront = JspJumpSettings.getInstance().getState().bringIdeToFront;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            if (toFront) WindowRaiser.raise(project, raise);
            if (matches.size() == 1) {
                open(project, matches.get(0));
                return;
            }
            JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(matches)
                    .setTitle("JSP Jump: " + matches.size() + " candidates (" + strategy + ")")
                    .setRenderer(new SimpleListCellRenderer<JspLocator.Match>() {
                        @Override
                        public void customize(@NotNull JList<? extends JspLocator.Match> list, JspLocator.Match value,
                                              int index, boolean selected, boolean hasFocus) {
                            setText(value == null ? "" : label(value));
                        }
                    })
                    .setNamerForFiltering(JspNavigator::label)
                    .setItemChosenCallback(m -> open(project, m))
                    .setMovable(true)
                    .setResizable(true)
                    .createPopup()
                    .showCenteredInCurrentWindow(project);
        }, ModalityState.nonModal());
    }

    private static void open(Project project, JspLocator.Match m) {
        if (!m.file().isValid()) return;
        new OpenFileDescriptor(project, m.file(), m.line(), m.column()).navigate(true);
    }

    private static String label(JspLocator.Match m) {
        return m.relativePath() + ":" + (m.line() + 1) + "   " + m.snippet();
    }
}
