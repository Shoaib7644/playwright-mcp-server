package com.qa.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * PlaywrightMcpServer — MCP Java SDK 0.10.0, stdio transport.
 *
 * SDK 0.10.0 handler signature (confirmed by compiler error):
 *   SyncToolSpecification BiFunction second arg = Map<String,Object>  ← raw args map
 *   NOT McpSchema.CallToolRequest — that is a later SDK version.
 *
 * So handlers are:  (McpSyncServerExchange exchange, Map<String,Object> args) -> CallToolResult
 * And args are read directly: args.get("key")  — no .arguments() call.
 */
public class PlaywrightMcpServer {

    private static final Logger LOG = Logger.getLogger(PlaywrightMcpServer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Playwright     playwright;
    private static Browser        browser;
    private static BrowserContext context;
    private static Page           page;

    private static final List<ObjectNode> recordedSteps = new CopyOnWriteArrayList<>();

    // ─────────────────────────────────────────────────────────────────────
    //  ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        var transport = new StdioServerTransportProvider(new ObjectMapper());

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("playwright-mcp-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .logging()
                        .build())
                .tools(
                        startRecordingSpec(),
                        stopRecordingSpec(),
                        playbackSpec(),
                        generateBddSpec(),
                        atomicActionSpec()
                )
                .build();

        LOG.info("PlaywrightMcpServer running on stdio …");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            LOG.info("Interrupted — shutting down.");
        } finally {
            server.close();
            closeBrowser();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TOOL SPECIFICATIONS
    //  SDK 0.10.0 confirmed pattern:
    //    new McpSchema.Tool(name, description, jsonSchemaString)
    //    new McpServerFeatures.SyncToolSpecification(tool, biFunction)
    //    biFunction: (McpSyncServerExchange, Map<String,Object>) -> CallToolResult
    //                 ^^^ second arg is the RAW ARGS MAP, not CallToolRequest
    // ─────────────────────────────────────────────────────────────────────

    private static McpServerFeatures.SyncToolSpecification startRecordingSpec() {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "start_recording",
                        "Launches Chromium, navigates to the given URL, and begins " +
                                "capturing interactions (clicks, fills, navigations) as JSON.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "url": {
                              "type": "string",
                              "description": "URL to navigate to at recording start."
                            }
                          },
                          "required": ["url"]
                        }"""),
                (exchange, args) -> handleStartRecording(args));
    }

    private static McpServerFeatures.SyncToolSpecification stopRecordingSpec() {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "stop_recording",
                        "Stops the recording session, closes the browser, and returns " +
                                "the captured JSON array of interaction steps.",
                        """
                        {
                          "type": "object",
                          "properties": {},
                          "required": []
                        }"""),
                (exchange, args) -> handleStopRecording(args));
    }

    private static McpServerFeatures.SyncToolSpecification playbackSpec() {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "playback_recording",
                        "Replays a JSON recording step-by-step using Playwright Java " +
                                "locators in a fresh browser session.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "jsonRecording": {
                              "type": "string",
                              "description": "JSON array returned by stop_recording."
                            },
                            "url": {
                              "type": "string",
                              "description": "Base URL to open before playback (optional)."
                            }
                          },
                          "required": ["jsonRecording"]
                        }"""),
                (exchange, args) -> handlePlayback(args));
    }

    private static McpServerFeatures.SyncToolSpecification generateBddSpec() {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "generate_playwright_bdd",
                        "Transforms a JSON recording into a complete Java/Maven/BDD project: " +
                                "Feature file, Step Definitions, Page Object, and pom.xml.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "jsonRecording": {
                              "type": "string",
                              "description": "JSON array returned by stop_recording."
                            },
                            "featureName": {
                              "type": "string",
                              "description": "Feature name, e.g. 'User Login'."
                            }
                          },
                          "required": ["jsonRecording", "featureName"]
                        }"""),
                (exchange, args) -> handleGenerateBdd(args));
    }

    private static McpServerFeatures.SyncToolSpecification atomicActionSpec() {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "execute_atomic_action",
                        "Executes a single Playwright action on the active page. " +
                                "Actions: click | fill | navigate | getByText | getByRole | " +
                                "getByPlaceholder | check | select_option | press | hover | wait.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "action": {
                              "type": "string",
                              "description": "Action name — see tool description."
                            },
                            "selector": {
                              "type": "string",
                              "description": "Selector, role, text, or placeholder value."
                            },
                            "value": {
                              "type": "string",
                              "description": "Value for fill / navigate actions."
                            }
                          },
                          "required": ["action", "selector"]
                        }"""),
                (exchange, args) -> handleAtomicAction(args));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HANDLERS  — receive Map<String,Object> directly (SDK 0.10.0)
    // ─────────────────────────────────────────────────────────────────────

    private static McpSchema.CallToolResult handleStartRecording(Map<String, Object> args) {
        try {
            String url = strArg(args, "url");
            closeBrowser();
            recordedSteps.clear();

            playwright = Playwright.create();
            browser    = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(false));
            context    = browser.newContext();
            page       = context.newPage();

            page.exposeFunction("__mcpCapture", (Object[] jsArgs) -> {
                try {
                    ObjectNode step = (ObjectNode) MAPPER.readTree((String) jsArgs[0]);
                    step.put("timestamp", Instant.now().toString());
                    recordedSteps.add(step);
                } catch (Exception e) {
                    LOG.warning("Capture error: " + e.getMessage());
                }
                return null;
            });

            context.addInitScript(CAPTURE_SCRIPT);
            page.navigate(url);

            ObjectNode nav = MAPPER.createObjectNode();
            nav.put("action",    "navigate");
            nav.put("selector",  "");
            nav.put("value",     url);
            nav.put("timestamp", Instant.now().toString());
            recordedSteps.add(nav);

            return ok("Recording started. Browser open at: " + url +
                    "\nInteract with the page, then call stop_recording().");
        } catch (Exception e) {
            return err("start_recording failed: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult handleStopRecording(Map<String, Object> args) {
        try {
            ArrayNode arr = MAPPER.createArrayNode();
            recordedSteps.forEach(arr::add);
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
            closeBrowser();
            return ok("Recording stopped. Captured " + recordedSteps.size() +
                    " steps.\n\n```json\n" + json + "\n```");
        } catch (Exception e) {
            return err("stop_recording failed: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult handlePlayback(Map<String, Object> args) {
        try {
            String jsonRecording = strArg(args, "jsonRecording");
            String baseUrl       = strArgOpt(args, "url", "");

            var steps = MAPPER.readTree(jsonRecording);
            closeBrowser();
            playwright = Playwright.create();
            browser    = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(false));
            context    = browser.newContext();
            page       = context.newPage();

            int count = 0;
            for (var step : steps) {
                executeStep(step.path("action").asText(),
                        step.path("selector").asText(),
                        step.path("value").asText(), baseUrl);
                count++;
                Thread.sleep(300);
            }
            return ok("Playback complete — replayed " + count + " steps.");
        } catch (Exception e) {
            return err("playback_recording failed: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult handleGenerateBdd(Map<String, Object> args) {
        try {
            String jsonRecording = strArg(args, "jsonRecording");
            String featureName   = strArg(args, "featureName");
            var steps = MAPPER.readTree(jsonRecording);
            var gen   = new BddCodeGenerator(featureName, steps);
            String report =
                    "# Generated BDD Project\n\n" +
                            "## pom.xml\n```xml\n"            + BddCodeGenerator.pomXml() + "\n```\n\n" +
                            "## Feature File\n```gherkin\n"   + gen.featureFile()          + "\n```\n\n" +
                            "## Step Definitions\n```java\n"  + gen.stepDefinitions()      + "\n```\n\n" +
                            "## Page Object\n```java\n"       + gen.pageObject()           + "\n```\n\n" +
                            "## JUnit Runner\n```java\n"      + gen.jUnitRunner()          + "\n```\n";
            return ok(report);
        } catch (Exception e) {
            return err("generate_playwright_bdd failed: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult handleAtomicAction(Map<String, Object> args) {
        try {
            if (page == null) {
                playwright = Playwright.create();
                browser    = playwright.chromium().launch(
                        new BrowserType.LaunchOptions().setHeadless(false));
                context    = browser.newContext();
                page       = context.newPage();
            }
            String action   = strArg(args, "action");
            String selector = strArg(args, "selector");
            String value    = strArgOpt(args, "value", "");
            executeStep(action, selector, value, "");
            return ok("Atomic action '" + action + "' on '" + selector + "' executed.");
        } catch (Exception e) {
            return err("execute_atomic_action failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  STEP EXECUTOR
    // ─────────────────────────────────────────────────────────────────────

    private static void executeStep(String action, String selector,
                                    String value, String baseUrl) {
        switch (action.toLowerCase()) {
            case "navigate"         -> page.navigate(value.isEmpty() ? baseUrl : value);
            case "click"            -> resolveLocator(selector).click();
            case "fill"             -> resolveLocator(selector).fill(value);
            case "type"             -> resolveLocator(selector).type(value);
            case "check"            -> resolveLocator(selector).check();
            case "select_option"    -> resolveLocator(selector).selectOption(value);
            case "getbytext",
                 "click_text"       -> page.getByText(selector).first().click();
            case "getbyrole"        -> page.getByRole(
                    AriaRole.valueOf(selector.toUpperCase())).click();
            case "getbyplaceholder" -> page.getByPlaceholder(selector).fill(value);
            case "press"            -> resolveLocator(selector).press(value);
            case "hover"            -> resolveLocator(selector).hover();
            case "wait"             -> page.waitForTimeout(
                    Double.parseDouble(value.isEmpty() ? "1000" : value));
            case "submit"           -> page.locator("form").first()
                    .evaluate("f => f.submit()");
            default                 -> LOG.warning("Unknown action skipped: " + action);
        }
    }

    private static Locator resolveLocator(String sel) {
        if (sel.startsWith("role:")) {
            String[] parts = sel.substring(5).split(":", 2);
            AriaRole role  = AriaRole.valueOf(parts[0].toUpperCase());
            if (parts.length > 1 && !parts[1].isEmpty())
                return page.getByRole(role, new Page.GetByRoleOptions().setName(parts[1]));
            return page.getByRole(role);
        }
        if (sel.startsWith("text:"))        return page.getByText(sel.substring(5));
        if (sel.startsWith("placeholder:"))  return page.getByPlaceholder(sel.substring(12));
        if (sel.startsWith("label:"))        return page.getByLabel(sel.substring(6));
        if (sel.startsWith("testid:"))       return page.getByTestId(sel.substring(7));
        return page.locator(sel);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  JS CAPTURE SCRIPT
    // ─────────────────────────────────────────────────────────────────────

    private static final String CAPTURE_SCRIPT = """
            (function () {
              function buildSelector(el) {
                const role = el.getAttribute('role') || el.tagName.toLowerCase();
                const name = el.getAttribute('aria-label')
                          || el.getAttribute('placeholder')
                          || (el.innerText || '').trim().substring(0, 50);
                if (name) return `role:${role}:${name}`;
                const tid = el.getAttribute('data-testid');
                if (tid) return `testid:${tid}`;
                const ph = el.getAttribute('placeholder');
                if (ph) return `placeholder:${ph}`;
                const id = el.id;
                if (id) {
                  const lbl = document.querySelector(`label[for="${id}"]`);
                  if (lbl) return `label:${lbl.innerText.trim()}`;
                }
                const parts = [];
                let cur = el;
                while (cur && cur !== document.body) {
                  let seg = cur.tagName.toLowerCase();
                  if (cur.id) { parts.unshift(`#${cur.id}`); break; }
                  if (cur.className) seg += '.' + [...cur.classList].join('.');
                  const sib = [...(cur.parentNode?.children || [])]
                    .filter(c => c.tagName === cur.tagName);
                  if (sib.length > 1) seg += `:nth-of-type(${sib.indexOf(cur) + 1})`;
                  parts.unshift(seg);
                  cur = cur.parentNode;
                }
                return parts.join(' > ');
              }
              function send(action, selector, value) {
                if (typeof window.__mcpCapture === 'function')
                  window.__mcpCapture(JSON.stringify({ action, selector, value }));
              }
              document.addEventListener('click', e => {
                const el = e.target, tag = (el.tagName || '').toLowerCase();
                if (['a','button','input','label','select','option'].includes(tag)
                    || el.getAttribute('role'))
                  send('click', buildSelector(el), el.value || (el.innerText || '').trim());
              }, true);
              document.addEventListener('change', e => {
                const el = e.target, tag = (el.tagName || '').toLowerCase();
                if (['input','textarea','select'].includes(tag))
                  send(tag === 'select' ? 'select_option' : 'fill', buildSelector(el), el.value);
              }, true);
              document.addEventListener('submit', e => {
                send('submit', buildSelector(e.target), '');
              }, true);
            })();
            """;

    // ─────────────────────────────────────────────────────────────────────
    //  ARGUMENT HELPERS  — work directly on Map<String,Object>
    // ─────────────────────────────────────────────────────────────────────

    private static String strArg(Map<String, Object> args, String name) {
        Object val = (args == null) ? null : args.get(name);
        if (val == null)
            throw new IllegalArgumentException("Missing required param: " + name);
        return val.toString();
    }

    private static String strArgOpt(Map<String, Object> args, String name, String def) {
        if (args == null) return def;
        Object val = args.get(name);
        return (val == null) ? def : val.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  RESULT HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private static McpSchema.CallToolResult ok(String text) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .build();
    }

    private static McpSchema.CallToolResult err(String text) {
        return McpSchema.CallToolResult.builder()
                .addTextContent("ERROR: " + text)
                .isError(true)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BROWSER LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    private static void closeBrowser() {
        try { if (page       != null) { page.close();       page       = null; } } catch (Exception ignored) {}
        try { if (context    != null) { context.close();    context    = null; } } catch (Exception ignored) {}
        try { if (browser    != null) { browser.close();    browser    = null; } } catch (Exception ignored) {}
        try { if (playwright != null) { playwright.close(); playwright = null; } } catch (Exception ignored) {}
    }
}