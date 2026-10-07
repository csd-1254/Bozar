package io.github.vimasig.bozar.obfuscator.utils.model;

public enum BozarCategory {
    STABLE("Stable obfuscation options. Most options are irreversible.\nA good way to protect & speed up your application."),
    ADVANCED("Advanced obfuscation options. Reversible.\nPowerful protection against newbies."),
    WATERMARK("Different ways to implement watermark to your application.");

    private final String description;

    BozarCategory(String description) {
        this.description = description;
    }

    public String getDescription() {
        return this.description;
    }
}
