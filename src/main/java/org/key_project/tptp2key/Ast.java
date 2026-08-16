package org.key_project.tptp2key;

import java.util.List;

/**
 * The shape of a TPTP formula, kept apart from the parse tree the grammar produces.
 *
 * TPTP does not separate terms from formulas the way KeY does. In FOF the two are told apart by
 * position, and in TFF an argument is parsed by the same rules as a formula, so what a symbol
 * stands for follows from its declaration rather than from where it stands. One expression type
 * therefore covers both sides, and {@link Translator} decides which side each occurrence belongs
 * to once the signature is known.
 */
public final class Ast {

    private Ast() {}

    public sealed interface Expr
            permits Var, App, Num, Distinct, Not, Binary, Quant, Infix {}

    /** A variable. TPTP writes these with an upper case initial. */
    public record Var(String name) implements Expr {}

    /** Which namespace a symbol comes from. */
    public enum Kind {
        /** A symbol the problem introduces, such as {@code f} or {@code 'my symbol'}. */
        PLAIN,
        /** A symbol TPTP defines, written with one dollar, such as {@code $sum} or {@code $true}. */
        DEFINED,
        /** A symbol a particular tool defines, written with two dollars. */
        SYSTEM
    }

    /**
     * A symbol applied to arguments; a constant or proposition when {@code args} is empty.
     *
     * {@code predicatePosition} records where the occurrence stood. For FOF and CNF the position
     * settles whether the symbol is a predicate or a functor, since the grammar reaches the two
     * through different rules. For TFF the declaration settles it and the flag is not consulted.
     */
    public record App(Kind kind, String name, List<Expr> args, boolean predicatePosition)
            implements Expr {}

    public enum NumKind { INTEGER, RATIONAL, REAL }

    /** A numeric literal, kept as written so that the translation can reject what it cannot map. */
    public record Num(NumKind kind, String text) implements Expr {}

    /** A distinct object, written in double quotes. Distinct objects differ from each other. */
    public record Distinct(String text) implements Expr {}

    public record Not(Expr body) implements Expr {}

    /** One of {@code & | => <= <=> <~> ~| ~&}. */
    public record Binary(String op, Expr left, Expr right) implements Expr {}

    public record Quant(boolean universal, List<TypedVar> vars, Expr body) implements Expr {}

    /** An equation or disequation: {@code op} is {@code =} or {@code !=}. */
    public record Infix(String op, Expr left, Expr right) implements Expr {}

    /** A quantified variable. {@code type} is null in CNF and FOF, where there are no types. */
    public record TypedVar(String name, Type type) {}

    /** A type name with its arguments; TFF0 types have none. */
    public record Type(String name, List<Type> args) {

        public static final Type INDIVIDUAL = new Type("$i", List.of());
        public static final Type PROPOSITION = new Type("$o", List.of());
        public static final Type INTEGER = new Type("$int", List.of());

        @Override
        public String toString() {
            return args.isEmpty() ? name
                    : name + args.stream().map(Type::toString)
                            .reduce((a, b) -> a + "," + b).map(s -> "(" + s + ")").orElse("");
        }
    }

    /** What a symbol takes and what it gives back. A result of {@code $o} makes it a predicate. */
    public record Signature(List<Type> argTypes, Type result) {

        public boolean isPredicate() {
            return result.equals(Type.PROPOSITION);
        }

        public int arity() {
            return argTypes.size();
        }
    }
}
