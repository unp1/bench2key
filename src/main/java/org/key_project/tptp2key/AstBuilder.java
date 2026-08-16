package org.key_project.tptp2key;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.key_project.tptp2key.parser.TPTPParser.*;

/**
 * Turns a parse tree into {@link Ast}.
 *
 * The grammar reaches an atom through a chain of rules that each have a single child, so most of
 * the work is descending through them. The chain is walked rather than listed: a context with one
 * child that the cases below do not name is passed through, which keeps this class to the rules
 * that carry meaning.
 *
 * Whether an occurrence stands in predicate position is inherited as the descent goes, since the
 * grammar marks it at the rule that introduces a formula or an argument list, not at the symbol.
 */
public final class AstBuilder {

    private AstBuilder() {}

    /** The formula of an annotated CNF, FOF or TFF input. */
    public static Ast.Expr formula(ParseTree ctx) {
        return expr(ctx, true);
    }

    private static Ast.Expr expr(ParseTree node, boolean predicate) {
        // Binary connectives. The associative ones are left recursive in the grammar, so both
        // shapes are three children wide and read the same way.
        if (node instanceof Fof_binary_nonassocContext || node instanceof Tff_binary_nonassocContext
                || node instanceof Fof_or_formulaContext || node instanceof Tff_or_formulaContext
                || node instanceof Fof_and_formulaContext || node instanceof Tff_and_formulaContext
                || node instanceof Cnf_disjunctionContext) {
            if (node.getChildCount() == 3) {
                return new Ast.Binary(node.getChild(1).getText(), expr(node.getChild(0), true),
                    expr(node.getChild(2), true));
            }
        }
        if (node instanceof Fof_unary_formulaContext || node instanceof Tff_prefix_unaryContext) {
            if (node.getChildCount() == 2) {
                String op = node.getChild(0).getText();
                if (!op.equals("~")) {
                    throw new Unsupported("unary connective " + op);
                }
                return new Ast.Not(expr(node.getChild(1), true));
            }
        }
        if (node instanceof Cnf_literalContext && node.getChildCount() >= 2) {
            // ~ atom, or ~ ( atom )
            ParseTree inner = node.getChild(node.getChildCount() == 2 ? 1 : 2);
            return new Ast.Not(expr(inner, true));
        }
        if (node instanceof Fof_infix_unaryContext || node instanceof Tff_infix_unaryContext) {
            return new Ast.Infix("!=", expr(node.getChild(0), false), expr(node.getChild(2), false));
        }
        if (node instanceof Fof_defined_infix_formulaContext || node instanceof Tff_defined_infixContext) {
            return new Ast.Infix("=", expr(node.getChild(0), false), expr(node.getChild(2), false));
        }
        if (node instanceof Fof_quantified_formulaContext || node instanceof Tff_quantified_formulaContext) {
            String quantifier = node.getChild(0).getText();
            if (!quantifier.equals("!") && !quantifier.equals("?")) {
                throw new Unsupported("quantifier " + quantifier);
            }
            List<Ast.TypedVar> vars = new ArrayList<>();
            variables(node.getChild(2), vars);
            return new Ast.Quant(quantifier.equals("!"), vars,
                expr(node.getChild(5), true));
        }

        // Symbols. The kind follows from the rule that produced the name.
        if (node instanceof Fof_plain_termContext || node instanceof Tff_plain_atomicContext) {
            return app(node, Ast.Kind.PLAIN, predicate);
        }
        if (node instanceof Fof_defined_plain_termContext || node instanceof Tff_defined_plainContext) {
            return app(node, Ast.Kind.DEFINED, predicate);
        }
        if (node instanceof Fof_system_termContext || node instanceof Tff_system_atomicContext) {
            return app(node, Ast.Kind.SYSTEM, predicate);
        }
        if (node instanceof VariableContext) {
            return new Ast.Var(node.getText());
        }
        if (node instanceof NumberContext) {
            return number(node.getText());
        }
        if (node instanceof TerminalNode terminal) {
            String text = terminal.getText();
            if (text.startsWith("\"")) {
                return new Ast.Distinct(Names.unquote(text));
            }
            throw new Unsupported("token " + text);
        }

        // Position markers: these rules say which side of KeY's divide their subtree belongs to.
        if (node instanceof Fof_atomic_formulaContext || node instanceof Tff_atomic_formulaContext
                || node instanceof Fof_plain_atomic_formulaContext
                || node instanceof Fof_defined_plain_formulaContext
                || node instanceof Fof_system_atomic_formulaContext) {
            return expr(node.getChild(0), true);
        }
        if (node instanceof Tff_unitary_termContext && node.getChildCount() == 1) {
            return expr(node.getChild(0), false);
        }

        // Parentheses, in the several rules that carry them.
        if (node.getChildCount() == 3 && node.getChild(0).getText().equals("(")
                && node.getChild(2).getText().equals(")")) {
            return expr(node.getChild(1), predicate);
        }

        if (node.getChildCount() == 1) {
            return expr(node.getChild(0), predicate);
        }
        throw new Unsupported(describe(node));
    }

    /** A symbol with its arguments, from a rule of the form {@code name} or {@code name(args)}. */
    private static Ast.Expr app(ParseTree node, Ast.Kind kind, boolean predicate) {
        String name = Names.unquote(node.getChild(0).getText());
        List<Ast.Expr> args = new ArrayList<>();
        if (node.getChildCount() > 1) {
            arguments(node.getChild(2), args);
        }
        return new Ast.App(kind, name, args, predicate);
    }

    /** Flattens the right recursive argument list. Arguments always stand in term position. */
    private static void arguments(ParseTree node, List<Ast.Expr> out) {
        if (node instanceof Fof_argumentsContext || node instanceof Tff_argumentsContext) {
            for (int i = 0; i < node.getChildCount(); i++) {
                ParseTree child = node.getChild(i);
                if (child instanceof TerminalNode) {
                    continue;
                }
                arguments(child, out);
            }
            return;
        }
        if (node instanceof Comma_tff_termContext) {
            arguments(node.getChild(1), out);
            return;
        }
        out.add(expr(node, false));
    }

    /** Flattens a variable list, keeping the type a TFF variable may carry. */
    private static void variables(ParseTree node, List<Ast.TypedVar> out) {
        if (node instanceof Fof_variable_listContext || node instanceof Tff_variable_listContext) {
            for (int i = 0; i < node.getChildCount(); i++) {
                ParseTree child = node.getChild(i);
                if (!(child instanceof TerminalNode)) {
                    variables(child, out);
                }
            }
            return;
        }
        if (node instanceof Tff_variableContext) {
            variables(node.getChild(0), out);
            return;
        }
        if (node instanceof Tff_typed_variableContext) {
            out.add(new Ast.TypedVar(node.getChild(0).getText(), type(node.getChild(2))));
            return;
        }
        if (node instanceof VariableContext) {
            out.add(new Ast.TypedVar(node.getText(), null));
            return;
        }
        throw new Unsupported(describe(node));
    }

    /** The declaration in {@code tff(name,type,symbol: type)}. */
    public static Declaration declaration(Tff_atom_typingContext ctx) {
        ParseTree node = ctx;
        while (node.getChildCount() == 3 && node.getChild(0).getText().equals("(")) {
            node = node.getChild(1);
        }
        String name = Names.unquote(node.getChild(0).getText());
        return new Declaration(name, signature(node.getChild(2)));
    }

    /** A symbol and what it was declared to be. */
    public record Declaration(String name, Ast.Signature signature) {}

    /** Reads a top level type as a signature, flattening {@code (A * B) > C}. */
    private static Ast.Signature signature(ParseTree node) {
        node = through(node);
        if (node instanceof Tff_mapping_typeContext) {
            List<Ast.Type> args = new ArrayList<>();
            product(node.getChild(0), args);
            return new Ast.Signature(args, type(node.getChild(2)));
        }
        if (node instanceof Tf1_quantified_typeContext) {
            throw new Unsupported("polymorphic type");
        }
        return new Ast.Signature(List.of(), type(node));
    }

    /** Flattens the {@code *} separated argument types on the left of an arrow. */
    private static void product(ParseTree node, List<Ast.Type> out) {
        node = through(node);
        if (node instanceof Tff_xprod_typeContext) {
            product(node.getChild(0), out);
            out.add(type(node.getChild(2)));
            return;
        }
        out.add(type(node));
    }

    private static Ast.Type type(ParseTree node) {
        node = through(node);
        if (node instanceof Tff_atomic_typeContext && node.getChildCount() == 4) {
            List<Ast.Type> args = new ArrayList<>();
            typeArguments(node.getChild(2), args);
            return new Ast.Type(Names.unquote(node.getChild(0).getText()), args);
        }
        if (node instanceof Txf_tuple_typeContext) {
            throw new Unsupported("tuple type");
        }
        if (node instanceof VariableContext) {
            throw new Unsupported("type variable");
        }
        if (node.getChildCount() == 1 || node instanceof TerminalNode) {
            return new Ast.Type(Names.unquote(node.getText()), List.of());
        }
        throw new Unsupported(describe(node));
    }

    private static void typeArguments(ParseTree node, List<Ast.Type> out) {
        if (node instanceof Tff_type_argumentsContext) {
            for (int i = 0; i < node.getChildCount(); i++) {
                if (!(node.getChild(i) instanceof TerminalNode)) {
                    typeArguments(node.getChild(i), out);
                }
            }
            return;
        }
        out.add(type(node));
    }

    /** Descends through rules that only pass their single child on, and through parentheses. */
    private static ParseTree through(ParseTree node) {
        while (true) {
            if (node.getChildCount() == 1 && !(node instanceof TerminalNode)
                    && !(node instanceof Tff_atomic_typeContext)) {
                node = node.getChild(0);
            } else if (node.getChildCount() == 3 && node.getChild(0).getText().equals("(")
                    && node.getChild(2).getText().equals(")")) {
                node = node.getChild(1);
            } else {
                return node;
            }
        }
    }

    private static Ast.Num number(String text) {
        if (text.indexOf('/') >= 0) {
            return new Ast.Num(Ast.NumKind.RATIONAL, text);
        }
        if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
            return new Ast.Num(Ast.NumKind.REAL, text);
        }
        return new Ast.Num(Ast.NumKind.INTEGER, text);
    }

    private static String describe(ParseTree node) {
        String rule = node.getClass().getSimpleName().replace("Context", "").toLowerCase();
        String text = node.getText();
        return rule + " " + (text.length() > 60 ? text.substring(0, 60) + "..." : text);
    }
}
