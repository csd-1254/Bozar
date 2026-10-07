package io.github.vimasig.bozar.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signed-JAR policy (docs/08 section 4). A stale signature file makes the JVM reject the
 * whole JAR, so the engine must emit none rather than emit broken ones.
 */
class SignedJarStripperTest {

    @TempDir
    Path dir;

    @Test
    void removesSignatureFilesAndKeepsEverythingElse() throws Exception {
        final Path jar = jar("META-INF/MANIFEST.MF", "META-INF/PLUGIN.SF", "META-INF/PLUGIN.RSA",
                "com/example/Hello.class", "plugin.yml", "META-INF/services/com.example.Spi");

        assertTrue(SignedJarStripper.strip(jar), "strip must report that it removed something");

        final List<String> remaining = entries(jar);
        assertEquals(List.of("META-INF/MANIFEST.MF", "com/example/Hello.class", "plugin.yml",
                        "META-INF/services/com.example.Spi"),
                remaining,
                "only signature files may be removed");
    }

    @Test
    void unsignedJarIsLeftAlone() throws Exception {
        final Path jar = jar("META-INF/MANIFEST.MF", "com/example/Hello.class");
        final byte[] before = Files.readAllBytes(jar);

        assertFalse(SignedJarStripper.strip(jar), "strip must report no change");

        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(jar)),
                "an unsigned JAR must be byte-identical after the strip pass");
    }

    @Test
    void nestedAndSimilarlyNamedFilesAreNotTreatedAsSignatures() throws Exception {
        assertFalse(SignedJarStripper.isSignatureFile("META-INF/foo/PLUGIN.SF"),
                "signature files live directly under META-INF");
        assertFalse(SignedJarStripper.isSignatureFile("com/example/My.SF"),
                "only META-INF signature files count");
        assertFalse(SignedJarStripper.isSignatureFile("META-INF/MANIFEST.MF"));
        assertTrue(SignedJarStripper.isSignatureFile("META-INF/PLUGIN.SF"));
        assertTrue(SignedJarStripper.isSignatureFile("meta-inf/plugin.dsa"),
                "signature detection must be case-insensitive");
    }

    private Path jar(String... entries) throws IOException {
        final Path jar = dir.resolve("signed.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : entries) {
                out.putNextEntry(new JarEntry(name));
                out.write(("content of " + name).getBytes());
                out.closeEntry();
            }
        }
        return jar;
    }

    private static List<String> entries(Path jar) throws IOException {
        final List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
