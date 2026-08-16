package org.key_project.bench2key.run;

import java.nio.file.Path;
import java.util.List;

/**
 * An input language, and everything about a problem collection that depends on which one it is.
 *
 * The work either side of the translation is the same whatever the input: walk a directory, group
 * what is found, filter it by the status the problem declares, write .key files, prove them with
 * KeY and report. What differs is how a problem names itself. SMT-LIB states a logic and a status
 * in {@code set-logic} and {@code set-info}; TPTP states a status in a header comment and takes its
 * grouping from the domain a problem belongs to. Everything language-specific is reached through
 * this interface, so the corpus scanner, the runners and the window are written once.
 *
 * @param <O> the choices this language's translation offers, as a type of its own rather than a
 *        bag of strings, so that the two languages cannot be handed each other's settings
 */
public interface Format<O> {

    /** The language, as the tab is labelled: {@code SMT-LIB} or {@code TPTP}. */
    String name();

    /** What the source directory field is called, e.g. {@code SMT-LIB directory}. */
    String sourceLabel();

    /** What problems are grouped by, as the list is titled: {@code Logics} or {@code Domains}. */
    String categoryLabel();

    /** A note next to the status filter saying which declared statuses can be proved at all. */
    String provableNote();

    /** Whether a file of the collection is one of this language's. */
    boolean accepts(Path file);

    /**
     * Reads a problem's header: its category, its declared status, and whether that status leaves
     * a proof to find. Only the header, since a collection holds files of tens of megabytes.
     */
    Problem read(Path file);

    /** The declared statuses worth filtering on, most useful first. */
    List<String> statuses();

    /** The name a translation is written under, given the name of the source. */
    String targetName(String sourceName);

    /** The translation settings to start from. */
    O defaultOptions();

    /** Translates one problem, writing the .key file, and says how it went. */
    Result translate(Path source, Path target, O options);
}
