package org.key_project.smt2key;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.key_project.bench2key.run.KeyRunner;
import org.key_project.bench2key.run.Outcome;
import org.key_project.bench2key.run.Result;
import org.key_project.smt2key.run.Options;
import org.key_project.bench2key.run.ProofOutcome;
import org.key_project.bench2key.run.StrategyOptions;
import org.key_project.bench2key.run.SubprocessRunner;
import org.key_project.smt2key.run.Translation;

/** Command line front end: translates SMT-LIB files to .key problems, and optionally proves them. */
public final class Smt2Key {

    private static final String USAGE = """
        smt2key: SMT-LIB 2 to KeY problem files.

        Usage: smt2key [options] <input.smt2 | directory> ...

        Input
          <path>            an .smt2 file, or a directory searched for .smt2 files
          --out PATH        output directory, mirroring the input tree, or a file for one input
                            (default: beside the input, with a .key suffix)
          --status LIST     only handle files declaring one of these statuses, e.g. --status unsat
                            Only unsat problems can be proved: a closed KeY proof means unsat.
          --limit N         stop after N inputs

        Translation
          --arrays MODE     how an SMT-LIB array is encoded: axioms (default), heap or seq
          --no-ext          omit the array extensionality axiom (axioms mode only)
          --let MODE        symbols (default) or inline
          --logics DIR      SMT-LIB logic definitions (default: the ones built into this tool)

        Strategy, written into the generated file and honoured by KeY on load
          --max-steps N       rule applications before the strategy gives up
          --strategy-timeout MS   strategy time limit, -1 for none
          --arith MODE        basic | defops | modelsearch
          --quantifiers MODE  none | nosplit | nosplitprogs | free
          --splitting MODE    normal | off | delayed
          --triggers MODE     best | good | classic   (only used with --quantifiers free)
        Naming any of these writes a settings block; naming none leaves KeY its own.

        Proving
          --prove           run KeY on each translated problem
          --timeout MS      per problem, default 10000
          --key JAR         the KeY jar (default: found beside this tool, or $KEY_JAR)
          --jobs N          proofs to run at once, default 1
          --cores N         worker threads for KeY's parallel prover per proof, default 4;
                            1 runs the single threaded prover
          --in-process      prove inside this JVM instead of starting one per problem. The taclet
                            base is then read once and the JIT stays warm, so timings reflect the
                            proving rather than the start-up; a proof that exhausts memory takes
                            the whole run with it, so this suits measurement, not batch work
          --keep-proofs     keep the .proof KeY saves beside each problem; by default it is read
                            for its statistics and then removed, since a batch fills a disk

        Other
          --gui             open the graphical front end instead
          --stats           print a summary grouped by outcome
          --quiet           do not name each file as it is handled
          -h, --help        this message
        """;

    private record Input(Path root, Path file) {}

    public static void main(String[] args) throws Exception {
        boolean gui = java.util.Arrays.asList(args).contains("--gui");
        if (!gui) {
            // Loading any AWT class turns the process into a windowing application, which on macOS
            // means a dock icon that takes focus from whatever the user is working in. Nothing on
            // the command line path needs a display, so say so before anything can load.
            System.setProperty("java.awt.headless", "true");
        }
        for (String a : args) {
            if (a.equals("--gui")) {
                org.key_project.bench2key.gui.Bench2KeyGui.main(without(args, "--gui"));
                return;
            }
            if (a.equals("-h") || a.equals("--help")) {
                System.out.print(USAGE);
                return;
            }
        }

        Path out = null;
        Options options = Options.defaults();
        Set<String> statusFilter = null;
        int limit = Integer.MAX_VALUE;
        int timeout = 10_000;
        int jobs = 1;
        int cores = Math.min(4, Runtime.getRuntime().availableProcessors());
        boolean prove = false;
        boolean keepProofs = false;
        boolean inProcess = false;
        boolean stats = false;
        boolean quiet = false;
        Path keyJar = null;
        List<Path> inputs = new ArrayList<>();

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--out" -> out = Paths.get(args[++i]);
                    case "--arrays" -> options = options.withArrays(args[++i]);
                    case "--no-ext" -> options = options.withExtensional(false);
                    case "--let" -> options = options.withLets(letMode(args[++i]));
                    case "--logics" -> options = options.withLogics(Paths.get(args[++i]));
                    case "--status" -> statusFilter = Set.of(args[++i].split(","));
                    case "--limit" -> limit = Integer.parseInt(args[++i]);
                    case "--timeout" -> timeout = Integer.parseInt(args[++i]);
                    case "--jobs" -> jobs = Integer.parseInt(args[++i]);
                    case "--cores" -> cores = Integer.parseInt(args[++i]);
                    case "--key" -> keyJar = Paths.get(args[++i]);
                    case "--max-steps" -> pending = strategy().withMaxSteps(Integer.parseInt(args[++i]));
                    case "--strategy-timeout" -> pending = strategy().withTimeout(Integer.parseInt(args[++i]));
                    case "--arith" -> pending = strategy().withNonLinearArithmetic(
                        pick(args[++i], "basic", "NON_LIN_ARITH_NONE", "defops",
                            "NON_LIN_ARITH_DEF_OPS", "modelsearch", "NON_LIN_ARITH_COMPLETION"));
                    case "--quantifiers" -> pending = strategy().withQuantifiers(
                        pick(args[++i], "none", "QUANTIFIERS_NONE", "nosplit",
                            "QUANTIFIERS_NON_SPLITTING", "nosplitprogs",
                            "QUANTIFIERS_NON_SPLITTING_WITH_PROGS", "free",
                            "QUANTIFIERS_INSTANTIATE"));
                    case "--splitting" -> pending = strategy().withSplitting(
                        pick(args[++i], "normal", "SPLITTING_NORMAL", "off", "SPLITTING_OFF",
                            "delayed", "SPLITTING_DELAYED"));
                    case "--triggers" -> pending = strategy().withTriggers(
                        pick(args[++i], "best", "TRIGGERS_BEST", "good", "TRIGGERS_GOOD",
                            "classic", "TRIGGERS_CLASSIC"));
                    case "--prove" -> prove = true;
                    case "--keep-proofs" -> keepProofs = true;
                    case "--in-process" -> inProcess = true;
                    case "--stats" -> stats = true;
                    case "--quiet" -> quiet = true;
                    default -> inputs.add(Paths.get(args[i]));
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("missing argument after " + args[args.length - 1]);
            System.exit(2);
        }
        if (inputs.isEmpty()) {
            System.out.print(USAGE);
            System.exit(2);
        }

        if (pending != null) {
            options = options.withStrategy(pending);
        }

        List<Input> files = collect(inputs, limit);
        boolean batch = files.size() > 1;
        if (out != null && (batch || !out.toString().endsWith(".key"))) {
            Files.createDirectories(out);
        }

        Map<String, Integer> outcomes = new TreeMap<>();
        Map<String, Integer> causes = new TreeMap<>();
        Map<String, String> examples = new LinkedHashMap<>();
        List<Path> translated = new ArrayList<>();

        for (Input in : files) {
            Path target = targetFor(in, out, batch);
            Result r = Translation.translate(in.file(), target, options, statusFilter);
            outcomes.merge(r.outcome().name(), 1, Integer::sum);
            if (r.ok()) {
                translated.add(target);
                if (!quiet) {
                    System.out.println(in.file() + "  ->  " + target);
                }
            } else if (r.outcome() != Outcome.SKIPPED) {
                causes.merge(r.detail(), 1, Integer::sum);
                examples.putIfAbsent(r.detail(), in.file().toString());
            }
        }

        if (stats || batch) {
            System.out.printf("%n=== %d input files, %d .key files written%n",
                files.size(), translated.size());
            outcomes.forEach((k, v) -> System.out.printf("%8d  %s%n", v, k));
            if (!causes.isEmpty()) {
                System.out.println("=== causes");
                causes.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue())
                        .reversed())
                    .limit(30)
                    .forEach(e -> {
                        System.out.printf("%8d  %s%n", e.getValue(), e.getKey());
                        System.out.println("             e.g. " + examples.get(e.getKey()));
                    });
            }
        }

        if (prove && !translated.isEmpty()) {
            proveAll(translated, keyJar, timeout, jobs, cores, keepProofs, inProcess);
        }
    }

    private static StrategyOptions pending;

    /** The strategy being assembled, created on the first strategy argument. */
    private static StrategyOptions strategy() {
        if (pending == null) {
            pending = StrategyOptions.defaults();
        }
        return pending;
    }

    /** Maps a short command line word to the KeY constant it stands for. */
    private static String pick(String given, String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i].equals(given)) {
                return pairs[i + 1];
            }
        }
        StringBuilder known = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            known.append(i == 0 ? "" : ", ").append(pairs[i]);
        }
        throw new IllegalArgumentException("unknown value '" + given + "'; expected one of " + known);
    }

    private static void proveAll(List<Path> files, Path keyJar, int timeout, int jobs, int cores,
            boolean keepProofs, boolean inProcess) throws IOException {
        Path jar = keyJar != null ? keyJar : findKeyJar();
        if (jar == null && !inProcess) {
            System.err.println("no KeY jar found; pass --key <jar> or set KEY_JAR");
            System.exit(3);
        }
        System.out.printf("%n=== proving %d problems, %d ms timeout, %d at a time, %d cores each%n",
            files.size(), timeout, jobs, cores);

        // In process, one problem at a time: the point is a warm JVM, and running several would
        // put them back in competition for the same cores.
        try (KeyRunner runner = inProcess
                ? org.key_project.bench2key.run.KeyRunner.inProcess(cores)
                : new SubprocessRunner(jar, "6g", cores, keepProofs);
                ExecutorService pool =
                    Executors.newFixedThreadPool(inProcess ? 1 : Math.max(1, jobs))) {
            List<Future<ProofOutcome>> futures = new ArrayList<>();
            for (Path f : files) {
                Callable<ProofOutcome> task = () -> runner.prove(f, timeout);
                futures.add(pool.submit(task));
            }
            int proved = 0;
            for (Future<ProofOutcome> future : futures) {
                ProofOutcome o;
                try {
                    o = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException e) {
                    System.out.println("  error: " + e.getCause());
                    continue;
                }
                if (o.result() == ProofOutcome.Result.PROVED) {
                    proved++;
                }
                System.out.printf("%-52s %-18s %6.1fs  %s nodes%n",
                    shorten(o.keyFile().getFileName().toString()), o.summary(),
                    o.millis() / 1000.0, o.nodes().isEmpty() ? "?" : o.nodes());
            }
            System.out.printf("=== %d of %d proved%n", proved, files.size());
        }
    }

    /** Looks where the tool is usually built, so that the common case needs no argument. */
    private static Path findKeyJar() throws IOException {
        String fromEnv = System.getenv("KEY_JAR");
        if (fromEnv != null && Files.exists(Paths.get(fromEnv))) {
            return Paths.get(fromEnv);
        }
        for (String candidate : List.of("key/key.ui/build/libs", "../key/key.ui/build/libs")) {
            Path dir = Paths.get(candidate);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    Path jar = s.filter(p -> p.toString().endsWith("exe.jar")).findFirst().orElse(null);
                    if (jar != null) {
                        return jar;
                    }
                }
            }
        }
        return null;
    }

    private static Translator.LetMode letMode(String name) {
        return switch (name) {
            case "symbols" -> Translator.LetMode.SYMBOLS;
            case "inline" -> Translator.LetMode.INLINE;
            default -> throw new IllegalArgumentException("unknown let mode: " + name);
        };
    }

    /** Each input keeps the root it was found under, so batch output can mirror the directory tree. */
    private static List<Input> collect(List<Path> inputs, int limit) throws IOException {
        List<Input> found = new ArrayList<>();
        for (Path input : inputs) {
            final Path in = input;
            if (Files.isDirectory(in)) {
                try (Stream<Path> s = Files.walk(in)) {
                    s.filter(p -> p.toString().endsWith(".smt2")).sorted()
                        .forEach(p -> found.add(new Input(in, p)));
                }
            } else {
                Path parent = in.getParent();
                found.add(new Input(parent == null ? Paths.get(".") : parent, in));
            }
        }
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    private static Path targetFor(Input in, Path out, boolean batch) throws IOException {
        if (out == null) {
            return Paths.get(in.file().toString().replaceFirst("\\.smt2$", "") + ".key");
        }
        if (!batch && !Files.isDirectory(out) && out.toString().endsWith(".key")) {
            return out;
        }
        Path target = out.resolve(
            in.root().relativize(in.file()).toString().replaceFirst("\\.smt2$", "") + ".key");
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        return target;
    }

    private static String[] without(String[] args, String remove) {
        return java.util.Arrays.stream(args).filter(a -> !a.equals(remove)).toArray(String[]::new);
    }

    private static String shorten(String s) {
        return s.length() > 50 ? s.substring(0, 47) + "..." : s;
    }
}
