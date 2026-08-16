package org.key_project.tptp2key;

import java.util.concurrent.Callable;

/**
 * Runs work on a thread with room for deep recursion.
 *
 * Reading a TPTP problem descends once per level of nesting, and does so three times over: the
 * generated parser walks its rules, the collector builds a formula from the parse tree, and the
 * translator prints it. The library holds formulas nested thousands of levels deep, which is past
 * what a default thread has room for, so the whole of that work belongs here rather than the
 * parsing alone.
 */
public final class DeepStack {

    private DeepStack() {}

    /** Stack for the working thread, in bytes. */
    private static long bytes = 512L * 1024 * 1024;

    public static void setBytes(long value) {
        bytes = value;
    }

    /** Runs the work on a thread of its own and returns what it produced. */
    public static <T> T call(String name, Callable<T> work) {
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        // A thread of its own is the only way to ask for a larger stack.
        Thread thread = new Thread(null, () -> {
            try {
                result[0] = work.call();
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, name, bytes);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        if (failure[0] instanceof RuntimeException e) {
            throw e;
        }
        if (failure[0] instanceof Error e) {
            throw e;
        }
        if (failure[0] != null) {
            throw new IllegalStateException(failure[0]);
        }
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }
}
