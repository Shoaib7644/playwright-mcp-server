package com.qa.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * BddCodeGenerator — converts an analyzed recording into a complete BDD project scaffold.
 *
 * Generates:
 *   - Gherkin Feature file (Scenario or Scenario Outline with Examples table)
 *   - Cucumber Step Definitions (UiActions abstraction, ScenarioContext DI)
 *   - Page Object class (locator constants + delegating action methods)
 *   - PlaywrightUiActions implementation (Salesforce-safe navigate, waitForSalesforceLightning)
 *   - ScenarioContext (shared state carrier, PicoContainer compatible)
 *   - UiActions interface
 *   - Cucumber + TestNG + Serenity runner classes
 *   - pom.xml
 *
 * Audit fixes applied:
 *   [BG-1] Stale 'private Playwright playwright;' field removed from stepDefinitions().
 *   [BG-2] 'import com.qa.context.ScenarioContext;' added to stepDefinitions().
 *   [BG-3] 'import com.microsoft.playwright.options.WaitUntilState;' added to playwrightUiActions().
 *   [BG-5] cucumber-picocontainer dependency added to pomXml().
 *   [BG-6] Package typo fixed: com.ca.context → com.qa.context throughout.
 *   [BG-7] rawGherkin() zero-arg method removed — now rawGherkin(boolean outline) in IntentAnalyzer.
 */
public class BddCodeGenerator {

    private final String                      featureName;
    private final List<IntentAnalyzer.Intent> intents;
    private final JsonNode                    allEvents;
    private final List<Map<String, String>>   dataRows;
    private final String                      tags;
    private final String                      framework;

    public BddCodeGenerator(String featureName,
                            List<IntentAnalyzer.Intent> intents,
                            JsonNode allEvents,
                            List<Map<String, String>> dataRows,
                            String tags,
                            String framework) {
        this.featureName = featureName;
        this.intents     = intents != null ? intents : Collections.emptyList();
        this.allEvents   = allEvents;
        this.dataRows    = dataRows != null ? dataRows : Collections.emptyList();
        this.tags        = tags != null ? tags : "@smoke @regression";
        this.framework   = framework != null ? framework.toLowerCase() : "cucumber";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FEATURE FILE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Generates the Gherkin feature file.
     *
     * Scenario Outline mode is activated when dataRows is non-empty.
     * In outline mode:
     *   - FILL RAW_ACTION steps use {@code <placeholder>} tokens derived from locator metadata.
     *   - The Examples table includes both caller-supplied columns and derived columns.
     *   - Derived columns for recognized intent types (LOGIN, SEARCH, etc.) are added automatically.
     *   - Derived columns with no value in dataRows emit {@code <colName>} as a visible reminder.
     */
    public String featureFile() {
        StringBuilder sb = new StringBuilder();
        sb.append(tags).append("\n");
        sb.append("Feature: ").append(featureName).append("\n");
        sb.append("  As a user\n");
        sb.append("  I want to ").append(toSentence(featureName)).append("\n");
        sb.append("  So that I achieve the expected outcome\n\n");
        sb.append("  Background:\n");
        sb.append("    Given the browser is open\n\n");

        boolean outline = !dataRows.isEmpty();

        if (outline) {
            // ── Collect all placeholder columns ──────────────────────────────
            // External columns come from the caller-supplied dataRows (preserved as-is).
            List<String> externalCols = new ArrayList<>(dataRows.get(0).keySet());

            // Derived columns: one per RAW_ACTION FILL intent not already in externalCols.
            // Order matches step appearance in the scenario body.
            List<String> derivedCols = new ArrayList<>();
            for (IntentAnalyzer.Intent intent : intents) {
                if (intent.type != IntentAnalyzer.IntentType.RAW_ACTION) continue;
                if (intent.sourceEvents.isEmpty()) continue;
                ObjectNode ev = intent.sourceEvents.get(0);
                if (!"FILL".equals(ev.path("actionType").asText())) continue;
                String ph = IntentAnalyzer.Intent.placeholderName(ev);
                if (!externalCols.contains(ph) && !derivedCols.contains(ph)) {
                    derivedCols.add(ph);
                }
            }

            // Fixed placeholder columns for recognized intent types
            // (these do not have a sourceEvents FILL event to derive from).
            for (IntentAnalyzer.Intent intent : intents) {
                switch (intent.type) {
                    case LOGIN -> {
                        if (!externalCols.contains("username") && !derivedCols.contains("username"))
                            derivedCols.add("username");
                        if (!externalCols.contains("password") && !derivedCols.contains("password"))
                            derivedCols.add("password");
                    }
                    case SEARCH -> {
                        if (!externalCols.contains("query") && !derivedCols.contains("query"))
                            derivedCols.add("query");
                    }
                    case TRANSFER -> {
                        if (!externalCols.contains("amount") && !derivedCols.contains("amount"))
                            derivedCols.add("amount");
                    }
                    case SELECT_FLOW -> {
                        String field = toCamel(intent.params.getOrDefault("field", "option"));
                        if (!externalCols.contains(field) && !derivedCols.contains(field))
                            derivedCols.add(field);
                    }
                    case UPLOAD_FLOW -> {
                        if (!externalCols.contains("filePath") && !derivedCols.contains("filePath"))
                            derivedCols.add("filePath");
                    }
                    default -> {}
                }
            }

            List<String> allCols = new ArrayList<>(externalCols);
            allCols.addAll(derivedCols);

            // ── Scenario Outline block ────────────────────────────────────────
            sb.append("  Scenario Outline: ")
                    .append(featureName)
                    .append(" — <")
                    .append(allCols.isEmpty() ? "value" : allCols.get(0))
                    .append(">\n");

            for (IntentAnalyzer.Intent intent : intents) {
                String step = intent.toGherkinStep(true);
                if (step != null && !step.isBlank())
                    sb.append("    ").append(step).append("\n");
            }

            // ── Examples table ────────────────────────────────────────────────
            sb.append("\n    Examples:\n");
            sb.append("      | ").append(String.join(" | ", allCols)).append(" |\n");

            for (Map<String, String> row : dataRows) {
                sb.append("      |");
                for (String col : allCols) {
                    // External rows supply values; derived columns emit <colName>
                    // as a visible reminder to the user that test data is required.
                    sb.append(" ").append(row.getOrDefault(col, "<" + col + ">")).append(" |");
                }
                sb.append("\n");
            }

        } else {
            sb.append("  Scenario: ").append(featureName).append("\n");
            for (IntentAnalyzer.Intent intent : intents) {
                String step = intent.toGherkinStep(false);
                if (step != null && !step.isBlank())
                    sb.append("    ").append(step).append("\n");
            }
        }

        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  STEP DEFINITIONS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Generates the Cucumber step definitions class.
     *
     * Audit fixes:
     *   [BG-1] Removed stale 'private Playwright playwright;' — Playwright is never
     *          used directly in step classes; only context.getUiActions() is needed.
     *   [BG-2] Added 'import com.qa.context.ScenarioContext;' — required for PicoContainer DI.
     *   [BG-6] Package corrected to com.qa.context (was com.ca.context — typo).
     */
    public String stepDefinitions() {
        String className = toPascal(featureName);
        return "package com.qa.stepdefs;\n\n" +
                "import com.qa.pages." + className + "Page;\n" +
                "import com.qa.actions.UiActions;\n" +
                // [BG-2 + BG-6 FIX] Correct package com.qa.context (not com.ca.context)
                "import com.qa.context.ScenarioContext;\n" +
                "import com.qa.utils.TestConfig;\n" +
                "import io.cucumber.java.en.*;\n" +
                "import io.qameta.allure.Step;\n" +
                "import org.junit.jupiter.api.Assertions;\n\n" +
                "/**\n" +
                " * Step definitions for: " + featureName + "\n" +
                " * If this class already exists, add only the NEW @When/@Then methods.\n" +
                " * DO NOT duplicate Background steps — those live in a shared steps class.\n" +
                " */\n" +
                "public class " + className + "Steps {\n\n" +
                // [BG-1 FIX] 'private Playwright playwright;' removed — not in scope here.
                "    private final ScenarioContext context;\n" +
                "    private final " + className + "Page po;\n\n" +
                "    /** PicoContainer constructor injection. */\n" +
                "    public " + className + "Steps(ScenarioContext context) {\n" +
                "        this.context = context;\n" +
                "        this.po      = new " + className + "Page(context.getPage());\n" +
                "    }\n\n" +
                buildStepMethods() +
                "}\n";
    }

    private String buildStepMethods() {
        StringBuilder sb = new StringBuilder();
        Set<String> emitted = new LinkedHashSet<>();

        for (IntentAnalyzer.Intent intent : intents) {
            String method = buildStepMethod(intent);
            if (method != null && !emitted.contains(method)) {
                sb.append(method);
                emitted.add(method);
            }
        }
        return sb.toString();
    }

    private String buildStepMethod(IntentAnalyzer.Intent intent) {
        return switch (intent.type) {
            case LOGIN ->
                    "    @When(\"user logs in with {string} and {string}\")\n" +
                            "    @Step(\"Login: {0}\")\n" +
                            "    public void userLogsIn(String username, String password) {\n" +
                            "        po.login(username, password);\n" +
                            "    }\n\n";

            case SEARCH ->
                    "    @When(\"user searches for {string}\")\n" +
                            "    @Step(\"Search: {0}\")\n" +
                            "    public void userSearchesFor(String query) {\n" +
                            "        po.search(query);\n" +
                            "    }\n\n";

            case TRANSFER ->
                    "    @When(\"user transfers {string}\")\n" +
                            "    @Step(\"Transfer: {0}\")\n" +
                            "    public void userTransfers(String amount) {\n" +
                            "        po.transfer(amount);\n" +
                            "    }\n\n";

            case FORM_SUBMIT -> {
                String formName = intent.params.getOrDefault("formName", "form");
                yield "    @When(\"user submits the {string} form\")\n" +
                        "    @Step(\"Submit form: {0}\")\n" +
                        "    public void userSubmitsForm(String formName) {\n" +
                        "        po.submitForm(formName);\n" +
                        "    }\n\n";
            }

            case NAVIGATION ->
                    "    @When(\"user navigates to {string}\")\n" +
                            "    @Step(\"Navigate to: {0}\")\n" +
                            "    public void userNavigatesTo(String url) {\n" +
                            "        context.getUiActions().navigate(url);\n" +
                            "    }\n\n";

            case SELECT_FLOW -> {
                String field = toCamel(intent.params.getOrDefault("field", "option"));
                yield "    @When(\"user selects {string} from {string}\")\n" +
                        "    @Step(\"Select: {0} from {1}\")\n" +
                        "    public void userSelects(String value, String field) {\n" +
                        "        context.getUiActions().selectOption(\"" + field + "\", value);\n" +
                        "    }\n\n";
            }

            case UPLOAD_FLOW ->
                    "    @When(\"user uploads file {string}\")\n" +
                            "    @Step(\"Upload file: {0}\")\n" +
                            "    public void userUploadsFile(String filePath) {\n" +
                            "        context.getUiActions().uploadFile(\""
                            + intent.params.getOrDefault("locator", "input[type='file']")
                            + "\", filePath);\n" +
                            "    }\n\n";

            case RAW_ACTION -> buildRawStepMethod(intent);
        };
    }

    private String buildRawStepMethod(IntentAnalyzer.Intent intent) {
        if (intent.sourceEvents.isEmpty()) return null;
        ObjectNode e  = intent.sourceEvents.get(0);
        String at     = e.path("actionType").asText();
        String loc    = e.path("locator").path("primary").asText("element");
        String val    = e.path("inputValue").asText("");

        return switch (at) {
            case "CLICK" ->
                    "    @When(\"user clicks the {string} element\")\n" +
                            "    @Step(\"Click: {0}\")\n" +
                            "    public void userClicksElement(String element) {\n" +
                            "        context.getUiActions().click(element);\n" +
                            "    }\n\n";

            case "FILL" -> {
                String ph = IntentAnalyzer.Intent.placeholderName(e);
                String methodName = "userEnters" + toPascal(ph);
                yield "    @When(\"user enters {string} in the {string} field\")\n" +
                        "    @Step(\"Enter " + ph + ": {0}\")\n" +
                        "    public void " + methodName + "(String value, String field) {\n" +
                        "        context.getUiActions().fill(field, value);\n" +
                        "    }\n\n";
            }

            case "SCROLL" ->
                    "    @When(\"user scrolls the page\")\n" +
                            "    @Step(\"Scroll page\")\n" +
                            "    public void userScrollsThePage() {\n" +
                            "        context.getUiActions().scroll(0, 300);\n" +
                            "    }\n\n";

            // [TASK-3 FIX] PRESS_KEY delegates to context.getUiActions().press(null, key).
            // null selector routes to page.keyboard().press(key) inside PlaywrightUiActions.
            // The original 'page.keyboard().press(key)' was a compilation error — 'page' is
            // never in scope in a step class; only 'context' is injected.
            case "PRESS_KEY" ->
                    "    @When(\"user presses the {string} key\")\n" +
                            "    @Step(\"Press key: {0}\")\n" +
                            "    public void userPressesTheKey(String key) {\n" +
                            "        context.getUiActions().press(null, key);\n" +
                            "    }\n\n";

            case "SELECT_OPTION" ->
                    "    @When(\"user selects {string} from {string}\")\n" +
                            "    @Step(\"Select option: {0} from {1}\")\n" +
                            "    public void userSelectsOption(String value, String field) {\n" +
                            "        context.getUiActions().selectOption(field, value);\n" +
                            "    }\n\n";

            case "CHECK" ->
                    "    @When(\"user checks the {string} checkbox\")\n" +
                            "    @Step(\"Check: {0}\")\n" +
                            "    public void userChecksCheckbox(String label) {\n" +
                            "        context.getUiActions().check(label);\n" +
                            "    }\n\n";

            case "NAVIGATE" ->
                    "    @When(\"user navigates to {string}\")\n" +
                            "    @Step(\"Navigate: {0}\")\n" +
                            "    public void userNavigatesTo(String url) {\n" +
                            "        context.getUiActions().navigate(url);\n" +
                            "    }\n\n";

            default -> null;
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PAGE OBJECT
    // ═════════════════════════════════════════════════════════════════════════

    public String pageObject() {
        String className = toPascal(featureName);
        StringBuilder locators = new StringBuilder();
        StringBuilder methods  = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();

        for (IntentAnalyzer.Intent intent : intents) {
            if (intent.sourceEvents.isEmpty()) continue;
            ObjectNode e   = intent.sourceEvents.get(0);
            String primary = e.path("locator").path("primary").asText("").trim();
            String pw      = e.path("locator").path("playwrightLocator").asText("").trim();
            String at      = e.path("actionType").asText();
            if (primary.isBlank() || seen.contains(primary)) continue;
            seen.add(primary);

            String constName = toConstant(primary);
            locators.append("    private static final String ")
                    .append(constName).append(" = \"")
                    .append(esc(primary)).append("\";\n");

            switch (intent.type) {
                case LOGIN  -> methods.append(loginPageMethods());
                case SEARCH -> methods.append(searchPageMethods());
                default     -> {
                    if ("FILL".equals(at)) {
                        String ph = IntentAnalyzer.Intent.placeholderName(e);
                        methods.append("    public void enter").append(toPascal(ph))
                                .append("(String value) {\n")
                                .append("        uiActions.fill(").append(constName).append(", value);\n")
                                .append("    }\n\n");
                    } else if ("CLICK".equals(at)) {
                        String mName = "click" + toPascal(locatorLabel(e));
                        methods.append("    public void ").append(mName).append("() {\n")
                                .append("        uiActions.click(").append(constName).append(");\n")
                                .append("    }\n\n");
                    }
                }
            }
        }

        return "package com.qa.pages;\n\n" +
                "import com.microsoft.playwright.Page;\n" +
                "import com.qa.actions.UiActions;\n" +
                "import com.qa.actions.PlaywrightUiActions;\n\n" +
                "/**\n" +
                " * Page Object for: " + featureName + "\n" +
                " * If this class already exists, add only the NEW locator constants and methods.\n" +
                " */\n" +
                "public class " + className + "Page {\n\n" +
                "    private final UiActions uiActions;\n\n" +
                locators +
                "\n" +
                "    public " + className + "Page(Page page) {\n" +
                "        this.uiActions = new PlaywrightUiActions(page);\n" +
                "    }\n\n" +
                methods +
                "}\n";
    }

    private String loginPageMethods() {
        return "    public void login(String username, String password) {\n" +
                "        uiActions.fill(USERNAME_LOCATOR, username);\n" +
                "        uiActions.fill(PASSWORD_LOCATOR, password);\n" +
                "        uiActions.click(LOGIN_BUTTON_LOCATOR);\n" +
                "    }\n\n";
    }

    private String searchPageMethods() {
        return "    public void search(String query) {\n" +
                "        uiActions.fill(SEARCH_LOCATOR, query);\n" +
                "        uiActions.press(SEARCH_LOCATOR, \"Enter\");\n" +
                "    }\n\n";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UI ACTIONS INTERFACE
    // ═════════════════════════════════════════════════════════════════════════

    public static String uiActionsInterface() {
        return "package com.qa.actions;\n\n" +
                "/**\n" +
                " * UiActions — abstraction over Playwright Locator API.\n" +
                " * Implement with PlaywrightUiActions for production; mock for unit tests.\n" +
                " */\n" +
                "public interface UiActions {\n\n" +
                "    void navigate(String url);\n" +
                "    void click(String selector);\n" +
                "    void fill(String selector, String value);\n" +
                "    void pressSequentially(String selector, String text);\n" +
                "    void press(String selector, String key);\n" +
                "    void selectOption(String selector, String value);\n" +
                "    void check(String selector);\n" +
                "    void uncheck(String selector);\n" +
                "    void hover(String selector);\n" +
                "    void clear(String selector);\n" +
                "    void uploadFile(String selector, String filePath);\n" +
                "    void scroll(int deltaX, int deltaY);\n" +
                "    String getText(String selector);\n" +
                "    boolean isVisible(String selector);\n" +
                "    void waitForSelector(String selector);\n" +
                "}\n";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PLAYWRIGHT UI ACTIONS (generated implementation)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Generates the PlaywrightUiActions implementation class.
     *
     * Audit fixes applied:
     *   [TASK-2] navigate() uses DOMCONTENTLOADED + waitForSalesforceLightning().
     *   [TASK-6] fill() uses scrollIntoViewIfNeeded + click + fill().
     *   [TASK-6] pressSequentially() replaces deprecated type() — per-character key events.
     *   [BG-3 FIX] WaitUntilState import added (was missing — caused compile error).
     *   [BG-4 confirmed] TIMEOUT_MS comes from the generated class field, not PlaywrightMcpServer.
     */
    public static String playwrightUiActions() {
        return "package com.qa.actions;\n\n" +
                "import com.microsoft.playwright.*;\n" +
                "import com.microsoft.playwright.options.AriaRole;\n" +
                "import com.microsoft.playwright.options.WaitForSelectorState;\n" +
                // [BG-3 FIX] WaitUntilState required for DOMCONTENTLOADED in navigate()
                "import com.microsoft.playwright.options.WaitUntilState;\n" +
                "import java.nio.file.Paths;\n\n" +
                "/**\n" +
                " * PlaywrightUiActions — UiActions implementation backed by a Playwright Page.\n" +
                " *\n" +
                " * navigate() strategy:\n" +
                " *   Uses DOMCONTENTLOADED (not NETWORKIDLE) — safe for Salesforce Lightning.\n" +
                " *   NETWORKIDLE deadlocks on SF orgs due to persistent WebSocket long-polling.\n" +
                " *   waitForSalesforceLightning() handles Aura/LWC render stabilization.\n" +
                " *\n" +
                " * fill() strategy:\n" +
                " *   scroll + click-to-focus + fill() for LWC/Aura shadow-DOM inputs.\n" +
                " *\n" +
                " * press() null-selector routing:\n" +
                " *   null/blank selector → page.keyboard().press(key) (global keypress).\n" +
                " *   non-blank selector  → locator.press(key) (element-scoped).\n" +
                " */\n" +
                "public class PlaywrightUiActions implements UiActions {\n\n" +
                "    private static final long TIMEOUT_MS = Long.parseLong(\n" +
                "            System.getenv().getOrDefault(\"MCP_TIMEOUT_MS\", \"30000\"));\n" +
                "    private static final long SF_TIMEOUT = Long.parseLong(\n" +
                "            System.getenv().getOrDefault(\"MCP_SF_ELEMENT_TIMEOUT_MS\", \"30000\"));\n" +
                "    private static final long LIGHTNING_TIMEOUT = Long.parseLong(\n" +
                "            System.getenv().getOrDefault(\"MCP_SF_LIGHTNING_TIMEOUT_MS\", \"20000\"));\n" +
                "    private static final long LWC_BUFFER_MS = Long.parseLong(\n" +
                "            System.getenv().getOrDefault(\"MCP_LWC_BUFFER_MS\", \"1000\"));\n" +
                "    private static final long TYPE_KEY_DELAY_MS = Long.parseLong(\n" +
                "            System.getenv().getOrDefault(\"MCP_TYPE_KEY_DELAY_MS\", \"50\"));\n" +
                "    private static final int RETRY_MAX = Integer.parseInt(\n" +
                "            System.getenv().getOrDefault(\"MCP_RETRY_MAX\", \"3\"));\n" +
                "    private static final long RETRY_DELAY_MS = Long.parseLong(\n" +
                "            System.getenv().getOrDefault(\"MCP_RETRY_DELAY_MS\", \"800\"));\n\n" +
                "    private final Page page;\n\n" +
                "    public PlaywrightUiActions(Page page) {\n" +
                "        this.page = page;\n" +
                "    }\n\n" +
                // [TASK-2] DOMCONTENTLOADED + waitForSalesforceLightning
                "    @Override\n" +
                "    public void navigate(String url) {\n" +
                "        retry(() -> page.navigate(url,\n" +
                "                new Page.NavigateOptions()\n" +
                "                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)\n" +
                "                        .setTimeout(TIMEOUT_MS)));\n" +
                "        waitForSalesforceLightning();\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void click(String selector) {\n" +
                "        retry(() -> resolve(selector).first()\n" +
                "                .click(new Locator.ClickOptions().setTimeout(SF_TIMEOUT)));\n" +
                "    }\n\n" +
                // [TASK-6] fill: scroll + click-to-focus + fill()
                "    @Override\n" +
                "    public void fill(String selector, String value) {\n" +
                "        retry(() -> {\n" +
                "            Locator loc = resolve(selector).first();\n" +
                "            loc.scrollIntoViewIfNeeded();\n" +
                "            loc.click();\n" +
                "            loc.fill(value);\n" +
                "        });\n" +
                "    }\n\n" +
                // [TASK-6] pressSequentially: per-character key events with configurable delay
                "    @Override\n" +
                "    public void pressSequentially(String selector, String text) {\n" +
                "        retry(() -> resolve(selector).pressSequentially(text,\n" +
                "                new Locator.PressSequentiallyOptions()\n" +
                "                        .setDelay((double) TYPE_KEY_DELAY_MS)));\n" +
                "    }\n\n" +
                // [TASK-3] null/blank selector → global keyboard press; else element press
                "    @Override\n" +
                "    public void press(String selector, String key) {\n" +
                "        if (selector == null || selector.isBlank()) {\n" +
                "            page.keyboard().press(key);\n" +
                "        } else {\n" +
                "            retry(() -> resolve(selector).press(key));\n" +
                "        }\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void selectOption(String selector, String value) {\n" +
                "        retry(() -> resolve(selector).selectOption(value));\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void check(String selector) {\n" +
                "        retry(() -> resolve(selector).check());\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void uncheck(String selector) {\n" +
                "        retry(() -> resolve(selector).uncheck());\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void hover(String selector) {\n" +
                "        retry(() -> resolve(selector).hover());\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void clear(String selector) {\n" +
                "        retry(() -> resolve(selector).clear());\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void uploadFile(String selector, String filePath) {\n" +
                "        resolve(selector).setInputFiles(Paths.get(filePath));\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void scroll(int deltaX, int deltaY) {\n" +
                "        page.mouse().wheel(deltaX, deltaY);\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public String getText(String selector) {\n" +
                "        return resolve(selector).first().innerText();\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public boolean isVisible(String selector) {\n" +
                "        return resolve(selector).first().isVisible();\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void waitForSelector(String selector) {\n" +
                "        page.waitForSelector(selector,\n" +
                "                new Page.WaitForSelectorOptions()\n" +
                "                        .setState(WaitForSelectorState.VISIBLE)\n" +
                "                        .setTimeout(SF_TIMEOUT));\n" +
                "    }\n\n" +
                "    // ── Locator resolution ────────────────────────────────────────────\n\n" +
                "    private Locator resolve(String raw) {\n" +
                "        if (raw == null || raw.isBlank()) return page.locator(\"body\");\n" +
                "        String sel = raw.replaceAll(\"::nth=\\\\d+$\", \"\").trim();\n" +
                "        if (sel.startsWith(\"role:\")) {\n" +
                "            String[] p = sel.substring(5).split(\":\", 2);\n" +
                "            try {\n" +
                "                AriaRole r = AriaRole.valueOf(\n" +
                "                        p[0].toUpperCase().replace(\"-\",\"_\").replace(\" \",\"_\"));\n" +
                "                return p.length > 1 && !p[1].isBlank()\n" +
                "                        ? page.getByRole(r, new Page.GetByRoleOptions().setName(p[1]))\n" +
                "                        : page.getByRole(r);\n" +
                "            } catch (IllegalArgumentException e) {\n" +
                "                return page.locator(\"[role='\" + p[0] + \"']\");\n" +
                "            }\n" +
                "        }\n" +
                "        if (sel.startsWith(\"text:\"))        return page.getByText(sel.substring(5),\n" +
                "                new Page.GetByTextOptions().setExact(false));\n" +
                "        if (sel.startsWith(\"placeholder:\")) return page.getByPlaceholder(sel.substring(12));\n" +
                "        if (sel.startsWith(\"label:\"))       return page.getByLabel(sel.substring(6));\n" +
                "        if (sel.startsWith(\"testid:\"))      return page.getByTestId(sel.substring(7));\n" +
                "        if (sel.startsWith(\"xpath:\"))       return page.locator(\"xpath=\" + sel.substring(6));\n" +
                "        return page.locator(sel);\n" +
                "    }\n\n" +
                "    // ── Retry engine ──────────────────────────────────────────────────\n\n" +
                "    @FunctionalInterface\n" +
                "    private interface Action { void run() throws Exception; }\n\n" +
                "    private void retry(Action action) {\n" +
                "        Exception last = null;\n" +
                "        for (int attempt = 0; attempt <= RETRY_MAX; attempt++) {\n" +
                "            try { action.run(); return; }\n" +
                "            catch (Exception e) {\n" +
                "                last = e;\n" +
                "                if (attempt < RETRY_MAX) {\n" +
                "                    try { Thread.sleep(RETRY_DELAY_MS); }\n" +
                "                    catch (InterruptedException ie) {\n" +
                "                        Thread.currentThread().interrupt();\n" +
                "                        throw new RuntimeException(ie);\n" +
                "                    }\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "        throw new RuntimeException(\"Action failed after \" + RETRY_MAX + \" retries\", last);\n" +
                "    }\n\n" +
                "    // ── Salesforce Lightning stabilization ────────────────────────────\n\n" +
                "    /**\n" +
                "     * Waits for Salesforce Lightning / LWC to finish rendering.\n" +
                "     * Non-Salesforce pages return immediately at step 1 (one-app absent).\n" +
                "     *\n" +
                "     * Step 1 — one-app bootstrap marker present.\n" +
                "     * Step 2 — at least one Aura-rendered component visible.\n" +
                "     * Step 3 — all SLDS spinners dismissed.\n" +
                "     * Step 4 — LWC_BUFFER_MS micro-task buffer for async render queue.\n" +
                "     */\n" +
                "    private void waitForSalesforceLightning() {\n" +
                "        try {\n" +
                "            page.waitForSelector(\"one-app\",\n" +
                "                    new Page.WaitForSelectorOptions()\n" +
                "                            .setState(WaitForSelectorState.ATTACHED)\n" +
                "                            .setTimeout(LIGHTNING_TIMEOUT));\n" +
                "        } catch (Exception ignored) {\n" +
                "            return; // Not a Salesforce page — exit immediately\n" +
                "        }\n" +
                "        try {\n" +
                "            page.waitForSelector(\"[data-aura-rendered-by]\",\n" +
                "                    new Page.WaitForSelectorOptions()\n" +
                "                            .setState(WaitForSelectorState.VISIBLE)\n" +
                "                            .setTimeout(LIGHTNING_TIMEOUT));\n" +
                "        } catch (Exception ignored) {}\n" +
                "        try {\n" +
                "            page.waitForFunction(\n" +
                "                    \"() => document.querySelectorAll('.slds-spinner').length === 0\",\n" +
                "                    new Page.WaitForFunctionOptions().setTimeout(LIGHTNING_TIMEOUT));\n" +
                "        } catch (Exception ignored) {}\n" +
                "        try { page.waitForTimeout(LWC_BUFFER_MS); } catch (Exception ignored) {}\n" +
                "    }\n" +
                "}\n";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SCENARIO CONTEXT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Returns the source code for ScenarioContext.
     *
     * Shared state carrier injected by Cucumber PicoContainer into every step class.
     * Generate once — do not regenerate per feature.
     * Path: src/test/java/com/qa/context/ScenarioContext.java
     *
     * Design decisions:
     *   - setPage() wires uiActions automatically — Hooks.java only needs one call.
     *   - reset() nulls handles rather than discarding the instance — safe for
     *     TestNG parallel reuse across scenarios in the same JVM.
     *   - testData uses synchronized LinkedHashMap — preserves insertion order for
     *     @After logging while remaining safe under parallel step execution.
     */
    public static String scenarioContext() {
        return "package com.qa.context;\n\n" +
                "import com.microsoft.playwright.Page;\n" +
                "import com.qa.actions.UiActions;\n" +
                "import com.qa.actions.PlaywrightUiActions;\n\n" +
                "import java.util.Collections;\n" +
                "import java.util.LinkedHashMap;\n" +
                "import java.util.Map;\n\n" +
                "/**\n" +
                " * ScenarioContext — shared state carrier for a single Cucumber scenario.\n" +
                " *\n" +
                " * Lifecycle:\n" +
                " *   Created once per scenario by PicoContainer.\n" +
                " *   Injected into every step class that declares it as a constructor parameter.\n" +
                " *   Destroyed (state cleared) after the scenario completes via Hooks.java @After.\n" +
                " *\n" +
                " * Usage in a step class:\n" +
                " * <pre>\n" +
                " *   public class MySteps {\n" +
                " *       private final ScenarioContext context;\n" +
                " *       public MySteps(ScenarioContext context) { this.context = context; }\n" +
                " *   }\n" +
                " * </pre>\n" +
                " */\n" +
                "public class ScenarioContext {\n\n" +
                "    // ── Core Playwright handles ───────────────────────────────────────\n\n" +
                "    private Page      page;\n" +
                "    private UiActions uiActions;\n\n" +
                "    // ── Shared test data (scenario-scoped key/value store) ────────────\n\n" +
                "    private final Map<String, String> testData =\n" +
                "            Collections.synchronizedMap(new LinkedHashMap<>());\n\n" +
                "    // ── Page ─────────────────────────────────────────────────────────\n\n" +
                "    /**\n" +
                "     * Returns the Playwright Page for the current scenario.\n" +
                "     * Set by Hooks.java @Before — always non-null when a step runs.\n" +
                "     */\n" +
                "    public Page getPage() {\n" +
                "        if (page == null) throw new IllegalStateException(\n" +
                "                \"ScenarioContext.page is null — \"\n" +
                "                + \"ensure Hooks.java @Before sets it before the first step.\");\n" +
                "        return page;\n" +
                "    }\n\n" +
                "    /**\n" +
                "     * Called by Hooks.java @Before to bind the scenario's Page.\n" +
                "     * Automatically wires a new PlaywrightUiActions instance.\n" +
                "     */\n" +
                "    public void setPage(Page page) {\n" +
                "        this.page      = page;\n" +
                "        this.uiActions = new PlaywrightUiActions(page);\n" +
                "    }\n\n" +
                "    // ── UiActions ────────────────────────────────────────────────────\n\n" +
                "    /**\n" +
                "     * Returns the UiActions implementation for the current scenario.\n" +
                "     * Automatically wired to the Page when setPage() is called.\n" +
                "     */\n" +
                "    public UiActions getUiActions() {\n" +
                "        if (uiActions == null) throw new IllegalStateException(\n" +
                "                \"ScenarioContext.uiActions is null — \"\n" +
                "                + \"ensure Hooks.java @Before calls setPage() first.\");\n" +
                "        return uiActions;\n" +
                "    }\n\n" +
                "    /**\n" +
                "     * Override the UiActions implementation (e.g. for mocking in unit tests).\n" +
                "     * Normal usage does not need to call this — setPage() wires it automatically.\n" +
                "     */\n" +
                "    public void setUiActions(UiActions uiActions) {\n" +
                "        this.uiActions = uiActions;\n" +
                "    }\n\n" +
                "    // ── Shared test data ─────────────────────────────────────────────\n\n" +
                "    /**\n" +
                "     * Store a value for the duration of the current scenario.\n" +
                "     * Use to pass data between steps (e.g. a generated ID from step 1 to step 3).\n" +
                "     */\n" +
                "    public void set(String key, String value) {\n" +
                "        testData.put(key, value);\n" +
                "    }\n\n" +
                "    /** Retrieve a value stored earlier in this scenario. Returns null if absent. */\n" +
                "    public String get(String key) {\n" +
                "        return testData.get(key);\n" +
                "    }\n\n" +
                "    /** Retrieve a value with a fallback default. */\n" +
                "    public String get(String key, String defaultValue) {\n" +
                "        return testData.getOrDefault(key, defaultValue);\n" +
                "    }\n\n" +
                "    /** Returns true if a value has been stored under the given key. */\n" +
                "    public boolean has(String key) {\n" +
                "        return testData.containsKey(key);\n" +
                "    }\n\n" +
                "    /**\n" +
                "     * Returns an unmodifiable snapshot of all test data.\n" +
                "     * Useful for debugging or Allure attachment in @After hooks.\n" +
                "     */\n" +
                "    public Map<String, String> getAllTestData() {\n" +
                "        return Collections.unmodifiableMap(testData);\n" +
                "    }\n\n" +
                "    // ── Lifecycle ────────────────────────────────────────────────────\n\n" +
                "    /**\n" +
                "     * Clears all scenario state.\n" +
                "     * Called by Hooks.java @After so PicoContainer can safely reuse this instance\n" +
                "     * across scenarios in the same JVM (parallel TestNG runs).\n" +
                "     */\n" +
                "    public void reset() {\n" +
                "        page      = null;\n" +
                "        uiActions = null;\n" +
                "        testData.clear();\n" +
                "    }\n" +
                "}\n";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  RUNNERS
    // ═════════════════════════════════════════════════════════════════════════

    public String runnerClass() {
        String className = toPascal(featureName);
        return switch (framework) {
            case "testng"   -> testngRunner(className);
            case "serenity" -> serenityRunner(className);
            default         -> cucumberRunner(className);
        };
    }

    private String cucumberRunner(String className) {
        return "package com.qa.runners;\n\n" +
                "import org.junit.platform.suite.api.*;\n\n" +
                "@Suite\n" +
                "@IncludeEngines(\"cucumber\")\n" +
                "@SelectClasspathResource(\"features\")\n" +
                "@ConfigurationParameter(key = \"cucumber.plugin\",\n" +
                "        value = \"pretty, io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm\")\n" +
                "@ConfigurationParameter(key = \"cucumber.glue\", value = \"com.qa.stepdefs\")\n" +
                "@ConfigurationParameter(key = \"cucumber.filter.tags\", value = \"" + tags + "\")\n" +
                "public class " + className + "Runner {}\n";
    }

    private String testngRunner(String className) {
        return "package com.qa.runners;\n\n" +
                "import io.cucumber.testng.AbstractTestNGCucumberTests;\n" +
                "import io.cucumber.testng.CucumberOptions;\n" +
                "import org.testng.annotations.DataProvider;\n\n" +
                "@CucumberOptions(\n" +
                "        features = \"src/test/resources/features\",\n" +
                "        glue     = \"com.qa.stepdefs\",\n" +
                "        tags     = \"" + tags + "\",\n" +
                "        plugin   = {\"pretty\",\n" +
                "                    \"io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm\"})\n" +
                "public class " + className + "Runner extends AbstractTestNGCucumberTests {\n" +
                "    @Override\n" +
                "    @DataProvider(parallel = true)\n" +
                "    public Object[][] scenarios() { return super.scenarios(); }\n" +
                "}\n";
    }

    private String serenityRunner(String className) {
        return "package com.qa.runners;\n\n" +
                "import io.cucumber.junit.CucumberOptions;\n" +
                "import net.serenitybdd.cucumber.CucumberWithSerenity;\n" +
                "import org.junit.runner.RunWith;\n\n" +
                "@RunWith(CucumberWithSerenity.class)\n" +
                "@CucumberOptions(\n" +
                "        features = \"src/test/resources/features\",\n" +
                "        glue     = \"com.qa.stepdefs\",\n" +
                "        tags     = \"" + tags + "\",\n" +
                "        plugin   = {\"pretty\"})\n" +
                "public class " + className + "Runner {}\n";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  POM.XML
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Generates the project pom.xml.
     *
     * [BG-5 FIX] cucumber-picocontainer added — required for ScenarioContext
     * constructor injection. Without it, PicoContainer cannot satisfy the
     * ScenarioContext parameter in step class constructors.
     */
    public static String pomXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0\n" +
                "         http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.qa</groupId>\n" +
                "  <artifactId>playwright-mcp-bdd</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "  <packaging>jar</packaging>\n\n" +
                "  <properties>\n" +
                "    <maven.compiler.source>17</maven.compiler.source>\n" +
                "    <maven.compiler.target>17</maven.compiler.target>\n" +
                "    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n" +
                "    <!-- Update playwright.version when upgrading -->\n" +
                "    <playwright.version>1.44.0</playwright.version>\n" +
                "    <cucumber.version>7.15.0</cucumber.version>\n" +
                "    <junit5.version>5.10.2</junit5.version>\n" +
                "    <allure.version>2.25.0</allure.version>\n" +
                "    <testng.version>7.9.0</testng.version>\n" +
                "  </properties>\n\n" +
                "  <dependencies>\n\n" +
                "    <!-- Playwright -->\n" +
                "    <dependency><groupId>com.microsoft.playwright</groupId><artifactId>playwright</artifactId>\n" +
                "      <version>${playwright.version}</version></dependency>\n\n" +
                "    <!-- Cucumber -->\n" +
                "    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-java</artifactId>\n" +
                "      <version>${cucumber.version}</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-junit-platform-engine</artifactId>\n" +
                "      <version>${cucumber.version}</version><scope>test</scope></dependency>\n" +
                // [BG-5 FIX] cucumber-picocontainer — required for ScenarioContext DI
                "    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-picocontainer</artifactId>\n" +
                "      <version>${cucumber.version}</version><scope>test</scope></dependency>\n\n" +
                "    <!-- JUnit 5 -->\n" +
                "    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>\n" +
                "      <version>${junit5.version}</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-suite</artifactId>\n" +
                "      <version>1.10.2</version><scope>test</scope></dependency>\n\n" +
                "    <!-- Allure -->\n" +
                "    <dependency><groupId>io.qameta.allure</groupId><artifactId>allure-cucumber7-jvm</artifactId>\n" +
                "      <version>${allure.version}</version><scope>test</scope></dependency>\n\n" +
                "    <!-- TestNG (optional — only required for testng runner) -->\n" +
                "    <dependency><groupId>org.testng</groupId><artifactId>testng</artifactId>\n" +
                "      <version>${testng.version}</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-testng</artifactId>\n" +
                "      <version>${cucumber.version}</version><scope>test</scope></dependency>\n\n" +
                "    <!-- Jackson -->\n" +
                "    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId>\n" +
                "      <version>2.17.0</version></dependency>\n\n" +
                "  </dependencies>\n\n" +
                "  <build>\n" +
                "    <plugins>\n" +
                "      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>\n" +
                "        <version>3.2.5</version>\n" +
                "        <configuration>\n" +
                "          <includes><include>**/*Runner.java</include></includes>\n" +
                "        </configuration>\n" +
                "      </plugin>\n" +
                "      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId>\n" +
                "        <version>3.5.2</version>\n" +
                "        <executions><execution><phase>package</phase><goals><goal>shade</goal></goals>\n" +
                "          <configuration>\n" +
                "            <filters><filter><artifact>*:*</artifact>\n" +
                "              <excludes><exclude>META-INF/*.SF</exclude><exclude>META-INF/*.DSA</exclude><exclude>META-INF/*.RSA</exclude></excludes>\n" +
                "            </filter></filters>\n" +
                "            <transformers>\n" +
                "              <transformer implementation=\"org.apache.maven.plugins.shade.resource.ServicesResourceTransformer\"/>\n" +
                "              <transformer implementation=\"org.apache.maven.plugins.shade.resource.ManifestResourceTransformer\">\n" +
                "                <mainClass>com.qa.mcp.PlaywrightMcpServer</mainClass>\n" +
                "              </transformer>\n" +
                "            </transformers>\n" +
                "          </configuration>\n" +
                "        </execution></executions>\n" +
                "      </plugin>\n" +
                "    </plugins>\n" +
                "  </build>\n" +
                "</project>\n";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ═════════════════════════════════════════════════════════════════════════

    private static String toPascal(String s) {
        if (s == null || s.isBlank()) return "Generated";
        String[] parts = s.replaceAll("[^a-zA-Z0-9 ]", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : parts) {
            if (!w.isBlank())
                sb.append(Character.toUpperCase(w.charAt(0)))
                        .append(w.substring(1).toLowerCase());
        }
        return sb.isEmpty() ? "Generated" : sb.toString();
    }

    private static String toCamel(String s) {
        if (s == null || s.isBlank()) return "value";
        String[] parts = s.trim().split("[\\s_\\-]+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String w = parts[i].replaceAll("[^a-zA-Z0-9]", "");
            if (w.isBlank()) continue;
            sb.append(i == 0
                    ? Character.toLowerCase(w.charAt(0)) + w.substring(1).toLowerCase()
                    : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase());
        }
        return sb.isEmpty() ? "value" : sb.toString();
    }

    private static String toConstant(String s) {
        if (s == null || s.isBlank()) return "ELEMENT_LOCATOR";
        return s.replaceAll("[^a-zA-Z0-9]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toUpperCase();
    }

    private static String toSentence(String s) {
        if (s == null || s.isBlank()) return "perform the workflow";
        return s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim();
    }

    private static String locatorLabel(ObjectNode event) {
        JsonNode loc = event.path("locator");
        String v;
        v = loc.path("ariaLabel").asText("").trim();   if (!v.isBlank()) return v;
        v = loc.path("text").asText("").trim();        if (!v.isBlank()) return v;
        v = loc.path("id").asText("").trim();          if (!v.isBlank()) return v;
        return "Element";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}