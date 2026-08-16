package org.key_project.tptp2key;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills in the symbols a problem uses but does not declare.
 *
 * CNF and FOF have no declarations at all: a symbol is a predicate or a functor according to where
 * it stands, and its arity is however many arguments it is given. TPTP requires that to be the same
 * everywhere, so a symbol used two ways is not a problem this translation can make sense of, and
 * saying so beats emitting a file KeY would reject for its own reasons.
 */
public final class Signatures {

    private Signatures() {}

    /** The declared signatures, extended with one for every symbol only used. */
    public static Map<String, Ast.Signature> complete(List<Collector.Input> inputs,
            Map<String, Ast.Signature> declared) {
        Map<String, Ast.Signature> result = new LinkedHashMap<>(declared);
        for (Collector.Input input : inputs) {
            walk(input.formula(), result, declared);
        }
        return result;
    }

    private static void walk(Ast.Expr expr, Map<String, Ast.Signature> result,
            Map<String, Ast.Signature> declared) {
        switch (expr) {
            case Ast.App app -> {
                if (app.kind() == Ast.Kind.PLAIN) {
                    record(app, result, declared);
                }
                app.args().forEach(a -> walk(a, result, declared));
            }
            case Ast.Not not -> walk(not.body(), result, declared);
            case Ast.Binary binary -> {
                walk(binary.left(), result, declared);
                walk(binary.right(), result, declared);
            }
            case Ast.Infix infix -> {
                walk(infix.left(), result, declared);
                walk(infix.right(), result, declared);
            }
            case Ast.Quant quant -> walk(quant.body(), result, declared);
            case Ast.Var v -> { }
            case Ast.Num n -> { }
            case Ast.Distinct d -> { }
        }
    }

    private static void record(Ast.App app, Map<String, Ast.Signature> result,
            Map<String, Ast.Signature> declared) {
        Ast.Signature known = result.get(app.name());
        if (known != null) {
            if (known.arity() != app.args().size()) {
                throw new Unsupported("symbol " + app.name() + " used with " + app.args().size()
                    + " arguments and with " + known.arity());
            }
            // A declaration says what the symbol is; only inferred entries can conflict here.
            if (!declared.containsKey(app.name()) && known.isPredicate() != app.predicatePosition()) {
                throw new Unsupported(
                    "symbol " + app.name() + " used both as a predicate and as a function");
            }
            return;
        }
        Ast.Type result_ = app.predicatePosition() ? Ast.Type.PROPOSITION : Ast.Type.INDIVIDUAL;
        result.put(app.name(),
            new Ast.Signature(java.util.Collections.nCopies(app.args().size(), Ast.Type.INDIVIDUAL),
                result_));
    }
}
