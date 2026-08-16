package org.key_project.tptp2key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranslatorTest {

    @TempDir
    Path dir;

    /** Translates a TPTP source, written to a file so that includes resolve as they do in use. */
    private String translate(String source) throws IOException {
        return translate(source, "test.p");
    }

    private String translate(String source, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, source.getBytes(StandardCharsets.UTF_8));
        Collector collector = new Collector(dir);
        collector.read(file);
        return new Translator(collector).run(name, Tptp2Key.status(source));
    }

    /** The part of the sequent before the arrow. */
    private String antecedent(String key) {
        String problem = key.substring(key.indexOf("\\problem {") + 10);
        return problem.substring(0, problem.indexOf("==>")).trim();
    }

    /** The part of the sequent after the arrow. */
    private String succedent(String key) {
        String problem = key.substring(key.indexOf("\\problem {") + 10);
        return problem.substring(problem.indexOf("==>") + 3).replace("}", "").trim();
    }

    @Test
    void axiomsBecomeTheAntecedentAndTheConjectureTheSuccedent() throws IOException {
        String key = translate("""
            fof(a1,axiom, p(c) ).
            fof(goal,conjecture, q(c) ).
            """);
        assertEquals("tptp_p(tptp_c)", antecedent(key));
        assertEquals("tptp_q(tptp_c)", succedent(key));
    }

    @Test
    void aClauseSetIsRefutedAgainstFalse() throws IOException {
        String key = translate("""
            cnf(c1,axiom, p(a) ).
            cnf(c2,negated_conjecture, ~ p(a) ).
            """);
        assertEquals("false", succedent(key));
        assertTrue(antecedent(key).contains("tptp_p(tptp_a)"), antecedent(key));
        assertTrue(antecedent(key).contains("!(tptp_p(tptp_a))"), antecedent(key));
    }

    @Test
    void clauseVariablesAreQuantifiedUniversally() throws IOException {
        String key = translate("cnf(c,axiom, ( ~ p(X) | q(X) ) ).\n");
        assertEquals("\\forall TPTP_i sv_0; ((!(tptp_p(sv_0)) | tptp_q(sv_0)))", antecedent(key));
    }

    @Test
    void predicatesAndFunctionsAreDeclaredWithTheirArity() throws IOException {
        String key = translate("fof(a,axiom, p(f(a),b) ).\n");
        assertTrue(key.contains("tptp_p(TPTP_i, TPTP_i);"), key);
        assertTrue(key.contains("TPTP_i tptp_f(TPTP_i);"), key);
        assertTrue(key.contains("TPTP_i tptp_a;"), key);
    }

    @Test
    void everyConnectiveHasACounterpart() throws IOException {
        String key = translate("""
            fof(a,axiom, ( ( p <=> q ) & ( ( p => q ) & ( p <= q ) ) ) ).
            fof(b,axiom, ( ( p <~> q ) & ( ( p ~| q ) & ( p ~& q ) ) ) ).
            """);
        assertTrue(key.contains("(tptp_p <-> tptp_q)"), key);
        assertTrue(key.contains("(tptp_p -> tptp_q)"), key);
        assertTrue(key.contains("(tptp_q -> tptp_p)"), key);
        assertTrue(key.contains("!(tptp_p <-> tptp_q)"), key);
        assertTrue(key.contains("!(tptp_p | tptp_q)"), key);
        assertTrue(key.contains("!(tptp_p & tptp_q)"), key);
    }

    @Test
    void distinctObjectsAreUniqueConstants() throws IOException {
        String key = translate("fof(a,axiom, \"one\" != \"two\" ).\n");
        assertTrue(key.contains("\\unique TPTP_i tptp_do_one;"), key);
        assertTrue(key.contains("\\unique TPTP_i tptp_do_two;"), key);
        assertEquals("!(tptp_do_one = tptp_do_two)", antecedent(key));
    }

    @Test
    void integerArithmeticUsesKeysOwnOperators() throws IOException {
        String key = translate("""
            tff(a,axiom, ! [X: $int] : $less($sum(X,1),$product(X,2)) ).
            tff(b,axiom, $quotient_e(7,2) = $remainder_e(7,2) ).
            """);
        assertTrue(key.contains("\\forall int sv_0; (((sv_0 + 1) < (sv_0 * 2)))"), key);
        assertTrue(key.contains("(div(7, 2) = mod(7, 2))"), key);
    }

    @Test
    void declaredTypesBecomeSorts() throws IOException {
        String key = translate("""
            tff(t,type, list: $tType ).
            tff(n,type, nil: list ).
            tff(l,type, len: list > $int ).
            tff(a,conjecture, len(nil) = 0 ).
            """);
        assertTrue(key.contains("TPTP_list;"), key);
        assertTrue(key.contains("TPTP_list tptp_nil;"), key);
        assertTrue(key.contains("int tptp_len(TPTP_list);"), key);
        assertEquals("(tptp_len(tptp_nil) = 0)", succedent(key));
    }

    @Test
    void quotedSymbolsAreRewrittenToLegalIdentifiers() throws IOException {
        String key = translate("fof(a,axiom, 'a symbol'(c) ).\n");
        assertTrue(key.contains("tptp_a_20symbol(TPTP_i);"), key);
    }

    @Test
    void includedFilesContributeTheirFormulas() throws IOException {
        Files.write(dir.resolve("axioms.ax"),
            "fof(one,axiom, p(a) ).\nfof(two,axiom, q(a) ).\n".getBytes(StandardCharsets.UTF_8));
        String key = translate("include('axioms.ax').\nfof(goal,conjecture, p(a) ).\n");
        assertTrue(antecedent(key).contains("tptp_p(tptp_a)"), antecedent(key));
        assertTrue(antecedent(key).contains("tptp_q(tptp_a)"), antecedent(key));
    }

    @Test
    void anIncludeSelectionTakesOnlyTheNamedFormulas() throws IOException {
        Files.write(dir.resolve("axioms.ax"),
            "fof(one,axiom, p(a) ).\nfof(two,axiom, q(a) ).\n".getBytes(StandardCharsets.UTF_8));
        String key = translate("include('axioms.ax',[one]).\nfof(goal,conjecture, p(a) ).\n");
        assertEquals("tptp_p(tptp_a)", antecedent(key));
    }

    @Test
    void theDeclaredStatusIsCarriedIntoTheOutput() throws IOException {
        String key = translate("% Status   : Theorem\nfof(a,conjecture, p ).\n");
        assertTrue(key.contains("declared status Theorem"), key);
    }

    @Test
    void higherOrderInputIsRefusedByName() {
        Unsupported e = assertThrows(Unsupported.class,
            () -> translate("thf(a,axiom, ( p @ a ) ).\n"));
        assertTrue(e.getMessage().contains("beyond first order"), e.getMessage());
    }

    @Test
    void aSymbolUsedAtTwoAritiesIsRefused() {
        Unsupported e = assertThrows(Unsupported.class,
            () -> translate("fof(a,axiom, ( p(x) & p(x,y) ) ).\n"));
        assertTrue(e.getMessage().contains("arguments"), e.getMessage());
    }

    @Test
    void realAndRationalTypesAreRefusedRatherThanEmittedUnprovable() {
        Unsupported e = assertThrows(Unsupported.class, () -> translate("""
            tff(t,type, c: $real ).
            tff(a,axiom, c = c ).
            """));
        assertTrue(e.getMessage().contains("$real"), e.getMessage());
    }
}
