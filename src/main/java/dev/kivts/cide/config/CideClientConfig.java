package dev.kivts.cide.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class CideClientConfig {
    public static final ModConfigSpec SPEC;

    // opacity of the editor. Default: 0.533
    public static final ModConfigSpec.DoubleValue OPACITY;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        OPACITY = b
            .comment("Opacity of the editor. Default: 0.533 - Good balance between both (well, its in the middle) ")
            .defineInRange("opacity", 0.533, 0.1, 1.0);

        SPEC = b.build();
    }

    private CideClientConfig() {}
}
