package org.key_project.tptp2key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Choosing which problems to try, when trying all of them is not the point. */
class SampleTest {

    @TempDir
    Path dir;

    private List<Path> files(String... names) throws IOException {
        List<Path> made = new ArrayList<>();
        for (String name : names) {
            Path file = dir.resolve(name);
            Files.write(file, "cnf(a,axiom, p ).\n".getBytes(StandardCharsets.UTF_8));
            made.add(file);
        }
        return made;
    }

    @Test
    void aSpreadTakesTheSameNumberFromEveryDomain() throws IOException {
        List<Path> all = files("PUZ001-1.p", "PUZ002-1.p", "PUZ003-1.p",
            "SET001-1.p", "SET002-1.p", "GRP001-1.p");
        List<Path> chosen = Sample.spread(all, 2);
        assertEquals(5, chosen.size(), "two of PUZ, two of SET, the one GRP");
        assertTrue(chosen.stream().anyMatch(p -> p.getFileName().toString().startsWith("GRP")),
            "a domain with fewer than asked for is still represented");
        assertEquals(false, chosen.contains(all.get(2)), "the third PUZ is beyond the two asked for");
    }

    @Test
    void theExtremesAreTheLargestProblems() throws IOException {
        List<Path> all = files("AAA001-1.p", "BBB001-1.p", "CCC001-1.p");
        Files.write(all.get(1), "x".repeat(5000).getBytes(StandardCharsets.UTF_8));
        List<Path> chosen = Sample.extremes(all, 1);
        assertEquals(all.get(1), chosen.get(0));
    }

    @Test
    void smokeTakesOneProblemOfEachLanguage() throws IOException {
        List<Path> all = files("PUZ001-1.p", "PUZ002-1.p", "PUZ001+1.p", "PUZ001_1.p", "PUZ001^1.p");
        List<Path> chosen = Sample.smoke(all);
        assertEquals(4, chosen.size(), "one each of CNF, FOF, TFF and THF");
        assertTrue(chosen.contains(all.get(0)));
        assertEquals(false, chosen.contains(all.get(1)), "a second clause problem adds nothing");
    }

    @Test
    void aSelectionCanSayHowManyItWants() throws IOException {
        List<Path> all = files("PUZ001-1.p", "PUZ002-1.p", "PUZ003-1.p");
        assertEquals(1, Sample.select("spread:1", all).size());
        assertEquals(3, Sample.select("all", all).size());
        assertEquals(2, Sample.select("extremes:2", all).size());
    }

    @Test
    void anUnknownSelectionSaysWhatItExpected() throws IOException {
        List<Path> all = files("PUZ001-1.p");
        IllegalArgumentException e =
            assertThrows(IllegalArgumentException.class, () -> Sample.select("most", all));
        assertTrue(e.getMessage().contains("spread"), e.getMessage());
    }
}
