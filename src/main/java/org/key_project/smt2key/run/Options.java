package org.key_project.smt2key.run;

import java.nio.file.Path;

import org.key_project.bench2key.run.StrategyOptions;
import org.key_project.smt2key.Translator;

/**
 * The choices that decide what a translation produces.
 *
 * @param arrays how an SMT-LIB array becomes a .key sort: {@code axioms}, {@code heap} or {@code seq}
 * @param extensional whether the array extensionality axiom is stated, {@code axioms} mode only
 * @param lets how {@code let} is translated
 * @param logics directory of SMT-LIB logic definitions, or null to use the ones on the classpath
 * @param strategy KeY strategy settings to write into the file, or null to leave KeY's own
 */
public record Options(String arrays, boolean extensional, Translator.LetMode lets, Path logics,
        StrategyOptions strategy) {

    public static Options defaults() {
        return new Options("axioms", true, Translator.LetMode.SYMBOLS, null, null);
    }

    public Options withArrays(String mode) {
        return new Options(mode, extensional, lets, logics, strategy);
    }

    public Options withExtensional(boolean on) {
        return new Options(arrays, on, lets, logics, strategy);
    }

    public Options withLets(Translator.LetMode mode) {
        return new Options(arrays, extensional, mode, logics, strategy);
    }

    public Options withLogics(Path dir) {
        return new Options(arrays, extensional, lets, dir, strategy);
    }

    public Options withStrategy(StrategyOptions s) {
        return new Options(arrays, extensional, lets, logics, s);
    }
}
