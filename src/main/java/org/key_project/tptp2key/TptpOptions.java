package org.key_project.tptp2key;

import java.nio.file.Path;

import org.key_project.bench2key.run.StrategyOptions;

/**
 * The choices that decide what a TPTP translation produces.
 *
 * @param library the TPTP root holding {@code Axioms}, against which includes resolve. Nearly every
 *        problem states its theory by reference, so without this there is a conjecture and nothing
 *        to prove it from
 * @param maxBytes refuse a file, or an included file, above this size, or zero for no ceiling
 * @param strategy KeY strategy settings to write into the file, or null to leave KeY its own
 */
public record TptpOptions(Path library, long maxBytes, StrategyOptions strategy) {

    public static TptpOptions defaults() {
        return new TptpOptions(null, 0, null);
    }

    public TptpOptions withLibrary(Path root) {
        return new TptpOptions(root, maxBytes, strategy);
    }

    public TptpOptions withMaxBytes(long bytes) {
        return new TptpOptions(library, bytes, strategy);
    }

    public TptpOptions withStrategy(StrategyOptions s) {
        return new TptpOptions(library, maxBytes, s);
    }
}
