package io.github.vimasig.bozar.obfuscator;

import io.github.vimasig.bozar.obfuscator.transformer.ClassTransformer;
import io.github.vimasig.bozar.obfuscator.transformer.TransformManager;
import io.github.vimasig.bozar.obfuscator.utils.ASMUtils;
import io.github.vimasig.bozar.obfuscator.utils.BozarClassVerifier;
import io.github.vimasig.bozar.obfuscator.utils.StreamUtils;
import io.github.vimasig.bozar.obfuscator.utils.StringUtils;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig;
import io.github.vimasig.bozar.obfuscator.utils.model.CustomClassWriter;
import io.github.vimasig.bozar.obfuscator.utils.model.ResourceWrapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Bozar implements Runnable {

    private final BozarConfig config;
    private final List<ClassNode> classes = new ArrayList<>();
    private final List<ResourceWrapper> resources = new ArrayList<>();
    private final List<ResourceWrapper> excludedEntries = new ArrayList<>();
    private ClassLoader classLoader;
    private TransformManager transformHandler;

    public Bozar(BozarConfig config) {
        this.config = config;
    }

    public BozarConfig getConfig() {
        return this.config;
    }

    public List<ClassNode> getClasses() {
        return this.classes;
    }

    public List<ResourceWrapper> getResources() {
        return this.resources;
    }

    public List<ResourceWrapper> getExcludedEntries() {
        return this.excludedEntries;
    }

    public ClassLoader getClassLoader() {
        return this.classLoader;
    }

    public TransformManager getTransformHandler() {
        return this.transformHandler;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public void setTransformHandler(TransformManager transformHandler) {
        this.transformHandler = transformHandler;
    }

    @Override
    public void run() {
        try {
            // Used to calculate time elapsed
            final long startTime = System.currentTimeMillis();

            // Input file checks
            if(!this.config.getInput().exists())
                throw new FileNotFoundException("Cannot find input");
            if(!this.config.getInput().isFile())
                throw new IllegalArgumentException("Received input is not a file");

            String inputExtension = this.config.getInput().getName().substring(this.config.getInput().getName().lastIndexOf(".") + 1).toLowerCase();
            int classEntryCount = 0;
            switch (inputExtension) {
                case "jar" -> {
                    // Read JAR input
                    log("Processing JAR input...");
                    try (var jarInputStream = new ZipInputStream(Files.newInputStream(this.config.getInput().toPath()))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = jarInputStream.getNextEntry()) != null) {
                            if (zipEntry.getName().endsWith(".class")) {
                                classEntryCount++;
                                if(classes.size() == Integer.MAX_VALUE)
                                    throw new IllegalArgumentException("Maximum class count exceeded");
                                byte[] classBytes = StreamUtils.readAll(jarInputStream);
                                ClassReader reader = new ClassReader(classBytes);
                                ClassNode classNode = new ClassNode();
                                reader.accept(classNode, 0);
                                if (!this.isExcluded(null, ASMUtils.getName(classNode))) {
                                    classes.add(classNode);
                                } else {
                                    excludedEntries.add(new ResourceWrapper(new ZipEntry(zipEntry.getName()), classBytes));
                                }
                            } else {
                                if(resources.size() == Integer.MAX_VALUE)
                                    throw new IllegalArgumentException("Maximum resource count exceeded");
                                byte[] resourceBytes = StreamUtils.readAll(jarInputStream);
                                if (!this.isExcluded(null, normalizeResourceName(zipEntry.getName()))) {
                                    resources.add(new ResourceWrapper(new ZipEntry(zipEntry.getName()), resourceBytes));
                                } else {
                                    excludedEntries.add(new ResourceWrapper(new ZipEntry(zipEntry.getName()), resourceBytes));
                                }
                            }
                        }
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported file extension: " + inputExtension);
            }
            
            // Empty/corrupted file check
            if(classEntryCount == 0 && resources.size() == 0)
                throw new IllegalArgumentException("Received input does not look like a proper JAR file");
            if(classEntryCount == 0)
                log("No class entries were found in input. Only resources will be processed.");
            if(classes.size() == 0)
                log("All class entries matched exclude rules and will be copied unchanged to output.");

            // Convert string library paths to URL array
            final var libs = this.getConfig().getLibraries();
            URL[] urls = new URL[libs.size() + 1];
            urls[libs.size()] = this.config.getInput().toURI().toURL();
            for (int i = 0; i < libs.size(); i++)
                urls[i] = new File(libs.get(i)).toURI().toURL();
            this.classLoader = new URLClassLoader(urls);

            // Transform
            log("Transforming...");
            this.transformHandler = new TransformManager(this);
            transformHandler.transformAll();

            // Write output
            log("Writing...");
            try (var out = new JarOutputStream(Files.newOutputStream(this.config.getOutput()))) {
                // Write resources
                resources.stream()
                        .filter(resourceWrapper -> !resourceWrapper.getZipEntry().isDirectory())
                        .filter(resourceWrapper -> resourceWrapper.getBytes() != null)
                        .forEach(resourceWrapper -> {
                    try {
                        out.putNextEntry(new JarEntry(resourceWrapper.getZipEntry().getName()));
                        StreamUtils.copy(new ByteArrayInputStream(resourceWrapper.getBytes()), out);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });

                // Write classes
                for(ClassNode classNode : this.classes) {
                    // Transform latest ASM output
                    if(!transformHandler.getClassTransformers().stream()
                            .filter(ClassTransformer::isEnabled)
                            .allMatch(classTransformer -> classTransformer.transformOutput(classNode)))
                        continue;

                    int flags = ClassWriter.COMPUTE_FRAMES;

                    // Skip frames if the class is excluded
                    if(this.isExcluded(null, ASMUtils.getName(classNode)))
                        flags = ClassWriter.COMPUTE_MAXS;

                    var classWriter = new CustomClassWriter(this, flags, this.classLoader);
                    var checkClassAdapter = new CheckClassAdapter(classWriter,true);

                    // for verification
                    classNode.methods.forEach(methodNode -> {
                        methodNode.maxStack += 10; methodNode.maxLocals += 10;
                    });

                    // Process class
                    try {
                        classNode.accept(checkClassAdapter);
                    } catch (Throwable t) {
                        err("Cannot process class: %s", classNode.name);
                        t.printStackTrace();
                        continue;
                    }

                    // Transform ClassWriter
                    transformHandler.getClassTransformers().stream()
                            .filter(ClassTransformer::isEnabled)
                            .forEach(classTransformer -> classTransformer.transformClassWriter(classWriter));

                    // Write class
                    try {
                        byte[] bytes = classWriter.toByteArray();
                        out.putNextEntry(new JarEntry(classNode.name + ".class"));
                        out.write(bytes);
                    } catch (IOException e) {
                        err("Cannot write class: %s" , classNode.name);
                        e.printStackTrace();
                    }
                }

                // Transform jar output
                transformHandler.getClassTransformers().stream()
                        .filter(ClassTransformer::isEnabled)
                        .forEach(classTransformer -> classTransformer.transformOutput(out));

                // Write excluded entries unchanged
                excludedEntries.stream()
                        .filter(resourceWrapper -> !resourceWrapper.getZipEntry().isDirectory())
                        .filter(resourceWrapper -> resourceWrapper.getBytes() != null)
                        .forEach(resourceWrapper -> {
                            try {
                                out.putNextEntry(new JarEntry(resourceWrapper.getZipEntry().getName()));
                                StreamUtils.copy(new ByteArrayInputStream(resourceWrapper.getBytes()), out);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
            }

            // Verify classes
            try {
                log("Verifying JAR...");
                if(!BozarClassVerifier.verify(this, this.config.getOutput(), this.classLoader))
                    err("Invalid classes present");
                else log("JAR verified successfully!");
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Elapsed time information
            final String timeElapsed = new DecimalFormat("##.###").format(((double)System.currentTimeMillis() - (double)startTime) / 1000D);
            log("Done. Took %ss", timeElapsed);

            // File size information
            final String oldSize = StringUtils.getConvertedSize(this.config.getInput().length());
            final String newSize = StringUtils.getConvertedSize(this.config.getOutput().toFile().length());
            log("File size changed from %s to %s", oldSize, newSize);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isExcluded(ClassTransformer classTransformer, final String str) {
        final String s = (str.contains("$")) ? str.substring(0, str.indexOf("$")) : str;
        return this.getConfig().getExclude().lines().anyMatch(line -> {
            line = line.trim();
            if(line.isEmpty()) return false;
            if(line.startsWith("#")) return false;

            // Detect target transformer
            String targetTransformer = null;
            if(line.contains(":")) {
                targetTransformer = line.split(":", 2)[0].trim();
                line = line.substring((targetTransformer + ":").length());
            }

            line = line.trim();
            if(line.isEmpty()) return false;

            if(targetTransformer != null && classTransformer == null) return false;
            if(targetTransformer != null && !classTransformer.getName().equals(targetTransformer)) return false;

            if(line.startsWith("**")) return s.endsWith(line.substring(2));
            else if(line.startsWith("*")) return s.endsWith(line.substring(1));

            if(line.endsWith("**"))
                return s.startsWith(line.substring(0, line.length() - 2));
            else if(line.endsWith("*"))
                return s.startsWith(line.substring(0, line.length() - 1))
                    && s.chars().filter(ch -> ch == '.').count() == line.chars().filter(ch -> ch == '.').count();
            else return line.equals(s);
        });
    }

    private static String normalizeResourceName(String resourceName) {
        return resourceName.replace('/', '.');
    }

    public void log(String format, Object... args) {
        System.out.println("[Bozar] " + String.format(format, args));
    }

    public void err(String format, Object... args) {
        System.err.println("[Bozar] [ERROR] " + String.format(format, args));
    }
}
