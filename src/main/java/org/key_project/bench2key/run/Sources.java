package org.key_project.bench2key.run;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * A collection of problems, whether it is a directory or an archive.
 *
 * Extracted, the TPTP library is about ten gigabytes, which is a lot to keep on disk to read a few
 * kilobytes at a time. A zip of it is nearer one, and Java can present one as a file system, so
 * everything downstream goes on working with {@link Path} and never learns where the bytes came
 * from: walking the collection, reading a problem's header, and resolving the {@code include}
 * directives that reach the axiom files all behave the same either way.
 *
 * The archive stays open for as long as its paths are in use, which is why this is closeable: a
 * path into a closed archive cannot be read.
 *
 * A zip is used rather than the {@code .tgz} the library is published as because a zip can be read
 * at any offset. Reading one file out of a gzipped tar means decompressing everything before it, so
 * picking a single problem from one would cost a pass over the whole archive.
 */
public final class Sources implements AutoCloseable {

    private final Path root;
    private final FileSystem archive;

    private Sources(Path root, FileSystem archive) {
        this.root = root;
        this.archive = archive;
    }

    /** Where to start walking: a directory, or the inside of an archive. */
    public Path root() {
        return root;
    }

    /** Whether these problems live in an archive, and so cannot be written back to. */
    public boolean archived() {
        return archive != null;
    }

    /** Whether a path names something this can open as an archive. */
    public static boolean isArchive(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        return name.endsWith(".zip") || name.endsWith(".jar");
    }

    /**
     * Opens a directory, an archive, or a single problem file.
     *
     * An archive holding one directory at its top, which is how the TPTP library zips up, is
     * entered, so that pointing at the archive behaves the same as pointing at the directory it
     * was made from.
     */
    public static Sources open(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return new Sources(path, null);
        }
        if (!isArchive(path)) {
            return new Sources(path, null);
        }
        FileSystem fs = FileSystems.newFileSystem(path, (ClassLoader) null);
        try {
            return new Sources(entryPoint(fs), fs);
        } catch (IOException | RuntimeException e) {
            fs.close();
            throw e;
        }
    }

    private static Path entryPoint(FileSystem fs) throws IOException {
        Path top = fs.getRootDirectories().iterator().next();
        try (Stream<Path> entries = Files.list(top)) {
            List<Path> found = entries.toList();
            if (found.size() == 1 && Files.isDirectory(found.get(0))) {
                return found.get(0);
            }
        }
        return top;
    }

    @Override
    public void close() throws IOException {
        if (archive != null) {
            archive.close();
        }
    }
}
