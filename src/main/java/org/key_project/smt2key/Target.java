package org.key_project.smt2key;

import org.smtlib.ISort;

/** The declaration sink and sort mapper that an {@link ArrayEncoding} writes into. */
public interface Target {

    /** The .key sort that represents {@code s} in a term position. */
    String keySort(ISort s);

    /** Requests a {@code \sorts} entry. */
    void needSort(String name);

    /** Requests a {@code \functions} entry, given as the full declaration text without the semicolon. */
    void needFun(String name, String declaration);

    /** Requests a formula in the antecedent of the generated problem. */
    void needAxiom(String formula);

    Names names();
}
