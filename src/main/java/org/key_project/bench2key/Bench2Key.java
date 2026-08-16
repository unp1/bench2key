package org.key_project.bench2key;

import java.util.Arrays;

/**
 * The way in: one window over both translators, or either command line tool.
 *
 * The two input languages differ in what they can express and in what has to be said about a
 * problem before it can be translated, but everything after the translation is the same work:
 * scanning a collection, filtering it by declared status, writing .key files and proving them with
 * KeY. So the front ends stay apart and everything behind them is shared, the window included,
 * where each language has a tab of its own.
 */
public final class Bench2Key {

    private static final String USAGE = """
        bench2key: benchmark problems to KeY problem files.

        Usage: bench2key [--gui]                  the window, with a tab per input language
               bench2key smt   [options] <path>   the SMT-LIB command line tool
               bench2key tptp  [options] <path>   the TPTP command line tool
               bench2key check <library>          the three sets worth running, in about a minute

        Pass --help after smt, tptp or check for the options of that tool.
        """;

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "--gui" : args[0];
        String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (command) {
            case "--gui", "-gui" -> org.key_project.bench2key.gui.Bench2KeyGui.main(rest);
            case "smt", "smt2key" -> org.key_project.smt2key.Smt2Key.main(rest);
            case "tptp", "tptp2key" -> org.key_project.tptp2key.Tptp2Key.main(rest);
            case "check" -> Check.main(rest);
            case "-h", "--help" -> System.out.print(USAGE);
            default -> {
                System.err.println("unknown command " + command);
                System.err.print(USAGE);
                System.exit(2);
            }
        }
    }
}
