package dev.kivts.cide.server;

import dan200.computercraft.api.lua.GenericSource;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.ILuaAPIFactory;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.GenericPeripheral;
import dan200.computercraft.api.peripheral.IDynamicPeripheral;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralType;
import dev.kivts.cide.net.payload.LuaManifestPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class CideLuaCatalog {
    private CideLuaCatalog() {}

    private static final Logger LOG = LoggerFactory.getLogger("CIDE/LuaCatalog");

    private static final String[] CORE_API_CLASSES = {
        "dan200.computercraft.core.apis.TermAPI",
        "dan200.computercraft.core.apis.RedstoneAPI",
        "dan200.computercraft.core.apis.FSAPI",
        "dan200.computercraft.core.apis.PeripheralAPI",
        "dan200.computercraft.core.apis.OSAPI",
        "dan200.computercraft.core.apis.HTTPAPI"
    };

    private static final Map<String, String> CORE_GLOBAL_NAMES = Map.of(
        "dan200.computercraft.core.apis.TermAPI",       "term",
        "dan200.computercraft.core.apis.RedstoneAPI",   "redstone",
        "dan200.computercraft.core.apis.FSAPI",         "fs",
        "dan200.computercraft.core.apis.PeripheralAPI", "peripheral",
        "dan200.computercraft.core.apis.OSAPI",         "os",
        "dan200.computercraft.core.apis.HTTPAPI",       "http"
    );

    private static final LuaManifestPayload EMPTY = new LuaManifestPayload(Set.of(), Map.of(), Map.of());

    private static volatile LuaManifestPayload cached;
    private static volatile CompletableFuture<LuaManifestPayload> future;

    public static synchronized void prewarm() {
        if (cached != null || future != null) return;
        CompletableFuture<Void> waitOn = luaResourceScan;
        CompletableFuture<LuaManifestPayload> ours = waitOn.thenApplyAsync(v -> {
            try { return build(); }
            catch (Throwable ignored) { return EMPTY; }
        });
        future = ours;
        ours.thenAccept(manifest -> {
            synchronized (CideLuaCatalog.class) {
                if (future == ours) cached = manifest;
            }
        });
    }

    public static LuaManifestPayload buildOrCached() {
        LuaManifestPayload snapshot = cached;
        if (snapshot != null) return snapshot;
        prewarm();
        try {
            LuaManifestPayload built = future.join();
            cached = built;
            return built;
        } catch (Throwable ignored) {
            return EMPTY;
        }
    }

    public static void sendTo(net.minecraft.server.level.ServerPlayer player) {
        LuaManifestPayload snapshot = cached;
        if (snapshot != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, snapshot);
            return;
        }
        prewarm();
        net.minecraft.server.MinecraftServer server =
            net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        future.whenComplete((manifest, error) -> {
            if (manifest == null) return;
            Runnable send = () -> {
                if (player.connection == null || player.hasDisconnected()) return;
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, manifest);
            };
            if (server != null) server.execute(send); else send.run();
        });
    }

    public static LuaManifestPayload build() {
        Set<String> globals = new LinkedHashSet<>();
        Map<String, List<String>> globalMembers = new LinkedHashMap<>();
        Map<String, List<String>> peripheralTypeMembers = new LinkedHashMap<>();

        scrapeCoreApis(globals, globalMembers);
        scrapeModGlobals(globals, globalMembers);
        scrapeGenericPeripherals(peripheralTypeMembers);
        scrapeClasspathPeripherals(peripheralTypeMembers);

        LuaManifestPayload base = new LuaManifestPayload(globals, globalMembers, peripheralTypeMembers);
        return overlayWithLuaResources(base);
    }

    public static LuaManifestPayload buildWithAttached(Map<String, IPeripheral> sideToPeripheral) {
        LuaManifestPayload base = buildOrCached();
        Map<String, List<String>> merged = new LinkedHashMap<>(base.peripheralTypeMembers());
        for (IPeripheral peripheral : sideToPeripheral.values()) {
            String type = safeGetType(peripheral);
            if (type == null) continue;
            List<String> methods = methodsFor(peripheral);
            if (methods.isEmpty()) continue;
            mergeMembers(merged, type, methods);
        }
        return new LuaManifestPayload(base.globals(), base.globalMembers(), merged);
    }

    private static volatile Set<String> luaResourceGlobals = Set.of();
    private static volatile Map<String, List<String>> luaResourceMembers = Map.of();
    private static volatile CompletableFuture<Void> luaResourceScan = CompletableFuture.completedFuture(null);

    public static final ResourceManagerReloadListener RELOAD_LISTENER = CideLuaCatalog::refreshLuaResources;

    public static void refreshLuaResources(ResourceManager resources) {
        Map<ResourceLocation, Resource> apiFiles;
        Map<ResourceLocation, Resource> moduleFiles;
        try {
            Map<ResourceLocation, Resource> allLua = resources.listResources(
                "lua", loc -> loc.getPath().endsWith(".lua"));
            apiFiles = new LinkedHashMap<>();
            moduleFiles = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, Resource> entry : allLua.entrySet()) {
                String path = entry.getKey().getPath();
                if (path.contains("/rom/apis/") || path.startsWith("rom/apis/")) {
                    apiFiles.put(entry.getKey(), entry.getValue());
                } else if (path.contains("/rom/modules/main/") || path.startsWith("rom/modules/main/")) {
                    moduleFiles.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (Throwable thrown) {
            LOG.warn("listResources failed: {}", thrown.toString());
            return;
        }

        CompletableFuture<Void> scan = CompletableFuture.runAsync(() -> {
            Set<String> globals = new LinkedHashSet<>();
            Map<String, List<String>> members = new LinkedHashMap<>();
            scanLuaApiFiles(apiFiles, globals, members);
            scanLuaModuleFiles(moduleFiles, members);
            LOG.info("Lua scan: {} apis + {} module files → {} globals, {} keys",
                apiFiles.size(), moduleFiles.size(), globals.size(), members.size());
            synchronized (CideLuaCatalog.class) {
                luaResourceGlobals = globals;
                luaResourceMembers = members;
                cached = null;
                future = null;
            }
        });
        synchronized (CideLuaCatalog.class) {
            luaResourceScan = scan;
            cached = null;
            future = null;
        }
    }

    private static LuaManifestPayload overlayWithLuaResources(LuaManifestPayload base) {
        Set<String> luaGlobals = luaResourceGlobals;
        Map<String, List<String>> luaMembers = luaResourceMembers;
        if (luaGlobals.isEmpty() && luaMembers.isEmpty()) return base;

        Set<String> globals = new LinkedHashSet<>(base.globals());
        globals.addAll(luaGlobals);
        Map<String, List<String>> globalMembers = new LinkedHashMap<>(base.globalMembers());
        for (Map.Entry<String, List<String>> entry : luaMembers.entrySet()) {
            mergeMembers(globalMembers, entry.getKey(), entry.getValue());
        }
        return new LuaManifestPayload(globals, globalMembers, base.peripheralTypeMembers());
    }


    private static void scrapeCoreApis(Set<String> globals, Map<String, List<String>> globalMembers) {
        for (String fqn : CORE_API_CLASSES) {
            Class<?> klass = tryLoad(fqn);
            if (klass == null) continue;
            String global = CORE_GLOBAL_NAMES.get(fqn);
            if (global == null) continue;
            List<String> methods = scanLuaFunctionMethods(klass);
            if (methods.isEmpty()) continue;
            globals.add(global);
            mergeMembers(globalMembers, global, methods);
        }
    }


    private static void scrapeModGlobals(Set<String> globals, Map<String, List<String>> globalMembers) {
        Collection<ILuaAPIFactory> factories = readApiFactories();
        if (factories.isEmpty()) return;

        StubComputerSystem stub = new StubComputerSystem();
        for (ILuaAPIFactory factory : factories) {
            ILuaAPI api;
            try {
                api = factory.create(stub);
            } catch (Throwable ignored) {
                continue;
            }
            if (api == null) continue;

            List<String> methods = scanLuaFunctionMethods(api.getClass());
            String[] names;
            try {
                names = api.getNames();
            } catch (Throwable ignored) {
                names = new String[0];
            }
            String moduleName;
            try {
                moduleName = api.getModuleName();
            } catch (Throwable ignored) {
                moduleName = null;
            }

            List<String> exposedAs = new ArrayList<>();
            if (names != null) for (String name : names) if (name != null && !name.isBlank()) exposedAs.add(name);
            if (moduleName != null && !moduleName.isBlank()) exposedAs.add(moduleName);

            for (String exposed : exposedAs) {
                globals.add(exposed);
                if (!methods.isEmpty()) mergeMembers(globalMembers, exposed, methods);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<ILuaAPIFactory> readApiFactories() {
        try {
            Class<?> registry = Class.forName("dan200.computercraft.impl.ApiFactories");
            Method getAll = registry.getMethod("getAll");
            Object result = getAll.invoke(null);
            return (Collection<ILuaAPIFactory>) result;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    // Pass C

    private static void scrapeGenericPeripherals(Map<String, List<String>> peripheralTypeMembers) {
        Collection<GenericSource> sources = readGenericSources();
        for (GenericSource source : sources) {
            List<String> methods = scanLuaFunctionMethods(source.getClass());
            if (methods.isEmpty()) continue;
            Set<String> types = typesFor(source);
            for (String type : types) mergeMembers(peripheralTypeMembers, type, methods);
        }
    }

    private static Set<String> typesFor(GenericSource source) {
        Set<String> out = new LinkedHashSet<>();
        if (source instanceof GenericPeripheral peripheral) {
            try {
                PeripheralType type = peripheral.getType();
                if (type != null) {
                    String primary = type.getPrimaryType();
                    if (primary != null && !primary.isBlank()) out.add(primary);
                    Set<String> additional = type.getAdditionalTypes();
                    if (additional != null) for (String t : additional) if (t != null && !t.isBlank()) out.add(t);
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }
    //what could possible go wrong by doing this right
    @SuppressWarnings("unchecked")
    private static Collection<GenericSource> readGenericSources() {
        try {
            Class<?> registry = Class.forName("dan200.computercraft.impl.GenericSources");
            Field sources = registry.getDeclaredField("sources");
            sources.setAccessible(true);
            return new ArrayList<>((Collection<GenericSource>) sources.get(null));
        } catch (Throwable ignored) {
            return List.of();
        }
    }



    public static List<String> methodsFor(IPeripheral peripheral) {
        if (peripheral instanceof IDynamicPeripheral dynamic) {
            try {
                String[] names = dynamic.getMethodNames();
                if (names != null) {
                    List<String> out = new ArrayList<>(names.length);
                    for (String name : names) if (name != null && !name.isBlank()) out.add(name);
                    return out;
                }
            } catch (Throwable ignored) {}
        }
        return scanLuaFunctionMethods(peripheral.getClass());
    }

    private static String safeGetType(IPeripheral peripheral) {
        try {
            return peripheral.getType();
        } catch (Throwable ignored) {
            return null;
        }
    }


    private static final String LUA_FUNCTION_DESC = "Ldan200/computercraft/api/lua/LuaFunction;";
    private static final String IPERIPHERAL_INTERNAL = "dan200/computercraft/api/peripheral/IPeripheral";
    private static final byte[] MARKER_LUA_FUNCTION =
        "dan200/computercraft/api/lua/LuaFunction".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MARKER_IPERIPHERAL =
        IPERIPHERAL_INTERNAL.getBytes(StandardCharsets.UTF_8);

    private static void scrapeClasspathPeripherals(Map<String, List<String>> peripheralTypeMembers) {
        java.util.List<? extends IModFileInfo> modFiles = ModList.get().getModFiles();
        LOG.info("Beginning to parse loaded mods for function names. Amount: {}", modFiles.size());
        int functionsBefore = countAllFunctions(peripheralTypeMembers);
        for (IModFileInfo info : modFiles) {
            try {
                scanModFile(info.getFile(), peripheralTypeMembers);
            } catch (Throwable ignored) {}
        }
        // some logging
        int functionsAfter = countAllFunctions(peripheralTypeMembers);
        LOG.info("Completed looking through mods for function mods, found {} functions!",
            functionsAfter - functionsBefore);
    }

    private static int countAllFunctions(Map<String, List<String>> members) {
        int total = 0;
        for (List<String> list : members.values()) if (list != null) total += list.size();
        return total;
    }

    private static void scanModFile(IModFile modFile, Map<String, List<String>> peripheralTypeMembers) throws IOException {
        Path root;
        try {
            root = modFile.getSecureJar().getRootPath();
        } catch (Throwable ignored) {
            return;
        }
        if (root == null || !Files.isDirectory(root)) return;

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> path.getFileName() != null
                    && path.getFileName().toString().endsWith(".class"))
                .forEach(path -> tryScanClassFile(path, peripheralTypeMembers));
        }
    }

    private static void tryScanClassFile(Path classFile, Map<String, List<String>> peripheralTypeMembers) {
        try {
            byte[] bytes = Files.readAllBytes(classFile);
            if (!containsMarker(bytes, MARKER_LUA_FUNCTION)) return;
            if (!containsMarker(bytes, MARKER_IPERIPHERAL)) return;
            PeripheralClassScanner scanner = new PeripheralClassScanner();
            new ClassReader(bytes).accept(scanner, ClassReader.SKIP_FRAMES);
            if (scanner.type == null || scanner.luaMethods.isEmpty()) return;
            mergeMembers(peripheralTypeMembers, scanner.type, scanner.luaMethods);
        } catch (Throwable ignored) {}
    }

    // Pass F: Lua resource scanner (rom/apis and rom/modules)

    private static final int LUA_FILE_MAX_BYTES = 512 * 1024;

    private static final Pattern LUA_TOP_FUNCTION =
        Pattern.compile("(?m)^[ \\t]*function\\s+([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern LUA_TOP_ASSIGN_FUNCTION =
        Pattern.compile("(?m)^[ \\t]*([A-Za-z_]\\w*)\\s*=\\s*function\\b");
    private static final Pattern LUA_TABLE_FUNCTION =
        Pattern.compile("(?m)^[ \\t]*function\\s+[A-Za-z_]\\w*\\.([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern LUA_TABLE_ASSIGN_FUNCTION =
        Pattern.compile("(?m)^[ \\t]*[A-Za-z_]\\w*\\.([A-Za-z_]\\w*)\\s*=\\s*function\\b");
    private static final Pattern LUA_DOC_FUNCTION =
        Pattern.compile("(?m)^---.*?@function\\s+([A-Za-z_]\\w*)");

    private static void scanLuaApiFiles(Map<ResourceLocation, Resource> files,
                                        Set<String> globals,
                                        Map<String, List<String>> globalMembers) {
        for (Map.Entry<ResourceLocation, Resource> entry : files.entrySet()) {
            String path = entry.getKey().getPath();
            int slash = path.lastIndexOf('/');
            String filename = slash >= 0 ? path.substring(slash + 1) : path;
            if (!filename.endsWith(".lua")) continue;
            String apiName = filename.substring(0, filename.length() - 4);
            if (apiName.isEmpty()) continue;

            List<String> functions = extractFunctionNames(readResource(entry.getValue()));
            if (functions.isEmpty()) continue;
            globals.add(apiName);
            mergeMembers(globalMembers, apiName, functions);
        }
    }

    private static void scanLuaModuleFiles(Map<ResourceLocation, Resource> files,
                                           Map<String, List<String>> members) {
        for (Map.Entry<ResourceLocation, Resource> entry : files.entrySet()) {
            String path = entry.getKey().getPath();
            int prefix = path.indexOf("lua/rom/modules/main/");
            if (prefix < 0) continue;
            String relative = path.substring(prefix + "lua/rom/modules/main/".length());
            if (!relative.endsWith(".lua")) continue;
            String withoutExt = relative.substring(0, relative.length() - 4);
            String dottedName = withoutExt.replace('/', '.');
            if (dottedName.isEmpty()) continue;

            List<String> functions = extractFunctionNames(readResource(entry.getValue()));
            if (functions.isEmpty()) continue;
            mergeMembers(members, dottedName, functions);
            mergeMembers(members, withoutExt, functions);
            int lastDot = dottedName.lastIndexOf('.');
            if (lastDot >= 0) mergeMembers(members, dottedName.substring(lastDot + 1), functions);
        }
    }

    private static String readResource(Resource resource) {
        try (java.io.InputStream in = resource.open()) {
            byte[] raw = in.readNBytes(LUA_FILE_MAX_BYTES);
            return new String(raw, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static List<String> extractFunctionNames(String source) {
        if (source.isEmpty()) return List.of();
        Set<String> names = new LinkedHashSet<>();
        addMatches(source, LUA_TOP_FUNCTION, names);
        addMatches(source, LUA_TOP_ASSIGN_FUNCTION, names);
        addMatches(source, LUA_TABLE_FUNCTION, names);
        addMatches(source, LUA_TABLE_ASSIGN_FUNCTION, names);
        addMatches(source, LUA_DOC_FUNCTION, names);
        return new ArrayList<>(names);
    }

    private static void addMatches(String source, Pattern pattern, Set<String> sink) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name != null && !name.isBlank()) sink.add(name);
        }
    }

    private static boolean containsMarker(byte[] haystack, byte[] needle) {
        int needleLen = needle.length;
        int limit = haystack.length - needleLen;
        byte first = needle[0];
        outer:
        for (int i = 0; i <= limit; i++) {
            if (haystack[i] != first) continue;
            for (int j = 1; j < needleLen; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static final class PeripheralClassScanner extends ClassVisitor {
        private final List<String> luaMethods = new ArrayList<>();
        private String type;
        private boolean isPeripheral;
        private String internalName;

        PeripheralClassScanner() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.internalName = name;
            if (interfaces != null) {
                for (String iface : interfaces) {
                    if (iface.equals(IPERIPHERAL_INTERNAL)) { isPeripheral = true; break; }
                }
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (!isPeripheral) return null;
            if (name.equals("getType") && descriptor.equals("()Ljava/lang/String;")) {
                return new GetTypeReader();
            }
            return new LuaFunctionMethodVisitor(name);
        }

        private final class LuaFunctionMethodVisitor extends MethodVisitor {
            private final String methodName;
            private String overrideName;
            private boolean isLuaFunction;

            LuaFunctionMethodVisitor(String methodName) {
                super(Opcodes.ASM9);
                this.methodName = methodName;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!descriptor.equals(LUA_FUNCTION_DESC)) return null;
                isLuaFunction = true;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String arrayName) {
                        if (!"value".equals(arrayName)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String n, Object value) {
                                if (value instanceof String string && !string.isBlank()) overrideName = string;
                            }
                        };
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (!isLuaFunction) return;
                luaMethods.add(overrideName != null ? overrideName : methodName);
            }
        }

        private final class GetTypeReader extends MethodVisitor {
            GetTypeReader() {
                super(Opcodes.ASM9);
            }

            @Override
            public void visitLdcInsn(Object value) {
                if (type == null && value instanceof String string && !string.isBlank()) type = string;
            }
        }
    }



    private static List<String> scanLuaFunctionMethods(Class<?> klass) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Method method : klass.getMethods()) {
            LuaFunction annotation = method.getAnnotation(LuaFunction.class);
            if (annotation == null) continue;
            if (Modifier.isStatic(method.getModifiers())) continue;
            String[] names = annotation.value();
            if (names.length == 0) {
                if (seen.add(method.getName())) out.add(method.getName());
            } else {
                for (String name : names) if (name != null && !name.isBlank() && seen.add(name)) out.add(name);
            }
        }
        return out;
    }

    // Helpers

    private static Class<?> tryLoad(String fqn) {
        try {
            return Class.forName(fqn);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void mergeMembers(Map<String, List<String>> target, String key, List<String> members) {
        List<String> existing = target.computeIfAbsent(key, k -> new ArrayList<>());
        for (String member : members) {
            if (member != null && !member.isBlank() && !existing.contains(member)) existing.add(member);
        }
    }
}
