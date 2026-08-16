package org.key_project.tptp2key;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.key_project.bench2key.run.Sources;
import org.key_project.bench2key.run.StrategyOptions;

/** Command line front end: translates TPTP problems to KeY problem files. */
public final class Tptp2Key {

    private static final String USAGE = """
        tptp2key: TPTP problems to KeY problem files.

        Usage: tptp2key [options] <problem.p | directory> ...

        Input
          <path>            a TPTP file, a directory searched for problems, or a .zip of
                            one. An archive is read where it lies, so the library need not be
                            unpacked, which saves about nine gigabytes. An Axioms directory of
                            the collection is left out: those files are what problems include,
                            not problems of their own.
          --tptp DIR        the TPTP library root, holding Axioms, as a directory or a .zip;
                            includes are resolved against it (default: $TPTP, or a directory
                            above the input that holds Axioms, inside the archive if that is
                            where the input is)
          --out PATH        output directory, mirroring the input tree, or a file for one input
                            (default: beside the input, with a .key suffix)
          --status LIST     only handle problems declaring one of these statuses, e.g.
                            --status Theorem,Unsatisfiable. Only those two can be proved.
          --within PATH     scan only this directory of the source, e.g. --within Problems.
                            Includes still resolve against the whole of it, so an archive of the
                            library can be scanned for its problems without its axiom files being
                            taken for problems themselves.
          --sample MODE     try part of the collection instead of all of it:
                              smoke         one problem of each language
                              spread[:n]    n problems of every domain, 10 by default. Where a
                                            coverage figure comes from, to a few percent, and what
                                            notices a domain that has stopped working
                              extremes[:n]  the n largest problems, 12 by default. Faults of size
                                            and depth sit at a library's edges, not spread through
                                            it, and that is where both crashes so far were found
                              all           everything, which takes hours and buys only precision
          --limit N         stop after N inputs

        Resources
          --stack MB        stack for the parsing thread, default 512. The TPTP rules chain a
                            formula through a dozen of them per level of nesting, so the deeply
                            nested problems need far more than a default thread has.
          --max-size MB     skip a file, or an included file, larger than this. Off by default:
                            nothing is refused for its size. A parse tree costs far more memory
                            than its text and the library holds ontologies of several hundred
                            megabytes, so give a large run a large heap:
                              JAVA_OPTS=-Xmx32g tptp2key ...
                            A ceiling is for surveys that would rather skip those than wait.

        Strategy, written into the generated file and honoured by KeY on load
          --arith MODE      none | defops | completion   (default: leave KeY its own)
          --max-steps N     rule applications before the strategy gives up
          --strategy-timeout MS   strategy time limit, -1 for none
        Naming any of these writes a settings block; naming none leaves KeY its own.

        Other
          --dry-run         translate but write nothing, for surveying a collection
          --stats           print a summary grouped by outcome
          --quiet           do not name each file as it is handled
          -h, --help        this message

        A TPTP problem asks whether its conjecture follows from its axioms, and a KeY sequent asks
        the same, so the axioms become the antecedent and the conjecture the succedent. A CNF
        problem has no conjecture; its clauses become the antecedent and the succedent is false,
        which is valid exactly when the clauses are unsatisfiable.
        """;

    /** The settings to build on, so that naming one option does not discard the others. */
    private static StrategyOptions base(StrategyOptions current) {
        return current == null ? StrategyOptions.defaults() : current;
    }

    private static StrategyOptions withArith(StrategyOptions current, String mode) {
        String value = switch (mode) {
            case "none" -> "NON_LIN_ARITH_NONE";
            case "defops" -> "NON_LIN_ARITH_DEF_OPS";
            case "completion" -> "NON_LIN_ARITH_COMPLETION";
            default -> throw new IllegalArgumentException(
                "unknown arithmetic mode " + mode + "; expected none, defops or completion");
        };
        return base(current).withNonLinearArithmetic(value);
    }

    /** The declared status, as the TPTP header records it. */
    private static final Pattern STATUS =
        Pattern.compile("^% Status\\s*:\\s*(\\w+)", Pattern.MULTILINE);

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("-h")
                || Arrays.asList(args).contains("--help")) {
            System.out.print(USAGE);
            return;
        }

        Path out = null;
        Path tptpRoot = System.getenv("TPTP") == null ? null : Paths.get(System.getenv("TPTP"));
        Set<String> statusFilter = null;
        int limit = Integer.MAX_VALUE;
        boolean stats = false;
        boolean quiet = false;
        boolean dryRun = false;
        String within = null;
        StrategyOptions strategy = null;
        String sample = "all";
        long maxBytes = 0;
        List<Path> inputs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Paths.get(args[++i]);
                case "--tptp" -> tptpRoot = Paths.get(args[++i]);
                case "--status" -> statusFilter = new LinkedHashSet<>(Arrays.asList(args[++i].split(",")));
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                case "--max-size" -> maxBytes = Long.parseLong(args[++i]) * 1024 * 1024;
                case "--stack" -> DeepStack.setBytes(Long.parseLong(args[++i]) * 1024 * 1024);
                case "--within" -> within = args[++i];
                case "--arith" -> strategy = withArith(strategy, args[++i]);
                case "--max-steps" -> strategy = base(strategy).withMaxSteps(Integer.parseInt(args[++i]));
                case "--strategy-timeout" ->
                    strategy = base(strategy).withTimeout(Integer.parseInt(args[++i]));
                case "--sample" -> sample = args[++i];
                case "--dry-run" -> dryRun = true;
                case "--stats" -> stats = true;
                case "--quiet" -> quiet = true;
                default -> {
                    if (args[i].startsWith("--")) {
                        System.err.println("unknown option " + args[i]);
                        System.exit(2);
                    }
                    inputs.add(Paths.get(args[i]));
                }
            }
        }

        // An archive stays open for as long as its paths are read, so the whole run happens inside
        // this block rather than the collecting alone.
        List<Sources> opened = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        if (tptpRoot != null && Sources.isArchive(tptpRoot)) {
            // The library may be an archive of its own, separate from the problems being read.
            Sources library = Sources.open(tptpRoot);
            opened.add(library);
            tptpRoot = library.root();
        }
        for (Path input : inputs) {
            Sources source = Sources.open(input);
            opened.add(source);
            Path root = source.root();
            roots.add(root);
            // Includes resolve against the whole collection even when only part of it is scanned.
            Path scan = within == null ? root : root.resolve(within);
            if (!Files.exists(scan)) {
                System.err.println("no such path: " + scan
                    + (within == null ? "" : " (--within " + within + " of " + input + ")"));
                System.exit(2);
            }
            if (Files.isDirectory(scan)) {
                // Axiom files are what problems include, not problems themselves, so wherever the
                // collection keeps them they are left out of what is scanned.
                Path axioms = root.resolve("Axioms");
                Path excluded = Files.isDirectory(axioms) ? axioms.toAbsolutePath().normalize() : null;
                try (Stream<Path> walk = Files.walk(scan)) {
                    walk.filter(Files::isRegularFile)
                        .filter(f -> excluded == null
                            || !f.toAbsolutePath().normalize().startsWith(excluded))
                        .filter(f -> f.toString().endsWith(".p") || f.toString().endsWith(".ax"))
                        .sorted().forEach(files::add);
                }
            } else {
                files.add(scan);
            }
        }
        files = Sample.select(sample, files);
        if (!"all".equals(sample) && !quiet) {
            System.out.println("sample " + sample + ": " + files.size() + " problems");
        }

        // The option variables are still being assigned above, so the lambda below takes a copy.
        final long sizeLimit = maxBytes;
        final StrategyOptions chosen = strategy;
        TreeMap<String, Integer> outcomes = new TreeMap<>();
        int done = 0;
        try {
            for (Path file : files) {
                if (done >= limit) {
                    break;
                }
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                String status = status(source);
                if (statusFilter != null && (status == null || !statusFilter.contains(status))) {
                    continue;
                }
                done++;
                Path root = tptpRoot != null ? tptpRoot : libraryRoot(file);
                try {
                    // Reading and printing both recurse once per level of nesting, so the whole of it
                    // runs where there is room for it.
                    Path library = root;
                    String text = DeepStack.call("tptp-translate", () -> {
                        Collector collector = new Collector(library, sizeLimit);
                        collector.read(file);
                        return new Translator(collector)
                        .run(file.getFileName().toString(), status, chosen);
                    });
                    Path target = dryRun ? null : target(file, roots, out);
                    if (target != null) {
                        Files.createDirectories(target.getParent());
                        Files.write(target, text.getBytes(StandardCharsets.UTF_8));
                    }
                    outcomes.merge("translated", 1, Integer::sum);
                    if (!quiet) {
                        System.out.println("ok   " + file + (target == null ? "" : " -> " + target));
                    }
                } catch (Unsupported e) {
                    outcomes.merge("unsupported", 1, Integer::sum);
                    if (!quiet) {
                        System.out.println("skip " + file + ": " + e.getMessage());
                    }
                } catch (Parser.SyntaxError e) {
                    outcomes.merge("syntax error", 1, Integer::sum);
                    if (!quiet) {
                        System.out.println("err  " + e.getMessage());
                    }
                } catch (RuntimeException | IOException e) {
                    outcomes.merge("failed", 1, Integer::sum);
                    if (!quiet) {
                        System.out.println("err  " + file + ": " + e);
                    }
                } catch (StackOverflowError | OutOfMemoryError e) {
                    // One problem too deep or too large to handle must not end the batch.
                    outcomes.merge("out of reach", 1, Integer::sum);
                    if (!quiet) {
                        System.out.println("err  " + file + ": " + e.getClass().getSimpleName());
                    }
                }
            }

        } finally {
            for (Sources source : opened) {
                source.close();
            }
        }

        if (stats) {
            System.out.println();
            outcomes.forEach((name, count) -> System.out.printf("%-14s %6d%n", name, count));
            System.out.printf("%-14s %6d%n", "total", done);
        }
    }

    /** The declared status of a problem, from the TPTP header, or null if it does not say. */
    static String status(String source) {
        Matcher matcher = STATUS.matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * The library root a problem sits in, recognised by the {@code Axioms} directory beside the
     * problem collection. Includes name their file relative to that root.
     */
    private static Path libraryRoot(Path file) {
        for (Path dir = file.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("Axioms"))) {
                return dir;
            }
        }
        return null;
    }

    /** Where a problem's translation goes: beside it, or under --out mirroring the input tree. */
    private static Path target(Path file, List<Path> inputs, Path out) {
        String name = file.getFileName().toString().replaceAll("\\.(p|ax)$", "") + ".key";
        if (out == null) {
            return file.toAbsolutePath().getParent().resolve(name);
        }
        if (!Files.isDirectory(out) && out.toString().endsWith(".key")) {
            return out;
        }
        for (Path root : inputs) {
            if (Files.isDirectory(root) && file.toAbsolutePath().startsWith(root.toAbsolutePath())) {
                Path relative = root.toAbsolutePath().relativize(file.toAbsolutePath()).getParent();
                if (relative == null) {
                    return out.resolve(name);
                }
                // The output is always a real file while the source may sit inside an archive, and
                // a path of one file system cannot be resolved against another.
                Path directory = out;
                for (Path step : relative) {
                    directory = directory.resolve(step.toString());
                }
                return directory.resolve(name);
            }
        }
        return out.resolve(name);
    }
}
