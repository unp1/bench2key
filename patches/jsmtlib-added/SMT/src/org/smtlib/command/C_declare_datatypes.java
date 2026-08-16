/*
 * Added for the KeY translation: SMT-LIB 2.6 algebraic datatypes.
 */
package org.smtlib.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.smtlib.ICommand_datatypes;
import org.smtlib.IExpr.INumeral;
import org.smtlib.IExpr.ISymbol;
import org.smtlib.IParser.ParserException;
import org.smtlib.IResponse;
import org.smtlib.ISolver;
import org.smtlib.ISort;
import org.smtlib.IVisitor;
import org.smtlib.impl.Command;
import org.smtlib.sexpr.Parser;
import org.smtlib.sexpr.Printer;

/**
 * Implements {@code declare-datatypes}.
 *
 * <pre>
 * (declare-datatypes ((name arity)...) ((constructor (selector sort)...)...)...)
 * </pre>
 *
 * Parametric datatypes, written with {@code par}, are not covered. They do not occur in the
 * datatype divisions of the SMT-LIB benchmark library, so nothing is lost by leaving them out until
 * something needs them; a declaration that uses one is reported rather than quietly mistranslated.
 */
public class C_declare_datatypes extends Command implements ICommand_datatypes {

    public static final String commandName = "declare-datatypes";

    public static class Selector implements ISelector {
        private final ISymbol name;
        private final ISort sort;

        public Selector(ISymbol name, ISort sort) {
            this.name = name;
            this.sort = sort;
        }

        @Override
        public ISymbol name() {
            return name;
        }

        @Override
        public ISort sort() {
            return sort;
        }
    }

    public static class Constructor implements IConstructor {
        private final ISymbol name;
        private final List<ISelector> selectors;

        public Constructor(ISymbol name, List<ISelector> selectors) {
            this.name = name;
            this.selectors = selectors;
        }

        @Override
        public ISymbol name() {
            return name;
        }

        @Override
        public List<ISelector> selectors() {
            return selectors;
        }
    }

    public static class Datatype implements IDatatype {
        private final ISymbol name;
        private final List<IConstructor> constructors;

        public Datatype(ISymbol name, List<IConstructor> constructors) {
            this.name = name;
            this.constructors = constructors;
        }

        @Override
        public ISymbol name() {
            return name;
        }

        @Override
        public List<IConstructor> constructors() {
            return constructors;
        }
    }

    protected List<IDatatype> datatypes;

    public C_declare_datatypes(List<IDatatype> datatypes) {
        this.datatypes = datatypes;
    }

    @Override
    public List<IDatatype> datatypes() {
        return datatypes;
    }

    @Override
    public String commandName() {
        return commandName;
    }

    public void write(Printer p) throws IOException, IVisitor.VisitorException {
        p.writer().append("(" + commandName + " ...)");
    }

    /**
     * Parses the sort declarations and then the bodies, which SMT-LIB gives as two lists that
     * correspond by position.
     */
    static public /*@Nullable*/ C_declare_datatypes parse(Parser p)
            throws IOException, ParserException {
        if (p.parseLP() == null) {
            return null;
        }
        List<ISymbol> names = new ArrayList<>();
        while (!p.isRP()) {
            if (p.parseLP() == null) {
                return null;
            }
            ISymbol name = p.parseSymbol();
            if (name == null) {
                return null;
            }
            INumeral arity = p.parseNumeral();
            if (arity == null) {
                return null;
            }
            if (arity.value().signum() != 0) {
                error(p.smt(), "Parametric datatypes are not supported: " + name.value(), name.pos());
                return null;
            }
            if (p.parseRP() == null) {
                return null;
            }
            names.add(name);
        }
        if (p.parseRP() == null) {
            return null;
        }

        List<IDatatype> datatypes = new ArrayList<>();
        if (p.parseLP() == null) {
            return null;
        }
        int i = 0;
        while (!p.isRP()) {
            if (i >= names.size()) {
                error(p.smt(), "More datatype bodies than names", null);
                return null;
            }
            List<IConstructor> constructors = parseBody(p);
            if (constructors == null) {
                return null;
            }
            datatypes.add(new Datatype(names.get(i++), constructors));
        }
        if (p.parseRP() == null) {
            return null;
        }
        if (i != names.size()) {
            error(p.smt(), "Fewer datatype bodies than names", null);
            return null;
        }
        return new C_declare_datatypes(datatypes);
    }

    /** Parses one datatype body: a parenthesised list of constructors. */
    static /*@Nullable*/ List<IConstructor> parseBody(Parser p) throws IOException, ParserException {
        if (p.parseLP() == null) {
            return null;
        }
        // A body may be wrapped in `par`, which this does not cover.
        List<IConstructor> constructors = new LinkedList<>();
        while (!p.isRP()) {
            if (p.parseLP() == null) {
                return null;
            }
            ISymbol name = p.parseSymbol();
            if (name == null) {
                return null;
            }
            if ("par".equals(name.value())) {
                error(p.smt(), "Parametric datatypes are not supported", name.pos());
                return null;
            }
            List<ISelector> selectors = new LinkedList<>();
            while (!p.isRP()) {
                if (p.parseLP() == null) {
                    return null;
                }
                ISymbol selector = p.parseSymbol();
                if (selector == null) {
                    return null;
                }
                ISort sort = p.parseSort(null);
                if (sort == null) {
                    return null;
                }
                if (p.parseRP() == null) {
                    return null;
                }
                selectors.add(new Selector(selector, sort));
            }
            if (p.parseRP() == null) {
                return null;
            }
            constructors.add(new Constructor(name, selectors));
        }
        if (p.parseRP() == null) {
            return null;
        }
        return constructors;
    }

    @Override
    public IResponse execute(ISolver solver) {
        return solver.declare_datatypes(this);
    }

    @Override
    public <T> T accept(IVisitor<T> v) throws IVisitor.VisitorException {
        return v.visit(this);
    }
}
