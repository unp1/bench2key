package org.key_project.smt2key;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.key_project.bench2key.run.Format;
import org.key_project.bench2key.run.Problem;
import org.key_project.bench2key.run.Result;
import org.key_project.smt2key.run.Options;
import org.key_project.smt2key.run.Translation;

/** SMT-LIB as an input language: problems grouped by the logic they declare. */
public final class SmtFormat implements Format<Options> {

    private static final Pattern LOGIC = Pattern.compile("\\(\\s*set-logic\\s+([\\w_]+)\\s*\\)");
    private static final Pattern STATUS =
        Pattern.compile("\\(\\s*set-info\\s+:status\\s+(\\w+)\\s*\\)");

    @Override
    public String name() {
        return "SMT-LIB";
    }

    @Override
    public String sourceLabel() {
        return "SMT-LIB directory";
    }

    @Override
    public String categoryLabel() {
        return "Logics";
    }

    @Override
    public String provableNote() {
        return "(only unsat problems can be proved)";
    }

    @Override
    public boolean accepts(Path file) {
        return file.toString().endsWith(".smt2");
    }

    @Override
    public List<String> statuses() {
        return List.of("unsat", "sat", "unknown");
    }

    @Override
    public String targetName(String sourceName) {
        return sourceName.replaceFirst("\\.smt2$", "") + ".key";
    }

    @Override
    public Options defaultOptions() {
        return Options.defaults();
    }

    @Override
    public Result translate(Path source, Path target, Options options) {
        // The status filter is applied to the table before anything is translated, so the
        // translation itself accepts whatever it is given.
        return Translation.translate(source, target, options, null);
    }

    /**
     * Reads only the header of a file.
     *
     * Everything wanted here is stated before the first declaration or assertion, and benchmark
     * files run to tens of megabytes, so reading stops as soon as the body starts.
     */
    @Override
    public Problem read(Path file) {
        String logic = null;
        String status = "unknown";
        long size = 0;
        try {
            size = Files.size(file);
            try (BufferedReader in = Files.newBufferedReader(file)) {
                String line;
                while ((line = in.readLine()) != null) {
                    String trimmed = line.stripLeading();
                    if (trimmed.startsWith("(declare") || trimmed.startsWith("(assert")
                            || trimmed.startsWith("(define") || trimmed.startsWith("(check-sat")) {
                        break;
                    }
                    Matcher m = LOGIC.matcher(line);
                    if (logic == null && m.find()) {
                        logic = m.group(1);
                    }
                    m = STATUS.matcher(line);
                    if (m.find()) {
                        status = m.group(1);
                    }
                }
            }
        } catch (IOException e) {
            // A file that cannot be read still belongs in the table, marked by its missing logic.
        }
        if (logic == null) {
            Path parent = file.getParent();
            logic = parent == null ? "?" : parent.getFileName().toString();
        }
        // Only an unsatisfiable set of assertions makes the translated sequent valid.
        return new Problem(file, logic, status, size, "unsat".equals(status));
    }
}
