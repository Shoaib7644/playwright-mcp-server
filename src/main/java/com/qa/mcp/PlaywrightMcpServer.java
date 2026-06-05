package com.qa.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PlaywrightMcpServer v7.1 — Salesforce Lightning + SPA + Enterprise Ready
 *
 * ══════════════════════════════════════════════════════════════════════
 *  Core capabilities:
 *    - Smart Wait Engine with Salesforce-safe DOMCONTENTLOADED strategy
 *    - waitForSalesforceLightning(): Aura/LWC spinner + render stabilization
 *    - FILL: click-to-focus before fill for LWC/Aura shadow-DOM inputs
 *    - FILL debounce: collapses rapid same-field fills to final value
 *    - onFrameNavigated: CAPTURE_SCRIPT re-injection into Lightning iframes
 *    - onLoad: synthetic NAVIGATE events for SPA/SSO transition tracking
 *    - Retry engine with failure artifacts and structured logging
 *    - Parallel playback via ThreadLocal page management
 *    - Dynamic href sanitization and locator deduplication
 *    - Thread-safe ingestEvent with INGEST_LOCK monitor
 *
 *  Timeout constants:
 *    SALESFORCE_PAGE_TIMEOUT    = 90 000 ms  (full page / SSO load)
 *    SALESFORCE_ELEMENT_TIMEOUT = 30 000 ms  (Lightning element wait)
 *    LIGHTNING_TIMEOUT          = 20 000 ms  (Aura/LWC render checks)
 * ══════════════════════════════════════════════════════════════════════
 */
public class PlaywrightMcpServer {

    private static final Logger LOG = Logger.getLogger("MCP");
    private static final boolean DEBUG = "DEBUG".equalsIgnoreCase(
            System.getenv().getOrDefault("MCP_LOG_LEVEL", "INFO"));

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ── Config (env-overridable) ──────────────────────────────────────────────
    private static final int  RETRY_MAX          = intEnv("MCP_RETRY_MAX", 3);
    private static final long TIMEOUT_MS         = longEnv("MCP_TIMEOUT_MS", 30_000);
    private static final long RETRY_DELAY_MS     = longEnv("MCP_RETRY_DELAY_MS", 800);
    private static final long MAX_STEP_DELAY     = longEnv("MCP_MAX_STEP_DELAY_MS", 500);
    // [PS-3 FIX] TYPE_KEY_DELAY_MS constant — used by pressSequentially() in replayEvent
    // and executeAtomicAction. 50 ms clears a 30 ms debounce window while keeping
    // a 20-field form replay under 5 seconds.
    private static final long TYPE_KEY_DELAY_MS  = longEnv("MCP_TYPE_KEY_DELAY_MS", 50);
    private static final Path FAILURE_DIR        = Paths.get(
            System.getenv().getOrDefault("MCP_FAILURE_DIR", "./mcp-failures"));

    // ── Salesforce-specific timeout constants ─────────────────────────────────
    private static final long SALESFORCE_PAGE_TIMEOUT    = longEnv("MCP_SF_PAGE_TIMEOUT_MS",    90_000);
    private static final long SALESFORCE_ELEMENT_TIMEOUT = longEnv("MCP_SF_ELEMENT_TIMEOUT_MS", 30_000);
    private static final long LIGHTNING_TIMEOUT          = longEnv("MCP_SF_LIGHTNING_TIMEOUT_MS", 20_000);
    private static final long LWC_MICROTASK_BUFFER_MS   = longEnv("MCP_LWC_BUFFER_MS", 1_000);

    // ── Recording state ───────────────────────────────────────────────────────
    private static final List<ObjectNode> recordedEvents   = new CopyOnWriteArrayList<>();
    private static final Set<String>      seenFingerprints = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final AtomicInteger    sequenceCounter  = new AtomicInteger(0);
    private static final AtomicLong       sessionStart     = new AtomicLong(0);
    private static volatile ObjectNode    sessionEnvelope  = null;
    private static volatile String        sessionName      = "Unnamed Session";

    // ── FILL debounce state ───────────────────────────────────────────────────
    private static final Map<String, ObjectNode> pendingFillByLocator =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Map<String, Long> pendingFillTimestamp =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private static final long FILL_DEBOUNCE_MS = 3_000L;

    // ── ingestEvent concurrency lock ──────────────────────────────────────────
    // Lock ordering rule: RECORDING_LOCK (outer) → INGEST_LOCK (inner).
    // Never acquire RECORDING_LOCK while INGEST_LOCK is held.
    private static final Object INGEST_LOCK = new Object();

    // ── Recording session concurrency lock ────────────────────────────────────
    // [PS-2 FIX] Proper import + short name via import statement above.
    // Protects the full start_recording and stop_recording initialization sequences.
    // Lock ordering: RECORDING_LOCK (outer) → INGEST_LOCK (inner).
    private static final ReentrantLock RECORDING_LOCK = new ReentrantLock();

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

    // ── Polling thread ────────────────────────────────────────────────────────
    // [PS-1 + PS-7 FIX] Replaced volatile pollingThread + volatile pollActive with
    // a single AtomicReference<Thread>. Invariant: null = not running, non-null = running.
    // compareAndSet guarantees no double-start and no NPE window.
    private static final AtomicReference<Thread> POLLING_THREAD = new AtomicReference<>(null);

    // ═════════════════════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ═════════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws Exception {
        configureLogging();
        var transport = new StdioServerTransportProvider(new ObjectMapper());
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("playwright-mcp-server", "7.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true).logging().build())
                .tools(
                        spec("start_recording",
                                "Launch Chrome (headful), bypass CSP, capture ALL interactions. " +
                                        "Salesforce Lightning / LWC compatible.",
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

        log(Level.INFO, "PlaywrightMcpServer v7.1 — Salesforce Lightning + SPA + Enterprise Ready");
        log(Level.INFO, "  retry=" + RETRY_MAX + " timeout=" + TIMEOUT_MS + "ms"
                + " sfPageTimeout=" + SALESFORCE_PAGE_TIMEOUT + "ms"
                + " lightningTimeout=" + LIGHTNING_TIMEOUT + "ms"
                + " typeKeyDelay=" + TYPE_KEY_DELAY_MS + "ms"
                + " debug=" + DEBUG);
        try { Thread.currentThread().join(); }
        catch (InterruptedException e) { log(Level.INFO, "Shutdown."); }
        finally { server.close(); closeRecBrowser(); closeTLBrowser(); }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HANDLERS
    // ═════════════════════════════════════════════════════════════════════════

    // [TASK-4] handleStartRecording protected by RECORDING_LOCK.
    // tryLock() rejects concurrent callers immediately — a queued start is never correct.
    private static McpSchema.CallToolResult handleStartRecording(Map<String, Object> args) {
        if (!RECORDING_LOCK.tryLock()) {
            return err("start_recording rejected — a recording session is already initializing. " +
                    "Call stop_recording() first.");
        }
        try {
            String url  = strArg(args, "url");
            sessionName = strArgOpt(args, "sessionName",
                    "Recording-" + Instant.now().toString().substring(0, 10));
            boolean hl  = boolArgOpt(args, "headless", false);

            // ── Atomic teardown of any prior session ─────────────────────────
            closeRecBrowser();

            // ── Reset all recording state ────────────────────────────────────
            recordedEvents.clear();
            seenFingerprints.clear();
            sequenceCounter.set(0);
            sessionStart.set(System.currentTimeMillis());
            pendingFillByLocator.clear();
            pendingFillTimestamp.clear();

            // ── Browser creation ─────────────────────────────────────────────
            recPW = Playwright.create();

            recBrowser = recPW.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(hl)
                    .setChannel("chrome"));

            recContext = recBrowser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1440, 900)
                    .setBypassCSP(true)
                    .setIgnoreHTTPSErrors(true));

            recContext.exposeBinding("__mcpCapture", (src, bArgs) -> {
                if (bArgs.length > 0 && bArgs[0] instanceof String raw) ingestEvent(raw, "binding");
                return null;
            });
            recContext.addInitScript(CAPTURE_SCRIPT);
            recPage         = recContext.newPage();
            sessionEnvelope = buildSessionEnvelope(url, hl);

            // ── Frame listeners ──────────────────────────────────────────────
            recPage.onFrameNavigated(frame -> {
                try { drainFrame(frame); }           catch (Exception ignored) {}
                try {
                    frame.evaluate(CAPTURE_SCRIPT);
                    if (DEBUG) log(Level.FINE, "CAPTURE_SCRIPT re-injected into frame: " + frame.url());
                } catch (Exception ignored) {}
            });

            recPage.onLoad(pg -> {
                try {
                    String currentUrl = pg.url();
                    if (currentUrl == null || currentUrl.isBlank() || currentUrl.equals("about:blank")) return;
                    ObjectNode navEvent = (ObjectNode) MAPPER.readTree(
                            MAPPER.writeValueAsString(buildNavigateEvent(currentUrl, 0)));
                    navEvent.put("captureLayer", "onLoad-listener");
                    ingestEvent(MAPPER.writeValueAsString(navEvent), "onLoad");
                    if (DEBUG) log(Level.FINE, "onLoad NAVIGATE emitted: " + currentUrl);
                } catch (Exception ignored) {}
            });

            // ── Navigate + Lightning stabilization ───────────────────────────
            long t0 = System.currentTimeMillis();
            recPage.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(SALESFORCE_PAGE_TIMEOUT));
            waitForSalesforceLightning(recPage);
            recordedEvents.add(buildNavigateEvent(url, System.currentTimeMillis() - t0));
            safeEval(CAPTURE_SCRIPT, recPage);

            // Polling starts only after browser is fully ready.
            startPolling();

            log(Level.INFO, "Recording started: " + sessionName);
            return ok("✅ Recording started — \"" + sessionName + "\"\n" +
                    "URL: " + url + "\n" +
                    "SmartWait: ACTIVE  Salesforce Lightning: COMPATIBLE\n" +
                    "Retry: " + RETRY_MAX + "x  Timeout: " + TIMEOUT_MS + "ms\n" +
                    "Call stop_recording() when done.");

        } catch (Exception e) {
            // Best-effort rollback on partial initialization — closeRecBrowser()
            // is safe against partially-null fields (each guard is independent).
            log(Level.SEVERE, "start_recording failed — rolling back: " + e.getMessage());
            closeRecBrowser();
            return err("start_recording failed: " + e.getMessage());

        } finally {
            RECORDING_LOCK.unlock();
        }
    }

    // [TASK-4] handleStopRecording serialized through RECORDING_LOCK to prevent
    // a stop arriving mid-initialization from corrupting a partially built browser.
    private static McpSchema.CallToolResult handleStopRecording(Map<String, Object> args) {
        if (!RECORDING_LOCK.tryLock()) {
            return err("stop_recording rejected — start_recording is still initializing. " +
                    "Wait for it to complete before stopping.");
        }
        try {
            drainQueue(recPage);
            if (recPage != null) {
                for (Frame frame : recPage.frames()) {
                    drainFrame(frame);
                }
            }
            stopPolling();
            drainQueue(recPage);

            long endMs = System.currentTimeMillis();
            List<IntentAnalyzer.Intent> intents = IntentAnalyzer.analyze(recordedEvents);

            ObjectNode recording = MAPPER.createObjectNode();
            recording.put("schemaVersion",   "7.1.0");
            recording.put("sessionName",     sessionName);
            recording.put("status",          "COMPLETED");
            recording.put("totalEvents",     recordedEvents.size());
            recording.put("totalIntents",    intents.size());
            recording.put("totalDurationMs", endMs - sessionStart.get());
            recording.put("endedAt",         Instant.now().toString());
            if (sessionEnvelope != null) recording.set("session", sessionEnvelope);
            recording.set("codeGenerationHints", buildCodeGenHints());

            ArrayNode evArr = MAPPER.createArrayNode();
            recordedEvents.forEach(evArr::add);
            recording.set("events", evArr);

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
        } finally {
            RECORDING_LOCK.unlock();
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

    private static McpSchema.CallToolResult runSequentialPlayback(JsonNode events, boolean hl) {
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
            JsonNode events, boolean hl, int threadCount) throws Exception {
        List<List<JsonNode>> chunks = splitChunks(events, threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            final int idx = i;
            final List<JsonNode> chunk = chunks.get(i);
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
        if (!pool.awaitTermination(10, TimeUnit.MINUTES)) {
            pool.shutdownNow();
            log(Level.WARNING, "Parallel playback: pool forced shutdown after 10-minute timeout");
        }

        StringBuilder sb = new StringBuilder("Parallel playback — " + threadCount + " threads\n\n");
        for (int i = 0; i < futures.size(); i++) {
            try {
                sb.append("── Thread ").append(i).append(" ──\n")
                        .append(futures.get(i).isDone() ? futures.get(i).get() : "⚠ timed out").append("\n");
            } catch (Exception e) {
                sb.append("── Thread ").append(i).append(" ──\n❌ ").append(e.getMessage()).append("\n");
            }
        }
        return ok(sb.toString());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CORE PLAYBACK LOOP
    // ═════════════════════════════════════════════════════════════════════════
    private static McpSchema.CallToolResult executePlayback(
            JsonNode events, Page pg, String label) {
        int passed = 0, failed = 0, skipped = 0;
        StringBuilder logBuf = new StringBuilder();
        long prevElapsed = 0;

        for (var event : events) {
            String at  = event.path("actionType").asText();
            int    seq = event.path("sequenceNo").asInt();

            if ("FOCUS".equals(at)) {
                logBuf.append("  ⏭ [").append(seq).append("] FOCUS skipped\n");
                skipped++;
                continue;
            }

            long elapsedMs = event.path("elapsedMs").asLong(0);
            long delay = Math.min(Math.max(elapsedMs - prevElapsed, 0), MAX_STEP_DELAY);
            if (delay > 50) sleep(delay);
            prevElapsed = elapsedMs;

            String selector = pickBestReplayLocator(event);
            String value    = event.path("inputValue").asText("");
            String key      = event.path("key").asText("");

            try {
                withRetry(() -> {
                    waitForStableState(pg, at, selector, event);
                    replayEvent(at, selector, value, key, event, pg);
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
                recoverPageState(pg, event);
            }
        }
        return ok("[" + label + "] ✅ " + passed + "  ❌ " + failed + "  ⏭ " + skipped + "\n\n" + logBuf);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SALESFORCE LIGHTNING WAIT
    // ═════════════════════════════════════════════════════════════════════════
    private static void waitForSalesforceLightning(Page pg) {
        try {
            pg.waitForSelector("one-app",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(LIGHTNING_TIMEOUT));
            if (DEBUG) log(Level.FINE, "SF Lightning: one-app found");
        } catch (Exception ignored) {
            return;
        }

        try {
            pg.waitForSelector("[data-aura-rendered-by]",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(LIGHTNING_TIMEOUT));
            if (DEBUG) log(Level.FINE, "SF Lightning: data-aura-rendered-by visible");
        } catch (Exception ignored) {}

        try {
            pg.waitForFunction(
                    "() => document.querySelectorAll('.slds-spinner').length === 0",
                    new Page.WaitForFunctionOptions().setTimeout(LIGHTNING_TIMEOUT));
            if (DEBUG) log(Level.FINE, "SF Lightning: all slds-spinners gone");
        } catch (Exception ignored) {}

        // LWC_MICROTASK_BUFFER_MS: env-overridable buffer for async render queue flush.
        try { pg.waitForTimeout(LWC_MICROTASK_BUFFER_MS); } catch (Exception ignored) {}
        if (DEBUG) log(Level.FINE, "SF Lightning: micro-task buffer complete");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SMART WAIT ENGINE
    // ═════════════════════════════════════════════════════════════════════════
    private static void waitForStableState(Page pg, String actionType,
                                           String selector, JsonNode event) {
        long domReadyTimeout = "NAVIGATE".equals(actionType) ? SALESFORCE_PAGE_TIMEOUT : 5_000;
        try {
            pg.waitForFunction("document.readyState === 'interactive' || document.readyState === 'complete'",
                    new Page.WaitForFunctionOptions().setTimeout(domReadyTimeout));
        } catch (Exception ignored) {}

        if ("NAVIGATE".equals(actionType)) {
            waitForSalesforceLightning(pg);
            return;
        }
        if ("WAIT_FOR_NETWORK_IDLE".equals(actionType)) return;

        if (selector != null && !selector.isBlank() && !"body".equals(selector)) {
            try {
                String cssForWait = toCssForWait(selector);
                switch (actionType) {
                    case "CLICK", "DOUBLE_CLICK", "RIGHT_CLICK", "HOVER" ->
                            pg.waitForSelector(cssForWait,
                                    new Page.WaitForSelectorOptions()
                                            .setState(WaitForSelectorState.VISIBLE)
                                            .setTimeout(SALESFORCE_ELEMENT_TIMEOUT));
                    case "FILL", "TYPE", "CLEAR" -> {
                        pg.waitForSelector(cssForWait,
                                new Page.WaitForSelectorOptions()
                                        .setState(WaitForSelectorState.VISIBLE)
                                        .setTimeout(SALESFORCE_ELEMENT_TIMEOUT));
                        pg.waitForFunction(
                                "sel => { const el = document.querySelector(sel); " +
                                        "return el && !el.disabled; }",
                                cssForWait,
                                new Page.WaitForFunctionOptions().setTimeout(SALESFORCE_ELEMENT_TIMEOUT));
                        try {
                            Locator locator = resolveLocator(selector, pg);
                            locator.first().waitFor(
                                    new Locator.WaitForOptions()
                                            .setTimeout(SALESFORCE_ELEMENT_TIMEOUT));
                        } catch (Exception ignored) {}
                    }
                    case "SELECT_OPTION" ->
                            pg.waitForSelector(cssForWait,
                                    new Page.WaitForSelectorOptions()
                                            .setState(WaitForSelectorState.VISIBLE)
                                            .setTimeout(SALESFORCE_ELEMENT_TIMEOUT));
                    case "CHECK", "UNCHECK" ->
                            pg.waitForSelector(cssForWait,
                                    new Page.WaitForSelectorOptions()
                                            .setState(WaitForSelectorState.VISIBLE)
                                            .setTimeout(SALESFORCE_ELEMENT_TIMEOUT));
                    case "UPLOAD_FILE" ->
                            pg.waitForSelector(cssForWait,
                                    new Page.WaitForSelectorOptions()
                                            .setState(WaitForSelectorState.ATTACHED)
                                            .setTimeout(SALESFORCE_ELEMENT_TIMEOUT));
                    default -> {}
                }
            } catch (Exception ignored) {}
        }

        try {
            pg.waitForFunction(
                    "() => !document.querySelector(':scope :not(script):not(style)') || true",
                    new Page.WaitForFunctionOptions().setTimeout(2000));
        } catch (Exception ignored) {}
    }

    private static void postActionWait(Page pg, String actionType) {
        try {
            switch (actionType) {
                case "CLICK", "DOUBLE_CLICK", "FORM_SUBMIT" -> {
                    try {
                        pg.waitForLoadState(LoadState.DOMCONTENTLOADED,
                                new Page.WaitForLoadStateOptions().setTimeout(5000));
                    } catch (Exception ignored) {}
                    waitForSalesforceLightning(pg);
                }
                case "FILL", "TYPE" -> {
                    try { pg.waitForTimeout(200); } catch (Exception ignored) {}
                }
                case "NAVIGATE" -> {
                    pg.waitForLoadState(LoadState.DOMCONTENTLOADED,
                            new Page.WaitForLoadStateOptions().setTimeout(SALESFORCE_PAGE_TIMEOUT));
                    waitForSalesforceLightning(pg);
                }
                default -> {}
            }
        } catch (Exception ignored) {}
    }

    private static void recoverPageState(Page pg, JsonNode event) {
        try {
            log(Level.INFO, "Recovery: validating page state after failure...");
            pg.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(10_000));
            waitForSalesforceLightning(pg);
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

    private static String toCssForWait(String sel) {
        if (sel == null || sel.isBlank()) return "body";
        sel = sel.replaceAll("::nth=\\d+$", "").trim();
        if (sel.startsWith("role:"))        return "[role]";
        if (sel.startsWith("text:"))        return "body";
        if (sel.startsWith("placeholder:")) return "[placeholder=\"" + sel.substring(12) + "\"]";
        if (sel.startsWith("testid:"))      return "[data-testid=\"" + sel.substring(7) + "\"]";
        if (sel.startsWith("xpath:"))       return "body";
        if (sel.startsWith("label:"))       return "body";
        return sel;
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

            List<ObjectNode> evList = new ArrayList<>();
            for (var e : events) if (e instanceof ObjectNode o) evList.add(o);
            List<IntentAnalyzer.Intent> intents = IntentAnalyzer.analyze(evList);

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
                    "**Note:** If this Steps class already exists, add only the NEW @When/@Then methods.\n" +
                    "```java\n" + gen.stepDefinitions() + "\n```\n\n" +

                    "## 3. Page Object\n" +
                    "**Path:** `src/test/java/com/qa/pages/" + toPascal(featureName) + "Page.java`\n" +
                    "**Note:** If this Page Object already exists, add only the NEW locator constants and methods.\n" +
                    "```java\n" + gen.pageObject() + "\n```\n\n" +

                    "## 4. ScenarioContext\n" +
                    "**Path:** `src/test/java/com/qa/context/ScenarioContext.java`\n" +
                    "**Note:** Generate once — shared across all step classes. Do not regenerate.\n" +
                    "```java\n" + BddCodeGenerator.scenarioContext() + "\n```\n");
        } catch (Exception e) {
            return err("generate_playwright_bdd failed: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ATOMIC ACTION
    // ═════════════════════════════════════════════════════════════════════════

    // [TASK-1] Tracks whether this call opened the browser so closeTLBrowser()
    // is only called when we own the instance, never when a caller-owned browser
    // (e.g. from runParallelPlayback) is already present.
    private static McpSchema.CallToolResult handleAtomicAction(Map<String, Object> args) {
        boolean opened = false;
        try {
            if (TL_PAGE.get() == null || TL_PAGE.get().isClosed()) {
                openTLPage(false);
                opened = true;
            }
            Page pg         = TL_PAGE.get();
            String action   = strArg(args, "action");
            String selector = strArgOpt(args, "selector", "");
            String value    = strArgOpt(args, "value", "");
            long   tms      = longArgOpt(args, "timeoutMs", TIMEOUT_MS);

            waitForStableState(pg, action.toUpperCase(), selector, MAPPER.createObjectNode());
            String result = executeAtomicAction(pg, action, selector, value, tms);
            return ok(result != null ? result : "Done: " + action + " on '" + selector + "'");
        } catch (Exception e) {
            return err("execute_atomic_action failed: " + e.getMessage());
        } finally {
            if (opened) closeTLBrowser();
        }
    }

    private static String executeAtomicAction(Page pg, String action,
                                              String selector, String value,
                                              long timeoutMs) throws Exception {
        return switch (action.toLowerCase()) {
            case "navigate" -> {
                pg.navigate(value.isBlank() ? selector : value,
                        new Page.NavigateOptions()
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(SALESFORCE_PAGE_TIMEOUT));
                waitForSalesforceLightning(pg);
                yield null;
            }
            case "wait_for_navigation" -> {
                pg.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(SALESFORCE_PAGE_TIMEOUT));
                yield "Navigation complete";
            }
            case "wait_for_network_idle" -> {
                waitForSalesforceLightning(pg);
                yield "Lightning idle (network-idle equivalent)";
            }
            case "wait_for_url" -> {
                pg.waitForURL(value, new Page.WaitForURLOptions().setTimeout(timeoutMs));
                yield "URL matched: " + value;
            }
            case "wait_for_selector", "wait_for_element" -> {
                pg.waitForSelector(selector,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(SALESFORCE_ELEMENT_TIMEOUT));
                yield "Visible: " + selector;
            }
            case "wait_for_hidden" -> {
                pg.waitForSelector(selector,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.HIDDEN)
                                .setTimeout(SALESFORCE_ELEMENT_TIMEOUT));
                yield "Hidden: " + selector;
            }
            case "click" -> {
                withRetry(() -> resolveLocator(selector, pg).click(), "click", selector);
                yield null;
            }
            case "double_click", "dblclick" -> {
                withRetry(() -> resolveLocator(selector, pg).dblclick(), "dblclick", selector);
                yield null;
            }
            case "right_click" -> {
                withRetry(() -> resolveLocator(selector, pg).click(
                                new Locator.ClickOptions().setButton(com.microsoft.playwright.options.MouseButton.RIGHT)),
                        "right_click", selector);
                yield null;
            }
            // [TASK-6] fill: scroll into view + click-to-focus + fill().
            // fill() assigns the value directly — correct for React/LWC synthetic events.
            case "fill" -> {
                withRetry(() -> {
                    resolveLocator(selector, pg).first().scrollIntoViewIfNeeded();
                    resolveLocator(selector, pg).first().click();
                    resolveLocator(selector, pg).first().fill(value);
                }, "fill", selector);
                yield null;
            }
            // [TASK-6] type: pressSequentially() replaces deprecated type().
            // Fires one keydown/keypress/keyup cycle per character — required for
            // autocomplete fields, masked inputs, and React onChange-per-keystroke.
            case "type" -> {
                withRetry(() ->
                                resolveLocator(selector, pg).pressSequentially(value,
                                        new Locator.PressSequentiallyOptions()
                                                .setDelay((double) TYPE_KEY_DELAY_MS)),
                        "type", selector);
                yield null;
            }
            case "clear" -> {
                withRetry(() -> resolveLocator(selector, pg).clear(), "clear", selector);
                yield null;
            }
            case "check" -> {
                withRetry(() -> resolveLocator(selector, pg).check(), "check", selector);
                yield null;
            }
            case "uncheck" -> {
                withRetry(() -> resolveLocator(selector, pg).uncheck(), "uncheck", selector);
                yield null;
            }
            case "select_option" -> {
                withRetry(() -> resolveLocator(selector, pg).selectOption(value), "select", selector);
                yield null;
            }
            case "hover" -> {
                withRetry(() -> resolveLocator(selector, pg).hover(), "hover", selector);
                yield null;
            }
            case "focus" -> {
                withRetry(() -> resolveLocator(selector, pg).focus(), "focus", selector);
                yield null;
            }
            case "press" -> {
                withRetry(() -> resolveLocator(selector, pg).press(value), "press", selector);
                yield null;
            }
            case "upload_file" -> {
                resolveLocator(selector, pg).setInputFiles(Paths.get(value));
                yield "Uploaded: " + value;
            }
            case "upload_files" -> {
                Path[] ps = Arrays.stream(value.split(",")).map(String::trim).map(Paths::get).toArray(Path[]::new);
                resolveLocator(selector, pg).setInputFiles(ps);
                yield "Uploaded: " + value;
            }
            case "scroll" -> {
                String[] p = value.split(",");
                pg.mouse().wheel(parseDouble(p.length > 0 ? p[0] : "0"), parseDouble(p.length > 1 ? p[1] : "0"));
                yield null;
            }
            case "scroll_to_element" -> {
                resolveLocator(selector, pg).scrollIntoViewIfNeeded();
                yield "Scrolled to: " + selector;
            }
            case "keyboard_press" -> { pg.keyboard().press(value); yield null; }
            case "keyboard_type"  -> { pg.keyboard().type(value);  yield null; }
            case "wait" -> {
                pg.waitForTimeout(value.isBlank() ? 1000 : parseDouble(value));
                yield null;
            }
            case "screenshot" -> {
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
        // NOTE: sleep() here blocks the calling thread. This is safe for playback
        // because each playback thread owns its own Playwright instance via ThreadLocal.
        // Do NOT call withRetry() from inside a Playwright event callback (e.g. onLoad,
        // onFrameNavigated) — that would block the Playwright event dispatcher.
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
                                    String key, JsonNode event, Page pg) throws Exception {
        switch (action.toUpperCase()) {
            case "NAVIGATE" -> {
                pg.navigate(value.isBlank() ? rawSel : value,
                        new Page.NavigateOptions()
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(SALESFORCE_PAGE_TIMEOUT));
                waitForSalesforceLightning(pg);
            }
            case "SCROLL"                -> pg.mouse().wheel(
                    event.path("scrollX").asDouble(0), event.path("scrollY").asDouble(0));
            case "FORM_SUBMIT"           -> resolveLocator(rawSel, pg).evaluate("f => f.submit()");
            case "WAIT"                  -> pg.waitForTimeout(value.isBlank() ? 500 : parseDouble(value));
            case "WAIT_FOR_SELECTOR"     -> pg.waitForSelector(rawSel,
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(SALESFORCE_ELEMENT_TIMEOUT));
            case "WAIT_FOR_URL"          -> pg.waitForURL(value,
                    new Page.WaitForURLOptions().setTimeout(SALESFORCE_ELEMENT_TIMEOUT));
            case "WAIT_FOR_NETWORK_IDLE" -> waitForSalesforceLightning(pg);
            case "UPLOAD_FILE"           -> resolveLocator(rawSel, pg).setInputFiles(Paths.get(value));
            default -> {
                Locator loc = resolveLocator(rawSel, pg);
                try {
                    if (loc.count() > 1) {
                        int nth = 0;
                        if (rawSel.contains("::nth="))
                            try { nth = Integer.parseInt(rawSel.replaceAll(".*::nth=(\\d+)", "$1")); } catch (Exception ig) {}
                        loc = loc.nth(nth);
                    }
                } catch (Exception ig) {}

                switch (action.toUpperCase()) {
                    case "CLICK" -> {
                        loc.first().scrollIntoViewIfNeeded();
                        loc.first().click(new Locator.ClickOptions().setTimeout(SALESFORCE_ELEMENT_TIMEOUT));
                    }
                    case "DOUBLE_CLICK"  -> loc.dblclick();
                    case "RIGHT_CLICK"   -> loc.click(new Locator.ClickOptions()
                            .setButton(com.microsoft.playwright.options.MouseButton.RIGHT));
                    // [TASK-6] FILL: scroll + click-to-focus + fill().
                    // fill() sets the value directly — correct for LWC/Aura shadow-DOM inputs.
                    case "FILL" -> {
                        loc.first().scrollIntoViewIfNeeded();
                        loc.first().click();
                        loc.first().fill(value);
                    }
                    // [TASK-6] TYPE: pressSequentially() replaces deprecated type().
                    // Per-character key events required for autocomplete and React onChange.
                    case "TYPE" -> loc.pressSequentially(value,
                            new Locator.PressSequentiallyOptions()
                                    .setDelay((double) TYPE_KEY_DELAY_MS));
                    case "CLEAR"         -> loc.clear();
                    case "PRESS_KEY"     -> {
                        String k = key.isBlank() ? value : key;
                        if (!rawSel.isBlank() && !"body".equals(rawSel)) loc.press(k);
                        else pg.keyboard().press(k);
                    }
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
    static String pickBestReplayLocator(JsonNode event) {
        var loc = event.path("locator");
        if (loc.isMissingNode() || loc.isNull())
            return event.path("inputValue").asText(event.path("pageUrl").asText("body"));
        String strategy = loc.path("strategy").asText("css");
        int nth = loc.path("nthIndex").asInt(0);
        int cnt = loc.path("matchCount").asInt(1);
        return switch (strategy) {
            case "href", "href-partial" -> loc.path("selector").asText(loc.path("cssSelector").asText());
            case "css-id"      -> loc.path("cssSelector").asText(loc.path("primary").asText());
            case "testId"      -> loc.path("selector").asText();
            case "xpath"       -> loc.path("selector").asText();
            case "placeholder" -> loc.path("selector").asText();
            case "role"        -> nth > 0 ? loc.path("selector").asText() + "::nth=" + nth
                    : loc.path("selector").asText();
            default -> {
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
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .withZone(ZoneId.systemDefault()).format(Instant.now());
            Path dir = FAILURE_DIR.resolve(ts + "-seq" + seq);
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
    //  CAPTURE SCRIPT
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
                    "console.log('[MCP Recorder v7.1] Active — Salesforce Lightning + SPA + Enterprise Ready.');\n" +
                    "})();";

    // ═════════════════════════════════════════════════════════════════════════
    //  EVENT INGESTION + POLLING
    // ═════════════════════════════════════════════════════════════════════════
    private static void ingestEvent(String raw, String source) {
        synchronized (INGEST_LOCK) {
            try {
                ObjectNode event = (ObjectNode) MAPPER.readTree(raw);
                String actionType = event.path("actionType").asText();

                JsonNode rawLoc = event.get("locator");
                if (rawLoc instanceof ObjectNode locNode) sanitizeLocatorNode(locNode);

                String fp = actionType
                        + "|" + event.path("timestamp").asText("").substring(0,
                        Math.min(19, event.path("timestamp").asText("x").length()))
                        + "|" + event.path("locator").path("primary").asText("nosel");
                if (!seenFingerprints.add(fp)) return;

                if ("FILL".equals(actionType)) {
                    String locatorKey = fillLocatorKey(event);
                    long   now        = System.currentTimeMillis();
                    Long   lastSeen   = pendingFillTimestamp.get(locatorKey);
                    boolean withinWindow = lastSeen != null && (now - lastSeen) <= FILL_DEBOUNCE_MS;

                    if (withinWindow) {
                        ObjectNode prev = pendingFillByLocator.get(locatorKey);
                        if (prev != null) {
                            int idx = recordedEvents.indexOf(prev);
                            if (idx >= 0) {
                                event.put("sequenceNo",   prev.path("sequenceNo").asInt());
                                event.put("elapsedMs",    System.currentTimeMillis() - sessionStart.get());
                                event.put("sessionName",  sessionName);
                                event.put("captureLayer", source);
                                String code = suggestCode(event);
                                if (code != null) event.put("suggestedPlaywrightCode", code);
                                recordedEvents.set(idx, event);
                                pendingFillByLocator.put(locatorKey, event);
                                pendingFillTimestamp.put(locatorKey, now);
                                if (DEBUG) logEvent(Level.FINE, "FILL-DEBOUNCED", actionType,
                                        event.path("locator").path("primary").asText("-"),
                                        event.path("sequenceNo").asInt());
                                return;
                            }
                        }
                    }
                    pendingFillByLocator.put(locatorKey, event);
                    pendingFillTimestamp.put(locatorKey, now);
                } else {
                    long now = System.currentTimeMillis();
                    pendingFillTimestamp.entrySet().removeIf(e -> (now - e.getValue()) > FILL_DEBOUNCE_MS);
                    pendingFillByLocator.keySet().retainAll(pendingFillTimestamp.keySet());
                }

                event.put("sequenceNo",   sequenceCounter.incrementAndGet());
                event.put("elapsedMs",    System.currentTimeMillis() - sessionStart.get());
                event.put("sessionName",  sessionName);
                event.put("captureLayer", source);
                String code = suggestCode(event);
                if (code != null) event.put("suggestedPlaywrightCode", code);
                recordedEvents.add(event);

                if ("FILL".equals(actionType)) {
                    pendingFillByLocator.put(fillLocatorKey(event), event);
                }

                if (DEBUG) logEvent(Level.FINE, "CAPTURED", actionType,
                        event.path("locator").path("primary").asText("-"),
                        event.path("sequenceNo").asInt());

            } catch (Exception e) {
                log(Level.WARNING, "ingestEvent [" + source + "]: " + e.getMessage());
            }
        }
    }

    private static String fillLocatorKey(ObjectNode event) {
        JsonNode loc = event.path("locator");
        String css = loc.path("cssSelector").asText("");
        if (!css.isBlank()) return "FILL|css|" + css;
        String primary = loc.path("primary").asText("");
        if (!primary.isBlank()) return "FILL|primary|" + primary;
        String strategy = loc.path("strategy").asText("css");
        String selector = loc.path("selector").asText(loc.path("playwrightLocator").asText("unknown"));
        return "FILL|" + strategy + "|" + selector;
    }

    private static void sanitizeLocatorNode(ObjectNode locNode) {
        if (locNode == null || locNode.isNull() || locNode.isMissingNode()) return;
        String strategy = locNode.path("strategy").asText("");
        if (!"href".equals(strategy)) return;
        String href = locNode.path("href").asText("");
        if (href.isBlank()) return;

        int qMark    = href.indexOf('?');
        int fragment = href.indexOf('#');
        int pathEnd  = (qMark >= 0 && (fragment < 0 || qMark < fragment)) ? qMark
                : (fragment >= 0) ? fragment : href.length();
        String path  = href.substring(0, pathEnd);
        String query = (qMark >= 0) ? href.substring(qMark) : "";

        boolean hasDynamicSegment = path.matches(".*/[^/]+/\\d+(/.*)?");
        boolean hasDynamicQuery   = !query.isEmpty() && query.matches(".*[?&][^=]+=\\d+.*");
        if (!hasDynamicSegment && !hasDynamicQuery) return;

        String stablePrefix;
        if (hasDynamicSegment) {
            stablePrefix = path.replaceFirst("/\\d+(/.*)?$", "/");
            if (!stablePrefix.endsWith("/")) stablePrefix += "/";
        } else {
            stablePrefix = path;
        }

        if (stablePrefix.isBlank() || "/".equals(stablePrefix)) {
            String css = locNode.path("cssSelector").asText("");
            if (!css.isBlank()) {
                locNode.put("strategy",          "css");
                locNode.put("primary",           css);
                locNode.put("selector",          css);
                locNode.put("playwrightLocator", "page.locator(\"" + esc(css) + "\")");
                locNode.putNull("href");
            }
            return;
        }

        String partialSelector = "a[href*='" + stablePrefix + "']";
        locNode.put("href",              stablePrefix);
        locNode.put("strategy",          "href-partial");
        locNode.put("selector",          partialSelector);
        locNode.put("primary",           partialSelector);
        locNode.put("playwrightLocator", "page.locator(\"a[href*='" + stablePrefix + "']\")");
        if (DEBUG) log(Level.FINE, "sanitizeLocatorNode: [" + href + "] → [" + partialSelector + "]");
    }

    // [TASK-5] startPolling() — AtomicReference replaces volatile Thread + volatile boolean.
    // compareAndSet(null, candidate) is the double-start guard: exactly one thread wins the slot.
    private static void startPolling() {
        Thread candidate = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(500);
                    drainQueue(recPage);
                    if (recPage != null) {
                        for (Frame frame : recPage.frames()) {
                            drainFrame(frame);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log(Level.WARNING, "Poll: " + e.getMessage());
                }
            }
            // Self-clear: if stopPolling() already nulled the reference, this is a no-op.
            POLLING_THREAD.compareAndSet(Thread.currentThread(), null);
        }, "mcp-queue-drainer");
        candidate.setDaemon(true);

        if (POLLING_THREAD.compareAndSet(null, candidate)) {
            candidate.start();
            if (DEBUG) log(Level.FINE, "Polling started.");
        } else {
            if (DEBUG) log(Level.FINE, "startPolling() skipped — drainer already running.");
        }
    }

    // [TASK-5] stopPolling() — getAndSet(null) atomically retrieves and clears in one operation.
    // The local variable 'running' is either null (safe early return) or a live Thread.
    // No NPE window is possible.
    private static void stopPolling() {
        Thread running = POLLING_THREAD.getAndSet(null);
        if (running == null) return;
        running.interrupt();
        if (DEBUG) log(Level.FINE, "Polling stopped.");
    }

    @SuppressWarnings("unchecked")
    private static void drainQueue(Page pg) {
        if (pg == null) return;
        try {
            Object r = pg.evaluate("()=>{const q=window.__mcpQueue__;if(!q||!q.length)return[];return q.splice(0);}");
            if (r instanceof List<?> l) for (Object item : l) if (item instanceof String s) ingestEvent(s, "queue-poll");
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void drainFrame(Frame frame) {
        if (frame == null) return;
        try {
            Object r = frame.evaluate("()=>{const q=window.__mcpQueue__;if(!q||!q.length)return[];return q.splice(0);}");
            if (r instanceof List<?> l) for (Object item : l) if (item instanceof String s) ingestEvent(s, "frame-drain");
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
            case "FILL"          -> loc + ".first().scrollIntoViewIfNeeded();\n"
                    + loc + ".first().click();\n"
                    + loc + ".first().fill(\"" + val + "\");";
            case "TYPE"          -> loc + ".pressSequentially(\"" + val + "\", "
                    + "new Locator.PressSequentiallyOptions().setDelay(" + TYPE_KEY_DELAY_MS + ".0));";
            case "PRESS_KEY"     -> loc + ".press(\"" + key + "\");";
            case "SELECT_OPTION" -> loc + ".selectOption(\"" + val + "\");";
            case "CHECK"         -> loc + ".check();";
            case "UNCHECK"       -> loc + ".uncheck();";
            case "HOVER"         -> loc + ".hover();";
            case "FOCUS"         -> "// FOCUS (informational): " + loc;
            case "SCROLL"        -> "page.mouse().wheel(" + sx + ", " + sy + ");";
            case "NAVIGATE"      -> "page.navigate(\"" + esc(e.path("inputValue").asText("")) + "\", "
                    + "new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));\n"
                    + "// waitForSalesforceLightning(page); // uncomment for Salesforce orgs";
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
        n.put("suggestedPlaywrightCode",
                "page.navigate(\"" + esc(url) + "\", new Page.NavigateOptions()" +
                        ".setWaitUntil(WaitUntilState.DOMCONTENTLOADED));");
        n.putNull("locator"); n.putNull("elementSnapshot");
        return n;
    }

    private static ObjectNode buildSessionEnvelope(String url, boolean hl) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("sessionId",      UUID.randomUUID().toString());
        e.put("sessionName",    sessionName);
        e.put("startUrl",       url);
        e.put("startedAt",      Instant.now().toString());
        e.put("browserType",    "chrome");
        e.put("headless",       hl);
        e.put("retryMax",       RETRY_MAX);
        e.put("timeoutMs",      TIMEOUT_MS);
        e.put("sfPageTimeoutMs",   SALESFORCE_PAGE_TIMEOUT);
        e.put("sfElementTimeoutMs", SALESFORCE_ELEMENT_TIMEOUT);
        e.put("lightningTimeoutMs", LIGHTNING_TIMEOUT);
        e.put("maxStepDelayMs", MAX_STEP_DELAY);
        e.put("typeKeyDelayMs", TYPE_KEY_DELAY_MS);
        e.put("locatorEngine",  "v7.1-salesforce-safe");
        e.put("operatingSystem", System.getProperty("os.name"));
        e.put("javaVersion",     System.getProperty("java.version"));
        ObjectNode vp = MAPPER.createObjectNode(); vp.put("width", 1440); vp.put("height", 900);
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
        Browser br = pw.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(hl)
                .setChannel("chrome"));
        BrowserContext ctx = br.newContext(new Browser.NewContextOptions()
                .setViewportSize(1440, 900)
                .setBypassCSP(true)
                .setIgnoreHTTPSErrors(true));
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
    private static List<List<JsonNode>> splitChunks(JsonNode events, int n) {
        List<JsonNode> all = new ArrayList<>();
        for (var e : events) all.add(e);
        List<List<JsonNode>> chunks = new ArrayList<>();
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
        LOG.log(l, String.format("[MCP] %s seq=%-3d action=%-18s loc=%s",
                status, seq, action, truncate(sel, 60)));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ═════════════════════════════════════════════════════════════════════════
    private static void safeEval(String s, Page pg) {
        try { if (pg != null) pg.evaluate(s); }
        catch (Exception e) { log(Level.WARNING, "Re-inject: " + e.getMessage()); }
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
        if (v == null) throw new IllegalArgumentException("Missing: " + k);
        return v.toString();
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
    private static int    intEnv(String k, int d)   { try { return Integer.parseInt(System.getenv(k));  } catch (Exception e) { return d; } }
    private static long   longEnv(String k, long d) { try { return Long.parseLong(System.getenv(k));    } catch (Exception e) { return d; } }
    private static double parseDouble(String s)     { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }
    private static McpSchema.CallToolResult ok(String t) {
        return McpSchema.CallToolResult.builder().addTextContent(t).build();
    }
    private static McpSchema.CallToolResult err(String t) {
        return McpSchema.CallToolResult.builder().addTextContent("ERROR: " + t).isError(true).build();
    }
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n) + "…";
    }
    private static String toPascal(String s) {
        if (s == null || s.isBlank()) return "Recorded";
        String[] p = s.replaceAll("[^a-zA-Z0-9 ]", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : p) if (!w.isBlank())
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        return sb.toString();
    }
}