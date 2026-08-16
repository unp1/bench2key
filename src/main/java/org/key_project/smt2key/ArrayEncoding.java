package org.key_project.smt2key;

import java.util.HashSet;
import java.util.Set;

import org.smtlib.ISort;

/**
 * How an SMT-LIB {@code (Array K V)} becomes a .key sort with read and write operations.
 *
 * The three implementations differ in what KeY has to do with the result. {@link Axioms} introduces
 * a fresh sort per index/element pair and states the array axioms in the problem, so KeY reasons
 * about it with nothing but equality and the axioms. {@link Heaps} and {@link Sequences} reuse a
 * theory that KeY already has taclets for, which is stronger where it applies but narrower: both
 * require an integer index, and each carries a caveat documented on the class.
 */
public interface ArrayEncoding {

    /** The .key sort representing this array sort, registering whatever it needs on first use. */
    String sortName(ISort.IApplication arraySort, Target target);

    /** The translation of {@code (select array index)}. */
    String select(ISort.IApplication arraySort, String array, String index, Target target);

    /** The translation of {@code (store array index value)}. */
    String store(ISort.IApplication arraySort, String array, String index, String value, Target target);

    /**
     * The translation of an equality between two arrays, as a formula.
     *
     * SMT-LIB array equality is extensional: two arrays are equal exactly when they agree at every
     * index. Writing it as an equality of whatever term represents the array would say something
     * else, and something stronger, since two representations may agree everywhere the problem can
     * observe and still be distinct terms. Stating it elementwise is what the source means, and it
     * pays off on a disequality: the negation is an existential, which leaves a witness index that
     * the rest of the problem can be instantiated at.
     */
    String equality(ISort.IApplication arraySort, String left, String right, Target target);

    /** A line for the header of the generated file, stating what the reader is looking at. */
    String describe();

    static ArrayEncoding byName(String name, boolean extensional) {
        return switch (name) {
            case "axioms" -> new Axioms(extensional);
            case "heap" -> new Heaps();
            case "seq" -> new Sequences();
            default -> throw new IllegalArgumentException("unknown array encoding: " + name);
        };
    }

    /**
     * A fresh uninterpreted sort per index/element pair, with select and store axiomatised in the
     * problem. Faithful to SMT-LIB's ArraysEx for any index and element sorts, at the price of
     * giving KeY no array-specific rules to work with.
     */
    final class Axioms implements ArrayEncoding {

        private final boolean extensional;
        private final Set<String> declared = new HashSet<>();

        Axioms(boolean extensional) {
            this.extensional = extensional;
        }

        @Override
        public String describe() {
            return "arrays: fresh sort per (index,element) pair, axiomatised in the problem"
                + (extensional ? " (with extensionality)" : " (without extensionality)");
        }

        private String suffix(ISort.IApplication arraySort, Target target) {
            return target.keySort(arraySort.param(0)) + "_" + target.keySort(arraySort.param(1));
        }

        @Override
        public String sortName(ISort.IApplication arraySort, Target target) {
            String suffix = suffix(arraySort, target);
            String sort = Names.SORT + "Arr_" + suffix;
            if (declared.add(sort)) {
                String index = target.keySort(arraySort.param(0));
                String element = target.keySort(arraySort.param(1));
                String sel = Names.FUN + "select_" + suffix;
                String sto = Names.FUN + "store_" + suffix;
                target.needSort(sort);
                target.needFun(sel, element + " " + sel + "(" + sort + ", " + index + ")");
                target.needFun(sto, sort + " " + sto + "(" + sort + ", " + index + ", " + element + ")");

                String a = target.names().freshVar();
                String b = target.names().freshVar();
                String i = target.names().freshVar();
                String j = target.names().freshVar();
                String v = target.names().freshVar();
                target.needAxiom("\\forall " + sort + " " + a + "; (\\forall " + index + " " + i
                    + "; (\\forall " + element + " " + v + "; ("
                    + sel + "(" + sto + "(" + a + ", " + i + ", " + v + "), " + i + ") = " + v + ")))");
                target.needAxiom("\\forall " + sort + " " + a + "; (\\forall " + index + " " + i
                    + "; (\\forall " + index + " " + j + "; (\\forall " + element + " " + v + "; ("
                    + i + " != " + j + " -> "
                    + sel + "(" + sto + "(" + a + ", " + i + ", " + v + "), " + j + ") = "
                    + sel + "(" + a + ", " + j + ")))))");
                if (extensional) {
                    target.needAxiom("\\forall " + sort + " " + a + "; (\\forall " + sort + " " + b + "; (("
                        + "\\forall " + index + " " + i + "; (" + sel + "(" + a + ", " + i + ") = "
                        + sel + "(" + b + ", " + i + "))) -> " + a + " = " + b + "))");
                }
            }
            return sort;
        }

        @Override
        public String equality(ISort.IApplication arraySort, String left, String right, Target target) {
            sortName(arraySort, target);
            String index = target.keySort(arraySort.param(0));
            String i = target.names().freshVar();
            String sel = Names.FUN + "select_" + suffix(arraySort, target);
            return "(\\forall " + index + " " + i + "; (" + sel + "(" + left + ", " + i + ") = "
                + sel + "(" + right + ", " + i + ")))";
        }

        @Override
        public String select(ISort.IApplication arraySort, String array, String index, Target target) {
            sortName(arraySort, target);
            return Names.FUN + "select_" + suffix(arraySort, target) + "(" + array + ", " + index + ")";
        }

        @Override
        public String store(ISort.IApplication arraySort, String array, String index, String value,
                Target target) {
            sortName(arraySort, target);
            return Names.FUN + "store_" + suffix(arraySort, target)
                + "(" + array + ", " + index + ", " + value + ")";
        }
    }

    /**
     * An SMT array becomes a KeY {@code Heap}, with index {@code i} addressing the field
     * {@code arr(i)} of one fixed object. KeY's own read-over-write taclets then apply, and
     * {@code arr} being declared unique supplies the disequality of distinct indices. The index sort
     * must be {@code Int}, since {@code arr} takes an integer. Heaps are not extensional in KeY, so
     * a problem that relies on array extensionality can stay open under this encoding.
     */
    final class Heaps implements ArrayEncoding {

        private static final String OBJ = Names.FUN + "arrObj";
        private boolean objDeclared = false;

        @Override
        public String describe() {
            return "arrays: KeY Heap, index i as field arr(i) of one fixed object"
                + " (no extensionality; integer index only)";
        }

        private void requireIntIndex(ISort.IApplication arraySort, Target target) {
            String index = target.keySort(arraySort.param(0));
            if (!"int".equals(index)) {
                throw new Unsupported("heap array encoding needs an Int index, not " + index);
            }
            if (!objDeclared) {
                target.needFun(OBJ, "java.lang.Object " + OBJ);
                objDeclared = true;
            }
        }

        @Override
        public String sortName(ISort.IApplication arraySort, Target target) {
            requireIntIndex(arraySort, target);
            return "Heap";
        }

        @Override
        public String equality(ISort.IApplication arraySort, String left, String right, Target target) {
            requireIntIndex(arraySort, target);
            String element = target.keySort(arraySort.param(1));
            String i = target.names().freshVar();
            return "(\\forall int " + i + "; (select<[" + element + "]>(" + left + ", " + OBJ
                + ", arr(" + i + ")) = select<[" + element + "]>(" + right + ", " + OBJ
                + ", arr(" + i + "))))";
        }

        @Override
        public String select(ISort.IApplication arraySort, String array, String index, Target target) {
            requireIntIndex(arraySort, target);
            String element = target.keySort(arraySort.param(1));
            return "select<[" + element + "]>(" + array + ", " + OBJ + ", arr(" + index + "))";
        }

        @Override
        public String store(ISort.IApplication arraySort, String array, String index, String value,
                Target target) {
            requireIntIndex(arraySort, target);
            return "store(" + array + ", " + OBJ + ", arr(" + index + "), " + value + ")";
        }
    }

    /**
     * An SMT array becomes a KeY {@code Seq}, with select as {@code seqGet} and store as
     * {@code seqUpd}. KeY's sequence taclets apply, but a {@code Seq} has a finite length while an
     * SMT array is total: {@code seqUpd} outside the range of the sequence is the identity. Sound
     * only for problems whose indices stay within range, which the translation does not check.
     */
    final class Sequences implements ArrayEncoding {

        @Override
        public String describe() {
            return "arrays: KeY Seq, select as seqGet and store as seqUpd"
                + " (UNSOUND unless all indices are in range; integer index only)";
        }

        private void requireIntIndex(ISort.IApplication arraySort, Target target) {
            String index = target.keySort(arraySort.param(0));
            if (!"int".equals(index)) {
                throw new Unsupported("seq array encoding needs an Int index, not " + index);
            }
        }

        @Override
        public String sortName(ISort.IApplication arraySort, Target target) {
            requireIntIndex(arraySort, target);
            return "Seq";
        }

        @Override
        public String equality(ISort.IApplication arraySort, String left, String right, Target target) {
            requireIntIndex(arraySort, target);
            String element = target.keySort(arraySort.param(1));
            String i = target.names().freshVar();
            // A sequence is finite, so equality is the lengths together with the elements, which is
            // the shape KeY's own equalityToSeqGetAndSeqLen produces.
            return "(seqLen(" + left + ") = seqLen(" + right + ") & \\forall int " + i + "; (0 <= "
                + i + " & " + i + " < seqLen(" + left + ") -> seqGet<[" + element + "]>(" + left
                + ", " + i + ") = seqGet<[" + element + "]>(" + right + ", " + i + ")))";
        }

        @Override
        public String select(ISort.IApplication arraySort, String array, String index, Target target) {
            requireIntIndex(arraySort, target);
            String element = target.keySort(arraySort.param(1));
            return "seqGet<[" + element + "]>(" + array + ", " + index + ")";
        }

        @Override
        public String store(ISort.IApplication arraySort, String array, String index, String value,
                Target target) {
            requireIntIndex(arraySort, target);
            return "seqUpd(" + array + ", " + index + ", " + value + ")";
        }
    }
}
