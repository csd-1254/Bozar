package io.github.vimasig.bozar.obfuscator.utils;

import io.github.vimasig.bozar.obfuscator.Bozar;
import io.github.vimasig.bozar.obfuscator.transformer.impl.CrasherTransformer;
import io.github.vimasig.bozar.obfuscator.utils.ASMUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BozarClassVerifier {

    public static boolean verify(Bozar bozar, Path path, ClassLoader parent) throws IOException {
        return verifyReport(bozar, path, parent).isOk();
    }

    /**
     * Upstream-only entry point kept for callers that want the bare boolean.
     *
     * <p>{@link #verifyReport} exists because this loop reports the offending class and the
     * ASM failure to {@link Bozar#err} — i.e. straight to {@code System.err} — so a caller
     * that only receives {@code false} cannot build an actionable error. docs/03 requires a
     * failed verification to identify the class, the stage, and the exception.
     */
    public static VerificationReport verifyReport(Bozar bozar, Path path, ClassLoader parent) throws IOException {
        // Closing releases the output JAR; leaving the loader open keeps the file locked for
        // the life of the host JVM, which breaks Gradle TestKit temp-dir cleanup.
        try (URLClassLoader classLoader =
                     new URLClassLoader(new URL[] { path.toFile().toURI().toURL() }, parent)) {
            var classes = new ArrayList<byte[]>();

            // Load JAR
            try (var jarInputStream = new ZipInputStream(Files.newInputStream(path))) {
                ZipEntry zipEntry;
                while ((zipEntry = jarInputStream.getNextEntry()) != null) {
                    if (zipEntry.getName().endsWith(".class")) {
                        classes.add(StreamUtils.readAll(jarInputStream));
                    }
                }
            }

            // Loop classes of loaded JAR
            final List<String> failures = new ArrayList<>();
            for (byte[] classBytes : classes) {
                ClassReader reader = new ClassReader(classBytes);
                ClassNode classNode = new ClassNode();
                reader.accept(classNode, 0);

                // Excluded classes are intentionally copied through unchanged, so do not verify them here.
                if (bozar.isExcluded(null, ASMUtils.getName(classNode)))
                    continue;

                // Skip watermark class because we know it's already invalid
                if(classNode.methods.stream().anyMatch(methodNode -> methodNode.name.equals("\u0001") && methodNode.desc.equals("(\u0001/)L\u0001/;")))
                    continue;
                // Skip crasher class
                if(classNode.name.startsWith(CrasherTransformer.PACKAGE_NAME + CrasherTransformer.REPEAT_BASE.repeat(10)))
                    continue;

                try {
                    StringWriter stringWriter = new StringWriter();
                    PrintWriter printWriter = new PrintWriter(stringWriter);
                    CheckClassAdapter.verify(reader, classLoader, false, printWriter);
                    if (!stringWriter.toString().isEmpty()) {
                        failures.add(classNode.name + ": " + firstLine(stringWriter.toString()));
                        bozar.err("Cannot verify class: %s", classNode.name);
                        bozar.err(stringWriter.toString());
                    }
                } catch (Throwable t) {
                    failures.add(classNode.name + ": " + describe(t));
                    bozar.err("Cannot verify class: %s", classNode.name);
                    t.printStackTrace();
                }
            }
            return new VerificationReport(failures);
        }
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse(text.trim());
    }

    /** Root-cause one-liner: the top exception is usually an ASM wrapper. */
    private static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        final String detail = root.getMessage();
        return detail == null || detail.isBlank()
                ? root.getClass().getName()
                : root.getClass().getName() + ": " + firstLine(detail);
    }

    /** Outcome of a verification pass. {@code getFailures()} is one entry per offending class. */
    public static final class VerificationReport {

        private final List<String> failures;

        VerificationReport(List<String> failures) {
            this.failures = List.copyOf(failures);
        }

        public boolean isOk() {
            return failures.isEmpty();
        }

        public List<String> getFailures() {
            return failures;
        }
    }
}
