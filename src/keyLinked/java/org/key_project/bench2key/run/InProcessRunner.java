package org.key_project.bench2key.run;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import de.uka.ilkd.key.control.DefaultUserInterfaceControl;
import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.Statistics;
import de.uka.ilkd.key.prover.impl.ParallelProver;

/**
 * Runs proofs in this JVM, through KeY's own API.
 *
 * Faster than starting a JVM per proof, and KeY's figures come straight off the proof rather than
 * out of a file. The cost is that a proof which exhausts the heap takes this process with it, so a
 * long batch is better served by {@link SubprocessRunner}.
 */
public final class InProcessRunner implements KeyRunner {

    private final int threads;

    public InProcessRunner() {
        this(1);
    }

    /** @param threads worker threads for KeY's own proof search; 1 leaves it single threaded */
    public InProcessRunner(int threads) {
        this.threads = threads;
        // KeY reads these before each run. Setting the properties rather than the settings object
        // is deliberate: changing the settings would be written back to the user's settings file.
        if (threads > 1) {
            System.setProperty(ParallelProver.PARALLEL_PROPERTY, "true");
            System.setProperty(ParallelProver.THREADS_PROPERTY, String.valueOf(threads));
        } else {
            System.setProperty(ParallelProver.PARALLEL_PROPERTY, "false");
        }
    }

    @Override
    public String describe() {
        return threads > 1 ? "in this JVM, " + threads + " cores" : "in this JVM, single core";
    }

    @Override
    public ProofOutcome prove(Path keyFile, int timeoutMillis) {
        long started = System.currentTimeMillis();
        KeYEnvironment<DefaultUserInterfaceControl> env = null;
        try {
            env = KeYEnvironment.load(keyFile);
            Proof proof = env.getLoadedProof();
            if (proof == null) {
                return ProofOutcome.error(keyFile, System.currentTimeMillis() - started,
                    "KeY loaded the file but produced no proof obligation");
            }
            proof.getSettings().getStrategySettings().setTimeout(timeoutMillis);
            env.getProofControl().startAndWaitForAutoMode(proof);

            int openGoals = proof.openGoals().size();
            long millis = System.currentTimeMillis() - started;
            ProofOutcome.Result result = proof.closed()
                ? ProofOutcome.Result.PROVED
                : ProofOutcome.Result.OPEN;
            return new ProofOutcome(keyFile, result, openGoals, millis,
                figures(proof.getStatistics(), openGoals), null, "");
        } catch (Exception e) {
            return ProofOutcome.error(keyFile, System.currentTimeMillis() - started,
                e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (env != null) {
                // Each proof holds a whole environment; releasing it keeps a batch from growing.
                env.dispose();
            }
        }
    }

    /** The same figures the console interface writes to its statistics file, in the same order. */
    private static Map<String, String> figures(Statistics s, int openGoals) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("open goals", String.valueOf(openGoals));
        out.put("Nodes", String.valueOf(s.nodes));
        out.put("Branches", String.valueOf(s.branches));
        out.put("Interactive steps", String.valueOf(s.interactiveSteps));
        out.put("Symbolic execution steps", String.valueOf(s.symbExApps));
        out.put("Automode time", s.autoModeTimeInMillis + "ms");
        out.put("Quantifier instantiations", String.valueOf(s.quantifierInstantiations));
        out.put("One-step Simplifier apps", String.valueOf(s.ossApps));
        out.put("SMT solver apps", String.valueOf(s.smtSolverApps));
        out.put("Merge Rule apps", String.valueOf(s.mergeRuleApps));
        out.put("Total rule apps", String.valueOf(s.totalRuleApps));
        return out;
    }

    @Override
    public void openInKeyGui(Path file) {
        // KeY's window has to be built on the event thread, and it loads asynchronously from there.
        javax.swing.SwingUtilities.invokeLater(() -> {
            de.uka.ilkd.key.gui.MainWindow window = de.uka.ilkd.key.gui.MainWindow.getInstance();
            window.setVisible(true);
            window.loadProblem(file);
        });
    }

    @Override
    public void close() {
        // Nothing outlives a call to prove.
    }
}
