package io.github.vimasig.bozar.core;

import io.github.vimasig.bozar.obfuscator.Bozar;
import io.github.vimasig.bozar.obfuscator.transformer.ClassTransformer;
import io.github.vimasig.bozar.obfuscator.transformer.TransformManager;
import io.github.vimasig.bozar.obfuscator.transformer.impl.renamer.ClassRenamerTransformer;
import io.github.vimasig.bozar.obfuscator.transformer.impl.renamer.FieldRenamerTransformer;
import io.github.vimasig.bozar.obfuscator.transformer.impl.renamer.MethodRenamerTransformer;
import io.github.vimasig.bozar.obfuscator.utils.BozarClassVerifier;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Plain-Java obfuscation facade. Owns no Gradle type and no logging type of its own;
 * callers supply logging by wrapping the returned result in their own task logic.
 *
 * <p>Wraps the upstream {@link Bozar} engine unchanged. {@link Bozar#run()} swallows
 * {@link IOException}, so this facade treats three things as hard failures:
 * <ol>
 *   <li>the output JAR does not exist after the run,</li>
 *   <li>the output holds fewer class entries than the input,</li>
 *   <li>{@link BozarClassVerifier} reports invalid classes.</li>
 * </ol>
 * This is the deviation that replaces upstream's {@code printStackTrace()} +
 * "Invalid classes present" warning with a typed error, as required by the
 * "never silently produce a broken JAR" contract.
 */
public final class ObfuscationEngine {

    private ObfuscationEngine() {
    }

    public static ObfuscationResult execute(ObfuscationRequest request) {
        final Path input = request.getInputJar();
        final Path output = request.getOutputJar();

        if (!Files.isRegularFile(input)) {
            throw new ObfuscationException("Input JAR does not exist or is not a regular file: " + input);
        }

        requireWatermarkTexts(request.getOptions());

        // Bozar adds the input JAR to its own classloader, so only pass the extra libraries.
        final List<String> libraries = new ArrayList<>(request.getClasspath());

        final BozarConfig config = new BozarConfig(
                input.toString(), output.toString(), request.getExclude(), libraries, request.getOptions());

        final Bozar bozar = new Bozar(config);

        // Seed is a task input when caching is on; must be set before the run and cleared
        // after, even on failure, so it cannot leak into a later execution in the same worker.
        Randoms.setSeed(request.getSeed());
        try {
            bozar.run();
        } catch (ObfuscationException e) {
            throw e;
        } catch (RuntimeException e) {
            // The engine reports input problems as unchecked exceptions ("Cannot find input",
            // "Received input does not look like a proper JAR file", "Unsupported file
            // extension"). Wrapping keeps the caller's failure handling in one type and puts
            // the offending path in the message, which the raw exception does not.
            throw new ObfuscationException(
                    "Obfuscation failed for input " + input + ": " + e.getMessage(), e);
        } finally {
            Randoms.setSeed(null);
        }

        if (!Files.isRegularFile(output)) {
            throw new ObfuscationException(
                    "Obfuscation completed without producing an output JAR at " + output
                            + ". The engine swallowed an I/O failure; rerun with verbose engine logging to inspect it.");
        }

        // The engine drops a class it cannot process instead of failing: Bozar.run() catches
        // the Throwable from classNode.accept, logs it, and continues, so the class is simply
        // absent from the output. A verification pass over the surviving classes then reports
        // success on an artifact that no longer contains everything the input did.
        checkNoClassesWereDropped(input, output,
                expectedOutputClassEntries(input, bozar));

        // Signature files copied from the input are invalid once bytecode changed; see
        // SignedJarStripper for the policy. Done before verification so the artifact that
        // gets verified is the artifact that gets published.
        try {
            SignedJarStripper.strip(output);
        } catch (IOException e) {
            throw new ObfuscationException(
                    "Could not remove invalid JAR signature files from " + output + ": " + e.getMessage(), e);
        }

        if (request.isVerify()) {
            throwIfVerificationFailed(bozar, output, libraries);
        }

        if (request.getMappingOutput() != null) {
            writeMapping(request.getMappingOutput(), bozar);
        }

        // The engine's own loader holds the input JAR open for the rest of the host JVM;
        // release it now so the input file is not locked (Gradle TestKit temp-dir cleanup).
        try {
            if (bozar.getClassLoader() instanceof URLClassLoader classLoader) {
                classLoader.close();
            }
        } catch (IOException ignored) {
            // Closing is best effort: the run has already produced and verified the output.
        }

        return new ObfuscationResult(input, output, request.getMappingOutput(),
                input.toFile().length(), output.toFile().length());
    }

    /**
     * Rejects a watermark that is enabled without its text.
     *
     * <p>Upstream reads the text straight from {@code WatermarkOptions} while declaring the
     * default in the transformer's {@code EnableType} — so through the GUI (which seeds the
     * text field from the declared default) it is never null, but through a programmatic
     * request it is. {@code DummyClassTransformer} and {@code TextInsideClassTransformer}
     * then fail with a bare {@code String.hashCode()} NPE, and {@code ZipCommentTransformer}
     * silently emits nothing. All three become one clear error instead.
     */
    private static void requireWatermarkTexts(BozarConfig.BozarOptions options) {
        final BozarConfig.BozarOptions.WatermarkOptions watermark = options.getWatermarkOptions();
        if (watermark == null) return;

        if (watermark.isDummyClass() && isBlank(watermark.getDummyClassText())) {
            throw new ObfuscationException(
                    "Watermark 'dummyClass' is enabled but no dummyClassText was supplied. "
                            + "The engine cannot generate the dummy class without it.");
        }
        if (watermark.isTextInsideClass() && isBlank(watermark.getTextInsideClassText())) {
            throw new ObfuscationException(
                    "Watermark 'textInsideClass' is enabled but no textInsideClassText was supplied.");
        }
        // No ldcPop check: that watermark's declared default text is the empty string.
        if (watermark.isZipComment() && isBlank(watermark.getZipCommentText())) {
            throw new ObfuscationException(
                    "Watermark 'zipComment' is enabled but no zipCommentText was supplied.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Verifies and turns any failing class into a typed failure carrying the class name and
     * the ASM-level cause.
     *
     * <p>Upstream's {@code Bozar.run()} discards the verification boolean and only prints
     * {@code "Invalid classes present"}; {@link BozarClassVerifier} itself reports the
     * offending class and the exception to {@code System.err}. Neither reaches a caller.
     * docs/03 requires a failed verification to identify the class, the stage, and the
     * exception, so they are carried in the exception message here.
     */
    private static void throwIfVerificationFailed(Bozar bozar, Path output, List<String> libraries) {
        final BozarClassVerifier.VerificationReport report;
        try (URLClassLoader classLoader = buildClassLoader(libraries)) {
            report = BozarClassVerifier.verifyReport(bozar, output, classLoader);
        } catch (IOException e) {
            throw new ObfuscationException("Could not read output JAR for verification: " + e.getMessage(), e);
        }
        if (report.isOk()) return;

        final String detail = String.join("; ", report.getFailures());
        throw new ObfuscationException(
                "Bytecode verification failed for output JAR " + output
                        + " after the transformation stage. " + report.getFailures().size()
                        + " class" + (report.getFailures().size() == 1 ? "" : "es")
                        + " did not pass ASM structural checks: " + detail
                        + ". The JAR was written but must not be published.");
    }

    /**
     * Fails when the output is missing any class the input contained.
     *
     * <p>This is the check that catches the engine's silent class drops, which verification
     * alone cannot: once a class is missing it is simply absent from the list the verifier
     * walks, so verification passes on a truncated JAR.
     *
     * <p>Names are compared, not counts. Renaming changes a class's entry name, but the
     * change is recorded in {@link ClassRenamerTransformer} and is a deterministic function
     * of the input name, so every input class has one expected output entry name. A count
     * comparison cannot express that: it only proves a lower bound, and the dummy-class and
     * crasher watermarks each add one synthetic class, so on any profile with watermarks on
     * a dropped class can be offset by an added one and the counts come out even. Measured
     * before this change: an input of two classes, one unparseable, with the dummy-class
     * watermark enabled, produced a verified output of two entries and no failure, with the
     * dropped class absent.
     *
     * <p>The comparison is a containment ({@code expected ⊆ actual}), not an equality, so
     * the synthetic watermark classes and any other additions are permitted. Nothing in the
     * engine removes a non-excluded input class by design, so any shortfall is a swallowed
     * failure.
     */
    private static void checkNoClassesWereDropped(Path input, Path output, Set<String> expected) {
        final Set<String> actual = classEntryNames(output);
        final Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw new ObfuscationException(
                    "Obfuscation produced " + actual.size() + " class entr" + (actual.size() == 1 ? "y" : "ies")
                            + " for output JAR " + output + " but is missing " + missing.size()
                            + (missing.size() == 1 ? " class it had: " : " classes it had: ") + missing
                            + ". They were dropped because the engine failed to process them, "
                            + "so the output is unusable. The engine logged the failing class to stderr.");
        }
    }

    /**
     * The output entry name each input class should appear under, applying the rename table
     * the engine built for this run. Excluded classes are not in the table and are written
     * through unchanged, so they keep their input name.
     */
    private static Set<String> expectedOutputClassEntries(Path input, Bozar bozar) {
        final Set<String> expected = new TreeSet<>();
        for (String entry : classEntryNames(input)) {
            final String internal = entry.substring(0, entry.length() - ".class".length());
            expected.add(renamedClass(bozar, internal) + ".class");
        }
        return expected;
    }

    private static String renamedClass(Bozar bozar, String internalName) {
        if (bozar.getTransformHandler() == null) return internalName;
        for (ClassTransformer transformer : bozar.getTransformHandler().getClassTransformers()) {
            if (transformer instanceof ClassRenamerTransformer renamer) {
                return renamer.getMap().getOrDefault(internalName, internalName);
            }
        }
        return internalName;
    }

    private static Set<String> classEntryNames(Path jar) {
        final Set<String> names = new TreeSet<>();
        try (var in = new JarInputStream(Files.newInputStream(jar))) {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                if (entry.getName().endsWith(".class")) names.add(entry.getName());
            }
        } catch (IOException e) {
            throw new ObfuscationException("Could not read " + jar + " to check its class entries: " + e.getMessage(), e);
        }
        return names;
    }

    private static URLClassLoader buildClassLoader(List<String> libraries) {
        final List<URL> urls = new ArrayList<>();
        for (String library : libraries) {
            final File file = new File(library);
            if (file.isFile()) {
                try {
                    urls.add(file.toURI().toURL());
                } catch (Exception e) {
                    throw new ObfuscationException("Could not load library " + library + ": " + e.getMessage(), e);
                }
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ObfuscationEngine.class.getClassLoader());
    }

    /**
     * Writes {@code old -> new} rename mappings to a properties file. Keys are the raw
     * map entries from the engine's three renamers:
     * <ul>
     *   <li>{@code class <internalName> <newInternalName>} — ClassRenamerTransformer</li>
     *   <li>{@code field <internalName>.<fieldName> <newFieldName>} — FieldRenamerTransformer</li>
     *   <li>{@code method <internalName>.<methodName><desc> <newName><desc>} — MethodRenamerTransformer</li>
     * </ul>
     * Splitting by transformer type and sorting each block keeps the file byte-stable
     * for a given seed. The raw form is intentionally not dotted-qualified: the engine
     * registers internal names, and converting them would be lossy for method descriptors.
     */
    private static void writeMapping(Path mappingOutput, Bozar bozar) {
        final Map<String, String> classMap = new TreeMap<>();
        final Map<String, String> fieldMap = new TreeMap<>();
        final Map<String, String> methodMap = new TreeMap<>();

        final List<ClassTransformer> transformers = bozar.getTransformHandler().getClassTransformers();
        for (ClassTransformer transformer : transformers) {
            if (ClassRenamerTransformer.class.isInstance(transformer)) classMap.putAll(((ClassRenamerTransformer) transformer).getMap());
            if (FieldRenamerTransformer.class.isInstance(transformer)) fieldMap.putAll(((FieldRenamerTransformer) transformer).getMap());
            if (MethodRenamerTransformer.class.isInstance(transformer)) methodMap.putAll(((MethodRenamerTransformer) transformer).getMap());
        }

        if (classMap.isEmpty() && fieldMap.isEmpty() && methodMap.isEmpty()) {
            return;
        }

        try {
            final Path parent = mappingOutput.getParent();
            if (parent != null) Files.createDirectories(parent);

            final StringBuilder sb = new StringBuilder();
            sb.append("# Bozar rename mapping\n");
            appendBlock(sb, "class", classMap);
            appendBlock(sb, "field", fieldMap);
            appendBlock(sb, "method", methodMap);
            Files.writeString(mappingOutput, sb.toString());
        } catch (IOException e) {
            throw new ObfuscationException("Could not write mapping file " + mappingOutput + ": " + e.getMessage(), e);
        }
    }

    private static void appendBlock(StringBuilder sb, String label, Map<String, String> map) {
        for (Map.Entry<String, String> entry : map.entrySet()) {
            sb.append(label).append(' ').append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        }
    }

    /** Thrown for any obfuscation failure that should fail the calling build task. */
    public static final class ObfuscationException extends RuntimeException {
        public ObfuscationException(String message) {
            super(message);
        }

        public ObfuscationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
