package org.key_project.smt2key;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.smtlib.ICommand;
import org.smtlib.IExpr;
import org.smtlib.IResponse;
import org.smtlib.ISort;
import org.smtlib.SMT;
import org.smtlib.solvers.Solver_test;

/**
 * Runs an SMT-LIB script for its declarations rather than for an answer.
 *
 * The base class already type-checks every assertion and fills {@link #typemap} with the sort of
 * each subexpression, and it already keeps the assertion set stack that {@code push} and {@code pop}
 * operate on. This subclass adds the parts a translation needs: the order in which sorts and symbols
 * were declared, the bodies of {@code define-fun}s, and the point at which {@code check-sat} asks
 * for an answer.
 */
public class Collector extends Solver_test {

    /** A declared function or predicate symbol, in declaration order. */
    public record Fun(String name, List<ISort> argSorts, ISort resultSort) {}

    /** A macro introduced by {@code define-fun}. */
    public record Macro(String name, List<IExpr.IDeclaration> parameters, ISort resultSort, IExpr body) {}

    private final List<String> declaredSorts = new ArrayList<>();
    private final List<Fun> declaredFuns = new ArrayList<>();
    private final Map<String, Macro> macros = new LinkedHashMap<>();
    private String logic = "?";
    private String status = "unknown";
    private int checkSats = 0;

    public Collector(SMT.Configuration smtConfig) {
        super(smtConfig, "");
    }

    public List<String> declaredSorts() {
        return declaredSorts;
    }

    public List<Fun> declaredFuns() {
        return declaredFuns;
    }

    public Map<String, Macro> macros() {
        return macros;
    }

    public Map<IExpr, ISort> typemap() {
        return typemap;
    }

    public String logic() {
        return logic;
    }

    /** The status the script declares through {@code (set-info :status ...)}. */
    public String status() {
        return status;
    }

    public int checkSats() {
        return checkSats;
    }

    /**
     * All assertions currently on the stack, outermost first. Assertions that a {@code pop} has
     * discarded are gone, so the result is the set that the following {@code check-sat} asks about.
     */
    public List<IExpr> assertions() {
        List<IExpr> all = new ArrayList<>();
        for (int i = assertionSetStack.size() - 1; i >= 0; i--) {
            all.addAll(assertionSetStack.get(i));
        }
        return all;
    }

    @Override
    public IResponse set_logic(String logicName, org.smtlib.IPos pos) {
        IResponse r = super.set_logic(logicName, pos);
        if (!r.isError()) {
            logic = logicName;
        }
        return r;
    }

    @Override
    public IResponse set_info(IExpr.IKeyword key, org.smtlib.IAttributeValue value) {
        if (":status".equals(key.value()) && value != null) {
            status = smt().defaultPrinter.toString(value);
        }
        return super.set_info(key, value);
    }

    @Override
    public IResponse declare_sort(ICommand.Ideclare_sort cmd) {
        IResponse r = super.declare_sort(cmd);
        if (!r.isError()) {
            if (cmd.arity().value().signum() != 0) {
                throw new Unsupported("sort constructor of arity " + cmd.arity().value()
                    + ": " + cmd.sortSymbol().value());
            }
            declaredSorts.add(cmd.sortSymbol().value());
        }
        return r;
    }

    @Override
    public IResponse declare_fun(ICommand.Ideclare_fun cmd) {
        IResponse r = super.declare_fun(cmd);
        if (!r.isError()) {
            declaredFuns.add(new Fun(cmd.symbol().value(), cmd.argSorts(), cmd.resultSort()));
        }
        return r;
    }

    @Override
    public IResponse declare_const(ICommand.Ideclare_const cmd) {
        IResponse r = super.declare_const(cmd);
        if (!r.isError()) {
            declaredFuns.add(new Fun(cmd.symbol().value(), List.of(), cmd.resultSort()));
        }
        return r;
    }

    @Override
    public IResponse define_fun(ICommand.Idefine_fun cmd) {
        IResponse r = super.define_fun(cmd);
        if (!r.isError()) {
            macros.put(cmd.symbol().value(),
                new Macro(cmd.symbol().value(), cmd.parameters(), cmd.resultSort(), cmd.expression()));
        }
        return r;
    }

    @Override
    public IResponse define_sort(ICommand.Idefine_sort cmd) {
        // Sort abbreviations need no record of their own: ISort.expand() resolves them on demand.
        return super.define_sort(cmd);
    }

    @Override
    public IResponse check_sat() {
        checkSats++;
        return super.check_sat();
    }
}
