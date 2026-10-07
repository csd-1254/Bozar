package io.github.vimasig.bozar.obfuscator.analysis;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM visitor that extracts class hierarchy information: superclass and interfaces.
 */
public class HierarchyVisitor extends ClassVisitor {
    private String superName;
    private String[] interfaces = new String[0];

    public HierarchyVisitor() {
        super(Opcodes.ASM9);
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.superName = superName;
        this.interfaces = interfaces != null ? interfaces : new String[0];
    }

    public String getSuperName() {
        return superName;
    }

    public String[] getInterfaces() {
        return interfaces;
    }
}