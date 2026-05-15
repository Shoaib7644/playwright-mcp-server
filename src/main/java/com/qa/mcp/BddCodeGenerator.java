package com.qa.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * BddCodeGenerator v6
 *
 * ══════════════════════════════════════════════════════════════════════
 *  NEW IN v6 (fixes 3 compilation errors + full intent-driven rewrite)
 * ══════════════════════════════════════════════════════════════════════
 *
 *  Constructor signature (matches PlaywrightMcpServer call at line 608):
 *    BddCodeGenerator(featureName, intents, events, dataRows, tags, framework)
 *
 *  New static methods (referenced at lines 612-613):
 *    uiActionsInterface()    → UiActions.java source
 *    playwrightUiActions()   → PlaywrightUiActions.java source
 *
 *  Intent-driven generation:
 *    - Feature file built from List<Intent> not raw events
 *    - One Gherkin step per Intent (not per DOM event)
 *    - LOGIN  → "When user logs in with <username> and <password>"
 *    - SEARCH → "When user searches for <query>"
 *    - FORM_SUBMIT → "When user submits the form"
 *    - RAW_ACTION → one step per remaining event
 *
 *  Page Object:
 *    - Uses UiActions interface (framework-agnostic)
 *    - One composite method per Intent
 *    - waitForVisible(), getText(), isVisible(), isEnabled() on every field
 *    - Full JavaDoc on all generated methods
 *
 *  Step Definitions:
 *    - Delegate to Page Object methods (no inline Playwright calls)
 *    - @Step(Allure) annotation on every step
 *    - Structured assertion messages
 *
 *  Runners:
 *    - cucumber (JUnit 5 Platform Suite)
 *    - testng   (AbstractTestNGCucumberTests + parallel DataProvider)
 *    - serenity (CucumberWithSerenity)
 *
 *  Data-driven:
 *    - dataRows → Scenario Outline + Examples table
 *    - Parameters wired into intent Gherkin steps
 * ══════════════════════════════════════════════════════════════════════
 */
public class BddCodeGenerator {

    private final String                         featureName;
    private final String                         className;
    private final List<IntentAnalyzer.Intent>    intents;
    private final JsonNode                       rawEvents;
    private final List<Map<String, String>>      dataRows;
    private final String                         tags;
    private final String                         framework;

    // Field registry — built once, shared across pageObject() + stepDefinitions()
    private final Map<String, FieldEntry> fields = new LinkedHashMap<>();

    private static class FieldEntry {
        String locatorExpr;   // raw CSS selector string — value of the String constant
        String actionType;    // FILL | CLICK | SELECT_OPTION …
        String defaultValue;
        String constantName;  // UPPER_SNAKE_CASE — name of the String constant field
        FieldEntry(String locatorExpr, String actionType, String defaultValue, String constantName) {
            this.locatorExpr  = locatorExpr;
            this.actionType   = actionType;
            this.defaultValue = defaultValue;
            this.constantName = constantName;
        }
    }

    /**
     * Primary constructor — called from PlaywrightMcpServer.handleGenerateBdd().
     *
     * @param featureName  human-readable feature label
     * @param intents      structured intents from IntentAnalyzer.analyze()
     * @param rawEvents    original recorded events (for locator extraction)
     * @param dataRows     optional rows for Scenario Outline
     * @param tags         Gherkin tags e.g. "@smoke @regression"
     * @param framework    "cucumber" | "testng" | "serenity"
     */
    public BddCodeGenerator(String featureName,
                            List<IntentAnalyzer.Intent> intents,
                            JsonNode rawEvents,
                            List<Map<String, String>> dataRows,
                            String tags,
                            String framework) {
        this.featureName = featureName;
        this.className   = toPascal(featureName);
        this.intents     = intents != null ? intents : List.of();
        this.rawEvents   = rawEvents;
        this.dataRows    = dataRows != null ? dataRows : List.of();
        this.tags        = tags != null && !tags.isBlank() ? tags : "@smoke @regression";
        this.framework   = framework != null ? framework.toLowerCase() : "cucumber";
        buildFieldRegistry();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FEATURE FILE
    // ═════════════════════════════════════════════════════════════════════════
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
            // Scenario Outline — parameters from first dataRow's keys
            List<String> cols = new ArrayList<>(dataRows.get(0).keySet());
            sb.append("  Scenario Outline: ").append(featureName)
                    .append(" — <").append(cols.get(0)).append(">\n");
            for (IntentAnalyzer.Intent intent : intents) {
                String step = intent.toGherkinStep(true);
                if (step != null) sb.append("    ").append(step).append("\n");
            }
            sb.append("\n    Examples:\n");
            sb.append("      | ").append(String.join(" | ", cols)).append(" |\n");
            for (Map<String, String> row : dataRows) {
                sb.append("      |");
                for (String col : cols) sb.append(" ").append(row.getOrDefault(col, "")).append(" |");
                sb.append("\n");
            }
        } else {
            sb.append("  Scenario: ").append(featureName).append("\n");
            for (IntentAnalyzer.Intent intent : intents) {
                String step = intent.toGherkinStep(false);
                if (step != null) sb.append("    ").append(step).append("\n");
            }
        }
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PAGE OBJECT  (UiActions-based, framework-agnostic)
    // ═════════════════════════════════════════════════════════════════════════
    public String pageObject() {
        StringBuilder fieldDecls = new StringBuilder();
        StringBuilder fieldInits = new StringBuilder();
        StringBuilder methods    = new StringBuilder();

        // String constant declarations — matches ParabankAccountOpeningPage pattern exactly:
        //   private final String USERNAME_FIELD = "#loginPanel > form > div:nth-of-type(1) > input";
        for (Map.Entry<String, FieldEntry> entry : fields.entrySet()) {
            String fname = entry.getKey();
            FieldEntry fe = entry.getValue();
            String constName = fe.constantName != null ? fe.constantName : toConstantName(fname);
            fieldDecls.append("    /** ").append(fe.actionType).append(" */\n");
            fieldDecls.append("    private final String ").append(constName)
                    .append(" = \"").append(fe.locatorExpr.replace("\"", "\\\"")).append("\";\n");
        }

        // fieldInits not needed — String constants are inline final declarations

        // Per-field action + getter methods
        Set<String> builtMethods = new LinkedHashSet<>();
        for (Map.Entry<String, FieldEntry> entry : fields.entrySet()) {
            String fname = entry.getKey();
            FieldEntry fe = entry.getValue();
            String actionMethod = buildActionMethod(fname, fe);
            if (actionMethod != null && builtMethods.add(fname + "_action"))
                methods.append(actionMethod).append("\n");
            String getters = buildGetterMethods(fname, fe.actionType);
            if (builtMethods.add(fname + "_getters"))
                methods.append(getters);
        }

        // Intent-level composite methods
        Set<String> builtIntents = new LinkedHashSet<>();
        for (IntentAnalyzer.Intent intent : intents) {
            if (intent.type == IntentAnalyzer.IntentType.RAW_ACTION) continue;
            String sig = intent.toMethodSignature(className);
            if (!builtIntents.add(sig)) continue;
            methods.append(buildCompositeMethod(intent));
        }

        return "package com.qa.pages;\n\n" +
                "import com.microsoft.playwright.*;\n" +
                "import com.microsoft.playwright.options.LoadState;\n" +
                "import com.qa.actions.UiActions;\n" +
                "import java.nio.file.Paths;\n\n" +
                "/**\n" +
                " * Page Object — " + featureName + "\n" +
                " * Generated by PlaywrightMcpServer v6\n" +
                " *\n" +
                " * Pattern: String selector constants + UiActions calls\n" +
                " * Matches: ParabankAccountOpeningPage.java structure exactly.\n" +
                " * Swap PlaywrightUiActions for SeleniumUiActions without touching tests.\n" +
                " */\n" +
                "public class " + className + "Page {\n\n" +
                "    private final Page      page;\n" +
                "    private final UiActions ui;\n\n" +
                "    // ── Selector constants ────────────────────────────────────────────\n" +
                fieldDecls + "\n" +
                "    /**\n" +
                "     * Construct the page object.\n" +
                "     * @param page Playwright Page instance\n" +
                "     * @param ui   UiActions implementation (PlaywrightUiActions or SeleniumUiActions)\n" +
                "     */\n" +
                "    public " + className + "Page(Page page, UiActions ui) {\n" +
                "        this.page = page;\n" +
                "        this.ui   = ui;\n" +
                "    }\n\n" +
                "    /**\n" +
                "     * Navigate to the given URL and wait for full page load.\n" +
                "     * @param url full URL\n" +
                "     * @return this page (fluent)\n" +
                "     */\n" +
                "    public " + className + "Page navigateTo(String url) {\n" +
                "        ui.navigate(url);\n" +
                "        return this;\n" +
                "    }\n\n" +
                "    /** Wait for network idle — call after actions that trigger slow XHR. */\n" +
                "    public void waitForPageLoad() {\n" +
                "        ui.waitForNetworkIdle();\n" +
                "    }\n\n" +
                "    /** @return current page URL */\n" +
                "    public String getCurrentUrl()  { return ui.getCurrentUrl(); }\n\n" +
                "    /** @return current page title */\n" +
                "    public String getPageTitle()   { return ui.getPageTitle(); }\n\n" +
                methods +
                "}\n";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  STEP DEFINITIONS  (delegate to Page Object methods)
    // ═════════════════════════════════════════════════════════════════════════
    public String stepDefinitions() {
        StringBuilder stepImpls = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();

        for (IntentAnalyzer.Intent intent : intents) {
            String key = intent.type.name() + "|" + intent.description;
            if (seen.contains(key)) continue;
            seen.add(key);
            String impl = buildStepImpl(intent);
            if (impl != null) stepImpls.append(impl).append("\n");
        }

        String po = toCamel(className) + "Page";

        return "package com.qa.stepdefs;\n\n" +
                "import com.microsoft.playwright.*;\n" +
                "import com.qa.pages." + className + "Page;\n" +
                "import com.qa.actions.PlaywrightUiActions;\n" +
                "import io.cucumber.java.After;\n" +
                "import io.cucumber.java.Before;\n" +
                "import io.cucumber.java.en.*;\n" +
                "import io.qameta.allure.Step;\n" +
                "import org.junit.jupiter.api.Assertions;\n" +
                "import java.io.IOException;\n" +
                "import java.io.InputStream;\n" +
                "import java.util.Properties;\n\n" +
                "/**\n" +
                " * Step Definitions — " + featureName + "\n" +
                " * Generated by PlaywrightMcpServer v6\n" +
                " * All steps delegate to {@link " + className + "Page} — no direct Playwright calls here.\n" +
                " */\n" +
                "public class " + className + "Steps {\n\n" +
                "    private Playwright       playwright;\n" +
                "    private Browser          browser;\n" +
                "    private BrowserContext   context;\n" +
                "    private Page             page;\n" +
                "    private " + className + "Page " + po + ";\n\n" +
                "    @Before\n" +
                "    public void setUp() {\n" +
                "        playwright = Playwright.create();\n" +
                "        browser    = playwright.chromium().launch(\n" +
                "                         new BrowserType.LaunchOptions().setHeadless(true));\n" +
                "        context    = browser.newContext(\n" +
                "                         new Browser.NewContextOptions().setBypassCSP(true));\n" +
                "        page       = context.newPage();\n" +
                "        " + po + " = new " + className + "Page(page, new PlaywrightUiActions(page));\n" +
                "    }\n\n" +
                "    @After\n" +
                "    public void tearDown() {\n" +
                "        if (context    != null) context.close();\n" +
                "        if (browser    != null) browser.close();\n" +
                "        if (playwright != null) playwright.close();\n" +
                "    }\n\n" +
                "    // ── Common steps ──────────────────────────────────────────────────────\n\n" +
                "    @Given(\"the browser is open\")\n" +
                "    public void theBrowserIsOpen() { /* ready via @Before */ }\n\n" +
                "    @When(\"user navigates to {string}\")\n" +
                "    @Step(\"Navigate to {0}\")\n" +
                "    public void userNavigatesTo(String url) {\n" +
                "        " + po + ".navigateTo(url);\n" +
                "    }\n\n" +
                "    @When(\"I navigate to {string}\")\n" +
                "    @Step(\"Navigate to {0}\")\n" +
                "    public void iNavigateTo(String url) {\n" +
                "        " + po + ".navigateTo(url);\n" +
                "    }\n\n" +
                "    @When(\"I scroll the page to position {int}, {int}\")\n" +
                "    @Step(\"Scroll to {0},{1}\")\n" +
                "    public void iScrollThePage(int x, int y) { page.mouse().wheel(x, y); }\n\n" +
                "    @Then(\"the URL should be {string}\")\n" +
                "    @Step(\"Assert URL equals {0}\")\n" +
                "    public void theUrlShouldBe(String url) {\n" +
                "        Assertions.assertEquals(url, page.url(),\n" +
                "            \"Expected URL '\" + url + \"' but was '\" + page.url() + \"'\");\n" +
                "    }\n\n" +
                "    @Then(\"I should see {string} on the page\")\n" +
                "    @Step(\"Assert text '{0}' is visible\")\n" +
                "    public void iShouldSeeOnThePage(String text) {\n" +
                "        Assertions.assertTrue(\n" +
                "            page.getByText(text).first().isVisible(),\n" +
                "            \"Expected text '\" + text + \"' to be visible on page\");\n" +
                "    }\n\n" +
                "    @Then(\"the {string} element should be visible\")\n" +
                "    @Step(\"Assert element '{0}' is visible\")\n" +
                "    public void theElementShouldBeVisible(String element) {\n" +
                "        Assertions.assertTrue(\n" +
                "            page.getByText(element).first().isVisible(),\n" +
                "            \"Element '\" + element + \"' is not visible\");\n" +
                "    }\n\n" +
                "    // ── Intent-driven steps ───────────────────────────────────────────────\n\n" +
                stepImpls +
                "    // ── Test Data ────────────────────────────────────────────────────────\n\n" +
                "    /**\n" +
                "     * Load a value from src/test/resources/TestData/*.properties.\n" +
                "     * Falls back to defaultValue when key is not found.\n" +
                "     * Usage: getProperty(\"parabank.password\", \"default\")\n" +
                "     */\n" +
                "    protected String getProperty(String key, String defaultValue) {\n" +
                "        try (InputStream is = getClass().getClassLoader()\n" +
                "                .getResourceAsStream(\"TestData/parabank.properties\")) {\n" +
                "            if (is == null) return defaultValue;\n" +
                "            Properties props = new Properties();\n" +
                "            props.load(is);\n" +
                "            return props.getProperty(key, defaultValue);\n" +
                "        } catch (IOException e) {\n" +
                "            return defaultValue;\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  RUNNER
    // ═════════════════════════════════════════════════════════════════════════
    public String runner() {
        return switch (framework) {
            case "testng"   -> testNgRunner();
            case "serenity" -> serenityRunner();
            default         -> cucumberRunner();
        };
    }

    private String cucumberRunner() {
        String firstTag = tags.split("\\s+")[0];
        return "package com.qa.runners;\n\n" +
                "import io.cucumber.junit.platform.engine.Constants;\n" +
                "import org.junit.platform.suite.api.*;\n\n" +
                "/**\n * JUnit 5 Cucumber Runner — " + featureName + "\n * Generated by PlaywrightMcpServer v6\n */\n" +
                "@Suite\n" +
                "@IncludeEngines(\"cucumber\")\n" +
                "@SelectClasspathResource(\"features\")\n" +
                "@ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME,      value = \"com.qa.stepdefs\")\n" +
                "@ConfigurationParameter(key = Constants.PLUGIN_PROPERTY_NAME,\n" +
                "        value = \"pretty, " +
                "html:target/cucumber-reports/" + className + "-report.html, " +
                "json:target/cucumber-reports/" + className + ".json, " +
                "io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm\")\n" +
                "@ConfigurationParameter(key = Constants.FILTER_TAGS_PROPERTY_NAME, value = \"" + firstTag + "\")\n" +
                "public class " + className + "Runner {}\n";
    }

    private String testNgRunner() {
        String firstTag = tags.split("\\s+")[0];
        return "package com.qa.runners;\n\n" +
                "import io.cucumber.testng.AbstractTestNGCucumberTests;\n" +
                "import io.cucumber.testng.CucumberOptions;\n" +
                "import org.testng.annotations.DataProvider;\n\n" +
                "/**\n * TestNG Cucumber Runner — " + featureName + "\n * Generated by PlaywrightMcpServer v6\n */\n" +
                "@CucumberOptions(\n" +
                "    features = \"src/test/resources/features\",\n" +
                "    glue     = \"com.qa.stepdefs\",\n" +
                "    tags     = \"" + firstTag + "\",\n" +
                "    plugin   = {\"pretty\",\n" +
                "                \"html:target/cucumber-reports/" + className + "-report.html\",\n" +
                "                \"json:target/cucumber-reports/" + className + ".json\"}\n" +
                ")\n" +
                "public class " + className + "Runner extends AbstractTestNGCucumberTests {\n\n" +
                "    /** Enable parallel scenario execution. */\n" +
                "    @Override\n" +
                "    @DataProvider(parallel = true)\n" +
                "    public Object[][] scenarios() {\n" +
                "        return super.scenarios();\n" +
                "    }\n}\n";
    }

    private String serenityRunner() {
        String firstTag = tags.split("\\s+")[0];
        return "package com.qa.runners;\n\n" +
                "import net.serenitybdd.cucumber.CucumberWithSerenity;\n" +
                "import io.cucumber.junit.CucumberOptions;\n" +
                "import org.junit.runner.RunWith;\n\n" +
                "/**\n * Serenity BDD Runner — " + featureName + "\n * Generated by PlaywrightMcpServer v6\n */\n" +
                "@RunWith(CucumberWithSerenity.class)\n" +
                "@CucumberOptions(\n" +
                "    features = \"src/test/resources/features\",\n" +
                "    glue     = \"com.qa.stepdefs\",\n" +
                "    tags     = \"" + firstTag + "\",\n" +
                "    plugin   = {\"pretty\"}\n" +
                ")\n" +
                "public class " + className + "Runner {}\n";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UI ACTIONS INTERFACE  (static — referenced by PlaywrightMcpServer)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Returns the source code for the UiActions interface.
     * Framework-agnostic contract for all page interactions.
     * Place at: src/main/java/com/qa/actions/UiActions.java
     */
    public static String uiActionsInterface() {
        return "package com.qa.actions;\n\n" +
                "import java.nio.file.Path;\n\n" +
                "/**\n" +
                " * UiActions — framework-agnostic interaction contract.\n" +
                " *\n" +
                " * Implementations:\n" +
                " *   {@link PlaywrightUiActions}  — Playwright Java\n" +
                " *   SeleniumUiActions             — Selenium WebDriver (future)\n" +
                " *\n" +
                " * Page Objects depend ONLY on this interface.\n" +
                " * Swap the implementation in step @Before without touching any Page Object.\n" +
                " */\n" +
                "public interface UiActions {\n\n" +
                "    /** Navigate to a URL and wait for load. */\n" +
                "    void navigate(String url);\n\n" +
                "    /** Click the element matching the given selector string. */\n" +
                "    void click(String selector);\n\n" +
                "    /** Double-click the element matching the given selector string. */\n" +
                "    void doubleClick(String selector);\n\n" +
                "    /** Clear and fill an input field. */\n" +
                "    void fill(String selector, String value);\n\n" +
                "    /** Type text character by character. */\n" +
                "    void type(String selector, String text);\n\n" +
                "    /** Clear an input field. */\n" +
                "    void clear(String selector);\n\n" +
                "    /** Press a key on a focused element or globally. */\n" +
                "    void press(String selector, String key);\n\n" +
                "    /** Hover over an element. */\n" +
                "    void hover(String selector);\n\n" +
                "    /** Select an option from a dropdown. */\n" +
                "    void selectOption(String selector, String value);\n\n" +
                "    /** Check a checkbox or radio button. */\n" +
                "    void check(String selector);\n\n" +
                "    /** Uncheck a checkbox. */\n" +
                "    void uncheck(String selector);\n\n" +
                "    /** Upload a file via a file input. */\n" +
                "    void uploadFile(String selector, Path filePath);\n\n" +
                "    /** Scroll the page by (deltaX, deltaY) pixels. */\n" +
                "    void scroll(double deltaX, double deltaY);\n\n" +
                "    /** Wait for an element to become visible. */\n" +
                "    void waitForVisible(String selector);\n\n" +
                "    /** Wait for an element to become hidden. */\n" +
                "    void waitForHidden(String selector);\n\n" +
                "    /** Wait for the page URL to match. */\n" +
                "    void waitForUrl(String urlPattern);\n\n" +
                "    /** Wait for network idle. */\n" +
                "    void waitForNetworkIdle();\n\n" +
                "    /** Take a screenshot and save to path. */\n" +
                "    void screenshot(String filePath);\n\n" +
                "    /** Get inner text of an element. */\n" +
                "    String getText(String selector);\n\n" +
                "    /** Get input value of a form field. */\n" +
                "    String getValue(String selector);\n\n" +
                "    /** Get an HTML attribute value. */\n" +
                "    String getAttribute(String selector, String attribute);\n\n" +
                "    /** Return true if the element is visible. */\n" +
                "    boolean isVisible(String selector);\n\n" +
                "    /** Return true if the element is enabled. */\n" +
                "    boolean isEnabled(String selector);\n\n" +
                "    /** Return true if the element is checked. */\n" +
                "    boolean isChecked(String selector);\n\n" +
                "    /** Return the current page URL. */\n" +
                "    String getCurrentUrl();\n\n" +
                "    /** Return the current page title. */\n" +
                "    String getPageTitle();\n" +
                "}\n";
    }

    /**
     * Returns the source code for PlaywrightUiActions.
     * Playwright implementation of the UiActions interface.
     * Place at: src/main/java/com/qa/actions/PlaywrightUiActions.java
     */
    public static String playwrightUiActions() {
        return "package com.qa.actions;\n\n" +
                "import com.microsoft.playwright.*;\n" +
                "import com.microsoft.playwright.options.AriaRole;\n" +
                "import com.microsoft.playwright.options.LoadState;\n" +
                "import com.microsoft.playwright.options.WaitForSelectorState;\n" +
                "import java.nio.file.Path;\n" +
                "import java.nio.file.Paths;\n\n" +
                "/**\n" +
                " * PlaywrightUiActions — Playwright implementation of {@link UiActions}.\n" +
                " *\n" +
                " * Features:\n" +
                " *  - Smart Wait: DOM ready → element visible → DOM settled before every action\n" +
                " *  - Retry: up to 3 attempts with 500ms back-off\n" +
                " *  - Locator resolution: role > placeholder > testId > label > css\n" +
                " */\n" +
                "public class PlaywrightUiActions implements UiActions {\n\n" +
                "    private static final long   TIMEOUT_MS   = Long.parseLong(\n" +
                "            System.getenv().getOrDefault(\"MCP_TIMEOUT_MS\", \"30000\"));\n" +
                "    private static final int    RETRY_MAX    = Integer.parseInt(\n" +
                "            System.getenv().getOrDefault(\"MCP_RETRY_MAX\", \"3\"));\n" +
                "    private static final long   RETRY_DELAY  = 500L;\n\n" +
                "    private final Page page;\n\n" +
                "    public PlaywrightUiActions(Page page) {\n" +
                "        this.page = page;\n" +
                "    }\n\n" +
                "    // ── Navigation ────────────────────────────────────────────────────\n\n" +
                "    @Override\n" +
                "    public void navigate(String url) {\n" +
                "        retry(() -> page.navigate(url));\n" +
                "        page.waitForLoadState(LoadState.LOAD);\n" +
                "        silently(() -> page.waitForLoadState(LoadState.NETWORKIDLE,\n" +
                "                new Page.WaitForLoadStateOptions().setTimeout(5000)));\n" +
                "    }\n\n" +
                "    // ── Interactions ──────────────────────────────────────────────────\n\n" +
                "    @Override\n" +
                "    public void click(String selector) {\n" +
                "        smartWait(selector, \"CLICK\");\n" +
                "        retry(() -> resolve(selector).click(\n" +
                "                new Locator.ClickOptions().setTimeout(TIMEOUT_MS)));\n" +
                "        silently(() -> page.waitForLoadState(LoadState.LOAD,\n" +
                "                new Page.WaitForLoadStateOptions().setTimeout(5000)));\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void doubleClick(String selector) {\n" +
                "        smartWait(selector, \"CLICK\");\n" +
                "        retry(() -> resolve(selector).dblclick());\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void fill(String selector, String value) {\n" +
                "        smartWait(selector, \"FILL\");\n" +
                "        retry(() -> { resolve(selector).clear(); resolve(selector).fill(value); });\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void type(String selector, String text) {\n" +
                "        smartWait(selector, \"FILL\");\n" +
                "        retry(() -> resolve(selector).type(text));\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void clear(String selector) {\n" +
                "        smartWait(selector, \"FILL\");\n" +
                "        retry(() -> resolve(selector).clear());\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void press(String selector, String key) {\n" +
                "        if (selector == null || selector.isBlank()) page.keyboard().press(key);\n" +
                "        else resolve(selector).press(key);\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void hover(String selector) {\n" +
                "        smartWait(selector, \"CLICK\");\n" +
                "        retry(() -> resolve(selector).hover());\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void selectOption(String selector, String value) {\n" +
                "        smartWait(selector, \"FILL\");\n" +
                "        retry(() -> resolve(selector).selectOption(value));\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void check(String selector) {\n" +
                "        smartWait(selector, \"CLICK\");\n" +
                "        retry(() -> resolve(selector).check());\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void uncheck(String selector) {\n" +
                "        smartWait(selector, \"CLICK\");\n" +
                "        retry(() -> resolve(selector).uncheck());\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void uploadFile(String selector, Path filePath) {\n" +
                "        resolve(selector).setInputFiles(filePath);\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void scroll(double deltaX, double deltaY) {\n" +
                "        page.mouse().wheel(deltaX, deltaY);\n" +
                "    }\n\n" +
                "    // ── Waits ─────────────────────────────────────────────────────────\n\n" +
                "    @Override\n" +
                "    public void waitForVisible(String selector) {\n" +
                "        page.waitForSelector(toBareCss(selector),\n" +
                "                new Page.WaitForSelectorOptions()\n" +
                "                        .setState(WaitForSelectorState.VISIBLE)\n" +
                "                        .setTimeout(TIMEOUT_MS));\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void waitForHidden(String selector) {\n" +
                "        page.waitForSelector(toBareCss(selector),\n" +
                "                new Page.WaitForSelectorOptions()\n" +
                "                        .setState(WaitForSelectorState.HIDDEN)\n" +
                "                        .setTimeout(TIMEOUT_MS));\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void waitForUrl(String urlPattern) {\n" +
                "        page.waitForURL(urlPattern, new Page.WaitForURLOptions().setTimeout(TIMEOUT_MS));\n" +
                "    }\n\n" +
                "    @Override\n" +
                "    public void waitForNetworkIdle() {\n" +
                "        page.waitForLoadState(LoadState.NETWORKIDLE,\n" +
                "                new Page.WaitForLoadStateOptions().setTimeout(TIMEOUT_MS));\n" +
                "    }\n\n" +
                "    // ── Screenshot ────────────────────────────────────────────────────\n\n" +
                "    @Override\n" +
                "    public void screenshot(String filePath) {\n" +
                "        page.screenshot(new Page.ScreenshotOptions()\n" +
                "                .setPath(Paths.get(filePath)).setFullPage(true));\n" +
                "    }\n\n" +
                "    // ── Inspection ────────────────────────────────────────────────────\n\n" +
                "    @Override public String  getText(String s)                     { return resolve(s).first().innerText(); }\n" +
                "    @Override public String  getValue(String s)                    { return resolve(s).first().inputValue(); }\n" +
                "    @Override public String  getAttribute(String s, String attr)   { return resolve(s).first().getAttribute(attr); }\n" +
                "    @Override public boolean isVisible(String s)                   { return resolve(s).first().isVisible(); }\n" +
                "    @Override public boolean isEnabled(String s)                   { return resolve(s).first().isEnabled(); }\n" +
                "    @Override public boolean isChecked(String s)                   { return resolve(s).first().isChecked(); }\n" +
                "    @Override public String  getCurrentUrl()                       { return page.url(); }\n" +
                "    @Override public String  getPageTitle()                        { return page.title(); }\n\n" +
                "    // ── Smart Wait ────────────────────────────────────────────────────\n\n" +
                "    private void smartWait(String selector, String actionType) {\n" +
                "        // 1. DOM ready\n" +
                "        silently(() -> page.waitForFunction(\n" +
                "                \"document.readyState === 'complete'\",\n" +
                "                new Page.WaitForFunctionOptions().setTimeout(TIMEOUT_MS)));\n" +
                "        if (selector == null || selector.isBlank()) return;\n" +
                "        // 2. Element visible\n" +
                "        String css = toBareCss(selector);\n" +
                "        silently(() -> page.waitForSelector(css,\n" +
                "                new Page.WaitForSelectorOptions()\n" +
                "                        .setState(WaitForSelectorState.VISIBLE)\n" +
                "                        .setTimeout(TIMEOUT_MS)));\n" +
                "        // 3. Element enabled (for inputs)\n" +
                "        if (\"FILL\".equals(actionType)) {\n" +
                "            silently(() -> page.waitForFunction(\n" +
                "                    \"sel => { const el = document.querySelector(sel); return el && !el.disabled; }\",\n" +
                "                    css, new Page.WaitForFunctionOptions().setTimeout(TIMEOUT_MS)));\n" +
                "        }\n" +
                "    }\n\n" +
                "    // ── Locator resolution ────────────────────────────────────────────\n\n" +
                "    private Locator resolve(String raw) {\n" +
                "        if (raw == null || raw.isBlank()) return page.locator(\"body\");\n" +
                "        String sel = raw.replaceAll(\"::nth=\\\\d+$\", \"\").trim();\n" +
                "        if (sel.startsWith(\"role:\")) {\n" +
                "            String[] p = sel.substring(5).split(\":\", 2);\n" +
                "            try {\n" +
                "                AriaRole r = AriaRole.valueOf(p[0].toUpperCase().replace(\"-\",\"_\").replace(\" \",\"_\"));\n" +
                "                return p.length > 1 && !p[1].isBlank()\n" +
                "                        ? page.getByRole(r, new Page.GetByRoleOptions().setName(p[1]))\n" +
                "                        : page.getByRole(r);\n" +
                "            } catch (IllegalArgumentException e) { return page.locator(\"[role='\" + p[0] + \"']\"); }\n" +
                "        }\n" +
                "        if (sel.startsWith(\"placeholder:\")) return page.getByPlaceholder(sel.substring(12));\n" +
                "        if (sel.startsWith(\"testid:\"))      return page.getByTestId(sel.substring(7));\n" +
                "        if (sel.startsWith(\"label:\"))       return page.getByLabel(sel.substring(6));\n" +
                "        if (sel.startsWith(\"text:\"))        return page.getByText(sel.substring(5),\n" +
                "                                                    new Page.GetByTextOptions().setExact(false));\n" +
                "        if (sel.startsWith(\"xpath:\"))       return page.locator(\"xpath=\" + sel.substring(6));\n" +
                "        return page.locator(sel);\n" +
                "    }\n\n" +
                "    private static String toBareCss(String sel) {\n" +
                "        if (sel == null) return \"body\";\n" +
                "        sel = sel.replaceAll(\"::nth=\\\\d+$\", \"\").trim();\n" +
                "        if (sel.startsWith(\"placeholder:\")) return \"[placeholder=\\\"\" + sel.substring(12) + \"\\\"]\";\n" +
                "        if (sel.startsWith(\"testid:\"))      return \"[data-testid=\\\"\" + sel.substring(7) + \"\\\"]\";\n" +
                "        if (sel.startsWith(\"role:\") || sel.startsWith(\"text:\") ||\n" +
                "            sel.startsWith(\"xpath:\") || sel.startsWith(\"label:\")) return \"body\";\n" +
                "        return sel;\n" +
                "    }\n\n" +
                "    // ── Retry ─────────────────────────────────────────────────────────\n\n" +
                "    @FunctionalInterface interface Action { void run() throws Exception; }\n\n" +
                "    private void retry(Action action) {\n" +
                "        Exception last = null;\n" +
                "        for (int i = 0; i <= RETRY_MAX; i++) {\n" +
                "            try { action.run(); return; } catch (Exception e) {\n" +
                "                last = e;\n" +
                "                if (i < RETRY_MAX) {\n" +
                "                    try { Thread.sleep(RETRY_DELAY); } catch (InterruptedException ie) {\n" +
                "                        Thread.currentThread().interrupt(); throw new RuntimeException(ie); }\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "        throw new RuntimeException(\"Action failed after \" + (RETRY_MAX + 1) + \" attempts\", last);\n" +
                "    }\n\n" +
                "    private void silently(Action action) {\n" +
                "        try { action.run(); } catch (Exception ignored) {}\n" +
                "    }\n" +
                "}\n";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  POM.XML
    // ═════════════════════════════════════════════════════════════════════════
    public static String pomXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.qa</groupId>\n" +
                "  <artifactId>playwright-bdd-framework</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "  <properties>\n" +
                "    <java.version>17</java.version>\n" +
                "    <maven.compiler.source>17</maven.compiler.source>\n" +
                "    <maven.compiler.target>17</maven.compiler.target>\n" +
                "    <playwright.version>1.44.0</playwright.version>\n" +
                "    <cucumber.version>7.18.0</cucumber.version>\n" +
                "    <allure.version>2.27.0</allure.version>\n" +
                "  </properties>\n" +
                "  <dependencies>\n" +
                "    <dependency><groupId>com.microsoft.playwright</groupId><artifactId>playwright</artifactId>\n" +
                "      <version>${playwright.version}</version></dependency>\n" +
                "    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-java</artifactId>\n" +
                "      <version>${cucumber.version}</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-junit-platform-engine</artifactId>\n" +
                "      <version>${cucumber.version}</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-testng</artifactId>\n" +
                "      <version>${cucumber.version}</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-suite</artifactId>\n" +
                "      <version>1.10.2</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>\n" +
                "      <version>5.10.2</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>org.testng</groupId><artifactId>testng</artifactId>\n" +
                "      <version>7.9.0</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>io.qameta.allure</groupId><artifactId>allure-cucumber7-jvm</artifactId>\n" +
                "      <version>${allure.version}</version><scope>test</scope></dependency>\n" +
                "    <dependency><groupId>io.qameta.allure</groupId><artifactId>allure-testng</artifactId>\n" +
                "      <version>${allure.version}</version><scope>test</scope></dependency>\n" +
                "  </dependencies>\n" +
                "  <build><plugins>\n" +
                "    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>\n" +
                "      <version>3.2.5</version>\n" +
                "      <configuration><systemPropertyVariables>\n" +
                "        <allure.results.directory>target/allure-results</allure.results.directory>\n" +
                "      </systemPropertyVariables></configuration>\n" +
                "    </plugin>\n" +
                "  </plugins></build>\n" +
                "</project>\n";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PRIVATE BUILDERS
    // ═════════════════════════════════════════════════════════════════════════

    /** Build field registry from raw events — called once in constructor.
     *  Stores the raw selector string (CSS/href/role prefix) as the locator constant value.
     *  This matches the ParabankAccountOpeningPage pattern: private final String FIELD = "css...";
     */
    private void buildFieldRegistry() {
        if (rawEvents == null) return;
        for (JsonNode e : rawEvents) {
            String at = e.path("actionType").asText();
            if (Set.of("FOCUS","NAVIGATE","SCROLL","HOVER").contains(at)) continue;
            JsonNode loc = e.path("locator");
            if (loc.isMissingNode() || loc.isNull()) continue;
            String fname = toFieldName(e);
            if (fname == null || fields.containsKey(fname)) continue;
            // Store the raw selector string (matching ParabankAccountOpeningPage pattern)
            String selectorStr = rawSelectorString(loc);
            String constantName = toConstantName(fname);
            fields.put(fname, new FieldEntry(selectorStr, at, e.path("inputValue").asText(""), constantName));
        }
    }

    /** Convert a locator node to a raw selector string suitable for a String constant.
     *  Prefers: href > cssSelector > id > xpath.
     *  This is used as the VALUE of the String constant in the Page Object.
     */
    private static String rawSelectorString(JsonNode loc) {
        String strategy = loc.path("strategy").asText("css");
        return switch (strategy) {
            case "href"    -> "a[href=\"" + loc.path("href").asText("") + "\"]";
            case "css-id"  -> loc.path("cssSelector").asText(loc.path("primary").asText(""));
            case "testId"  -> "[data-testid=\"" + loc.path("testId").asText() + "\"]";
            case "role"    -> loc.path("cssSelector").asText(loc.path("primary").asText(""));
            case "placeholder" -> "[placeholder=\"" + loc.path("placeholder").asText() + "\"]";
            default        -> loc.path("cssSelector").asText(loc.path("primary").asText("body"));
        };
    }

    /** Convert a camelCase field name to UPPER_SNAKE_CASE constant name. */
    private static String toConstantName(String camel) {
        if (camel == null || camel.isBlank()) return "ELEMENT";
        // Insert underscore before each uppercase letter, then uppercase everything
        return camel.replaceAll("([A-Z])", "_$1").toUpperCase().replaceAll("^_", "");
    }

    private String buildActionMethod(String fname, FieldEntry fe) {
        // Use UPPER_SNAKE_CASE constant name (e.g. USERNAME_FIELD) in the method body
        String constName = fe.constantName != null ? fe.constantName : toConstantName(fname);
        String jd = "    /**\n     * Perform " + fe.actionType.toLowerCase().replace("_"," ")
                + " on the '" + fname + "' element.\n";
        return switch (fe.actionType.toUpperCase()) {
            case "CLICK", "DOUBLE_CLICK" ->
                    jd + "     * @return this page (fluent)\n     */\n" +
                            "    public " + className + "Page click" + cap(fname) + "() {\n" +
                            "        ui.click(" + constName + ");\n" +
                            "        return this;\n    }\n";
            case "FILL" ->
                    jd + "     * @param value text to enter\n     * @return this page (fluent)\n     */\n" +
                            "    public " + className + "Page enter" + cap(fname) + "(String value) {\n" +
                            "        ui.fill(" + constName + ", value);\n" +
                            "        return this;\n    }\n";
            case "SELECT_OPTION" ->
                    jd + "     * @param value option value to select\n     * @return this page (fluent)\n     */\n" +
                            "    public " + className + "Page select" + cap(fname) + "(String value) {\n" +
                            "        ui.selectOption(" + constName + ", value);\n" +
                            "        return this;\n    }\n";
            case "CHECK" ->
                    jd + "     * @return this page (fluent)\n     */\n" +
                            "    public " + className + "Page check" + cap(fname) + "() {\n" +
                            "        ui.check(" + constName + "); return this;\n    }\n";
            case "UNCHECK" ->
                    jd + "     * @return this page (fluent)\n     */\n" +
                            "    public " + className + "Page uncheck" + cap(fname) + "() {\n" +
                            "        ui.uncheck(" + constName + "); return this;\n    }\n";
            case "UPLOAD_FILE" ->
                    jd + "     * @param filePath absolute path to file\n     * @return this page (fluent)\n     */\n" +
                            "    public " + className + "Page uploadTo" + cap(fname) + "(String filePath) {\n" +
                            "        ui.uploadFile(" + constName + ", java.nio.file.Paths.get(filePath));\n" +
                            "        return this;\n    }\n";
            default -> null;
        };
    }

    private String buildGetterMethods(String fname, String actionType) {
        if ("CLICK".equals(actionType)) return "";
        String constName = toConstantName(fname);
        return
                "    /** @return inner text of '" + fname + "' */\n" +
                        "    public String get" + cap(fname) + "Text()       { return ui.getText(" + constName + "); }\n\n" +
                        "    /** @return true if '" + fname + "' is visible */\n" +
                        "    public boolean is" + cap(fname) + "Visible()    { return ui.isVisible(" + constName + "); }\n\n" +
                        "    /** @return true if '" + fname + "' is enabled */\n" +
                        "    public boolean is" + cap(fname) + "Enabled()    { return ui.isEnabled(" + constName + "); }\n\n" +
                        "    /** Wait until '" + fname + "' is visible. */\n" +
                        "    public void waitFor" + cap(fname) + "Visible()   { ui.waitForVisible(" + constName + "); }\n\n";
    }

    private String buildCompositeMethod(IntentAnalyzer.Intent intent) {
        String sig  = intent.toMethodSignature(className);
        String name = intent.toMethodName();

        // Build the method body by calling per-field methods
        StringBuilder body = new StringBuilder();
        for (ObjectNode ev : intent.sourceEvents) {
            String at    = ev.path("actionType").asText();
            String fname = toFieldName(ev);
            if (fname == null) continue;
            switch (at) {
                case "FILL"          -> body.append("        enter").append(cap(fname)).append("(").append(toCamel(fname)).append("Value);\n");
                case "CLICK"         -> body.append("        click").append(cap(fname)).append("();\n");
                case "SELECT_OPTION" -> body.append("        select").append(cap(fname)).append("(").append(toCamel(fname)).append("Value);\n");
            }
        }

        return
                "    /**\n" +
                        "     * " + intent.description + "\n" +
                        "     * Intent: " + intent.type.name() + " — groups " + intent.sourceEvents.size() + " raw events.\n" +
                        "     */\n" +
                        "    " + sig + " {\n" +
                        body +
                        "        return this;\n    }\n\n";
    }

    private String buildStepImpl(IntentAnalyzer.Intent intent) {
        String po   = toCamel(className) + "Page";
        String meth = intent.toMethodName();

        return switch (intent.type) {
            case LOGIN ->
                    "    @When(\"user logs in with {string} and {string}\")\n" +
                            "    @Step(\"Login with user '{0}'\")\n" +
                            "    public void userLogsIn(String username, String password) {\n" +
                            "        " + po + ".login(username, password);\n    }\n";
            case SEARCH ->
                    "    @When(\"user searches for {string}\")\n" +
                            "    @Step(\"Search for '{0}'\")\n" +
                            "    public void userSearchesFor(String query) {\n" +
                            "        " + po + ".search(query);\n    }\n";
            case TRANSFER ->
                    "    @When(\"user transfers {string}\")\n" +
                            "    @Step(\"Transfer amount '{0}'\")\n" +
                            "    public void userTransfers(String amount) {\n" +
                            "        " + po + ".transfer(amount);\n    }\n";
            case FORM_SUBMIT -> {
                String params = intent.params.entrySet().stream()
                        .filter(e -> !e.getKey().equals("formName"))
                        .map(e -> "String " + toCamel(e.getKey()))
                        .reduce((a, b) -> a + ", " + b).orElse("");
                String args = intent.params.entrySet().stream()
                        .filter(e -> !e.getKey().equals("formName"))
                        .map(e -> toCamel(e.getKey()))
                        .reduce((a, b) -> a + ", " + b).orElse("");
                String formName = intent.params.getOrDefault("formName","form");
                yield "    @When(\"user submits the {string} form\")\n" +
                        "    @Step(\"Submit form'{0}'\")\n" +
                        "    public void userSubmitsForm(String formName) {\n" +
                        "        " + po + "." + meth + "(" + args + ");\n    }\n";
            }
            case NAVIGATION ->
                    "    @When(\"user navigates to {string}\")\n" +
                            "    @Step(\"Navigate to '{0}'\")\n" +
                            "    public void userNavigatesTo(String url) {\n" +
                            "        " + po + ".navigateTo(url);\n    }\n";
            case SELECT_FLOW ->
                    "    @When(\"user selects {string} from {string}\")\n" +
                            "    @Step(\"Select '{0}' from '{1}'\")\n" +
                            "    public void userSelects(String value, String field) {\n" +
                            "        " + po + "." + meth + "(value);\n    }\n";
            case UPLOAD_FLOW ->
                    "    @When(\"user uploads file {string}\")\n" +
                            "    @Step(\"Upload file '{0}'\")\n" +
                            "    public void userUploadsFile(String file) {\n" +
                            "        " + po + ".uploadFile(file);\n    }\n";
            case RAW_ACTION -> buildRawStepImpl(intent, po);
        };
    }

    private String buildRawStepImpl(IntentAnalyzer.Intent intent, String po) {
        if (intent.sourceEvents.isEmpty()) return null;
        ObjectNode ev = intent.sourceEvents.get(0);
        String at  = ev.path("actionType").asText();
        String loc = readableLabel(ev);
        String fname = toFieldName(ev);
        if (fname == null) return null;

        return switch (at) {
            case "CLICK" ->
                    "    @When(\"user clicks the {string} element\")\n" +
                            "    @Step(\"Click: {0}\")\n" +
                            "    public void userClicksThe" + cap(toCamel(loc)) + "(String element) {\n" +
                            "        " + po + ".click" + cap(fname) + "();\n    }\n";
            case "FILL" ->
                    "    @When(\"user enters {string} in the {string} field\")\n" +
                            "    @Step(\"Fill '{0}' into: {1}\")\n" +
                            "    public void userEntersInThe" + cap(toCamel(loc)) + "(String value, String field) {\n" +
                            "        " + po + ".enter" + cap(fname) + "(value);\n    }\n";
            case "PRESS_KEY" ->
                    "    @When(\"user presses the {string} key\")\n" +
                            "    @Step(\"Press key: {0}\")\n" +
                            "    public void userPressesTheKey(String key) { page.keyboard().press(key); }\n";
            default -> null;
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOCATOR HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private String scopedPwExpr(JsonNode loc) {
        String strategy  = loc.path("strategy").asText("css");
        int    nthIndex  = loc.path("nthIndex").asInt(0);
        int    matchCount= loc.path("matchCount").asInt(1);
        String pwExpr    = loc.path("playwrightLocator").asText("");

        if (pwExpr.isBlank()) {
            pwExpr = switch (strategy) {
                case "href"        -> "page.locator(\"a[href=\\\"" + esc(loc.path("href").asText()) + "\\\"]\")";
                case "css-id"      -> "page.locator(\"" + loc.path("cssSelector").asText() + "\")";
                case "testId"      -> "page.getByTestId(\"" + esc(loc.path("testId").asText()) + "\")";
                case "role"        -> "page.getByRole(AriaRole." +
                        loc.path("ariaRole").asText("BUTTON").toUpperCase().replace("-","_") +
                        ", new Page.GetByRoleOptions().setName(\"" + esc(loc.path("ariaLabel").asText()) + "\"))";
                case "placeholder" -> "page.getByPlaceholder(\"" + esc(loc.path("placeholder").asText()) + "\")";
                default            -> "page.locator(\"" + esc(loc.path("cssSelector").asText("body")) + "\")";
            };
        }
        boolean needsNth = matchCount > 1 && nthIndex > 0
                && !List.of("href","css-id","testId","xpath").contains(strategy);
        return needsNth ? pwExpr + ".nth(" + nthIndex + ")" : pwExpr;
    }

    private String toFieldName(JsonNode e) {
        if (e == null) return null;
        JsonNode loc = e.path("locator");
        if (loc.isMissingNode() || loc.isNull()) return null;
        String strategy = loc.path("strategy").asText("");
        String base = switch (strategy) {
            case "href"        -> loc.path("href").asText("").replaceAll(".*/(.*)", "$1").replace("-"," ");
            case "css-id"      -> loc.path("id").asText("");
            case "testId"      -> loc.path("testId").asText("");
            case "role"        -> loc.path("ariaLabel").asText(loc.path("text").asText(""));
            case "placeholder" -> loc.path("placeholder").asText("");
            default            -> loc.path("text").asText(loc.path("cssSelector").asText("element"));
        };
        if (base.isBlank()) base = "element";
        String at = e.path("actionType").asText("");
        String suffix = switch (at) {
            case "CLICK","DOUBLE_CLICK" -> {
                String tag = e.path("elementSnapshot").path("tagName").asText("");
                yield "button".equals(tag) ? "Button" : "a".equals(tag) ? "Link" : "Element";
            }
            case "FILL"          -> "Field";
            case "SELECT_OPTION" -> "Dropdown";
            case "CHECK","UNCHECK" -> "Checkbox";
            case "UPLOAD_FILE"   -> "Upload";
            default -> "Element";
        };
        String cleaned = base.replaceAll("[^a-zA-Z0-9 ]"," ").trim();
        if (cleaned.isBlank()) return null;
        return toCamel(cleaned) + suffix;
    }

    private String readableLabel(JsonNode e) {
        JsonNode loc = e.path("locator");
        if (loc.isMissingNode() || loc.isNull()) return "element";
        for (String f : new String[]{"ariaLabel","placeholder","text","id","href","testId"}) {
            String v = loc.path(f).asText("");
            if (!v.isBlank()) return v.substring(0, Math.min(v.length(), 50));
        }
        return loc.path("cssSelector").asText("element");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  STRING UTILITIES
    // ═════════════════════════════════════════════════════════════════════════
    private static String toPascal(String s) {
        if (s == null || s.isBlank()) return "Recorded";
        String[] p = s.replaceAll("[^a-zA-Z0-9 ]"," ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : p) if (!w.isBlank())
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        return sb.toString();
    }
    private static String toCamel(String s) {
        String p = toPascal(s);
        return p.isEmpty() ? "element" : Character.toLowerCase(p.charAt(0)) + p.substring(1);
    }
    private static String cap(String s) {
        return s == null || s.isBlank() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\","\\\\").replace("\"","\\\"");
    }
    private static String toSentence(String s) {
        if (s == null || s.isBlank()) return "complete the action";
        return Character.toLowerCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}