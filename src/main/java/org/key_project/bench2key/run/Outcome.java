package org.key_project.bench2key.run;

/** What became of one attempted translation. */
public enum Outcome {
    /** A .key file was written. */
    OK,
    /** The input language's front end rejected the input. */
    REJECTED,
    /** The input uses something the translation does not cover. */
    UNSUPPORTED,
    /** Filtered out by the declared status. */
    SKIPPED,
    IO_ERROR,
    CRASH
}
