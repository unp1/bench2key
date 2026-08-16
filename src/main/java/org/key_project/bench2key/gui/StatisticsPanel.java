package org.key_project.bench2key.gui;

import java.awt.BorderLayout;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

import org.key_project.bench2key.run.ProofOutcome;

/** KeY's figures for one proof. */
public final class StatisticsPanel extends JPanel {

    private final DefaultTableModel model =
        new DefaultTableModel(new Object[] { "Measure", "Value" }, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    private final JLabel heading = new JLabel("No proof selected");

    public StatisticsPanel() {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createTitledBorder("Proof statistics"));
        heading.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        add(heading, BorderLayout.NORTH);
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    /** Shows the outcome of one proof, or clears the panel when there is none. */
    public void show(ProofOutcome outcome) {
        model.setRowCount(0);
        if (outcome == null) {
            heading.setText("No proof selected");
            return;
        }
        heading.setText(String.format("%s   %s   %.1f s",
            outcome.keyFile().getFileName(), outcome.summary(), outcome.millis() / 1000.0));
        for (Map.Entry<String, String> e : outcome.statistics().entrySet()) {
            model.addRow(new Object[] { e.getKey(), e.getValue() });
        }
        if (outcome.statistics().isEmpty() && !outcome.log().isEmpty()) {
            model.addRow(new Object[] { "message", outcome.log().lines().findFirst().orElse("") });
        }
    }
}
