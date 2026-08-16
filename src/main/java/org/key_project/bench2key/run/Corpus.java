package org.key_project.bench2key.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** A directory of problems in one input language, indexed by the category each one belongs to. */
public final class Corpus {

    private final Path root;
    private final List<Problem> problems;
    private final Format<?> format;

    private Corpus(Path root, List<Problem> problems, Format<?> format) {
        this.root = root;
        this.problems = problems;
        this.format = format;
    }

    public Path root() {
        return root;
    }

    public List<Problem> problems() {
        return problems;
    }

    public Format<?> format() {
        return format;
    }

    /** The problems of one category, in the order they were found. */
    public List<Problem> byCategory(String category) {
        return problems.stream().filter(p -> p.category().equals(category)).toList();
    }

    /** Every category present, with how many problems each has. */
    public Map<String, Integer> categories() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Problem p : problems) {
            counts.merge(p.category(), 1, Integer::sum);
        }
        return counts;
    }

    /** A summary of statuses, which says how many problems KeY could close in principle. */
    public Map<String, Integer> statuses() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Problem p : problems) {
            counts.merge(p.status(), 1, Integer::sum);
        }
        return counts;
    }

    /** Told how far a scan has got, so that a caller can show progress. */
    @FunctionalInterface
    public interface Progress {
        void at(int done, int total);
    }

    /**
     * Walks a directory for the language's files and reads the header of each.
     *
     * @param progress called as files are read, or null
     */
    public static Corpus scan(Path root, Format<?> format, Progress progress) throws IOException {
        return scan(root, format, null, progress);
    }

    /**
     * @param axioms a directory of the collection holding files that problems include rather than
     *        problems of their own, left out of the scan, or null if there is no such place
     */
    public static Corpus scan(Path root, Format<?> format, Path axioms, Progress progress)
            throws IOException {
        List<Path> files = new ArrayList<>();
        Path excluded = axioms == null ? null : axioms.toAbsolutePath().normalize();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                .filter(f -> excluded == null || !f.toAbsolutePath().normalize().startsWith(excluded))
                .filter(format::accepts).sorted().forEach(files::add);
        }
        List<Problem> found = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            found.add(format.read(files.get(i)));
            if (progress != null && (i % 200 == 0 || i == files.size() - 1)) {
                progress.at(i + 1, files.size());
            }
        }
        return new Corpus(root, found, format);
    }

    /** Where the translation of a problem goes, mirroring the directory structure below the root. */
    public Path targetFor(Problem p, Path outputRoot) {
        Path relative = root.relativize(p.source());
        Path parent = relative.getParent();
        String name = format.targetName(relative.getFileName().toString());
        // The output always goes to real files, while the source may sit inside an archive, and a
        // path of one file system cannot be resolved against another. The names carry over; the
        // paths do not.
        Path directory = outputRoot;
        if (parent != null) {
            for (Path step : parent) {
                directory = directory.resolve(step.toString());
            }
        }
        return directory.resolve(name);
    }
}
