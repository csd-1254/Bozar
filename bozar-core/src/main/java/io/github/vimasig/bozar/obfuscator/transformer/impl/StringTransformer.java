package io.github.vimasig.bozar.obfuscator.transformer.impl;

import io.github.vimasig.bozar.obfuscator.Bozar;
import io.github.vimasig.bozar.obfuscator.transformer.ClassTransformer;
import io.github.vimasig.bozar.obfuscator.utils.ASMUtils;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarCategory;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class StringTransformer extends ClassTransformer {

    private static final int LIGHT_MAX_UTF8_BYTES = 128;

    public StringTransformer(Bozar bozar) {
        super(bozar, "String obfuscation", BozarCategory.ADVANCED);
    }

    @Override
    public void transformMethod(ClassNode classNode, MethodNode methodNode) {
        if (!ASMUtils.isMethodEligibleToModify(classNode, methodNode)) return;

        BozarConfig.BozarOptions.StringObfuscationOption option = this.getBozar().getConfig().getOptions().getStringObfuscation();
        if (option == BozarConfig.BozarOptions.StringObfuscationOption.OFF) return;

        List<LdcInsnNode> insnToReplace = new ArrayList<>();
        for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
            if (insn instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof String)
                insnToReplace.add(ldcInsn);
        }

        final int varIndex = methodNode.maxLocals + 1;
        for (LdcInsnNode ldcInsn : insnToReplace) {
            String str = (String) ldcInsn.cst;
            InsnList replacement = generateObfuscatedString(str, option, varIndex);

            if (replacement != null) {
                methodNode.instructions.insertBefore(ldcInsn, replacement);
                methodNode.instructions.remove(ldcInsn);
            }
        }
    }

    private InsnList generateObfuscatedString(String str, BozarConfig.BozarOptions.StringObfuscationOption option, int varIndex) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;

        if (length == 0) {
            InsnList insnList = new InsnList();
            insnList.add(new LdcInsnNode(""));
            return insnList;
        }

        if (option == BozarConfig.BozarOptions.StringObfuscationOption.LIGHT) {
            if (length > LIGHT_MAX_UTF8_BYTES) {
                return null;
            }
            return generateLightString(bytes, varIndex);
        }

        return generateHeavyString(bytes, varIndex);
    }

    private InsnList generateLightString(byte[] bytes, int varIndex) {
        InsnList insnList = new InsnList();

        insnList.add(ASMUtils.pushInt(bytes.length));
        insnList.add(new IntInsnNode(NEWARRAY, T_BYTE));
        insnList.add(new VarInsnNode(ASTORE, varIndex));

        for (int i = 0; i < bytes.length; i++) {
            int xorKey = random.nextInt(256);
            int encoded = (bytes[i] & 0xFF) ^ xorKey;

            insnList.add(new VarInsnNode(ALOAD, varIndex));
            insnList.add(ASMUtils.pushInt(i));
            insnList.add(ASMUtils.pushInt(encoded));
            insnList.add(ASMUtils.pushInt(xorKey));
            insnList.add(new InsnNode(IXOR));
            insnList.add(new InsnNode(I2B));
            insnList.add(new InsnNode(BASTORE));
        }

        addStringCtor(insnList, varIndex);
        return insnList;
    }

    private InsnList generateHeavyString(byte[] bytes, int varIndex) {
        InsnList insnList = new InsnList();

        insnList.add(ASMUtils.pushInt(bytes.length));
        insnList.add(new IntInsnNode(NEWARRAY, T_BYTE));
        insnList.add(new VarInsnNode(ASTORE, varIndex));

        for (int i = 0; i < bytes.length; i++) {
            int shiftAmount = random.nextInt(24) + 1;
            int randomValue = random.nextInt();
            int obfuscatedValue = (randomValue & ~(0xff << shiftAmount)) | ((bytes[i] & 0xFF) << shiftAmount);

            insnList.add(new VarInsnNode(ALOAD, varIndex));
            insnList.add(ASMUtils.pushInt(i));
            insnList.add(ASMUtils.pushInt(obfuscatedValue));
            insnList.add(ASMUtils.pushInt(shiftAmount));
            insnList.add(new InsnNode(IUSHR));
            insnList.add(new InsnNode(I2B));
            insnList.add(new InsnNode(BASTORE));
        }

        addStringCtor(insnList, varIndex);
        return insnList;
    }

    private static void addStringCtor(InsnList insnList, int varIndex) {
        insnList.add(new TypeInsnNode(NEW, "java/lang/String"));
        insnList.add(new InsnNode(DUP));
        insnList.add(new VarInsnNode(ALOAD, varIndex));
        insnList.add(new FieldInsnNode(GETSTATIC,
                "java/nio/charset/StandardCharsets",
                "UTF_8",
                "Ljava/nio/charset/Charset;"));
        insnList.add(new MethodInsnNode(INVOKESPECIAL,
                "java/lang/String",
                "<init>",
                "([BLjava/nio/charset/Charset;)V",
                false));
    }

    @Override
    public BozarConfig.EnableType getEnableType() {
        return new BozarConfig.EnableType(
                () -> ((List<?>) this.getEnableType().type()).contains(this.getBozar().getConfig().getOptions().getStringObfuscation()),
                new ArrayList<>(List.of(
                        BozarConfig.BozarOptions.StringObfuscationOption.LIGHT,
                        BozarConfig.BozarOptions.StringObfuscationOption.HEAVY
                ))
        );
    }
}