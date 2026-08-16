package org.key_project.tptp2key;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which problems of a collection to try, when trying all of them is not the point.
 *
 * A run over the whole library takes hours and answers one question: what fraction translates, to
 * within a fraction of a percent. Everything else it is asked to do, a far smaller set does in
 * under a minute, and does better. Two kinds of smaller set earn their place.
 *
 * A spread takes the same number of problems from every domain. It is what a coverage figure comes
 * from, at a precision of a few percent rather than a fraction of one, and it is what notices a
 * domain that has stopped working. It also reaches every corner of the library at once, which a
 * run in alphabetical order does not: the sample found the {@code SYN} syntax problems in half a
 * minute, where a full run had not reached them in several hours.
 *
 * The extremes take the largest problems there are. Faults of size and depth are not spread evenly
 * through a library, they sit at its edges, and both of the crashes found so far were there: the
 * parser overflowed its stack on {@code HWV055}, and the eight largest problems in the library are
 * all {@code HWV}.
 */
public final class Sample {

    private Sample() {}

    /** The domain a problem belongs to, from the leading letters of its name, as in PUZ001+1. */
    private static final Pattern DOMAIN = Pattern.compile("^([A-Z]{3})\\d");

    /** How many to take per domain, or how many of the largest, unless told otherwise. */
    public static final int SPREAD_PER_DOMAIN = 10;
    public static final int EXTREMES = 12;

    /** Reads a selection as it is written on the command line: {@code spread} or {@code spread:5}. */
    public static List<Path> select(String mode, List<Path> files) throws IOException {
        String name = mode;
        int count = -1;
        int colon = mode.indexOf(':');
        if (colon > 0) {
            name = mode.substring(0, colon);
            count = Integer.parseInt(mode.substring(colon + 1));
        }
        return switch (name) {
            case "all" -> files;
            case "spread" -> spread(files, count < 0 ? SPREAD_PER_DOMAIN : count);
            case "extremes" -> extremes(files, count < 0 ? EXTREMES : count);
            case "smoke" -> smoke(files);
            default -> throw new IllegalArgumentException("unknown sample " + mode
                + "; expected all, smoke, spread[:n] or extremes[:n]");
        };
    }

    /** The first {@code perDomain} problems of every domain, in the order the library lists them. */
    public static List<Path> spread(List<Path> files, int perDomain) {
        Map<String, List<Path>> byDomain = new LinkedHashMap<>();
        for (Path file : files) {
            byDomain.computeIfAbsent(domain(file), d -> new ArrayList<>()).add(file);
        }
        List<Path> chosen = new ArrayList<>();
        for (List<Path> domain : byDomain.values()) {
            chosen.addAll(domain.subList(0, Math.min(perDomain, domain.size())));
        }
        return chosen;
    }

    /** The largest problems, where the faults of size and depth are. */
    public static List<Path> extremes(List<Path> files, int count) throws IOException {
        Map<Path, Long> sizes = new LinkedHashMap<>();
        for (Path file : files) {
            try {
                sizes.put(file, Files.size(file));
            } catch (IOException e) {
                sizes.put(file, 0L);
            }
        }
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparingLong(sizes::get).reversed());
        return sorted.subList(0, Math.min(count, sorted.size()));
    }

    /**
     * One problem of each language, so that a change that breaks a whole language says so at once.
     * The languages are told apart by the character TPTP puts in a problem's name.
     */
    public static List<Path> smoke(List<Path> files) {
        Map<Character, Path> byLanguage = new LinkedHashMap<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            for (char marker : new char[] { '-', '+', '_', '^' }) {
                if (name.indexOf(marker) > 0) {
                    byLanguage.putIfAbsent(marker, file);
                    break;
                }
            }
        }
        return new ArrayList<>(byLanguage.values());
    }

    private static String domain(Path file) {
        Matcher m = DOMAIN.matcher(file.getFileName().toString());
        if (m.find()) {
            return m.group(1);
        }
        return file.getParent() == null ? "?" : file.getParent().getFileName().toString();
    }
}
