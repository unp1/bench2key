package org.key_project.tptp2key;

/** Input the translation does not cover. Carries the construct that stopped it. */
public final class Unsupported extends RuntimeException {

    public Unsupported(String message) {
        super(message);
    }
}
