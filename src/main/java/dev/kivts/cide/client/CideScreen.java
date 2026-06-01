package dev.kivts.cide.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dan200.computercraft.client.render.RenderTypes;
import dan200.computercraft.client.render.text.FixedWidthFontRenderer;
import dan200.computercraft.shared.computer.terminal.NetworkedTerminal;
import dev.kivts.cide.config.CideClientConfig;
import dev.kivts.cide.net.payload.*;
import dev.kivts.cide.wiki.WikiLine;
import dev.kivts.cide.wiki.WikiRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CideScreen extends Screen {
    private static final Gson GSON = new Gson();

    // Current font, unused since it didnt play out very well when I tried it
    private static final ResourceLocation EDITOR_FONT_ID = ResourceLocation.fromNamespaceAndPath("cide", "editor");
    private static final Style            EDITOR_STYLE   = Style.EMPTY; // withFont(EDITOR_FONT_ID) - disabled

    private int editorTextWidth(String text) {
        return font.width(Component.literal(text).withStyle(EDITOR_STYLE));
    }

    private String editorTextTruncated(String text, int maxWidth) {
        return font.getSplitter().plainHeadByWidth(text, maxWidth, EDITOR_STYLE);
    }

    private void drawEditorText(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.drawString(font, Component.literal(text).withStyle(EDITOR_STYLE), x, y, color, false);
    }

    private final Map<String, Integer> widthCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > 2048;
        }
    };

    private int cachedEditorTextWidth(String text) {
        return widthCache.computeIfAbsent(text, this::editorTextWidth);
    }

    // Layout
    private static final int LINE_H     = 10;
    private static final int WIKI_LINE_H = 11;
    private static final int WIKI_INDENT_W = 12;
    private static final int WIKI_BULLET_W = 12;
    private static final int LINE_NUM_W = 40;
    private static final int TAB_H      = 18;
    private static final int TOP_H      = 22;
    private static final int MAX_UNDO   = 100;
    private static final int  CHUNK_SIZE      = 32 * 1024;
    private static final long LOW_SPACE_BYTES = 300L * 1024L;
    // CIDE colors, why is it not hex :sob:
    private static final int C_HOVER    = 0xFF1A2840;
    private static final int C_SELECTED = 0xFF1A2840;
    private static final int C_SEL_BG   = 0x554080FF;
    private static final int C_OUTLINE  = 0x77697386;
    private static final int C_DIVIDER  = 0x55697386;
    private static final int C_CURLINE  = 0x22FFFFFF;

    // Colors that can change based on config opacity settings - could be a slider in game, but Im not making a literal vsc in here.
    private float panelOpacity;
    private int C_BG, C_SIDEBAR, C_EDITOR, C_TOPBAR, C_TABBAR, C_TAB_ACTIVE, C_TAB_IDLE, C_CTX_BG;

    private void rebuildPanelColors() {
        C_BG         = panelColor(0x101418, 1.00f);
        C_SIDEBAR    = panelColor(0x18202C, 1.00f);
        C_EDITOR     = panelColor(0x101418, 0.94f);
        C_TOPBAR     = panelColor(0x20262F, 1.25f);
        C_TABBAR     = panelColor(0x1A2030, 1.12f);
        C_TAB_ACTIVE = panelColor(0x1A2A45, 1.25f);
        C_TAB_IDLE   = panelColor(0x182030, 0.88f);
        C_CTX_BG     = panelColor(0x14181F, 1.75f);
    }

    private int panelColor(int rgb, float mult) {
        int a = Mth.clamp((int)(panelOpacity * 255 * mult), 0x18, 0xFF);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    // Lua colors
    static final int CS_NORMAL   = 0xFFE2E8F4;
    static final int CS_KEYWORD  = 0xFFCC99FF;
    static final int CS_BUILTIN  = 0xFF4FC1FF;
    static final int CS_STRING   = 0xFFCE9178;
    static final int CS_COMMENT  = 0xFF6A9955;
    static final int CS_NUMBER   = 0xFFB5CEA8;
    static final int CS_OPERATOR = 0xFFAAB8C8;
    static final int CS_READONLY = 0xFF7A8494;

    private static CideScreen active;
    private static final String CIDE_VERSION = ModList.get()
        .getModContainerById("cide")
        .map(container -> container.getModInfo().getVersion().toString())
        .orElse("dev");

    // Computer metadata 
    private final net.minecraft.core.BlockPos pos;
    private final int computerId;
    private final String computerLabel;
    private final boolean writesEnabled;
    private boolean lockedToPlayer;
    private final UUID sessionId;
    private final boolean adminView;

    // File tree stuff
    private final List<FileListPayload.Entry> entries = new ArrayList<>();
    private int fileScroll;
    private FileListPayload.Entry selectedEntry;
    private long freeSpace = -1;
    private final Map<String, PendingDownload> pendingDownloads = new HashMap<>();
    private record PendingDownload(byte[][] chunks, boolean readOnly) {}

    // tabs
    private record EditorTab(String path, List<String> lines, boolean readOnly,
         boolean dirty, int cursorLine, int cursorCol,
        int scroll, Deque<List<String>> undoStack,
        Deque<List<String>> redoStack) {
        static EditorTab blank() {
            List<String> lines = new ArrayList<>();
            lines.add("");
            return new EditorTab("", lines, false, false, 0, 0, 0,
                new ArrayDeque<>(), new ArrayDeque<>());
        }
        EditorTab withCursor(int newCursorLine, int newCursorCol) {
            return new EditorTab(path, lines, readOnly, dirty, newCursorLine, newCursorCol, scroll, undoStack, redoStack);
        }
        EditorTab withScroll(int newScroll) {
            return new EditorTab(path, lines, readOnly, dirty, cursorLine, cursorCol, newScroll, undoStack, redoStack);
        }
        EditorTab withPath(String newPath) {
            return new EditorTab(newPath, lines, readOnly, dirty, cursorLine, cursorCol, scroll, undoStack, redoStack);
        }
    }

    private record VisibleLineSlice(int start, int end, int x) {}
    private final List<EditorTab> tabs = new ArrayList<>();
    private int activeTab = 0;

    // Editor state 
    private List<String> lines;
    private String currentPath = "";
    private boolean currentReadOnly;
    private int editorScroll;
    private int cursorLine, cursorColumn;
    private static final String FOCUS_NONE = "none";
    private static final String FOCUS_EDITOR = "editor";
    private static final String FOCUS_SEARCH = "search";
    private static final String FOCUS_CONSOLE = "console";
    private static final String FOCUS_SPLITSCREEN_CONSOLE = "split_screen_console";
    private static final String FOCUS_TREE = "tree";
    private String currentFocus = FOCUS_EDITOR;
    private boolean dirty;
    private Deque<List<String>> undoStack;
    private Deque<List<String>> redoStack;

    // Selection
    private int selAnchorLine = -1, selAnchorCol = -1;

    // Undo timing
    private long lastUndoPush = 0;

    // File drag
    private FileListPayload.Entry draggedEntry;
    private int dragStartX, dragStartY;
    private boolean dragging;

    // Editor mouse selections
    private boolean editorDragging;

    // Horizontal scroll
    private int     hScroll = 0;
    private boolean hScrollDragging = false;
    private int     hScrollDragStartX;
    private int     hScrollDragStartValue;

    // IDE scale (slider + keyboard)
    private float ideScale = 1.0f;
    private static final float IDE_SCALE_MIN  = 0.2f;
    private static final float IDE_SCALE_MAX  = 1.0f;
    private static final float IDE_SCALE_STEP = 0.05f;
    private static final int   SCALE_SLIDER_W = 60;
    private int scaleSliderX;
    private boolean scaleDragging;
    private int lockX, lockY, lockW, lockH;

    // Sidebar resize
    private static final int SIDEBAR_MIN_W = 20;
    private static final int SIDEBAR_MAX_W = 300;
    private boolean sidebarDragging;
    private boolean sidebarScrollDragging;
    private boolean editorScrollbarDragging;

    // Tab bar horizontal scroll and other junk
    private int tabScrollPx = 0;
    private int consoleButtonX, consoleButtonY, consoleButtonW, consoleButtonH;
    private boolean consoleOn;
    private NetworkedTerminal consoleTerminal;
    private long lastConsolePoll;
    private int consolePowerX, consolePowerY, consolePowerW, consolePowerH;
    private int consoleTerminateX, consoleTerminateY, consoleTerminateW, consoleTerminateH;
    private int consoleTermX, consoleTermY;
    private float consoleTermScale = 1.0f;
    private boolean splitConsoleOpen;
    private int splitConsoleX;
    private int splitConsoleY;
    private int splitConsoleW;
    private int splitConsoleH;
    private int runButtonX, runButtonY, runButtonW, runButtonH;
    private int splitCloseX, splitCloseY, splitCloseW, splitCloseH;
    private static final int MAX_CONSOLE_PASTE_BYTES = 8192;
    private static final int MAX_CONSOLE_TERMINAL_BYTES = 64 * 1024;
    private final Set<Integer> consoleKeysDown = new HashSet<>();
    private int consoleMouseDown = -1;
    private int consoleLastCellX = 1;
    private int consoleLastCellY = 1;
    private boolean consoleMayHaveChangedFiles;
    private final Set<String> silentReloadPaths = new HashSet<>();
    private final Set<String> missingReadPaths = new HashSet<>();

    // File copy/paste
    private String copiedPath = "";
    private String armedDeletePath = "";
    private long armedDeleteUntil;

    // Fade popup message
    private String fadeMessage = "";
    private long   fadeUntil   = 0;

    // Status
    private String status = "";

    // Auto-sync
    private long lastAutoSync;
    private long lastSessionSave;

    // Context menu
    private boolean contextOpen;
    private int contextX, contextY;
    private FileListPayload.Entry contextEntry;
    private boolean editorContextOpen;
    private int editorContextX, editorContextY;
    private String editorContextWord = "";
    private String editorContextPageKey;
    private int editorContextDefLine = -1;

    // restore a CIDE session from server data - stored in computercraft/cide/sessions. Only the last computer is saved to avoid problems that I dont want to solve.
    private final Map<String, RestoredTab> pendingRestoredTabs = new LinkedHashMap<>();
    private PendingSessionDownload pendingSessionDownload;
    private String pendingRestoredActivePath = "";
    private int pendingRestoredActiveIndex = 0;
    private boolean applyingSession;
    private static final int MAX_SESSION_CHARS = 64 * 1024;
    private static final int MAX_SESSION_CHUNKS = 4;
    private static final int MAX_SESSION_TABS = 16;
    private static final int MAX_SESSION_DIRS = 128;
    private static final int MAX_SESSION_PATH = 256;
    private record RestoredTab(String path, int cursorLine, int cursorColumn, int scroll) {}
    private record PendingSessionDownload(byte[][] chunks) {}

    // Tree state: per-directory cached children + which dirs are open
    private final Map<String, List<FileListPayload.Entry>> dirContents = new LinkedHashMap<>();
    private final Set<String> expandedDirs = new LinkedHashSet<>();

    // Inline input for naming a new file/folder in-place
    private enum InlineMode { NONE, NEW_FILE, NEW_FOLDER, RENAME }
    private InlineMode inlineMode      = InlineMode.NONE;
    private String     inlineParent   = "";
    private String     inlineText     = "";
    private int        inlineInsert   = 0;
    private String     pendingRenameOld = "";

    // Search
    private boolean searchOpen;
    
    private String searchQuery = "";
    private int searchCursorPos = 0;
    private int searchSelAnchor = -1;
    private final List<int[]> searchMatches = new ArrayList<>();
    private int searchMatchIndex;

    // Double-click detection
    private long lastClickTime = 0;
    private int  lastClickLine = -1;
    private int  lastClickCol  = -1;
    private int  lastClickWordStart = -1;
    private int  lastClickWordEnd = -1;

    // Autocomplete lua/CC/whatever other mods add if they ever add this.
    private final List<String> acSuggestions = new ArrayList<>();
    private int acIndex = -1;
    private String acPrefix = "";
    private boolean acIsItemNamespace = false;

    private final Map<String, java.util.Set<Integer>> breakpoints = new LinkedHashMap<>();
    private String pausedFile = "";
    private int pausedLine = -1;
    private final Map<String, String> pausedLocals = new LinkedHashMap<>();
    private int continueBtnX, continueBtnY, continueBtnW, continueBtnH;
    private int stepBtnX, stepBtnY, stepBtnW, stepBtnH;

    // Type inference - updated lazily whenever file content changes
    private Map<String, String> typeMap         = new LinkedHashMap<>();
    private Map<String, String> lastGoodTypeMap = new LinkedHashMap<>();
    private int typeMapFingerprint = 0;
    private LuaAst.Chunk lastParsedChunk = null;
    // Live peripheral map: side/network-name >>> peripheral type, queried from server on open
    private Map<String, String> sideToType = new LinkedHashMap<>();
    // All declared names in the current file (locals, functions, loop vars, params)
    private Set<String> fileLocalNames = new LinkedHashSet<>();
    //In a perfect world these wouldn't be here so you either take it or leave it
    private static final String[] LUA_KEYWORDS = {
        "and","break","do","else","elseif","end","false","for","function",
        "goto","if","in","local","nil","not","or","repeat","return","then",
        "true","until","while"
    };
    private static final String[] LUA_GLOBALS = {
        "print","pairs","ipairs","next","select","type","tostring","tonumber",
        "rawget","rawset","rawequal","rawlen","setmetatable","getmetatable",
        "pcall","xpcall","error","assert","require","unpack","table","string",
        "math","coroutine","io","_G","_VERSION"
    };
   
    // Panel geometry
    private int panelX, panelY, panelW, panelH;
    private int sidebarW;
    private int editorX, editorY, editorW;

    public CideScreen(OpenIdePayload payload) {
        super(Component.translatable("screen.cide.title"));
        pos           = payload.pos();
        computerId    = payload.computerId();
        computerLabel = payload.label();
        writesEnabled = payload.writesEnabled();
        lockedToPlayer = payload.lockedToPlayer();
        sessionId     = payload.sessionId();
        adminView     = payload.adminView();
        panelOpacity  = (float) (double) CideClientConfig.OPACITY.get();
        rebuildPanelColors();
        tabs.add(EditorTab.blank());
        syncFromTab();
    }

    // Static handlers called by CideClient

    static void handleFileList(FileListPayload payload) {
        if (active == null || active.computerId != payload.computerId()) return;
        List<FileListPayload.Entry> sorted = new ArrayList<>(payload.entries());
        sorted.sort(Comparator.comparing(FileListPayload.Entry::directory).reversed()
            .thenComparing(FileListPayload.Entry::path));
        active.dirContents.put(payload.path(), sorted);
        for (FileListPayload.Entry entry : sorted) active.missingReadPaths.remove(entry.path());
        active.freeSpace = payload.freeSpace();
        if (payload.truncated()) active.status = "File list truncated";
        active.rebuildEntryList();
    }

    static void handleFileContent(FileContentPayload payload) {
        if (active == null || active.computerId != payload.computerId()) return;
        active.receiveFileContent(payload.path(), payload.content(), payload.readOnly());
    }

    static void handleFileContentChunk(FileContentChunkPayload payload) {
        if (active == null || active.computerId != payload.computerId()) return;
        if (payload.totalChunks() < 1 || payload.totalChunks() > maxDownloadChunks()) return;
        if (payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.totalChunks()) return;

        PendingDownload pending = active.pendingDownloads.get(payload.path());
        if (pending == null || pending.chunks().length != payload.totalChunks()) {
            pending = new PendingDownload(new byte[payload.totalChunks()][], payload.readOnly());
            active.pendingDownloads.put(payload.path(), pending);
        }
        pending.chunks()[payload.chunkIndex()] = payload.data();

        int totalBytes = 0;
        for (byte[] chunk : pending.chunks()) {
            if (chunk == null) return;
            totalBytes += chunk.length;
            if (totalBytes > Integer.MAX_VALUE) {
                active.pendingDownloads.remove(payload.path());
                active.status = "Denied: file is too large to open";
                return;
            }
        }

        byte[] bytes = new byte[totalBytes];
        int offset = 0;
        for (byte[] chunk : pending.chunks()) {
            System.arraycopy(chunk, 0, bytes, offset, chunk.length);
            offset += chunk.length;
        }
        active.pendingDownloads.remove(payload.path());
        active.receiveFileContent(payload.path(), new String(bytes, StandardCharsets.UTF_8), pending.readOnly());
    }

    private static int maxDownloadChunks() {
        return Math.max(1, Integer.MAX_VALUE / CHUNK_SIZE);
    }

    private void receiveFileContent(String path, String content, boolean readOnly) {
        missingReadPaths.remove(path);
        boolean keepTreeFocus = isTreeFocused();
        boolean silentReload = silentReloadPaths.remove(path);
        List<String> newLines = splitFileLines(content);

        // Check if a tab for this path already exists >>> switch to it
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).path().equals(path)) {
                EditorTab old = tabs.get(i);
                if (silentReload && old.dirty()) return;
                if (!silentReload && old.dirty()) {
                    switchTab(i);
                    status = "Unsaved changes";
                    return;
                }
                tabs.set(i, new EditorTab(path, newLines, readOnly, false,
                    old.cursorLine(), old.cursorCol(), old.scroll(), old.undoStack(), old.redoStack()));
                if (!silentReload) {
                    applyRestoredTab(path, i);
                    // During session restore, don't switchTab here — finishRestoreIfDone()
                    // picks the saved active path once everything has loaded. Switching now
                    // would yank the user to whichever duplicate response arrived last.
                    if (!applyingSession) {
                        switchTab(i);
                        if (keepTreeFocus) focusTree();
                    } else if (i == activeTab) {
                        syncFromTab();
                        clampCursorAndScroll();
                    }
                } else if (i == activeTab) {
                    syncFromTab();
                    clampCursorAndScroll();
                }
                finishRestoreIfDone();
                return;
            }
        }
        if (silentReload) return;

        // Otherwise open in a new tab (or reuse blank tab)
        RestoredTab restored = pendingRestoredTabs.remove(path);
        int restoredLine = restored == null ? 0 : restored.cursorLine();
        int restoredColumn = restored == null ? 0 : restored.cursorColumn();
        int restoredScroll = restored == null ? 0 : restored.scroll();
        EditorTab tab = new EditorTab(path, newLines, readOnly,
            false, restoredLine, restoredColumn, restoredScroll, new ArrayDeque<>(), new ArrayDeque<>());

        // Replace blank placeholder tab if it's the only one and untouched
        boolean isRestoreActive = applyingSession
            && !pendingRestoredActivePath.isBlank()
            && pendingRestoredActivePath.equals(path);
        // While restoring a session, only the active path is allowed to move activeTab —
        // every other arriving tab leaves it alone so the user doesn't see the editor
        // flicker through every tab and end on whatever happened to arrive last.
        boolean takeFocus = !applyingSession || isRestoreActive;
        if (tabs.size() == 1 && tabs.get(0).path().isEmpty() && !dirty) {
            tabs.set(0, tab);
            if (takeFocus) activeTab = 0;
        } else {
            tabs.add(tab);
            if (takeFocus) activeTab = tabs.size() - 1;
        }
        activeTab = Mth.clamp(activeTab, 0, tabs.size() - 1);
        syncFromTab();
        clampCursorAndScroll();
        if (keepTreeFocus) focusTree();
        else focusEditor();
        status = readOnly ? "Read-only" : "";
        finishRestoreIfDone();
    }

    private void finishRestoreIfDone() {
        if (!applyingSession || !pendingRestoredTabs.isEmpty()) return;
        if (!pendingRestoredActivePath.isBlank()) {
            for (int i = 0; i < tabs.size(); i++) {
                if (pendingRestoredActivePath.equals(tabs.get(i).path())) {
                    activeTab = i;
                    switchTab(i);
                    break;
                }
            }
        }
        applyingSession = false;
        pendingRestoredActivePath = "";
    }

    static void handleOperation(OperationResultPayload payload) {
        if (active == null) return;
        if (payload.ok()) {
            active.status = payload.message() + " " + payload.path();
            if (payload.message().equals("renamed") && !active.pendingRenameOld.isEmpty()) {
                String oldPath = active.pendingRenameOld;
                String newPath = payload.path();
                for (int i = 0; i < active.tabs.size(); i++) {
                    if (oldPath.equals(active.tabs.get(i).path()))
                        active.tabs.set(i, active.tabs.get(i).withPath(newPath));
                }
                if (oldPath.equals(active.currentPath)) active.currentPath = newPath;
                active.pendingRenameOld = "";
            }
            if (payload.message().equals("saved") && !active.isPathOpen(payload.path())) {
                active.requestRead(payload.path());
            }
            active.requestList("");
            int slash = payload.path().lastIndexOf('/');
            if (slash > 0) active.requestList(payload.path().substring(0, slash));
            for (String dir : new ArrayList<>(active.expandedDirs)) active.requestList(dir);
        } else {
            if (active.handleMissingFile(payload)) return;
            active.showFade(payload.message());
            // If the denied op left a sidebar selection with no open tab, clear it
            if (active.selectedEntry != null) {
                String selPath = active.selectedEntry.path();
                boolean hasTab = active.tabs.stream().anyMatch(t -> t.path().equals(selPath));
                if (!hasTab && !selPath.equals(active.currentPath))
                    active.selectedEntry = null;
            }
        }
    }

    private boolean handleMissingFile(OperationResultPayload payload) {
        String path = payload.path();
        String msg = payload.message() == null ? "" : payload.message().toLowerCase(Locale.ROOT);
        if (!msg.contains("file does not exist") && !msg.contains("no such file")) return false;

        if (path == null || path.isBlank()) {
            requestList("");
            for (String dir : new ArrayList<>(expandedDirs)) requestList(dir);
            status = "Missing file skipped";
            return true;
        }

        pendingRestoredTabs.remove(path);
        silentReloadPaths.remove(path);
        missingReadPaths.add(path);
        entries.removeIf(entry -> entry.path().equals(path));
        for (List<FileListPayload.Entry> list : dirContents.values()) {
            list.removeIf(entry -> entry.path().equals(path));
        }
        if (selectedEntry != null && selectedEntry.path().equals(path)) selectedEntry = null;
        requestList("");
        int slash = path.lastIndexOf('/');
        if (slash > 0) requestList(path.substring(0, slash));
        status = "Missing file skipped";
        finishRestoreIfDone();
        return true;
    }

    static void handlePeripheralMap(PeripheralMapPayload payload) {
        if (active == null || active.computerId != payload.computerId()) return;
        active.sideToType = new LinkedHashMap<>(payload.sideToType());
        active.typeMapFingerprint = 0; // force re-resolve with new side data
    }

    static void handleDebugPaused(dev.kivts.cide.net.payload.DebugPausedPayload payload) {
        if (active == null || active.computerId != payload.computerId()) return;
        if (payload.line() < 0) { active.clearPaused(); return; }
        active.pausedFile = payload.file() == null ? "" : payload.file();
        active.pausedLine = payload.line();
        active.pausedLocals.clear();
        if (payload.locals() != null) active.pausedLocals.putAll(payload.locals());
        if (!active.pausedFile.isBlank() && !active.pausedFile.startsWith("rom/")) active.openIfMissing(active.pausedFile);
    }

    private void openIfMissing(String path) {
        for (EditorTab tab : tabs) {
            if (path.equals(tab.path())) return;
        }
        requestRead(path);
    }

    private boolean isPaused() {
        return pausedLine > 0 && !pausedFile.isBlank();
    }

    private void clearPaused() {
        pausedFile = "";
        pausedLine = -1;
        pausedLocals.clear();
    }

    private void sendBreakpointsToServer() {
        if (adminView || computerId < 0) return;
        java.util.Map<String, java.util.List<Integer>> payload = new java.util.LinkedHashMap<>();
        for (var entry : breakpoints.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            payload.put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new dev.kivts.cide.net.payload.DebugSetBreakpointsPayload(computerId, payload));
    }

    private void sendDebugCommand(int command) {
        if (computerId < 0) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new dev.kivts.cide.net.payload.DebugCommandPayload(computerId, command));
        clearPaused();
    }

    private void terminateDebugIfActive() {
        if (computerId < 0) return;
        if (!isPaused() && breakpoints.isEmpty()) return;
        sendConsoleAction(dev.kivts.cide.net.payload.ConsoleActionPayload.TERMINATE);
        clearPaused();
    }

    private void toggleBreakpoint(String path, int line) {
        if (path == null || path.isBlank() || line < 0) return;
        java.util.Set<Integer> set = breakpoints.computeIfAbsent(path, k -> new java.util.LinkedHashSet<>());
        if (!set.remove(line)) set.add(line);
        if (set.isEmpty()) breakpoints.remove(path);
        sendBreakpointsToServer();
    }

    static void handleLockState(LockStatePayload payload) {
        if (active == null || active.computerId != payload.computerId()) return;
        active.lockedToPlayer = payload.lockedToPlayer();
    }

    static void handleSessionLoad(SessionLoadPayload payload) {
        if (active == null || active.computerId != payload.computerId()) return;
        active.restoreSession(payload.json());
    }

    static void handleSessionLoadChunk(SessionLoadChunkPayload payload) {
        if (active == null || active.computerId != payload.computerId()) return;
        if (payload.totalChunks() < 1 || payload.totalChunks() > MAX_SESSION_CHUNKS) return;
        if (payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.totalChunks()) return;

        if (active.pendingSessionDownload == null
                || active.pendingSessionDownload.chunks().length != payload.totalChunks()) {
            active.pendingSessionDownload = new PendingSessionDownload(new byte[payload.totalChunks()][]);
        }
        active.pendingSessionDownload.chunks()[payload.chunkIndex()] = payload.data();

        int totalBytes = 0;
        for (byte[] chunk : active.pendingSessionDownload.chunks()) {
            if (chunk == null) return;
            totalBytes += chunk.length;
            if (totalBytes > MAX_SESSION_CHARS) {
                active.pendingSessionDownload = null;
                return;
            }
        }

        byte[] bytes = new byte[totalBytes];
        int offset = 0;
        for (byte[] chunk : active.pendingSessionDownload.chunks()) {
            System.arraycopy(chunk, 0, bytes, offset, chunk.length);
            offset += chunk.length;
        }
        active.pendingSessionDownload = null;
        active.restoreSession(new String(bytes, StandardCharsets.UTF_8));
    }

    static void handleConsoleState(ConsoleStatePayload payload) {
        if (active == null || active.computerId != payload.computerId()) return;
        active.consoleOn = payload.on();
        if (payload.terminal() != null && payload.terminal().size() <= MAX_CONSOLE_TERMINAL_BYTES) {
            if (active.consoleTerminal == null) active.consoleTerminal = payload.terminal().create();
            else payload.terminal().apply(active.consoleTerminal);
        }
    }

    private static List<String> splitFileLines(String content) {
        List<String> out = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            out.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    private void restoreSession(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_SESSION_CHARS) return;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (getInt(root, "computerId", -1) != computerId) return;
            boolean restoreConsole = root.has("consoleOpen") && root.get("consoleOpen").getAsBoolean();

            applyingSession = true;
            pendingRestoredTabs.clear();
            expandedDirs.clear();
            dirContents.clear();
            fileScroll = Mth.clamp(getInt(root, "fileScroll", 0), 0, 1_000_000);
            sidebarW = Mth.clamp(getInt(root, "sidebarWidth", sidebarW), SIDEBAR_MIN_W, SIDEBAR_MAX_W);
            recalcLayout(); // restored sidebar width must propagate to editorX/editorW

            if (root.has("expandedDirs") && root.get("expandedDirs").isJsonArray()) {
                int count = 0;
                for (var element : root.getAsJsonArray("expandedDirs")) {
                    if (count++ >= MAX_SESSION_DIRS) break;
                    String path = cleanSessionPath(element.getAsString());
                    if (path != null) expandedDirs.add(path);
                }
            }

            List<RestoredTab> restoreTabs = new ArrayList<>();
            if (root.has("tabs") && root.get("tabs").isJsonArray()) {
                int count = 0;
                for (var element : root.getAsJsonArray("tabs")) {
                    if (count++ >= MAX_SESSION_TABS || !element.isJsonObject()) break;
                    JsonObject obj = element.getAsJsonObject();
                    String path = cleanSessionPath(getString(obj, "path", ""));
                    if (path == null || path.isBlank() || path.startsWith("wiki:")) continue;
                    RestoredTab tab = new RestoredTab(path,
                        Mth.clamp(getInt(obj, "cursorLine", 0), 0, 1_000_000),
                        Mth.clamp(getInt(obj, "cursorColumn", 0), 0, 1_000_000),
                        Mth.clamp(getInt(obj, "scroll", 0), 0, 1_000_000));
                    if (!pendingRestoredTabs.containsKey(path)) {
                        pendingRestoredTabs.put(path, tab);
                        restoreTabs.add(tab);
                    }
                }
            }

            pendingRestoredActiveIndex = Mth.clamp(getInt(root, "activeTab", 0), 0, Math.max(0, restoreTabs.size() - 1));
            String savedActivePath = cleanSessionPath(getString(root, "activePath", ""));
            if (savedActivePath != null && !savedActivePath.isBlank()) {
                // Trust the saved path even if it's a non-file (e.g. "console:terminal").
                // openConsoleTab() will park activeTab on the console; subsequent file-tab
                // arrivals see pendingRestoredActivePath != "" and preserve previousActive.
                pendingRestoredActivePath = savedActivePath;
            } else {
                pendingRestoredActivePath = restoreTabs.isEmpty() ? "" : restoreTabs.get(pendingRestoredActiveIndex).path();
            }
            splitConsoleOpen = root.has("splitConsoleOpen") && root.get("splitConsoleOpen").getAsBoolean();

            breakpoints.clear();
            if (root.has("breakpoints") && root.get("breakpoints").isJsonArray()) {
                for (var element : root.getAsJsonArray("breakpoints")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject obj = element.getAsJsonObject();
                    String path = cleanSessionPath(getString(obj, "path", ""));
                    if (path == null || path.isBlank()) continue;
                    if (!obj.has("lines") || !obj.get("lines").isJsonArray()) continue;
                    java.util.Set<Integer> set = new java.util.LinkedHashSet<>();
                    for (var ln : obj.getAsJsonArray("lines")) {
                        try {
                            int v = ln.getAsInt();
                            if (v > 0 && v < 1_000_000) set.add(v);
                        } catch (Exception ignored) {}
                    }
                    if (!set.isEmpty()) breakpoints.put(path, set);
                }
                if (!breakpoints.isEmpty()) sendBreakpointsToServer();
            }

            for (RestoredTab tab : restoreTabs) requestRead(tab.path());
            if (restoreConsole) openConsoleTab();
            for (String dir : expandedDirs) requestList(dir);
            if (!restoreTabs.isEmpty()) status = "Restoring session";
            // If there were no file tabs to wait on, finish the restore immediately so the
            // saved active path (e.g. the console) gets selected.
            finishRestoreIfDone();
        } catch (Exception ignored) {
            // Malformed or older-schema session — bail cleanly so the IDE still works.
            pendingRestoredTabs.clear();
            pendingRestoredActivePath = "";
            applyingSession = false;
            status = "Session restore skipped";
        }
    }

    private void applyRestoredTab(String path, int tabIndex) {
        RestoredTab restored = pendingRestoredTabs.remove(path);
        if (restored == null || tabIndex < 0 || tabIndex >= tabs.size()) return;
        EditorTab old = tabs.get(tabIndex);
        tabs.set(tabIndex, new EditorTab(old.path(), old.lines(), old.readOnly(), old.dirty(),
            restored.cursorLine(), restored.cursorColumn(), restored.scroll(), old.undoStack(), old.redoStack()));
        if (path.equals(pendingRestoredActivePath)) activeTab = tabIndex;
        if (tabIndex == activeTab) {
            syncFromTab();
            clampCursorAndScroll();
        }
    }

    private boolean isPathOpen(String path) {
        for (EditorTab tab : tabs) {
            if (tab.path().equals(path)) return true;
        }
        return false;
    }

    private void clampCursorAndScroll() {
        if (lines == null || lines.isEmpty()) return;
        cursorLine = Mth.clamp(cursorLine, 0, lines.size() - 1);
        cursorColumn = Mth.clamp(cursorColumn, 0, lines.get(cursorLine).length());
        editorScroll = Mth.clamp(editorScroll, 0, Math.max(0, lines.size() - 1));
    }

    private void saveSession() {
        if (adminView) return;
        // Never overwrite the persisted session while a restore is still in flight — the
        // in-progress activeTab/activePath would clobber what the user actually had focused.
        if (applyingSession) return;
        String json = buildSessionJson();
        if (!json.isBlank()) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            int totalChunks = Math.max(1, (bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
            for (int i = 0; i < totalChunks; i++) {
                int start = i * CHUNK_SIZE;
                byte[] chunk = Arrays.copyOfRange(bytes, start, Math.min(start + CHUNK_SIZE, bytes.length));
                PacketDistributor.sendToServer(new SessionSaveChunkPayload(pos, computerId, i, totalChunks, chunk));
            }
        }
        lastSessionSave = System.currentTimeMillis();
    }

    private String buildSessionJson() {
        syncToTab();
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("computerId", computerId);
        root.addProperty("activeTab", Mth.clamp(activeTab, 0, Math.max(0, tabs.size() - 1)));
        if (activeTab >= 0 && activeTab < tabs.size()) {
            root.addProperty("activePath", tabs.get(activeTab).path());
        }
        root.addProperty("fileScroll", Mth.clamp(fileScroll, 0, 1_000_000));
        root.addProperty("sidebarWidth", Mth.clamp(sidebarW, SIDEBAR_MIN_W, SIDEBAR_MAX_W));
        root.addProperty("consoleOpen", tabs.stream().anyMatch(tab -> isConsolePath(tab.path())));
        root.addProperty("splitConsoleOpen", splitConsoleOpen);

        JsonArray tabArray = new JsonArray();
        LinkedHashSet<String> seenPaths = new LinkedHashSet<>();
        for (EditorTab tab : tabs) {
            if (tabArray.size() >= MAX_SESSION_TABS) break;
            String path = cleanSessionPath(tab.path());
            if (path == null || path.isBlank() || path.startsWith("wiki:") || isConsolePath(path) || !seenPaths.add(path)) continue;
            JsonObject object = new JsonObject();
            object.addProperty("path", path);
            object.addProperty("cursorLine", Mth.clamp(tab.cursorLine(), 0, 1_000_000));
            object.addProperty("cursorColumn", Mth.clamp(tab.cursorCol(), 0, 1_000_000));
            object.addProperty("scroll", Mth.clamp(tab.scroll(), 0, 1_000_000));
            tabArray.add(object);
        }
        root.add("tabs", tabArray);

        JsonArray dirs = new JsonArray();
        int dirCount = 0;
        for (String dir : expandedDirs) {
            if (dirCount++ >= MAX_SESSION_DIRS) break;
            String path = cleanSessionPath(dir);
            if (path != null) dirs.add(path);
        }
        root.add("expandedDirs", dirs);

        JsonArray bpArray = new JsonArray();
        int bpFiles = 0;
        for (Map.Entry<String, java.util.Set<Integer>> entry : breakpoints.entrySet()) {
            if (bpFiles++ >= MAX_SESSION_TABS) break;
            String path = cleanSessionPath(entry.getKey());
            if (path == null || entry.getValue() == null || entry.getValue().isEmpty()) continue;
            JsonObject obj = new JsonObject();
            obj.addProperty("path", path);
            JsonArray lines = new JsonArray();
            int line = 0;
            for (Integer ln : entry.getValue()) {
                if (line++ >= 1024) break;
                if (ln != null && ln > 0) lines.add(ln);
            }
            obj.add("lines", lines);
            bpArray.add(obj);
        }
        root.add("breakpoints", bpArray);

        String json = GSON.toJson(root);
        return json.length() > MAX_SESSION_CHARS ? "" : json;
    }

    private String cleanSessionPath(String raw) {
        if (raw == null) return null;
        String path = raw.replace('\\', '/').strip();
        if (path.length() > MAX_SESSION_PATH) path = path.substring(0, MAX_SESSION_PATH);
        if (path.startsWith("/") || path.contains("..") || path.indexOf('\0') >= 0) return null;
        return path;
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String getString(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Override
    protected void init() {
        active = this;
        recalcLayout();
        requestList("");
        if (!adminView) PacketDistributor.sendToServer(new PeripheralQueryPayload(pos, computerId));
        lastAutoSync = System.currentTimeMillis();
    }

    private void recalcLayout() {
        panelW = Mth.clamp((int)(width  * 0.92), Math.min(540, width  - 8), width  - 8);
        panelH = Mth.clamp((int)(height * 0.90), Math.min(340, height - 8), height - 8);
        panelX = (width  - panelW) / 2;
        panelY = (height - panelH) / 2;
        if (sidebarW == 0) sidebarW = Math.min(190, Math.max(150, panelW / 5));
        sidebarW     = Mth.clamp(sidebarW, SIDEBAR_MIN_W, Math.min(SIDEBAR_MAX_W, panelW / 2 - 10));
        editorX      = panelX + sidebarW + 1;
        editorY      = panelY + TOP_H + TAB_H;
        editorW      = panelW - sidebarW - 1;
        scaleSliderX = panelX + panelW - 8 - SCALE_SLIDER_W;
    }

    // transform screen coordinates to editor coordinates with all the bells and whistles like zoom.
    // Editor coordinate unmapping (undoes ideScale transform)
    private double unscaleEditorX(double screenX) { return editorX + (screenX - editorX) / ideScale; }
    private double unscaleEditorY(double screenY) { return editorY + (screenY - editorY) / ideScale; }

    //fueled by listening to starsector ost - pretty good.
    // Sidebar coordinate unmapping (pivot = top-left of sidebar content)
    private int sidebarPivotY() { return panelY + TOP_H + TAB_H; }
    private double unscaleSidebarY(double screenY) { return sidebarPivotY() + (screenY - sidebarPivotY()) / ideScale; }

    private boolean isEditorFocused() { return FOCUS_EDITOR.equals(currentFocus); }
    private boolean isSearchFocused() { return FOCUS_SEARCH.equals(currentFocus); }
    private boolean isConsoleFocused() { return FOCUS_CONSOLE.equals(currentFocus); }
    private boolean isSplitConsoleFocused() { return FOCUS_SPLITSCREEN_CONSOLE.equals(currentFocus); }
    private boolean isTreeFocused() { return FOCUS_TREE.equals(currentFocus); }
    private void focusEditor() { currentFocus = FOCUS_EDITOR; }
    private void focusSearch() { currentFocus = FOCUS_SEARCH; }
    private void focusConsole() { currentFocus = FOCUS_CONSOLE; }
    private void focusSplitConsole() { currentFocus = FOCUS_SPLITSCREEN_CONSOLE; }
    private void focusTree() { currentFocus = FOCUS_TREE; }
    private void clearCideFocus(String focus) {
        if (focus.equals(currentFocus)) currentFocus = FOCUS_NONE;
    }

    private void adjustScale(float delta) {
        ideScale = Mth.clamp(ideScale + delta, IDE_SCALE_MIN, IDE_SCALE_MAX);
    }

    private void applySliderDrag(double mouseX) {
        float fraction = (float)((mouseX - scaleSliderX) / SCALE_SLIDER_W);
        ideScale = IDE_SCALE_MIN + Mth.clamp(fraction, 0f, 1f) * (IDE_SCALE_MAX - IDE_SCALE_MIN);
        ideScale = Math.round(ideScale / IDE_SCALE_STEP) * IDE_SCALE_STEP;
    }

    @Override
    public void removed() {
        terminateDebugIfActive();
        saveSession();
        releaseConsoleInputs();
        if (!adminView) PacketDistributor.sendToServer(new ConsoleActionPayload(pos, computerId, sessionId, ConsoleActionPayload.CLOSE));
        if (active == this) active = null;
    }

    // Rendering

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    protected void renderBlurredBackground(float partialTick) {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, C_BG);
        graphics.renderOutline(panelX, panelY, panelW, panelH, C_OUTLINE);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + TOP_H, C_TOPBAR);
        graphics.fill(panelX, panelY + TOP_H, panelX + sidebarW, panelY + panelH, C_SIDEBAR);
        graphics.fill(panelX, panelY + TOP_H, panelX + panelW, panelY + TOP_H + TAB_H, C_TABBAR);
        graphics.fill(editorX, editorY, editorX + editorW, panelY + panelH, C_EDITOR);
        boolean divHov = sidebarDragging || (Math.abs(mouseX - (panelX + sidebarW)) <= 3
            && mouseY >= panelY + TOP_H && mouseY < panelY + panelH);
        graphics.fill(panelX + sidebarW, panelY + TOP_H, panelX + sidebarW + 1, panelY + panelH,
            divHov ? 0xFF4FC1FF : C_DIVIDER);
        renderTabs(graphics, mouseX, mouseY);
        renderFiles(graphics, mouseX, mouseY);
        renderEditor(graphics, mouseX, mouseY);  // applies its own internal scale - everything else uses raw coords

        if (searchOpen) renderSearch(graphics);
        renderContextMenu(graphics, mouseX, mouseY);
        renderEditorContextMenu(graphics, mouseX, mouseY);

        if (dragging && draggedEntry != null)
            graphics.drawString(font, draggedEntry.path(), mouseX + 8, mouseY + 8, 0xFFFFFFFF, true);
        renderAutocomplete(graphics);
        renderWatermark(graphics);
        renderTopBar(graphics, mouseX, mouseY);
        renderEditorTooltips(graphics, mouseX, mouseY);

        // Fading error/info popup
        if (!fadeMessage.isEmpty()) {
            long rem = fadeUntil - System.currentTimeMillis();
            if (rem > 0) {
                int alpha  = (int) Math.min(255, rem * 255 / 600);
                if (alpha > 4) {
                    int fw     = font.width(fadeMessage) + 12;
                    int fx     = panelX + panelW / 2 - fw / 2;
                    int fy2    = panelY + panelH - 28;
                    graphics.fill(fx, fy2, fx + fw, fy2 + 14, (alpha << 24) | 0x0A0E14);
                    graphics.drawString(font, fadeMessage, fx + 6, fy2 + 3, (alpha << 24) | 0xFF7777, false);
                }
            } else {
                fadeMessage = "";
            }
        }
    }

    private void renderTopBar(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "CIDE", panelX + 8, panelY + 7, 0xFFB4CCFF, false);

        String comp = adminView ? "Admin view" : computerLabel == null || computerLabel.isBlank()
            ? "Computer #" + computerId
            : computerLabel + " (#" + computerId + ")";
        int cw = font.width(comp);
        int compX = panelX + panelW / 2 - cw / 2;
        graphics.drawString(font, comp, compX, panelY + 7, 0xFFD4DCED, false);

        consoleButtonW = 15; consoleButtonH = 12;
        consoleButtonX = compX - consoleButtonW - 4;
        consoleButtonY = panelY + TOP_H / 2 - consoleButtonH / 2;
        boolean addHover = mouseX >= consoleButtonX && mouseX < consoleButtonX + consoleButtonW
            && mouseY >= consoleButtonY && mouseY < consoleButtonY + consoleButtonH;
        if (addHover) {
            String tooltip = adminView
                ? "You cant open a console window inside the admin view, use a CC tweaked command instead."
                : "Open a computer console in CIDE - if one is already present, switch to that window instead.";
            List<Component> lines = List.of(
                Component.literal(tooltip).withStyle(style -> style.withColor(0xFFFFFF))
            );

            graphics.renderComponentTooltip(
                Minecraft.getInstance().font,
                lines,
                mouseX,
                mouseY
                );
}
        graphics.fill(consoleButtonX, consoleButtonY, consoleButtonX + consoleButtonW, consoleButtonY + consoleButtonH,
            addHover ? 0xFF1E4E78 : C_TAB_IDLE);
        graphics.renderOutline(consoleButtonX, consoleButtonY, consoleButtonW, consoleButtonH, addHover ? 0xFF4FC1FF : C_OUTLINE);
        graphics.drawString(font, "+", consoleButtonX + 5, consoleButtonY+2, addHover ? 0xFFFFFFFF : CS_NORMAL, false);
        
        // Scale slider
        int sliderY = panelY + TOP_H / 2 - 2;
        float scaleFraction = (ideScale - IDE_SCALE_MIN) / (IDE_SCALE_MAX - IDE_SCALE_MIN);
        int thumbX = scaleSliderX + Math.round(scaleFraction * SCALE_SLIDER_W);
        graphics.fill(scaleSliderX, sliderY, scaleSliderX + SCALE_SLIDER_W, sliderY + 4, 0xFF1A2233);
        graphics.fill(scaleSliderX, sliderY, thumbX, sliderY + 4, 0xFF3A6080);
        graphics.fill(thumbX - 1, sliderY - 2, thumbX + 3, sliderY + 6, 0xFFD4DCED);
        String pct = (int)(ideScale * 100) + "%";
        graphics.drawString(font, pct, scaleSliderX - font.width(pct) - 4, panelY + 7, 0xFF7A8EA8, false);

        int statusRight = scaleSliderX - font.width(pct) - 4;
        boolean lockHover = false;
        if (adminView) {
            lockW = 0;
            lockH = 0;
            lockX = statusRight;
            lockY = panelY + 4;
        } else {
            String lockIcon = lockedToPlayer ? "\uD83D\uDD12" : "\uD83D\uDD13";
            lockW = Math.max(12, font.width(lockIcon) + 4);
            lockH = 14;
            lockX = statusRight - lockW - 4;
            lockY = panelY + 4;
            lockHover = mouseX >= lockX && mouseX < lockX + lockW && mouseY >= lockY && mouseY < lockY + lockH;
            if (lockHover) graphics.fill(lockX-3, lockY - 1, lockX + lockW + 1, lockY + lockH + 1, 0x441A2840);
            graphics.drawString(font, lockIcon, lockX + 2, panelY + 7,
                lockedToPlayer ? 0xFFFF6B6B : 0xFF67D184, false);
        }

        // Status / free space - left of slider area
        int rightEdge = (adminView ? statusRight : lockX) - 8;
        if (freeSpace >= 0) {
            String free = "Free: " + fmtSize(freeSpace);
            int freeColor = freeSpace < LOW_SPACE_BYTES ? 0xFFFF6666 : 0xFF7A8EA8;
            int fx = rightEdge - font.width(free);
            graphics.drawString(font, free, fx, panelY + 7, freeColor, false);
            rightEdge = fx - 10;
        }
        if (!status.isEmpty()) {
            int sColor = status.startsWith("Denied") ? 0xFFFF7777
                : (status.contains("saved") || status.contains("created")) ? 0xFF9FD0A2 : 0xFF7A8EA8;
            String shown = editorTextTruncated(status, Math.max(20, rightEdge - editorX - 8));
            int sx = rightEdge - font.width(shown);
            graphics.drawString(font, shown, sx, panelY + 7, sColor, false);
        }

        if (!adminView && lockHover) {
            String tooltip = lockedToPlayer
                ? "CIDE can only be opened by you on this computer."
                : "CIDE can currently be opened by anyone.";

            List<Component> lines = List.of(
                Component.literal(tooltip).withStyle(style -> style.withColor(0xFFFFFF))
            );

            graphics.renderComponentTooltip(
                Minecraft.getInstance().font,
                lines,
                mouseX,
                mouseY
                );
}
    }

    private void renderTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        int tabY = panelY + TOP_H;

        int tabStart = editorX + 2;
        int availableWidth = editorW - 22;

        // Compute total tab width to clamp scroll
        int totalTabsWidth = 0;
        for (EditorTab tab : tabs) totalTabsWidth += tabLabelWidth(tab) + 2;
        tabScrollPx = Mth.clamp(tabScrollPx, 0, Math.max(0, totalTabsWidth - availableWidth));

        // Clip to tab bar area
        int clipX = tabStart;
        graphics.enableScissor(clipX, tabY, clipX + availableWidth, tabY + TAB_H);

        graphics.pose().pushPose();
        graphics.pose().translate(-tabScrollPx, 0, 0);

        int tabX = tabStart;
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            String label = tabLabel(tab);
            int tabWidth = font.width(label) + 10 + 15;
            // map mouse back into scrolled space for hover/click detection
            int scrolledMouseX = (int)(mouseX + tabScrollPx);
            boolean isActive = i == activeTab;
            boolean hovered  = scrolledMouseX >= tabX && scrolledMouseX < tabX + tabWidth
                && mouseY >= tabY && mouseY < tabY + TAB_H;
            int background = isActive ? C_TAB_ACTIVE : hovered ? C_HOVER : C_TAB_IDLE;
            graphics.fill(tabX, tabY, tabX + tabWidth, tabY + TAB_H, background);
            if (isActive) graphics.fill(tabX, tabY + TAB_H - 1, tabX + tabWidth, tabY + TAB_H, 0xFF4FC1FF);
            graphics.drawString(font, label, tabX + 8, tabY + 5, isActive ? CS_NORMAL : CS_READONLY, false);
            if (isActive || hovered) {
                int closeX = tabX + tabWidth - 15;
                boolean closeHovered = scrolledMouseX >= closeX - 2 && scrolledMouseX < closeX + 10
                    && mouseY >= tabY + 2 && mouseY < tabY + TAB_H - 2;
                if (closeHovered) graphics.fill(closeX, tabY + 3, closeX + 13, tabY + TAB_H - 4, 0x55FF4444);
                graphics.drawString(font, "×", closeX + 4, tabY + 5, closeHovered ? 0xFFFF8888 : 0xFF6A7590, false);
            }
            tabX += tabWidth + 2;
        }

        graphics.pose().popPose();
        graphics.disableScissor();

        // Scroll-shadow indicators
        if (tabScrollPx > 0)
            graphics.fill(tabStart, tabY, tabStart + 10, tabY + TAB_H, 0x88000000);
        if (tabScrollPx < totalTabsWidth - availableWidth)
            graphics.fill(tabStart + availableWidth - 10, tabY, tabStart + availableWidth, tabY + TAB_H, 0x88000000);
    }

    private String tabLabel(EditorTab tab) {
        if (isConsolePath(tab.path())) return "Console";
        if (tab.path().startsWith("wiki:")) {
            String key = tab.path().substring(5);
            return key.substring(key.lastIndexOf('/') + 1) + " [wiki]";
        }
        String label = tab.path().isEmpty() ? "Welcome"
            : tab.path().substring(tab.path().lastIndexOf('/') + 1);
        if (tab.dirty()) label += " •";
        return label;
    }

    private int tabLabelWidth(EditorTab tab) {
        return font.width(tabLabel(tab)) + 10 + 15;
    }

    private static boolean isConsolePath(String path) {
        return "console:terminal".equals(path);
    }

    private void openConsoleTab() {
        for (int i = 0; i < tabs.size(); i++) {
            if (isConsolePath(tabs.get(i).path())) {
                switchTab(i);
                focusConsole();
                requestConsoleState();
                return;
            }
        }
        syncToTab();
        EditorTab tab = new EditorTab("console:terminal", new ArrayList<>(List.of("")), true, false,
            0, 0, 0, new ArrayDeque<>(), new ArrayDeque<>());
        if (tabs.size() == 1 && tabs.get(0).path().isEmpty() && !dirty) {
            tabs.set(0, tab);
            activeTab = 0;
        } else {
            tabs.add(tab);
            activeTab = tabs.size() - 1;
        }
        syncFromTab();
        focusConsole();
        requestConsoleState();
    }

    private void renderWelcomeScreen(GuiGraphics graphics, int bot) {
        // All coordinates are in logical space (inside the editor's ideScale transform).
        // Logical width = editorW / ideScale, logical height = bot - editorY.
        int lw     = (int)(editorW / ideScale);
        int totalH = bot - editorY;
        int cx     = editorX + lw / 2;
        int titleY = editorY + totalH / 5;

        float ts = 3.0f/ideScale;
        graphics.pose().pushPose();
        graphics.pose().translate(cx, titleY, 0);
        graphics.pose().scale(ts, ts, 1f);
        String title = "CIDE";
        graphics.drawString(font, title, -font.width(title) / 2, 0, 0xFF4FC1FF, false);
        graphics.pose().popPose();

        String sub = "Advanced Editor for ComputerCraft";
        graphics.drawString(font, sub, cx - font.width(sub) / 2,
            titleY + (int)(LINE_H * ts) + 6, 0xFF7A8EA8, false);

        String[] features = {
            "File tree familiar to anyone that ever used VSC",
            "Right-click any method or variable name to jump to its definition",
            "Right-click a ComputerCraft name to open the wiki",
            "A lot of familiar shortcuts",
            "All in Minecraft",
            "Drag and drop files between folders",
        };
        int fy = titleY + (int)(LINE_H * ts) + 26;
        for (String f : features) {
            graphics.drawString(font, f, cx - font.width(f) / 2, fy, 0xFF3A4A5C, false);
            fy += LINE_H + 3;
        }
    }

    private void renderWatermark(GuiGraphics graphics) {
        String wm = "CIDE v" + CIDE_VERSION + "  by kivts";
        graphics.drawString(font, wm, panelX + panelW - font.width(wm) - 8, panelY + panelH - 10, 0x33AABBCC, false);
    }

    private void renderFiles(GuiGraphics graphics, int mouseX, int mouseY) {
        int pivotY = sidebarPivotY();

        // Apply ideScale to sidebar content - same pattern as the editor
        graphics.pose().pushPose();
        graphics.pose().translate(panelX, pivotY, 0f);
        graphics.pose().scale(ideScale, ideScale, 1f);
        graphics.pose().translate(-panelX, -pivotY, 0f);

        // Unmap mouse into sidebar logical space
        double localMouseY = unscaleSidebarY(mouseY);

        int rowY      = pivotY + 6;
        int rowBottom = pivotY + (int)((panelY + panelH - 6 - pivotY) / ideScale);

        graphics.drawString(font, "FILES", panelX + 8, rowY, 0xFF4A5568, false);
        rowY += LINE_H + 3;

        int entriesTop = rowY;
        boolean inlineAddsRow = inlineMode != InlineMode.NONE && inlineMode != InlineMode.RENAME;
        int totalRows  = entries.size() + (inlineAddsRow ? 1 : 0);
        int visible    = Math.max(1, (rowBottom - entriesTop) / LINE_H);
        fileScroll     = Mth.clamp(fileScroll, 0, Math.max(0, totalRows - visible));
        int start      = fileScroll;

        for (int virtualIndex = start; virtualIndex < totalRows && rowY < rowBottom; virtualIndex++) {
            if (inlineMode != InlineMode.NONE && virtualIndex == inlineInsert) {
                int depth = inlineParent.isEmpty() ? 0
                    : (int) inlineParent.chars().filter(c -> c == '/').count() + 1;
                graphics.fill(panelX + 3, rowY - 1, panelX + (int)(sidebarW / ideScale) - 3, rowY + LINE_H - 1, 0x55334466);
                String icon = inlineMode == InlineMode.NEW_FILE ? "  " : "▸ ";
                String label = "  ".repeat(depth) + icon + inlineText + "█";
                graphics.drawString(font, trim(label, (int)((sidebarW - 12) / ideScale / 6)), panelX + 7, rowY, CS_NORMAL, false);
                rowY += LINE_H;
                continue;
            }
            int entryIndex = (inlineAddsRow && virtualIndex > inlineInsert) ? virtualIndex - 1 : virtualIndex;
            if (entryIndex < 0 || entryIndex >= entries.size()) continue;
            FileListPayload.Entry entry = entries.get(entryIndex);
            boolean hovered = mouseX >= panelX && mouseX < panelX + sidebarW
                && localMouseY >= rowY - 1 && localMouseY < rowY + LINE_H;
            boolean selected = entry.equals(selectedEntry) || entry.path().equals(currentPath);
            boolean armedDelete = isDeleteArmed(entry.path());
            if (armedDelete) {
                graphics.fill(panelX + 3, rowY - 1, panelX + (int)(sidebarW / ideScale) - 3, rowY + LINE_H - 1, 0xAA5A1018);
            } else if (hovered || selected) {
                graphics.fill(panelX + 3, rowY - 1, panelX + (int)(sidebarW / ideScale) - 3, rowY + LINE_H - 1,
                    hovered ? C_HOVER : C_SELECTED);
            }
            int depth = (int) entry.path().chars().filter(c -> c == '/').count();
            String prefix = entry.directory()
                ? (expandedDirs.contains(entry.path()) ? "▾ " : "▸ ") : "  ";
            int color;
            if (armedDelete)            color = 0xFFFFD0D6;
            else if (entry.directory()) color = 0xFF87BFFF;
            else if (entry.readOnly())  color = CS_READONLY;
            else                        color = CS_NORMAL;
            graphics.drawString(font, trim("  ".repeat(depth) + prefix + entryName(entry), (int)((sidebarW - 12) / ideScale / 6)),
                panelX + 7, rowY, color, false);
            rowY += LINE_H;
        }

        // Scrollbar (screen space - drawn after popPose)
        int scrollbarHeight = (int)((panelY + panelH - 6 - pivotY - (entriesTop - pivotY) * ideScale));
        int scrollbarTop    = pivotY + (int)((entriesTop - pivotY) * ideScale);
        graphics.pose().popPose();

        if (totalRows > visible) {
            int thumbH = Math.max(8, scrollbarHeight * visible / totalRows);
            int thumbY = scrollbarTop + (scrollbarHeight - thumbH) * Math.max(0, start) / Math.max(1, totalRows - visible);
            graphics.fill(panelX + 2, scrollbarTop, panelX + 5, panelY + panelH - 6, 0x22FFFFFF);
            graphics.fill(panelX + 2, thumbY, panelX + 5, thumbY + thumbH, 0x99AABBCC);
        }
    }

    private void renderEditor(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.pose().pushPose();
        graphics.pose().translate(editorX, editorY, 0f);
        graphics.pose().scale(ideScale, ideScale, 1f);
        graphics.pose().translate(-editorX, -editorY, 0f);

        int x   = editorX + 5;
        int y   = editorY + 6;
        // Expand the logical bottom so the scaled content fills the full editor height
        int bot = editorY + (int)((panelY + panelH - 12 - editorY) / ideScale);
        boolean splitVisible = splitConsoleOpen && !isConsolePath(currentPath);
        int editorRight = editorRightLogical();
        if (splitVisible) {
            int naturalConsoleW = consoleTerminal == null ? 320
                : consoleTerminal.getWidth() * FixedWidthFontRenderer.FONT_WIDTH + 28;
            int maxSplitW = Math.max(180, (editorRight - editorX) / 2);
            splitConsoleW = Mth.clamp(naturalConsoleW, 190, maxSplitW);
            splitConsoleX = editorRight - splitConsoleW;
            splitConsoleY = editorY + 5;
            splitConsoleH = bot - splitConsoleY;
        } else {
            splitConsoleX = 0;
            splitConsoleY = 0;
            splitConsoleW = 0;
            splitConsoleH = 0;
        }

        if (lines == null || lines.isEmpty()) { lines = new ArrayList<>(); lines.add(""); }

        if (currentPath.isBlank()) {
            renderWelcomeScreen(graphics, bot);
            graphics.pose().popPose();
            return;
        }

        // Wiki tab - hand off to dedicated renderer
        if (isConsolePath(currentPath)) {
            renderConsole(graphics, x, y, bot, editorRight);
            graphics.pose().popPose();
            return;
        }

        if (currentPath.startsWith("wiki:")) {
            renderWikiContent(graphics, x, y, bot);
            graphics.pose().popPose();
            renderEditorScrollbar(graphics);
            return;
        }

        renderFileHeader(graphics, x, y, mouseX, mouseY);
        y += LINE_H + 3;

        // Search bar (inline, just below header)
        if (searchOpen) {
            int sw   = Math.min(260, Math.max(80, editorTextRightLogical() - x - LINE_NUM_W - 8));
            int sbX  = x + LINE_NUM_W;
            int txtX = sbX + 4;
            String prefix = "Find: ";
            int prefixW = font.width(prefix);

            // Background
            graphics.fill(sbX, y, sbX + sw, y + LINE_H + 2, 0xAA141820);

            // Selection highlight (drawn before text so text renders on top)
            if (isSearchFocused() && searchHasSel()) {
                int s = Math.min(searchSelAnchor, searchCursorPos);
                int e = Math.max(searchSelAnchor, searchCursorPos);
                int sx = txtX + prefixW + font.width(searchQuery.substring(0, s));
                int ex = txtX + prefixW + font.width(searchQuery.substring(0, e));
                graphics.fill(sx, y + 1, ex, y + LINE_H + 1, C_SEL_BG);
            }

            // Outline - bright blue when focused, dim otherwise
            graphics.renderOutline(sbX, y, sw, LINE_H + 2, isSearchFocused() ? 0xFF4FC1FF : C_OUTLINE);

            // Text
            graphics.drawString(font, prefix + searchQuery, txtX, y + 2, CS_NORMAL, false);

            // Cursor - only when focused
            if (isSearchFocused()) {
                int curX = txtX + prefixW + font.width(searchQuery.substring(0, searchCursorPos));
                graphics.fill(curX, y + 1, curX + 1, y + LINE_H, 0xFFF2F5FF);
            }

            // Match count
            String info = searchMatches.isEmpty() ? "No results"
                : (searchMatchIndex + 1) + "/" + searchMatches.size();
            int ic = searchMatches.isEmpty() ? 0xFFFF7777 : 0xFF9FD0A2;
            graphics.drawString(font, info, sbX + sw - font.width(info) - 18, y + 2, ic, false);

            // Red x close button
            int xbx = sbX + sw - 12;
            graphics.fill(xbx - 1, y + 1, xbx + 10, y + LINE_H + 1, 0x55FF3333);
            graphics.drawString(font, "×", xbx+2, y + 2, 0xFFFF8888, false);
            y += LINE_H + 4;
        }

        int textX    = x + LINE_NUM_W;
        int visible  = Math.max(1, (bot - y) / LINE_H);
        editorScroll = Mth.clamp(editorScroll, 0, Math.max(0, lines.size() - visible));

        // Horizontal scroll - compute max content width and clamp offset
        int textRight = editorTextRightLogical();
        int textAreaW = Math.max(20, textRight - textX);
        int maxLineW  = 0;
        for (String ln : lines) maxLineW = Math.max(maxLineW, cachedEditorTextWidth(ln));
        boolean showHBar = maxLineW > textAreaW;
        hScroll = Mth.clamp(hScroll, 0, showHBar ? maxLineW - textAreaW : 0);

        // Clip text rendering to editor right edge (screen coords - unaffected by pose)
        graphics.enableScissor(editorX, editorY, logicalToScreenX(textRight + 4), panelY + panelH - 12);

        // Normalise selection range
        int sl1 = -1, sc1 = -1, sl2 = -1, sc2 = -1;
        if (hasSelection()) {
            if (selAnchorLine < cursorLine || (selAnchorLine == cursorLine && selAnchorCol <= cursorColumn)) {
                sl1 = selAnchorLine; sc1 = selAnchorCol; sl2 = cursorLine; sc2 = cursorColumn;
            } else {
                sl1 = cursorLine; sc1 = cursorColumn; sl2 = selAnchorLine; sc2 = selAnchorCol;
            }
        }

        for (int i = editorScroll; i < lines.size() && y < bot; i++) {
            String line = lines.get(i);
            VisibleLineSlice slice = visibleLineSlice(line, textX, textAreaW);

            // Current-line highlight (full width, not scrolled)
            if (isEditorFocused() && i == cursorLine)
                graphics.fill(editorX + 3, y - 1, editorTextRightLogical() + 4, y + LINE_H - 1, C_CURLINE);

            // Selection background (scrolled with content)
            if (hasSelection() && i >= sl1 && i <= sl2) {
                int startC = (i == sl1) ? sc1 : 0;
                int endC   = (i == sl2) ? sc2 : line.length();
                int clippedStart = Mth.clamp(startC, slice.start(), slice.end());
                int clippedEnd = Mth.clamp(endC, slice.start(), slice.end());
                if (clippedStart < clippedEnd || (i < sl2 && clippedStart <= slice.end())) {
                    int sx = slice.x() + editorTextWidth(line.substring(slice.start(), clippedStart));
                    int ex = slice.x() + editorTextWidth(line.substring(slice.start(), clippedEnd));
                    if (i < sl2) ex = Math.max(ex, sx + 4);
                    graphics.fill(sx, y - 1, ex, y + LINE_H - 1, C_SEL_BG);
                }
            }

            // Search match highlights (scrolled)
            if (searchOpen && !searchQuery.isEmpty()) {
                String lo = line.toLowerCase(Locale.ROOT);
                String ql = searchQuery.toLowerCase(Locale.ROOT);
                int fi = Math.max(0, slice.start() - ql.length() + 1);
                while ((fi = lo.indexOf(ql, fi)) >= 0) {
                    if (fi > slice.end()) break;
                    int matchEnd = fi + searchQuery.length();
                    int visibleStart = Mth.clamp(fi, slice.start(), slice.end());
                    int visibleEnd = Mth.clamp(matchEnd, slice.start(), slice.end());
                    if (visibleStart >= visibleEnd) {
                        fi += searchQuery.length();
                        continue;
                    }
                    int hx = slice.x() + editorTextWidth(line.substring(slice.start(), visibleStart));
                    int hw = editorTextWidth(line.substring(visibleStart, visibleEnd));
                    boolean cur = !searchMatches.isEmpty()
                        && searchMatches.get(searchMatchIndex)[0] == i
                        && searchMatches.get(searchMatchIndex)[1] == fi;
                    graphics.fill(hx, y - 1, hx + hw, y + LINE_H - 1, cur ? 0x88FFAA00 : 0x445599FF);
                    fi += searchQuery.length();
                }
            }

            // Paused-line highlight (whole row, scrolled with editor)
            if (isPaused() && currentPath.equals(pausedFile) && i == pausedLine - 1) {
                graphics.fill(editorX + 3, y - 1, editorTextRightLogical() + 4, y + LINE_H - 1, 0x55F0C040);
            }

            // Line number (always at fixed position, not scrolled)
            graphics.drawString(font, String.valueOf(i + 1), x, y,
                i == cursorLine ? 0xFFC0C8D8 : 0xFF3A4252, false);

            // Breakpoint dot in the gutter
            java.util.Set<Integer> activeBps = breakpoints.get(currentPath);
            boolean hasBp = activeBps != null && activeBps.contains(i + 1);
            int dotCenterX = x + LINE_NUM_W - 8;
            int dotCenterY = y + LINE_H / 2 - 1;
            int dotRadius = 3;
            boolean hoverGutter = mouseInGutterRow(mouseX, mouseY, y);
            if (hasBp) {
                drawCircle(graphics, dotCenterX, dotCenterY, dotRadius, 0xFFE05050);
            } else if (hoverGutter && !currentReadOnly) {
                drawCircleOutline(graphics, dotCenterX, dotCenterY, dotRadius, 0x80808080);
            }

            // Line text (scrolled)
            renderLineText(graphics, line, slice, y);

            // Paused-line inline locals — filtered to identifiers that appear on this line
            if (isPaused() && currentPath.equals(pausedFile) && i == pausedLine - 1 && !pausedLocals.isEmpty()) {
                java.util.Set<String> identsOnLine = identifiersIn(line);
                int afterText = slice.x() + editorTextWidth(line.substring(slice.start(), Math.min(line.length(), slice.end())));
                int annotationX = Math.max(afterText + 12, editorX + LINE_NUM_W + 200);
                StringBuilder sb = new StringBuilder();
                int rendered = 0;
                for (Map.Entry<String, String> entry : pausedLocals.entrySet()) {
                    if (!identsOnLine.contains(entry.getKey())) continue;
                    if (rendered++ > 0) sb.append("  ");
                    sb.append(entry.getKey()).append(" = ").append(entry.getValue());
                    if (rendered >= 8) { sb.append("  …"); break; }
                }
                if (sb.length() > 0) graphics.drawString(font, sb.toString(), annotationX, y, 0x66C8D4E8, false);
            }

            // Cursor (scrolled) - hidden while the search bar has keyboard focus
            if (isEditorFocused() && !isSearchFocused() && i == cursorLine) {
                int cursor = Math.min(cursorColumn, line.length());
                if (cursor >= slice.start() && cursor <= slice.end()) {
                    int cx = slice.x() + editorTextWidth(line.substring(slice.start(), cursor));
                    graphics.fill(cx, y - 1, cx + 1, y + LINE_H - 1, 0xFFF0F4FF);
                }
            }

            y += LINE_H;
        }

        graphics.disableScissor();

        // Horizontal scrollbar - only when content is wider than the text area
        if (showHBar) {
            int hbY  = bot - 5;
            int hbX1 = textX;
            int hbX2 = textRight;
            int hbW  = hbX2 - hbX1;
            int tW   = Math.max(20, hbW * textAreaW / Math.max(1, maxLineW));
            int tX   = hbX1 + (hbW - tW) * hScroll / Math.max(1, maxLineW - textAreaW);
            graphics.fill(hbX1, hbY, hbX2, hbY + 4, 0x22FFFFFF);
            graphics.fill(tX, hbY, tX + tW, hbY + 4, hScrollDragging ? 0xCCAABBCC : 0x77AABBCC);
        }

        if (splitVisible) renderSplitConsole(graphics, editorY + (int)((panelY + panelH - 12 - editorY) / ideScale));
        graphics.pose().popPose();
        renderEditorScrollbar(graphics);
    }

    private void renderFileHeader(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        runButtonW = 15;
        runButtonH = 13;
        splitCloseW = splitConsoleOpen ? 15 : 0;
        splitCloseH = 13;
        int right = editorTextRightLogical();
        splitCloseX = right - splitCloseW;
        splitCloseY = y - 1;
        runButtonX = splitCloseX - runButtonW - (splitConsoleOpen ? 4 : 0);
        runButtonY = y - 1;

        continueBtnW = 0; stepBtnW = 0;
        if (isPaused()) {
            continueBtnW = font.width("Continue") + 8;
            continueBtnH = 13;
            stepBtnW = font.width("Step") + 8;
            stepBtnH = 13;
            continueBtnY = y - 1;
            stepBtnY = y - 1;
            stepBtnX = runButtonX - stepBtnW - 4;
            continueBtnX = stepBtnX - continueBtnW - 3;
        }

        int headerRight = isPaused() ? continueBtnX : runButtonX;
        String header = currentPath + (currentReadOnly || !writesEnabled ? " [read-only]" : dirty ? " *" : "");
        int maxHeaderW = Math.max(20, headerRight - x - 6);
        graphics.drawString(font, trimToWidth(header, maxHeaderW), x, y, 0xFFC0CCE0, false);

        if (isPaused()) {
            boolean contHover = inLogicalBox(mouseX, mouseY, continueBtnX, continueBtnY, continueBtnW, continueBtnH);
            graphics.fill(continueBtnX, continueBtnY, continueBtnX + continueBtnW, continueBtnY + continueBtnH,
                contHover ? 0xFF1F4B7B : 0xAA0F2645);
            graphics.renderOutline(continueBtnX, continueBtnY, continueBtnW, continueBtnH, contHover ? 0xFF7DC0FF : 0xFF4F88C8);
            graphics.drawString(font, "Continue", continueBtnX + 3, continueBtnY + 3, 0xFFBFD6F0, false);

            boolean stepHover = inLogicalBox(mouseX, mouseY, stepBtnX, stepBtnY, stepBtnW, stepBtnH);
            graphics.fill(stepBtnX, stepBtnY, stepBtnX + stepBtnW, stepBtnY + stepBtnH,
                stepHover ? 0xFF4B4B7B : 0xAA262645);
            graphics.renderOutline(stepBtnX, stepBtnY, stepBtnW, stepBtnH, stepHover ? 0xFFB0B0FF : 0xFF6868B0);
            graphics.drawString(font, "Step", stepBtnX + 5, stepBtnY + 3, 0xFFC8C8F0, false);
        }

        boolean runHover = inLogicalBox(mouseX, mouseY, runButtonX, runButtonY, runButtonW, runButtonH);
        graphics.fill(runButtonX, runButtonY, runButtonX + runButtonW, runButtonY + runButtonH,
            runHover ? 0xFF1F6B3A : 0xAA12321F);
        graphics.renderOutline(runButtonX, runButtonY, runButtonW, runButtonH, runHover ? 0xFF7DFF9B : 0xFF67D184);
        graphics.drawString(font, ">", runButtonX + 5, runButtonY + 3, 0xFFB9FFC8, false);

        if (splitConsoleOpen) {
            boolean closeHover = inLogicalBox(mouseX, mouseY, splitCloseX, splitCloseY, splitCloseW, splitCloseH);
            graphics.fill(splitCloseX, splitCloseY, splitCloseX + splitCloseW, splitCloseY + splitCloseH,
                closeHover ? 0xFF5B1F28 : 0xAA32141A);
            graphics.renderOutline(splitCloseX, splitCloseY, splitCloseW, splitCloseH, closeHover ? 0xFFFF8794 : 0xFFFF6B6B);
            graphics.drawString(font, "x", splitCloseX + 5, splitCloseY + 2, 0xFFFFADB8, false);
        }
    }

    private void renderEditorTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        if (adminView && canShowRunButtonTooltip() && inLogicalBox(mouseX, mouseY, runButtonX, runButtonY, runButtonW, runButtonH)) {
            graphics.renderComponentTooltip(font,
                List.of(Component.literal("You cant run a file inside the admin view, use a CC tweaked command instead.").withStyle(style -> style.withColor(0xFFFFFF))),
                mouseX, mouseY);
        } else if (canRunCurrentFile() && inLogicalBox(mouseX, mouseY, runButtonX, runButtonY, runButtonW, runButtonH)) {
            graphics.renderComponentTooltip(font,
                List.of(Component.literal("Open the console and launch this program (F5)").withStyle(style -> style.withColor(0xFFFFFF))),
                mouseX, mouseY);
        }
    }

    private String trimToWidth(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String suffix = "...";
        int suffixW = font.width(suffix);
        if (maxWidth <= suffixW) return suffix;
        return font.getSplitter().plainHeadByWidth(text, maxWidth - suffixW, Style.EMPTY) + suffix;
    }

    private int editorRightLogical() {
        return editorX + (int)(editorW / ideScale) - 8;
    }

    private int editorTextRightLogical() {
        return splitConsoleOpen && !isConsolePath(currentPath) && splitConsoleX > 0
            ? splitConsoleX - 8 : editorRightLogical();
    }

    private int logicalToScreenX(int logicalX) {
        return editorX + Math.round((logicalX - editorX) * ideScale);
    }

    private void renderSplitConsole(GuiGraphics graphics, int bot) {
        int y = splitConsoleY;
        int top = y + 6;
        int x = splitConsoleX + 6;
        int right = splitConsoleX + splitConsoleW - 6;
        graphics.fill(splitConsoleX, y, splitConsoleX + splitConsoleW, bot - 2, 0xEE0B0F14);
        graphics.renderOutline(splitConsoleX, y, splitConsoleW, bot - y - 2,
            isSplitConsoleFocused() ? 0xFF4FC1FF : C_OUTLINE);
        graphics.drawString(font, "Console", x, top, isSplitConsoleFocused() ? 0xFFB4CCFF : 0xFF7A8EA8, false);

        int oldPowerX = consolePowerX, oldPowerY = consolePowerY, oldPowerW = consolePowerW, oldPowerH = consolePowerH;
        int oldTerminateX = consoleTerminateX, oldTerminateY = consoleTerminateY, oldTerminateW = consoleTerminateW, oldTerminateH = consoleTerminateH;
        int oldTermX = consoleTermX, oldTermY = consoleTermY;
        float oldScale = consoleTermScale;
        renderConsole(graphics, x, top + LINE_H + 2, bot, right);
        if (isConsolePath(currentPath)) {
            consolePowerX = oldPowerX; consolePowerY = oldPowerY; consolePowerW = oldPowerW; consolePowerH = oldPowerH;
            consoleTerminateX = oldTerminateX; consoleTerminateY = oldTerminateY; consoleTerminateW = oldTerminateW; consoleTerminateH = oldTerminateH;
            consoleTermX = oldTermX; consoleTermY = oldTermY; consoleTermScale = oldScale;
        }
    }

    private void renderConsole(GuiGraphics graphics, int x, int y, int bot, int right) {
        consolePowerX = x;
        consolePowerY = y + LINE_H + 5;
        consolePowerW = 58;
        consolePowerH = 15;
        consoleTerminateX = consolePowerX + consolePowerW + 6;
        consoleTerminateY = consolePowerY;
        consoleTerminateW = 70;
        consoleTerminateH = 15;
        drawConsoleButton(graphics, consolePowerX, consolePowerY, consolePowerW, consolePowerH,
            consoleOn ? "Shutdown" : "Turn On", consoleOn ? 0xFFFFB36B : 0xFF67D184);
        drawConsoleButton(graphics, consoleTerminateX, consoleTerminateY, consoleTerminateW, consoleTerminateH,
            "Terminate", 0xFFFF6B6B);

        if (consoleTerminal == null) {
            graphics.drawString(font, "Waiting for terminal...", x, consolePowerY + 24, 0xFF7A8EA8, false);
            return;
        }

        int maxW = Math.max(1, right - x - 4);
        int maxH = Math.max(1, bot - (consolePowerY + consolePowerH + 14));
        int naturalW = consoleTerminal.getWidth() * FixedWidthFontRenderer.FONT_WIDTH + 4;
        int naturalH = consoleTerminal.getHeight() * FixedWidthFontRenderer.FONT_HEIGHT + 4;
        consoleTermScale = Math.min(1.0f, Math.min(maxW / (float) naturalW, maxH / (float) naturalH));
        consoleTermX = x;
        consoleTermY = consolePowerY + consolePowerH + 12;

        graphics.pose().pushPose();
        graphics.pose().translate(consoleTermX, consoleTermY, 0f);
        graphics.pose().scale(consoleTermScale, consoleTermScale, 1f);
        graphics.pose().translate(-consoleTermX, -consoleTermY, 0f);
        var emitter = FixedWidthFontRenderer.toVertexConsumer(graphics.pose(),
            graphics.bufferSource().getBuffer(RenderTypes.TERMINAL));
        FixedWidthFontRenderer.drawTerminal(emitter, consoleTermX + 2, consoleTermY + 2,
            consoleTerminal, 2, 2, 2, 2);
        graphics.pose().popPose();
    }

    private void drawConsoleButton(GuiGraphics graphics, int x, int y, int w, int h, String label, int color) {
        graphics.fill(x, y, x + w, y + h, 0xAA141820);
        graphics.renderOutline(x, y, w, h, color);
        graphics.drawString(font, label, x + (w - font.width(label)) / 2, y + 4, color, false);
    }

    private void renderEditorScrollbar(GuiGraphics graphics) {
        if (currentPath.isBlank() || isConsolePath(currentPath)) return;
        int sbTop    = editorY;
        int sbBot    = panelY + panelH - 6;
        int contentH = sbBot - sbTop;
        int totalLines;
        int headerH;
        if (currentPath.startsWith("wiki:")) {
            totalLines = wrapWikiLines(WikiRegistry.getPage(currentPath.substring(5))).size();
            headerH    = (int)(6 * ideScale);
        } else {
            totalLines = lines != null ? lines.size() : 0;
            headerH    = LINE_H + 3 + (searchOpen ? LINE_H + 4 : 0) + 6;
        }
        int lineH = currentPath.startsWith("wiki:") ? WIKI_LINE_H : LINE_H;
        int visLines = Math.max(1, (int)((contentH - headerH) / (lineH * ideScale)));
        if (totalLines > visLines) {
            int sbX    = splitConsoleOpen && !currentPath.startsWith("wiki:")
                ? logicalToScreenX(editorTextRightLogical()) + 2 : editorX + editorW - 5;
            int thumbH = Math.max(8, contentH * visLines / totalLines);
            int thumbY = sbTop + (contentH - thumbH) * editorScroll
                / Math.max(1, totalLines - visLines);
            graphics.fill(sbX, sbTop, sbX + 3, sbBot, 0x22FFFFFF);
            graphics.fill(sbX, thumbY, sbX + 3, thumbY + thumbH, 0x77AABBCC);
        }
    }
    //SO IT TURNS OUT RENDERING THE ENTIRE HORIZONTAL LINE IS BAD FOR FPS....
    private VisibleLineSlice visibleLineSlice(String line, int textX, int textAreaW) {
        if (line.isEmpty()) return new VisibleLineSlice(0, 0, textX - hScroll);
        int averageCharW = Math.max(1, editorTextWidth("m"));
        int overscanChars = Math.max(32, textAreaW / Math.max(1, averageCharW));
        int start = Mth.clamp(hScroll / averageCharW - overscanChars / 2, 0, line.length());
        int end = Mth.clamp(start + overscanChars * 2 + textAreaW / averageCharW, start, line.length());
        int prefixWidth = start == 0 ? 0 : editorTextWidth(line.substring(0, start));
        return new VisibleLineSlice(start, end, textX - hScroll + prefixWidth);
    }

    private void renderLineText(GuiGraphics graphics, String text, VisibleLineSlice slice, int y) {
        if (slice.start() >= slice.end()) return;
        String visibleText = text.substring(slice.start(), slice.end());
        int x = slice.x();
        if (isLuaLikePath(currentPath)) {
            for (LuaLexer.Token t : LuaLexer.tokenize(visibleText)) {
                drawEditorText(graphics, t.text(), x, y, t.color());
                x += editorTextWidth(t.text());
            }
        } else {
            drawEditorText(graphics, visibleText, x, y, CS_NORMAL);
        }
    }

    private void renderContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!contextOpen) return;
        List<String> items = contextItems();
        int w = 110, h = items.size() * 14 + 6;
        int cx = Math.min(contextX, panelX + panelW - w - 4);
        int cy = Math.min(contextY, panelY + panelH - h - 4);
        graphics.fill(cx, cy, cx + w, cy + h, C_CTX_BG);
        graphics.renderOutline(cx, cy, w, h, C_OUTLINE);
        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i);
            int iy = cy + 4 + i * 14;
            if (item.equals("---")) {
                graphics.fill(cx + 4, iy + 6, cx + w - 4, iy + 7, 0xFF334055);
                continue;
            }
            boolean hov = mouseX >= cx && mouseX < cx + w && mouseY >= iy && mouseY < iy + 14;
            if (hov) graphics.fill(cx + 2, iy, cx + w - 2, iy + 14, C_HOVER);
            graphics.drawString(font, item, cx + 8, iy + 3, CS_NORMAL, false);
        }
    }

    private void renderEditorContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!editorContextOpen) return;
        List<String> items = editorContextItems();
        int w = 132, h = items.size() * 16 + 6;
        int cx = Math.min(editorContextX, panelX + panelW - w - 4);
        int cy = Math.min(editorContextY, panelY + panelH - h - 4);
        graphics.fill(cx, cy, cx + w, cy + h, C_CTX_BG);
        graphics.renderOutline(cx, cy, w, h, C_OUTLINE);
        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i);
            int iy = cy + 3 + i * 16;
            boolean hov = mouseX >= cx && mouseX < cx + w && mouseY >= iy && mouseY < iy + 16;
            if (hov) {
                graphics.fill(cx + 1, iy, cx + w - 1, iy + 16, 0xFF1E4E78);
                graphics.fill(cx + 2, iy + 1, cx + 4, iy + 15, 0xFF4FC1FF);
            }
            graphics.drawString(font, item, cx + 10, iy + 4, hov ? 0xFFFFFFFF : CS_NORMAL, false);
        }
    }

    private void renderSearch(GuiGraphics graphics) {
        // search is rendered inline inside renderEditor; nothing extra needed here
    }

    private void syncFromTab() {
        EditorTab t = tabs.get(activeTab);
        lines         = t.lines();
        currentPath   = t.path();
        currentReadOnly = t.readOnly();
        editorScroll  = t.scroll();
        cursorLine    = t.cursorLine();
        cursorColumn  = t.cursorCol();
        dirty         = t.dirty();
        undoStack     = t.undoStack();
        redoStack     = t.redoStack();
        selAnchorLine = -1;
        selAnchorCol  = -1;
        hScroll       = 0;
    }

    private void syncToTab() {
        EditorTab old = tabs.get(activeTab);
        tabs.set(activeTab, new EditorTab(currentPath, lines, currentReadOnly,
            dirty, cursorLine, cursorColumn, editorScroll, undoStack, redoStack));
    }

    private void switchTab(int index) {
        syncToTab();
        boolean leavingConsole = isConsolePath(currentPath) && index != activeTab;
        if (leavingConsole) {
            releaseConsoleInputs();
            refreshOpenTabsAfterConsole();
        }
        activeTab = Mth.clamp(index, 0, tabs.size() - 1);
        syncFromTab();
        if (isConsolePath(currentPath)) focusConsole();
        else focusEditor();
        if (isConsoleFocused()) requestConsoleState();
    }

    private void closeTab(int index) {
        boolean closingConsole = index >= 0 && index < tabs.size() && isConsolePath(tabs.get(index).path());
        if (closingConsole) {
            releaseConsoleInputs();
            refreshOpenTabsAfterConsole();
        }
        if (tabs.size() == 1) {
            // Reset rather than close
            tabs.set(0, EditorTab.blank());
            activeTab = 0;
            syncFromTab();
            clearCideFocus(FOCUS_CONSOLE);
            return;
        }
        tabs.remove(index);
        activeTab = Mth.clamp(activeTab, 0, tabs.size() - 1);
        syncFromTab();
        if (isConsolePath(currentPath)) focusConsole();
        else focusEditor();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key != GLFW.GLFW_KEY_DELETE && key != GLFW.GLFW_KEY_ENTER && key != GLFW.GLFW_KEY_KP_ENTER) disarmTreeDelete();

        if (key == 256) { // Escape
            if (!acSuggestions.isEmpty())       { acSuggestions.clear(); acIndex = -1; return true; }
            if (editorContextOpen)              { editorContextOpen = false; return true; }
            if (contextOpen)                    { contextOpen = false; return true; }
            if (inlineMode != InlineMode.NONE)  { inlineMode = InlineMode.NONE; inlineText = ""; return true; }
            if (searchOpen)                     { searchOpen = false; clearCideFocus(FOCUS_SEARCH); searchMatches.clear(); return true; }
            onClose(); return true;
        }

        if (inlineMode != InlineMode.NONE) return handleInlineKey(key);
        if (searchOpen && isSearchFocused())   return handleSearchKey(key);

        if (key == GLFW.GLFW_KEY_F5 && canRunCurrentFile()) {
            launchActiveFile();
            return true;
        }

        if (splitConsoleOpen && isSplitConsoleFocused() && !isConsolePath(currentPath)) {
            if (Screen.isPaste(key)) {
                sendConsolePaste(Minecraft.getInstance().keyboardHandler.getClipboard());
                return true;
            }
            boolean repeat = !consoleKeysDown.add(key);
            sendConsoleInput(ConsoleInputPayload.KEY_DOWN, key, repeat ? 1 : 0, 0, 0, "");
            return true;
        }

        if (isConsolePath(currentPath) && isConsoleFocused()) {
            if (Screen.isPaste(key)) {
                sendConsolePaste(Minecraft.getInstance().keyboardHandler.getClipboard());
                return true;
            }
            boolean repeat = !consoleKeysDown.add(key);
            sendConsoleInput(ConsoleInputPayload.KEY_DOWN, key, repeat ? 1 : 0, 0, 0, "");
            return true;
        }

        // Global shortcuts
        if (Screen.hasControlDown() && key == 83) { save(); return true; }        // Ctrl+S
        if (Screen.hasControlDown() && key == 70) { // Ctrl+F
            if (searchOpen) { focusSearch(); } else { openSearch(); }
            return true;
        }
        if (Screen.hasControlDown() && key == 87) { closeTab(activeTab); return true; } // Ctrl+W
        if (Screen.hasControlDown() && key == 61) { adjustScale( IDE_SCALE_STEP); return true; } // Ctrl+=
        if (Screen.hasControlDown() && key == 45) { adjustScale(-IDE_SCALE_STEP); return true; } // Ctrl+-

        if (handleTreeDeleteKey(key)) return true;
        if (handleTreeShortcut(key)) return true;

        // Tab switching Ctrl+1..9
        if (Screen.hasControlDown() && key >= 49 && key <= 57) {
            int ti = key - 49;
            if (ti < tabs.size()) switchTab(ti);
            return true;
        }

        if (isEditorFocused()) return handleEditorKey(key);
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (splitConsoleOpen && isSplitConsoleFocused() && !isConsolePath(currentPath)) {
            consoleKeysDown.remove(key);
            sendConsoleInput(ConsoleInputPayload.KEY_UP, key, 0, 0, 0, "");
            return true;
        }
        if (isConsolePath(currentPath) && isConsoleFocused()) {
            consoleKeysDown.remove(key);
            sendConsoleInput(ConsoleInputPayload.KEY_UP, key, 0, 0, 0, "");
            return true;
        }
        return super.keyReleased(key, scan, mods);
    }

    private boolean handleEditorKey(int key) {
        boolean ctrl  = Screen.hasControlDown();
        boolean shift = Screen.hasShiftDown();

        // Clamp cursor to current document bounds - guards against stale state
        // left over from a tab switch that fires mid-keyrepeat (e.g. crash on backspace).
        if (lines != null && !lines.isEmpty()) {
            cursorLine   = Mth.clamp(cursorLine, 0, lines.size() - 1);
            cursorColumn = Mth.clamp(cursorColumn, 0, lines.get(cursorLine).length());
        }

        // Autocomplete navigation intercepts Up/Down/Tab when dropdown is open
        if (!acSuggestions.isEmpty()) {
            if (key == 264) { acIndex = (acIndex + 1) % acSuggestions.size(); return true; }
            if (key == 265) { acIndex = (acIndex - 1 + acSuggestions.size()) % acSuggestions.size(); return true; }
            if (key == 258) { acceptAutocomplete(); return true; }
        }

        if (ctrl) {
            switch (key) {
                case 65 -> { selectAll(); return true; }
                case 67 -> { copyToClipboard(); return true; }
                case 88 -> { cutToClipboard(); return true; }
                case 86 -> {
                    if (!currentReadOnly && writesEnabled) {
                        String paste = Minecraft.getInstance().keyboardHandler.getClipboard();
                        if (!hasSpaceForEdit(paste.getBytes(StandardCharsets.UTF_8).length)) {
                            status = "Denied: not enough disk space";
                            return true;
                        }
                        pushMilestone(); // one milestone covers both the delete and the insert
                        if (hasSelection()) deleteSelection();
                        insertRaw(paste);
                    }
                    return true;
                }
                case 90 -> { undo(); return true; }
                case 89 -> { redo(); return true; }
            }
        }

        switch (key) {
            case 258 -> { // Tab - insert 2 spaces
                if (!currentReadOnly && writesEnabled) {
                    if (!hasSpaceForEdit(2)) { status = "Denied: not enough disk space"; return true; }
                    pushMilestone(); if (hasSelection()) deleteSelection(); insertRaw("    ");
                }
                return true;
            }
            case 257, 335 -> {
                acSuggestions.clear(); acIndex = -1;
                if (!currentReadOnly && writesEnabled) {
                    if (!hasSpaceForEdit(1)) { status = "Denied: not enough disk space"; return true; }
                    pushMilestone(); if (hasSelection()) deleteSelection(); newline();
                }
                return true;
            }
            case 259 -> {
                acSuggestions.clear(); acIndex = -1;
                if (!currentReadOnly && writesEnabled) {
                    if (hasSelection()) { pushMilestone(); deleteSelection(); }
                    else { pushMilestone(); backspace(); }
                    rebuildAutocomplete();
                }
                return true;
            }
            case 261 -> { acSuggestions.clear(); acIndex = -1; if (!currentReadOnly && writesEnabled) { if (hasSelection()) { pushMilestone(); deleteSelection(); } else { pushMilestone(); delForward(); } } return true; }
            case 262 -> { acSuggestions.clear(); acIndex = -1; if (ctrl) movRightWord(shift); else movRight(shift); return true; }
            case 263 -> { acSuggestions.clear(); acIndex = -1; if (ctrl) movLeftWord(shift);  else movLeft(shift);  return true; }
            case 264 -> { movDown(shift);  return true; }
            case 265 -> { movUp(shift);    return true; }
            case 268 -> { acSuggestions.clear(); acIndex = -1; movHome(shift);  return true; }
            case 269 -> { acSuggestions.clear(); acIndex = -1; movEnd(shift);   return true; }
            case 266 -> { acSuggestions.clear(); acIndex = -1; for (int i = 0; i < 10; i++) movUp(shift);   return true; }
            case 267 -> { acSuggestions.clear(); acIndex = -1; for (int i = 0; i < 10; i++) movDown(shift); return true; }
        }
        return false;
    }

    private boolean handleInlineKey(int key) {
        switch (key) {
            case 257, 335 -> { confirmInlineInput(); return true; }
            case 259 -> { if (!inlineText.isEmpty()) inlineText = inlineText.substring(0, inlineText.length() - 1); return true; }
        }
        return false;
    }

    private boolean handleSearchKey(int key) {
        boolean shift = Screen.hasShiftDown();
        boolean ctrl  = Screen.hasControlDown();
        switch (key) {
            case 257, 335 -> { nextMatch(); return true; }
            case 259 -> { // Backspace
                if (searchHasSel()) deleteSearchSel();
                else if (searchCursorPos > 0) {
                    searchQuery = searchQuery.substring(0, searchCursorPos - 1) + searchQuery.substring(searchCursorPos);
                    searchCursorPos--; rebuildMatches();
                }
                return true;
            }
            case 261 -> { // Delete
                if (searchHasSel()) deleteSearchSel();
                else if (searchCursorPos < searchQuery.length()) {
                    searchQuery = searchQuery.substring(0, searchCursorPos) + searchQuery.substring(searchCursorPos + 1);
                    rebuildMatches();
                }
                return true;
            }
            case 263 -> { // Left
                if (!shift) searchSelAnchor = -1; else searchAnchorIfNeeded();
                if (searchCursorPos > 0) searchCursorPos--;
                return true;
            }
            case 262 -> { // Right
                if (!shift) searchSelAnchor = -1; else searchAnchorIfNeeded();
                if (searchCursorPos < searchQuery.length()) searchCursorPos++;
                return true;
            }
            case 268 -> { // Home
                if (!shift) searchSelAnchor = -1; else searchAnchorIfNeeded();
                searchCursorPos = 0; return true;
            }
            case 269 -> { // End
                if (!shift) searchSelAnchor = -1; else searchAnchorIfNeeded();
                searchCursorPos = searchQuery.length(); return true;
            }
            case 65 -> { // Ctrl+A
                if (ctrl) { searchSelAnchor = 0; searchCursorPos = searchQuery.length(); } return true;
            }
            case 67 -> { // Ctrl+C
                if (ctrl && searchHasSel()) {
                    int s = Math.min(searchSelAnchor, searchCursorPos);
                    int e = Math.max(searchSelAnchor, searchCursorPos);
                    minecraft.keyboardHandler.setClipboard(searchQuery.substring(s, e));
                }
                return ctrl;
            }
            case 88 -> { // Ctrl+X
                if (ctrl && searchHasSel()) {
                    int s = Math.min(searchSelAnchor, searchCursorPos);
                    int e = Math.max(searchSelAnchor, searchCursorPos);
                    minecraft.keyboardHandler.setClipboard(searchQuery.substring(s, e));
                    deleteSearchSel();
                }
                return ctrl;
            }
            case 86 -> { // Ctrl+V
                if (ctrl) {
                    if (searchHasSel()) deleteSearchSel();
                    String clip = minecraft.keyboardHandler.getClipboard().replace("\n", "").replace("\r", "");
                    searchQuery = searchQuery.substring(0, searchCursorPos) + clip + searchQuery.substring(searchCursorPos);
                    searchCursorPos += clip.length();
                    rebuildMatches();
                }
                return ctrl;
            }
        }
        return false;
    }

    private boolean searchHasSel() { return searchSelAnchor >= 0 && searchSelAnchor != searchCursorPos; }
    private void searchAnchorIfNeeded() { if (searchSelAnchor < 0) searchSelAnchor = searchCursorPos; }

    private void deleteSearchSel() {
        int s = Math.min(searchSelAnchor, searchCursorPos);
        int e = Math.max(searchSelAnchor, searchCursorPos);
        searchQuery = searchQuery.substring(0, s) + searchQuery.substring(e);
        searchCursorPos = s; searchSelAnchor = -1; rebuildMatches();
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (inlineMode != InlineMode.NONE) {
            if (c >= 32 && c < 127) inlineText += c;
            return true;
        }
        if (searchOpen && isSearchFocused()) {
            if (c >= 32) {
                if (searchHasSel()) deleteSearchSel();
                searchQuery = searchQuery.substring(0, searchCursorPos) + c + searchQuery.substring(searchCursorPos);
                searchCursorPos++; rebuildMatches();
            }
            return true;
        }
        if (splitConsoleOpen && isSplitConsoleFocused() && !isConsolePath(currentPath)) {
            sendConsoleInput(ConsoleInputPayload.CHAR, c, 0, 0, 0, "");
            return true;
        }
        if (isConsolePath(currentPath) && isConsoleFocused()) {
            sendConsoleInput(ConsoleInputPayload.CHAR, c, 0, 0, 0, "");
            return true;
        }
        if (!isEditorFocused() || currentReadOnly || !writesEnabled || currentPath.isBlank()) return false;
        if (c >= 32) {
            // Skip over auto-inserted closing character instead of double-inserting
            if ((c == ')' || c == ']' || c == '}' || c == '"' || c == '\'')
                    && cursorColumn < lines.get(cursorLine).length()
                    && lines.get(cursorLine).charAt(cursorColumn) == c) {
                cursorColumn++;
                keepVisible();
                return true;
            }

            int charBytes = c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            boolean willAutoClose = (c == '(' || c == '[' || c == '{' || c == '"' || c == '\'');
            if (!hasSpaceForEdit(charBytes + (willAutoClose ? 1 : 0))) {
                status = "Denied: not enough disk space";
                return true;
            }

            if (hasSelection()) { pushMilestone(); deleteSelection(); }
            insertChar(c);

            // Auto-close bracket/quote pairs
            char close = switch (c) {
                case '(' -> ')';
                case '[' -> ']';
                case '{' -> '}';
                case '"' -> '"';
                case '\'' -> '\'';
                default  -> 0;
            };
            if (close != 0) {
                String ln = lines.get(cursorLine);
                boolean atEnd = cursorColumn >= ln.length();
                char next = atEnd ? 0 : ln.charAt(cursorColumn);
                boolean shouldClose = atEnd || next == ' ' || next == '\t'
                        || next == ')' || next == ']' || next == '}' || next == ',';
                if (shouldClose) {
                    lines.set(cursorLine, ln.substring(0, cursorColumn) + close + ln.substring(cursorColumn));
                    dirty = true;
                }
            }

            if (isIdentChar(c) || c == '.' || c == ':' || c == '"' || c == '\'' || c == '-') rebuildAutocomplete();
            else { acSuggestions.clear(); acIndex = -1; }
        }
        return true;
    }

    private void pushMilestone() {
        if (undoStack.size() >= MAX_UNDO) undoStack.pollLast();
        undoStack.push(new ArrayList<>(lines));
        redoStack.clear(); // any new edit invalidates redo history
        lastUndoPush = System.currentTimeMillis();
    }

    private void pushIfStale() {
        if (System.currentTimeMillis() - lastUndoPush > 500) pushMilestone();
    }

    private void undo() {
        if (undoStack.isEmpty()) { status = "Nothing to undo"; return; }
        if (redoStack.size() >= MAX_UNDO) redoStack.pollLast();
        redoStack.push(new ArrayList<>(lines));
        lines.clear(); lines.addAll(undoStack.pop());
        cursorLine   = Math.min(cursorLine, lines.size() - 1);
        cursorColumn = Math.min(cursorColumn, lines.get(cursorLine).length());
        selAnchorLine = -1;
        dirty = true;
        keepVisible();
    }

    private void redo() {
        if (redoStack.isEmpty()) { status = "Nothing to redo"; return; }
        if (undoStack.size() >= MAX_UNDO) undoStack.pollLast();
        undoStack.push(new ArrayList<>(lines));
        lines.clear(); lines.addAll(redoStack.pop());
        cursorLine   = Math.min(cursorLine, lines.size() - 1);
        cursorColumn = Math.min(cursorColumn, lines.get(cursorLine).length());
        selAnchorLine = -1;
        dirty = true;
        keepVisible();
    }

    private void insertChar(char c) {
        pushIfStale();
        String line = lines.get(cursorLine);
        lines.set(cursorLine, line.substring(0, cursorColumn) + c + line.substring(cursorColumn));
        cursorColumn++;
        dirty = true;
        keepVisible();
    }

    /** Insert text at cursor without pushing a milestone - caller must push first. */
    private void insertRaw(String text) {
        String[] parts = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (parts.length == 0) return;
        String currentLine = lines.get(cursorLine);
        String before = currentLine.substring(0, cursorColumn);
        String after  = currentLine.substring(cursorColumn);
        lines.set(cursorLine, before + parts[0] + (parts.length == 1 ? after : ""));
        for (int i = 1; i < parts.length; i++) {
            lines.add(cursorLine + i, i == parts.length - 1 ? parts[i] + after : parts[i]);
        }
        if (parts.length > 1) {
            cursorLine   += parts.length - 1;
            cursorColumn  = parts[parts.length - 1].length();
        } else {
            cursorColumn = before.length() + parts[0].length();
        }
        dirty = true;
        keepVisible();
    }

    private void newline() {
        String line = lines.get(cursorLine);
        // auto-indent
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t'); i++)
            indent.append(line.charAt(i));
        lines.set(cursorLine, line.substring(0, cursorColumn));
        lines.add(cursorLine + 1, indent + line.substring(cursorColumn));
        cursorLine++; cursorColumn = indent.length();
        dirty = true; keepVisible();
    }

    private void backspace() {
        if (cursorLine < 0 || cursorLine >= lines.size()) return;

        String line = lines.get(cursorLine);
        cursorColumn = Math.max(0, Math.min(cursorColumn, line.length()));

        if (cursorColumn > 0) {
            lines.set(cursorLine,
                line.substring(0, cursorColumn - 1) + line.substring(cursorColumn)
            );
            cursorColumn--;
            dirty = true;
        } else if (cursorLine > 0) {
            int ol = lines.get(cursorLine - 1).length();
            lines.set(cursorLine - 1, lines.get(cursorLine - 1) + lines.remove(cursorLine));
            cursorLine--;
            cursorColumn = ol;
            dirty = true;
        }

        keepVisible();
    }
    
    private void delForward() {
        String line = lines.get(cursorLine);
        if (cursorColumn < line.length()) {
            lines.set(cursorLine, line.substring(0, cursorColumn) + line.substring(cursorColumn + 1));
            dirty = true;
        } else if (cursorLine + 1 < lines.size()) {
            lines.set(cursorLine, line + lines.remove(cursorLine + 1));
            dirty = true;
        }
        keepVisible();
    }

    private boolean hasSelection() { return selAnchorLine >= 0; }
    private void clearSel() { selAnchorLine = -1; selAnchorCol = -1; }

    private void selectAll() {
        selAnchorLine = 0; selAnchorCol = 0;
        cursorLine = lines.size() - 1;
        cursorColumn = lines.get(cursorLine).length();
        keepVisible();
    }

    private String getSelText() {
        if (!hasSelection()) return lines.get(cursorLine);
        int l1, c1, l2, c2;
        if (selAnchorLine < cursorLine || (selAnchorLine == cursorLine && selAnchorCol <= cursorColumn)) {
            l1=selAnchorLine; c1=selAnchorCol; l2=cursorLine; c2=cursorColumn;
        } else {
            l1=cursorLine; c1=cursorColumn; l2=selAnchorLine; c2=selAnchorCol;
        }
        if (l1 == l2) return lines.get(l1).substring(c1, c2);
        StringBuilder sb = new StringBuilder();
        sb.append(lines.get(l1).substring(c1)).append('\n');
        for (int i = l1+1; i < l2; i++) sb.append(lines.get(i)).append('\n');
        sb.append(lines.get(l2).substring(0, c2));
        return sb.toString();
    }

    private void deleteSelection() {
        if (!hasSelection()) return;
        int l1, c1, l2, c2;
        if (selAnchorLine < cursorLine || (selAnchorLine == cursorLine && selAnchorCol <= cursorColumn)) {
            l1=selAnchorLine; c1=selAnchorCol; l2=cursorLine; c2=cursorColumn;
        } else {
            l1=cursorLine; c1=cursorColumn; l2=selAnchorLine; c2=selAnchorCol;
        }
        if (l1 == l2) {
            String ln = lines.get(l1);
            lines.set(l1, ln.substring(0, c1) + ln.substring(c2));
        } else {
            String start = lines.get(l1).substring(0, c1);
            String end   = lines.get(l2).substring(c2);
            lines.set(l1, start + end);
            lines.subList(l1+1, l2+1).clear();
        }
        cursorLine = l1; cursorColumn = c1;
        clearSel(); dirty = true; keepVisible();
    }

    private void copyToClipboard() {
        Minecraft.getInstance().keyboardHandler.setClipboard(getSelText());
        status = "Copied";
    }

    private void cutToClipboard() {
        if (currentReadOnly || !writesEnabled) return;
        Minecraft.getInstance().keyboardHandler.setClipboard(getSelText());
        if (hasSelection()) { pushMilestone(); deleteSelection(); }
        status = "Cut";
    }

    private void movLeft(boolean ext) {
        if (!ext) clearSel(); else if (!hasSelection()) { selAnchorLine=cursorLine; selAnchorCol=cursorColumn; }
        if (cursorColumn > 0) cursorColumn--;
        else if (cursorLine > 0) { cursorLine--; cursorColumn = lines.get(cursorLine).length(); }
        keepVisible();
    }
    private void movRight(boolean ext) {
        if (!ext) clearSel(); else if (!hasSelection()) { selAnchorLine=cursorLine; selAnchorCol=cursorColumn; }
        if (cursorColumn < lines.get(cursorLine).length()) cursorColumn++;
        else if (cursorLine+1 < lines.size()) { cursorLine++; cursorColumn = 0; }
        keepVisible();
    }

    private void movLeftWord(boolean ext) {
        if (!ext) clearSel(); else if (!hasSelection()) { selAnchorLine=cursorLine; selAnchorCol=cursorColumn; }
        if (cursorColumn == 0) {
            if (cursorLine > 0) { cursorLine--; cursorColumn = lines.get(cursorLine).length(); }
        } else {
            String line = lines.get(cursorLine);
            int i = cursorColumn;
            while (i > 0 && !isIdentChar(line.charAt(i - 1))) i--;
            while (i > 0 &&  isIdentChar(line.charAt(i - 1))) i--;
            cursorColumn = i;
        }
        keepVisible();
    }

    private void movRightWord(boolean ext) {
        if (!ext) clearSel(); else if (!hasSelection()) { selAnchorLine=cursorLine; selAnchorCol=cursorColumn; }
        String line = lines.get(cursorLine);
        if (cursorColumn >= line.length()) {
            if (cursorLine + 1 < lines.size()) { cursorLine++; cursorColumn = 0; }
        } else {
            int i = cursorColumn;
            int n = line.length();
            while (i < n && !isIdentChar(line.charAt(i))) i++;
            while (i < n &&  isIdentChar(line.charAt(i))) i++;
            cursorColumn = i;
        }
        keepVisible();
    }
    private void movUp(boolean ext) {
        if (!ext) clearSel(); else if (!hasSelection()) { selAnchorLine=cursorLine; selAnchorCol=cursorColumn; }
        cursorLine = Math.max(0, cursorLine-1);
        cursorColumn = Math.min(cursorColumn, lines.get(cursorLine).length());
        keepVisible();
    }
    private void movDown(boolean ext) {
        if (!ext) clearSel(); else if (!hasSelection()) { selAnchorLine=cursorLine; selAnchorCol=cursorColumn; }
        if (!ext && cursorLine == lines.size() - 1 && !currentReadOnly && writesEnabled && !currentPath.startsWith("wiki:")) {
            lines.add("");
            cursorLine++;
            cursorColumn = 0;
            dirty = true;
        } else {
            cursorLine = Math.min(lines.size() - 1, cursorLine + 1);
            cursorColumn = Math.min(cursorColumn, lines.get(cursorLine).length());
        }
        keepVisible();
    }
    private void movHome(boolean ext) {
        if (!ext) clearSel(); else if (!hasSelection()) { selAnchorLine=cursorLine; selAnchorCol=cursorColumn; }
        String line = lines.get(cursorLine);
        int first = 0;
        while (first < line.length() && Character.isWhitespace(line.charAt(first))) first++;
        cursorColumn = (cursorColumn == first) ? 0 : first;
        keepVisible();
    }
    private void movEnd(boolean ext) {
        if (!ext) clearSel(); else if (!hasSelection()) { selAnchorLine=cursorLine; selAnchorCol=cursorColumn; }
        cursorColumn = lines.get(cursorLine).length();
        keepVisible();
    }

    private void keepVisible() {
        int visible = Math.max(1, (int)((panelH - TOP_H - TAB_H - 30) / (LINE_H * ideScale)));
        if (cursorLine < editorScroll) editorScroll = cursorLine;
        if (cursorLine >= editorScroll + visible) editorScroll = cursorLine - visible + 1;
        // Horizontal: scroll so the cursor column stays in view
        if (lines != null && cursorLine < lines.size()) {
            String cl = lines.get(cursorLine);
            int curX = editorTextWidth(cl.substring(0, Math.min(cursorColumn, cl.length())));
            int taW  = Math.max(20, editorTextRightLogical() - (editorX + 5 + LINE_NUM_W));
            if (curX < hScroll) hScroll = curX;
            if (curX > hScroll + taW - 8) hScroll = curX - taW + 8;
        }
    }

    private void keepSearchMatchComfortable() {
        int visible = Math.max(1, (int)((panelH - TOP_H - TAB_H - 30) / (LINE_H * ideScale)));
        int targetTop = cursorLine - Math.max(2, visible / 3);
        editorScroll = Mth.clamp(targetTop, 0, Math.max(0, lines.size() - visible));
        keepVisible();
    }

    private void openSearch() {
        searchOpen = !searchOpen;
        if (searchOpen) {
            searchQuery = ""; searchMatches.clear();
            searchCursorPos = 0; searchSelAnchor = -1;
            focusSearch();
        } else {
            clearCideFocus(FOCUS_SEARCH);
        }
    }

    private void rebuildMatches() {
        searchMatches.clear(); searchMatchIndex = 0;
        if (searchQuery.isEmpty()) return;
        String ql = searchQuery.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.size(); i++) {
            String ll = lines.get(i).toLowerCase(Locale.ROOT);
            int fi = 0;
            while ((fi = ll.indexOf(ql, fi)) >= 0) {
                searchMatches.add(new int[]{i, fi});
                fi += searchQuery.length();
            }
        }
        if (!searchMatches.isEmpty()) { cursorLine = searchMatches.get(0)[0]; cursorColumn = searchMatches.get(0)[1]; keepSearchMatchComfortable(); }
    }

    private void nextMatch() {
        if (searchMatches.isEmpty()) return;
        searchMatchIndex = (searchMatchIndex + 1) % searchMatches.size();
        cursorLine = searchMatches.get(searchMatchIndex)[0];
        cursorColumn = searchMatches.get(searchMatchIndex)[1];
        keepSearchMatchComfortable();
    }

    private void save() {
        if (currentPath.isBlank() || currentPath.startsWith("wiki:") || isConsolePath(currentPath)) return;
        if (currentReadOnly || !writesEnabled) { status = "Read-only"; return; }
        byte[] bytes = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > writableBytesForCurrentFile()) {
            status = "Denied: not enough disk space";
            return;
        }
        int totalChunks = Math.max(1, (bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            byte[] chunk = Arrays.copyOfRange(bytes, start, Math.min(start + CHUNK_SIZE, bytes.length));
            PacketDistributor.sendToServer(new WriteChunkPayload(pos, computerId, currentPath, i, totalChunks, chunk));
        }
        dirty = false; syncToTab(); status = "Saved " + currentPath;
    }

    private boolean hasSpaceForEdit(int addedBytes) {
        if (freeSpace < 0 || currentPath.isBlank() || currentPath.startsWith("wiki:") || isConsolePath(currentPath)) return true;
        byte[] currentBytes = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
        int selectionBytes = hasSelection() ? getSelText().getBytes(StandardCharsets.UTF_8).length : 0;
        long estimated = Math.max(0, (long) currentBytes.length - selectionBytes + addedBytes);
        return estimated <= writableBytesForCurrentFile();
    }

    private long writableBytesForCurrentFile() {
        if (freeSpace < 0) return Long.MAX_VALUE;
        return Math.max(0, freeSpace) + knownFileSize(currentPath);
    }

    private long knownFileSize(String path) {
        if (path == null || path.isBlank()) return 0;
        for (FileListPayload.Entry entry : entries) {
            if (path.equals(entry.path()) && !entry.directory()) return Math.max(0, entry.size());
        }
        for (List<FileListPayload.Entry> list : dirContents.values()) {
            for (FileListPayload.Entry entry : list) {
                if (path.equals(entry.path()) && !entry.directory()) return Math.max(0, entry.size());
            }
        }
        return 0;
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        if (!armedDeletePath.isBlank() && now > armedDeleteUntil) disarmTreeDelete();
        if ((isConsolePath(currentPath) || splitConsoleOpen) && now - lastConsolePoll >= 250L) {
            requestConsoleState();
            lastConsolePoll = now;
        }
        if (now - lastAutoSync >= 5_000L) {
            if (dirty) save();
            requestList("");
            for (String dir : expandedDirs) requestList(dir);
            saveSession();
            lastAutoSync = now;
        }
    }

    private void requestList(String path) { PacketDistributor.sendToServer(new ListFilesPayload(pos, computerId, path)); }
    private void requestRead(String path)  {
        if (path == null || path.isBlank() || missingReadPaths.contains(path)) return;
        PacketDistributor.sendToServer(new ReadFilePayload(pos, computerId, path));
    }
    private void requestConsoleState() {
        if (!adminView) PacketDistributor.sendToServer(new ConsolePollPayload(pos, computerId, sessionId));
    }

    private boolean canRunCurrentFile() {
        return !adminView && !currentPath.isBlank() && !currentPath.startsWith("wiki:") && !isConsolePath(currentPath);
    }

    private boolean canShowRunButtonTooltip() {
        return !currentPath.isBlank() && !currentPath.startsWith("wiki:") && !isConsolePath(currentPath);
    }

    private void launchActiveFile() {
        if (!canRunCurrentFile()) return;
        if (dirty) save();
        clearPaused();
        splitConsoleOpen = true;
        focusSplitConsole();
        noteConsoleMayHaveChangedFiles();
        PacketDistributor.sendToServer(new RunProgramPayload(pos, computerId, sessionId, currentPath));
        requestConsoleState();
        status = "Running " + currentPath;
    }

    private void sendConsoleInput(int action, int a, int b, int x, int y, String text) {
        if (action == ConsoleInputPayload.KEY_DOWN || action == ConsoleInputPayload.CHAR
                || action == ConsoleInputPayload.PASTE) {
            noteConsoleMayHaveChangedFiles();
        }
        PacketDistributor.sendToServer(new ConsoleInputPayload(pos, computerId, sessionId, action, a, b, x, y,
            text == null ? "" : text));
    }

    private void sendConsolePaste(String text) {
        if (text == null || text.isEmpty()) return;
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_CONSOLE_PASTE_BYTES) {
            showFade("Paste rejected; Too Long.");
            return;
        }
        sendConsoleInput(ConsoleInputPayload.PASTE, 0, 0, 0, 0, text);
    }

    private void sendConsoleAction(int action) {
        if (action == ConsoleActionPayload.TERMINATE || action == ConsoleActionPayload.TURN_ON
                || action == ConsoleActionPayload.SHUTDOWN || action == ConsoleActionPayload.REBOOT) {
            noteConsoleMayHaveChangedFiles();
        }
        PacketDistributor.sendToServer(new ConsoleActionPayload(pos, computerId, sessionId, action));
    }

    private void releaseConsoleInputs() {
        if (consoleKeysDown.isEmpty() && consoleMouseDown < 0) return;
        consoleKeysDown.clear();
        consoleMouseDown = -1;
        sendConsoleAction(ConsoleActionPayload.RELEASE_INPUTS);
    }

    private void noteConsoleMayHaveChangedFiles() {
        consoleMayHaveChangedFiles = true;
    }

    private void refreshOpenTabsAfterConsole() {
        if (!consoleMayHaveChangedFiles) return;
        consoleMayHaveChangedFiles = false;
        boolean dirtyOpenFile = false;
        for (EditorTab tab : tabs) {
            String path = tab.path();
            if (path.isBlank() || path.startsWith("wiki:") || isConsolePath(path)) continue;
            if (tab.dirty()) {
                dirtyOpenFile = true;
                continue;
            }
            silentReloadPaths.add(path);
            requestRead(path);
        }
        if (dirtyOpenFile) status = "Console may have changed files; dirty tabs were not reloaded";
    }

    private void deleteCtxEntry() {
        if (contextEntry == null) return;
        PacketDistributor.sendToServer(new DeleteFilePayload(pos, computerId, contextEntry.path()));
        if (contextEntry.path().equals(armedDeletePath)) disarmTreeDelete();
        if (contextEntry.path().equals(currentPath)) {
            currentPath = ""; lines.clear(); lines.add(""); dirty = false;
        }
    }

    private void copyCtxEntry() {
        if (contextEntry == null || contextEntry.directory()) return;
        copiedPath = contextEntry.path(); status = "Copied " + copiedPath;
    }

    private void pasteCopied() {
        if (copiedPath.isBlank()) return;
        PacketDistributor.sendToServer(new CopyFilePayload(pos, computerId, copiedPath, contextDirectory()));
    }

    private boolean handleTreeShortcut(int key) {
        if (!Screen.hasControlDown()) return false;
        if (key == 67 && selectedEntry != null && !selectedEntry.directory()
                && (isTreeFocused() || !hasSelection())) {
            copiedPath = selectedEntry.path();
            status = "Copied " + copiedPath;
            focusTree();
            return true;
        }
        if (key == 86 && isTreeFocused() && !copiedPath.isBlank()) {
            PacketDistributor.sendToServer(new CopyFilePayload(pos, computerId, copiedPath, selectedDirectory()));
            return true;
        }
        return false;
    }

    private boolean handleTreeDeleteKey(int key) {
        boolean confirmKey = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
        if (key != GLFW.GLFW_KEY_DELETE && !confirmKey) return false;
        if (!isTreeFocused() || selectedEntry == null) return false;
        String path = selectedEntry.path();
        long now = System.currentTimeMillis();
        if (confirmKey && path.equals(armedDeletePath) && now <= armedDeleteUntil) {
            PacketDistributor.sendToServer(new DeleteFilePayload(pos, computerId, path));
            disarmTreeDelete();
            if (path.equals(currentPath)) {
                currentPath = "";
                lines.clear();
                lines.add("");
                dirty = false;
            }
        } else if (key == GLFW.GLFW_KEY_DELETE) {
            armedDeletePath = path;
            armedDeleteUntil = now + 2_000L;
            showFade("Delete " + entryName(selectedEntry) + "? Press ENTER to confirm.");
        } else {
            return false;
        }
        return true;
    }

    private boolean isDeleteArmed(String path) {
        if (armedDeletePath.isBlank()) return false;
        if (System.currentTimeMillis() > armedDeleteUntil) {
            disarmTreeDelete();
            return false;
        }
        return armedDeletePath.equals(path);
    }

    private void disarmTreeDelete() {
        armedDeletePath = "";
        armedDeleteUntil = 0L;
    }

    private String selectedDirectory() {
        if (selectedEntry != null && selectedEntry.directory()) return selectedEntry.path();
        if (selectedEntry != null && selectedEntry.path().contains("/"))
            return selectedEntry.path().substring(0, selectedEntry.path().lastIndexOf('/'));
        return "";
    }

    private String contextDirectory() {
        if (contextEntry != null && contextEntry.directory()) return contextEntry.path();
        if (contextEntry != null && contextEntry.path().contains("/"))
            return contextEntry.path().substring(0, contextEntry.path().lastIndexOf('/'));
        return "";
    }

    private List<String> contextItems() {
        List<String> out = new ArrayList<>();
        if (contextEntry != null && !contextEntry.directory()) {
            out.add("Open"); out.add("Copy File"); out.add("---");
        }
        out.add("New File");
        out.add("New Folder");
        if (!copiedPath.isBlank()) out.add("Paste");
        if (contextEntry != null) { out.add("---"); out.add("Rename"); out.add("Delete"); }
        return out;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int btn) {
        disarmTreeDelete();

        if (editorContextOpen) {
            if (btn == 0 && clickEditorCtx(mouseX, mouseY)) return true;
            editorContextOpen = false;
            if (btn != 1) return true;
        }

        if (contextOpen) {
            if (btn == 0 && clickCtx(mouseX, mouseY)) return true;
            contextOpen = false; return true;
        }

        if (btn == 0 && mouseX >= consoleButtonX && mouseX < consoleButtonX + consoleButtonW
                && mouseY >= consoleButtonY && mouseY < consoleButtonY + consoleButtonH) {
            if (adminView) return true;
            openConsoleTab();
            return true;
        }

        if (btn == 0 && canRunCurrentFile() && inLogicalBox(mouseX, mouseY, runButtonX, runButtonY, runButtonW, runButtonH)) {
            launchActiveFile();
            return true;
        }

        if (btn == 0 && isPaused() && inLogicalBox(mouseX, mouseY, continueBtnX, continueBtnY, continueBtnW, continueBtnH)) {
            sendDebugCommand(dev.kivts.cide.net.payload.DebugCommandPayload.CONTINUE);
            return true;
        }
        if (btn == 0 && isPaused() && inLogicalBox(mouseX, mouseY, stepBtnX, stepBtnY, stepBtnW, stepBtnH)) {
            sendDebugCommand(dev.kivts.cide.net.payload.DebugCommandPayload.STEP);
            return true;
        }

        if (btn == 0 && !currentReadOnly && !currentPath.isBlank() && !currentPath.startsWith("wiki:") && !isConsolePath(currentPath)) {
            int gutterLine = gutterLineAt(mouseX, mouseY);
            if (gutterLine >= 0) {
                toggleBreakpoint(currentPath, gutterLine + 1);
                return true;
            }
        }

        if (btn == 0 && splitConsoleOpen && canRunCurrentFile()
                && inLogicalBox(mouseX, mouseY, splitCloseX, splitCloseY, splitCloseW, splitCloseH)) {
            terminateDebugIfActive();
            releaseConsoleInputs();
            splitConsoleOpen = false;
            focusEditor();
            return true;
        }

        if (splitConsoleOpen && canRunCurrentFile() && inSplitConsole(mouseX, mouseY)) {
            focusSplitConsole();
            if (btn == 0 && inLogicalBox(mouseX, mouseY, consolePowerX, consolePowerY, consolePowerW, consolePowerH)) {
                sendConsoleAction(consoleOn ? ConsoleActionPayload.SHUTDOWN : ConsoleActionPayload.TURN_ON);
                return true;
            }
            if (btn == 0 && inLogicalBox(mouseX, mouseY, consoleTerminateX, consoleTerminateY, consoleTerminateW, consoleTerminateH)) {
                sendConsoleAction(ConsoleActionPayload.TERMINATE);
                return true;
            }
            int[] cell = consoleCell(mouseX, mouseY);
            if (cell != null) {
                if (btn >= 0 && btn <= 2) {
                    consoleMouseDown = btn + 1;
                    consoleLastCellX = cell[0];
                    consoleLastCellY = cell[1];
                    sendConsoleInput(ConsoleInputPayload.MOUSE_CLICK, btn + 1, 0, cell[0], cell[1], "");
                }
                return true;
            }
            return true;
        }

        if (btn == 0 && isSplitConsoleFocused() && !inSplitConsole(mouseX, mouseY)) {
            releaseConsoleInputs();
            clearCideFocus(FOCUS_SPLITSCREEN_CONSOLE);
        }

        if (btn == 0 && isConsolePath(currentPath) && isConsoleFocused() && !inEditor(mouseX, mouseY)) {
            releaseConsoleInputs();
            clearCideFocus(FOCUS_CONSOLE);
        }

        if (isConsolePath(currentPath) && inEditor(mouseX, mouseY)) {
            focusConsole();
            if (btn == 0 && inLogicalBox(mouseX, mouseY, consolePowerX, consolePowerY, consolePowerW, consolePowerH)) {
                sendConsoleAction(consoleOn ? ConsoleActionPayload.SHUTDOWN : ConsoleActionPayload.TURN_ON);
                return true;
            }
            if (btn == 0 && inLogicalBox(mouseX, mouseY, consoleTerminateX, consoleTerminateY, consoleTerminateW, consoleTerminateH)) {
                sendConsoleAction(ConsoleActionPayload.TERMINATE);
                return true;
            }
            int[] cell = consoleCell(mouseX, mouseY);
            if (cell != null) {
                if (btn >= 0 && btn <= 2) {
                    consoleMouseDown = btn + 1;
                    consoleLastCellX = cell[0];
                    consoleLastCellY = cell[1];
                    sendConsoleInput(ConsoleInputPayload.MOUSE_CLICK, btn + 1, 0, cell[0], cell[1], "");
                }
                return true;
            }
            return true;
        }

        if (btn == 1 && inEditor(mouseX, mouseY)) {
            if (lines != null && !currentPath.isBlank() && !currentPath.startsWith("wiki:")) {
                openEditorContext(mouseX, mouseY);
            }
            return true;
        }

        if (!adminView && btn == 0 && mouseX >= lockX && mouseX < lockX + lockW && mouseY >= lockY && mouseY < lockY + lockH) {
            PacketDistributor.sendToServer(new ToggleLockPayload(pos, computerId));
            return true;
        }

        if (btn == 0 && mouseY >= panelY + 4 && mouseY <= panelY + TOP_H - 4
                && mouseX >= scaleSliderX - 2 && mouseX <= scaleSliderX + SCALE_SLIDER_W + 2) {
            scaleDragging = true;
            applySliderDrag(mouseX);
            return true;
        }

        if (btn == 0 && Math.abs(mouseX - (panelX + sidebarW)) <= 3
                && mouseY >= panelY + TOP_H && mouseY < panelY + panelH) {
            sidebarDragging = true;
            return true;
        }

        // Sidebar scrollbar drag (left edge strip)
        int ssbX = panelX + 2;
        int ssbTop = panelY + TOP_H + TAB_H + 6 + LINE_H + 3;
        if (btn == 0 && mouseX >= ssbX && mouseX < ssbX + 4 && mouseY >= ssbTop && mouseY < panelY + panelH - 6) {
            sidebarScrollDragging = true;
            updateSidebarScroll(mouseY);
            return true;
        }

        // Editor scrollbar drag (right edge strip)
        int esbX = splitConsoleOpen && !currentPath.startsWith("wiki:")
            ? logicalToScreenX(editorTextRightLogical()) + 2 : editorX + editorW - 5;
        if (btn == 0 && mouseX >= esbX && mouseX < esbX + 4 && mouseY >= editorY && mouseY < panelY + panelH - 6
                && !currentPath.isBlank()) {
            editorScrollbarDragging = true;
            updateEditorScroll(mouseY);
            return true;
        }

        // Tab bar clicks - adjust for tabScrollPx offset
        if (mouseY >= panelY + TOP_H && mouseY < panelY + TOP_H + TAB_H
                && mouseX >= editorX && mouseX < editorX + editorW) {
            int rmx = (int)(mouseX + tabScrollPx); // unscroll
            int tx  = editorX + 2;
            for (int i = 0; i < tabs.size(); i++) {
                int tw = tabLabelWidth(tabs.get(i));
                if (rmx >= tx && rmx < tx + tw) {
                    int closeX = tx + tw - 15;
                    if (rmx >= closeX && rmx < closeX + 12) closeTab(i);
                    else switchTab(i);
                    return true;
                }
                tx += tw + 2;
            }
        }

        if (btn == 1 && inSidebar(mouseX, mouseY)) {
            int idx = entryIndexAt(mouseY);
            contextEntry = (idx >= 0 && idx < entries.size()) ? entries.get(idx) : null;
            selectedEntry = contextEntry;
            contextX = (int)mouseX; contextY = (int)mouseY;
            contextOpen = true; focusTree(); editorDragging = false;
            editorContextOpen = false;
            return true;
        }

        if (btn == 0 && inSidebar(mouseX, mouseY)) {
            int idx = entryIndexAt(mouseY);
            if (idx >= 0 && idx < entries.size()) {
                selectedEntry = entries.get(idx);
                if (selectedEntry.directory()) {
                    String dp = selectedEntry.path();
                    if (expandedDirs.contains(dp)) {
                        expandedDirs.remove(dp);
                        rebuildEntryList();
                    } else {
                        expandedDirs.add(dp);
                        if (!dirContents.containsKey(dp)) requestList(dp);
                        else rebuildEntryList();
                    }
                } else {
                    draggedEntry = selectedEntry;
                    dragStartX = (int)mouseX; dragStartY = (int)mouseY; dragging = false;
                    requestRead(selectedEntry.path());
                }
            } else {
                selectedEntry = null;
            }
            focusTree(); editorDragging = false; return true;
        }

        // Search bar clicks (uses unscaleEditorX/unscaleEditorY to account for ideScale)
        if (btn == 0 && searchOpen && inEditor(mouseX, mouseY)) {
            int sw  = Math.min(260, editorW - 14);
            int sbY = editorY + 6 + LINE_H + 3;
            int sbX = editorX + 5 + LINE_NUM_W;
            // x close button
            int xbx = sbX + sw - 13;
            if (unscaleEditorX(mouseX) >= xbx - 1 && unscaleEditorX(mouseX) < xbx + 9
                    && unscaleEditorY(mouseY) >= sbY + 1 && unscaleEditorY(mouseY) < sbY + LINE_H + 1) {
                searchOpen = false; clearCideFocus(FOCUS_SEARCH); searchMatches.clear(); return true;
            }
            // Click inside the text area - focus search, position cursor
            if (unscaleEditorX(mouseX) >= sbX && unscaleEditorX(mouseX) < sbX + sw
                    && unscaleEditorY(mouseY) >= sbY && unscaleEditorY(mouseY) < sbY + LINE_H + 2) {
                focusSearch();
                int relX = (int)unscaleEditorX(mouseX) - sbX - 4 - font.width("Find: ");
                searchCursorPos = font.plainSubstrByWidth(searchQuery, Math.max(0, relX)).length();
                searchSelAnchor = -1;
                return true;
            }
        }

        // Right-click in editor: wiki lookup >>> local def jump
        if (btn == 1 && inEditor(mouseX, mouseY)) {
            if (lines != null && !currentPath.isBlank() && !currentPath.startsWith("wiki:")) {
                refreshTypeMap();
                int[] hit = editorHitTest(mouseX, mouseY);
                int row = hit != null ? hit[0] : -1;
                int col = hit != null ? hit[1] : -1;
                String word = (hit != null) ? wordAt(lines.get(row), col) : "";
                if (!word.isEmpty()) {
                    // 1) Direct wiki symbol, or resolve via type map (e.g. "myMon" >>> "monitor" >>> wiki page)
                    String pageKey = WikiRegistry.resolveSymbol(word);
                    if (pageKey == null) {
                        String inferred = typeMap.getOrDefault(word, lastGoodTypeMap.get(word));
                        if (inferred != null) pageKey = WikiRegistry.resolveSymbol(inferred);
                    }
                    // 2) Dot-notation: right-click on "write" in "mon.write" >>> try "mon" >>> resolve type >>> wiki
                    if (pageKey == null) {
                        String ln = lines.get(row);
                        int ws = col;
                        while (ws > 0 && isIdentChar(ln.charAt(ws - 1))) ws--;
                        if (ws > 0 && ln.charAt(ws - 1) == '.') {
                            int oe = ws - 1, os = oe;
                            while (os > 0 && isIdentChar(ln.charAt(os - 1))) os--;
                            String obj = ln.substring(os, oe);
                            if (!obj.isEmpty()) {
                                pageKey = WikiRegistry.resolveSymbol(obj);
                                if (pageKey == null) {
                                    String inferred = typeMap.getOrDefault(obj, lastGoodTypeMap.get(obj));
                                    if (inferred != null) pageKey = WikiRegistry.resolveSymbol(inferred);
                                }
                            }
                        }
                    }
                    if (pageKey != null) {
                        openWikiTab(pageKey);
                    } else {
                        // 3) local definition jump
                        int defLine = findDefinitionLine(word);
                        if (defLine >= 0) {
                            cursorLine = defLine; cursorColumn = 0;
                            keepVisible(); focusEditor();
                        } else {
                            showFade("\"" + word + "\" not defined in this file or has no wiki entry");
                        }
                    }
                }
            }
            return true;
        }

        // Horizontal scrollbar click
        if (btn == 0 && inEditor(mouseX, mouseY) && !currentPath.isBlank() && !currentPath.startsWith("wiki:")) {
            int lBot  = editorY + (int)((panelY + panelH - 12 - editorY) / ideScale);
            int hbY   = lBot - 5;
            int hbX1  = editorX + 5 + LINE_NUM_W;
            int hbX2  = editorTextRightLogical();
            double lx = unscaleEditorX(mouseX), ly = unscaleEditorY(mouseY);
            if (lx >= hbX1 && lx < hbX2 && ly >= hbY && ly < hbY + 5) {
                hScrollDragging    = true;
                hScrollDragStartX  = (int)mouseX;
                hScrollDragStartValue = hScroll;
                return true;
            }
        }

        // Editor click: map screen coords into editor's logical space via unscaleEditorX/unscaleEditorY
  if (btn == 0 && inEditor(mouseX, mouseY)) {
            focusEditor();
            acSuggestions.clear(); acIndex = -1; acIsItemNamespace = false;
            boolean hadSelection = hasSelection();
            int previousAnchorLine = selAnchorLine;
            int previousAnchorCol = selAnchorCol;
            int previousCursorLine = cursorLine;
            int previousCursorCol = cursorColumn;
            int yBase = editorY + 6 + LINE_H + 3 + (searchOpen ? LINE_H + 4 : 0);
            int row   = editorScroll + (int)((unscaleEditorY(mouseY) - yBase) / LINE_H);
            cursorLine   = Mth.clamp(row, 0, lines.size() - 1);
            int rel = Math.max(0, (int)unscaleEditorX(mouseX) - (editorX + 5 + LINE_NUM_W));
            cursorColumn = Mth.clamp(editorTextTruncated(lines.get(cursorLine), rel).length(),
                0, lines.get(cursorLine).length());

            long now = System.currentTimeMillis();
            int[] wordRange = wordRangeAt(lines.get(cursorLine), cursorColumn);
            boolean sameWord = wordRange != null && lastClickLine == cursorLine
                && lastClickWordStart == wordRange[0] && lastClickWordEnd == wordRange[1];
            boolean selectedClickedWord = hadSelection && wordRange != null
                && selectionMatchesRange(cursorLine, wordRange[0], wordRange[1],
                    previousAnchorLine, previousAnchorCol, previousCursorLine, previousCursorCol);
            if (now - lastClickTime < 350 && sameWord) {
                if (selectedClickedWord) {
                    selectChunkAt(cursorLine, cursorColumn);
                } else {
                    selectWordAt(cursorLine, cursorColumn);
                }
                lastClickTime = 0;
            } else {
                if (selectedClickedWord) {
                    selAnchorLine = previousAnchorLine;
                    selAnchorCol = previousAnchorCol;
                    cursorLine = previousCursorLine;
                    cursorColumn = previousCursorCol;
                    editorDragging = false;
                } else {
                    selAnchorLine = cursorLine;
                    selAnchorCol  = cursorColumn;
                    editorDragging = true;
                }
                lastClickTime = now;
                lastClickLine = cursorLine;
                lastClickCol  = selectedClickedWord ? previousCursorCol : cursorColumn;
                lastClickWordStart = wordRange == null ? -1 : wordRange[0];
                lastClickWordEnd = wordRange == null ? -1 : wordRange[1];
            }
            return true;
        }

        if (btn == 0) {
            if (inEditor(mouseX, mouseY)) focusEditor();
            else clearCideFocus(FOCUS_EDITOR);
            if (!isEditorFocused()) editorDragging = false;
        }
        return super.mouseClicked(mouseX, mouseY, btn);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (contextOpen) return true;
        if (splitConsoleOpen && canRunCurrentFile() && inSplitConsole(mouseX, mouseY)) {
            int[] cell = consoleCell(mouseX, mouseY);
            if (cell != null && scrollY != 0) {
                focusSplitConsole();
                sendConsoleInput(ConsoleInputPayload.MOUSE_SCROLL, scrollY < 0 ? 1 : -1, 0, cell[0], cell[1], "");
                return true;
            }
        }
        if (isConsolePath(currentPath) && inEditor(mouseX, mouseY)) {
            int[] cell = consoleCell(mouseX, mouseY);
            if (cell != null && scrollY != 0) {
                sendConsoleInput(ConsoleInputPayload.MOUSE_SCROLL, scrollY < 0 ? 1 : -1, 0, cell[0], cell[1], "");
                return true;
            }
        }
        // Shift+scroll = horizontal pan in the editor
        if (Screen.hasShiftDown() && inEditor(mouseX, mouseY) && !currentPath.isBlank() && !currentPath.startsWith("wiki:")) {
            int step = scrollY < 0 ? 20 : scrollY > 0 ? -20 : 0;
            hScroll = Math.max(0, hScroll + step);
            return true;
        }
        int delta = scrollY < 0 ? 4 : scrollY > 0 ? -4 : 0;
        // Tab bar scroll (horizontal)
        if (mouseY >= panelY + TOP_H && mouseY < panelY + TOP_H + TAB_H && mouseX >= editorX) {
            int totalTabW = 0;
            for (EditorTab t : tabs) totalTabW += tabLabelWidth(t) + 2;
            tabScrollPx = Mth.clamp(tabScrollPx - delta * 20, 0, Math.max(0, totalTabW - Math.max(1, editorW - 22)));
            return true;
        }
        if (inSidebar(mouseX, mouseY)) {
            fileScroll = Mth.clamp(fileScroll + delta, 0, sidebarMaxScroll());
        } else if (inEditor(mouseX, mouseY)) {
            if (currentPath.startsWith("wiki:")) {
                int total = wrapWikiLines(WikiRegistry.getPage(currentPath.substring(5))).size();
                int visible = Math.max(1, (int)((panelY + panelH - 12 - editorY) / (WIKI_LINE_H * ideScale)));
                editorScroll = Mth.clamp(editorScroll + delta, 0, Math.max(0, total - visible));
            } else {
                editorScroll = Mth.clamp(editorScroll + delta, 0, Math.max(0, lines.size() - 1));
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int btn, double dx, double dy) {
        if (splitConsoleOpen && isSplitConsoleFocused() && !isConsolePath(currentPath)) {
            int[] cell = consoleCell(mouseX, mouseY);
            if (cell != null && btn >= 0 && btn <= 2) {
                consoleLastCellX = cell[0];
                consoleLastCellY = cell[1];
                sendConsoleInput(ConsoleInputPayload.MOUSE_DRAG, btn + 1, 0, cell[0], cell[1], "");
                return true;
            }
        }
        if (isConsolePath(currentPath) && isConsoleFocused()) {
            int[] cell = consoleCell(mouseX, mouseY);
            if (cell != null && btn >= 0 && btn <= 2) {
                consoleLastCellX = cell[0];
                consoleLastCellY = cell[1];
                sendConsoleInput(ConsoleInputPayload.MOUSE_DRAG, btn + 1, 0, cell[0], cell[1], "");
                return true;
            }
        }
        if (btn == 0 && hScrollDragging) {
            int textAreaW = Math.max(20, editorTextRightLogical() - (editorX + 5 + LINE_NUM_W));
            int maxLineW  = 0;
            if (lines != null) for (String ln : lines) maxLineW = Math.max(maxLineW, editorTextWidth(ln));
            int hbW  = editorTextRightLogical() - (editorX + 5 + LINE_NUM_W);
            int tW   = Math.max(20, hbW * textAreaW / Math.max(1, maxLineW));
            int range = Math.max(1, maxLineW - textAreaW);
            float ratio = range > 0 ? (float)(hbW - tW) / range : 0;
            if (ratio > 0) hScroll = Mth.clamp(hScrollDragStartValue + (int)((mouseX - hScrollDragStartX) / ratio / ideScale), 0, range);
            return true;
        }
        if (btn == 0 && scaleDragging) {
            applySliderDrag(mouseX);
            return true;
        }
        if (btn == 0 && sidebarDragging) {
            sidebarW = Mth.clamp((int)mouseX - panelX, SIDEBAR_MIN_W, Math.min(SIDEBAR_MAX_W, panelW / 2 - 10));
            editorX  = panelX + sidebarW + 1;
            editorW  = panelW - sidebarW - 1;
            return true;
        }
        if (btn == 0 && sidebarScrollDragging) { updateSidebarScroll(mouseY); return true; }
        if (btn == 0 && editorScrollbarDragging) { updateEditorScroll(mouseY); return true; }
        if (btn == 0 && draggedEntry != null) {
            if (Math.abs(mouseX - dragStartX) + Math.abs(mouseY - dragStartY) > 6) dragging = true;
            return true;
        }
        if (btn == 0 && editorDragging) {
            int yBase = editorY + 6 + LINE_H + 3 + (searchOpen ? LINE_H + 4 : 0);
            int row   = editorScroll + (int)((unscaleEditorY(mouseY) - yBase) / LINE_H);
            cursorLine   = Mth.clamp(row, 0, lines.size() - 1);
            int rel = Math.max(0, (int)unscaleEditorX(mouseX) - (editorX + 5 + LINE_NUM_W));
            cursorColumn = Mth.clamp(editorTextTruncated(lines.get(cursorLine), rel).length(),
                0, lines.get(cursorLine).length());
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int btn) {
        if (splitConsoleOpen && isSplitConsoleFocused() && !isConsolePath(currentPath)) {
            int[] cell = consoleCell(mouseX, mouseY);
            if (btn >= 0 && btn <= 2 && consoleMouseDown == btn + 1) {
                int x = cell == null ? consoleLastCellX : cell[0];
                int y = cell == null ? consoleLastCellY : cell[1];
                sendConsoleInput(ConsoleInputPayload.MOUSE_UP, btn + 1, 0, x, y, "");
                consoleMouseDown = -1;
                return true;
            }
        }
        if (isConsolePath(currentPath) && isConsoleFocused()) {
            int[] cell = consoleCell(mouseX, mouseY);
            if (btn >= 0 && btn <= 2 && consoleMouseDown == btn + 1) {
                int x = cell == null ? consoleLastCellX : cell[0];
                int y = cell == null ? consoleLastCellY : cell[1];
                sendConsoleInput(ConsoleInputPayload.MOUSE_UP, btn + 1, 0, x, y, "");
                consoleMouseDown = -1;
                return true;
            }
        }
        if (btn == 0 && hScrollDragging)         { hScrollDragging         = false; return true; }
        if (btn == 0 && scaleDragging)           { scaleDragging           = false; return true; }
        if (btn == 0 && sidebarDragging)        { sidebarDragging        = false; return true; }
        if (btn == 0 && sidebarScrollDragging)  { sidebarScrollDragging  = false; return true; }
        if (btn == 0 && editorScrollbarDragging){ editorScrollbarDragging = false; return true; }
        if (btn == 0 && editorDragging) {
            editorDragging = false;
            if (selAnchorLine == cursorLine && selAnchorCol == cursorColumn) clearSel();
            return true;
        }
        if (btn == 0 && draggedEntry != null) {
            FileListPayload.Entry src = draggedEntry; draggedEntry = null;
            if (dragging) {
                dragging = false;
                if (!inSidebar(mouseX, mouseY)) return true; // dropped outside sidebar - no-op
                FileListPayload.Entry tgt = entryAt(mouseY);
                String destDir;
                if (tgt != null && tgt.directory() && !tgt.path().equals(src.path())) {
                    destDir = tgt.path(); // dropped on a folder >>> into that folder
                } else {
                    destDir = ""; // dropped anywhere else in the sidebar >>> root
                }
                // Skip the move if the file is already in destDir
                String srcParent = src.path().contains("/")
                    ? src.path().substring(0, src.path().lastIndexOf('/')) : "";
                if (!srcParent.equals(destDir))
                    PacketDistributor.sendToServer(new MoveFilePayload(pos, computerId, src.path(), destDir));
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, btn);
    }

    private boolean clickCtx(double mouseX, double mouseY) {
        List<String> items = contextItems();
        int w = 110, h = items.size() * 14 + 6;
        int cx = Math.min(contextX, panelX + panelW - w - 4);
        int cy = Math.min(contextY, panelY + panelH - h - 4);
        if (mouseX < cx || mouseX >= cx + w || mouseY < cy || mouseY >= cy + h) { contextOpen = false; return false; }
        int idx = (int)((mouseY - cy - 4) / 14);
        if (idx >= 0 && idx < items.size()) {
            String a = items.get(idx);
            if (!a.equals("---")) runCtxAction(a);
        }
        contextOpen = false; return true;
    }

    private void runCtxAction(String a) {
        switch (a) {
            case "Open"       -> { if (contextEntry != null) requestRead(contextEntry.path()); }
            case "Copy File"  -> copyCtxEntry();
            case "New File"   -> startInlineInput(InlineMode.NEW_FILE);
            case "New Folder" -> startInlineInput(InlineMode.NEW_FOLDER);
            case "Paste"      -> pasteCopied();
            case "Rename"     -> startInlineInput(InlineMode.RENAME);
            case "Delete"     -> deleteCtxEntry();
        }
    }

    private List<String> editorContextItems() {
        return List.of("Go to Definition", "Documentation");
    }

    private boolean clickEditorCtx(double mouseX, double mouseY) {
        List<String> items = editorContextItems();
        int w = 132, h = items.size() * 16 + 6;
        int cx = Math.min(editorContextX, panelX + panelW - w - 4);
        int cy = Math.min(editorContextY, panelY + panelH - h - 4);
        if (mouseX < cx || mouseX >= cx + w || mouseY < cy || mouseY >= cy + h) {
            editorContextOpen = false;
            return false;
        }
        int idx = (int)((mouseY - cy - 3) / 16);
        if (idx >= 0 && idx < items.size()) runEditorCtxAction(items.get(idx));
        editorContextOpen = false;
        return true;
    }

    private void runEditorCtxAction(String action) {
        switch (action) {
            case "Go to Definition" -> {
                if (editorContextDefLine >= 0) {
                    cursorLine = editorContextDefLine;
                    cursorColumn = 0;
                    keepVisible();
                    focusEditor();
                } else {
                    showFade("\"" + editorContextWord + "\" is not defined in this file");
                }
            }
            case "Documentation" -> {
                if (editorContextPageKey != null) {
                    openWikiTab(editorContextPageKey);
                } else {
                    showFade("\"" + editorContextWord + "\" has no wiki entry");
                }
            }
        }
    }

    private void openEditorContext(double mouseX, double mouseY) {
        refreshTypeMap();
        int[] hit = editorHitTest(mouseX, mouseY);
        if (hit == null) { editorContextOpen = false; return; }
        int row = hit[0], col = hit[1];
        String word = wordAt(lines.get(row), col);
        if (word.isEmpty()) {
            editorContextOpen = false;
            return;
        }

        cursorLine = row;
        cursorColumn = col;
        editorContextWord = word;
        editorContextDefLine = findDefinitionLine(word);
        editorContextPageKey = resolveDocumentationPage(row, col, word);
        editorContextX = (int) mouseX;
        editorContextY = (int) mouseY;
        editorContextOpen = true;
        contextOpen = false;
        focusEditor();
        editorDragging = false;
        acSuggestions.clear();
        acIndex = -1;
    }

    private String resolveDocumentationPage(int row, int col, String word) {
        String pageKey = WikiRegistry.resolveSymbol(word);
        if (pageKey == null) {
            String inferred = typeMap.getOrDefault(word, lastGoodTypeMap.get(word));
            if (inferred != null) pageKey = WikiRegistry.resolveSymbol(inferred);
        }
        if (pageKey != null) return pageKey;

        String line = lines.get(row);
        int ws = col;
        while (ws > 0 && isIdentChar(line.charAt(ws - 1))) ws--;
        if (ws <= 0 || line.charAt(ws - 1) != '.') return null;

        int objEnd = ws - 1;
        int objStart = objEnd;
        while (objStart > 0 && isIdentChar(line.charAt(objStart - 1))) objStart--;
        String obj = line.substring(objStart, objEnd);
        if (obj.isEmpty()) return null;

        pageKey = WikiRegistry.resolveSymbol(obj);
        if (pageKey != null) return pageKey;
        String inferred = typeMap.getOrDefault(obj, lastGoodTypeMap.get(obj));
        return inferred == null ? null : WikiRegistry.resolveSymbol(inferred);
    }

    private void rebuildEntryList() {
        entries.clear();
        appendDirEntries("");
    }

    private void appendDirEntries(String dir) {
        List<FileListPayload.Entry> children = dirContents.get(dir);
        if (children == null) return;
        for (FileListPayload.Entry e : children) {
            entries.add(e);
            if (e.directory() && expandedDirs.contains(e.path())) {
                appendDirEntries(e.path());
            }
        }
    }

    private void startInlineInput(InlineMode mode) {
        inlineMode   = mode;
        inlineParent = contextDirectory();
        if (mode == InlineMode.RENAME && contextEntry != null) {
            inlineText   = entryName(contextEntry);
            inlineInsert = entries.indexOf(contextEntry);
            if (inlineInsert < 0) inlineInsert = 0;
        } else {
            inlineText   = "";
            if (!inlineParent.isEmpty() && !expandedDirs.contains(inlineParent)) {
                expandedDirs.add(inlineParent);
                if (!dirContents.containsKey(inlineParent)) requestList(inlineParent);
                else rebuildEntryList();
            }
            inlineInsert = 0;
            for (int i = 0; i < entries.size(); i++) {
                String p = entries.get(i).path();
                if (inlineParent.isEmpty() || p.equals(inlineParent) || p.startsWith(inlineParent + "/"))
                    inlineInsert = i + 1;
            }
        }
    }

    private void confirmInlineInput() {
        String name = inlineText.trim();
        if (!name.isEmpty()) {
            if (inlineMode == InlineMode.RENAME && contextEntry != null) {
                pendingRenameOld = contextEntry.path();
                PacketDistributor.sendToServer(new RenameFilePayload(pos, computerId, contextEntry.path(), name));
            } else {
                String full = inlineParent.isEmpty() ? name : inlineParent + "/" + name;
                if (inlineMode == InlineMode.NEW_FILE)
                    PacketDistributor.sendToServer(new WriteFilePayload(pos, computerId, full, ""));
                else
                    PacketDistributor.sendToServer(new CreateFolderPayload(pos, computerId, full));
            }
        }
        inlineMode = InlineMode.NONE; inlineText = "";
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private boolean inSidebar(double mouseX, double mouseY) {
        return mouseX >= panelX && mouseX < panelX + sidebarW
            && mouseY >= panelY + TOP_H + TAB_H && mouseY < panelY + panelH;
    }
    private boolean inEditor(double mouseX, double mouseY) {
        return mouseX >= editorX && mouseX < editorX + editorW
            && mouseY >= editorY && mouseY < panelY + panelH;
    }

    private boolean inLogicalBox(double mouseX, double mouseY, int x, int y, int w, int h) {
        double lx = unscaleEditorX(mouseX);
        double ly = unscaleEditorY(mouseY);
        return lx >= x && lx < x + w && ly >= y && ly < y + h;
    }

    private boolean inSplitConsole(double mouseX, double mouseY) {
        if (!splitConsoleOpen || splitConsoleX <= 0 || splitConsoleY <= 0 || splitConsoleW <= 0 || splitConsoleH <= 0) return false;
        double lx = unscaleEditorX(mouseX);
        double ly = unscaleEditorY(mouseY);
        return lx >= splitConsoleX && lx < splitConsoleX + splitConsoleW
            && ly >= splitConsoleY && ly < splitConsoleY + splitConsoleH;
    }

    private int[] consoleCell(double mouseX, double mouseY) {
        if (consoleTerminal == null || consoleTermScale <= 0) return null;
        double lx = unscaleEditorX(mouseX);
        double ly = unscaleEditorY(mouseY);
        double tx = consoleTermX + 2;
        double ty = consoleTermY + 2;
        double relX = (lx - tx) / consoleTermScale;
        double relY = (ly - ty) / consoleTermScale;
        int cellX = (int)(relX / FixedWidthFontRenderer.FONT_WIDTH) + 1;
        int cellY = (int)(relY / FixedWidthFontRenderer.FONT_HEIGHT) + 1;
        if (cellX < 1 || cellY < 1 || cellX > consoleTerminal.getWidth() || cellY > consoleTerminal.getHeight())
            return null;
        return new int[] { cellX, cellY };
    }

    private int entryIndexAt(double mouseY) {
        double lmy = unscaleSidebarY(mouseY); // unmap through sidebar scale
        int entriesTop = sidebarPivotY() + 6 + LINE_H + 3;
        fileScroll = Mth.clamp(fileScroll, 0, sidebarMaxScroll());
        int v = fileScroll + (int)((lmy - entriesTop) / LINE_H);
        if (inlineMode != InlineMode.NONE) {
            if (v == inlineInsert) return -2;
            if (inlineMode != InlineMode.RENAME && v > inlineInsert) return v - 1;
        }
        return v;
    }
    private FileListPayload.Entry entryAt(double mouseY) {
        int i = entryIndexAt(mouseY);
        return (i >= 0 && i < entries.size()) ? entries.get(i) : null;
    }
    private String entryName(FileListPayload.Entry e) {
        int s = e.path().lastIndexOf('/');
        return s >= 0 ? e.path().substring(s + 1) : e.path();
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 3)) + "...";
    }
    private static String fmtSize(long b) {
        // Match ComputerCraft's `drive` display (decimal KB/MB), so CIDE's "Free" number
        // agrees with what `drive` / `fs.getFreeSpace` reports in-game.
        if (b < 1000) return b + " B";
        if (b < 1000 * 1000) return (b / 1000) + " KB";
        return (b / (1000 * 1000)) + " MB";
    }

    private int sidebarMaxScroll() {
        int total = entries.size() + (inlineMode != InlineMode.NONE && inlineMode != InlineMode.RENAME ? 1 : 0);
        int pivotY = sidebarPivotY();
        int fBot = pivotY + (int)((panelY + panelH - 6 - pivotY) / ideScale);
        int entriesTop = pivotY + 6 + LINE_H + 3;
        int visible = Math.max(1, (fBot - entriesTop) / LINE_H);
        return Math.max(0, total - visible);
    }

    private void updateSidebarScroll(double mouseY) {
        int pivotY   = sidebarPivotY();
        int trackTop = pivotY + 6 + LINE_H + 3;
        int trackH   = (int)((panelY + panelH - 6 - pivotY) / ideScale) - 6 - LINE_H - 3;
        int total    = entries.size() + (inlineMode != InlineMode.NONE && inlineMode != InlineMode.RENAME ? 1 : 0);
        int visible  = Math.max(1, trackH / LINE_H);
        if (total <= visible) { fileScroll = 0; return; }
        int thumbH   = Math.max(8, trackH * visible / total);
        double lmy   = unscaleSidebarY(mouseY);
        fileScroll   = Mth.clamp(
            (int) Math.round((lmy - trackTop - thumbH / 2.0) * (total - visible) / (trackH - thumbH)),
            0, total - visible);
    }

    private void updateEditorScroll(double mouseY) {
        if (currentPath.isBlank()) return;
        int sbTop    = editorY;
        int contentH = (panelY + panelH - 6) - sbTop;
        int totalLines;
        int headerH;
        if (currentPath.startsWith("wiki:")) {
            totalLines = wrapWikiLines(WikiRegistry.getPage(currentPath.substring(5))).size();
            headerH    = 0;
        } else {
            if (lines == null) return;
            totalLines = lines.size();
            headerH    = LINE_H + 3 + (searchOpen ? LINE_H + 4 : 0) + 6;
        }
        int lineH = currentPath.startsWith("wiki:") ? WIKI_LINE_H : LINE_H;
        int visLines = Math.max(1, (int)((contentH - headerH) / (lineH * ideScale)));
        if (totalLines <= visLines) return;
        int thumbH   = Math.max(8, contentH * visLines / totalLines);
        editorScroll = Mth.clamp(
            (int) Math.round((mouseY - sbTop - thumbH / 2.0) * (totalLines - visLines) / (contentH - thumbH)),
            0, totalLines - visLines);
    }

    private void openWikiTab(String pageKey) {
        String wikiPath = "wiki:" + pageKey;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).path().equals(wikiPath)) { switchTab(i); return; }
        }
        List<String> placeholder = new ArrayList<>(); placeholder.add("");
        EditorTab tab = new EditorTab(wikiPath, placeholder, true, false, 0, 0, 0, new ArrayDeque<>(), new ArrayDeque<>());
        if (tabs.size() == 1 && tabs.get(0).path().isEmpty() && !dirty) {
            tabs.set(0, tab); activeTab = 0;
        } else {
            tabs.add(tab); activeTab = tabs.size() - 1;
        }
        syncFromTab();
        clearCideFocus(FOCUS_EDITOR);
        status = "Wiki: " + pageKey;
    }

    private void renderWikiContent(GuiGraphics graphics, int x, int y, int bot) {
        String pageKey = currentPath.substring(5);
        List<WikiLine> wikiLines = WikiRegistry.getPage(pageKey);

        if (wikiLines.isEmpty()) {
            graphics.drawString(font, "No wiki content for: " + pageKey, x, y, 0xFF4A5568, false);
            return;
        }

        // Pre-wrap all lines so scroll math is accurate
        List<WikiLine> wrapped = wrapWikiLines(wikiLines);
        int visible = Math.max(1, (bot - y) / WIKI_LINE_H);
        editorScroll = Mth.clamp(editorScroll, 0, Math.max(0, wrapped.size() - visible));

        for (int i = editorScroll; i < wrapped.size() && y < bot; i++) {
            WikiLine wl = wrapped.get(i);
            if (wl.hr()) {
                int lineY = y + WIKI_LINE_H / 2;
                int ruleW = (int)((editorW - 12) / ideScale);
                graphics.fill(x, lineY, x + ruleW, lineY + 1, 0x55697386);
            } else if (wl.color() == WikiLine.C_H1) {
                float hs = 2.0f;
                graphics.pose().pushPose();
                graphics.pose().translate(x, y, 0);
                graphics.pose().scale(hs, hs, 1f);
                graphics.drawString(font, wl.text(), 0, 0, wl.color(), false);
                graphics.pose().popPose();
                y += WIKI_LINE_H * 3;
            } else if (wl.color() == WikiLine.C_H2) {
                y += WIKI_LINE_H;
                float hs = 1.25f;
                graphics.pose().pushPose();
                graphics.pose().translate(x + wl.indent() * WIKI_INDENT_W, y, 0);
                graphics.pose().scale(hs, hs, 1f);
                graphics.drawString(font, wl.text(), 0, 0, wl.color(), false);
                graphics.pose().popPose();
                y += WIKI_LINE_H;
            } else {
                int textX = x + wl.indent() * WIKI_INDENT_W;
                if (wl.bullet()) {
                    graphics.drawString(font, "\u2022", textX, y, wl.color(), false);
                    textX += WIKI_BULLET_W;
                }
                graphics.drawString(font, wl.text(), textX, y, wl.color(), false);
            }
            y += WIKI_LINE_H;
        }
    }

    private List<WikiLine> wrapWikiLines(List<WikiLine> source) {
        int availW = (int)((editorW - 18) / ideScale);
        List<WikiLine> out = new ArrayList<>();
        for (WikiLine wl : source) {
            if (wl.hr() || wl.text().isEmpty()) { out.add(wl); continue; }
            int indent = wl.indent() * WIKI_INDENT_W + (wl.bullet() ? WIKI_BULLET_W : 0);
            int lineW  = Math.max(40, availW - indent);
            String remaining = wl.text();
            boolean first = true;
            while (!remaining.isEmpty()) {
                String chunk = font.plainSubstrByWidth(remaining, lineW);
                if (chunk.isEmpty()) {
                    out.add(new WikiLine(remaining, wl.color(), false, wl.indent() + (wl.bullet() && !first ? 1 : 0)));
                    break;
                }
                if (wl.bullet() && !first) {
                    out.add(new WikiLine(chunk, wl.color(), false, wl.indent() + 1));
                } else {
                    out.add(new WikiLine(chunk, wl.color(), false, wl.indent(), wl.bullet()));
                }
                remaining = remaining.substring(chunk.length()).stripLeading();
                first = false;
            }
        }
        return out;
    }

    /**
     * Re-parses the current file and updates the type map used for smart
     * autocomplete and wiki navigation. Skips if the content hasn't changed.
     * On a broken/mid-edit file the last successful inference is kept as fallback.
     */
    private void refreshTypeMap() {
        if (lines == null || !isLuaLikePath(currentPath)) return;
        int fp = lines.hashCode();
        if (fp != typeMapFingerprint) {
            typeMapFingerprint = fp;
            try {
                lastParsedChunk = LuaParser.parse(lines);
                Map<String, String> fresh = LuaTypeResolver.resolve(lastParsedChunk, sideToType);
                typeMap = fresh;
                if (!fresh.isEmpty()) lastGoodTypeMap = fresh;
            } catch (Exception ignored) {
                typeMap = lastGoodTypeMap;
            }
        }
        // Always recompute scope-visible names - cursor line may change without an edit
        if (lastParsedChunk != null) {
            try {
                fileLocalNames = LuaTypeResolver.collectNames(lastParsedChunk, cursorLine);
            } catch (Exception ignored) {}
        }
    }

    private List<String> luaPrefixLines(int columnLimit) {
        if (lines == null || cursorLine >= lines.size()) return List.of();
        List<String> prefix = new ArrayList<>(cursorLine + 1);
        for (int i = 0; i < cursorLine; i++) prefix.add(lines.get(i));
        String current = lines.get(cursorLine);
        int end = Mth.clamp(columnLimit, 0, current.length());
        prefix.add(current.substring(0, end));
        return prefix;
    }

    private Set<String> visibleLuaNames(int columnLimit) {
        try {
            return LuaTypeResolver.collectNames(LuaParser.parse(luaPrefixLines(columnLimit)), cursorLine);
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private Map<String, String> visibleLuaTypes(int columnLimit) {
        try {
            return LuaTypeResolver.resolve(LuaParser.parse(luaPrefixLines(columnLimit)), sideToType);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void showFade(String msg) {
        fadeMessage = msg;
        fadeUntil   = System.currentTimeMillis() + 2500;
    }

    private String wordAt(String line, int col) {
        int start = col;
        while (start > 0 && isIdentChar(line.charAt(start - 1))) start--;
        int end = Math.max(col, start);
        while (end < line.length() && isIdentChar(line.charAt(end))) end++;
        return line.substring(start, end);
    }

    /**
     * Maps a screen coordinate to (row, col) within actual text content.
     * Returns null if the click lands outside all text: below the last line,
     * in the line-number gutter, or past the end of the text on that row.
     */
    private int[] editorHitTest(double mouseX, double mouseY) {
        if (lines == null || lines.isEmpty()) return null;
        int yBase = editorY + 6 + LINE_H + 3 + (searchOpen ? LINE_H + 4 : 0);
        int rawRow = editorScroll + (int)((unscaleEditorY(mouseY) - yBase) / LINE_H);
        if (rawRow < 0 || rawRow >= lines.size()) return null; // above/below content
        int rawRel = (int)unscaleEditorX(mouseX) - (editorX + 5 + LINE_NUM_W);
        if (rawRel < 0) return null; // inside line-number gutter
        String lineText = lines.get(rawRow);
        if (rawRel > editorTextWidth(lineText)) return null; // past end of text on this row
        int col = editorTextTruncated(lineText, rawRel).length();
        return new int[]{rawRow, col};
    }

    private boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static final java.util.regex.Pattern IDENT_PATTERN = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private java.util.Set<String> identifiersIn(String line) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (line == null || line.isEmpty()) return out;
        java.util.regex.Matcher m = IDENT_PATTERN.matcher(line);
        while (m.find()) out.add(m.group());
        return out;
    }

    private boolean mouseInGutterRow(int mouseX, int mouseY, int rowY) {
        double lx = unscaleEditorX(mouseX);
        double ly = unscaleEditorY(mouseY);
        return lx >= editorX + 5 && lx < editorX + 5 + LINE_NUM_W
            && ly >= rowY - 1 && ly < rowY + LINE_H - 1;
    }

    private int gutterLineAt(double mouseX, double mouseY) {
        double lx = unscaleEditorX(mouseX);
        if (lx < editorX + 5 || lx >= editorX + 5 + LINE_NUM_W) return -1;
        int yBase = editorY + 6 + LINE_H + 3 + (searchOpen ? LINE_H + 4 : 0);
        int row   = editorScroll + (int)((unscaleEditorY(mouseY) - yBase) / LINE_H);
        if (row < 0 || row >= lines.size()) return -1;
        return row;
    }

    private static void drawCircle(GuiGraphics graphics, int cx, int cy, int r, int color) {
        int rr = r * r;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dy * dy <= rr) graphics.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
            }
        }
    }

    private static void drawCircleOutline(GuiGraphics graphics, int cx, int cy, int r, int color) {
        int rrOut = r * r;
        int rrIn = (r - 1) * (r - 1);
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                int d = dx * dx + dy * dy;
                if (d <= rrOut && d >= rrIn) graphics.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
            }
        }
    }

    private static final java.util.regex.Pattern PERIPHERAL_CALL =
        java.util.regex.Pattern.compile("\\bperipheral\\.(wrap|find)\\s*\\(\\s*$");

    /** Returns the column of the opening quote if the cursor is inside a single-line string literal, otherwise -1. */
    private static int stringOpenBefore(String line, int cursor) {
        int openIdx = -1;
        char openChar = 0;
        int limit = Math.min(cursor, line.length());
        for (int i = 0; i < limit; i++) {
            char c = line.charAt(i);
            if (openChar == 0) {
                if (c == '"' || c == '\'') { openChar = c; openIdx = i; }
            } else if (c == '\\') {
                i++;
            } else if (c == openChar) {
                openChar = 0;
                openIdx = -1;
            }
        }
        return openChar == 0 ? -1 : openIdx;
    }

    private int[] wordRangeAt(String line, int col) {
        if (line.isEmpty()) return null;
        col = Mth.clamp(col, 0, line.length());
        int probe = col;
        if (probe == line.length() || (probe < line.length() && !isIdentChar(line.charAt(probe)))) {
            if (probe > 0 && isIdentChar(line.charAt(probe - 1))) probe--;
            else return null;
        }
        int start = probe;
        while (start > 0 && isIdentChar(line.charAt(start - 1))) start--;
        int end = probe;
        while (end < line.length() && isIdentChar(line.charAt(end))) end++;
        return start == end ? null : new int[] { start, end };
    }

    private int[] chunkRangeAt(String line, int col) {
        if (line.isEmpty()) return null;
        col = Mth.clamp(col, 0, line.length());
        int probe = col;
        if (probe == line.length() || (probe < line.length() && Character.isWhitespace(line.charAt(probe)))) {
            if (probe > 0 && !Character.isWhitespace(line.charAt(probe - 1))) probe--;
            else return null;
        }
        int start = probe;
        while (start > 0 && !Character.isWhitespace(line.charAt(start - 1))) start--;
        int end = probe;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
        return start == end ? null : new int[] { start, end };
    }

    private boolean selectionMatchesRange(int lineIndex, int start, int end,
        int anchorLine, int anchorCol,
        int selectionCursorLine, int selectionCursorCol) {
        if (anchorLine != lineIndex || selectionCursorLine != lineIndex) return false;
        return Math.min(anchorCol, selectionCursorCol) == start
            && Math.max(anchorCol, selectionCursorCol) == end;
    }


    private boolean isLuaLikePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("wiki:")) return false;
        if (path.endsWith(".lua")) return true;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.indexOf('.', slash + 1) < 0;
    }
    //look at this orphaned method, adopt him.
    private void selectWordAt(int lineIndex, int column) {
        if (lineIndex < 0 || lineIndex >= lines.size()) return;
        int[] range = wordRangeAt(lines.get(lineIndex), column);
        if (range == null) return;
        selAnchorLine = lineIndex; selAnchorCol = range[0];
        cursorLine = lineIndex; cursorColumn = range[1];
        editorDragging = false;
    }

    private void selectChunkAt(int lineIndex, int column) {
        if (lineIndex < 0 || lineIndex >= lines.size()) return;
        int[] range = chunkRangeAt(lines.get(lineIndex), column);
        if (range == null) return;
        selAnchorLine = lineIndex; selAnchorCol = range[0];
        cursorLine = lineIndex; cursorColumn = range[1];
        editorDragging = false;
    }

    private void rebuildAutocomplete() {
        acSuggestions.clear();
        acIndex = -1;
        acPrefix = "";
        acIsItemNamespace = false;
        if (!isEditorFocused() || currentPath.isBlank() || currentPath.startsWith("wiki:")) return;
        if (!isLuaLikePath(currentPath)) return;
        if (cursorLine >= lines.size()) return;
        refreshTypeMap();
        String line = lines.get(cursorLine);

        //autocomplete peripheral names inside peripheral.wrap()
        int openQuote = stringOpenBefore(line, cursorColumn);
        if (openQuote >= 0) {
            String beforeQuote = line.substring(0, openQuote);
            java.util.regex.Matcher peripheralCall = PERIPHERAL_CALL.matcher(beforeQuote);
            if (peripheralCall.find()) {
                boolean isFind = "find".equals(peripheralCall.group(1));
                acPrefix = line.substring(openQuote + 1, cursorColumn);
                String pfx = acPrefix.toLowerCase(Locale.ROOT);
                if (isFind) {
                    java.util.Set<String> types = new java.util.LinkedHashSet<>(sideToType.values());
                    for (String type : types)
                        if (type != null && type.toLowerCase(Locale.ROOT).startsWith(pfx)) acSuggestions.add(type);
                } else {
                    for (String name : sideToType.keySet())
                        if (name != null && name.toLowerCase(Locale.ROOT).startsWith(pfx)) acSuggestions.add(name);
                }
                acSuggestions.sort(String.CASE_INSENSITIVE_ORDER);
                if (!acSuggestions.isEmpty()) acIndex = 0;
                return;
            }
        }

        // Scan back over identifier chars to find the start of the current token
        int start = cursorColumn;
        while (start > 0 && isIdentChar(line.charAt(start - 1))) start--;

        if (start > 0 && (line.charAt(start - 1) == '.' || line.charAt(start - 1) == ':')) {
            char separator = line.charAt(start - 1);
            int objEnd   = start - 1;
            int objStart = objEnd;
            while (objStart > 0 && isIdentChar(line.charAt(objStart - 1))) objStart--;
            String objectName = line.substring(objStart, objEnd);
            if (objectName.isEmpty()) return;
            if (separator == ':' && !LuaCompletionRegistry.isItemNamespace(objectName)) return;

            acPrefix = line.substring(start, cursorColumn); // member prefix (may be empty)
            String pfx = acPrefix.toLowerCase(Locale.ROOT);

            // Resolve objectName >>> module key via registry first, then type map fallback
            String moduleKey = objectName;
            if (LuaCompletionRegistry.getMembers(objectName).isEmpty()) {
                Map<String, String> visibleTypes = visibleLuaTypes(objStart);
                String inferred = visibleTypes.get(objectName);
                if (inferred != null) moduleKey = inferred;
            }
            acIsItemNamespace = LuaCompletionRegistry.isItemNamespace(moduleKey);
            for (String m : LuaCompletionRegistry.getMembers(moduleKey))
                if (m.toLowerCase(Locale.ROOT).startsWith(pfx)) acSuggestions.add(m);
            acSuggestions.sort(String.CASE_INSENSITIVE_ORDER);
            if (!acSuggestions.isEmpty()) acIndex = 0;
            return;
        }

        if (cursorColumn - start < 1) return;
        acPrefix = line.substring(start, cursorColumn);
        String pfx = acPrefix.toLowerCase(Locale.ROOT);

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String s : visibleLuaNames(start))
            if (!s.equals(acPrefix) && s.toLowerCase(Locale.ROOT).startsWith(pfx)) seen.add(s);
        for (String kw : LUA_KEYWORDS)
            if (!kw.equals(acPrefix) && kw.startsWith(pfx)) seen.add(kw);
        for (String graphics : LUA_GLOBALS)
            if (!graphics.equals(acPrefix) && graphics.startsWith(pfx)) seen.add(graphics);
        for (String sym : LuaCompletionRegistry.getAll())
            if (!sym.equals(acPrefix) && sym.toLowerCase(Locale.ROOT).startsWith(pfx)) seen.add(sym);

        acSuggestions.addAll(seen);
        acSuggestions.sort(String.CASE_INSENSITIVE_ORDER);
        if (!acSuggestions.isEmpty()) acIndex = 0;
    }


    private void acceptAutocomplete() {
        if (acSuggestions.isEmpty() || acIndex < 0) return;
        String suffix = acSuggestions.get(acIndex).substring(acPrefix.length());
        boolean swapDotForColon = acIsItemNamespace;
        acSuggestions.clear(); acIndex = -1;
        acIsItemNamespace = false;
        if (currentReadOnly || !writesEnabled) return;
        if (suffix.isEmpty() && !swapDotForColon) return;
        pushMilestone();
        if (swapDotForColon && cursorLine < lines.size()) {
            String line = lines.get(cursorLine);
            int dotPos = cursorColumn - acPrefix.length() - 1;
            if (dotPos >= 0 && dotPos < line.length() && line.charAt(dotPos) == '.') {
                lines.set(cursorLine, line.substring(0, dotPos) + ":" + line.substring(dotPos + 1));
            }
        }
        if (!suffix.isEmpty()) insertRaw(suffix);
    }

    private void renderAutocomplete(GuiGraphics graphics) {
        if (acSuggestions.isEmpty() || acIndex < 0 || currentPath.isBlank()) return;
        if (cursorLine >= lines.size()) return;
        String line = lines.get(cursorLine);
        // Convert logical cursor position to screen space because we love rendering the window at a wrong location
        int logX = editorX + 5 + LINE_NUM_W + editorTextWidth(line.substring(0, Math.min(cursorColumn, line.length())));
        int logY = editorY + 6 + LINE_H + 3 + (searchOpen ? LINE_H + 4 : 0) + (cursorLine - editorScroll) * LINE_H;
        int scx  = (int)(editorX + (logX - editorX) * ideScale);
        int scy  = (int)(editorY + (logY - editorY) * ideScale);

        int maxShow = Math.min(8, acSuggestions.size());
        int startIdx = Mth.clamp(acIndex - maxShow / 2, 0, Math.max(0, acSuggestions.size() - maxShow));
        int itemH = 11;
        int dropW = 100;
        for (int i = startIdx; i < startIdx + maxShow; i++)
            dropW = Math.max(dropW, editorTextWidth(acSuggestions.get(i)) + 18);
        int dropH = maxShow * itemH + 4;
        int dropX = scx;
        int dropY = scy + (int)(LINE_H * ideScale) + 1;
        if (dropX + dropW > panelX + panelW - 4) dropX = panelX + panelW - 4 - dropW;
        if (dropY + dropH > panelY + panelH - 4) dropY = scy - dropH - 1;

        graphics.fill(dropX, dropY, dropX + dropW, dropY + dropH, 0xF00D1117);
        graphics.renderOutline(dropX, dropY, dropW, dropH, 0x77697386);

        for (int i = 0; i < maxShow; i++) {
            int idx = startIdx + i;
            String sug = acSuggestions.get(idx);
            int iy = dropY + 2 + i * itemH;
            if (idx == acIndex) graphics.fill(dropX + 1, iy, dropX + dropW - 1, iy + itemH, 0xFF1A2E48);
            int px = dropX + 13;
            // typed prefix (dim) + completion suffix (bright)
            int prefLen = Math.min(acPrefix.length(), sug.length());
            drawEditorText(graphics, sug.substring(0, prefLen), px, iy + 1, CS_READONLY);
            drawEditorText(graphics, sug.substring(prefLen), px + editorTextWidth(sug.substring(0, prefLen)), iy + 1, CS_NORMAL);
        }
        String hint = "Tab"; 
        int selY = dropY + 2 + (acIndex - startIdx) * itemH;
        drawEditorText(graphics, hint, dropX + dropW - editorTextWidth(hint) - 4, selY + 1, 0x554FC1FF);
    }

    private int findDefinitionLine(String name) {
        if (name == null || name.isBlank() || lines == null || lines.isEmpty()) return -1;
        int limit = Mth.clamp(cursorLine, 0, lines.size() - 1);
        ArrayDeque<Integer> scopes = new ArrayDeque<>();
        List<DefinitionHit> hits = new ArrayList<>();
        scopes.push(0);

        for (int i = 0; i <= limit; i++) {
            String line = stripLuaComment(lines.get(i)).strip();
            if (line.isEmpty()) continue;

            closeScopes(line, scopes);
            collectDefinitionHits(line, i, scopes.peek(), name, hits);
            openScopes(line, i, scopes);
        }

        DefinitionHit best = null;
        for (DefinitionHit hit : hits) {
            if (hit.line() > limit || hit.scopeStart() > limit) continue;
            if (best == null
                    || hit.scopeStart() > best.scopeStart()
                    || (hit.scopeStart() == best.scopeStart() && hit.line() > best.line())) best = hit;
        }
        return best == null ? -1 : best.line();
    }

    private record DefinitionHit(int line, int scopeStart) {}

    private void collectDefinitionHits(String line, int lineNo, int scopeStart, String name, List<DefinitionHit> hits) {
        String localFunction = localFunctionName(line);
        if (name.equals(localFunction)) hits.add(new DefinitionHit(lineNo, scopeStart));
        addFunctionParams(line, lineNo, name, hits);
        addForVars(line, lineNo, name, hits);
        addLocalVars(line, lineNo, scopeStart, name, hits);
        addAssignedName(line, lineNo, scopeStart, name, hits);
        addTableField(line, lineNo, scopeStart, name, hits);
    }

    private void addLocalVars(String line, int lineNo, int scopeStart, String name, List<DefinitionHit> hits) {
        if (!line.startsWith("local ") || line.startsWith("local function ")) return;
        String names = line.substring(6);
        int eq = names.indexOf('=');
        if (eq >= 0) names = names.substring(0, eq);
        for (String part : names.split(",")) {
            String n = leadingIdentifier(part.stripLeading());
            if (name.equals(n)) hits.add(new DefinitionHit(lineNo, scopeStart));
        }
    }

    private void addAssignedName(String line, int lineNo, int scopeStart, String name, List<DefinitionHit> hits) {
        if (line.startsWith("local ") || line.startsWith("for ") || line.startsWith("function ")) return;
        int eq = line.indexOf('=');
        if (eq <= 0 || (eq + 1 < line.length() && line.charAt(eq + 1) == '=')) return;
        String left = line.substring(0, eq);
        for (String part : left.split(",")) {
            String n = leadingIdentifier(part.stripLeading());
            if (name.equals(n)) hits.add(new DefinitionHit(lineNo, scopeStart));
        }
    }

    private void addFunctionParams(String line, int lineNo, String name, List<DefinitionHit> hits) {
        String params = paramsBetweenParens(line);
        if (params == null) return;
        boolean functionLine = line.startsWith("function ") || line.startsWith("local function ")
            || line.contains(" function(") || line.contains(" function (");
        if (!functionLine) return;
        for (String part : params.split(",")) {
            String n = leadingIdentifier(part.stripLeading());
            if (name.equals(n)) hits.add(new DefinitionHit(lineNo, lineNo + 1));
        }
    }

    private void addForVars(String line, int lineNo, String name, List<DefinitionHit> hits) {
        if (!line.startsWith("for ")) return;
        int end = line.indexOf(" do");
        if (end < 0) end = line.length();
        String vars = line.substring(4, end);
        int in = vars.indexOf(" in ");
        int eq = vars.indexOf('=');
        if (in >= 0) vars = vars.substring(0, in);
        else if (eq >= 0) vars = vars.substring(0, eq);
        for (String part : vars.split(",")) {
            String n = leadingIdentifier(part.stripLeading());
            if (name.equals(n)) hits.add(new DefinitionHit(lineNo, lineNo + 1));
        }
    }

    private void addTableField(String line, int lineNo, int scopeStart, String name, List<DefinitionHit> hits) {
        if (line.startsWith(name + " =") || line.startsWith(name + "=")) hits.add(new DefinitionHit(lineNo, scopeStart));
        String dotted = "." + name;
        int dot = line.indexOf(dotted);
        if (dot >= 0) {
            int after = dot + dotted.length();
            if (after < line.length() && Character.isWhitespace(line.charAt(after))) {
                String rest = line.substring(after).stripLeading();
                if (rest.startsWith("=")) hits.add(new DefinitionHit(lineNo, scopeStart));
            }
        }
    }

    private String localFunctionName(String line) {
        String prefix;
        if (line.startsWith("local function ")) prefix = "local function ";
        else if (line.startsWith("function "))  prefix = "function ";
        else return "";
        String rest = line.substring(prefix.length()).stripLeading();
        String name = leadingIdentifier(rest);
        if (name.isEmpty()) return "";
        int consumed = name.length();
        while (consumed < rest.length() && (rest.charAt(consumed) == '.' || rest.charAt(consumed) == ':')) {
            consumed++;
            String next = leadingIdentifier(rest.substring(consumed));
            if (next.isEmpty()) break;
            name = next;
            consumed += next.length();
        }
        return name;
    }

    private String paramsBetweenParens(String line) {
        int open = line.indexOf('(');
        int close = line.indexOf(')', open + 1);
        if (open < 0 || close < open) return null;
        return line.substring(open + 1, close);
    }

    private String leadingIdentifier(String text) {
        if (text == null || text.isBlank()) return "";
        int i = 0;
        if (!isIdentStart(text.charAt(i))) return "";
        i++;
        while (i < text.length() && isIdentChar(text.charAt(i))) i++;
        return text.substring(0, i);
    }

    private boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private String stripLuaComment(String line) {
        boolean single = false, dbl = false, esc = false;
        for (int i = 0; i + 1 < line.length(); i++) {
            char c = line.charAt(i);
            if (esc) { esc = false; continue; }
            if ((single || dbl) && c == '\\') { esc = true; continue; }
            if (!dbl && c == '\'') single = !single;
            else if (!single && c == '"') dbl = !dbl;
            else if (!single && !dbl && c == '-' && line.charAt(i + 1) == '-') return line.substring(0, i);
        }
        return line;
    }

    private void closeScopes(String line, ArrayDeque<Integer> scopes) {
        if (line.startsWith("end") || line.startsWith("until") || line.startsWith("else") || line.startsWith("elseif")) {
            if (scopes.size() > 1) scopes.pop();
        }
    }

    private void openScopes(String line, int lineNo, ArrayDeque<Integer> scopes) {
        if (opensScope(line)) scopes.push(lineNo + 1);
        if (line.startsWith("else") || line.startsWith("elseif")) scopes.push(lineNo + 1);
    }

    private boolean opensScope(String line) {
        return line.startsWith("function ")
            || line.startsWith("local function ")
            || line.startsWith("do")
            || line.startsWith("while ")
            || line.startsWith("for ")
            || line.startsWith("repeat")
            || line.startsWith("if ");
    }
}
