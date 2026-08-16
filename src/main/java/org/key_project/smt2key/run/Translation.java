package org.key_project.smt2key.run;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.key_project.bench2key.run.Outcome;
import org.key_project.bench2key.run.Result;
import org.key_project.smt2key.ArrayEncoding;
import org.key_project.smt2key.Collector;
import org.key_project.smt2key.Translator;
import org.key_project.smt2key.Unsupported;
import org.smtlib.CharSequenceReader;
import org.smtlib.ICommand;
import org.smtlib.IParser;
import org.smtlib.IResponse;
import org.smtlib.ISource;
import org.smtlib.SMT;

/** Translating one SMT-LIB file, shared by the command line and the GUI. */
public final class Translation {

        private Translation() {
    }

    /**
     * Reads one SMT-LIB file and writes its .key translation.
     *
     * @param statusFilter declared statuses to accept, or null for all
     */
    public static Result translate(Path source, Path target, Options options,
            Set<String> statusFilter) {
        SMT smt = new SMT();
        if (options.logics() != null) {
            smt.smtConfig.logicPath = options.logics().toString();
        }
        smt.smtConfig.relax = false;
        // jSMTLIB reports through a log rather than by returning; capture it for the diagnosis.
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        smt.smtConfig.log.out = sink;
        smt.smtConfig.log.diag = sink;

        Collector collector = new Collector(smt.smtConfig);
        try {
            String text = Files.readString(source);
            // The buffer is sized to the whole input on purpose. jSMTLIB's reader starts at
            // 100000 characters and grows by doubling, and a token that straddles one of those
            // boundaries is cut in two: a symbol at the boundary comes back short by the
            // characters beyond it, and the file then fails on a name that never appears in it.
            ISource src = smt.smtConfig.smtFactory.createSource(
                new CharSequenceReader(new StringReader(text), text.length() + 2, 100, 2), null);
            IParser parser = smt.smtConfig.smtFactory.createParser(smt.smtConfig, src);
            collector.start();
            while (!parser.isEOD()) {
                ICommand cmd = parser.parseCommand();
                if (cmd == null) {
                    return new Result(source, target, Outcome.REJECTED,
                        firstLine(captured, "parse error"));
                }
                IResponse r = cmd.execute(collector);
                if (r != null && r.isError()) {
                    return new Result(source, target, Outcome.REJECTED,
                        firstLine(captured, "command error"));
                }
                if (collector.checkSats() > 0) {
                    break;
                }
            }
            if (statusFilter != null && !statusFilter.contains(collector.status())) {
                return new Result(source, target, Outcome.SKIPPED, "status " + collector.status());
            }
            ArrayEncoding arrays = ArrayEncoding.byName(options.arrays(), options.extensional());
            String key = new Translator(collector, arrays, options.lets(), text.length())
                .run(source.getFileName().toString(), options.strategy());
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, key);
            return new Result(source, target, Outcome.OK, "");
        } catch (Unsupported u) {
            return new Result(source, target, Outcome.UNSUPPORTED, shorten(u.getMessage()));
        } catch (IParser.ParserException e) {
            return new Result(source, target, Outcome.REJECTED,
                firstLine(captured, String.valueOf(e.getMessage())));
        } catch (java.io.IOException e) {
            return new Result(source, target, Outcome.IO_ERROR, shorten(e.getMessage()));
        } catch (RuntimeException | StackOverflowError e) {
            return new Result(source, target, Outcome.CRASH,
                e.getClass().getSimpleName() + ": " + shorten(e.getMessage()));
        }
    }

    /** The first line logged, which is the message closest to the cause. */
    private static String firstLine(ByteArrayOutputStream captured, String fallback) {
        String all = captured.toString(StandardCharsets.UTF_8).trim();
        return shorten(all.isEmpty() ? fallback : all.lines().findFirst().orElse(fallback));
    }

    private static String shorten(String s) {
        String t = String.valueOf(s).replaceAll("\\s+", " ").trim();
        return t.length() > 110 ? t.substring(0, 110) + "..." : t;
    }
}
