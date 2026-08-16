/*
 * Added for the KeY translation: SMT-LIB 2.6 algebraic datatypes.
 */
package org.smtlib;

import java.util.List;

import org.smtlib.IExpr.ISymbol;

/** The pieces of a {@code declare-datatype} or {@code declare-datatypes} command. */
public interface ICommand_datatypes extends ICommand {

    /** One field of a constructor: its selector and the sort that selector returns. */
    interface ISelector {
        ISymbol name();

        ISort sort();
    }

    /** One constructor, with the fields it carries. */
    interface IConstructor {
        ISymbol name();

        List<ISelector> selectors();
    }

    /** One datatype: the sort it introduces and the constructors that generate it. */
    interface IDatatype {
        ISymbol name();

        List<IConstructor> constructors();
    }

    List<IDatatype> datatypes();
}
