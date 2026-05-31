package dev.kivts.cide.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

@SuppressWarnings("deprecation")
public final class CideServerConfig {
    public static final ModConfigSpec SPEC;

    // Access
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue ENABLE_WRITES;
    public static final ModConfigSpec.BooleanValue REQUIRE_OPERATOR;
    public static final ModConfigSpec.BooleanValue ALLOW_COMMAND_COMPUTERS;
    public static final ModConfigSpec.DoubleValue  MAX_USE_DISTANCE;

    // Computer type access
    public static final ModConfigSpec.BooleanValue BASIC_EDITOR;
    public static final ModConfigSpec.BooleanValue TABLET_EDITOR;

    public static final ModConfigSpec.IntValue MAX_LISTED_ENTRIES;
    public static final ModConfigSpec.IntValue RATE_LIMIT_RPS;

    // Path filtering
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DENIED_PATH_PREFIXES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_PATH_PREFIXES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("General").push("access");

        ENABLED = b
            .comment("Is CIDE enabled?")
            .define("enabled", true);

        ENABLE_WRITES = b
            .comment("Allow players to mutate files through CIDE.")
            .define("enableWrites", true);

        REQUIRE_OPERATOR = b
            .comment("Require OP to open CIDE.")
            .define("requireOperator", false);

        ALLOW_COMMAND_COMPUTERS = b
            .comment("Allow CIDE to open on command computers.")
            .define("allowCommandComputers", false);

        MAX_USE_DISTANCE = b
            .comment("Maximum distance (in blocks) a player can be from the computer to open CIDE, This mainly exists for turtles.")
            .defineInRange("maxUseDistance", 8.0, 1.0, 64.0);

        b.pop();

        b.comment("Computers").push("computerTypes");

        BASIC_EDITOR = b
            .comment("Does basic version of a pc have the editor or not? Turtles are treated as normal PCs.")
            .define("basicEditor", false);

        TABLET_EDITOR = b
            .comment("Do Tablets have the editor? Opened by shift clicking the air with one in hand.")
            .define("tabletEditor", false);

        b.pop();

        b.comment("Server Limits").push("limits");

        MAX_LISTED_ENTRIES = b
            .comment("Maximum number of files listed per directory.")
            .defineInRange("maxListedEntries", 256, 1, 10000);

        RATE_LIMIT_RPS = b
            .comment("Maximum network requests per second per player before rate-limiting kicks in. Probably dont change this. (nor do you really need to)")
            .defineInRange("rateLimitRequestsPerSecond", 200, 1, 10000);

        b.pop();

        b.comment("Path Whitelisting/blacklisting.").push("paths");

        DENIED_PATH_PREFIXES = b
            .comment("Paths that are always denied. Default: [\"rom\", \".\"] - dont forget about the normal CC interface - a file starting with a dot will be hidden in computer craft.")
            .defineListAllowEmpty("deniedPathPrefixes", List.of("rom", "."), s -> s instanceof String);

        ALLOWED_PATH_PREFIXES = b
            .comment("If non-empty, only paths matching these prefixes are accessible. Leave this field empty to allow any name - dont forget about the normal CC interface.")
            .defineListAllowEmpty("allowedPathPrefixes", List.of(), s -> s instanceof String);

        b.pop();

        SPEC = b.build();
    }

    private CideServerConfig() {}
}
