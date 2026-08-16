package org.key_project.bench2key.run;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The KeY strategy settings a translated problem is proved with.
 *
 * These reach KeY as a {@code \settings} block in the generated .key file, which both ways of
 * running KeY read when they load it. Putting them in the file rather than applying them afterwards
 * means the file records what it was proved with, so the run can be repeated from the file alone.
 *
 * Only the settings that bear on first order problems are here. The rest of what KeY offers
 * concerns Java verification, and a translated SMT-LIB problem contains no program.
 *
 * @param maxSteps rule applications before the strategy gives up
 * @param timeoutMillis wall clock limit for the strategy, or -1 for none
 * @param nonLinearArithmetic {@code NON_LIN_ARITH_NONE}, {@code _DEF_OPS} or {@code _COMPLETION}
 * @param quantifiers {@code QUANTIFIERS_NONE}, {@code _NON_SPLITTING},
 *        {@code _NON_SPLITTING_WITH_PROGS} or {@code _INSTANTIATE}
 * @param splitting {@code SPLITTING_NORMAL}, {@code SPLITTING_OFF} or {@code SPLITTING_DELAYED}
 * @param triggers which trigger selection quantifier instantiation uses: {@code TRIGGERS_BEST},
 *        {@code TRIGGERS_GOOD} or {@code TRIGGERS_CLASSIC}
 */
public record StrategyOptions(int maxSteps, int timeoutMillis, String nonLinearArithmetic,
        String quantifiers, String splitting, String triggers) {

    public static final String[] NON_LINEAR_ARITHMETIC =
        { "NON_LIN_ARITH_NONE", "NON_LIN_ARITH_DEF_OPS", "NON_LIN_ARITH_COMPLETION" };

    public static final String[] QUANTIFIERS = { "QUANTIFIERS_NONE", "QUANTIFIERS_NON_SPLITTING",
        "QUANTIFIERS_NON_SPLITTING_WITH_PROGS", "QUANTIFIERS_INSTANTIATE" };

    public static final String[] SPLITTING =
        { "SPLITTING_NORMAL", "SPLITTING_OFF", "SPLITTING_DELAYED" };

    /**
     * How quantifier instantiation picks its triggers. Best and Good both use the theory aware
     * selection and differ in how tied candidates are ordered; Classic is the plain equality and
     * integer selection with no ordering.
     */
    public static final String[] TRIGGERS = { "TRIGGERS_BEST", "TRIGGERS_GOOD", "TRIGGERS_CLASSIC" };

    /**
     * The starting point for a translated problem.
     *
     * KeY's own default of 10000 rule applications is short for these: a translated benchmark
     * carries its whole assertion set at once rather than growing one through symbolic execution.
     * Lifting the cap much further does not help either, since a search with no traction spends
     * the extra steps going nowhere, so this sits a little above KeY's default rather than far
     * above it.
     */
    public static StrategyOptions defaults() {
        return new StrategyOptions(30_000, -1, "NON_LIN_ARITH_DEF_OPS", "QUANTIFIERS_INSTANTIATE",
            "SPLITTING_DELAYED", "TRIGGERS_BEST");
    }

    /**
     * The {@code \settings} block for a .key file.
     *
     * The block has to precede the declarations, and the reader accepts a table of the same shape
     * KeY writes into a saved proof.
     */
    public String toKeyBlock() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("NON_LIN_ARITH_OPTIONS_KEY", nonLinearArithmetic);
        options.put("QUANTIFIERS_OPTIONS_KEY", quantifiers);
        options.put("SPLITTING_OPTIONS_KEY", splitting);
        options.put("TRIGGERS_OPTIONS_KEY", triggers);

        StringBuilder sb = new StringBuilder();
        sb.append("\\settings {\n");
        sb.append("    \"Strategy\" : {\n");
        sb.append("        \"MaximumNumberOfAutomaticApplications\" : ").append(maxSteps).append(",\n");
        sb.append("        \"Timeout\" : ").append(timeoutMillis).append(",\n");
        sb.append("        \"options\" : {\n");
        int i = 0;
        for (Map.Entry<String, String> e : options.entrySet()) {
            sb.append("            \"").append(e.getKey()).append("\" : \"").append(e.getValue())
                .append(++i < options.size() ? "\",\n" : "\"\n");
        }
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    public StrategyOptions withMaxSteps(int v) {
        return new StrategyOptions(v, timeoutMillis, nonLinearArithmetic, quantifiers, splitting,
            triggers);
    }

    public StrategyOptions withTimeout(int v) {
        return new StrategyOptions(maxSteps, v, nonLinearArithmetic, quantifiers, splitting,
            triggers);
    }

    public StrategyOptions withNonLinearArithmetic(String v) {
        return new StrategyOptions(maxSteps, timeoutMillis, v, quantifiers, splitting, triggers);
    }

    public StrategyOptions withQuantifiers(String v) {
        return new StrategyOptions(maxSteps, timeoutMillis, nonLinearArithmetic, v, splitting,
            triggers);
    }

    public StrategyOptions withSplitting(String v) {
        return new StrategyOptions(maxSteps, timeoutMillis, nonLinearArithmetic, quantifiers, v,
            triggers);
    }

    public StrategyOptions withTriggers(String v) {
        return new StrategyOptions(maxSteps, timeoutMillis, nonLinearArithmetic, quantifiers,
            splitting, v);
    }
}
