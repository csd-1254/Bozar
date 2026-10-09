package io.github.vimasig.bozar.obfuscator.transformer;

import org.objectweb.asm.Type;
import org.objectweb.asm.commons.SimpleRemapper;

import java.util.Map;

/**
 * A {@link SimpleRemapper} that also renames the method name baked into a lambda / method-reference
 * {@code invokedynamic} call site.
 *
 * <p>The JDK's {@code LambdaMetafactory} generates a hidden class that implements the functional
 * interface's single abstract method (SAM). The implemented method's name comes from the
 * {@code invokedynamic} <em>name</em>, which the compiler sets to the SAM name. When the renamer
 * renames that interface method but the call-site name is left untouched, the generated lambda
 * implements a method with the old name and the JVM throws {@link AbstractMethodError} at enable
 * time (e.g. {@code Receiver class ...$$Lambda does not define or inherit ... 'abstract ... mo(FT)'}).
 *
 * <p>{@link SimpleRemapper#mapInvokeDynamicMethodName(String, String)} does nothing useful here:
 * it looks the name up under an empty owner, so it never matches a {@code owner.name+desc} entry.
 * The functional-interface type is the <em>return type</em> of the call-site descriptor, which is
 * enough to recover the owner and find the registered rename. Non-lambda indys (e.g.
 * {@code StringConcatFactory}) never match an entry and are returned unchanged.
 */
public final class LambdaAwareRemapper extends SimpleRemapper {

    private final Map<String, String> mapping;

    public LambdaAwareRemapper(Map<String, String> mapping) {
        super(mapping);
        this.mapping = mapping;
    }

    @Override
    public String mapInvokeDynamicMethodName(final String name, final String descriptor) {
        final Type returnType = Type.getReturnType(descriptor);
        if (returnType.getSort() != Type.OBJECT) {
            return name;
        }
        // Map key format is owner + "." + name + methodDescriptor (see ASMUtils.getMethodMapKey).
        // The SAM descriptor is not available on the call site, so match on owner + "." + name and
        // require the next char to start a descriptor so "get" cannot swallow "getValue".
        final String prefix = returnType.getInternalName() + "." + name;
        for (Map.Entry<String, String> entry : this.mapping.entrySet()) {
            final String key = entry.getKey();
            if (key.length() > prefix.length()
                    && key.charAt(prefix.length()) == '('
                    && key.startsWith(prefix)) {
                return entry.getValue();
            }
        }
        return name;
    }
}
