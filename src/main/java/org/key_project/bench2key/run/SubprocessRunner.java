package org.key_project.bench2key.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs each proof in a JVM of its own.
 *
 * KeY's console interface keeps every proof of a run in memory and writes its statistics to a file
 * in the working directory, so each attempt gets a directory to itself and the figures are read
 * back from there. The isolation is the point: a proof that exhausts its heap ends its own process
 * and leaves the caller running.
 */
public final class SubprocessRunner implements KeyRunner {

    private static final Pattern ANSI = Pattern.compile("\\[[0-9;]*m");
    private static final Pattern OPEN_GOALS = Pattern.compile("Number of goals remaining open: (\\d+)");

    private final Path keyJar;
    private final String heap;
    private final int threads;
    private final boolean keepProofs;
    private final Path scratch;

    public SubprocessRunner(Path keyJar, String heap) throws IOException {
        this(keyJar, heap, 1, true);
    }

    public SubprocessRunner(Path keyJar, String heap, int threads) throws IOException {
        this(keyJar, heap, threads, true);
    }

    /**
     * @param threads worker threads for KeY's own proof search; 1 leaves it single threaded
     * @param keepProofs whether to keep the saved proof. KeY writes one beside every problem it
     *        finishes and has no way to be told not to, and they run to hundreds of megabytes on a
     *        long search, so a batch that only wants the figures discards them once read.
     */
    public SubprocessRunner(Path keyJar, String heap, int threads, boolean keepProofs)
            throws IOException {
        this.keyJar = keyJar;
        this.heap = heap;
        this.threads = threads;
        this.keepProofs = keepProofs;
        this.scratch = Files.createTempDirectory("smt2key-proofs");
    }

    @Override
    public String describe() {
        return threads > 1
            ? "separate JVM per proof, " + threads + " cores each"
            : "separate JVM per proof, single core";
    }

    @Override
    public ProofOutcome prove(Path keyFile, int timeoutMillis) {
        long started = System.currentTimeMillis();
        Path work;
        try {
            work = Files.createTempDirectory(scratch, "proof");
        } catch (IOException e) {
            return ProofOutcome.error(keyFile, 0, "cannot create a working directory: " + e.getMessage());
        }
        List<String> command = new ArrayList<>(List.of(
            javaBinary(), "-Xmx" + heap,
            // A proof needs no display, and without this each one becomes a windowing application
            // that takes focus while it runs.
            "-Djava.awt.headless=true", "-Dapple.awt.UIElement=true",
            "-jar", keyJar.toString(),
            "--auto", "--timeout", String.valueOf(timeoutMillis)));
        if (threads > 1) {
            command.add("--threads");
            command.add(String.valueOf(threads));
        }
        command.add(keyFile.toAbsolutePath().toString());
        try {
            Process p = new ProcessBuilder(command)
                .directory(work.toFile())
                .redirectErrorStream(true)
                .start();
            String log;
            try (var in = p.getInputStream()) {
                log = ANSI.matcher(new String(in.readAllBytes())).replaceAll("");
            }
            // The proof itself is bounded by KeY's own timeout; this only stops a wedged JVM.
            if (!p.waitFor(timeoutMillis + 120_000L, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return ProofOutcome.error(keyFile, System.currentTimeMillis() - started,
                    "KeY did not finish and was stopped");
            }
            long millis = System.currentTimeMillis() - started;
            return read(keyFile, log, work, millis);
        } catch (IOException e) {
            return ProofOutcome.error(keyFile, System.currentTimeMillis() - started, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProofOutcome.error(keyFile, System.currentTimeMillis() - started, "interrupted");
        }
    }

    private ProofOutcome read(Path keyFile, String log, Path work, long millis) {
        ProofOutcome.Result result = log.contains(" - Proved")
            ? ProofOutcome.Result.PROVED
            : log.contains(" - Not proved") ? ProofOutcome.Result.OPEN : ProofOutcome.Result.ERROR;
        int openGoals = -1;
        Matcher m = OPEN_GOALS.matcher(log);
        while (m.find()) {
            openGoals = Integer.parseInt(m.group(1));
        }
        Map<String, String> statistics = readStatistics(work);
        // The statistics come from the working directory, so the proof itself is only needed if
        // somebody means to open it.
        Path proof = keepProofs ? proofBeside(keyFile) : discardProofs(keyFile);
        return new ProofOutcome(keyFile, result, openGoals, millis, statistics, proof, log);
    }

    /** KeY writes {@code <proof name>.csv} into its working directory when a run finishes. */
    private static Map<String, String> readStatistics(Path work) {
        Map<String, String> statistics = new LinkedHashMap<>();
        try (var files = Files.list(work)) {
            Path csv = files.filter(f -> f.toString().endsWith(".csv")).findFirst().orElse(null);
            if (csv == null) {
                return statistics;
            }
            for (String line : Files.readAllLines(csv)) {
                int semicolon = line.indexOf(';');
                if (semicolon > 0) {
                    statistics.putIfAbsent(line.substring(0, semicolon), line.substring(semicolon + 1));
                }
            }
        } catch (IOException e) {
            // Statistics are a convenience; their absence is not a failure of the proof.
        }
        return statistics;
    }

    /**
     * Removes every proof saved for this problem, returning null so nothing offers to open one.
     *
     * KeY appends a counter rather than overwriting, so a problem proved more than once leaves a
     * file per run. All of them go.
     */
    private static Path discardProofs(Path keyFile) {
        String base = keyFile.getFileName().toString().replaceFirst("\\.key$", "");
        Path parent = keyFile.toAbsolutePath().getParent();
        if (parent == null) {
            return null;
        }
        try (var files = Files.list(parent)) {
            for (Path f : files.filter(f -> {
                String n = f.getFileName().toString();
                return n.startsWith(base) && n.endsWith(".proof");
            }).toList()) {
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            // Leaving the files costs space, not correctness.
        }
        return null;
    }

    /** KeY saves the proof next to the problem, with the suffix replaced. */
    private static Path proofBeside(Path keyFile) {
        String base = keyFile.getFileName().toString().replaceFirst("\\.key$", "");
        Path parent = keyFile.toAbsolutePath().getParent();
        for (String suffix : List.of(".auto.proof", ".proof")) {
            Path candidate = parent.resolve(base + suffix);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        // KeY appends a counter when a proof of that name already exists.
        try (var files = Files.list(parent)) {
            return files.filter(f -> f.getFileName().toString().startsWith(base + ".auto.")
                    && f.toString().endsWith(".proof")).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void openInKeyGui(Path file) throws IOException {
        List<String> command = new ArrayList<>(List.of(
            javaBinary(), "-Xmx" + heap, "-jar", keyJar.toString(), file.toAbsolutePath().toString()));
        new ProcessBuilder(command).directory(scratch.toFile()).start();
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Override
    public void close() {
        // The scratch directory holds the statistics files of finished runs; leaving it to the
        // operating system keeps a crashed run inspectable.
    }
}
