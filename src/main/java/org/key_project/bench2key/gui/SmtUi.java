package org.key_project.bench2key.gui;

import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.key_project.bench2key.run.Format;
import org.key_project.bench2key.run.StrategyOptions;
import org.key_project.smt2key.SmtFormat;
import org.key_project.smt2key.Translator;
import org.key_project.smt2key.run.Options;

/** The SMT-LIB tab's own controls: how arrays are encoded and how {@code let} is translated. */
public final class SmtUi implements FormatUi<Options> {

    private final SmtFormat format = new SmtFormat();

    private final JComboBox<String> arrays =
        new JComboBox<>(new String[] { "axioms", "heap", "seq" });
    private final JComboBox<String> lets = new JComboBox<>(new String[] { "symbols", "inline" });
    private final JCheckBox extensional = new JCheckBox("array extensionality", true);

    @Override
    public Format<Options> format() {
        return format;
    }

    @Override
    public JComponent optionsPanel() {
        JPanel panel = new JPanel();
        panel.add(new JLabel("Arrays:"));
        panel.add(arrays);
        panel.add(extensional);
        panel.add(Box.createHorizontalStrut(8));
        panel.add(new JLabel("let:"));
        panel.add(lets);
        return panel;
    }

    @Override
    public Options options(StrategyOptions strategy) {
        Translator.LetMode mode = "inline".equals(lets.getSelectedItem())
                ? Translator.LetMode.INLINE
                : Translator.LetMode.SYMBOLS;
        return new Options((String) arrays.getSelectedItem(), extensional.isSelected(), mode, null,
            strategy);
    }

    @Override
    public void restore(Settings settings) {
        arrays.setSelectedItem(settings.get(format.name(), "arrays", "axioms"));
        lets.setSelectedItem(settings.get(format.name(), "lets", "symbols"));
        extensional.setSelected(settings.getBoolean(format.name(), "extensional", true));
    }

    @Override
    public void save(Settings settings) {
        settings.set(format.name(), "arrays", (String) arrays.getSelectedItem());
        settings.set(format.name(), "lets", (String) lets.getSelectedItem());
        settings.setBoolean(format.name(), "extensional", extensional.isSelected());
    }
}
