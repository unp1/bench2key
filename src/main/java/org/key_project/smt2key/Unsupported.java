package org.key_project.smt2key;

/** Signals an SMT-LIB construct that the translation does not cover. */
public class Unsupported extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Unsupported(String message) {
        super(message);
    }
}
