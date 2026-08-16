package org.key_project.smt2key;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps SMT-LIB symbols to legal .key identifiers.
 *
 * A .key identifier matches {@code [A-Za-z_#$][A-Za-z0-9_#$]*}, so most SMT-LIB symbols need
 * rewriting. Every generated name carries a prefix, because redeclaring a name that the standard
 * rule base already uses is a hard error in KeY, and the rule base occupies short names such as
 * {@code add}, {@code select} and {@code min}. Distinct SMT-LIB symbols always receive distinct
 * identifiers, even when they rewrite to the same characters.
 */
public final class Names {

    /** Prefix for function and predicate symbols. */
    public static final String FUN = "smt_";

    /** Prefix for sort names; KeY keeps sorts in their own namespace, but a separate prefix reads better. */
    public static final String SORT = "SMT_";

    private final Map<String, String> funNames = new HashMap<>();
    private final Map<String, String> sortNames = new HashMap<>();
    private final Set<String> used = new HashSet<>();
    private int freshVars = 0;
    private int freshLets = 0;

    /** The identifier for a function or predicate symbol. */
    public String fun(String smtName) {
        return funNames.computeIfAbsent(smtName, n -> unique(FUN + mangle(n)));
    }

    /** The identifier for a sort name. */
    public String sort(String smtName) {
        return sortNames.computeIfAbsent(smtName, n -> unique(SORT + mangle(n)));
    }

    /** A fresh name for a symbol standing for a let-binding. */
    public String letSymbol() {
        return unique(FUN + "let_" + (freshLets++));
    }

    /**
     * A fresh name for a bound variable. Quantifiers are renamed rather than translated verbatim,
     * which keeps SMT-LIB's shadowing from turning into variable capture.
     */
    public String freshVar() {
        return "sv_" + (freshVars++);
    }

    /** Appends a counter for as long as the candidate is taken. */
    private String unique(String candidate) {
        String name = candidate;
        for (int i = 2; !used.add(name); i++) {
            name = candidate + "_" + i;
        }
        return name;
    }

    /**
     * Rewrites every character outside {@code [A-Za-z0-9_]} as {@code _hh}, using the code point in
     * hexadecimal. Quoted SMT-LIB symbols keep their bars out of the result, since the bars are
     * delimiters rather than part of the name.
     */
    static String mangle(String smtName) {
        String name = smtName;
        if (name.length() >= 2 && name.startsWith("|") && name.endsWith("|")) {
            name = name.substring(1, name.length() - 1);
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_').append(Integer.toHexString(c));
            }
        }
        return sb.toString();
    }
}
