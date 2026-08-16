package org.key_project.bench2key.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.key_project.tptp2key.TptpFormat;
import org.key_project.tptp2key.TptpOptions;

/** Reading a collection out of an archive, which is meant to be indistinguishable from a directory. */
class SourcesTest {

    @TempDir
    Path dir;

    /** Writes a zip holding the named entries, as a library archive would look. */
    private Path zip(String name, String... pathsAndContents) throws IOException {
        Path archive = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int i = 0; i < pathsAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry(pathsAndContents[i]));
                out.write(pathsAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return archive;
    }

    @Test
    void aDirectoryIsOpenedAsItself() throws IOException {
        try (Sources sources = Sources.open(dir)) {
            assertEquals(dir, sources.root());
            assertEquals(false, sources.archived());
        }
    }

    @Test
    void anArchiveHoldingOneDirectoryIsEntered() throws IOException {
        Path archive = zip("library.zip", "TPTP-v9.3.0/Problems/PUZ/PUZ001-1.p", "cnf(a,axiom, p ).\n");
        try (Sources sources = Sources.open(archive)) {
            assertTrue(sources.archived());
            assertEquals("TPTP-v9.3.0", sources.root().getFileName().toString());
        }
    }

    @Test
    void aScanOfAnArchiveFindsWhatAScanOfTheDirectoryWould() throws IOException {
        Path archive = zip("library.zip",
            "Problems/PUZ/PUZ001-1.p", "% Status   : Unsatisfiable\ncnf(a,axiom, p ).\n",
            "Problems/SET/SET001-1.p", "% Status   : Unsatisfiable\ncnf(a,axiom, q ).\n");
        try (Sources sources = Sources.open(archive)) {
            Corpus corpus = Corpus.scan(sources.root(), new TptpFormat(), null);
            assertEquals(2, corpus.problems().size());
            assertEquals(1, corpus.categories().get("PUZ"));
            assertEquals("Unsatisfiable", corpus.byCategory("SET").get(0).status());
        }
    }

    @Test
    void anIncludeInsideAnArchiveResolvesAgainstTheArchive() throws IOException {
        Path archive = zip("library.zip",
            "Axioms/SET001-0.ax", "cnf(axiom_one,axiom, p(a) ).\n",
            "Problems/SET/SET001-1.p",
            "% Status   : Unsatisfiable\ninclude('Axioms/SET001-0.ax').\ncnf(c,axiom, q(a) ).\n");
        try (Sources sources = Sources.open(archive)) {
            Path problem = sources.root().resolve("Problems/SET/SET001-1.p");
            Path target = dir.resolve("out.key");
            Result result =
                new TptpFormat().translate(problem, target, TptpOptions.defaults());
            assertEquals(Outcome.OK, result.outcome(), result.detail());
            String text = Files.readString(target);
            assertTrue(text.contains("tptp_p(tptp_a)"), "the included axiom is missing: " + text);
            assertTrue(text.contains("tptp_q(tptp_a)"), text);
        }
    }

    @Test
    void outputGoesToRealFilesEvenWhenTheSourceIsInAnArchive() throws IOException {
        // Two directories at the top, as the library has, so that nothing is entered and the
        // paths below the root are the ones the output mirrors.
        Path archive = zip("library.zip",
            "Axioms/SET001-0.ax", "cnf(a,axiom, p ).\n",
            "Problems/PUZ/PUZ001-1.p", "% Status   : Unsatisfiable\ncnf(a,axiom, p ).\n");
        try (Sources sources = Sources.open(archive)) {
            Corpus corpus = Corpus.scan(sources.root().resolve("Problems"), new TptpFormat(), null);
            Path out = dir.resolve("out");
            Path target = corpus.targetFor(corpus.problems().get(0), out);
            // The name and the structure carry over; the file system does not.
            assertEquals(out.getFileSystem(), target.getFileSystem());
            assertEquals(out.resolve("PUZ").resolve("PUZ001-1.key"), target);
        }
    }
}
