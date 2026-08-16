package org.key_project.bench2key.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.key_project.smt2key.SmtFormat;
import org.key_project.tptp2key.TptpFormat;
import org.key_project.tptp2key.TptpOptions;

/** The seam the window and the corpus scanner reach both input languages through. */
class FormatTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent() == null ? dir : file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void tptpGroupsProblemsByTheDomainTheirNameBeginsWith() throws IOException {
        Path file = write("PUZ031+1.p", "% Status   : Theorem\nfof(a,conjecture, p ).\n");
        Problem problem = new TptpFormat().read(file);
        assertEquals("PUZ", problem.category());
        assertEquals("Theorem", problem.status());
        assertTrue(problem.provable(), "a Theorem has a proof to find");
    }

    @Test
    void tptpKnowsWhichStatusesLeaveAProofToFind() throws IOException {
        Path counter = write("SYN316+1.p",
            "% Status   : CounterSatisfiable\nfof(a,conjecture, p ).\n");
        assertEquals(false, new TptpFormat().read(counter).provable());
        Path unsat = write("PUZ001-1.p", "% Status   : Unsatisfiable\ncnf(a,axiom, p ).\n");
        assertTrue(new TptpFormat().read(unsat).provable());
    }

    @Test
    void tptpWithoutAStatusHeaderIsUnknown() throws IOException {
        Path file = write("ABC001+1.p", "fof(a,conjecture, p ).\n");
        assertEquals("Unknown", new TptpFormat().read(file).status());
    }

    @Test
    void smtGroupsProblemsByTheLogicTheyDeclare() throws IOException {
        Path file = write("x.smt2",
            "(set-logic QF_UF)\n(set-info :status unsat)\n(assert false)\n(check-sat)\n");
        Problem problem = new SmtFormat().read(file);
        assertEquals("QF_UF", problem.category());
        assertEquals("unsat", problem.status());
        assertTrue(problem.provable(), "an unsat problem has a proof to find");
    }

    @Test
    void aScanGroupsWhatItFindsAndMirrorsTheTreeOnTheWayOut() throws IOException {
        write("PUZ/PUZ001-1.p", "% Status   : Unsatisfiable\ncnf(a,axiom, p ).\n");
        write("PUZ/PUZ002-1.p", "% Status   : Unsatisfiable\ncnf(a,axiom, q ).\n");
        write("SET/SET001-1.p", "% Status   : Unsatisfiable\ncnf(a,axiom, r ).\n");
        Corpus corpus = Corpus.scan(dir, new TptpFormat(), null);

        Map<String, Integer> categories = corpus.categories();
        assertEquals(2, categories.get("PUZ"));
        assertEquals(1, categories.get("SET"));
        assertEquals(2, corpus.byCategory("PUZ").size());

        Problem first = corpus.byCategory("SET").get(0);
        assertEquals(Path.of("out", "SET", "SET001-1.key"),
            corpus.targetFor(first, Path.of("out")));
    }

    @Test
    void aScanTakesOnlyTheFilesOfItsOwnLanguage() throws IOException {
        write("a.p", "% Status   : Theorem\nfof(a,conjecture, p ).\n");
        write("b.smt2", "(set-logic QF_UF)\n(assert false)\n(check-sat)\n");
        assertEquals(1, Corpus.scan(dir, new TptpFormat(), null).problems().size());
        assertEquals(1, Corpus.scan(dir, new SmtFormat(), null).problems().size());
    }

    @Test
    void translatingThroughTheSeamWritesTheKeyFile() throws IOException {
        Path source = write("PUZ001-1.p",
            "% Status   : Unsatisfiable\ncnf(a,axiom, p(c) ).\ncnf(b,negated_conjecture, ~ p(c) ).\n");
        Path target = dir.resolve("PUZ001-1.key");
        Result result = new TptpFormat().translate(source, target, TptpOptions.defaults());
        assertEquals(Outcome.OK, result.outcome(), result.detail());
        String text = Files.readString(target);
        assertTrue(text.contains("tptp_p(tptp_c)"), text);
        assertTrue(text.contains("==>"), text);
    }

    @Test
    void aStrategyChosenInTheWindowIsWrittenIntoTheFile() throws IOException {
        Path source = write("PUZ001-1.p", "% Status   : Unsatisfiable\ncnf(a,axiom, p ).\n");
        Path target = dir.resolve("out.key");
        TptpOptions options = TptpOptions.defaults().withStrategy(StrategyOptions.defaults());
        assertEquals(Outcome.OK,
            new TptpFormat().translate(source, target, options).outcome());
        assertTrue(Files.readString(target).contains("\\settings"), Files.readString(target));
    }

    @Test
    void whatCannotBeTranslatedIsReportedRatherThanThrown() throws IOException {
        Path source = write("SYN000^1.p", "thf(a,axiom, ( p @ a ) ).\n");
        Result result =
            new TptpFormat().translate(source, dir.resolve("o.key"), TptpOptions.defaults());
        assertEquals(Outcome.UNSUPPORTED, result.outcome());
        assertTrue(result.detail().contains("beyond first order"), result.detail());
    }
}
