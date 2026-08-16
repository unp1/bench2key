package org.key_project.bench2key.run;

import java.nio.file.Path;

/**
 * One problem as the table shows it.
 *
 * @param source the problem file
 * @param category what it is grouped under: its SMT-LIB logic, or its TPTP domain
 * @param status the status it declares, such as {@code unsat} or {@code Theorem}
 * @param size the size of the source in bytes
 * @param provable whether that status leaves KeY a proof to find at all; a problem declared
 *        satisfiable has none, so a run that leaves it open has found nothing out
 */
public record Problem(Path source, String category, String status, long size, boolean provable) {

    public String name() {
        return source.getFileName().toString();
    }
}
