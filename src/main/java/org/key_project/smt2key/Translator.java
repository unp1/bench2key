package org.key_project.smt2key;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.smtlib.IExpr;
import org.smtlib.ISort;

/**
 * Turns a collected SMT-LIB script into the text of a .key problem file.
 *
 * The direction of the translation follows from what each tool answers. An SMT solver asks whether
 * the assertions are satisfiable together; KeY asks whether a sequent is valid. The two line up on
 * the sequent {@code A1, ..., An ==> false}, which is valid exactly when the conjunction of the
 * assertions is unsatisfiable. So a closed KeY proof corresponds to {@code unsat}, and a benchmark
 * whose declared status is {@code sat} has no closed proof to find.
 *
 * KeY separates formulas from terms, while SMT-LIB has {@code Bool} as an ordinary sort. Bool-valued
 * declared symbols therefore become predicates, and the two directions of the mismatch are bridged
 * where they arise: a formula in a term position becomes {@code \if(F)\then(TRUE)\else(FALSE)}, and
 * a boolean term in a formula position becomes {@code t = TRUE}.
 */
public final class Translator implements Target {

    /** Text together with the side of KeY's formula/term divide it belongs to. */
    private record Nat(String text, boolean isFormula) {}

    /** A bound variable: its name in the output, the .key sort it prints as, and its SMT-LIB sort. */
    private record Var(String name, String keySort, ISort smtSort) {}

    /**
     * An SMT-LIB symbol standing for an expression.
     *
     * A binding used once is kept as its expression together with the scope that expression belongs
     * to, and translated afresh at its single use. A binding used more often gets a symbol of its
     * own, and {@code fixed} is the application of that symbol.
     */
    private record Binding(IExpr expr, Env env, Nat fixed, ISort sort) {

        static Binding inlined(IExpr expr, Env env) {
            return new Binding(expr, env, null, null);
        }
    }

    /** How {@code let} is translated. */
    public enum LetMode {
        /** A symbol per binding used more than once, defined by an equation in the antecedent. */
        SYMBOLS,
        /** Every binding re-translated at each use, which duplicates shared structure. */
        INLINE
    }

    /** The names in scope: quantified variables, and symbols bound by {@code let} or a macro call. */
    private record Env(Map<String, Var> vars, Map<String, Binding> subst) {

        static final Env EMPTY = new Env(Map.of(), Map.of());

        Env withVar(String smtName, Var var) {
            Map<String, Var> next = new HashMap<>(vars);
            next.put(smtName, var);
            return new Env(next, subst);
        }

        Env withSubst(Map<String, Binding> bindings) {
            Map<String, Binding> next = new HashMap<>(subst);
            next.putAll(bindings);
            return new Env(vars, next);
        }
    }

    /**
     * Ceiling on generated characters, as a multiple of the input size.
     *
     * The guard exists to catch output that grows out of proportion to its input, which is what
     * unfolding shared structure does. A large problem legitimately produces a large file, so an
     * absolute ceiling would reject those instead. Ordinary files land within a factor of three.
     */
    private static final int BUDGET_FACTOR = 32;
    private static final long BUDGET_FLOOR = 64L * 1024 * 1024;

    private final Collector collector;
    private final ArrayEncoding arrays;
    private final LetMode letMode;
    private final Names names = new Names();
    private final Map<String, Collector.Fun> funs = new HashMap<>();
    private final ISort boolSort;
    private final ISort intSort;
    private final long budget;

    private final Set<String> sortDecls = new LinkedHashSet<>();
    private final Map<String, String> funDecls = new LinkedHashMap<>();
    private final Map<String, String> predDecls = new LinkedHashMap<>();
    private final List<String> axioms = new ArrayList<>();
    private long spent = 0;

    public Translator(Collector collector, ArrayEncoding arrays, LetMode letMode, long sourceSize) {
        this.collector = collector;
        this.arrays = arrays;
        this.letMode = letMode;
        this.budget = Math.max(BUDGET_FLOOR, BUDGET_FACTOR * sourceSize);
        this.boolSort = collector.smt().sortFactory.Bool();
        this.intSort = collector.smt().sortFactory.createSortExpression(
            collector.smt().exprFactory.symbol("Int"));
        for (Collector.Fun f : collector.declaredFuns()) {
            funs.put(f.name(), f);
        }
    }

    /** The complete text of the .key file for the script that the collector ran. */
    public String run(String sourceName) {
        return run(sourceName, null);
    }

    /**
     * The complete text of the .key file, with KeY strategy settings written in.
     *
     * @param strategy settings to state in the file, or null to leave KeY its own
     */
    public String run(String sourceName, org.key_project.bench2key.run.StrategyOptions strategy) {
        List<String> asserts = new ArrayList<>();
        for (IExpr a : collector.assertions()) {
            String text = tr(a, true, Env.EMPTY);
            account(text);
            asserts.add(text);
        }

        StringBuilder out = new StringBuilder();
        out.append("// Generated by smt2key from ").append(sourceName).append('\n');
        out.append("// SMT-LIB logic ").append(collector.logic())
           .append(", declared status ").append(collector.status()).append('\n');
        out.append("// ").append(arrays.describe()).append('\n');
        out.append("// let: ").append(letMode == LetMode.SYMBOLS
            ? "a symbol per binding used more than once" : "inlined").append('\n');
        out.append("// A closed proof of this file means the SMT-LIB problem is unsat.\n");
        out.append('\n');

        // The settings block has to come before any declaration.
        if (strategy != null) {
            out.append(strategy.toKeyBlock());
        }

        if (!sortDecls.isEmpty()) {
            out.append("\\sorts {\n");
            for (String s : sortDecls) {
                out.append("    ").append(s).append(";\n");
            }
            out.append("}\n\n");
        }
        if (!funDecls.isEmpty()) {
            out.append("\\functions {\n");
            for (String d : funDecls.values()) {
                out.append("    ").append(d).append(";\n");
            }
            out.append("}\n\n");
        }
        if (!predDecls.isEmpty()) {
            out.append("\\predicates {\n");
            for (String d : predDecls.values()) {
                out.append("    ").append(d).append(";\n");
            }
            out.append("}\n\n");
        }

        out.append("\\problem {\n");
        List<String> antecedent = new ArrayList<>(axioms);
        antecedent.addAll(asserts);
        for (int i = 0; i < antecedent.size(); i++) {
            out.append(i == 0 ? "      " : "    , ").append(antecedent.get(i)).append('\n');
        }
        out.append("==>\n    false\n}\n");
        return out.toString();
    }

    // ---------------------------------------------------------------- Target

    @Override
    public Names names() {
        return names;
    }

    @Override
    public void needSort(String name) {
        sortDecls.add(name);
    }

    @Override
    public void needFun(String name, String declaration) {
        funDecls.putIfAbsent(name, declaration);
    }

    @Override
    public void needAxiom(String formula) {
        axioms.add(formula);
        account(formula);
    }

    /**
     * Expands sort abbreviations. The sorts this class constructs for Int carry no definition to
     * expand, and jSMTLIB's own expansion dereferences that definition unconditionally.
     */
    private static ISort expand(ISort s) {
        if (s instanceof ISort.IApplication app && app.definition() == null) {
            return s;
        }
        return s.expand();
    }

    @Override
    public String keySort(ISort s) {
        ISort sort = expand(s);
        if (!(sort instanceof ISort.IApplication app)) {
            throw new Unsupported("sort expression " + sort);
        }
        String family = app.family().headSymbol().value();
        switch (family) {
            case "Bool":
                return "boolean";
            case "Int":
                return "int";
            case "Array":
                return arrays.sortName(app, this);
            case "Real":
            case "BitVec":
            case "RoundingMode":
            case "String":
                throw new Unsupported("sort " + family);
            default:
                if (!app.parameters().isEmpty()) {
                    throw new Unsupported("sort " + family + " applied to " + app.parameters().size()
                        + " arguments");
                }
                String name = names.sort(family);
                needSort(name);
                return name;
        }
    }

    // ------------------------------------------------------------ expressions

    /**
     * The sort of an expression, derived from the declarations and the enclosing scope.
     *
     * The type checker's own map is not usable here: it is a {@code HashMap} keyed by expression,
     * and jSMTLIB compares symbols by name, so all occurrences of a name share one entry. Scripts
     * that reuse a {@code let} variable such as {@code _cse0} at several sorts therefore read back
     * whichever sort was recorded last.
     */
    private ISort sortOf(IExpr e, Env env) {
        if (e instanceof IExpr.IAttributedExpr a) {
            return sortOf(a.expr(), env);
        }
        if (e instanceof IExpr.INumeral) {
            return intSort;
        }
        if (e instanceof IExpr.IForall || e instanceof IExpr.IExists) {
            return boolSort;
        }
        if (e instanceof IExpr.ILet l) {
            Map<String, Binding> bindings = new HashMap<>();
            for (IExpr.IBinding b : l.bindings()) {
                bindings.put(b.parameter().value(), Binding.inlined(b.expr(), env));
            }
            return sortOf(l.expr(), env.withSubst(bindings));
        }
        if (e instanceof IExpr.ISymbol s) {
            String name = s.value();
            Binding bound = env.subst().get(name);
            if (bound != null) {
                return bound.sort() != null ? bound.sort() : sortOf(bound.expr(), bound.env());
            }
            Var var = env.vars().get(name);
            if (var != null) {
                return var.smtSort();
            }
            if ("true".equals(name) || "false".equals(name)) {
                return boolSort;
            }
            Collector.Macro macro = collector.macros().get(name);
            if (macro != null) {
                return macro.resultSort();
            }
            Collector.Fun f = funs.get(name);
            if (f != null) {
                return f.resultSort();
            }
            throw new Unsupported("symbol " + name + " is not declared");
        }
        if (e instanceof IExpr.IFcnExpr f && f.head() instanceof IExpr.ISymbol head) {
            String name = head.value();
            Collector.Macro macro = collector.macros().get(name);
            if (macro != null) {
                return macro.resultSort();
            }
            return switch (name) {
                case "not", "and", "or", "=>", "xor", "=", "distinct",
                     "<", "<=", ">", ">=" -> boolSort;
                case "+", "-", "*", "div", "mod", "abs" -> intSort;
                case "ite" -> sortOf(f.args().get(1), env);
                case "select" -> arraySortOf(f.args().get(0), env).param(1);
                case "store" -> sortOf(f.args().get(0), env);
                default -> {
                    Collector.Fun fn = funs.get(name);
                    if (fn == null) {
                        throw new Unsupported("symbol " + name + " is not declared");
                    }
                    yield fn.resultSort();
                }
            };
        }
        throw new Unsupported("cannot determine the sort of a "
            + e.getClass().getSimpleName());
    }

    private boolean isBool(IExpr e, Env env) {
        return sortOf(e, env).isBool();
    }

    private String tr(IExpr e, boolean wantFormula, Env env) {
        return adapt(trNat(e, wantFormula, env), wantFormula);
    }

    /**
     * Accounts for one finished formula of the output.
     *
     * Counting every intermediate result instead would measure work rather than size: a subterm is
     * part of the text of each of its ancestors, so a deep but perfectly ordinary problem would
     * appear to produce many times what it really does.
     */
    private void account(String text) {
        spent += text.length();
        if (spent > budget) {
            throw new Unsupported("output exceeds " + (budget / (1024 * 1024))
                + " MB, which is " + BUDGET_FACTOR + " times the input");
        }
    }

    /** Crosses KeY's formula/term divide when the caller wants the other side. */
    private String adapt(Nat n, boolean wantFormula) {
        if (n.isFormula() == wantFormula) {
            return n.text();
        }
        return wantFormula
            ? "(" + n.text() + " = TRUE)"
            : "\\if(" + n.text() + ")\\then(TRUE)\\else(FALSE)";
    }

    private Nat trNat(IExpr e, boolean wantFormula, Env env) {
        if (e instanceof IExpr.IAttributedExpr a) {
            // :named and :pattern carry no meaning for the translation.
            return trNat(a.expr(), wantFormula, env);
        }
        if (e instanceof IExpr.INumeral n) {
            return new Nat(n.value().toString(), false);
        }
        if (e instanceof IExpr.ISymbol s) {
            return trSymbol(s, wantFormula, env);
        }
        if (e instanceof IExpr.IFcnExpr f) {
            return trApplication(f, wantFormula, env);
        }
        if (e instanceof IExpr.IForall q) {
            return new Nat(trQuantifier("\\forall", q.parameters(), q.expr(), env), true);
        }
        if (e instanceof IExpr.IExists q) {
            return new Nat(trQuantifier("\\exists", q.parameters(), q.expr(), env), true);
        }
        if (e instanceof IExpr.ILet l) {
            return trNat(l.expr(), wantFormula, env.withSubst(bind(l, env)));
        }
        if (e instanceof IExpr.IDecimal) {
            throw new Unsupported("decimal literal");
        }
        if (e instanceof IExpr.IStringLiteral) {
            throw new Unsupported("string literal");
        }
        if (e instanceof IExpr.IBinaryLiteral || e instanceof IExpr.IHexLiteral) {
            throw new Unsupported("bit-vector literal");
        }
        throw new Unsupported("expression of kind " + e.getClass().getSimpleName());
    }

    /**
     * Decides how each binding of one {@code let} is represented.
     *
     * Bindings of a single {@code let} are simultaneous, so every bound expression is read in the
     * enclosing scope. A binding used more than once is translated once and then either named or
     * repeated, depending on its size, which is what keeps a script written as one deeply shared
     * expression from growing exponentially when it is unfolded. A binding used once is inlined,
     * since naming it would gain nothing. A binding never used is dropped without being translated
     * at all: it may well mention a construct this translation does not cover, and a problem should
     * not fail over a subterm it does not depend on.
     */
    private Map<String, Binding> bind(IExpr.ILet l, Env env) {
        Map<String, Binding> bindings = new HashMap<>();
        for (IExpr.IBinding b : l.bindings()) {
            String name = b.parameter().value();
            int uses = countUses(name, l.expr(), 0);
            if (uses == 0) {
                continue;
            }
            bindings.put(name, uses > 1 && letMode == LetMode.SYMBOLS
                ? share(b.expr(), env)
                : Binding.inlined(b.expr(), env));
        }
        return bindings;
    }

    /**
     * Introduces a symbol for one bound expression and states its defining equation.
     *
     * The expression may mention variables of enclosing quantifiers, so the symbol takes those as
     * parameters. Which ones they are is read off the translated text: every quantified variable is
     * renamed to a name of its own that occurs nowhere else, so a match is an occurrence. Defining
     * a fresh symbol is a conservative extension, hence sound to add to the antecedent.
     */
    private Binding share(IExpr expr, Env env) {
        ISort sort = sortOf(expr, env);
        boolean isFormula = sort.isBool();
        String text = tr(expr, isFormula, env);
        // Naming a short expression costs more than repeating it: the definition is one more
        // quantified formula for the proof search to carry. Repetition cannot run away either,
        // since a repeated expression that grows past the threshold is named at the next binding.
        Nat fixed = text.length() > SHARE_THRESHOLD
            ? nameText(text, sort, isFormula, env)
            : new Nat(text, isFormula);
        return new Binding(expr, env, fixed, sort);
    }

    /**
     * Introduces a symbol for a piece of already translated text and states its defining equation.
     *
     * Used both for a let-binding and wherever the encoding would otherwise emit one argument in
     * several places, as the chainable operators do.
     */
    private Nat nameText(String body, ISort sort, boolean isFormula, Env env) {
        List<Var> params = parametersOf(body, env);

        String name = names.letSymbol();
        StringBuilder signature = new StringBuilder();
        StringBuilder call = new StringBuilder(name);
        if (!params.isEmpty()) {
            signature.append('(');
            call.append('(');
            for (int i = 0; i < params.size(); i++) {
                signature.append(i == 0 ? "" : ", ").append(params.get(i).keySort());
                call.append(i == 0 ? "" : ", ").append(params.get(i).name());
            }
            signature.append(')');
            call.append(')');
        }
        if (isFormula) {
            needPred(name, name + signature);
        } else {
            needFun(name, keySort(sort) + " " + name + signature);
        }

        String definition = isFormula
            ? "((" + call + ") <-> (" + body + "))"
            : "(" + call + " = " + body + ")";
        for (int i = params.size() - 1; i >= 0; i--) {
            definition = "(\\forall " + params.get(i).keySort() + " " + params.get(i).name() + "; "
                + definition + ")";
        }
        needAxiom(definition);
        return new Nat(call.toString(), isFormula);
    }

    /** Text longer than this is named rather than repeated, so that nesting cannot multiply it. */
    private static final int SHARE_THRESHOLD = 200;

    /**
     * Translates each argument once, naming those the caller will emit in more than one place.
     *
     * SMT-LIB's chainable and pairwise operators spread their arguments over several conjuncts.
     * Repeating the text of an argument is harmless once, but these operators nest, and each level
     * would double what the level below produced.
     */
    private List<String> shareArguments(List<IExpr> args, boolean asFormula, Env env, boolean repeated) {
        List<String> out = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            String text = tr(args.get(i), asFormula, env);
            boolean emittedTwice = repeated && args.size() > 2
                && (args.size() > 3 || (i > 0 && i < args.size() - 1));
            if (emittedTwice && text.length() > SHARE_THRESHOLD) {
                text = nameText(text, sortOf(args.get(i), env), asFormula, env).text();
            }
            out.add(text);
        }
        return out;
    }

    /** The quantified variables in scope that the given text mentions, in the order they were bound. */
    private List<Var> parametersOf(String text, Env env) {
        List<Var> used = new ArrayList<>();
        for (Var v : env.vars().values()) {
            if (Pattern.compile("\\b" + Pattern.quote(v.name()) + "\\b").matcher(text).find()) {
                used.add(v);
            }
        }
        used.sort(Comparator.comparingInt(v -> Integer.parseInt(v.name().substring(3))));
        return used;
    }

    /**
     * How often a let-bound name occurs, counted no further than two since that is the whole
     * question. A quantifier or an inner binding of the same name hides the outer one, and the
     * bound expressions of an inner {@code let} still belong to the outer scope.
     */
    private int countUses(String name, IExpr e, int found) {
        if (found > 1) {
            return found;
        }
        if (e instanceof IExpr.ISymbol s) {
            return s.value().equals(name) ? found + 1 : found;
        }
        if (e instanceof IExpr.IAttributedExpr a) {
            return countUses(name, a.expr(), found);
        }
        if (e instanceof IExpr.IFcnExpr f) {
            int n = found;
            for (IExpr arg : f.args()) {
                n = countUses(name, arg, n);
                if (n > 1) {
                    return n;
                }
            }
            return n;
        }
        if (e instanceof IExpr.IForall q) {
            return rebinds(name, q.parameters()) ? found : countUses(name, q.expr(), found);
        }
        if (e instanceof IExpr.IExists q) {
            return rebinds(name, q.parameters()) ? found : countUses(name, q.expr(), found);
        }
        if (e instanceof IExpr.ILet l) {
            int n = found;
            boolean shadowed = false;
            for (IExpr.IBinding b : l.bindings()) {
                n = countUses(name, b.expr(), n);
                shadowed |= b.parameter().value().equals(name);
            }
            return shadowed || n > 1 ? n : countUses(name, l.expr(), n);
        }
        return found;
    }

    private static boolean rebinds(String name, List<IExpr.IDeclaration> params) {
        for (IExpr.IDeclaration d : params) {
            if (d.parameter().value().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private Nat trSymbol(IExpr.ISymbol s, boolean wantFormula, Env env) {
        String name = s.value();
        Binding bound = env.subst().get(name);
        if (bound != null) {
            return bound.fixed() != null ? bound.fixed() : trNat(bound.expr(), wantFormula, bound.env());
        }
        Var var = env.vars().get(name);
        if (var != null) {
            return new Nat(var.name(), false);
        }
        if ("true".equals(name)) {
            return wantFormula ? new Nat("true", true) : new Nat("TRUE", false);
        }
        if ("false".equals(name)) {
            return wantFormula ? new Nat("false", true) : new Nat("FALSE", false);
        }
        Collector.Macro macro = collector.macros().get(name);
        if (macro != null) {
            return trNat(macro.body(), wantFormula, Env.EMPTY);
        }
        return new Nat(declare(name, List.of(), Env.EMPTY), isPredicate(name));
    }

    private boolean isPredicate(String smtName) {
        Collector.Fun f = funs.get(smtName);
        if (f == null) {
            throw new Unsupported("symbol " + smtName + " is not declared");
        }
        return expand(f.resultSort()).isBool();
    }

    /** Registers the declaration of a user symbol and returns its application text. */
    private String declare(String smtName, List<IExpr> args, Env env) {
        Collector.Fun f = funs.get(smtName);
        if (f == null) {
            throw new Unsupported("symbol " + smtName + " is not declared");
        }
        if (f.argSorts().size() != args.size()) {
            throw new Unsupported("symbol " + smtName + " applied to " + args.size()
                + " arguments, declared with " + f.argSorts().size());
        }
        String name = names.fun(smtName);
        StringBuilder signature = new StringBuilder();
        if (!f.argSorts().isEmpty()) {
            signature.append('(');
            for (int i = 0; i < f.argSorts().size(); i++) {
                signature.append(i == 0 ? "" : ", ").append(keySort(f.argSorts().get(i)));
            }
            signature.append(')');
        }
        if (expand(f.resultSort()).isBool()) {
            needPred(name, name + signature);
        } else {
            needFun(name, keySort(f.resultSort()) + " " + name + signature);
        }

        if (args.isEmpty()) {
            return name;
        }
        StringBuilder call = new StringBuilder(name).append('(');
        for (int i = 0; i < args.size(); i++) {
            // KeY's arguments are terms, whatever KeY sort the SMT-LIB sort maps to.
            call.append(i == 0 ? "" : ", ").append(tr(args.get(i), false, env));
        }
        return call.append(')').toString();
    }

    private void needPred(String name, String declaration) {
        predDecls.putIfAbsent(name, declaration);
    }

    private String trQuantifier(String keyword, List<IExpr.IDeclaration> params, IExpr body, Env env) {
        Env inner = env;
        List<String> prefixes = new ArrayList<>();
        for (IExpr.IDeclaration d : params) {
            String sort = keySort(d.sort());
            String fresh = names.freshVar();
            prefixes.add(keyword + " " + sort + " " + fresh + "; ");
            inner = inner.withVar(d.parameter().value(), new Var(fresh, sort, d.sort()));
        }
        StringBuilder sb = new StringBuilder();
        for (String p : prefixes) {
            sb.append('(').append(p);
        }
        sb.append('(').append(tr(body, true, inner)).append(')');
        for (int i = 0; i < prefixes.size(); i++) {
            sb.append(')');
        }
        return sb.toString();
    }

    private Nat trApplication(IExpr.IFcnExpr f, boolean wantFormula, Env env) {
        if (!(f.head() instanceof IExpr.ISymbol head)) {
            throw new Unsupported("indexed or qualified identifier "
                + f.head().getClass().getSimpleName());
        }
        String name = head.value();
        List<IExpr> args = f.args();

        Collector.Macro macro = collector.macros().get(name);
        if (macro != null) {
            if (macro.parameters().size() != args.size()) {
                throw new Unsupported("macro " + name + " applied to the wrong number of arguments");
            }
            Map<String, Binding> subst = new HashMap<>();
            for (int i = 0; i < args.size(); i++) {
                subst.put(macro.parameters().get(i).parameter().value(),
                    Binding.inlined(args.get(i), env));
            }
            return trNat(macro.body(), wantFormula, new Env(Map.of(), subst));
        }

        switch (name) {
            case "not":
                return new Nat("!(" + tr(args.get(0), true, env) + ")", true);
            case "and":
                return new Nat(join(args, " & ", true, env), true);
            case "or":
                return new Nat(join(args, " | ", true, env), true);
            case "=>":
                return new Nat(rightAssoc(args, " -> ", env), true);
            case "xor":
                return new Nat(xor(args, env), true);
            case "=":
                return new Nat(equality(args, env), true);
            case "distinct":
                return new Nat(distinct(args, env), true);
            case "ite":
                return ite(f, args, wantFormula, env);
            case "+":
                return new Nat(join(args, " + ", false, env), false);
            case "*":
                return new Nat(join(args, " * ", false, env), false);
            case "-":
                if (args.size() == 1) {
                    return new Nat("(-(" + tr(args.get(0), false, env) + "))", false);
                }
                return new Nat(join(args, " - ", false, env), false);
            case "div":
                return new Nat(leftAssocCall("div", args, env), false);
            case "mod":
                requireArity(name, args, 2);
                return new Nat("mod(" + tr(args.get(0), false, env) + ", "
                    + tr(args.get(1), false, env) + ")", false);
            case "abs": {
                requireArity(name, args, 1);
                String t = tr(args.get(0), false, env);
                if (t.length() > SHARE_THRESHOLD) {
                    t = nameText(t, intSort, false, env).text();
                }
                return new Nat("\\if(" + t + " >= 0)\\then(" + t + ")\\else((-(" + t + ")))", false);
            }
            case "<=":
            case "<":
            case ">=":
            case ">":
                return new Nat(chain(args, " " + name + " ", env), true);
            case "select":
                requireArity(name, args, 2);
                return new Nat(arrays.select(arraySortOf(args.get(0), env),
                    tr(args.get(0), false, env), tr(args.get(1), false, env), this), false);
            case "store":
                requireArity(name, args, 3);
                return new Nat(arrays.store(arraySortOf(args.get(0), env),
                    tr(args.get(0), false, env), tr(args.get(1), false, env),
                    tr(args.get(2), false, env), this), false);
            case "/":
            case "to_real":
            case "to_int":
            case "is_int":
            case "divisible":
                throw new Unsupported("operator " + name);
            default:
                return new Nat(declare(name, args, env), isPredicate(name));
        }
    }

    private ISort.IApplication arraySortOf(IExpr array, Env env) {
        ISort s = expand(sortOf(array, env));
        if (s instanceof ISort.IApplication app
                && "Array".equals(app.family().headSymbol().value())) {
            return app;
        }
        throw new Unsupported("select or store on a non-array sort: " + s + " ["
            + s.getClass().getSimpleName() + "]");
    }

    private void requireArity(String name, List<IExpr> args, int arity) {
        if (args.size() != arity) {
            throw new Unsupported(name + " applied to " + args.size() + " arguments");
        }
    }

    private String join(List<IExpr> args, String op, boolean formula, Env env) {
        if (args.size() == 1) {
            return tr(args.get(0), formula, env);
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.size(); i++) {
            sb.append(i == 0 ? "" : op).append(tr(args.get(i), formula, env));
        }
        return sb.append(')').toString();
    }

    /** {@code =>} is right-associative, so the nesting has to be built from the back. */
    private String rightAssoc(List<IExpr> args, String op, Env env) {
        String result = tr(args.get(args.size() - 1), true, env);
        for (int i = args.size() - 2; i >= 0; i--) {
            result = "(" + tr(args.get(i), true, env) + op + result + ")";
        }
        return result;
    }

    private String leftAssocCall(String fn, List<IExpr> args, Env env) {
        if (args.size() < 2) {
            throw new Unsupported(fn + " applied to " + args.size() + " arguments");
        }
        String result = tr(args.get(0), false, env);
        for (int i = 1; i < args.size(); i++) {
            result = fn + "(" + result + ", " + tr(args.get(i), false, env) + ")";
        }
        return result;
    }

    /** KeY has no exclusive or, so it is spelled as a chain of negated equivalences. */
    private String xor(List<IExpr> args, Env env) {
        String result = tr(args.get(0), true, env);
        for (int i = 1; i < args.size(); i++) {
            result = "!((" + result + ") <-> (" + tr(args.get(i), true, env) + "))";
        }
        return result;
    }

    /** {@code =} is chainable in SMT-LIB, and relates formulas when its arguments are Bool. */
    private String equality(List<IExpr> args, Env env) {
        boolean bool = isBool(args.get(0), env);
        ISort.IApplication array = arraySortOrNull(args.get(0), env);
        List<String> a = shareArguments(args, bool, env, true);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i + 1 < a.size(); i++) {
            parts.add(equal(a.get(i), a.get(i + 1), bool, array));
        }
        return parts.size() == 1 ? parts.get(0) : "(" + String.join(" & ", parts) + ")";
    }

    /**
     * One equality, written the way the sort it compares means it.
     *
     * Arrays are extensional in SMT-LIB, so their equality is elementwise rather than an equality
     * of the terms that stand for them.
     */
    private String equal(String left, String right, boolean bool, ISort.IApplication array) {
        if (bool) {
            return "((" + left + ") <-> (" + right + "))";
        }
        if (array != null) {
            return arrays.equality(array, left, right, this);
        }
        return "(" + left + " = " + right + ")";
    }

    /** The array sort of an expression, or null when it is not an array. */
    private ISort.IApplication arraySortOrNull(IExpr e, Env env) {
        ISort s = expand(sortOf(e, env));
        return s instanceof ISort.IApplication app
            && "Array".equals(app.family().headSymbol().value()) ? app : null;
    }

    private String distinct(List<IExpr> args, Env env) {
        boolean bool = isBool(args.get(0), env);
        if (bool && args.size() > 2) {
            // Three pairwise distinct values do not fit in Bool.
            return "false";
        }
        ISort.IApplication array = arraySortOrNull(args.get(0), env);
        List<String> a = shareArguments(args, bool, env, true);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            for (int j = i + 1; j < a.size(); j++) {
                parts.add("!" + equal(a.get(i), a.get(j), bool, array));
            }
        }
        if (parts.isEmpty()) {
            return "true";
        }
        return parts.size() == 1 ? parts.get(0) : "(" + String.join(" & ", parts) + ")";
    }

    /** Comparisons are chainable: {@code (< a b c)} means each neighbouring pair is ordered. */
    private String chain(List<IExpr> args, String op, Env env) {
        List<String> a = shareArguments(args, false, env, true);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i + 1 < a.size(); i++) {
            parts.add("(" + a.get(i) + op + a.get(i + 1) + ")");
        }
        if (parts.isEmpty()) {
            throw new Unsupported("comparison with fewer than two arguments");
        }
        return parts.size() == 1 ? parts.get(0) : "(" + String.join(" & ", parts) + ")";
    }

    private Nat ite(IExpr.IFcnExpr f, List<IExpr> args, boolean wantFormula, Env env) {
        requireArity("ite", args, 3);
        boolean branchesAreFormulas = isBool(f, env) && wantFormula;
        String c = tr(args.get(0), true, env);
        String t = tr(args.get(1), branchesAreFormulas, env);
        String e = tr(args.get(2), branchesAreFormulas, env);
        return new Nat("\\if(" + c + ")\\then(" + t + ")\\else(" + e + ")", branchesAreFormulas);
    }

    /** Kept so that a numeral wider than a long still round-trips through the output. */
    static String numeral(BigInteger v) {
        return v.toString();
    }
}
