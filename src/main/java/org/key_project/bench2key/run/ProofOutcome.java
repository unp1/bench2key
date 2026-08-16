package org.key_project.bench2key.run;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What came of asking KeY to prove one file.
 *
 * @param keyFile the .key file that was proved
 * @param result whether the proof closed
 * @param openGoals goals left open, zero exactly when the proof closed
 * @param millis wall clock time of the attempt
 * @param statistics KeY's own figures, in the order KeY reports them
 * @param proofFile the saved proof, if one was written
 * @param log what KeY printed, kept for diagnosis
 */
public record ProofOutcome(Path keyFile, Result result, int openGoals, long millis,
        Map<String, String> statistics, Path proofFile, String log) {

    public enum Result {
        /** No goals left: the SMT-LIB problem is unsatisfiable. */
        PROVED,
        /** Goals remain, either because the problem is satisfiable or because KeY did not get there. */
        OPEN,
        /** KeY could not run the problem at all. */
        ERROR
    }

    public static ProofOutcome error(Path keyFile, long millis, String message) {
        return new ProofOutcome(keyFile, Result.ERROR, -1, millis, new LinkedHashMap<>(), null, message);
    }

    public String nodes() {
        return statistics.getOrDefault("Nodes", "");
    }

    public String summary() {
        return switch (result) {
            case PROVED -> "proved";
            case OPEN -> openGoals >= 0 ? "open (" + openGoals + " goals)" : "open";
            case ERROR -> "error";
        };
    }
}
