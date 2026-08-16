package org.key_project.bench2key.run;

import java.nio.file.Path;

/** How translating one problem went, with a line saying why when it did not go. */
public record Result(Path source, Path target, Outcome outcome, String detail) {

    public boolean ok() {
        return outcome == Outcome.OK;
    }

    /** Shortens a diagnosis to something a table cell can carry. */
    public static String shorten(String message) {
        String text = String.valueOf(message).replaceAll("\\s+", " ").trim();
        return text.length() > 110 ? text.substring(0, 110) + "..." : text;
    }
}
