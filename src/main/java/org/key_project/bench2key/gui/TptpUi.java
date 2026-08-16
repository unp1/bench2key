package org.key_project.bench2key.gui;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.key_project.bench2key.run.Format;
import org.key_project.bench2key.run.StrategyOptions;
import org.key_project.tptp2key.TptpFormat;
import org.key_project.tptp2key.TptpOptions;

/**
 * The TPTP tab's own control: where the library root is.
 *
 * Nearly every TPTP problem states its theory by reference, with {@code include('Axioms/...')}
 * resolved against the root of the library, so a problem read without it is a conjecture with
 * nothing to prove it from. Left empty, the root is looked for above the problem itself, which is
 * what the command line does too.
 */
public final class TptpUi implements FormatUi<TptpOptions> {

    private final TptpFormat format = new TptpFormat();
    private final JTextField library = new JTextField(26);

    @Override
    public Format<TptpOptions> format() {
        return format;
    }

    @Override
    public JComponent optionsPanel() {
        JPanel panel = new JPanel();
        panel.add(new JLabel("Axioms:"));
        panel.add(library);
        JButton browse = new JButton("...");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (!library.getText().isBlank()) {
                chooser.setCurrentDirectory(new File(library.getText()));
            }
            if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                library.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        panel.add(browse);
        panel.add(new JLabel("(empty means the Axioms of the collection, or above the problem)"));
        return panel;
    }

    @Override
    public Path axioms(Path root) {
        String text = library.getText().trim();
        if (!text.isEmpty()) {
            return Paths.get(text);
        }
        // A collection that carries its axioms names them the way the library does.
        Path beside = root == null ? null : root.resolve("Axioms");
        return beside != null && java.nio.file.Files.isDirectory(beside) ? beside : null;
    }

    @Override
    public TptpOptions options(StrategyOptions strategy) {
        String text = library.getText().trim();
        Path root = text.isEmpty() ? null : Paths.get(text);
        return new TptpOptions(root, 0, strategy);
    }

    @Override
    public void restore(Settings settings) {
        library.setText(settings.get(format.name(), "library", ""));
    }

    @Override
    public void save(Settings settings) {
        settings.set(format.name(), "library", library.getText().trim());
    }
}
