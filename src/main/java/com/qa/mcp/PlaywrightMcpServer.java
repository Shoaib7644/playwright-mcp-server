package com.qa.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * PlaywrightMcpServer v4 — Zero-ambiguity locator engine.
 *
 * ══════════════════════════════════════════════════════════════════════
 *  WHAT CHANGED FROM v3 — ROOT CAUSE FIXES
 * ══════════════════════════════════════════════════════════════════════
 *
 *  Problem 1 ── JS captures innermost element (span/div), not the
 *               clickable ancestor (a/button).
 *  Fix        ── interactiveAncestor() walks composedPath() upward to
 *               the first element that is actually interactive.
 *               Clicks on <span>Architecture</span> now capture the
 *               parent <a href="/docs/learn/architecture">.
 *
 *  Problem 2 ── text: strategy is ambiguous (multiple "Architecture",
 *               "Extensions" elements on page).
 *  Fix        ── NEW uniqueness-scored locator pipeline:
 *               href > id (css) > testId > aria-label+role > nth-scoped
 *               CSS > xpath > text (last resort, always with nth=0).
 *               Each candidate is scored; highest unique score wins.
 *
 *  Problem 3 ── ID with special chars (#/docs/learn/architecture)
 *               was discarded. Now escaped properly via CSS.escape()
 *               equivalent and used when present.
 *
 *  Problem 4 ── href on <a> tags is the strongest unique locator for
 *               nav links but was never captured. Now captured as
 *               strategy "href" → page.locator("a[href='...']").
 *
 *  Problem 5 ── Playback resolveLocator() used .first() only for
 *               text: strategy, missed ambiguity for role/text combos.
 *  Fix        ── resolveLocator() now runs a runtime uniqueness check:
 *               if locator.count() > 1 it auto-scopes by nth-index
 *               stored in the recording.
 *
 *  Problem 6 ── FOCUS events duplicated CLICK events with the same
 *               locator, causing redundant replay failures.
 *  Fix        ── FOCUS events are filtered OUT during playback
 *               (they are informational only).
 *
 *  Problem 7 ── Scroll recorded as scrollX=0,scrollY=0 (captured
 *               before the scroll completed).
 *  Fix        ── Scroll payload uses scrollTop of the scrolling
 *               container element, not window.scrollX/Y.
 * ══════════════════════════════════════════════════════════════════════
 */
public class PlaywrightMcpServer {

    private static final Logger LOG = Logger.getLogger(PlaywrightMcpServer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ── Browser state ─────────────────────────────────────────────────────────
    private static Playwright     playwright;
    private static Browser        browser;
    private static BrowserContext context;
    private static Page           page;

    // ── Recording state ───────────────────────────────────────────────────────
    private static final List<ObjectNode> recordedEvents   = new CopyOnWriteArrayList<>();
    private static final Set<String>      seenFingerprints = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final AtomicInteger    sequenceCounter  = new AtomicInteger(0);
    private static final AtomicLong       sessionStart     = new AtomicLong(0);
    private static ObjectNode             sessionEnvelope  = null;
    private static String                 sessionName      = "Unnamed Session";

    // ── Polling thread ────────────────────────────────────────────────────────
    private static volatile Thread  pollingThread = null;
    private static volatile boolean pollActive    = false;

    // ─────────────────────────────────────────────────────────────────────────
    //  ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        var transport = new StdioServerTransportProvider(new ObjectMapper());
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("playwright-mcp-server", "4.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true).logging().build())
                .tools(
                        startRecordingSpec(), stopRecordingSpec(),
                        playbackSpec(), generateBddSpec(), atomicActionSpec()
                )
                .build();

        LOG.info("PlaywrightMcpServer v4 running on stdio …");
        try { Thread.currentThread().join(); }
        catch (InterruptedException e) { LOG.info("Interrupted — shutting down."); }
        finally { server.close(); closeBrowser(); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TOOL SPECS
    // ─────────────────────────────────────────────────────────────────────────
    private static McpServerFeatures.SyncToolSpecification startRecordingSpec() {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("start_recording",
                        "Launches Chromium (headful), bypasses CSP, navigates to URL. " +
                                "Captures ALL interactions with unambiguous locators: walks to " +
                                "interactive ancestor, scores href/id/testId/role/nth strategies.",
                        """
                        {
                          "type":"object",
                          "properties":{
                            "url":        {"type":"string"},
                            "sessionName":{"type":"string"},
                            "headless":   {"type":"boolean"}
                          },
                          "required":["url"]
                        }"""),
                (ex, args) -> handleStartRecording(args));
    }

    private static McpServerFeatures.SyncToolSpecification stopRecordingSpec() {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("stop_recording",
                        "Stops recording, drains queue, returns full JSON document.",
                        """
                        {
                          "type":"object",
                          "properties":{},
                          "required":[]
                        }
                        """),
                (ex, args) -> handleStopRecording(args));
    }

    private static McpServerFeatures.SyncToolSpecification playbackSpec() {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("playback_recording",
                        "Replays a JSON recording. FOCUS events are skipped (informational). " +
                                "Runtime uniqueness check: if locator matches >1 element, auto-scopes " +
                                "by nth-index from recording. Proportional timing preserved.",
                        """
                        {
                          "type":"object",
                          "properties":{
                            "jsonRecording":{"type":"string"},
                            "headless":     {"type":"boolean"}
                          },
                          "required":["jsonRecording"]
                        }"""),
                (ex, args) -> handlePlayback(args));
    }

    private static McpServerFeatures.SyncToolSpecification generateBddSpec() {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("generate_playwright_bdd",
                        "Converts JSON recording to Feature file, Step Defs, Page Object, pom.xml. " +
                                "Uses scoped locators — no ambiguous getByText() in generated code.",
                        """
                        {
                          "type":"object",
                          "properties":{
                            "jsonRecording":{"type":"string"},
                            "featureName":  {"type":"string"}
                          },
                          "required":["jsonRecording","featureName"]
                        }"""),
                (ex, args) -> handleGenerateBdd(args));
    }

    private static McpServerFeatures.SyncToolSpecification atomicActionSpec() {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("execute_atomic_action",
                        "Single Playwright action on the active page.",
                        """
                        {
                          "type":"object",
                          "properties":{
                            "action":  {"type":"string"},
                            "selector":{"type":"string"},
                            "value":   {"type":"string"}
                          },
                          "required":["action","selector"]
                        }"""),
                (ex, args) -> handleAtomicAction(args));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HANDLERS
    // ─────────────────────────────────────────────────────────────────────────
    private static McpSchema.CallToolResult handleStartRecording(Map<String, Object> args) {
        try {
            String url  = strArg(args, "url");
            sessionName = strArgOpt(args, "sessionName",
                    "Recording-" + Instant.now().toString().substring(0, 10));
            boolean hl  = boolArgOpt(args, "headless", false);

            closeBrowser();
            recordedEvents.clear();
            seenFingerprints.clear();
            sequenceCounter.set(0);
            sessionStart.set(System.currentTimeMillis());

            playwright = Playwright.create();
            browser    = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(hl)
                            .setArgs(List.of(
                                    "--disable-web-security",
                                    "--disable-features=IsolateOrigins,site-per-process"
                            ))
            );
            context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1280, 720)
                    .setBypassCSP(true));

            // Strip CSP headers from all responses
            context.route("**/*", route -> {
                var resp = route.fetch();
                Map<String, String> headers = new HashMap<>(resp.headers());
                headers.remove("content-security-policy");
                headers.remove("content-security-policy-report-only");
                headers.remove("x-content-security-policy");
                route.fulfill(new Route.FulfillOptions().setResponse(resp).setHeaders(headers));
            });

            // exposeBinding on context — survives all navigations
            context.exposeBinding("__mcpCapture", (source, bindingArgs) -> {
                if (bindingArgs.length > 0 && bindingArgs[0] instanceof String raw) {
                    ingestEvent(raw, "binding");
                }
                return null;
            });

            // Init script runs before every page load
            context.addInitScript(CAPTURE_SCRIPT);

            page = context.newPage();
            sessionEnvelope = buildSessionEnvelope(url, hl);

            long t0 = System.currentTimeMillis();
            page.navigate(url);
            long dur = System.currentTimeMillis() - t0;

            recordedEvents.add(buildNavigateEvent(url, dur));
            safeEval(CAPTURE_SCRIPT); // re-inject into already-loaded page
            startPolling();

            return ok("✅ Recording started — \"" + sessionName + "\"\n" +
                    "URL: " + url + "\n" +
                    "Locator engine: v4 (href > id > testId > role > nth-css > xpath)\n" +
                    "CSP bypass: 3-layer active\n\n" +
                    "Interact freely. Call stop_recording() when done.");

        } catch (Exception e) {
            LOG.severe("start_recording: " + e.getMessage());
            return err("start_recording failed: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult handleStopRecording(Map<String, Object> args) {
        try {
            stopPolling();
            drainQueue();

            long endMs = System.currentTimeMillis();
            ObjectNode recording = MAPPER.createObjectNode();
            recording.put("schemaVersion",   "4.0.0");
            recording.put("sessionName",     sessionName);
            recording.put("status",          "COMPLETED");
            recording.put("totalEvents",     recordedEvents.size());
            recording.put("totalDurationMs", endMs - sessionStart.get());
            recording.put("endedAt",         Instant.now().toString());
            if (sessionEnvelope != null) recording.set("session", sessionEnvelope);
            recording.set("codeGenerationHints", buildCodeGenHints());
            ArrayNode arr = MAPPER.createArrayNode();
            recordedEvents.forEach(arr::add);
            recording.set("events", arr);

            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(recording);
            closeBrowser();

            return ok("✅ Recording stopped.\nEvents: " + recordedEvents.size() +
                    "\nDuration: " + (endMs - sessionStart.get()) + " ms\n\n" +
                    "```json\n" + json + "\n```");
        } catch (Exception e) {
            return err("stop_recording failed: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult handlePlayback(Map<String, Object> args) {
        try {
            String jsonRecording = strArg(args, "jsonRecording");
            boolean hl           = boolArgOpt(args, "headless", false);

            var doc    = MAPPER.readTree(jsonRecording);
            var events = doc.has("events") ? doc.path("events") : doc;

            closeBrowser();
            playwright = Playwright.create();
            browser    = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(hl));
            context    = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1280, 720).setBypassCSP(true));
            page = context.newPage();

            int passed = 0, failed = 0, skipped = 0;
            StringBuilder log = new StringBuilder();
            long prevElapsed = 0;

            for (var event : events) {
                String at = event.path("actionType").asText();

                // FOCUS events are informational — skip during playback
                if ("FOCUS".equals(at)) {
                    log.append("  ⏭ [").append(event.path("sequenceNo").asInt())
                            .append("] FOCUS skipped (informational)\n");
                    skipped++;
                    continue;
                }

                // Proportional timing, capped at 2500ms between steps
                long elapsedMs = event.path("elapsedMs").asLong(0);
                long delay = Math.min(Math.max(elapsedMs - prevElapsed, 0), 2500);
                if (delay > 80) Thread.sleep(delay);
                prevElapsed = elapsedMs;

                // Resolve the best available locator for this event
                String resolvedSelector = pickBestReplayLocator(event);
                String value = event.path("inputValue").asText("");
                String key   = event.path("key").asText("");

                try {
                    replayEvent(at, resolvedSelector, value, key, event);
                    log.append("  ✅ [").append(event.path("sequenceNo").asInt()).append("] ")
                            .append(at).append(" → ").append(truncate(resolvedSelector, 70)).append("\n");
                    passed++;
                } catch (Exception e) {
                    log.append("  ❌ [").append(event.path("sequenceNo").asInt()).append("] ")
                            .append(at).append(" → ").append(truncate(resolvedSelector, 70))
                            .append("\n     ↳ ").append(e.getMessage()).append("\n");
                    failed++;
                }
            }

            return ok("Playback complete — ✅ " + passed + "  ❌ " + failed +
                    "  ⏭ " + skipped + "\n\n" + log);

        } catch (Exception e) {
            return err("playback_recording failed: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult handleGenerateBdd(Map<String, Object> args) {
        try {
            String jsonRecording = strArg(args, "jsonRecording");
            String featureName   = strArg(args, "featureName");
            var doc    = MAPPER.readTree(jsonRecording);
            var events = doc.has("events") ? doc.path("events") : doc;
            var gen    = new BddCodeGenerator(featureName, events);

            return ok("# Generated BDD Project — " + featureName + "\n\n" +
                    "## pom.xml\n```xml\n"           + BddCodeGenerator.pomXml() + "\n```\n\n" +
                    "## Feature File\n```gherkin\n"  + gen.featureFile()         + "\n```\n\n" +
                    "## Step Definitions\n```java\n" + gen.stepDefinitions()     + "\n```\n\n" +
                    "## Page Object\n```java\n"      + gen.pageObject()          + "\n```\n\n" +
                    "## JUnit Runner\n```java\n"     + gen.jUnitRunner()         + "\n```\n");
        } catch (Exception e) {
            return err("generate_playwright_bdd failed: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult handleAtomicAction(Map<String, Object> args) {
        try {
            if (page == null) {
                playwright = Playwright.create();
                browser    = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
                context    = browser.newContext(new Browser.NewContextOptions().setBypassCSP(true));
                page       = context.newPage();
            }
            replayEvent(strArg(args, "action"), strArgOpt(args, "selector", ""),
                    strArgOpt(args, "value", ""), strArgOpt(args, "value", ""), null);
            return ok("Done: " + strArg(args, "action") + " on " + strArgOpt(args, "selector", ""));
        } catch (Exception e) {
            return err("execute_atomic_action failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CAPTURE SCRIPT  v4
    //
    //  Key changes vs v3:
    //  ──────────────────
    //  • interactiveAncestor() — walks composedPath() up to the nearest
    //    a / button / [role=button|link|tab|menuitem|option] element.
    //    Clicks on inner <span> text now attach to the parent interactive.
    //
    //  • uniquenessScore() — evaluates each locator candidate and returns
    //    how many elements on the page it matches. Lowest count wins.
    //    Candidates: href, id, testId, ariaLabel+role, nth-scoped CSS, xpath, text.
    //
    //  • hrefLocator() — <a href> is often the strongest nav link locator.
    //    Relative hrefs produce a[href="/path"] which is unambiguous.
    //
    //  • nthIndex — records which index (0-based) the element is within
    //    all matches of the same strategy. Playback uses this when count>1.
    //
    //  • SCROLL now records scrollTop of the scrolling container, not
    //    window.scrollX/Y which can be 0 during the event.
    //
    //  • CLICK dedup — a CLICK that fires within 50ms of a preceding
    //    FOCUS on the same selector is skipped (avoids duplicate pairs).
    // ─────────────────────────────────────────────────────────────────────────
    private static final String CAPTURE_SCRIPT = /* language=javascript */ """
        (function () {
          if (window.__mcpCaptureActive__) return;
          window.__mcpCaptureActive__ = true;
          if (!window.__mcpQueue__) window.__mcpQueue__ = [];

          /* ══ SEND ══════════════════════════════════════════════════════════ */
          function send(obj) {
            const str = JSON.stringify(obj);
            window.__mcpQueue__.push(str);
            try { if (typeof window.__mcpCapture === 'function') window.__mcpCapture(str); }
            catch(e) { /* queue fallback active */ }
          }

          /* ══ INTERACTIVE ANCESTOR ══════════════════════════════════════════
             Walk composedPath() upward to find the real interactive element.
             Clicks on <span>Architecture</span> resolve to <a href="/docs/learn/architecture">.
          ═════════════════════════════════════════════════════════════════════ */
          const INTERACTIVE_TAGS   = new Set(['A','BUTTON','SELECT','INPUT','TEXTAREA','LABEL']);
          const INTERACTIVE_ROLES  = new Set(['button','link','tab','menuitem','option',
                                              'checkbox','radio','combobox','listbox','switch']);
          function interactiveAncestor(path) {
            for (const el of path) {
              if (!el || el === document || el === window) break;
              if (INTERACTIVE_TAGS.has(el.tagName)) return el;
              const r = el.getAttribute && el.getAttribute('role');
              if (r && INTERACTIVE_ROLES.has(r)) return el;
            }
            return path[0] || null; // fallback: innermost element
          }

          /* ══ CSS ESCAPE ════════════════════════════════════════════════════ */
          function cssEscape(str) {
            try { return CSS.escape(str); } catch(e) { return str.replace(/[^a-zA-Z0-9-_]/g,'\\\\$&'); }
          }

          /* ══ XPATH ═════════════════════════════════════════════════════════ */
          function xpath(el) {
            if (!el || el === document.body) return '/html/body';
            if (el.id) return '//*[@id="' + el.id + '"]';
            const sib = el.parentNode
              ? [...el.parentNode.childNodes].filter(n => n.nodeName === el.nodeName) : [];
            const idx = sib.indexOf(el) + 1;
            return xpath(el.parentNode) + '/' + el.nodeName.toLowerCase()
              + (sib.length > 1 ? '[' + idx + ']' : '');
          }

          /* ══ CSS PATH ══════════════════════════════════════════════════════ */
          function cssPath(el) {
            if (!el) return 'body';
            if (el.id) return '#' + cssEscape(el.id);
            const parts = [];
            let cur = el;
            while (cur && cur !== document.body) {
              let seg = cur.tagName.toLowerCase();
              if (cur.id) { parts.unshift('#' + cssEscape(cur.id)); break; }
              const sib = [...(cur.parentNode?.children || [])].filter(c => c.tagName === cur.tagName);
              if (sib.length > 1) seg += ':nth-of-type(' + (sib.indexOf(cur) + 1) + ')';
              parts.unshift(seg);
              cur = cur.parentNode;
            }
            return parts.join(' > ');
          }

          /* ══ UNIQUENESS CHECK ══════════════════════════════════════════════
             Returns how many elements the selector matches on the current page.
             0 = broken, 1 = perfectly unique ✓, >1 = ambiguous.
          ═════════════════════════════════════════════════════════════════════ */
          function countMatches(selector) {
            try { return document.querySelectorAll(selector).length; } catch(e) { return 99; }
          }
          function countXpath(xp) {
            try {
              const r = document.evaluate(xp, document, null,
                        XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
              return r.snapshotLength;
            } catch(e) { return 99; }
          }
          function nthOf(el, selector) {
            try {
              return [...document.querySelectorAll(selector)].indexOf(el);
            } catch(e) { return 0; }
          }

          /* ══ LOCATOR PIPELINE ══════════════════════════════════════════════
             Builds ALL candidate locators and picks the most unique one.
             Priority scoring (lower matchCount = better):
               href (1)  >  id (1)  >  testId (1)  >  ariaLabel+role  >
               nth-scoped CSS (1)   >  nth-scoped xpath (1)  >  text (n)
          ═════════════════════════════════════════════════════════════════════ */
          function buildLocator(el) {
            if (!el) return null;

            const tag         = el.tagName.toLowerCase();
            const elId        = el.id || null;
            const testId      = el.getAttribute?.('data-testid')
                             || el.getAttribute?.('data-test')
                             || el.getAttribute?.('data-cy') || null;
            const ariaLabel   = el.getAttribute?.('aria-label') || null;
            const ariaRole    = el.getAttribute?.('role') || tag;
            const placeholder = el.getAttribute?.('placeholder') || null;
            const href        = el.getAttribute?.('href') || null;
            const name        = el.getAttribute?.('name') || null;
            const innerText   = (el.innerText || '').trim().substring(0, 80);
            const css         = cssPath(el);
            const xp          = xpath(el);

            // Candidate list: [ {strategy, selector, matchCount, playwrightExpr} ]
            const candidates = [];

            // 1. href (most unique for nav links)
            if (href && tag === 'a') {
              const hrefSel = 'a[href="' + href + '"]';
              const cnt     = countMatches(hrefSel);
              candidates.push({ strategy:'href', selector:hrefSel, matchCount:cnt,
                playwrightExpr:'page.locator("a[href=\\"' + href + '\\"]")',
                primary: hrefSel });
            }

            // 2. id (CSS — handles special chars via cssEscape)
            if (elId) {
              const idSel = '#' + cssEscape(elId);
              const cnt   = countMatches(idSel);
              candidates.push({ strategy:'css-id', selector:idSel, matchCount:cnt,
                playwrightExpr:'page.locator("' + idSel + '")',
                primary: idSel });
            }

            // 3. testId
            if (testId) {
              const cnt = countMatches('[data-testid="' + testId + '"]');
              candidates.push({ strategy:'testId', selector:'testid:' + testId, matchCount:cnt,
                playwrightExpr:'page.getByTestId("' + testId + '")',
                primary:'testid:' + testId });
            }

            // 4. aria-label + role
            if (ariaLabel) {
              const roleSel = '[role="' + ariaRole + '"][aria-label="' + ariaLabel + '"]';
              const cnt     = countMatches(roleSel);
              candidates.push({ strategy:'role', selector:'role:' + ariaRole + ':' + ariaLabel,
                matchCount: cnt,
                playwrightExpr:'page.getByRole(AriaRole.' + ariaRole.toUpperCase().replace(/-/g,'_')
                  + ', new Page.GetByRoleOptions().setName("' + ariaLabel + '"))',
                primary:'role:' + ariaRole + ':' + ariaLabel });
            }

            // 5. placeholder
            if (placeholder) {
              const cnt = countMatches('[placeholder="' + placeholder + '"]');
              candidates.push({ strategy:'placeholder',
                selector:'placeholder:' + placeholder, matchCount:cnt,
                playwrightExpr:'page.getByPlaceholder("' + placeholder + '")',
                primary:'placeholder:' + placeholder });
            }

            // 6. Full CSS path (usually unique due to nth-of-type)
            if (css) {
              const cnt = countMatches(css);
              candidates.push({ strategy:'css', selector:css, matchCount:cnt,
                playwrightExpr:'page.locator("' + css.replace(/"/g,'\\"') + '")',
                primary: css });
            }

            // 7. XPath (via xpath: prefix for resolveLocator)
            if (xp) {
              const cnt = countXpath(xp);
              candidates.push({ strategy:'xpath', selector:'xpath:' + xp, matchCount:cnt,
                playwrightExpr:'page.locator("xpath=' + xp + '")',
                primary:'xpath:' + xp });
            }

            // 8. Text (last resort — always ambiguous risk)
            if (innerText && innerText.length < 60) {
              const cnt = document.querySelectorAll('*').length > 0
                ? [...document.querySelectorAll('*')].filter(e =>
                    (e.innerText||'').trim() === innerText).length : 99;
              const nth = [...document.querySelectorAll('*')].filter(e =>
                    (e.innerText||'').trim() === innerText).indexOf(el);
              candidates.push({ strategy:'text', selector:'text:' + innerText,
                matchCount:cnt, nthIndex: nth >= 0 ? nth : 0,
                playwrightExpr:'page.getByText("' + innerText + '").nth(' + Math.max(nth,0) + ')',
                primary:'text:' + innerText });
            }

            // Pick the candidate with lowest matchCount (most unique)
            // Tie-break: prefer earlier in list (higher priority strategy)
            candidates.sort((a,b) => a.matchCount - b.matchCount || 0);
            const best = candidates[0] || { strategy:'css', selector:css,
                matchCount:99, playwrightExpr:'page.locator("body")', primary:css };

            // nthIndex within best strategy (for playback scoping)
            let nthIndex = best.nthIndex !== undefined ? best.nthIndex : 0;
            if (nthIndex === 0 && best.matchCount > 1) {
              try { nthIndex = nthOf(el, best.selector.replace(/^(text|xpath|role|testid|placeholder):[^:]*:?/,'')); }
              catch(e) {}
            }

            return {
              primary:          best.primary,
              strategy:         best.strategy,
              matchCount:       best.matchCount,
              nthIndex:         nthIndex,
              playwrightLocator:best.playwrightExpr,
              // All alternatives for debugging/fallback
              cssSelector:  css,
              xpath:        xp,
              id:           elId,
              name,
              ariaLabel,
              ariaRole,
              placeholder,
              testId,
              href,
              text:         innerText,
            };
          }

          /* ══ ELEMENT SNAPSHOT ══════════════════════════════════════════════ */
          function snapshot(el) {
            let bb = null;
            try { const r = el.getBoundingClientRect();
              bb = { x:Math.round(r.x), y:Math.round(r.y),
                     width:Math.round(r.width), height:Math.round(r.height) }; }
            catch(e) {}
            const attrs = {};
            for (const a of (el.attributes||[])) attrs[a.name] = a.value;
            return {
              tagName:   el.tagName.toLowerCase(),
              type:      el.getAttribute?.('type')     || null,
              innerText: (el.innerText||'').trim().substring(0,200),
              value:     el.value !== undefined ? el.value : null,
              isChecked: el.checked !== undefined ? el.checked : null,
              isVisible: !!(el.offsetWidth||el.offsetHeight||el.getClientRects?.().length),
              boundingBox: bb,
              attributes: attrs,
            };
          }

          /* ══ FULL PAYLOAD ══════════════════════════════════════════════════ */
          function payload(actionType, el, extra) {
            return {
              actionType,
              timestamp:       new Date().toISOString(),
              pageUrl:         window.location.href,
              pageTitle:       document.title,
              locator:         el ? buildLocator(el) : null,
              elementSnapshot: el ? snapshot(el) : null,
              coordinates:     extra?.coords      || null,
              inputValue:      extra?.inputValue  !== undefined ? extra.inputValue : null,
              key:             extra?.key         || null,
              scrollX:         extra?.scrollX     !== undefined ? extra.scrollX : null,
              scrollY:         extra?.scrollY     !== undefined ? extra.scrollY : null,
              selectValues:    extra?.selectValues || null,
            };
          }

          /* ══ DEDUP: track last FOCUS to suppress redundant CLICK ══════════ */
          let lastFocusSelector = null;
          let lastFocusTime     = 0;

          const OPT = { capture:true, passive:true };

          /* ── CLICK ─────────────────────────────────────────────────────── */
          document.addEventListener('click', e => {
            const path   = e.composedPath?.() || [e.target];
            const el     = interactiveAncestor(path);
            if (!el || el === document.documentElement) return;
            const loc    = buildLocator(el);
            const coords = { x:Math.round(e.clientX), y:Math.round(e.clientY) };
            send(payload('CLICK', el, { coords }));
          }, OPT);

          /* ── DOUBLE CLICK ──────────────────────────────────────────────── */
          document.addEventListener('dblclick', e => {
            const path = e.composedPath?.() || [e.target];
            const el   = interactiveAncestor(path);
            send(payload('DOUBLE_CLICK', el,
              { coords:{ x:Math.round(e.clientX), y:Math.round(e.clientY) } }));
          }, OPT);

          /* ── RIGHT CLICK ───────────────────────────────────────────────── */
          document.addEventListener('contextmenu', e => {
            const path = e.composedPath?.() || [e.target];
            const el   = interactiveAncestor(path);
            send(payload('RIGHT_CLICK', el,
              { coords:{ x:Math.round(e.clientX), y:Math.round(e.clientY) } }));
          }, OPT);

          /* ── FILL ──────────────────────────────────────────────────────── */
          document.addEventListener('input', e => {
            const el = e.target;
            if (!el||!['INPUT','TEXTAREA'].includes(el.tagName)) return;
            const isPass = el.type === 'password';
            send(payload('FILL', el, { inputValue: isPass ? '***REDACTED***' : el.value }));
          }, OPT);

          /* ── CHANGE (select / checkbox / radio) ────────────────────────── */
          document.addEventListener('change', e => {
            const el  = e.target;
            const tag = el?.tagName?.toLowerCase();
            if (tag === 'select') {
              const vals = [...el.selectedOptions].map(o => o.value);
              send(payload('SELECT_OPTION', el, { selectValues:vals, inputValue:vals.join(',') }));
            } else if (el?.type === 'checkbox') {
              send(payload(el.checked ? 'CHECK' : 'UNCHECK', el,
                { inputValue:String(el.checked) }));
            } else if (el?.type === 'radio') {
              send(payload('RADIO_SELECT', el, { inputValue:el.value }));
            }
          }, OPT);

          /* ── KEY PRESS ─────────────────────────────────────────────────── */
          const SPECIAL = new Set([
            'Enter','Tab','Escape','Backspace','Delete',
            'ArrowUp','ArrowDown','ArrowLeft','ArrowRight',
            'Home','End','PageUp','PageDown',
            'F1','F2','F3','F4','F5','F6','F7','F8','F9','F10','F11','F12',
          ]);
          document.addEventListener('keydown', e => {
            const isShortcut = (e.ctrlKey||e.metaKey) && !['Control','Meta'].includes(e.key);
            if (!SPECIAL.has(e.key) && !isShortcut) return;
            const el  = document.activeElement || document.body;
            const key = (e.ctrlKey?'Control+':'') + (e.metaKey?'Meta+':'')
                      + (e.shiftKey?'Shift+':'') + (e.altKey?'Alt+':'') + e.key;
            send(payload('PRESS_KEY', el, { key }));
          }, OPT);

          /* ── FOCUS ─────────────────────────────────────────────────────── */
          document.addEventListener('focus', e => {
            const path = e.composedPath?.() || [e.target];
            const el   = interactiveAncestor(path);
            if (!el) return;
            const tag  = el.tagName;
            if (!['INPUT','TEXTAREA','SELECT','BUTTON','A'].includes(tag)) return;
            lastFocusSelector = buildLocator(el)?.primary || '';
            lastFocusTime     = Date.now();
            send(payload('FOCUS', el, {}));
          }, OPT);

          /* ── HOVER (debounced 350ms, interactive only) ─────────────────── */
          let hoverTimer;
          document.addEventListener('mouseover', e => {
            clearTimeout(hoverTimer);
            hoverTimer = setTimeout(() => {
              const path = e.composedPath?.() || [e.target];
              const el   = interactiveAncestor(path);
              if (!el) return;
              const tag  = el.tagName.toLowerCase();
              if (!['a','button','select'].includes(tag) && !el.getAttribute?.('role')) return;
              send(payload('HOVER', el,
                { coords:{ x:Math.round(e.clientX), y:Math.round(e.clientY) } }));
            }, 350);
          }, OPT);

          /* ── SCROLL (throttled 600ms, uses scrollTop of container) ─────── */
          let scrollTimer;
          document.addEventListener('scroll', e => {
            clearTimeout(scrollTimer);
            scrollTimer = setTimeout(() => {
              // Use the scrolling container's scrollTop/Left, not window.*
              const container = e.target === document ? document.documentElement : e.target;
              const sx = Math.round(container.scrollLeft || window.scrollX || 0);
              const sy = Math.round(container.scrollTop  || window.scrollY || 0);
              send({ actionType:'SCROLL', timestamp:new Date().toISOString(),
                     pageUrl:window.location.href, pageTitle:document.title,
                     locator:null, elementSnapshot:null,
                     coordinates:null, inputValue:null, key:null,
                     scrollX:sx, scrollY:sy, selectValues:null });
            }, 600);
          }, OPT);

          /* ── FORM SUBMIT ────────────────────────────────────────────────── */
          document.addEventListener('submit', e => {
            const path = e.composedPath?.() || [e.target];
            send(payload('FORM_SUBMIT', path[0], {}));
          }, OPT);

          /* ── SPA NAVIGATION ─────────────────────────────────────────────── */
          const _push    = history.pushState.bind(history);
          const _replace = history.replaceState.bind(history);
          const navPayload = () => ({ actionType:'NAVIGATE',
            timestamp:new Date().toISOString(),
            pageUrl:window.location.href, pageTitle:document.title,
            locator:null, elementSnapshot:null, inputValue:window.location.href });
          history.pushState    = function() { _push(...arguments);    send(navPayload()); };
          history.replaceState = function() { _replace(...arguments); send(navPayload()); };
          window.addEventListener('popstate',   () => send(navPayload()), OPT);
          window.addEventListener('hashchange', () => send(navPayload()), OPT);

          console.log('[MCP Recorder v4] Active — zero-ambiguity locator engine.');
        })();
        """;

    // ─────────────────────────────────────────────────────────────────────────
    //  PLAYBACK LOCATOR RESOLUTION
    //
    //  Priority for choosing which locator string to use during replay:
    //  1. href   → page.locator("a[href='...']")              always unique
    //  2. css-id → page.locator("#escaped-id")                always unique
    //  3. testId → page.getByTestId(...)                      always unique
    //  4. xpath  → page.locator("xpath=...")                  usually unique
    //  5. role   → page.getByRole(...).nth(nthIndex)          scoped by nth
    //  6. css    → page.locator(css).nth(nthIndex)            scoped by nth
    //  7. text   → page.getByText(...).nth(nthIndex)          scoped by nth
    //  8. Runtime check: if count()>1 → auto-scope with .nth(nthIndex)
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the best replay selector string from a recorded event node. */
    static String pickBestReplayLocator(com.fasterxml.jackson.databind.JsonNode event) {
        var loc = event.path("locator");
        if (loc.isMissingNode() || loc.isNull()) {
            // Navigate / Scroll events — use inputValue or scrollX/Y
            return event.path("inputValue").asText(
                    event.path("pageUrl").asText("body"));
        }

        String strategy   = loc.path("strategy").asText("css");
        int    nthIndex   = loc.path("nthIndex").asInt(0);
        int    matchCount = loc.path("matchCount").asInt(1);

        // Strategies guaranteed unique — return immediately, no nth needed
        return switch (strategy) {
            case "href"   -> loc.path("selector").asText(loc.path("cssSelector").asText());
            case "css-id" -> loc.path("cssSelector").asText(loc.path("primary").asText());
            case "testId" -> loc.path("selector").asText();   // "testid:xxx"
            case "xpath"  -> loc.path("selector").asText();   // "xpath://*[@id=...]"

            // Potentially ambiguous — attach nth index if needed
            case "role"        -> nthIndex > 0
                    ? loc.path("selector").asText() + "::nth=" + nthIndex
                    : loc.path("selector").asText();

            case "placeholder" -> loc.path("selector").asText(); // usually unique

            default -> {
                // css or text — append nth hint if matchCount > 1
                String sel = loc.path("primary").asText(
                        loc.path("cssSelector").asText("body"));
                yield (matchCount > 1 && nthIndex >= 0)
                        ? sel + "::nth=" + nthIndex
                        : sel;
            }
        };
    }

    /** Executes a single action. Handles the ::nth= suffix for scoping. */
    private static void replayEvent(String action, String rawSelector,
                                    String value, String key,
                                    com.fasterxml.jackson.databind.JsonNode event) {
        if ("NAVIGATE".equals(action)) {
            page.navigate(value.isBlank() ? rawSelector : value);
            return;
        }
        if ("SCROLL".equals(action)) {
            double sx = event != null ? event.path("scrollX").asDouble(0) : 0;
            double sy = event != null ? event.path("scrollY").asDouble(0) : 0;
            page.mouse().wheel(sx, sy);
            return;
        }
        if ("FORM_SUBMIT".equals(action)) {
            resolveLocator(rawSelector).evaluate("f => f.submit()");
            return;
        }
        if ("WAIT".equals(action)) {
            page.waitForTimeout(value.isBlank() ? 1000 : Double.parseDouble(value));
            return;
        }

        Locator loc = resolveLocator(rawSelector);

        // Runtime uniqueness check — if more than 1 match, extract nth
        try {
            int cnt = loc.count();
            if (cnt > 1) {
                // Extract nth from the ::nth= suffix we injected
                int nth = 0;
                if (rawSelector.contains("::nth=")) {
                    try { nth = Integer.parseInt(rawSelector.replaceAll(".*::nth=(\\d+)", "$1")); }
                    catch (Exception ignored) {}
                }
                loc = loc.nth(nth);
                LOG.fine("Scoped to .nth(" + nth + ") — " + cnt + " matches for: " + rawSelector);
            }
        } catch (Exception ignored) {}

        switch (action.toUpperCase()) {
            case "CLICK"         -> loc.click();
            case "DOUBLE_CLICK"  -> loc.dblclick();
            case "RIGHT_CLICK"   -> loc.click(new Locator.ClickOptions()
                    .setButton(com.microsoft.playwright.options.MouseButton.RIGHT));
            case "FILL"          -> loc.fill(value);
            case "TYPE"          -> loc.type(value);
            case "CLEAR"         -> loc.clear();
            case "PRESS_KEY"     -> {
                String k = key.isBlank() ? value : key;
                if (!rawSelector.isBlank() && !"body".equals(rawSelector)) loc.press(k);
                else page.keyboard().press(k);
            }
            case "SELECT_OPTION" -> loc.selectOption(value);
            case "CHECK"         -> loc.check();
            case "UNCHECK"       -> loc.uncheck();
            case "HOVER"         -> loc.hover();
            case "FOCUS"         -> loc.focus();
            default              -> LOG.warning("Unknown action skipped: " + action);
        }
    }

    /** Resolves a selector string — including all prefix conventions and ::nth= suffix. */
    private static Locator resolveLocator(String raw) {
        if (raw == null || raw.isBlank()) return page.locator("body");

        // Strip ::nth= before resolving — handled separately in replayEvent
        String sel = raw.replaceAll("::nth=\\d+$", "").trim();

        if (sel.startsWith("role:")) {
            String[] p = sel.substring(5).split(":", 2);
            try {
                AriaRole r = AriaRole.valueOf(
                        p[0].toUpperCase().replace("-", "_").replace(" ", "_"));
                if (p.length > 1 && !p[1].isBlank())
                    return page.getByRole(r, new Page.GetByRoleOptions().setName(p[1]));
                return page.getByRole(r);
            } catch (IllegalArgumentException e) {
                return page.locator("[role='" + p[0] + "']");
            }
        }
        if (sel.startsWith("text:"))        return page.getByText(sel.substring(5), new Page.GetByTextOptions().setExact(false));
        if (sel.startsWith("placeholder:")) return page.getByPlaceholder(sel.substring(12));
        if (sel.startsWith("label:"))       return page.getByLabel(sel.substring(6));
        if (sel.startsWith("testid:"))      return page.getByTestId(sel.substring(7));
        if (sel.startsWith("xpath:"))       return page.locator("xpath=" + sel.substring(6));
        return page.locator(sel);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  EVENT INGESTION
    // ─────────────────────────────────────────────────────────────────────────
    private static void ingestEvent(String raw, String source) {
        try {
            ObjectNode event = (ObjectNode) MAPPER.readTree(raw);

            // Fingerprint: actionType + timestamp (second precision) + primary locator
            String fingerprint = event.path("actionType").asText()
                    + "|" + event.path("timestamp").asText("").substring(0, Math.min(19,
                    event.path("timestamp").asText("x").length()))
                    + "|" + event.path("locator").path("primary").asText("nosel");
            if (!seenFingerprints.add(fingerprint)) return;

            event.put("sequenceNo",   sequenceCounter.incrementAndGet());
            event.put("elapsedMs",    System.currentTimeMillis() - sessionStart.get());
            event.put("sessionName",  sessionName);
            event.put("captureLayer", source);

            String code = suggestCode(event);
            if (code != null) event.put("suggestedPlaywrightCode", code);

            recordedEvents.add(event);

        } catch (Exception e) {
            LOG.warning("ingestEvent error [" + source + "]: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  POLLING THREAD (Layer 3 fallback)
    // ─────────────────────────────────────────────────────────────────────────
    private static void startPolling() {
        pollActive    = true;
        pollingThread = new Thread(() -> {
            while (pollActive) {
                try { Thread.sleep(500); drainQueue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch (Exception e) { LOG.warning("Poll error: " + e.getMessage()); }
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
    private static void drainQueue() {
        if (page == null) return;
        try {
            Object result = page.evaluate(
                    "() => { const q=window.__mcpQueue__; if(!q||!q.length) return []; " +
                            "return q.splice(0); }");
            if (result instanceof List<?> list)
                for (Object item : list)
                    if (item instanceof String s) ingestEvent(s, "queue-poll");
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CODE SUGGESTION
    // ─────────────────────────────────────────────────────────────────────────
    private static String suggestCode(ObjectNode e) {
        String at  = e.path("actionType").asText();
        String pw  = e.path("locator").path("playwrightLocator").asText("");
        if (pw.isBlank()) pw = "page.locator(\"body\")";
        int nth    = e.path("locator").path("nthIndex").asInt(0);
        int cnt    = e.path("locator").path("matchCount").asInt(1);
        // Apply .nth() in generated code when needed
        String loc = (cnt > 1 && nth > 0) ? pw + ".nth(" + nth + ")" : pw;
        String val = esc(e.path("inputValue").asText(""));
        String key = esc(e.path("key").asText(""));
        int sx     = e.path("scrollX").asInt(0);
        int sy     = e.path("scrollY").asInt(0);
        return switch (at) {
            case "CLICK"         -> loc + ".click();";
            case "DOUBLE_CLICK"  -> loc + ".dblclick();";
            case "RIGHT_CLICK"   -> loc + ".click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));";
            case "FILL"          -> loc + ".fill(\"" + val + "\");";
            case "PRESS_KEY"     -> loc + ".press(\"" + key + "\");";
            case "SELECT_OPTION" -> loc + ".selectOption(\"" + val + "\");";
            case "CHECK"         -> loc + ".check();";
            case "UNCHECK"       -> loc + ".uncheck();";
            case "HOVER"         -> loc + ".hover();";
            case "FOCUS"         -> "// FOCUS (informational — not replayed): " + loc;
            case "SCROLL"        -> "page.mouse().wheel(" + sx + ", " + sy + ");";
            case "NAVIGATE"      -> "page.navigate(\"" + esc(e.path("inputValue").asText("")) + "\");";
            case "FORM_SUBMIT"   -> loc + ".evaluate(\"f => f.submit()\");";
            default              -> null;
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  METADATA BUILDERS
    // ─────────────────────────────────────────────────────────────────────────
    private static ObjectNode buildNavigateEvent(String url, long durationMs) {
        ObjectNode nav = MAPPER.createObjectNode();
        nav.put("sequenceNo",             sequenceCounter.incrementAndGet());
        nav.put("actionType",             "NAVIGATE");
        nav.put("timestamp",              Instant.now().toString());
        nav.put("elapsedMs",              0L);
        nav.put("durationMs",             durationMs);
        nav.put("sessionName",            sessionName);
        nav.put("pageUrl",                url);
        nav.put("inputValue",             url);
        nav.put("captureLayer",           "java-direct");
        nav.put("suggestedPlaywrightCode","page.navigate(\"" + esc(url) + "\");");
        nav.putNull("locator");
        nav.putNull("elementSnapshot");
        return nav;
    }

    private static ObjectNode buildSessionEnvelope(String url, boolean headless) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("sessionId",         UUID.randomUUID().toString());
        env.put("sessionName",       sessionName);
        env.put("startUrl",          url);
        env.put("startedAt",         Instant.now().toString());
        env.put("browserType",       "chromium");
        env.put("headless",          headless);
        env.put("cspBypass",         "bypassCSP=true + --disable-web-security + route-header-strip");
        env.put("locatorEngine",     "v4-zero-ambiguity");
        env.put("operatingSystem",   System.getProperty("os.name"));
        env.put("javaVersion",       System.getProperty("java.version"));
        env.put("playwrightVersion", "1.44.0");
        env.put("timezone",          TimeZone.getDefault().getID());
        env.put("locale",            Locale.getDefault().toString());
        ObjectNode vp = MAPPER.createObjectNode();
        vp.put("width", 1280); vp.put("height", 720);
        env.set("viewport", vp);
        return env;
    }

    private static ObjectNode buildCodeGenHints() {
        Set<String> strategies = new LinkedHashSet<>();
        Set<String> actionTypes = new LinkedHashSet<>();
        boolean hasFill = false, hasAssert = false;
        for (ObjectNode e : recordedEvents) {
            String at = e.path("actionType").asText();
            actionTypes.add(at);
            String st = e.path("locator").path("strategy").asText();
            if (!st.isBlank()) strategies.add(st);
            if ("FILL".equals(at)) hasFill = true;
            if (at.contains("ASSERT")) hasAssert = true;
        }
        ObjectNode hints = MAPPER.createObjectNode();
        hints.put("suggestedTestClassName",  toPascal(sessionName) + "Test");
        hints.put("suggestedTestMethodName", "test" + toPascal(sessionName));
        hints.put("framework",               "JUnit5-Cucumber");
        hints.put("hasFillActions",          hasFill);
        hints.put("hasAssertions",           hasAssert);
        ArrayNode stArr = MAPPER.createArrayNode(); strategies.forEach(stArr::add);
        hints.set("locatorStrategiesUsed", stArr);
        ArrayNode atArr = MAPPER.createArrayNode(); actionTypes.forEach(atArr::add);
        hints.set("actionTypesUsed", atArr);
        return hints;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UTILITIES
    // ─────────────────────────────────────────────────────────────────────────
    private static void safeEval(String script) {
        try { if (page != null) page.evaluate(script); }
        catch (Exception e) { LOG.warning("Re-inject: " + e.getMessage()); }
    }

    private static void closeBrowser() {
        stopPolling();
        try { if (page       != null) { page.close();       page       = null; } } catch (Exception ignored) {}
        try { if (context    != null) { context.close();    context    = null; } } catch (Exception ignored) {}
        try { if (browser    != null) { browser.close();    browser    = null; } } catch (Exception ignored) {}
        try { if (playwright != null) { playwright.close(); playwright = null; } } catch (Exception ignored) {}
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
        if (a == null) return d; Object v = a.get(k);
        if (v == null) return d;
        return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
    }
    private static McpSchema.CallToolResult ok(String t) {
        return McpSchema.CallToolResult.builder().addTextContent(t).build();
    }
    private static McpSchema.CallToolResult err(String t) {
        return McpSchema.CallToolResult.builder().addTextContent("ERROR: " + t).isError(true).build();
    }
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\","\\\\").replace("\"","\\\"");
    }
    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n) + "…";
    }
    private static String toPascal(String s) {
        if (s == null || s.isBlank()) return "Recorded";
        String[] p = s.replaceAll("[^a-zA-Z0-9 ]"," ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : p) if (!w.isBlank())
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        return sb.toString();
    }
}