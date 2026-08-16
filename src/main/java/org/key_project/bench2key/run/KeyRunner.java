package org.key_project.bench2key.run;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

/**
 * Running KeY on a translated problem.
 *
 * Two ways of calling KeY, good at different things. {@link SubprocessRunner} starts KeY as a
 * program of its own, so each proof gets its own JVM and a problem that exhausts memory takes
 * nothing else with it, which matters for a batch. The in-process runner keeps the proof in this
 * JVM, which is faster, reports KeY's figures directly rather than through a file, and can hand a
 * finished proof straight to KeY's window.
 *
 * Only the first of those is built by default, and the reason is licensing rather than taste. KeY
 * is under the GPL, and calling it in this JVM means compiling against it and linking it, which
 * makes the result a combined work; jSMTLIB, which the SMT-LIB front end parses with, is under the
 * EPL, and the two licences cannot be combined in one distributed program. Starting KeY as a
 * separate program raises no such question. The in-process runner is therefore kept in a source set
 * of its own, compiled only when the build is asked for it with {@code -Pkey.inprocess=true}, and
 * reached from here by name so that nothing else refers to it. The GPL governs distribution, so a
 * build made and kept by one person is free to link whatever it likes.
 */
public interface KeyRunner extends AutoCloseable {

    /** Proves one .key file, giving up after the timeout. */
    ProofOutcome prove(Path keyFile, int timeoutMillis);

    /** Opens a .key problem or a saved .proof in KeY's own window. */
    void openInKeyGui(Path file) throws IOException;

    /** A name for this way of running KeY, for the GUI to show. */
    String describe();

    @Override
    void close();

    /** The class the in-process runner is compiled to, when it is compiled at all. */
    String IN_PROCESS = "org.key_project.bench2key.run.InProcessRunner";

    /** Whether this build has the in-process runner in it. */
    static boolean inProcessAvailable() {
        try {
            Class.forName(IN_PROCESS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * The in-process runner, if this build has one.
     *
     * @throws IOException when it was not built, which is the default
     */
    static KeyRunner inProcess(int threads) throws IOException {
        try {
            return (KeyRunner) Class.forName(IN_PROCESS).getConstructor(int.class)
                .newInstance(threads);
        } catch (ClassNotFoundException e) {
            throw new IOException("this build has no in-process runner; build with "
                + "-Pkey.inprocess=true to link KeY into it, or run KeY as a separate process");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new IOException("the in-process runner could not start: " + cause, cause);
        } catch (ReflectiveOperationException e) {
            throw new IOException("the in-process runner could not start: " + e, e);
        }
    }
}
