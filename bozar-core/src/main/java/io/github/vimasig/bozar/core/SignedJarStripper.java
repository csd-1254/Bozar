package io.github.vimasig.bozar.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Removes JAR signature files from an already-written output JAR.
 *
 * <p>Policy (docs/08 section 4): bytecode rewriting invalidates JAR signatures, and a
 * stale {@code META-INF/*.SF} makes the JVM reject the JAR outright with
 * "Invalid signature file digest for Manifest main attributes". The safe default is
 * therefore to emit no signature files rather than emit broken ones. Re-signing is left
 * to a dedicated signing step, which is not implemented here.
 *
 * <p>Only the {@code .SF}/{@code .RSA}/{@code .DSA}/{@code .EC} files are removed. Digest
 * attributes left in the manifest are inert without a signature file.
 */
final class SignedJarStripper {

    private SignedJarStripper() {
    }

    static boolean strip(Path jar) throws IOException {
        final Path stripped = jar.resolveSibling(jar.getFileName() + ".unsigned");
        boolean removedAny = false;

        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(stripped))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (isSignatureFile(entry.getName())) {
                    removedAny = true;
                    continue;
                }
                copyEntry(entry, in, out);
            }
        }

        if (!removedAny) {
            Files.deleteIfExists(stripped);
            return false;
        }
        Files.move(stripped, jar, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    static boolean isSignatureFile(String name) {
        final String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/") || upper.indexOf('/', "META-INF/".length()) > 0) {
            return false;
        }
        return upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    private static void copyEntry(ZipEntry entry, InputStream in, ZipOutputStream out)
            throws IOException {
        final ZipEntry copy = new ZipEntry(entry.getName());
        copy.setMethod(entry.getMethod());
        copy.setTime(entry.getTime());
        if (entry.getMethod() == ZipEntry.STORED) {
            // Stored entries carry their size and CRC in the local header; the bytes are
            // unchanged, so the original values still apply.
            copy.setSize(entry.getSize());
            copy.setCompressedSize(entry.getCompressedSize());
            copy.setCrc(entry.getCrc());
        }
        out.putNextEntry(copy);
        in.transferTo(out);
        out.closeEntry();
    }
}
