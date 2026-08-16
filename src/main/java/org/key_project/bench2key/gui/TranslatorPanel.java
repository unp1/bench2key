package org.key_project.bench2key.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import org.key_project.bench2key.run.Corpus;
import org.key_project.bench2key.run.Format;
import org.key_project.bench2key.run.KeyRunner;
import org.key_project.bench2key.run.Problem;
import org.key_project.bench2key.run.ProofOutcome;
import org.key_project.bench2key.run.Result;
import org.key_project.bench2key.run.Sources;
import org.key_project.bench2key.run.StrategyOptions;
import org.key_project.bench2key.run.SubprocessRunner;

/**
 * One input language's tab: pick a collection and an output directory, translate, and prove.
 *
 * Everything slow runs on a worker, so the window stays responsive and a long batch can be
 * stopped. Nothing here touches the table or the labels off the event thread.
 *
 * @param <O> the language's translation settings, which only {@link FormatUi} knows the shape of
 */
public final class TranslatorPanel<O> extends JPanel {

    private final FormatUi<O> ui;
    private final Format<O> format;
    private final Settings settings;

    private final JTextField sourceDirectory = new JTextField(34);
    private final JTextField outputDirectory = new JTextField(34);
    private final JTextField keyJar = new JTextField(34);

    private final JSpinner timeout =
        new JSpinner(new SpinnerNumberModel(10_000, 1000, 3_600_000, 1000));
    private final JSpinner jobs = new JSpinner(new SpinnerNumberModel(2, 1, 32, 1));
    private final JComboBox<String> runnerKind = new JComboBox<>(
        KeyRunner.inProcessAvailable()
                ? new String[] { "a process per problem", "in this process" }
                : new String[] { "a process per problem" });
    private final JComboBox<String> cores =
        new JComboBox<>(new String[] { "single core", "multi core" });
    private final JSpinner coreCount = new JSpinner(new SpinnerNumberModel(
        Settings.DEFAULT_CORES, 1, Runtime.getRuntime().availableProcessors(), 1));
    private final JComboBox<String> statusFilter;

    private final DefaultListModel<String> categoryModel = new DefaultListModel<>();
    private final JList<String> categoryList = new JList<>(categoryModel);
    private final ProblemTableModel tableModel = new ProblemTableModel();
    private final JTable table = new JTable(tableModel);
    private final StatisticsPanel statistics = new StatisticsPanel();

    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel();
    private final JButton cancel = new JButton("Stop");

    private Corpus corpus;
    /** Held open for as long as its paths are in use, which is until the next scan. */
    private Sources sources;
    private final AtomicBoolean stopRequested = new AtomicBoolean();

    public TranslatorPanel(FormatUi<O> ui, Settings settings) {
        this.ui = ui;
        this.format = ui.format();
        this.settings = settings;

        List<String> choices = new ArrayList<>();
        choices.add("all");
        choices.addAll(format.statuses());
        statusFilter = new JComboBox<>(choices.toArray(new String[0]));
        status.setText("Choose the " + format.sourceLabel() + " and press Scan.");

        setLayout(new BorderLayout(6, 6));
        add(directoriesAndOptions(), BorderLayout.NORTH);
        add(centre(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        cores.addActionListener(e -> syncCoreCount());
        restore();
        syncCoreCount();
    }

    /** The language this tab is for, which is what the tab is labelled with. */
    public String formatName() {
        return format.name();
    }

    // ------------------------------------------------------------------ layout

    private JPanel directoriesAndOptions() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        GridBagConstraints c = new GridBagConstraints();

        // A collection is a directory or a .zip of one, so both can be picked.
        addDirectoryRow(panel, c, 0, format.sourceLabel() + ":", sourceDirectory,
            JFileChooser.FILES_AND_DIRECTORIES);
        addDirectoryRow(panel, c, 1, "Output directory:", outputDirectory,
            JFileChooser.DIRECTORIES_ONLY);
        addDirectoryRow(panel, c, 2, "KeY jar:", keyJar, JFileChooser.FILES_ONLY);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        options.add(ui.optionsPanel());

        JPanel proving = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        proving.add(new JLabel("Timeout (ms):"));
        proving.add(timeout);
        proving.add(new JLabel("Run:"));
        proving.add(runnerKind);
        proving.add(cores);
        proving.add(coreCount);
        proving.add(new JLabel("problems at once:"));
        proving.add(jobs);

        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 3;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(options, c);
        c.gridy = 4;
        panel.add(proving, c);
        return panel;
    }

    private void addDirectoryRow(JPanel panel, GridBagConstraints c, int row, String label,
            JTextField field, int mode) {
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 2, 2, 6);
        panel.add(new JLabel(label), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);

        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JButton browse = new JButton("...");
        browse.addActionListener(e -> choose(field, mode));
        panel.add(browse, c);
    }

    /** The core count applies only to multi core, so it follows that choice. */
    private void syncCoreCount() {
        coreCount.setEnabled(cores.getSelectedIndex() == 1);
    }

    private JSplitPane centre() {
        categoryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        categoryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showCategory();
            }
        });
        JScrollPane categories = new JScrollPane(categoryList);
        categories.setBorder(BorderFactory.createTitledBorder(format.categoryLabel()));

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ProblemTableModel.Row row = focusedRow();
                statistics.show(row == null ? null : row.outcome);
            }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    viewAndEdit();
                }
            }
        });
        JScrollPane problems = new JScrollPane(table);
        JPanel problemPane = new JPanel(new BorderLayout(4, 4));
        problemPane.setBorder(BorderFactory.createTitledBorder("Problems"));
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        filterBar.add(new JLabel("Declared status:"));
        filterBar.add(statusFilter);
        filterBar.add(new JLabel(format.provableNote()));
        statusFilter.addActionListener(e -> {
            save();
            showCategory();
        });
        problemPane.add(filterBar, BorderLayout.NORTH);
        problemPane.add(problems, BorderLayout.CENTER);

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, problemPane, statistics);
        right.setResizeWeight(0.7);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, categories, right);
        split.setResizeWeight(0.18);
        return split;
    }

    private JPanel actions() {
        JPanel buttons = new JPanel();
        buttons.add(button("Scan", e -> scan()));
        buttons.add(button("Translate selected", e -> translate(selectedRows())));
        buttons.add(button("Translate " + format.categoryLabel().toLowerCase().replaceAll("s$", ""),
            e -> translate(tableModel.rows())));
        buttons.add(button("Prove selected", e -> prove(selectedRows())));
        buttons.add(button("Strategy settings...", e -> editStrategy()));
        buttons.add(button("View / edit", e -> viewAndEdit()));
        buttons.add(button("Open in KeY", e -> openInKey()));
        cancel.setEnabled(false);
        cancel.addActionListener(e -> stopRequested.set(true));
        buttons.add(cancel);

        progress.setStringPainted(true);
        JPanel south = new JPanel(new BorderLayout(6, 2));
        south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        south.add(buttons, BorderLayout.NORTH);
        south.add(progress, BorderLayout.CENTER);
        south.add(status, BorderLayout.SOUTH);
        return south;
    }

    private JButton button(String text, java.awt.event.ActionListener action) {
        JButton b = new JButton(text);
        b.addActionListener(action);
        return b;
    }

    // ------------------------------------------------------------------ actions

    private void scan() {
        Path root = pathOf(sourceDirectory);
        if (root == null) {
            warn("Choose the " + format.sourceLabel() + " first.");
            return;
        }
        save();
        running(true, "Scanning " + root);
        new Worker<Corpus>() {
            @Override
            protected Corpus work() throws IOException {
                // The previous collection's paths go out of use with it; an archive left open
                // would hold its file for as long as the window did.
                if (sources != null) {
                    sources.close();
                }
                sources = Sources.open(root);
                // The axiom files are what problems include, not problems themselves, so they are
                // named separately and left out of what is scanned.
                return Corpus.scan(sources.root(), format, ui.axioms(sources.root()), (done, total) -> {
                    publish("read " + done + " of " + total + " files");
                    SwingUtilities.invokeLater(() -> progress.setValue(100 * done / total));
                });
            }

            @Override
            protected void finished(Corpus result) {
                corpus = result;
                categoryModel.clear();
                Map<String, Integer> categories = result.categories();
                categories.forEach((name, count) -> categoryModel.addElement(name + "  (" + count + ")"));
                if (!categoryModel.isEmpty()) {
                    categoryList.setSelectedIndex(0);
                }
                long provable = result.problems().stream().filter(Problem::provable).count();
                status.setText(result.problems().size() + " problems, " + categories.size() + " "
                    + format.categoryLabel().toLowerCase() + ", " + provable + " with a proof to find");
            }
        }.execute();
    }

    private void showCategory() {
        String selected = categoryList.getSelectedValue();
        if (corpus == null || selected == null) {
            return;
        }
        String category = selected.substring(0, selected.indexOf("  (")).trim();
        Path out = pathOf(outputDirectory);
        String wanted = (String) statusFilter.getSelectedItem();
        List<Problem> all = corpus.byCategory(category);
        List<ProblemTableModel.Row> rows = new ArrayList<>();
        for (Problem p : all) {
            if ("all".equals(wanted) || p.status().equals(wanted)) {
                rows.add(new ProblemTableModel.Row(p, out == null ? null : corpus.targetFor(p, out)));
            }
        }
        tableModel.setRows(rows);
        statistics.show(null);
        int hidden = all.size() - rows.size();
        status.setText(category + ": " + rows.size() + " problems"
            + (hidden > 0 ? ", " + hidden + " hidden by the status filter" : ""));
    }

    private void translate(List<ProblemTableModel.Row> rows) {
        if (rows.isEmpty()) {
            warn("Select at least one problem.");
            return;
        }
        Path out = pathOf(outputDirectory);
        if (out == null) {
            warn("Choose an output directory first.");
            return;
        }
        save();
        running(true, "Translating " + rows.size() + " problems");
        new Worker<Integer>() {
            @Override
            protected Integer work() {
                int ok = 0;
                for (int i = 0; i < rows.size() && !stopRequested.get(); i++) {
                    ProblemTableModel.Row row = rows.get(i);
                    row.target = corpus.targetFor(row.problem, out);
                    Result r = format.translate(row.problem.source(), row.target, optionsFor(row));
                    row.translation = r.ok() ? "yes" : r.outcome() + ": " + r.detail();
                    if (r.ok()) {
                        ok++;
                    }
                    int done = i + 1;
                    SwingUtilities.invokeLater(() -> {
                        tableModel.updated(row);
                        progress.setValue(100 * done / rows.size());
                    });
                    publish(done + " of " + rows.size());
                }
                return ok;
            }

            @Override
            protected void finished(Integer ok) {
                status.setText(ok + " of " + rows.size() + " translated");
            }
        }.execute();
    }

    private void prove(List<ProblemTableModel.Row> rows) {
        if (rows.isEmpty()) {
            warn("Select at least one problem.");
            return;
        }
        Path out = pathOf(outputDirectory);
        if (out == null) {
            warn("Choose an output directory first.");
            return;
        }
        KeyRunner runner;
        try {
            runner = newRunner();
        } catch (IOException e) {
            warn("Cannot start KeY: " + e.getMessage());
            return;
        }
        save();
        int timeoutMillis = (Integer) timeout.getValue();
        int parallel = (Integer) jobs.getValue();
        running(true, "Proving " + rows.size() + " problems " + runner.describe());
        new Worker<Integer>() {
            @Override
            protected Integer work() throws Exception {
                int[] proved = { 0 };
                int[] done = { 0 };
                try (ExecutorService pool = Executors.newFixedThreadPool(parallel)) {
                    for (ProblemTableModel.Row row : rows) {
                        pool.submit(() -> {
                            if (stopRequested.get()) {
                                return;
                            }
                            // A problem has to be translated before it can be proved; doing it here
                            // keeps the button meaning "prove this", whatever state the row is in.
                            // A row given its own strategy needs translating again: the settings
                            // are written into the file, so the old one states the wrong strategy.
                            if (!row.translated() || row.translation.isEmpty()) {
                                row.target = corpus.targetFor(row.problem, out);
                                Result r = format.translate(row.problem.source(), row.target,
                                    optionsFor(row));
                                row.translation = r.ok() ? "yes" : r.outcome() + ": " + r.detail();
                                if (!r.ok()) {
                                    update(row, ++done[0], rows.size());
                                    return;
                                }
                            }
                            ProofOutcome outcome = runner.prove(row.target, timeoutMillis);
                            row.outcome = outcome;
                            if (outcome.result() == ProofOutcome.Result.PROVED) {
                                proved[0]++;
                            }
                            update(row, ++done[0], rows.size());
                        });
                    }
                    pool.shutdown();
                    pool.awaitTermination(365, TimeUnit.DAYS);
                }
                return proved[0];
            }

            @Override
            protected void finished(Integer proved) {
                status.setText(proved + " of " + rows.size() + " proved");
                ProblemTableModel.Row row = focusedRow();
                statistics.show(row == null ? null : row.outcome);
                runner.close();
            }
        }.execute();
    }

    private void update(ProblemTableModel.Row row, int done, int total) {
        SwingUtilities.invokeLater(() -> {
            tableModel.updated(row);
            progress.setValue(100 * done / total);
            status.setText(done + " of " + total);
        });
    }

    private void openInKey() {
        ProblemTableModel.Row row = focusedRow();
        if (row == null) {
            warn("Select a problem first.");
            return;
        }
        // A finished proof is more use in the window than the problem it came from.
        Path file = row.outcome != null && row.outcome.proofFile() != null
            ? row.outcome.proofFile()
            : row.target;
        if (file == null) {
            warn("Translate the problem first.");
            return;
        }
        try {
            KeyRunner runner = newRunner();
            runner.openInKeyGui(file);
            status.setText("Opened " + file.getFileName() + " in KeY");
        } catch (IOException e) {
            warn("Cannot start KeY: " + e.getMessage());
        }
    }

    /**
     * Opens the source and its translation for reading, and for editing when they are small enough.
     *
     * Saving either one makes what is already known about the row wrong, so the proof result and
     * the translation state are dropped rather than left to describe a file that has changed.
     */
    private void viewAndEdit() {
        ProblemTableModel.Row row = focusedRow();
        if (row == null) {
            warn("Select a problem first.");
            return;
        }
        Path out = pathOf(outputDirectory);
        if (row.target == null && out != null && corpus != null) {
            row.target = corpus.targetFor(row.problem, out);
        }
        new FileEditor(window(), row.problem.source(), row.target, saved -> {
            row.outcome = null;
            if (saved.equals(row.problem.source())) {
                row.translation = "";
            }
            tableModel.updated(row);
            statistics.show(null);
            status.setText("Saved " + saved.getFileName() + "; earlier results dropped");
        }).setVisible(true);
    }

    /**
     * Edits the KeY strategy settings, either as the default or for the selected problems.
     *
     * The settings live in the generated file, so a problem given settings of its own is translated
     * again on the next attempt; its earlier translation described a different strategy.
     */
    private void editStrategy() {
        List<ProblemTableModel.Row> selected = selectedRows();
        StrategyDialog dialog = new StrategyDialog(window(), settings.strategy(), selected.size(),
            (Integer) timeout.getValue());
        dialog.setVisible(true);
        StrategyOptions chosen = dialog.result();
        if (chosen == null) {
            return;
        }
        if (dialog.scope() == StrategyDialog.Scope.SELECTION) {
            for (ProblemTableModel.Row row : selected) {
                row.strategy = chosen;
                row.translation = "";
                row.outcome = null;
                tableModel.updated(row);
            }
            status.setText("Strategy set for " + selected.size()
                + " problems; they will be translated again");
        } else {
            settings.strategy(chosen);
            status.setText("Default strategy updated");
        }
    }

    /** The options for one problem: what the tab says, with that problem's own strategy if it has one. */
    private O optionsFor(ProblemTableModel.Row row) {
        return ui.options(row.strategy == null ? settings.strategy() : row.strategy);
    }

    private KeyRunner newRunner() throws IOException {
        int threads = cores();
        if (runnerKind.getSelectedIndex() == 1) {
            return KeyRunner.inProcess(threads);
        }
        Path jar = pathOf(keyJar);
        if (jar == null) {
            throw new IOException("no KeY jar chosen");
        }
        return new SubprocessRunner(jar, "6g", threads);
    }

    /**
     * Worker threads for one proof.
     *
     * This is KeY's own parallel proof search, which is a different thing from running several
     * problems at once: the one splits a single proof over cores, the other fills the cores with
     * separate proofs. Both together oversubscribe the machine, so multi core pairs with a small
     * number of problems at once.
     */
    private int cores() {
        return cores.getSelectedIndex() == 1 ? (Integer) coreCount.getValue() : 1;
    }

    // ------------------------------------------------------------------ helpers

    /** A background task that keeps the buttons and the progress bar in step with itself. */
    private abstract class Worker<T> extends SwingWorker<T, String> {

        protected abstract T work() throws Exception;

        protected abstract void finished(T result);

        @Override
        protected final T doInBackground() throws Exception {
            return work();
        }

        @Override
        protected final void process(List<String> messages) {
            status.setText(messages.get(messages.size() - 1));
        }

        @Override
        protected final void done() {
            try {
                finished(get());
            } catch (Exception e) {
                warn(String.valueOf(e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
            } finally {
                running(false, null);
            }
        }
    }

    /**
     * Puts the buttons and the progress bar into the state of a run starting or ending.
     *
     * A finished run leaves the bar full rather than resetting it to nothing, so that the last
     * thing shown says the work completed instead of looking as though it never began.
     */
    private void running(boolean on, String message) {
        cancel.setEnabled(on);
        progress.setIndeterminate(false);
        if (on) {
            stopRequested.set(false);
            progress.setValue(0);
        } else {
            progress.setValue(100);
        }
        if (message != null) {
            status.setText(message);
        }
    }

    private List<ProblemTableModel.Row> selectedRows() {
        List<ProblemTableModel.Row> rows = new ArrayList<>();
        for (int viewIndex : table.getSelectedRows()) {
            rows.add(tableModel.row(table.convertRowIndexToModel(viewIndex)));
        }
        return rows;
    }

    private ProblemTableModel.Row focusedRow() {
        int viewIndex = table.getSelectedRow();
        return viewIndex < 0 ? null : tableModel.row(table.convertRowIndexToModel(viewIndex));
    }

    private void choose(JTextField field, int mode) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(mode);
        if (!field.getText().isBlank()) {
            chooser.setCurrentDirectory(new File(field.getText()).getParentFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
            save();
        }
    }

    private java.awt.Window window() {
        return SwingUtilities.getWindowAncestor(this);
    }

    private Path pathOf(JTextField field) {
        String text = field.getText().trim();
        return text.isEmpty() ? null : Paths.get(text);
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, format.name(), JOptionPane.WARNING_MESSAGE);
    }

    /** Fills the fields in from what was remembered, the tab's own answers and the shared ones. */
    public void restore() {
        setText(sourceDirectory, settings.sourceDirectory(format.name()));
        setText(outputDirectory, settings.outputDirectory());
        setText(keyJar, settings.keyJar());
        timeout.setValue(settings.timeout());
        jobs.setValue(settings.jobs());
        runnerKind.setSelectedIndex("inprocess".equals(settings.runner()) ? 1 : 0);
        statusFilter.setSelectedItem(settings.statusFilter(format.name()));
        cores.setSelectedIndex(settings.multiCore() ? 1 : 0);
        coreCount.setValue(Math.min(settings.coreCount(), Runtime.getRuntime().availableProcessors()));
        ui.restore(settings);
    }

    /** Remembers what the fields say. Called before anything long, so a crash loses nothing. */
    public void save() {
        settings.sourceDirectory(format.name(), pathOf(sourceDirectory));
        settings.outputDirectory(pathOf(outputDirectory));
        settings.keyJar(pathOf(keyJar));
        settings.timeout((Integer) timeout.getValue());
        settings.jobs((Integer) jobs.getValue());
        settings.runner(runnerKind.getSelectedIndex() == 1 ? "inprocess" : "subprocess");
        settings.statusFilter(format.name(), (String) statusFilter.getSelectedItem());
        settings.multiCore(cores.getSelectedIndex() == 1);
        settings.coreCount((Integer) coreCount.getValue());
        ui.save(settings);
    }

    private static void setText(JTextField field, Path p) {
        if (p != null) {
            field.setText(p.toString());
        }
    }
}
