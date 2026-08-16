package org.key_project.bench2key.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JTabbedPane;

/**
 * The window: one tab per input language, over the same output directory and the same KeY.
 *
 * The tabs are separate because a collection is in one language at a time and the choices a
 * translation offers are not the same on both sides. What they share is everything after the
 * translation, so the settings that belong to the run rather than to the language, the output
 * directory and the KeY jar among them, are remembered once and appear filled in on both.
 */
public final class MainWindow extends JFrame {

    private final List<TranslatorPanel<?>> panels = new ArrayList<>();

    public MainWindow() {
        super("bench2key");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        Settings settings = new Settings();
        JTabbedPane tabs = new JTabbedPane();
        addTab(tabs, new TranslatorPanel<>(new SmtUi(), settings));
        addTab(tabs, new TranslatorPanel<>(new TptpUi(), settings));

        // A tab brought forward fills its shared fields in from what the other one last said, so
        // that choosing an output directory or a KeY jar once is enough for both.
        tabs.addChangeListener(e -> {
            int selected = tabs.getSelectedIndex();
            for (int i = 0; i < panels.size(); i++) {
                if (i != selected) {
                    panels.get(i).save();
                }
            }
            if (selected >= 0) {
                panels.get(selected).restore();
            }
        });

        add(tabs, BorderLayout.CENTER);
        setPreferredSize(new Dimension(1220, 800));
        pack();
        setLocationRelativeTo(null);
    }

    private void addTab(JTabbedPane tabs, TranslatorPanel<?> panel) {
        tabs.addTab(panel.formatName(), panel);
        panels.add(panel);
    }
}
