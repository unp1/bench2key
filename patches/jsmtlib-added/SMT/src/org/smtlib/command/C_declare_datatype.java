/*
 * Added for the KeY translation: SMT-LIB 2.6 algebraic datatypes.
 */
package org.smtlib.command;

import java.io.IOException;
import java.util.List;

import org.smtlib.ICommand_datatypes;
import org.smtlib.IExpr.ISymbol;
import org.smtlib.IParser.ParserException;
import org.smtlib.sexpr.Parser;

/**
 * Implements {@code declare-datatype}, the single-datatype form.
 *
 * <pre>
 * (declare-datatype name ((constructor (selector sort)...)...))
 * </pre>
 */
public class C_declare_datatype extends C_declare_datatypes {

    public static final String commandName = "declare-datatype";

    public C_declare_datatype(List<IDatatype> datatypes) {
        super(datatypes);
    }

    @Override
    public String commandName() {
        return commandName;
    }

    static public /*@Nullable*/ C_declare_datatype parse(Parser p)
            throws IOException, ParserException {
        ISymbol name = p.parseSymbol();
        if (name == null) {
            return null;
        }
        List<IConstructor> constructors = parseBody(p);
        if (constructors == null) {
            return null;
        }
        return new C_declare_datatype(List.of(new Datatype(name, constructors)));
    }
}
