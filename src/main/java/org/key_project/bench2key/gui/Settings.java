package org.key_project.bench2key.gui;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.prefs.Preferences;

import org.key_project.bench2key.run.StrategyOptions;

/**
 * What the window remembers between sessions.
 *
 * Some of it belongs to the window as a whole, such as where translations go and which KeY to run.
 * The rest belongs to one input language: where its collection sits, which declared status its
 * table is filtered by, and whatever its own translation offers. Those are stored under a key
 * carrying the language's name, so the two tabs remember separate answers to the same question.
 */
public final class Settings {

    /** Worker threads for one proof unless told otherwise. */
    public static final int DEFAULT_CORES =
        Math.min(4, Runtime.getRuntime().availableProcessors());

    private final Preferences store = Preferences.userNodeForPackage(Settings.class);

    // ------------------------------------------------------------------ shared

    public Path outputDirectory() {
        return path("outDir");
    }

    public void outputDirectory(Path p) {
        put("outDir", p);
    }

    public Path keyJar() {
        return path("keyJar");
    }

    public void keyJar(Path p) {
        put("keyJar", p);
    }

    public int timeout() {
        return store.getInt("timeout", 10_000);
    }

    public void timeout(int v) {
        store.putInt("timeout", v);
    }

    public int jobs() {
        return store.getInt("jobs", 2);
    }

    public void jobs(int v) {
        store.putInt("jobs", v);
    }

    public String runner() {
        return store.get("runner", "subprocess");
    }

    public void runner(String v) {
        store.put("runner", v);
    }

    /** Multi core is the default; KeY's parallel prover produces the same proof, only sooner. */
    public boolean multiCore() {
        return store.getBoolean("multiCore", true);
    }

    public void multiCore(boolean v) {
        store.putBoolean("multiCore", v);
    }

    /** Four workers by default, which is where KeY's parallel prover has been exercised most. */
    public int coreCount() {
        return store.getInt("coreCount", DEFAULT_CORES);
    }

    public void coreCount(int v) {
        store.putInt("coreCount", v);
    }

    /** The strategy settings used for every problem that has none of its own. */
    public StrategyOptions strategy() {
        StrategyOptions d = StrategyOptions.defaults();
        return new StrategyOptions(store.getInt("maxSteps", d.maxSteps()),
            store.getInt("strategyTimeout", d.timeoutMillis()),
            store.get("nonLinArith", d.nonLinearArithmetic()),
            store.get("quantifiers", d.quantifiers()),
            store.get("splitting", d.splitting()),
            store.get("triggers", d.triggers()));
    }

    public void strategy(StrategyOptions o) {
        store.putInt("maxSteps", o.maxSteps());
        store.putInt("strategyTimeout", o.timeoutMillis());
        store.put("nonLinArith", o.nonLinearArithmetic());
        store.put("quantifiers", o.quantifiers());
        store.put("splitting", o.splitting());
        store.put("triggers", o.triggers());
    }

    // ------------------------------------------------------------------ per language

    public Path sourceDirectory(String format) {
        return path(format + ".sourceDir");
    }

    public void sourceDirectory(String format, Path p) {
        put(format + ".sourceDir", p);
    }

    /** Which declared status the problem table shows, or {@code all}. */
    public String statusFilter(String format) {
        return store.get(format + ".statusFilter", "all");
    }

    public void statusFilter(String format, String v) {
        store.put(format + ".statusFilter", v);
    }

    /** A choice belonging to one language's translation. */
    public String get(String format, String key, String fallback) {
        return store.get(format + "." + key, fallback);
    }

    public void set(String format, String key, String value) {
        store.put(format + "." + key, value == null ? "" : value);
    }

    public boolean getBoolean(String format, String key, boolean fallback) {
        return store.getBoolean(format + "." + key, fallback);
    }

    public void setBoolean(String format, String key, boolean value) {
        store.putBoolean(format + "." + key, value);
    }

    private Path path(String key) {
        String v = store.get(key, "");
        return v.isEmpty() ? null : Paths.get(v);
    }

    private void put(String key, Path p) {
        store.put(key, p == null ? "" : p.toString());
    }
}
