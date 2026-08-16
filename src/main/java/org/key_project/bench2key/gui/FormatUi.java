package org.key_project.bench2key.gui;

import javax.swing.JComponent;

import org.key_project.bench2key.run.Format;
import org.key_project.bench2key.run.StrategyOptions;

/**
 * The widgets belonging to one input language, and the settings they stand for.
 *
 * {@link Format} says everything about a language that the command line also needs.
 * What is left over is the part that only a window has: the controls for the choices that
 * language's translation offers, which differ enough between the two that a shared panel cannot
 * name them. Everything else in the window is the same either way.
 *
 * @param <O> the language's translation settings
 */
public interface FormatUi<O> {

    Format<O> format();

    /** The language's own controls, laid out to sit in the options row. */
    JComponent optionsPanel();

    /** What the controls currently say, with the strategy the caller wants written in. */
    O options(StrategyOptions strategy);

    /**
     * The directory of the collection holding files that problems include rather than problems of
     * their own, so that a scan can leave them out, or null for a language without includes.
     *
     * @param root the collection being scanned, for working out a sensible default
     */
    default java.nio.file.Path axioms(java.nio.file.Path root) {
        return null;
    }

    void restore(Settings settings);

    void save(Settings settings);
}
