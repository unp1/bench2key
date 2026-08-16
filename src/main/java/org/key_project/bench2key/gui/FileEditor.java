package org.key_project.bench2key.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Shows a problem's SMT-LIB source and its .key translation side by side, and lets both be edited.
 *
 * A benchmark file can run to tens of megabytes, which no text area will hold usefully, so a large
 * file is shown in part and cannot be saved. Refusing to save is the point: writing back a buffer
 * that holds only the beginning of a file would destroy the rest of it.
 */
public final class FileEditor extends JDialog {

    /** Above this, a file is shown in part and editing is refused. */
    private static final long EDITABLE_LIMIT = 2L * 1024 * 1024;

    /** Told when a file has been written, so that stale results can be dropped. */
    @FunctionalInterface
    public interface Saved {
        void saved(Path file);
    }

    public FileEditor(java.awt.Window owner, Path smtFile, Path keyFile, Saved onSave) {
        super(owner, "View and edit", ModalityType.MODELESS);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("SMT-LIB source", panelFor(smtFile, onSave));
        tabs.addTab("KeY problem", panelFor(keyFile, onSave));
        add(tabs);
        setPreferredSize(new Dimension(900, 700));
        pack();
        setLocationRelativeTo(owner);
    }

    private static JPanel panelFor(Path file, Saved onSave) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JTextArea area = new JTextArea();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JLabel note = new JLabel();
        JButton save = new JButton("Save");
        save.setEnabled(false);

        if (file == null) {
            area.setText("Not translated yet.");
            area.setEditable(false);
            note.setText("No file");
        } else if (!Files.exists(file)) {
            area.setText("");
            area.setEditable(false);
            note.setText(file + " does not exist");
        } else {
            long size = size(file);
            // A problem read out of an archive is shown but not edited: saving it would rewrite
            // the archive, and a collection is something to read from, not to alter in place.
            boolean inArchive = !file.getFileSystem().equals(java.nio.file.FileSystems.getDefault());
            boolean whole = size <= EDITABLE_LIMIT && !inArchive;
            area.setText(read(file, size <= EDITABLE_LIMIT));
            area.setCaretPosition(0);
            area.setEditable(whole);
            note.setText(inArchive
                ? file + "  —  inside an archive, editing disabled"
                : whole
                    ? file.toString()
                    : file + "  —  showing the first " + (EDITABLE_LIMIT / 1024 / 1024)
                        + " MB of " + size / 1024 / 1024 + " MB, editing disabled");
            if (whole) {
                area.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        save.setEnabled(true);
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        save.setEnabled(true);
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                        save.setEnabled(true);
                    }
                });
                save.addActionListener(e -> {
                    try {
                        Files.writeString(file, area.getText());
                        save.setEnabled(false);
                        if (onSave != null) {
                            onSave.saved(file);
                        }
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(panel, "Cannot save: " + ex.getMessage(),
                            "smt2key", JOptionPane.ERROR_MESSAGE);
                    }
                });
            }
        }

        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.add(note, BorderLayout.CENTER);
        bar.add(save, BorderLayout.EAST);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        panel.add(bar, BorderLayout.SOUTH);
        return panel;
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String read(Path file, boolean whole) {
        try {
            if (whole) {
                return Files.readString(file);
            }
            try (var in = Files.newInputStream(file)) {
                return new String(in.readNBytes((int) EDITABLE_LIMIT), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return "Cannot read " + file + ": " + e.getMessage();
        }
    }
}
