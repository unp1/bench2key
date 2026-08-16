package org.key_project.tptp2key;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps TPTP symbols to legal .key identifiers.
 *
 * A .key identifier matches {@code [A-Za-z_#$][A-Za-z0-9_#$]*}, and TPTP symbols can be single
 * quoted, which lets them hold any printable character. Every generated name carries a prefix,
 * because redeclaring a name that KeY's standard rule base already uses is a hard error, and that
 * rule base occupies short names such as {@code add}, {@code select} and {@code min}. Distinct TPTP
 * symbols always receive distinct identifiers, even when they rewrite to the same characters.
 */
public final class Names {

    /** Prefix for function and predicate symbols. */
    public static final String FUN = "tptp_";

    /** Prefix for sorts. */
    public static final String SORT = "TPTP_";

    private final Map<String, String> funNames = new HashMap<>();
    private final Map<String, String> sortNames = new HashMap<>();
    private final Map<String, String> distinctNames = new HashMap<>();
    private final Set<String> used = new HashSet<>();
    private int freshVars = 0;

    /** The identifier for a function or predicate symbol. */
    public String fun(String tptpName) {
        return funNames.computeIfAbsent(tptpName, n -> unique(FUN + mangle(n)));
    }

    /** The identifier for a sort. */
    public String sort(String tptpName) {
        return sortNames.computeIfAbsent(tptpName, n -> unique(SORT + mangle(n)));
    }

    /** The identifier for a distinct object, kept apart so that its own prefix stays readable. */
    public String distinct(String text) {
        return distinctNames.computeIfAbsent(text, t -> unique(FUN + "do_" + mangle(t)));
    }

    /**
     * A fresh name for a bound variable. Quantifiers are renamed rather than translated verbatim,
     * so that shadowing in the input cannot turn into variable capture in the output.
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
     * hexadecimal.
     */
    static String mangle(String tptpName) {
        StringBuilder sb = new StringBuilder(tptpName.length());
        for (int i = 0; i < tptpName.length(); i++) {
            char c = tptpName.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_').append(Integer.toHexString(c));
            }
        }
        return sb.toString();
    }

    /**
     * Strips the quotes of a single quoted symbol or a distinct object, and undoes the two escapes
     * TPTP allows inside them. The quotes delimit the name and are not part of it, so
     * {@code 'a b'} and a hypothetical bare {@code a b} would be the same symbol.
     */
    public static String unquote(String text) {
        if (text.length() < 2) {
            return text;
        }
        char quote = text.charAt(0);
        if ((quote != '\'' && quote != '"') || text.charAt(text.length() - 1) != quote) {
            return text;
        }
        String body = text.substring(1, text.length() - 1);
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(i + 1);
                if (next == quote || next == '\\') {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
