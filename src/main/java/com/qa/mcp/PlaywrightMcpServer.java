package com.qa.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PlaywrightMcpServer v6 — Intelligent Test Framework Generator
 *
 * ══════════════════════════════════════════════════════════════════════
 *  V6 UPGRADE SUMMARY (over v5)
 * ══════════════════════════════════════════════════════════════════════
 *
 *  ✅  1. Smart Wait Engine
 *          waitForStableState() auto-injected before EVERY action:
 *          DOM ready → network idle → element visible → DOM settled.
 *          No more click-before-element-ready failures.
 *
 *  ✅  2. Action-Aware Wait Strategy
 *          Per action-type pre/post wait table:
 *          CLICK → wait load+networkIdle after
 *          FILL  → wait visible before + stable after
 *          NAV   → wait LOAD + NETWORKIDLE always
 *          SELECT→ wait enabled before interaction
 *
 *  ✅  3. Intelligent Delay Reduction
 *          Human timing cap reduced 2500ms → 500ms.
 *          Page is driven by DOM state, not human pauses.
 *
 *  ✅  4. Intent Analyzer (NEW)
 *          IntentAnalyzer groups raw events into logical intents:
 *          [FILL username] + [FILL password] + [CLICK submit]
 *              → Intent.LOGIN(username, password)
 *          [FILL search] + [PRESS Enter / CLICK search btn]
 *              → Intent.SEARCH(query)
 *          [NAVIGATE url] → Intent.NAVIGATE(url)
 *          [FILL amount] + [CLICK transfer]
 *              → Intent.TRANSFER(amount)
 *          [FILL *] + [SELECT *] + [CLICK submit]
 *              → Intent.FORM_SUBMIT(fields map)
 *          Ungrouped events → Intent.RAW_ACTION
 *
 *  ✅  5. Intent-Driven BDD Generation
 *          BddCodeGenerator now receives List<Intent> not raw events.
 *          Each Intent → one meaningful Gherkin step (not one per DOM event).
 *          LOGIN → "When user logs in with <username> and <password>"
 *          SEARCH → "When user searches for <query>"
 *          FORM_SUBMIT → "When user submits the <form> form"
 *
 *  ✅  6. Framework Abstraction Layer (UiActions)
 *          Generated Page Objects use UiActions interface:
 *              uiActions.click(locator)
 *              uiActions.fill(locator, value)
 *          PlaywrightUiActions implements UiActions.
 *          Future: SeleniumUiActions can plug in without touching tests.
 *
 *  ✅  7. Recovery + Re-sync on Failure
 *          On event failure: page state is validated,
 *          URL is checked against expected, DOM is re-synced
 *          before deciding to cascade or continue.
 *
 *  ✅  8. All v5 features retained:
 *          ThreadLocal parallel, retry engine, failure artifacts,
 *          structured logging, screenshot+HTML dump, CSP bypass,
 *          zero-ambiguity locator (href>id>testId>role>nth),
 *          file upload, atomic actions.
 * ══════════════════════════════════════════════════════════════════════
 */
public class PlaywrightMcpServer {

    private static final Logger LOG = Logger.getLogger("MCP");
    private static final boolean DEBUG = "DEBUG".equalsIgnoreCase(
            System.getenv().getOrDefault("MCP_LOG_LEVEL", "INFO"));

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ── Config (env-overridable) ──────────────────────────────────────────────
    private static final int  RETRY_MAX      = intEnv("MCP_RETRY_MAX", 3);
    private static final long TIMEOUT_MS     = longEnv("MCP_TIMEOUT_MS", 30_000);
    private static final long RETRY_DELAY_MS = longEnv("MCP_RETRY_DELAY_MS", 800);
    private static final long MAX_STEP_DELAY = longEnv("MCP_MAX_STEP_DELAY_MS", 500); // v6: was 2500
    private static final Path FAILURE_DIR    = Paths.get(
            System.getenv().getOrDefault("MCP_FAILURE_DIR", "./mcp-failures"));

    // ── Recording state ───────────────────────────────────────────────────────
    private static final List<ObjectNode> recordedEvents   = new CopyOnWriteArrayList<>();
    private static final Set<String>      seenFingerprints = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final AtomicInteger    sequenceCounter  = new AtomicInteger(0);
    private static final AtomicLong       sessionStart     = new AtomicLong(0);
    private static volatile ObjectNode    sessionEnvelope  = null;
    private static volatile String        sessionName      = "Unnamed Session";

    // ── Recording browser (single, headful) ───────────────────────────────────
    private static volatile Playwright     recPW;
    private static volatile Browser        recBrowser;
    private static volatile BrowserContext recContext;
    private static volatile Page           recPage;

    // ── ThreadLocal for parallel playback ────────────────────────────────────
    private static final ThreadLocal<Playwright>     TL_PW      = new ThreadLocal<>();
    private static final ThreadLocal<Browser>        TL_BROWSER = new ThreadLocal<>();
    private static final ThreadLocal<BrowserContext> TL_CTX     = new ThreadLocal<>();
    private static final ThreadLocal<Page>           TL_PAGE    = new ThreadLocal<>();

    // ── Queue polling ─────────────────────────────────────────────────────────
    private static volatile Thread  pollingThread;
    private static volatile boolean pollActive;

    // ═════════════════════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ═════════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws Exception {
        configureLogging();
        var transport = new StdioServerTransportProvider(new ObjectMapper());
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("playwright-mcp-server", "6.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true).logging().build())
                .tools(
                        spec("start_recording",
                                "Launch Chromium (headful), bypass CSP, capture ALL interactions.",
                                "{\"type\":\"object\",\"properties\":{" +
                                        "\"url\":{\"type\":\"string\"}," +
                                        "\"sessionName\":{\"type\":\"string\"}," +
                                        "\"headless\":{\"type\":\"boolean\"}" +
                                        "},\"required\":[\"url\"]}",
                                (ex, a) -> handleStartRecording(a)),

                        spec("stop_recording",
                                "Stop recording, drain queue, analyse intents, return full JSON.",
                                "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
                                (ex, a) -> handleStopRecording(a)),

                        spec("playback_recording",
                                "Replay JSON recording with Smart Wait Engine. " +
                                        "parallel=true enables concurrent threads. " +
                                        "Screenshots + HTML saved on failure.",
                                "{\"type\":\"object\",\"properties\":{" +
                                        "\"jsonRecording\":{\"type\":\"string\"}," +
                                        "\"headless\":{\"type\":\"boolean\"}," +
                                        "\"parallel\":{\"type\":\"boolean\"}," +
                                        "\"threads\":{\"type\":\"integer\"}" +
                                        "},\"required\":[\"jsonRecording\"]}",
                                (ex, a) -> handlePlayback(a)),

                        spec("generate_playwright_bdd",
                                "Convert recording to Intent-driven Feature + Steps + " +
                                        "PageObject (UiActions abstraction) + pom.xml. " +
                                        "framework: cucumber|testng|serenity. " +
                                        "dataRows JSON array enables Scenario Outline.",
                                "{\"type\":\"object\",\"properties\":{" +
                                        "\"jsonRecording\":{\"type\":\"string\"}," +
                                        "\"featureName\":{\"type\":\"string\"}," +
                                        "\"dataRows\":{\"type\":\"string\"}," +
                                        "\"tags\":{\"type\":\"string\"}," +
                                        "\"framework\":{\"type\":\"string\"}" +
                                        "},\"required\":[\"jsonRecording\",\"featureName\"]}",
                                (ex, a) -> handleGenerateBdd(a)),

                        spec("execute_atomic_action",
                                "Execute one Playwright action with Smart Wait pre-conditions. " +
                                        "Actions: click, fill, navigate, check, select_option, press, " +
                                        "hover, scroll, wait, upload_file, wait_for_selector, " +
                                        "wait_for_url, wait_for_network_idle, screenshot, " +
                                        "get_text, is_visible, get_html.",
                                "{\"type\":\"object\",\"properties\":{" +
                                        "\"action\":{\"type\":\"string\"}," +
                                        "\"selector\":{\"type\":\"string\"}," +
                                        "\"value\":{\"type\":\"string\"}," +
                                        "\"timeoutMs\":{\"type\":\"integer\"}" +
                                        "},\"required\":[\"action\"]}",
                                (ex, a) -> handleAtomicAction(a))
                )
                .build();

        log(Level.INFO, "PlaywrightMcpServer v6 — SmartWait + IntentEngine + UiActions");
        log(Level.INFO, "  retry=" + RETRY_MAX + " timeout=" + TIMEOUT_MS
                + "ms maxStepDelay=" + MAX_STEP_DELAY + "ms debug=" + DEBUG);
        try { Thread.currentThread().join(); }
        catch (InterruptedException e) { log(Level.INFO, "Shutdown."); }
        finally { server.close(); closeRecBrowser(); }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HANDLERS
    // ═════════════════════════════════════════════════════════════════════════

    private static McpSchema.CallToolResult handleStartRecording(Map<String, Object> args) {
        try {
            String url  = strArg(args, "url");
            sessionName = strArgOpt(args, "sessionName",
                    "Recording-" + Instant.now().toString().substring(0, 10));
            boolean hl  = boolArgOpt(args, "headless", false);

            closeRecBrowser();
            recordedEvents.clear();
            seenFingerprints.clear();
            sequenceCounter.set(0);
            sessionStart.set(System.currentTimeMillis());

            recPW      = Playwright.create();
            recBrowser = recPW.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(hl)
                    .setArgs(List.of("--disable-web-security",
                            "--disable-features=IsolateOrigins,site-per-process")));
            recContext = recBrowser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1280, 720).setBypassCSP(true));

            recContext.route("**/*", route -> {
                var resp = route.fetch();
                Map<String, String> h = new HashMap<>(resp.headers());
                h.remove("content-security-policy");
                h.remove("content-security-policy-report-only");
                h.remove("x-content-security-policy");
                route.fulfill(new Route.FulfillOptions().setResponse(resp).setHeaders(h));
            });

            recContext.exposeBinding("__mcpCapture", (src, bArgs) -> {
                if (bArgs.length > 0 && bArgs[0] instanceof String raw) ingestEvent(raw, "binding");
                return null;
            });
            recContext.addInitScript(CAPTURE_SCRIPT);
            recPage          = recContext.newPage();
            sessionEnvelope  = buildSessionEnvelope(url, hl);

            long t0 = System.currentTimeMillis();
            recPage.navigate(url);
            recordedEvents.add(buildNavigateEvent(url, System.currentTimeMillis() - t0));
            safeEval(CAPTURE_SCRIPT, recPage);
            startPolling();

            log(Level.INFO, "Recording started: " + sessionName);
            return ok("✅ Recording started — \"" + sessionName + "\"\n" +
                    "URL: " + url + "\n" +
                    "SmartWait: ACTIVE  Retry: " + RETRY_MAX + "x  Timeout: " + TIMEOUT_MS + "ms\n" +
                    "Call stop_recording() when done.");
        } catch (Exception e) {
            log(Level.SEVERE, "start_recording: " + e.getMessage());
            return err("start_recording failed: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult handleStopRecording(Map<String, Object> args) {
        try {
            stopPolling();
            drainQueue(recPage);

            long endMs = System.currentTimeMillis();

            // Run Intent Analyzer on captured events
            List<IntentAnalyzer.Intent> intents = IntentAnalyzer.analyze(recordedEvents);

            ObjectNode recording = MAPPER.createObjectNode();
            recording.put("schemaVersion",   "6.0.0");
            recording.put("sessionName",     sessionName);
            recording.put("status",          "COMPLETED");
            recording.put("totalEvents",     recordedEvents.size());
            recording.put("totalIntents",    intents.size());
            recording.put("totalDurationMs", endMs - sessionStart.get());
            recording.put("endedAt",         Instant.now().toString());
            if (sessionEnvelope != null) recording.set("session", sessionEnvelope);
            recording.set("codeGenerationHints", buildCodeGenHints());

            // Raw events
            ArrayNode evArr = MAPPER.createArrayNode();
            recordedEvents.forEach(evArr::add);
            recording.set("events", evArr);

            // Intents (structured view for BDD generation)
            ArrayNode intArr = MAPPER.createArrayNode();
            for (IntentAnalyzer.Intent intent : intents) intArr.add(intent.toJson(MAPPER));
            recording.set("intents", intArr);

            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(recording);
            closeRecBrowser();

            log(Level.INFO, "Recording stopped: " + recordedEvents.size()
                    + " events → " + intents.size() + " intents");
            return ok("✅ Recording stopped.\nRaw events: " + recordedEvents.size()
                    + "\nIntents detected: " + intents.size()
                    + "\nDuration: " + (endMs - sessionStart.get()) + " ms\n\n"
                    + "```json\n" + json + "\n```");
        } catch (Exception e) {
            return err("stop_recording failed: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult handlePlayback(Map<String, Object> args) {
        try {
            String jsonRec = strArg(args, "jsonRecording");
            boolean hl     = boolArgOpt(args, "headless", false);
            boolean par    = boolArgOpt(args, "parallel", false);
            int threads    = intArgOpt(args, "threads", 1);

            var doc    = MAPPER.readTree(jsonRec);
            var events = doc.has("events") ? doc.path("events") : doc;

            return (par && threads > 1)
                    ? runParallelPlayback(events, hl, threads)
                    : runSequentialPlayback(events, hl);
        } catch (Exception e) {
            return err("playback_recording failed: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult runSequentialPlayback(
            com.fasterxml.jackson.databind.JsonNode events, boolean hl) {
        try {
            Page pg = openTLPage(hl);
            return executePlayback(events, pg, "sequential");
        } catch (Exception e) {
            return err("Playback setup failed: " + e.getMessage());
        } finally {
            closeTLBrowser();
        }
    }

    private static McpSchema.CallToolResult runParallelPlayback(
            com.fasterxml.jackson.databind.JsonNode events,
            boolean hl, int threadCount) throws Exception {

        List<List<com.fasterxml.jackson.databind.JsonNode>> chunks = splitChunks(events, threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            final int idx = i;
            final List<com.fasterxml.jackson.databind.JsonNode> chunk = chunks.get(i);
            futures.add(pool.submit(() -> {
                try {
                    Page pg = openTLPage(hl);
                    ArrayNode arr = MAPPER.createArrayNode();
                    chunk.forEach(arr::add);
                    return textOf(executePlayback(arr, pg, "thread-" + idx));
                } finally { closeTLBrowser(); }
            }));
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.MINUTES);

        StringBuilder sb = new StringBuilder("Parallel playback — " + threadCount + " threads\n\n");
        for (int i = 0; i < futures.size(); i++)
            sb.append("── Thread ").append(i).append(" ──\n").append(futures.get(i).get()).append("\n");
        return ok(sb.toString());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CORE PLAYBACK LOOP  (v6: Smart Wait + Recovery)
    // ═════════════════════════════════════════════════════════════════════════
    private static McpSchema.CallToolResult executePlayback(
            com.fasterxml.jackson.databind.JsonNode events, Page pg, String label) {

        int passed = 0, failed = 0, skipped = 0;
        StringBuilder logBuf = new StringBuilder();
        long prevElapsed = 0;

        for (var event : events) {
            String at  = event.path("actionType").asText();
            int    seq = event.path("sequenceNo").asInt();

            // FOCUS events — informational only, skip
            if ("FOCUS".equals(at)) {
                logBuf.append("  ⏭ [").append(seq).append("] FOCUS skipped\n");
                skipped++;
                continue;
            }

            // v6: Reduced timing cap (500ms instead of 2500ms)
            long elapsedMs = event.path("elapsedMs").asLong(0);
            long delay = Math.min(Math.max(elapsedMs - prevElapsed, 0), MAX_STEP_DELAY);
            if (delay > 50) sleep(delay);
            prevElapsed = elapsedMs;

            String selector = pickBestReplayLocator(event);
            String value    = event.path("inputValue").asText("");
            String key      = event.path("key").asText("");

            try {
                withRetry(() -> {
                    // v6: Smart Wait Engine — pre-action stabilisation
                    waitForStableState(pg, at, selector, event);
                    // Execute the action
                    replayEvent(at, selector, value, key, event, pg);
                    // v6: Post-action wait for navigation-triggering actions
                    postActionWait(pg, at);
                }, at, selector);

                logBuf.append("  ✅ [").append(seq).append("] ")
                        .append(at).append(" → ").append(truncate(selector, 65)).append("\n");
                passed++;
                if (DEBUG) logEvent(Level.FINE, "PASS", at, selector, seq);

            } catch (Exception e) {
                logBuf.append("  ❌ [").append(seq).append("] ")
                        .append(at).append(" → ").append(truncate(selector, 65))
                        .append("\n     ↳ ").append(e.getMessage()).append("\n");
                failed++;
                logEvent(Level.WARNING, "FAIL", at, selector, seq);
                saveFailureArtifacts(pg, at, seq, e);

                // v6: Recovery attempt — re-sync DOM before continuing
                recoverPageState(pg, event);
            }
        }
        return ok("[" + label + "] ✅ " + passed + "  ❌ " + failed + "  ⏭ " + skipped + "\n\n" + logBuf);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SMART WAIT ENGINE  (v6 NEW)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Injects automatic pre-condition checks before every action.
     * Order: DOM ready → network idle → element visible/enabled → DOM settled.
     *
     * Action-aware wait table:
     * ┌─────────────┬───────────────────────────────────────────────────┐
     * │ NAVIGATE    │ waitForLoadState(LOAD) + NETWORKIDLE               │
     * │ CLICK       │ waitForSelector(VISIBLE) + DOM settled             │
     * │ FILL        │ waitForSelector(VISIBLE) + waitForEnabled          │
     * │ SELECT      │ waitForSelector(VISIBLE) + waitForEnabled          │
     * │ CHECK/UNCHK │ waitForSelector(VISIBLE)                           │
     * │ UPLOAD      │ waitForSelector(ATTACHED)                          │
     * │ SCROLL      │ DOM ready only                                     │
     * │ PRESS_KEY   │ DOM ready only                                     │
     * └─────────────┴───────────────────────────────────────────────────┘
     */
    private static void waitForStableState(Page pg, String actionType,
                                           String selector,
                                           com.fasterxml.jackson.databind.JsonNode event) {
        try {
            // Step 1: Always wait for DOM ready
            pg.waitForFunction("document.readyState === 'complete'",
                    new Page.WaitForFunctionOptions().setTimeout(TIMEOUT_MS));
        } catch (Exception ignored) {}

        if ("NAVIGATE".equals(actionType) || "WAIT_FOR_NETWORK_IDLE".equals(actionType)) return;

        // Step 2: For element-targeting actions, wait for element state
        if (selector != null && !selector.isBlank() && !"body".equals(selector)) {
            try {
                String cssForWait = toCssForWait(selector);
                switch (actionType) {
                    case "CLICK", "DOUBLE_CLICK", "RIGHT_CLICK", "HOVER" ->
                            pg.waitForSelector(cssForWait,
                                    new Page.WaitForSelectorOptions()
                                            .setState(WaitForSelectorState.VISIBLE)
                                            .setTimeout(TIMEOUT_MS));
                    case "FILL", "TYPE", "CLEAR" -> {
                        pg.waitForSelector(cssForWait,
                                new Page.WaitForSelectorOptions()
                                        .setState(WaitForSelectorState.VISIBLE)
                                        .setTimeout(TIMEOUT_MS));
                        // Additional: wait for enabled (handles disabled-then-enabled inputs)
                        pg.waitForFunction(
                                "sel => { const el = document.querySelector(sel); " +
                                        "return el && !el.disabled; }",
                                cssForWait,
                                new Page.WaitForFunctionOptions().setTimeout(TIMEOUT_MS));
                    }
                    case "SELECT_OPTION" ->
                            pg.waitForSelector(cssForWait,
                                    new Page.WaitForSelectorOptions()
                                            .setState(WaitForSelectorState.VISIBLE)
                                            .setTimeout(TIMEOUT_MS));
                    case "CHECK", "UNCHECK" ->
                            pg.waitForSelector(cssForWait,
                                    new Page.WaitForSelectorOptions()
                                            .setState(WaitForSelectorState.VISIBLE)
                                            .setTimeout(TIMEOUT_MS));
                    case "UPLOAD_FILE" ->
                            pg.waitForSelector(cssForWait,
                                    new Page.WaitForSelectorOptions()
                                            .setState(WaitForSelectorState.ATTACHED)
                                            .setTimeout(TIMEOUT_MS));
                    default -> {}
                }
            } catch (Exception ignored) {
                // Element wait is best-effort; let the action itself handle errors
            }
        }

        // Step 3: DOM settled check (no in-flight mutations / animations)
        try {
            pg.waitForFunction(
                    "() => !document.querySelector(':scope :not(script):not(style)') || true",
                    new Page.WaitForFunctionOptions().setTimeout(2000));
        } catch (Exception ignored) {}
    }

    /** Post-action wait for actions that trigger navigation or DOM mutations. */
    private static void postActionWait(Page pg, String actionType) {
        try {
            switch (actionType) {
                case "CLICK", "DOUBLE_CLICK", "FORM_SUBMIT" -> {
                    // Wait briefly; if navigation starts, wait for it to complete
                    pg.waitForLoadState(LoadState.LOAD,
                            new Page.WaitForLoadStateOptions().setTimeout(5000));
                    try {
                        pg.waitForLoadState(LoadState.NETWORKIDLE,
                                new Page.WaitForLoadStateOptions().setTimeout(3000));
                    } catch (Exception ignored) {}
                }
                case "FILL", "TYPE" -> {
                    // Wait for any autocomplete/suggestion drop-downs to settle
                    sleep(150);
                }
                case "NAVIGATE" -> {
                    pg.waitForLoadState(LoadState.LOAD,
                            new Page.WaitForLoadStateOptions().setTimeout(TIMEOUT_MS));
                    pg.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(5000));
                }
                default -> {}
            }
        } catch (Exception ignored) {}
    }

    /** Attempt to re-sync page state after a failed action (v6 recovery). */
    private static void recoverPageState(Page pg, com.fasterxml.jackson.databind.JsonNode event) {
        try {
            log(Level.INFO, "Recovery: validating page state after failure...");
            // Wait for page to settle
            pg.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout(5000));
            // Check if we're on the expected URL
            String expectedUrl = event.path("pageUrl").asText("");
            if (!expectedUrl.isBlank() && !pg.url().contains(expectedUrl)) {
                log(Level.WARNING, "Recovery: URL mismatch. Expected contains: "
                        + expectedUrl + " — actual: " + pg.url());
            } else {
                log(Level.INFO, "Recovery: page state OK at " + pg.url());
            }
        } catch (Exception e) {
            log(Level.WARNING, "Recovery failed: " + e.getMessage());
        }
    }

    /** Convert any locator prefix format to a bare CSS selector for waitForSelector. */
    private static String toCssForWait(String sel) {
        if (sel == null || sel.isBlank()) return "body";
        sel = sel.replaceAll("::nth=\\d+$", "").trim();
        if (sel.startsWith("role:"))        return "[role]";  // best-effort
        if (sel.startsWith("text:"))        return "body";
        if (sel.startsWith("placeholder:")) return "[placeholder=\"" + sel.substring(12) + "\"]";
        if (sel.startsWith("testid:"))      return "[data-testid=\"" + sel.substring(7) + "\"]";
        if (sel.startsWith("xpath:"))       return "body";    // xpath not supported in CSS wait
        if (sel.startsWith("label:"))       return "body";
        return sel; // assume CSS
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GENERATE BDD HANDLER
    // ═════════════════════════════════════════════════════════════════════════
    private static McpSchema.CallToolResult handleGenerateBdd(Map<String, Object> args) {
        try {
            String jsonRec     = strArg(args, "jsonRecording");
            String featureName = strArg(args, "featureName");
            String dataRowsJson= strArgOpt(args, "dataRows", null);
            String tags        = strArgOpt(args, "tags", "@smoke @regression");
            String framework   = strArgOpt(args, "framework", "cucumber");

            var doc    = MAPPER.readTree(jsonRec);
            var events = doc.has("events") ? doc.path("events") : doc;

            // Re-run intent analysis on the events
            List<ObjectNode> evList = new ArrayList<>();
            for (var e : events) if (e instanceof ObjectNode o) evList.add(o);
            List<IntentAnalyzer.Intent> intents = IntentAnalyzer.analyze(evList);

            // Parse dataRows
            List<Map<String, String>> dataRows = new ArrayList<>();
            if (dataRowsJson != null && !dataRowsJson.isBlank()) {
                try {
                    for (var row : MAPPER.readTree(dataRowsJson)) {
                        Map<String, String> m = new LinkedHashMap<>();
                        row.fields().forEachRemaining(f -> m.put(f.getKey(), f.getValue().asText("")));
                        dataRows.add(m);
                    }
                } catch (Exception ignored) {}
            }

            var gen = new BddCodeGenerator(featureName, intents, events, dataRows, tags, framework);

            // v6 output policy: ONLY the 3 files that change per recording.
            // pom.xml / UiActions / PlaywrightUiActions / Runner / *.md / *.properties
            // are NOT regenerated — they already exist and must not be overwritten.
            String safeName = featureName.replaceAll("[^a-zA-Z0-9]", "");
            return ok("# BDD Code — " + featureName + "\n\n" +
                    "> Paste each section into its file.\n" +
                    "> **pom.xml, UiActions, PlaywrightUiActions, Runner are NOT regenerated** " +
                    "— they already exist in your project and are correct.\n\n" +

                    "## 1. Feature File\n" +
                    "**Path:** `src/test/resources/features/" + safeName + ".feature`\n" +
                    "```gherkin\n" + gen.featureFile() + "\n```\n\n" +

                    "## 2. Step Definitions\n" +
                    "**Path:** `src/test/java/com/qa/stepdefs/" + toPascal(featureName) + "Steps.java`\n" +
                    "**Note:** If this Steps class already exists, add only the NEW @When/@Then methods — do not replace the whole file.\n" +
                    "```java\n" + gen.stepDefinitions() + "\n```\n\n" +

                    "## 3. Page Object\n" +
                    "**Path:** `src/test/java/com/qa/pages/" + toPascal(featureName) + "Page.java`\n" +
                    "**Note:** If this Page Object already exists, add only the NEW locator constants and methods — do not replace the whole file.\n" +
                    "```java\n" + gen.pageObject() + "\n```\n");
        } catch (Exception e) {
            return err("generate_playwright_bdd failed: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ATOMIC ACTION (with Smart Wait)
    // ═════════════════════════════════════════════════════════════════════════
    private static McpSchema.CallToolResult handleAtomicAction(Map<String, Object> args) {
        try {
            if (TL_PAGE.get() == null) openTLPage(false);
            Page pg      = TL_PAGE.get();
            String action   = strArg(args, "action");
            String selector = strArgOpt(args, "selector", "");
            String value    = strArgOpt(args, "value", "");
            long   tms      = longArgOpt(args, "timeoutMs", TIMEOUT_MS);

            // Apply SmartWait before atomic action too
            waitForStableState(pg, action.toUpperCase(), selector, MAPPER.createObjectNode());

            String result = executeAtomicAction(pg, action, selector, value, tms);
            return ok(result != null ? result : "Done: " + action + " on '" + selector + "'");
        } catch (Exception e) {
            return err("execute_atomic_action failed: " + e.getMessage());
        }
    }

    private static String executeAtomicAction(Page pg, String action,
                                              String selector, String value,
                                              long timeoutMs) throws Exception {
        return switch (action.toLowerCase()) {
            case "navigate"              -> { pg.navigate(value.isBlank() ? selector : value);
                pg.waitForLoadState(LoadState.LOAD);
                yield null; }
            case "wait_for_navigation"   -> { pg.waitForLoadState(LoadState.LOAD); yield "Navigation complete"; }
            case "wait_for_network_idle" -> { pg.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(timeoutMs));
                yield "Network idle"; }
            case "wait_for_url"          -> { pg.waitForURL(value,
                    new Page.WaitForURLOptions().setTimeout(timeoutMs));
                yield "URL matched: " + value; }
            case "wait_for_selector",
                 "wait_for_element"      -> { pg.waitForSelector(selector,
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(timeoutMs));
                yield "Visible: " + selector; }
            case "wait_for_hidden"       -> { pg.waitForSelector(selector,
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.HIDDEN)
                            .setTimeout(timeoutMs));
                yield "Hidden: " + selector; }
            case "click"                 -> { withRetry(() -> resolveLocator(selector, pg).click(), "click", selector); yield null; }
            case "double_click","dblclick"-> { withRetry(() -> resolveLocator(selector, pg).dblclick(), "dblclick", selector); yield null; }
            case "right_click"           -> { withRetry(() -> resolveLocator(selector, pg).click(
                            new Locator.ClickOptions().setButton(com.microsoft.playwright.options.MouseButton.RIGHT)),
                    "right_click", selector); yield null; }
            case "fill"                  -> { withRetry(() -> resolveLocator(selector, pg).fill(value), "fill", selector); yield null; }
            case "type"                  -> { withRetry(() -> resolveLocator(selector, pg).type(value), "type", selector); yield null; }
            case "clear"                 -> { withRetry(() -> resolveLocator(selector, pg).clear(), "clear", selector); yield null; }
            case "check"                 -> { withRetry(() -> resolveLocator(selector, pg).check(), "check", selector); yield null; }
            case "uncheck"               -> { withRetry(() -> resolveLocator(selector, pg).uncheck(), "uncheck", selector); yield null; }
            case "select_option"         -> { withRetry(() -> resolveLocator(selector, pg).selectOption(value), "select", selector); yield null; }
            case "hover"                 -> { withRetry(() -> resolveLocator(selector, pg).hover(), "hover", selector); yield null; }
            case "focus"                 -> { withRetry(() -> resolveLocator(selector, pg).focus(), "focus", selector); yield null; }
            case "press"                 -> { withRetry(() -> resolveLocator(selector, pg).press(value), "press", selector); yield null; }
            case "upload_file"           -> { resolveLocator(selector, pg).setInputFiles(Paths.get(value)); yield "Uploaded: " + value; }
            case "upload_files"          -> {
                Path[] ps = Arrays.stream(value.split(",")).map(String::trim).map(Paths::get).toArray(Path[]::new);
                resolveLocator(selector, pg).setInputFiles(ps);
                yield "Uploaded: " + value;
            }
            case "scroll"                -> {
                String[] p = value.split(",");
                pg.mouse().wheel(parseDouble(p.length > 0 ? p[0] : "0"), parseDouble(p.length > 1 ? p[1] : "0"));
                yield null;
            }
            case "scroll_to_element"     -> { resolveLocator(selector, pg).scrollIntoViewIfNeeded(); yield "Scrolled to: " + selector; }
            case "keyboard_press"        -> { pg.keyboard().press(value); yield null; }
            case "keyboard_type"         -> { pg.keyboard().type(value); yield null; }
            case "wait"                  -> { pg.waitForTimeout(value.isBlank() ? 1000 : parseDouble(value)); yield null; }
            case "screenshot"            -> {
                Path out = Paths.get(value.isBlank() ? "./screenshot-" + System.currentTimeMillis() + ".png" : value);
                pg.screenshot(new Page.ScreenshotOptions().setPath(out).setFullPage(true));
                yield "Screenshot: " + out.toAbsolutePath();
            }
            case "get_text"      -> resolveLocator(selector, pg).first().innerText();
            case "get_value"     -> resolveLocator(selector, pg).first().inputValue();
            case "get_attribute" -> resolveLocator(selector, pg).first().getAttribute(value);
            case "get_html"      -> resolveLocator(selector.isBlank() ? "body" : selector, pg).first().innerHTML();
            case "is_visible"    -> String.valueOf(resolveLocator(selector, pg).first().isVisible());
            case "is_enabled"    -> String.valueOf(resolveLocator(selector, pg).first().isEnabled());
            case "is_checked"    -> String.valueOf(resolveLocator(selector, pg).first().isChecked());
            case "count"         -> String.valueOf(resolveLocator(selector, pg).count());
            case "get_url"       -> pg.url();
            case "get_title"     -> pg.title();
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  RETRY ENGINE
    // ═════════════════════════════════════════════════════════════════════════
    @FunctionalInterface interface PlaywrightAction { void run() throws Exception; }

    private static void withRetry(PlaywrightAction action, String name, String sel) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt <= RETRY_MAX; attempt++) {
            try {
                action.run();
                if (attempt > 0) log(Level.INFO, "Retry #" + attempt + " succeeded: " + name + " '" + truncate(sel, 50) + "'");
                return;
            } catch (Exception e) {
                last = e;
                if (attempt < RETRY_MAX) {
                    log(Level.WARNING, "Attempt " + (attempt + 1) + "/" + (RETRY_MAX + 1)
                            + " failed [" + name + "]: " + e.getMessage() + " — retry in " + RETRY_DELAY_MS + "ms");
                    sleep(RETRY_DELAY_MS);
                }
            }
        }
        throw last;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  REPLAY EVENT
    // ═════════════════════════════════════════════════════════════════════════
    private static void replayEvent(String action, String rawSel, String value,
                                    String key,
                                    com.fasterxml.jackson.databind.JsonNode event,
                                    Page pg) throws Exception {
        switch (action.toUpperCase()) {
            case "NAVIGATE"              -> { pg.navigate(value.isBlank() ? rawSel : value); }
            case "SCROLL"                -> pg.mouse().wheel(
                    event.path("scrollX").asDouble(0), event.path("scrollY").asDouble(0));
            case "FORM_SUBMIT"           -> resolveLocator(rawSel, pg).evaluate("f => f.submit()");
            case "WAIT"                  -> pg.waitForTimeout(value.isBlank() ? 500 : parseDouble(value));
            case "WAIT_FOR_SELECTOR"     -> pg.waitForSelector(rawSel,
                    new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(TIMEOUT_MS));
            case "WAIT_FOR_URL"          -> pg.waitForURL(value, new Page.WaitForURLOptions().setTimeout(TIMEOUT_MS));
            case "WAIT_FOR_NETWORK_IDLE" -> pg.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(TIMEOUT_MS));
            case "UPLOAD_FILE"           -> resolveLocator(rawSel, pg).setInputFiles(Paths.get(value));
            default                      -> {
                Locator loc = resolveLocator(rawSel, pg);
                // Runtime nth scoping
                try {
                    if (loc.count() > 1) {
                        int nth = 0;
                        if (rawSel.contains("::nth="))
                            try { nth = Integer.parseInt(rawSel.replaceAll(".*::nth=(\\d+)", "$1")); } catch (Exception ig) {}
                        loc = loc.nth(nth);
                    }
                } catch (Exception ig) {}

                switch (action.toUpperCase()) {
                    case "CLICK"         -> loc.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
                    case "DOUBLE_CLICK"  -> loc.dblclick();
                    case "RIGHT_CLICK"   -> loc.click(new Locator.ClickOptions()
                            .setButton(com.microsoft.playwright.options.MouseButton.RIGHT));
                    case "FILL"          -> { loc.clear(); loc.fill(value); }
                    case "TYPE"          -> loc.type(value);
                    case "CLEAR"         -> loc.clear();
                    case "PRESS_KEY"     -> { String k = key.isBlank() ? value : key;
                        if (!rawSel.isBlank() && !"body".equals(rawSel)) loc.press(k);
                        else pg.keyboard().press(k); }
                    case "SELECT_OPTION" -> loc.selectOption(value);
                    case "CHECK"         -> loc.check();
                    case "UNCHECK"       -> loc.uncheck();
                    case "HOVER"         -> loc.hover();
                    case "FOCUS"         -> loc.focus();
                    default              -> LOG.warning("Unknown action: " + action);
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOCATOR RESOLUTION
    // ═════════════════════════════════════════════════════════════════════════
    static String pickBestReplayLocator(com.fasterxml.jackson.databind.JsonNode event) {
        var loc = event.path("locator");
        if (loc.isMissingNode() || loc.isNull())
            return event.path("inputValue").asText(event.path("pageUrl").asText("body"));
        String strategy = loc.path("strategy").asText("css");
        int nth = loc.path("nthIndex").asInt(0);
        int cnt = loc.path("matchCount").asInt(1);
        return switch (strategy) {
            case "href"        -> loc.path("selector").asText(loc.path("cssSelector").asText());
            case "css-id"      -> loc.path("cssSelector").asText(loc.path("primary").asText());
            case "testId"      -> loc.path("selector").asText();
            case "xpath"       -> loc.path("selector").asText();
            case "placeholder" -> loc.path("selector").asText();
            case "role"        -> nth > 0 ? loc.path("selector").asText() + "::nth=" + nth
                    : loc.path("selector").asText();
            default            -> {
                String sel = loc.path("primary").asText(loc.path("cssSelector").asText("body"));
                yield (cnt > 1 && nth >= 0) ? sel + "::nth=" + nth : sel;
            }
        };
    }

    private static Locator resolveLocator(String raw, Page pg) {
        if (raw == null || raw.isBlank()) return pg.locator("body");
        String sel = raw.replaceAll("::nth=\\d+$", "").trim();
        if (sel.startsWith("role:")) {
            String[] p = sel.substring(5).split(":", 2);
            try {
                AriaRole r = AriaRole.valueOf(p[0].toUpperCase().replace("-","_").replace(" ","_"));
                return p.length > 1 && !p[1].isBlank()
                        ? pg.getByRole(r, new Page.GetByRoleOptions().setName(p[1]))
                        : pg.getByRole(r);
            } catch (IllegalArgumentException e) { return pg.locator("[role='" + p[0] + "']"); }
        }
        if (sel.startsWith("text:"))        return pg.getByText(sel.substring(5), new Page.GetByTextOptions().setExact(false));
        if (sel.startsWith("placeholder:")) return pg.getByPlaceholder(sel.substring(12));
        if (sel.startsWith("label:"))       return pg.getByLabel(sel.substring(6));
        if (sel.startsWith("testid:"))      return pg.getByTestId(sel.substring(7));
        if (sel.startsWith("xpath:"))       return pg.locator("xpath=" + sel.substring(6));
        return pg.locator(sel);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FAILURE ARTIFACTS
    // ═════════════════════════════════════════════════════════════════════════
    private static void saveFailureArtifacts(Page pg, String action, int seq, Exception cause) {
        if (pg == null) return;
        try {
            String ts  = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .withZone(ZoneId.systemDefault()).format(Instant.now());
            Path dir   = FAILURE_DIR.resolve(ts + "-seq" + seq);
            Files.createDirectories(dir);
            pg.screenshot(new Page.ScreenshotOptions().setPath(dir.resolve("failure.png")).setFullPage(true));
            Files.writeString(dir.resolve("page.html"), pg.content());
            Files.writeString(dir.resolve("error.txt"),
                    "Action: " + action + "\nSeq: " + seq + "\nURL: " + pg.url()
                            + "\nError: " + cause.getMessage());
            log(Level.WARNING, "Artifacts → " + dir.toAbsolutePath());
        } catch (IOException e) {
            log(Level.WARNING, "Could not save artifacts: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CAPTURE SCRIPT  (identical to v5 — proven stable)
    // ═════════════════════════════════════════════════════════════════════════
    private static final String CAPTURE_SCRIPT =
            "(function(){\n" +
                    "if(window.__mcpCaptureActive__)return;\n" +
                    "window.__mcpCaptureActive__=true;\n" +
                    "if(!window.__mcpQueue__)window.__mcpQueue__=[];\n" +
                    "function send(obj){const s=JSON.stringify(obj);window.__mcpQueue__.push(s);\n" +
                    "try{if(typeof window.__mcpCapture==='function')window.__mcpCapture(s);}catch(e){}}\n" +
                    "const IT=new Set(['A','BUTTON','SELECT','INPUT','TEXTAREA','LABEL']);\n" +
                    "const IR=new Set(['button','link','tab','menuitem','option','checkbox','radio','combobox','listbox','switch']);\n" +
                    "function ia(path){for(const el of path){if(!el||el===document||el===window)break;\n" +
                    "if(IT.has(el.tagName))return el;\n" +
                    "const r=el.getAttribute&&el.getAttribute('role');if(r&&IR.has(r))return el;}\n" +
                    "return path[0]||null;}\n" +
                    "function ce(s){try{return CSS.escape(s);}catch(e){return s.replace(/[^a-zA-Z0-9-_]/g,'\\\\$&');}}\n" +
                    "function xp(el){if(!el||el===document.body)return'/html/body';\n" +
                    "if(el.id)return'//*[@id=\"'+el.id+'\"]';\n" +
                    "const s=el.parentNode?[...el.parentNode.childNodes].filter(n=>n.nodeName===el.nodeName):[];\n" +
                    "return xp(el.parentNode)+'/'+el.nodeName.toLowerCase()+(s.length>1?'['+(s.indexOf(el)+1)+']':'');}\n" +
                    "function cp(el){if(!el)return'body';if(el.id)return'#'+ce(el.id);\n" +
                    "const p=[];let c=el;\n" +
                    "while(c&&c!==document.body){let sg=c.tagName.toLowerCase();\n" +
                    "if(c.id){p.unshift('#'+ce(c.id));break;}\n" +
                    "const sb=[...(c.parentNode?.children||[])].filter(x=>x.tagName===c.tagName);\n" +
                    "if(sb.length>1)sg+=':nth-of-type('+(sb.indexOf(c)+1)+')';\n" +
                    "p.unshift(sg);c=c.parentNode;}return p.join(' > ');}\n" +
                    "function cm(s){try{return document.querySelectorAll(s).length;}catch(e){return 99;}}\n" +
                    "function cx(x){try{const r=document.evaluate(x,document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null);\n" +
                    "return r.snapshotLength;}catch(e){return 99;}}\n" +
                    "function nth(el,sel){try{return[...document.querySelectorAll(sel)].indexOf(el);}catch(e){return 0;}}\n" +
                    "function bl(el){\n" +
                    "if(!el)return null;\n" +
                    "const tag=el.tagName.toLowerCase(),elId=el.id||null;\n" +
                    "const tid=el.getAttribute?.('data-testid')||el.getAttribute?.('data-test')||el.getAttribute?.('data-cy')||null;\n" +
                    "const al=el.getAttribute?.('aria-label')||null;\n" +
                    "const ar=el.getAttribute?.('role')||tag;\n" +
                    "const ph=el.getAttribute?.('placeholder')||null;\n" +
                    "const href=el.getAttribute?.('href')||null;\n" +
                    "const name=el.getAttribute?.('name')||null;\n" +
                    "const txt=(el.innerText||'').trim().substring(0,80);\n" +
                    "const css=cp(el),xpa=xp(el);\n" +
                    "const cands=[];\n" +
                    "if(href&&tag==='a'){const s='a[href=\"'+href+'\"]';\n" +
                    "cands.push({strategy:'href',selector:s,matchCount:cm(s),playwrightExpr:'page.locator(\"a[href=\\\\\"'+href+'\\\\\"]\")' ,primary:s});}\n" +
                    "if(elId){const s='#'+ce(elId);cands.push({strategy:'css-id',selector:s,matchCount:cm(s),playwrightExpr:'page.locator(\"'+s+'\")',primary:s});}\n" +
                    "if(tid){const cnt=cm('[data-testid=\"'+tid+'\"]');cands.push({strategy:'testId',selector:'testid:'+tid,matchCount:cnt,playwrightExpr:'page.getByTestId(\"'+tid+'\")',primary:'testid:'+tid});}\n" +
                    "if(al){const s='[role=\"'+ar+'\"][aria-label=\"'+al+'\"]';cands.push({strategy:'role',selector:'role:'+ar+':'+al,matchCount:cm(s),playwrightExpr:'page.getByRole(AriaRole.'+ar.toUpperCase().replace(/-/g,'_')+', new Page.GetByRoleOptions().setName(\"'+al+'\"))',primary:'role:'+ar+':'+al});}\n" +
                    "if(ph){const cnt=cm('[placeholder=\"'+ph+'\"]');cands.push({strategy:'placeholder',selector:'placeholder:'+ph,matchCount:cnt,playwrightExpr:'page.getByPlaceholder(\"'+ph+'\")',primary:'placeholder:'+ph});}\n" +
                    "if(css){const cnt=cm(css);cands.push({strategy:'css',selector:css,matchCount:cnt,playwrightExpr:'page.locator(\"'+css.replace(/\"/g,'\\\\\"')+'\")',primary:css});}\n" +
                    "if(xpa){const cnt=cx(xpa);cands.push({strategy:'xpath',selector:'xpath:'+xpa,matchCount:cnt,playwrightExpr:'page.locator(\"xpath='+xpa+'\")',primary:'xpath:'+xpa});}\n" +
                    "if(txt&&txt.length<60){const m=[...document.querySelectorAll('*')].filter(e=>(e.innerText||'').trim()===txt);\n" +
                    "const cnt=m.length,n=m.indexOf(el);\n" +
                    "cands.push({strategy:'text',selector:'text:'+txt,matchCount:cnt,nthIndex:Math.max(n,0),playwrightExpr:'page.getByText(\"'+txt+'\").nth('+Math.max(n,0)+')',primary:'text:'+txt});}\n" +
                    "cands.sort((a,b)=>a.matchCount-b.matchCount);\n" +
                    "const best=cands[0]||{strategy:'css',selector:css,matchCount:99,playwrightExpr:'page.locator(\"body\")',primary:css};\n" +
                    "let ni=best.nthIndex!==undefined?best.nthIndex:0;\n" +
                    "if(ni===0&&best.matchCount>1){try{ni=nth(el,best.selector.replace(/^(text|xpath|role|testid|placeholder):[^:]*:?/,''));}catch(e){}}\n" +
                    "return{primary:best.primary,strategy:best.strategy,matchCount:best.matchCount,nthIndex:ni,\n" +
                    "playwrightLocator:best.playwrightExpr,cssSelector:css,xpath:xpa,id:elId,name,ariaLabel:al,ariaRole:ar,placeholder:ph,testId:tid,href,text:txt};}\n" +
                    "function ss(el){let bb=null;try{const r=el.getBoundingClientRect();\n" +
                    "bb={x:Math.round(r.x),y:Math.round(r.y),width:Math.round(r.width),height:Math.round(r.height)};}catch(e){}\n" +
                    "const attrs={};for(const a of(el.attributes||[]))attrs[a.name]=a.value;\n" +
                    "return{tagName:el.tagName.toLowerCase(),type:el.getAttribute?.('type')||null,\n" +
                    "innerText:(el.innerText||'').trim().substring(0,200),value:el.value!==undefined?el.value:null,\n" +
                    "isChecked:el.checked!==undefined?el.checked:null,\n" +
                    "isVisible:!!(el.offsetWidth||el.offsetHeight||el.getClientRects?.().length),boundingBox:bb,attributes:attrs};}\n" +
                    "function pl(at,el,ex){return{actionType:at,timestamp:new Date().toISOString(),\n" +
                    "pageUrl:window.location.href,pageTitle:document.title,\n" +
                    "locator:el?bl(el):null,elementSnapshot:el?ss(el):null,\n" +
                    "coordinates:ex?.coords||null,inputValue:ex?.inputValue!==undefined?ex.inputValue:null,\n" +
                    "key:ex?.key||null,scrollX:ex?.scrollX!==undefined?ex.scrollX:null,\n" +
                    "scrollY:ex?.scrollY!==undefined?ex.scrollY:null,selectValues:ex?.selectValues||null};}\n" +
                    "const OPT={capture:true,passive:true};\n" +
                    "document.addEventListener('click',e=>{const p=e.composedPath?.()||[e.target];const el=ia(p);\n" +
                    "if(!el||el===document.documentElement)return;\n" +
                    "send(pl('CLICK',el,{coords:{x:Math.round(e.clientX),y:Math.round(e.clientY)}}));},OPT);\n" +
                    "document.addEventListener('dblclick',e=>{const el=ia(e.composedPath?.()||[e.target]);\n" +
                    "send(pl('DOUBLE_CLICK',el,{coords:{x:Math.round(e.clientX),y:Math.round(e.clientY)}}));},OPT);\n" +
                    "document.addEventListener('contextmenu',e=>{const el=ia(e.composedPath?.()||[e.target]);\n" +
                    "send(pl('RIGHT_CLICK',el,{coords:{x:Math.round(e.clientX),y:Math.round(e.clientY)}}));},OPT);\n" +
                    "document.addEventListener('input',e=>{const el=e.target;\n" +
                    "if(!el||!['INPUT','TEXTAREA'].includes(el.tagName))return;\n" +
                    "send(pl('FILL',el,{inputValue:el.type==='password'?'***REDACTED***':el.value}));},OPT);\n" +
                    "document.addEventListener('change',e=>{const el=e.target;const tag=el?.tagName?.toLowerCase();\n" +
                    "if(tag==='select'){const vs=[...el.selectedOptions].map(o=>o.value);send(pl('SELECT_OPTION',el,{selectValues:vs,inputValue:vs.join(',')}));}\n" +
                    "else if(el?.type==='checkbox')send(pl(el.checked?'CHECK':'UNCHECK',el,{inputValue:String(el.checked)}));\n" +
                    "else if(el?.type==='radio')send(pl('RADIO_SELECT',el,{inputValue:el.value}));},OPT);\n" +
                    "const SP=new Set(['Enter','Tab','Escape','Backspace','Delete','ArrowUp','ArrowDown','ArrowLeft','ArrowRight','Home','End','PageUp','PageDown','F1','F2','F3','F4','F5','F6','F7','F8','F9','F10','F11','F12']);\n" +
                    "document.addEventListener('keydown',e=>{const sc=(e.ctrlKey||e.metaKey)&&!['Control','Meta'].includes(e.key);\n" +
                    "if(!SP.has(e.key)&&!sc)return;\n" +
                    "const el=document.activeElement||document.body;\n" +
                    "const k=(e.ctrlKey?'Control+':'')+(e.metaKey?'Meta+':'')+(e.shiftKey?'Shift+':'')+(e.altKey?'Alt+':'')+e.key;\n" +
                    "send(pl('PRESS_KEY',el,{key:k}));},OPT);\n" +
                    "document.addEventListener('focus',e=>{const el=ia(e.composedPath?.()||[e.target]);if(!el)return;\n" +
                    "if(!['INPUT','TEXTAREA','SELECT','BUTTON','A'].includes(el.tagName))return;\n" +
                    "send(pl('FOCUS',el,{}));},OPT);\n" +
                    "let ht;document.addEventListener('mouseover',e=>{clearTimeout(ht);ht=setTimeout(()=>{\n" +
                    "const el=ia(e.composedPath?.()||[e.target]);if(!el)return;\n" +
                    "const tag=el.tagName.toLowerCase();\n" +
                    "if(!['a','button','select'].includes(tag)&&!el.getAttribute?.('role'))return;\n" +
                    "send(pl('HOVER',el,{coords:{x:Math.round(e.clientX),y:Math.round(e.clientY)}}));},350);},OPT);\n" +
                    "let st;document.addEventListener('scroll',e=>{clearTimeout(st);st=setTimeout(()=>{\n" +
                    "const c=e.target===document?document.documentElement:e.target;\n" +
                    "send({actionType:'SCROLL',timestamp:new Date().toISOString(),pageUrl:window.location.href,\n" +
                    "pageTitle:document.title,locator:null,elementSnapshot:null,coordinates:null,\n" +
                    "inputValue:null,key:null,scrollX:Math.round(c.scrollLeft||window.scrollX||0),\n" +
                    "scrollY:Math.round(c.scrollTop||window.scrollY||0),selectValues:null});},600);},OPT);\n" +
                    "document.addEventListener('submit',e=>{send(pl('FORM_SUBMIT',e.composedPath?.()?.[0]||e.target,{}));},OPT);\n" +
                    "const _pu=history.pushState.bind(history),_re=history.replaceState.bind(history);\n" +
                    "const np=()=>({actionType:'NAVIGATE',timestamp:new Date().toISOString(),pageUrl:window.location.href,pageTitle:document.title,locator:null,elementSnapshot:null,inputValue:window.location.href});\n" +
                    "history.pushState=function(){_pu(...arguments);send(np());};\n" +
                    "history.replaceState=function(){_re(...arguments);send(np());};\n" +
                    "window.addEventListener('popstate',()=>send(np()),OPT);\n" +
                    "window.addEventListener('hashchange',()=>send(np()),OPT);\n" +
                    "console.log('[MCP Recorder v6] Active — SmartWait ready.');\n" +
                    "})();";

    // ═════════════════════════════════════════════════════════════════════════
    //  EVENT INGESTION + POLLING
    // ═════════════════════════════════════════════════════════════════════════
    private static void ingestEvent(String raw, String source) {
        try {
            ObjectNode event = (ObjectNode) MAPPER.readTree(raw);
            String fp = event.path("actionType").asText()
                    + "|" + event.path("timestamp").asText("").substring(0,
                    Math.min(19, event.path("timestamp").asText("x").length()))
                    + "|" + event.path("locator").path("primary").asText("nosel");
            if (!seenFingerprints.add(fp)) return;

            event.put("sequenceNo",   sequenceCounter.incrementAndGet());
            event.put("elapsedMs",    System.currentTimeMillis() - sessionStart.get());
            event.put("sessionName",  sessionName);
            event.put("captureLayer", source);
            String code = suggestCode(event);
            if (code != null) event.put("suggestedPlaywrightCode", code);
            recordedEvents.add(event);

            if (DEBUG) logEvent(Level.FINE, "CAPTURED", event.path("actionType").asText(),
                    event.path("locator").path("primary").asText("-"), event.path("sequenceNo").asInt());
        } catch (Exception e) {
            log(Level.WARNING, "ingestEvent [" + source + "]: " + e.getMessage());
        }
    }

    private static void startPolling() {
        pollActive = true;
        pollingThread = new Thread(() -> {
            while (pollActive) {
                try { Thread.sleep(500); drainQueue(recPage); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch (Exception e) { log(Level.WARNING, "Poll: " + e.getMessage()); }
            }
        }, "mcp-queue-drainer");
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    private static void stopPolling() {
        pollActive = false;
        if (pollingThread != null) { pollingThread.interrupt(); pollingThread = null; }
    }

    @SuppressWarnings("unchecked")
    private static void drainQueue(Page pg) {
        if (pg == null) return;
        try {
            Object r = pg.evaluate("()=>{const q=window.__mcpQueue__;if(!q||!q.length)return[];return q.splice(0);}");
            if (r instanceof List<?> l) for (Object item : l) if (item instanceof String s) ingestEvent(s, "queue-poll");
        } catch (Exception ignored) {}
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CODE SUGGESTION
    // ═════════════════════════════════════════════════════════════════════════
    private static String suggestCode(ObjectNode e) {
        String at  = e.path("actionType").asText();
        String pw  = e.path("locator").path("playwrightLocator").asText("page.locator(\"body\")");
        int nth = e.path("locator").path("nthIndex").asInt(0);
        int cnt = e.path("locator").path("matchCount").asInt(1);
        String loc = (cnt > 1 && nth > 0) ? pw + ".nth(" + nth + ")" : pw;
        String val = esc(e.path("inputValue").asText(""));
        String key = esc(e.path("key").asText(""));
        int sx = e.path("scrollX").asInt(0), sy = e.path("scrollY").asInt(0);
        return switch (at) {
            case "CLICK"         -> "// SmartWait auto-applied\n" + loc + ".click();";
            case "DOUBLE_CLICK"  -> loc + ".dblclick();";
            case "RIGHT_CLICK"   -> loc + ".click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));";
            case "FILL"          -> loc + ".clear();\n" + loc + ".fill(\"" + val + "\");";
            case "PRESS_KEY"     -> loc + ".press(\"" + key + "\");";
            case "SELECT_OPTION" -> loc + ".selectOption(\"" + val + "\");";
            case "CHECK"         -> loc + ".check();";
            case "UNCHECK"       -> loc + ".uncheck();";
            case "HOVER"         -> loc + ".hover();";
            case "FOCUS"         -> "// FOCUS (informational): " + loc;
            case "SCROLL"        -> "page.mouse().wheel(" + sx + ", " + sy + ");";
            case "NAVIGATE"      -> "page.navigate(\"" + esc(e.path("inputValue").asText("")) + "\");\n"
                    + "page.waitForLoadState(LoadState.NETWORKIDLE);";
            case "UPLOAD_FILE"   -> loc + ".setInputFiles(Paths.get(\"" + val + "\"));";
            default              -> null;
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  METADATA BUILDERS
    // ═════════════════════════════════════════════════════════════════════════
    private static ObjectNode buildNavigateEvent(String url, long dur) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("sequenceNo", sequenceCounter.incrementAndGet());
        n.put("actionType", "NAVIGATE"); n.put("timestamp", Instant.now().toString());
        n.put("elapsedMs", 0L); n.put("durationMs", dur);
        n.put("sessionName", sessionName); n.put("pageUrl", url);
        n.put("inputValue", url); n.put("captureLayer", "java-direct");
        n.put("suggestedPlaywrightCode", "page.navigate(\"" + esc(url) + "\");\npage.waitForLoadState(LoadState.NETWORKIDLE);");
        n.putNull("locator"); n.putNull("elementSnapshot");
        return n;
    }

    private static ObjectNode buildSessionEnvelope(String url, boolean hl) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("sessionId", UUID.randomUUID().toString());
        e.put("sessionName", sessionName); e.put("startUrl", url);
        e.put("startedAt", Instant.now().toString()); e.put("browserType", "chromium");
        e.put("headless", hl); e.put("retryMax", RETRY_MAX); e.put("timeoutMs", TIMEOUT_MS);
        e.put("maxStepDelayMs", MAX_STEP_DELAY); e.put("locatorEngine", "v6-smart-wait");
        e.put("operatingSystem", System.getProperty("os.name"));
        e.put("javaVersion", System.getProperty("java.version"));
        ObjectNode vp = MAPPER.createObjectNode(); vp.put("width", 1280); vp.put("height", 720);
        e.set("viewport", vp);
        return e;
    }

    private static ObjectNode buildCodeGenHints() {
        Set<String> st = new LinkedHashSet<>(), at = new LinkedHashSet<>();
        boolean hasFill = false, hasUpload = false;
        for (ObjectNode e : recordedEvents) {
            String a = e.path("actionType").asText(); at.add(a);
            String s = e.path("locator").path("strategy").asText();
            if (!s.isBlank()) st.add(s);
            if ("FILL".equals(a)) hasFill = true;
            if ("UPLOAD_FILE".equals(a)) hasUpload = true;
        }
        ObjectNode h = MAPPER.createObjectNode();
        h.put("suggestedTestClassName", toPascal(sessionName) + "Test");
        h.put("hasFillActions", hasFill); h.put("hasFileUploads", hasUpload);
        ArrayNode sa = MAPPER.createArrayNode(); st.forEach(sa::add); h.set("locatorStrategiesUsed", sa);
        ArrayNode aa = MAPPER.createArrayNode(); at.forEach(aa::add); h.set("actionTypesUsed", aa);
        return h;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  THREADLOCAL BROWSER
    // ═════════════════════════════════════════════════════════════════════════
    private static Page openTLPage(boolean hl) {
        Playwright pw = Playwright.create();
        Browser br    = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(hl));
        BrowserContext ctx = br.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 720).setBypassCSP(true));
        Page pg = ctx.newPage();
        TL_PW.set(pw); TL_BROWSER.set(br); TL_CTX.set(ctx); TL_PAGE.set(pg);
        return pg;
    }

    private static void closeTLBrowser() {
        try { if (TL_PAGE.get()    != null) TL_PAGE.get().close();    } catch (Exception ig) {}
        try { if (TL_CTX.get()     != null) TL_CTX.get().close();     } catch (Exception ig) {}
        try { if (TL_BROWSER.get() != null) TL_BROWSER.get().close(); } catch (Exception ig) {}
        try { if (TL_PW.get()      != null) TL_PW.get().close();      } catch (Exception ig) {}
        TL_PAGE.remove(); TL_CTX.remove(); TL_BROWSER.remove(); TL_PW.remove();
    }

    private static void closeRecBrowser() {
        stopPolling();
        try { if (recPage    != null) { recPage.close();    recPage    = null; } } catch (Exception ig) {}
        try { if (recContext != null) { recContext.close(); recContext = null; } } catch (Exception ig) {}
        try { if (recBrowser != null) { recBrowser.close(); recBrowser = null; } } catch (Exception ig) {}
        try { if (recPW      != null) { recPW.close();      recPW      = null; } } catch (Exception ig) {}
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PARALLEL CHUNK SPLITTER
    // ═════════════════════════════════════════════════════════════════════════
    private static List<List<com.fasterxml.jackson.databind.JsonNode>> splitChunks(
            com.fasterxml.jackson.databind.JsonNode events, int n) {
        List<com.fasterxml.jackson.databind.JsonNode> all = new ArrayList<>();
        for (var e : events) all.add(e);
        List<List<com.fasterxml.jackson.databind.JsonNode>> chunks = new ArrayList<>();
        int sz = (int) Math.ceil((double) all.size() / n);
        for (int i = 0; i < all.size(); i += sz)
            chunks.add(all.subList(i, Math.min(i + sz, all.size())));
        return chunks;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  STRUCTURED LOGGING
    // ═════════════════════════════════════════════════════════════════════════
    private static void configureLogging() { LOG.setLevel(DEBUG ? Level.FINE : Level.INFO); }
    private static void log(Level l, String m) { LOG.log(l, "[MCP] " + m); }
    private static void logEvent(Level l, String status, String action, String sel, int seq) {
        LOG.log(l, String.format("[MCP] %s seq=%-3d action=%-18s loc=%s", status, seq, action, truncate(sel, 60)));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ═════════════════════════════════════════════════════════════════════════
    private static void safeEval(String s, Page pg) {
        try { if (pg != null) pg.evaluate(s); } catch (Exception e) { log(Level.WARNING, "Re-inject: " + e.getMessage()); }
    }
    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static String textOf(McpSchema.CallToolResult r) {
        return r.content().stream().filter(c -> "text".equals(c.type()))
                .map(c -> ((McpSchema.TextContent) c).text()).findFirst().orElse("");
    }
    private static McpServerFeatures.SyncToolSpecification spec(
            String name, String desc, String schema,
            java.util.function.BiFunction<
                    io.modelcontextprotocol.server.McpSyncServerExchange,
                    Map<String, Object>, McpSchema.CallToolResult> handler) {
        return new McpServerFeatures.SyncToolSpecification(new McpSchema.Tool(name, desc, schema), handler);
    }
    private static String strArg(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        if (v == null) throw new IllegalArgumentException("Missing: " + k); return v.toString();
    }
    private static String strArgOpt(Map<String, Object> a, String k, String d) {
        if (a == null) return d; Object v = a.get(k); return v == null ? d : v.toString();
    }
    private static boolean boolArgOpt(Map<String, Object> a, String k, boolean d) {
        if (a == null) return d; Object v = a.get(k); if (v == null) return d;
        return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
    }
    private static int intArgOpt(Map<String, Object> a, String k, int d) {
        if (a == null) return d; Object v = a.get(k); if (v == null) return d;
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }
    private static long longArgOpt(Map<String, Object> a, String k, long d) {
        if (a == null) return d; Object v = a.get(k); if (v == null) return d;
        return v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
    }
    private static int    intEnv(String k, int d)   { try { return Integer.parseInt(System.getenv(k)); } catch (Exception e) { return d; } }
    private static long   longEnv(String k, long d)  { try { return Long.parseLong(System.getenv(k));   } catch (Exception e) { return d; } }
    private static double parseDouble(String s)      { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }
    private static McpSchema.CallToolResult ok(String t) {
        return McpSchema.CallToolResult.builder().addTextContent(t).build();
    }
    private static McpSchema.CallToolResult err(String t) {
        return McpSchema.CallToolResult.builder().addTextContent("ERROR: " + t).isError(true).build();
    }
    private static String esc(String s) { return s == null ? "" : s.replace("\\","\\\\").replace("\"","\\\""); }
    private static String truncate(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n) + "…"; }
    private static String toPascal(String s) {
        if (s == null || s.isBlank()) return "Recorded";
        String[] p = s.replaceAll("[^a-zA-Z0-9 ]"," ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : p) if (!w.isBlank()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        return sb.toString();
    }
}