package org.key_project.tptp2key;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.antlr.v4.runtime.tree.ParseTree;
import org.key_project.tptp2key.parser.TPTPParser;

/**
 * Reads a TPTP problem, its includes and their declarations into one place.
 *
 * A TPTP problem states its axioms by reference: nearly every problem in the library pulls its
 * theory in with {@code include}, so a problem read on its own is a conjecture with nothing to
 * prove it from. Includes are followed here, and a selection list on the include keeps only the
 * named formulas, as TPTP prescribes.
 *
 * Symbols are declared in TFF and only used in CNF and FOF. Both end up in the same table: a
 * declaration is recorded as it is read, and an undeclared symbol is given the signature its uses
 * imply, over the one sort that untyped TPTP has.
 */
public final class Collector {

    /** The TPTP languages, in the order of what they demand of a prover. */
    public enum Language { CNF, FOF, TFF, THF }

    /** One annotated formula, with the role that says where in the sequent it belongs. */
    public record Input(String name, String role, Ast.Expr formula, Language language) {}

    /**
     * Ceiling on the size of a file that will be parsed, or zero for none.
     *
     * There is no size at which a problem stops being worth translating, so nothing is refused for
     * its size unless the caller asks for a ceiling. A parse tree does cost far more memory than
     * the text it came from, and the library holds ontologies of several hundred megabytes, so a
     * run over the whole library is worth giving a large heap; a ceiling is for surveys that would
     * rather skip those than wait for them.
     */
    private final long maxBytes;

    private final Path root;
    private final List<Input> inputs = new ArrayList<>();
    private final Map<String, Ast.Signature> signatures = new LinkedHashMap<>();
    private final Set<String> declaredSorts = new LinkedHashSet<>();
    private final Set<Path> reading = new LinkedHashSet<>();
    private final List<Path> included = new ArrayList<>();
    private Language language = Language.CNF;

    /**
     * @param root the TPTP directory holding {@code Axioms}, against which includes are resolved
     */
    public Collector(Path root) {
        this(root, 0);
    }

    public Collector(Path root, long maxBytes) {
        this.root = root;
        this.maxBytes = maxBytes;
    }

    public List<Input> inputs() {
        return inputs;
    }

    /** Every symbol with its signature, declared or inferred. */
    public Map<String, Ast.Signature> signatures() {
        return signatures;
    }

    /** The sorts a TFF problem declares with {@code $tType}. */
    public Set<String> declaredSorts() {
        return declaredSorts;
    }

    /** The most demanding language any of the read formulas is written in. */
    public Language language() {
        return language;
    }

    /** The files pulled in by {@code include}, in the order they were read. */
    public List<Path> included() {
        return included;
    }

    public void read(Path file) throws IOException {
        if (!reading.add(file.toAbsolutePath().normalize())) {
            return; // A file that includes itself, directly or around a cycle.
        }
        checkSize(file);
        readTree(Parser.parse(file), file, null);
    }

    private void checkSize(Path file) throws IOException {
        if (maxBytes <= 0) {
            return;
        }
        long size = Files.size(file);
        if (size > maxBytes) {
            throw new Unsupported(file.getFileName() + " is " + (size / (1024 * 1024))
                + " MB, above the limit of " + (maxBytes / (1024 * 1024)) + " MB");
        }
    }

    private void readTree(TPTPParser.Tptp_fileContext tree, Path file, Set<String> selection)
            throws IOException {
        for (int i = 0; i < tree.getChildCount(); i++) {
            ParseTree child = tree.getChild(i);
            if (!(child instanceof TPTPParser.Tptp_inputContext input)) {
                continue; // The end of file marker.
            }
            ParseTree item = input.getChild(0);
            if (item instanceof TPTPParser.IncludeContext include) {
                readInclude(include, file);
            } else {
                readFormula(item.getChild(0), selection);
            }
        }
    }

    private void readInclude(TPTPParser.IncludeContext include, Path from) throws IOException {
        String name = Names.unquote(include.getChild(1).getText());
        Set<String> selection = selection(include.getChild(2));
        Path target = resolve(name, from);
        if (target == null) {
            throw new Unsupported("include not found: " + name);
        }
        included.add(target);
        if (!reading.add(target.toAbsolutePath().normalize())) {
            return;
        }
        checkSize(target);
        readTree(Parser.parse(target), target, selection);
    }

    /**
     * The names an include selects, or null for all of them. TPTP writes the selection as a list
     * of formula names in brackets, and a star for everything.
     */
    private Set<String> selection(ParseTree optionals) {
        if (optionals == null || optionals.getChildCount() == 0) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        collectNames(optionals, names);
        return names.isEmpty() ? null : names;
    }

    private void collectNames(ParseTree node, Set<String> out) {
        if (node instanceof TPTPParser.NameContext) {
            out.add(Names.unquote(node.getText()));
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectNames(node.getChild(i), out);
        }
    }

    /**
     * Finds an included file. TPTP names it relative to the library root, and a problem taken out
     * of the library on its own is still worth translating, so the including file's own directory
     * is tried as well.
     */
    private Path resolve(String name, Path from) {
        List<Path> candidates = new ArrayList<>();
        if (root != null) {
            candidates.add(root.resolve(name));
            // An include names its file from the library root, as in Axioms/SET001-0.ax. Someone
            // who points at the axiom directory itself has named the same place, so that reading
            // is tried too rather than refused on a technicality.
            int slash = name.indexOf('/');
            if (slash > 0) {
                candidates.add(root.resolve(name.substring(slash + 1)));
            }
        }
        Path dir = from.toAbsolutePath().getParent();
        if (dir != null) {
            candidates.add(dir.resolve(name));
            for (Path up = dir; up != null; up = up.getParent()) {
                candidates.add(up.resolve(name));
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void readFormula(ParseTree annotated, Set<String> selection) {
        Language lang = switch (annotated) {
            case TPTPParser.Cnf_annotatedContext c -> Language.CNF;
            case TPTPParser.Fof_annotatedContext c -> Language.FOF;
            case TPTPParser.Tff_annotatedContext c -> Language.TFF;
            default -> Language.THF;
        };
        if (lang == Language.THF) {
            throw new Unsupported(annotated.getChild(0).getText().replace("(", "")
                + " is beyond first order logic");
        }
        if (lang.ordinal() > language.ordinal()) {
            language = lang;
        }

        String name = Names.unquote(annotated.getChild(1).getText());
        String role = annotated.getChild(3).getText();
        if (selection != null && !selection.contains(name)) {
            return;
        }
        ParseTree body = annotated.getChild(5);

        if (role.equals("type")) {
            declare(body);
            return;
        }
        inputs.add(new Input(name, role, AstBuilder.formula(body), lang));
    }

    /** Records what a {@code type} input declares: a sort, or a symbol's signature. */
    private void declare(ParseTree body) {
        TPTPParser.Tff_atom_typingContext typing = findTyping(body);
        if (typing == null) {
            throw new Unsupported("type declaration " + body.getText());
        }
        AstBuilder.Declaration declaration = AstBuilder.declaration(typing);
        Ast.Signature signature = declaration.signature();
        if (signature.arity() == 0 && signature.result().name().equals("$tType")) {
            declaredSorts.add(declaration.name());
            return;
        }
        signatures.put(declaration.name(), signature);
    }

    private TPTPParser.Tff_atom_typingContext findTyping(ParseTree node) {
        if (node instanceof TPTPParser.Tff_atom_typingContext typing) {
            return typing;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TPTPParser.Tff_atom_typingContext found = findTyping(node.getChild(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
