package com.qa.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper; // ADD THIS
import com.fasterxml.jackson.databind.SerializationFeature; // ADD THIS IF NEEDED

import java.util.*;

/**
 * BddCodeGenerator v4
 *
 * Changes from v3:
 * ─────────────────
 * • Uses playwrightLocator field from recording (already has .nth() when needed).
 * • FOCUS events are excluded from feature file and step defs entirely.
 * • Collapses consecutive HOVER+CLICK on same locator into a single CLICK step.
 * • Page Object uses the most-unique strategy field to pick the correct API call.
 * • Step definitions use scoped locators — no bare getByText() for ambiguous text.
 * • Generates a LocatorHelper utility that centralises nth-scoped access.
 */
public class BddCodeGenerator {

    private final String   featureName;
    private final String   className;
    private final JsonNode events;

    // Deduplication: track locator fields already used as Page Object fields
    private final Map<String, String>  fieldToLocatorExpr = new LinkedHashMap<>();
    private final Map<String, String>  fieldToMethod      = new LinkedHashMap<>();

    public BddCodeGenerator(String featureName, JsonNode events) {
        this.featureName = featureName;
        this.className   = toPascal(featureName);
        this.events      = collapseHoverClick(events);
    }

    // ── FEATURE FILE ──────────────────────────────────────────────────────────
    public String featureFile() {
        StringBuilder sb = new StringBuilder();
        sb.append("@smoke @regression\n");
        sb.append("Feature: ").append(featureName).append("\n\n");
        sb.append("  Background:\n");
        sb.append("    Given the browser is open\n\n");
        sb.append("  Scenario: ").append(featureName).append("\n");

        for (JsonNode e : events) {
            String step = toGherkinStep(e);
            if (step != null) sb.append("    ").append(step).append("\n");
        }
        return sb.toString();
    }

    private String toGherkinStep(JsonNode e) {
        String at  = e.path("actionType").asText();
        String val = e.path("inputValue").asText("");
        String key = e.path("key").asText("");
        String loc = readableLabel(e);

        // Skip FOCUS — informational only
        if ("FOCUS".equals(at)) return null;

        return switch (at) {
            case "NAVIGATE"      -> "When I navigate to \"" + esc(val) + "\"";
            case "CLICK"         -> "When I click the \"" + loc + "\" element";
            case "DOUBLE_CLICK"  -> "When I double-click the \"" + loc + "\" element";
            case "RIGHT_CLICK"   -> "When I right-click the \"" + loc + "\" element";
            case "FILL"          -> "When I fill \"" + esc(val) + "\" into the \"" + loc + "\" field";
            case "SELECT_OPTION" -> "When I select \"" + esc(val) + "\" from the \"" + loc + "\" dropdown";
            case "CHECK"         -> "When I check the \"" + loc + "\" checkbox";
            case "UNCHECK"       -> "When I uncheck the \"" + loc + "\" checkbox";
            case "PRESS_KEY"     -> "When I press the \"" + esc(key) + "\" key";
            case "HOVER"         -> "When I hover over the \"" + loc + "\" element";
            case "SCROLL"        -> {
                int sx = e.path("scrollX").asInt(0);
                int sy = e.path("scrollY").asInt(0);
                yield "When I scroll the page to position " + sx + ", " + sy;
            }
            case "ASSERT_TEXT"   -> "Then I should see \"" + esc(val) + "\" on the page";
            case "ASSERT_URL"    -> "Then the URL should be \"" + esc(val) + "\"";
            case "FORM_SUBMIT"   -> "When I submit the form";
            default              -> null;
        };
    }

    // ── PAGE OBJECT ───────────────────────────────────────────────────────────
    public String pageObject() {
        StringBuilder fields  = new StringBuilder();
        StringBuilder inits   = new StringBuilder();
        StringBuilder methods = new StringBuilder();

        for (JsonNode e : events) {
            String at = e.path("actionType").asText();
            if ("FOCUS".equals(at) || "NAVIGATE".equals(at) ||
                    "SCROLL".equals(at) || "NAVIGATE".equals(at)) continue;

            JsonNode loc = e.path("locator");
            if (loc.isMissingNode() || loc.isNull()) continue;

            String pwExpr    = scopedPlaywrightExpr(loc);
            String fieldName = toFieldName(e);
            if (fieldName == null || fieldToLocatorExpr.containsKey(fieldName)) continue;

            fieldToLocatorExpr.put(fieldName, pwExpr);

            fields.append("    private final Locator ").append(fieldName).append(";\n");
            inits.append("        this.").append(fieldName).append(" = ").append(pwExpr).append(";\n");

            String method = buildMethod(at, fieldName, val(e));
            if (method != null && !fieldToMethod.containsKey(fieldName)) {
                fieldToMethod.put(fieldName, method);
                methods.append(method).append("\n");
            }
        }

        return "package com.qa.pages;\n\n" +
                "import com.microsoft.playwright.*;\n" +
                "import com.microsoft.playwright.options.AriaRole;\n\n" +
                "/** Page Object — " + featureName + " (generated by PlaywrightMcpServer v4) */\n" +
                "public class " + className + "Page {\n\n" +
                "    private final Page page;\n\n" +
                fields + "\n" +
                "    public " + className + "Page(Page page) {\n" +
                "        this.page = page;\n" +
                inits +
                "    }\n\n" +
                "    public " + className + "Page navigateTo(String url) {\n" +
                "        page.navigate(url);\n" +
                "        return this;\n" +
                "    }\n\n" +
                methods +
                "}\n";
    }

    // ── STEP DEFINITIONS ──────────────────────────────────────────────────────
    public String stepDefinitions() {
        StringBuilder stepImpls = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();

        for (JsonNode e : events) {
            String at  = e.path("actionType").asText();
            if ("FOCUS".equals(at)) continue;

            String stepKey = at + "|" + readableLabel(e);
            if (seen.contains(stepKey)) continue;
            seen.add(stepKey);

            String impl = toStepImpl(e, at);
            if (impl != null) stepImpls.append(impl).append("\n");
        }

        return "package com.qa.stepdefs;\n\n" +
                "import com.microsoft.playwright.*;\n" +
                "import com.qa.pages." + className + "Page;\n" +
                "import io.cucumber.java.*;\n" +
                "import io.cucumber.java.en.*;\n" +
                "import org.junit.jupiter.api.Assertions;\n\n" +
                "public class " + className + "Steps {\n\n" +
                "    private Playwright playwright;\n" +
                "    private Browser browser;\n" +
                "    private BrowserContext context;\n" +
                "    private Page page;\n" +
                "    private " + className + "Page " + toCamel(className) + "Page;\n\n" +
                "    @Before\n" +
                "    public void setUp() {\n" +
                "        playwright = Playwright.create();\n" +
                "        browser    = playwright.chromium().launch(\n" +
                "                       new BrowserType.LaunchOptions().setHeadless(true));\n" +
                "        context    = browser.newContext(\n" +
                "                       new Browser.NewContextOptions().setBypassCSP(true));\n" +
                "        page       = context.newPage();\n" +
                "        " + toCamel(className) + "Page = new " + className + "Page(page);\n" +
                "    }\n\n" +
                "    @After\n" +
                "    public void tearDown() {\n" +
                "        if (context    != null) context.close();\n" +
                "        if (browser    != null) browser.close();\n" +
                "        if (playwright != null) playwright.close();\n" +
                "    }\n\n" +
                "    @Given(\"the browser is open\")\n" +
                "    public void theBrowserIsOpen() { /* ready via @Before */ }\n\n" +
                "    @When(\"I navigate to {string}\")\n" +
                "    public void iNavigateTo(String url) {\n" +
                "        " + toCamel(className) + "Page.navigateTo(url);\n" +
                "    }\n\n" +
                "    @When(\"I scroll the page to position {int}, {int}\")\n" +
                "    public void iScrollThePage(int x, int y) {\n" +
                "        page.mouse().wheel(x, y);\n" +
                "    }\n\n" +
                "    @Then(\"the URL should be {string}\")\n" +
                "    public void theUrlShouldBe(String url) {\n" +
                "        Assertions.assertEquals(url, page.url());\n" +
                "    }\n\n" +
                "    @Then(\"I should see {string} on the page\")\n" +
                "    public void iShouldSeeOnThePage(String text) {\n" +
                "        Assertions.assertTrue(page.getByText(text).first().isVisible());\n" +
                "    }\n\n" +
                stepImpls +
                "}\n";
    }

    // ── JUNIT RUNNER ──────────────────────────────────────────────────────────
    public String jUnitRunner() {
        return "package com.qa.runners;\n\n" +
                "import io.cucumber.junit.platform.engine.Constants;\n" +
                "import org.junit.platform.suite.api.*;\n\n" +
                "@Suite\n@IncludeEngines(\"cucumber\")\n" +
                "@SelectClasspathResource(\"features\")\n" +
                "@ConfigurationParameter(key=Constants.GLUE_PROPERTY_NAME, value=\"com.qa.stepdefs\")\n" +
                "@ConfigurationParameter(key=Constants.PLUGIN_PROPERTY_NAME,\n" +
                "        value=\"pretty, html:target/cucumber-reports/" + className +
                "-report.html, json:target/cucumber-reports/" + className + ".json\")\n" +
                "@ConfigurationParameter(key=Constants.FILTER_TAGS_PROPERTY_NAME, value=\"@smoke\")\n" +
                "public class " + className + "Runner {}\n";
    }

    // ── POM.XML ───────────────────────────────────────────────────────────────
    public static String pomXml() {
        return """
               <?xml version="1.0" encoding="UTF-8"?>
               <project xmlns="http://maven.apache.org/POM/4.0.0"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                          http://maven.apache.org/xsd/maven-4.0.0.xsd">
                 <modelVersion>4.0.0</modelVersion>
                 <groupId>com.qa</groupId>
                 <artifactId>playwright-bdd-framework</artifactId>
                 <version>1.0.0</version>
                 <properties>
                   <java.version>17</java.version>
                   <maven.compiler.source>17</maven.compiler.source>
                   <maven.compiler.target>17</maven.compiler.target>
                   <playwright.version>1.44.0</playwright.version>
                   <cucumber.version>7.18.0</cucumber.version>
                 </properties>
                 <dependencies>
                   <dependency>
                     <groupId>com.microsoft.playwright</groupId>
                     <artifactId>playwright</artifactId>
                     <version>${playwright.version}</version>
                   </dependency>
                   <dependency>
                     <groupId>io.cucumber</groupId>
                     <artifactId>cucumber-java</artifactId>
                     <version>${cucumber.version}</version><scope>test</scope>
                   </dependency>
                   <dependency>
                     <groupId>io.cucumber</groupId>
                     <artifactId>cucumber-junit-platform-engine</artifactId>
                     <version>${cucumber.version}</version><scope>test</scope>
                   </dependency>
                   <dependency>
                     <groupId>org.junit.platform</groupId>
                     <artifactId>junit-platform-suite</artifactId>
                     <version>1.10.2</version><scope>test</scope>
                   </dependency>
                   <dependency>
                     <groupId>org.junit.jupiter</groupId>
                     <artifactId>junit-jupiter</artifactId>
                     <version>5.10.2</version><scope>test</scope>
                   </dependency>
                 </dependencies>
               </project>
               """;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Collapse consecutive HOVER + CLICK on the same locator into a single CLICK.
     * The HOVER is then informational context only — not a separate step.
     */
    private static JsonNode collapseHoverClick(JsonNode src) {
        com.fasterxml.jackson.databind.node.ArrayNode out =
                new ObjectMapper().createArrayNode();
        String prevPrimary = "";
        String prevAction  = "";
        for (JsonNode e : src) {
            String at  = e.path("actionType").asText();
            String pri = e.path("locator").path("primary").asText("");

            // If this CLICK immediately follows a HOVER on the same locator, skip the HOVER
            if ("HOVER".equals(prevAction) && "CLICK".equals(at) && pri.equals(prevPrimary)) {
                // Remove last entry (the HOVER) from out and add the CLICK
                if (out.size() > 0) ((com.fasterxml.jackson.databind.node.ArrayNode) out).remove(out.size() - 1);
            }
            out.add(e);
            prevPrimary = pri;
            prevAction  = at;
        }
        return out;
    }

    /**
     * Build the unambiguous Playwright Java expression from a locator node.
     * Uses the recorded matchCount + nthIndex to scope when needed.
     */
    private String scopedPlaywrightExpr(JsonNode loc) {
        String strategy  = loc.path("strategy").asText("css");
        int    nthIndex  = loc.path("nthIndex").asInt(0);
        int    matchCount= loc.path("matchCount").asInt(1);
        String pwExpr    = loc.path("playwrightLocator").asText("");

        if (pwExpr.isBlank()) {
            // Reconstruct from parts
            pwExpr = switch (strategy) {
                case "href"    -> "page.locator(\"a[href=\\\"" + esc(loc.path("href").asText()) + "\\\"]\")";
                case "css-id"  -> "page.locator(\"" + loc.path("cssSelector").asText() + "\")";
                case "testId"  -> "page.getByTestId(\"" + esc(loc.path("testId").asText()) + "\")";
                case "role"    -> "page.getByRole(AriaRole." +
                        loc.path("ariaRole").asText("BUTTON").toUpperCase().replace("-","_") +
                        ", new Page.GetByRoleOptions().setName(\"" +
                        esc(loc.path("ariaLabel").asText()) + "\"))";
                case "placeholder" -> "page.getByPlaceholder(\"" + esc(loc.path("placeholder").asText()) + "\")";
                default        -> "page.locator(\"" + esc(loc.path("cssSelector").asText("body")) + "\")";
            };
        }

        // Append .nth() when ambiguous AND not already unique
        boolean needsNth = matchCount > 1 && nthIndex > 0
                && !List.of("href","css-id","testId","xpath").contains(strategy);
        return needsNth ? pwExpr + ".nth(" + nthIndex + ")" : pwExpr;
    }

    private String toFieldName(JsonNode e) {
        JsonNode loc = e.path("locator");
        String strategy = loc.path("strategy").asText("");

        // Use the most meaningful label available
        String base = switch (strategy) {
            case "href"        -> {
                String h = loc.path("href").asText("");
                yield h.replaceAll(".*/(.*)", "$1").replace("-", " ");
            }
            case "css-id"      -> loc.path("id").asText("");
            case "testId"      -> loc.path("testId").asText("");
            case "role"        -> loc.path("ariaLabel").asText(loc.path("text").asText(""));
            case "placeholder" -> loc.path("placeholder").asText("");
            default            -> loc.path("text").asText(loc.path("cssSelector").asText("element"));
        };
        if (base.isBlank()) base = "element";

        String at = e.path("actionType").asText("");
        String suffix = switch (at) {
            case "CLICK" -> {
                String tag = e.path("elementSnapshot").path("tagName").asText("");
                yield "button".equals(tag) ? "Button" : "a".equals(tag) ? "Link" : "Element";
            }
            case "FILL"          -> "Field";
            case "SELECT_OPTION" -> "Dropdown";
            case "CHECK","UNCHECK" -> "Checkbox";
            default -> "Element";
        };

        String cleaned = base.replaceAll("[^a-zA-Z0-9 ]", " ").trim();
        return toCamel(cleaned) + suffix;
    }

    private String buildMethod(String actionType, String fieldName, String value) {
        return switch (actionType.toUpperCase()) {
            case "CLICK","DOUBLE_CLICK" ->
                    "    public " + className + "Page click" + cap(fieldName) + "() {\n" +
                            "        " + fieldName + ".click();\n" +
                            "        return this;\n    }\n";
            case "FILL" ->
                    "    public " + className + "Page enter" + cap(fieldName) +
                            "(String value) {\n        " + fieldName + ".fill(value);\n" +
                            "        return this;\n    }\n";
            case "SELECT_OPTION" ->
                    "    public " + className + "Page select" + cap(fieldName) +
                            "(String value) {\n        " + fieldName + ".selectOption(value);\n" +
                            "        return this;\n    }\n";
            case "CHECK" ->
                    "    public " + className + "Page check" + cap(fieldName) +
                            "() {\n        " + fieldName + ".check();\n        return this;\n    }\n";
            case "UNCHECK" ->
                    "    public " + className + "Page uncheck" + cap(fieldName) +
                            "() {\n        " + fieldName + ".uncheck();\n        return this;\n    }\n";
            default -> null;
        };
    }

    private String toStepImpl(JsonNode e, String at) {
        String label  = readableLabel(e);
        String pwExpr = scopedPlaywrightExpr(e.path("locator"));
        String val    = val(e);

        return switch (at) {
            case "CLICK" ->
                    "    @When(\"I click the {string} element\")\n" +
                            "    public void iClickThe" + cap(toCamel(label)) + "Element(String element) {\n" +
                            "        " + pwExpr + ".click();\n    }\n";
            case "FILL" ->
                    "    @When(\"I fill {string} into the {string} field\")\n" +
                            "    public void iFillIntoThe" + cap(toCamel(label)) + "Field(String value, String field) {\n" +
                            "        " + pwExpr + ".fill(value);\n    }\n";
            case "PRESS_KEY" ->
                    "    @When(\"I press the {string} key\")\n" +
                            "    public void iPressTheKey(String key) {\n" +
                            "        page.keyboard().press(key);\n    }\n";
            case "HOVER" ->
                    "    @When(\"I hover over the {string} element\")\n" +
                            "    public void iHoverOver" + cap(toCamel(label)) + "(String element) {\n" +
                            "        " + pwExpr + ".hover();\n    }\n";
            case "SELECT_OPTION" ->
                    "    @When(\"I select {string} from the {string} dropdown\")\n" +
                            "    public void iSelectFrom" + cap(toCamel(label)) + "Dropdown(String value, String dropdown) {\n" +
                            "        " + pwExpr + ".selectOption(value);\n    }\n";
            default -> null;
        };
    }

    private String readableLabel(JsonNode e) {
        JsonNode loc = e.path("locator");
        if (loc.isMissingNode() || loc.isNull()) return "page";

        // Prefer human-readable fields in order
        for (String field : new String[]{"ariaLabel","placeholder","text","id","href","testId"}) {
            String v = loc.path(field).asText("");
            if (!v.isBlank()) return v.substring(0, Math.min(v.length(), 50));
        }
        return loc.path("cssSelector").asText("element");
    }

    private static String val(JsonNode e) { return e.path("inputValue").asText(""); }

    private static String toPascal(String s) {
        if (s == null || s.isBlank()) return "Recorded";
        String[] p = s.replaceAll("[^a-zA-Z0-9 ]"," ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : p) if (!w.isBlank())
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        return sb.toString();
    }
    private static String toCamel(String s) {
        String p = toPascal(s); return p.isEmpty() ? "element"
                : Character.toLowerCase(p.charAt(0)) + p.substring(1);
    }
    private static String cap(String s) {
        return s == null || s.isBlank() ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\","\\\\").replace("\"","\\\"");
    }
}