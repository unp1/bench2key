package org.key_project.bench2key.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.table.AbstractTableModel;

import org.key_project.bench2key.run.Problem;
import org.key_project.bench2key.run.ProofOutcome;

/** The problems of one logic, with what has happened to each of them so far. */
public final class ProblemTableModel extends AbstractTableModel {

    /** One problem and the state the session has built up around it. */
    public static final class Row {
        public final Problem problem;
        public Path target;
        public String translation = "";
        public ProofOutcome outcome;
        /** Settings for this problem alone, or null to use the default. */
        public org.key_project.bench2key.run.StrategyOptions strategy;

        Row(Problem problem, Path target) {
            this.problem = problem;
            this.target = target;
        }

        public boolean translated() {
            return target != null && Files.exists(target);
        }
    }

    private static final String[] COLUMNS =
        { "Problem", "Status", "Size", "Translated", "Proof", "Time", "Nodes", "Strategy" };

    private List<Row> rows = new ArrayList<>();

    public void setRows(List<Row> rows) {
        this.rows = rows;
        fireTableDataChanged();
    }

    public Row row(int index) {
        return rows.get(index);
    }

    public List<Row> rows() {
        return rows;
    }

    public void updated(Row row) {
        int i = rows.indexOf(row);
        if (i >= 0) {
            fireTableRowsUpdated(i, i);
        }
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == 2 ? Long.class : String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int column) {
        Row r = rows.get(rowIndex);
        return switch (column) {
            case 0 -> r.problem.name();
            case 1 -> r.problem.status();
            case 2 -> r.problem.size();
            case 3 -> r.translation.isEmpty() ? (r.translated() ? "yes" : "") : r.translation;
            case 4 -> r.outcome == null ? "" : r.outcome.summary();
            case 5 -> r.outcome == null ? "" : String.format("%.1fs", r.outcome.millis() / 1000.0);
            case 6 -> r.outcome == null ? "" : r.outcome.nodes();
            case 7 -> r.strategy == null ? "default" : "custom";
            default -> "";
        };
    }
}
