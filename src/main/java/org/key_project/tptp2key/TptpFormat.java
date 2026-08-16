package org.key_project.tptp2key;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.key_project.bench2key.run.Format;
import org.key_project.bench2key.run.Outcome;
import org.key_project.bench2key.run.Problem;
import org.key_project.bench2key.run.Result;

/** TPTP as an input language: problems grouped by the domain their name begins with. */
public final class TptpFormat implements Format<TptpOptions> {

    private static final Pattern STATUS =
        Pattern.compile("^%\\s*Status\\s*:\\s*(\\w+)", Pattern.MULTILINE);

    /** The domain is the leading letters of a TPTP name, as in PUZ001+1 or SET014-3. */
    private static final Pattern DOMAIN = Pattern.compile("^([A-Z]{3})\\d");

    /**
     * The statuses that leave a proof to find.
     *
     * A Theorem follows from its axioms and an Unsatisfiable clause set refutes itself, which are
     * the two shapes the translation produces. ContradictoryAxioms means the axioms are already
     * inconsistent, so the conjecture follows from them too.
     */
    private static final Set<String> PROVABLE =
        Set.of("Theorem", "Unsatisfiable", "ContradictoryAxioms");

    @Override
    public String name() {
        return "TPTP";
    }

    @Override
    public String sourceLabel() {
        return "TPTP problem directory";
    }

    @Override
    public String categoryLabel() {
        return "Domains";
    }

    @Override
    public String provableNote() {
        return "(only Theorem, Unsatisfiable and ContradictoryAxioms can be proved)";
    }

    @Override
    public boolean accepts(Path file) {
        String name = file.toString();
        return name.endsWith(".p") || name.endsWith(".ax");
    }

    @Override
    public List<String> statuses() {
        return List.of("Theorem", "Unsatisfiable", "ContradictoryAxioms", "CounterSatisfiable",
            "Satisfiable", "Open", "Unknown");
    }

    @Override
    public String targetName(String sourceName) {
        return sourceName.replaceFirst("\\.(p|ax)$", "") + ".key";
    }

    @Override
    public TptpOptions defaultOptions() {
        return TptpOptions.defaults();
    }

    @Override
    public Result translate(Path source, Path target, TptpOptions options) {
        try {
            Path library = options.library() != null ? options.library() : libraryRoot(source);
            String status = status(read(source, 120));
            // Reading and printing both recurse once per level of nesting, so the whole of it runs
            // where there is room for it.
            String text = DeepStack.call("tptp-translate", () -> {
                Collector collector = new Collector(library, options.maxBytes());
                collector.read(source);
                return new Translator(collector)
                    .run(source.getFileName().toString(), status, options.strategy());
            });
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, text);
            return new Result(source, target, Outcome.OK, "");
        } catch (Unsupported e) {
            return new Result(source, target, Outcome.UNSUPPORTED, Result.shorten(e.getMessage()));
        } catch (Parser.SyntaxError e) {
            return new Result(source, target, Outcome.REJECTED, Result.shorten(e.getMessage()));
        } catch (IOException e) {
            return new Result(source, target, Outcome.IO_ERROR, Result.shorten(e.getMessage()));
        } catch (StackOverflowError | OutOfMemoryError e) {
            // Too deeply nested or too large for this run, rather than anything wrong with it.
            return new Result(source, target, Outcome.UNSUPPORTED,
                "out of reach: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            return new Result(source, target, Outcome.CRASH,
                e.getClass().getSimpleName() + ": " + Result.shorten(e.getMessage()));
        }
    }

    @Override
    public Problem read(Path file) {
        long size = 0;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            // A file that cannot be measured still belongs in the table.
        }
        String status = status(read(file, 120));
        String name = file.getFileName().toString();
        Matcher m = DOMAIN.matcher(name);
        String domain = m.find() ? m.group(1)
                : file.getParent() == null ? "?" : file.getParent().getFileName().toString();
        return new Problem(file, domain, status, size, PROVABLE.contains(status));
    }

    /** The declared status, from the TPTP header, or {@code Unknown} if it does not say. */
    static String status(String header) {
        Matcher matcher = STATUS.matcher(header);
        return matcher.find() ? matcher.group(1) : "Unknown";
    }

    /**
     * The first lines of a file. The header states everything wanted here, and problems run to
     * hundreds of megabytes, so reading stops once the header is past.
     */
    private static String read(Path file, int lines) {
        StringBuilder head = new StringBuilder();
        try (BufferedReader in = Files.newBufferedReader(file)) {
            String line;
            for (int i = 0; i < lines && (line = in.readLine()) != null; i++) {
                head.append(line).append('\n');
            }
        } catch (IOException e) {
            // An unreadable file has no header to report.
        }
        return head.toString();
    }

    /**
     * The library root a problem sits in, recognised by the {@code Axioms} directory beside the
     * problem collection. Includes name their file relative to that root.
     */
    static Path libraryRoot(Path file) {
        for (Path dir = file.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("Axioms"))) {
                return dir;
            }
        }
        return null;
    }
}
