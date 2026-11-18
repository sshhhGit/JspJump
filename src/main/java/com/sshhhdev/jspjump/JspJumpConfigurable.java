package com.sshhhdev.jspjump;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.ide.BuiltInServerManager;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.Objects;

/**
 * Settings | Tools | JSP Jump
 */
public final class JspJumpConfigurable implements Configurable {

    private JBCheckBox enabled;
    private JBCheckBox bringToFront;
    private JBTextArea origins;
    private JBTextArea includes;
    private JBTextArea scripts;
    private JBTextArea excludes;
    private JBTextField columnFunctions;

    @Override
    public @Nls String getDisplayName() {
        return "JSP Jump";
    }

    @Override
    public @Nullable JComponent createComponent() {
        enabled = new JBCheckBox("Accept jump requests from the browser");
        bringToFront = new JBCheckBox("Bring the IDE window to front");
        origins = area();
        includes = area();
        scripts = area();
        excludes = area();
        columnFunctions = new JBTextField();

        int port = BuiltInServerManager.getInstance().getPort();
        JBLabel endpoint = new JBLabel("Endpoint: http://localhost:" + port + "/jspjump   (IDE built-in server port "
                + port + "; the bookmarklet finds ports 63342-63361 automatically)");
        endpoint.setCopyable(true);

        JPanel panel = FormBuilder.createFormBuilder()
                .addComponent(enabled)
                .addComponent(bringToFront)
                .addComponent(endpoint)
                .addComponent(installButton())
                .addLabeledComponent("Allowed page origins (one per line, * = wildcard):", new JBScrollPane(origins), true)
                .addLabeledComponent("JSP files to search (glob, project-relative):", new JBScrollPane(includes), true)
                .addLabeledComponent("Script files searched after the JSPs (glob; scripts loaded by the screen JSP come first):",
                        new JBScrollPane(scripts), true)
                .addLabeledComponent("Excluded paths (glob):", new JBScrollPane(excludes), true)
                .addLabeledComponent("Grid column functions (comma separated):", columnFunctions, true)
                .addComponent(hint("Column functions are matched as  .fn(\"dataField\", \"header\" ...)  "
                        + "and  dataField: \"...\"  is always searched."))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    private static JComponent installButton() {
        javax.swing.JButton b = new javax.swing.JButton("Add Browser Bookmark... (북마크 추가 페이지 열기)");
        b.addActionListener(e -> com.intellij.ide.BrowserUtil.browse(JspJumpNotifications.installPageUrl()));
        JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        p.add(b);
        return p;
    }

    private static JBTextArea area() {
        JBTextArea a = new JBTextArea(4, 50);
        a.setLineWrap(false);
        return a;
    }

    private static JBLabel hint(String text) {
        JBLabel l = new JBLabel(text);
        l.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        l.setForeground(UIUtil.getContextHelpForeground());
        return l;
    }

    @Override
    public boolean isModified() {
        JspJumpSettings.State s = JspJumpSettings.getInstance().getState();
        return enabled.isSelected() != s.enabled
                || bringToFront.isSelected() != s.bringIdeToFront
                || !Objects.equals(origins.getText(), s.allowedOrigins)
                || !Objects.equals(includes.getText(), s.includeGlobs)
                || !Objects.equals(scripts.getText(), s.scriptGlobs)
                || !Objects.equals(excludes.getText(), s.excludeGlobs)
                || !Objects.equals(columnFunctions.getText(), s.columnFunctions);
    }

    @Override
    public void apply() {
        JspJumpSettings.State s = JspJumpSettings.getInstance().getState();
        s.enabled = enabled.isSelected();
        s.bringIdeToFront = bringToFront.isSelected();
        s.allowedOrigins = origins.getText();
        s.includeGlobs = includes.getText();
        s.scriptGlobs = scripts.getText();
        s.excludeGlobs = excludes.getText();
        s.columnFunctions = columnFunctions.getText();
    }

    @Override
    public void reset() {
        if (enabled == null) return;
        JspJumpSettings.State s = JspJumpSettings.getInstance().getState();
        enabled.setSelected(s.enabled);
        bringToFront.setSelected(s.bringIdeToFront);
        origins.setText(s.allowedOrigins);
        includes.setText(s.includeGlobs);
        scripts.setText(s.scriptGlobs);
        excludes.setText(s.excludeGlobs);
        columnFunctions.setText(s.columnFunctions);
    }

    @Override
    public void disposeUIResources() {
        enabled = null;
        bringToFront = null;
        origins = includes = scripts = excludes = null;
        columnFunctions = null;
    }
}
