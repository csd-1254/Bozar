package io.github.vimasig.bozar.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

/**
 * Watermark sub-options, mirroring {@code BozarOptions.WatermarkOptions}. The boolean toggles
 * enable each watermark transformer; the text properties override the default
 * "Obfuscated with Bozar" string. Absent values inherit the selected {@code Profile}'s defaults.
 */
public class BozarWatermarkExtension {

    private final Property<Boolean> dummyClass;
    private final Property<Boolean> textInsideClass;
    private final Property<Boolean> ldcPop;
    private final Property<Boolean> zipComment;
    private final Property<String> dummyClassText;
    private final Property<String> textInsideClassText;
    private final Property<String> ldcPopText;
    private final Property<String> zipCommentText;

    public BozarWatermarkExtension(ObjectFactory objects) {
        this.dummyClass = objects.property(Boolean.class);
        this.textInsideClass = objects.property(Boolean.class);
        this.ldcPop = objects.property(Boolean.class);
        this.zipComment = objects.property(Boolean.class);
        this.dummyClassText = objects.property(String.class);
        this.textInsideClassText = objects.property(String.class);
        this.ldcPopText = objects.property(String.class);
        this.zipCommentText = objects.property(String.class);
    }

    public Property<Boolean> getDummyClass() { return dummyClass; }
    public Property<Boolean> getTextInsideClass() { return textInsideClass; }
    public Property<Boolean> getLdcPop() { return ldcPop; }
    public Property<Boolean> getZipComment() { return zipComment; }
    public Property<String> getDummyClassText() { return dummyClassText; }
    public Property<String> getTextInsideClassText() { return textInsideClassText; }
    public Property<String> getLdcPopText() { return ldcPopText; }
    public Property<String> getZipCommentText() { return zipCommentText; }

    public void dummyClass(boolean v) { dummyClass.set(v); }
    public void textInsideClass(boolean v) { textInsideClass.set(v); }
    public void ldcPop(boolean v) { ldcPop.set(v); }
    public void zipComment(boolean v) { zipComment.set(v); }
    public void dummyClassText(String v) { dummyClassText.set(v); }
    public void textInsideClassText(String v) { textInsideClassText.set(v); }
    public void ldcPopText(String v) { ldcPopText.set(v); }
    public void zipCommentText(String v) { zipCommentText.set(v); }

    public void setDummyClass(boolean v) { dummyClass.set(v); }
    public void setTextInsideClass(boolean v) { textInsideClass.set(v); }
    public void setLdcPop(boolean v) { ldcPop.set(v); }
    public void setZipComment(boolean v) { zipComment.set(v); }
    public void setDummyClassText(String v) { dummyClassText.set(v); }
    public void setTextInsideClassText(String v) { textInsideClassText.set(v); }
    public void setLdcPopText(String v) { ldcPopText.set(v); }
    public void setZipCommentText(String v) { zipCommentText.set(v); }
}
