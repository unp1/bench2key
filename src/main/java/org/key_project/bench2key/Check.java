package org.key_project.bench2key;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The three sets worth running against a collection, in one command.
 *
 * Running everything takes hours and answers one question, how large the translated fraction is to
 * within a fraction of a percent. These three answer the questions actually being asked, in about a
 * minute: does each language still work, does every domain still work and roughly what fraction
 * translates, and do the problems at the edges of the library still get through.
 */
public final class Check {

    private static final String USAGE = """
        bench2key check <library> [options]

        Runs three sets against a TPTP collection, a directory or a .zip of one:

          smoke      one problem of each language
          spread     ten problems of every domain, for a coverage figure and per domain regressions
          extremes   the twelve largest problems, where faults of size and depth are

        Any further options are passed on to `bench2key tptp`, so --tptp, --max-size and the rest
        work as they do there. Nothing is written; this only reports.
        """;

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.print(USAGE);
            return;
        }
        Path library = Paths.get(args[0]);
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);

        for (String sample : new String[] { "smoke", "spread", "extremes" }) {
            System.out.println();
            System.out.println("=== " + sample + " ".repeat(12 - sample.length()) + "=================");
            String[] call = new String[rest.length + 5];
            call[0] = "--dry-run";
            call[1] = "--quiet";
            call[2] = "--stats";
            call[3] = "--sample";
            call[4] = sample;
            System.arraycopy(rest, 0, call, 5, rest.length);
            String[] withLibrary = new String[call.length + 1];
            System.arraycopy(call, 0, withLibrary, 0, call.length);
            withLibrary[call.length] = library.toString();
            long started = System.currentTimeMillis();
            org.key_project.tptp2key.Tptp2Key.main(withLibrary);
            System.out.printf("%-14s %5.1f s%n", "took", (System.currentTimeMillis() - started) / 1000.0);
        }
    }
}
