package org.key_project.bench2key.gui;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import org.key_project.bench2key.run.StrategyOptions;

/**
 * The KeY strategy settings, either as the default for everything or for the selected problems.
 *
 * The settings are written into the generated .key file, so choosing them for a selection means
 * those problems are translated again before they are proved.
 */
public final class StrategyDialog extends JDialog {

    /** Where the edited settings are to apply. */
    public enum Scope {
        GLOBAL, SELECTION
    }

    private final JSpinner maxSteps =
        new JSpinner(new SpinnerNumberModel(StrategyOptions.defaults().maxSteps(), 100, 10_000_000, 1000));
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(-1, -1, 3_600_000, 1000));
    private final JComboBox<String> arithmetic = new JComboBox<>(StrategyOptions.NON_LINEAR_ARITHMETIC);
    private final JComboBox<String> quantifiers = new JComboBox<>(StrategyOptions.QUANTIFIERS);
    private final JComboBox<String> splitting = new JComboBox<>(StrategyOptions.SPLITTING);
    private final JComboBox<String> triggers = new JComboBox<>(StrategyOptions.TRIGGERS);

    private final JRadioButton global = new JRadioButton("the default for every problem", true);
    private final JRadioButton selection;

    private final int mainWindowTimeout;
    private StrategyOptions result;
    private Scope scope = Scope.GLOBAL;

    /**
     * @param initial the settings to start from
     * @param selectedCount how many problems are selected, which decides whether the per selection
     *        scope is offered
     * @param mainWindowTimeout the timeout the main window is set to, which the strategy timeout
     *        follows unless a strategy timeout of its own has been chosen
     */
    public StrategyDialog(java.awt.Window owner, StrategyOptions initial, int selectedCount,
            int mainWindowTimeout) {
        super(owner, "Strategy settings", ModalityType.APPLICATION_MODAL);
        this.mainWindowTimeout = mainWindowTimeout;
        selection = new JRadioButton("only the " + selectedCount + " selected problems");
        selection.setEnabled(selectedCount > 0);

        setLayout(new BorderLayout(8, 8));
        add(scopePanel(), BorderLayout.NORTH);
        add(settingsPanel(), BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);
        show(initial);
        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel scopePanel() {
        ButtonGroup group = new ButtonGroup();
        group.add(global);
        group.add(selection);
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createTitledBorder("Apply to"));
        panel.add(global);
        panel.add(selection);
        return panel;
    }

    private JPanel settingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("KeY strategy"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 6, 3, 6);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        addRow(panel, c, row++, "Max rule applications", maxSteps);
        addRow(panel, c, row++, "Strategy timeout (ms, -1 for none)", timeout);
        addRow(panel, c, row++, "Non-linear arithmetic", arithmetic);
        addRow(panel, c, row++, "Quantifiers", quantifiers);
        addRow(panel, c, row++, "Quantifier triggers", triggers);
        addRow(panel, c, row, "Splitting", splitting);
        return panel;
    }

    private void addRow(JPanel panel, GridBagConstraints c, int row, String label,
            java.awt.Component field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
    }

    private JPanel buttons() {
        JPanel panel = new JPanel();
        JButton reset = new JButton("Reset to KeY defaults");
        reset.addActionListener(e -> show(StrategyOptions.defaults()));
        // Only quantifier instantiation consults the triggers.
        quantifiers.addActionListener(e -> syncTriggers());
        syncTriggers();
        JButton ok = new JButton("Apply");
        ok.addActionListener(e -> {
            result = read();
            scope = selection.isSelected() ? Scope.SELECTION : Scope.GLOBAL;
            dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        panel.add(reset);
        panel.add(cancel);
        panel.add(ok);
        getRootPane().setDefaultButton(ok);
        return panel;
    }

    /** Triggers are consulted only when quantifiers are instantiated freely. */
    private void syncTriggers() {
        triggers.setEnabled("QUANTIFIERS_INSTANTIATE".equals(quantifiers.getSelectedItem()));
    }

    private void show(StrategyOptions o) {
        maxSteps.setValue(o.maxSteps());
        // A strategy timeout that was never chosen follows the one set in the main window, so the
        // two do not quietly disagree about how long a proof may take.
        timeout.setValue(o.timeoutMillis() < 0 ? mainWindowTimeout : o.timeoutMillis());
        arithmetic.setSelectedItem(o.nonLinearArithmetic());
        quantifiers.setSelectedItem(o.quantifiers());
        splitting.setSelectedItem(o.splitting());
        triggers.setSelectedItem(o.triggers());
    }

    private StrategyOptions read() {
        return new StrategyOptions((Integer) maxSteps.getValue(), (Integer) timeout.getValue(),
            (String) arithmetic.getSelectedItem(), (String) quantifiers.getSelectedItem(),
            (String) splitting.getSelectedItem(), (String) triggers.getSelectedItem());
    }

    /** The settings chosen, or null if the dialog was cancelled. */
    public StrategyOptions result() {
        return result;
    }

    public Scope scope() {
        return scope;
    }
}
