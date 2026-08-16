package org.key_project.bench2key.gui;

import java.nio.file.Paths;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;



/** Starts the graphical front end. */
public final class Bench2KeyGui {

    private static final String USAGE = """
        bench2key --gui [--smt DIR] [--tptp DIR] [--out DIR] [--key JAR]

        The paths are remembered between sessions; passing them just fills the fields in.
        """;

    private Bench2KeyGui() {
    }

    public static void main(String[] args) {
        Settings settings = new Settings();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--smt" -> settings.sourceDirectory("SMT-LIB", Paths.get(args[++i]));
                    case "--tptp" -> settings.sourceDirectory("TPTP", Paths.get(args[++i]));
                    case "--out" -> settings.outputDirectory(Paths.get(args[++i]));
                    case "--key" -> settings.keyJar(Paths.get(args[++i]));
                    case "-h", "--help" -> {
                        System.out.print(USAGE);
                        return;
                    }
                    default -> { /* the command line front ends share this argument list */ }
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("missing argument after " + args[args.length - 1]);
            System.exit(2);
        }

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // The cross-platform look and feel is a perfectly good fallback.
            }
            new MainWindow().setVisible(true);
        });
    }
}
