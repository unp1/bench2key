package org.key_project.tptp2key;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.key_project.tptp2key.parser.TPTPLexer;
import org.key_project.tptp2key.parser.TPTPParser;

/** Reads a TPTP file into a parse tree, turning syntax errors into an exception. */
public final class Parser {

    private Parser() {}

    /** A file the grammar rejects. The message names the first position that went wrong. */
    public static final class SyntaxError extends RuntimeException {
        public SyntaxError(String message) {
            super(message);
        }
    }

    public static TPTPParser.Tptp_fileContext parse(Path file) throws IOException {
        return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), file.toString());
    }

    public static TPTPParser.Tptp_fileContext parse(String source, String name) {
        // Deep recursion is handled once, around the whole of the reading and printing, by
        // DeepStack; a file parsed on its own still gets the room it needs.
        return DeepStack.call("tptp-parser", () -> parseHere(source, name));
    }

    private static TPTPParser.Tptp_fileContext parseHere(String source, String name) {
        TPTPLexer lexer = new TPTPLexer(CharStreams.fromString(source, name));
        TPTPParser parser = new TPTPParser(new CommonTokenStream(lexer));
        List<String> errors = new ArrayList<>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offending, int line,
                    int column, String message, RecognitionException e) {
                if (errors.size() < 4) {
                    errors.add(line + ":" + column + " " + message);
                }
            }
        };
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);

        TPTPParser.Tptp_fileContext tree = parser.tptp_file();
        if (!errors.isEmpty()) {
            throw new SyntaxError(name + ": " + String.join("; ", errors));
        }
        return tree;
    }
}
