package org.smtlib;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.smtlib.solvers.Solver_test;

/**
 * Parses and type-checks a corpus of SMT-LIB files through the jSMTLIB API and reports how many are
 * accepted, grouped by failure reason. Lives in package org.smtlib to reach the log's listener list,
 * which is where the lexer reports errors that never surface as command responses.
 *
 * Usage: SmtScan &lt;logicDir&gt; &lt;corpusRoot&gt; [maxFilesPerLogic]
 */
public class SmtScan {

    record Outcome(String file, String kind, String detail, String status) {}

    /** Collects everything the tool logs instead of letting it reach the console. */
    static class Collector implements Log.IListener {
        int errors = 0;
        String first = null;

        void record(String msg) {
            errors++;
            if (first == null) {
                first = msg;
            }
        }

        @Override
        public void logOut(String msg) {}

        @Override
        public void logOut(IResponse result) {
            if (result.isError()) {
                record(String.valueOf(result));
            }
        }

        @Override
        public void logError(String msg) {
            record(msg);
        }

        @Override
        public void logError(IResponse.IError result) {
            record(result.errorMsg());
        }

        @Override
        public void logDiag(String msg) {}

        @Override
        public void indent(String chars) {}
    }

    static final Pattern STATUS = Pattern.compile("\\(\\s*set-info\\s+:status\\s+(\\w+)\\s*\\)");

    public static void main(String[] args) throws Exception {
        String logicPath = args[0];
        Path root = Paths.get(args[1]);
        int cap = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;

        // One directory below the corpus root is one logic; sample up to `cap` files from each.
        List<Path> files = new ArrayList<>();
        try (Stream<Path> logics = Files.list(root)) {
            for (Path logic : logics.sorted().toList()) {
                if (!Files.isDirectory(logic)) {
                    continue;
                }
                try (Stream<Path> s = Files.walk(logic)) {
                    files.addAll(s.filter(p -> p.toString().endsWith(".smt2")).sorted().limit(cap).toList());
                }
            }
        }

        Map<String, Integer> kinds = new TreeMap<>();
        Map<String, Integer> details = new TreeMap<>();
        Map<String, Integer> statuses = new TreeMap<>();
        Map<String, int[]> perLogic = new TreeMap<>(); // logic -> {ok, fail}
        Map<String, List<String>> examples = new TreeMap<>();

        long t0 = System.currentTimeMillis();
        for (Path f : files) {
            Outcome o = scan(f, logicPath);
            String logic = root.relativize(f).getName(0).toString();
            int[] tally = perLogic.computeIfAbsent(logic, k -> new int[2]);
            statuses.merge(o.status(), 1, Integer::sum);
            if (o.kind().equals("OK")) {
                tally[0]++;
            } else {
                tally[1]++;
                details.merge(o.detail(), 1, Integer::sum);
                examples.computeIfAbsent(o.detail(), k -> new ArrayList<>());
                if (examples.get(o.detail()).size() < 2) {
                    examples.get(o.detail()).add(o.file());
                }
            }
            kinds.merge(o.kind(), 1, Integer::sum);
        }
        long ms = System.currentTimeMillis() - t0;

        int ok = kinds.getOrDefault("OK", 0);
        System.out.printf("=== scanned %d files in %.1f s (%.1f ms/file)%n", files.size(), ms / 1000.0,
                ms / (double) Math.max(1, files.size()));
        System.out.printf("=== accepted: %d / %d  (%.1f%%)%n", ok, files.size(), 100.0 * ok / files.size());
        System.out.println("=== outcomes");
        kinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.printf("%8d  %s%n", e.getValue(), e.getKey()));
        System.out.println("=== declared :status over the whole corpus");
        statuses.forEach((k, v) -> System.out.printf("%8d  %s%n", v, k));
        System.out.println("=== per logic (accepted / rejected)");
        perLogic.forEach((k, v) -> System.out.printf("%-12s %6d / %6d%n", k, v[0], v[1]));
        System.out.println("=== distinct failure causes (top 30)");
        details.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(30)
                .forEach(e -> {
                    System.out.printf("%8d  %s%n", e.getValue(), e.getKey());
                    for (String ex : examples.getOrDefault(e.getKey(), List.of())) {
                        System.out.println("             e.g. " + ex);
                    }
                });
    }

    /** Runs one file through parser and type checker; the first logged error decides the outcome. */
    static Outcome scan(Path f, String logicPath) {
        String status = "none";
        SMT smt = new SMT();
        Collector collector = new Collector();
        smt.smtConfig.logicPath = logicPath;
        smt.smtConfig.relax = false;
        smt.smtConfig.log.listeners.clear();
        smt.smtConfig.log.addListener(collector);
        try {
            String text = Files.readString(f);
            Matcher m = STATUS.matcher(text);
            if (m.find()) {
                status = m.group(1);
            }
            // The buffer is sized to the whole input on purpose. jSMTLIB's reader starts at
            // 100000 characters and grows by doubling, and a token that straddles one of those
            // boundaries is cut in two: a symbol at the boundary comes back short by the
            // characters beyond it, and the file then fails on a name that never appears in it.
            ISource source = smt.smtConfig.smtFactory.createSource(
                    new CharSequenceReader(new java.io.StringReader(text), text.length() + 2, 100, 2),
                    null);
            IParser parser = smt.smtConfig.smtFactory.createParser(smt.smtConfig, source);
            ISolver solver = new Solver_test(smt.smtConfig, "");
            solver.start();
            while (!parser.isEOD()) {
                ICommand cmd = parser.parseCommand();
                if (cmd == null) {
                    return outcome(f, "PARSE_ERROR", collector, "parseCommand returned null", status);
                }
                IResponse r = cmd.execute(solver);
                if (r != null && r.isError()) {
                    return outcome(f, "SEMANTIC_ERROR", collector, describe(r), status);
                }
            }
            if (collector.errors > 0) {
                return outcome(f, "LOGGED_ERROR", collector, null, status);
            }
            return new Outcome(f.toString(), "OK", "", status);
        } catch (IParser.ParserException e) {
            return outcome(f, "PARSE_ERROR", collector, String.valueOf(e.getMessage()), status);
        } catch (IOException e) {
            return outcome(f, "IO_ERROR", collector, String.valueOf(e.getMessage()), status);
        } catch (RuntimeException | StackOverflowError e) {
            return outcome(f, "CRASH", collector,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), status);
        }
    }

    /** Prefers the first logged message, which is closer to the root cause than the last response. */
    static Outcome outcome(Path f, String kind, Collector collector, String fallback, String status) {
        String detail = collector.first != null ? collector.first : fallback;
        return new Outcome(f.toString(), kind, normalise(String.valueOf(detail)), status);
    }

    static String describe(IResponse r) {
        return r instanceof IResponse.IError e ? e.errorMsg() : String.valueOf(r);
    }

    /** Collapses whitespace and numbers so that messages aggregate into buckets. */
    static String normalise(String msg) {
        String s = msg.replaceAll("\\s+", " ").trim().replaceAll("\\d+", "N");
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
