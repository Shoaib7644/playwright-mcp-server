package com.qa.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * BddCodeGenerator
 *
 * Given a feature name and a parsed JSON recording, produces:
 *   • Gherkin Feature file
 *   • Cucumber Step Definitions (Java)
 *   • Page Object Model class  (Java)
 *   • JUnit 5 Platform Runner
 *   • pom.xml (static helper)
 */
public class BddCodeGenerator {

    private final String      featureName;
    private final JsonNode    steps;
    private final String      className;   // e.g. "UserLogin"
    private final String      varName;     // e.g. "userLogin"
    private final List<Step>  parsed;

    public BddCodeGenerator(String featureName, JsonNode steps) {
        this.featureName = featureName;
        this.steps       = steps;
        this.className   = toCamelCase(featureName, true);
        this.varName     = toCamelCase(featureName, false);
        this.parsed      = parseSteps(steps);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  FEATURE FILE
    // ─────────────────────────────────────────────────────────────────────────

    public String featureFile() {
        var sb = new StringBuilder();
        sb.append("Feature: ").append(featureName).append("\n\n");
        sb.append("  As a user\n");
        sb.append("  I want to ").append(featureName.toLowerCase()).append("\n");
        sb.append("  So that I can complete my task successfully\n\n");
        sb.append("  Scenario: ").append(featureName).append("\n");

        boolean firstNav = true;
        int stepNum = 0;
        for (Step s : parsed) {
            String keyword = stepNum == 0 ? "Given" : (s.action.equals("navigate") ? "When" : "And");
            switch (s.action) {
                case "navigate" -> {
                    if (firstNav) {
                        sb.append("    Given the user opens \"").append(s.value).append("\"\n");
                        firstNav = false;
                    } else {
                        sb.append("    When  the user navigates to \"").append(s.value).append("\"\n");
                    }
                }
                case "fill", "type" ->
                        sb.append("    And   the user fills \"").append(humanLabel(s.selector))
                                .append("\" with \"").append(s.value).append("\"\n");
                case "click", "getbytext", "click_text" ->
                        sb.append("    And   the user clicks \"").append(humanLabel(s.selector)).append("\"\n");
                case "select_option" ->
                        sb.append("    And   the user selects \"").append(s.value)
                                .append("\" from \"").append(humanLabel(s.selector)).append("\"\n");
                case "submit" ->
                        sb.append("    And   the user submits the form\n");
                case "check" ->
                        sb.append("    And   the user checks \"").append(humanLabel(s.selector)).append("\"\n");
                default ->
                        sb.append("    And   the user performs ").append(s.action)
                                .append(" on \"").append(humanLabel(s.selector)).append("\"\n");
            }
            stepNum++;
        }
        sb.append("    Then  the action completes successfully\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  STEP DEFINITIONS
    // ─────────────────────────────────────────────────────────────────────────

    public String stepDefinitions() {
        var sb = new StringBuilder();
        sb.append("package com.qa.steps;\n\n");
        sb.append("import com.qa.pages.").append(className).append("Page;\n");
        sb.append("import io.cucumber.java.en.*;\n");
        sb.append("import io.cucumber.java.After;\n");
        sb.append("import io.cucumber.java.Before;\n");
        sb.append("import com.microsoft.playwright.*;\n\n");

        sb.append("public class ").append(className).append("Steps {\n\n");
        sb.append("    private Playwright playwright;\n");
        sb.append("    private Browser browser;\n");
        sb.append("    private Page page;\n");
        sb.append("    private ").append(className).append("Page ").append(varName).append("Page;\n\n");

        // @Before
        sb.append("    @Before\n");
        sb.append("    public void setUp() {\n");
        sb.append("        playwright  = Playwright.create();\n");
        sb.append("        browser     = playwright.chromium().launch(\n");
        sb.append("            new BrowserType.LaunchOptions().setHeadless(false));\n");
        sb.append("        page        = browser.newPage();\n");
        sb.append("        ").append(varName).append("Page = new ").append(className).append("Page(page);\n");
        sb.append("    }\n\n");

        // @After
        sb.append("    @After\n");
        sb.append("    public void tearDown() {\n");
        sb.append("        if (page     != null) page.close();\n");
        sb.append("        if (browser  != null) browser.close();\n");
        sb.append("        if (playwright != null) playwright.close();\n");
        sb.append("    }\n\n");

        // Deduplicate step definitions (same Gherkin text could appear twice)
        Set<String> seen = new LinkedHashSet<>();

        for (Step s : parsed) {
            String stepDef = buildStepDef(s);
            if (stepDef != null && seen.add(stepDef)) {
                sb.append(stepDef).append("\n");
            }
        }

        // Catch-all "Then" assertion
        sb.append("    @Then(\"the action completes successfully\")\n");
        sb.append("    public void verifySuccess() {\n");
        sb.append("        // TODO: add explicit assertions, e.g.\n");
        sb.append("        // org.junit.jupiter.api.Assertions.assertTrue(page.title().length() > 0);\n");
        sb.append("        System.out.println(\"[PASS] Scenario completed — page title: \" + page.title());\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PAGE OBJECT
    // ─────────────────────────────────────────────────────────────────────────

    public String pageObject() {
        var sb = new StringBuilder();
        sb.append("package com.qa.pages;\n\n");
        sb.append("import com.microsoft.playwright.*;\n");
        sb.append("import com.microsoft.playwright.options.AriaRole;\n\n");

        sb.append("/**\n");
        sb.append(" * Page Object for: ").append(featureName).append("\n");
        sb.append(" * Auto-generated by PlaywrightMcpServer — review selectors before production use.\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append("Page {\n\n");
        sb.append("    private final Page page;\n\n");

        // Field declarations from recorded selectors
        Set<String> fieldNames = new LinkedHashSet<>();
        for (Step s : parsed) {
            if (!s.selector.isBlank() && !s.action.equals("navigate")) {
                String field = toFieldName(s.selector);
                if (fieldNames.add(field)) {
                    sb.append("    // Selector: ").append(s.selector).append("\n");
                    sb.append("    private final Locator ").append(field).append(";\n\n");
                }
            }
        }

        // Constructor
        sb.append("    public ").append(className).append("Page(Page page) {\n");
        sb.append("        this.page = page;\n");
        fieldNames.clear();
        for (Step s : parsed) {
            if (!s.selector.isBlank() && !s.action.equals("navigate")) {
                String field = toFieldName(s.selector);
                if (fieldNames.add(field)) {
                    sb.append("        this.").append(field)
                            .append(" = ").append(buildLocatorInit(s.selector)).append(";\n");
                }
            }
        }
        sb.append("    }\n\n");

        // navigate()
        for (Step s : parsed) {
            if (s.action.equals("navigate")) {
                sb.append("    public void navigate() {\n");
                sb.append("        page.navigate(\"").append(s.value).append("\");\n");
                sb.append("    }\n\n");
                break;
            }
        }

        // Action methods  (deduplicated)
        Set<String> methodSigs = new LinkedHashSet<>();
        for (Step s : parsed) {
            if (s.selector.isBlank() || s.action.equals("navigate")) continue;
            String field  = toFieldName(s.selector);
            String method = buildMethodCode(s, field);
            if (method != null && methodSigs.add(field + "::" + s.action)) {
                sb.append(method).append("\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  JUNIT RUNNER
    // ─────────────────────────────────────────────────────────────────────────

    public String jUnitRunner() {
        return """
                package com.qa.runner;

                import org.junit.platform.suite.api.*;
                import static io.cucumber.junit.platform.engine.Constants.*;

                @Suite
                @IncludeEngines("cucumber")
                @SelectClasspathResource("features")
                @ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
                    value = "pretty, html:target/cucumber-reports/report.html")
                @ConfigurationParameter(key = GLUE_PROPERTY_NAME,
                    value = "com.qa.steps")
                public class CucumberTestRunner {
                    // JUnit Platform launcher — no body needed
                }
                """;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  POM.XML (static)
    // ─────────────────────────────────────────────────────────────────────────

    public static String pomXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>

                  <groupId>com.qa</groupId>
                  <artifactId>playwright-bdd-framework</artifactId>
                  <version>1.0.0-SNAPSHOT</version>
                  <packaging>jar</packaging>

                  <properties>
                    <java.version>17</java.version>
                    <maven.compiler.source>${java.version}</maven.compiler.source>
                    <maven.compiler.target>${java.version}</maven.compiler.target>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

                    <!-- Dependency versions -->
                    <playwright.version>1.44.0</playwright.version>
                    <cucumber.version>7.18.0</cucumber.version>
                    <junit.platform.version>1.10.2</junit.platform.version>
                    <junit.jupiter.version>5.10.2</junit.jupiter.version>
                    <jackson.version>2.17.1</jackson.version>
                    <mcp.version>0.9.0</mcp.version>
                  </properties>

                  <dependencies>

                    <!-- ── Playwright Java ──────────────────────────────────── -->
                    <dependency>
                      <groupId>com.microsoft.playwright</groupId>
                      <artifactId>playwright</artifactId>
                      <version>${playwright.version}</version>
                    </dependency>

                    <!-- ── MCP Java SDK (stdio transport) ───────────────────── -->
                    <dependency>
                      <groupId>io.modelcontextprotocol.sdk</groupId>
                      <artifactId>mcp</artifactId>
                      <version>${mcp.version}</version>
                    </dependency>

                    <!-- ── Cucumber / BDD ───────────────────────────────────── -->
                    <dependency>
                      <groupId>io.cucumber</groupId>
                      <artifactId>cucumber-java</artifactId>
                      <version>${cucumber.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>io.cucumber</groupId>
                      <artifactId>cucumber-junit-platform-engine</artifactId>
                      <version>${cucumber.version}</version>
                    </dependency>

                    <!-- ── JUnit 5 ──────────────────────────────────────────── -->
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter-api</artifactId>
                      <version>${junit.jupiter.version}</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.junit.platform</groupId>
                      <artifactId>junit-platform-suite</artifactId>
                      <version>${junit.platform.version}</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.junit.platform</groupId>
                      <artifactId>junit-platform-launcher</artifactId>
                      <version>${junit.platform.version}</version>
                      <scope>test</scope>
                    </dependency>

                    <!-- ── Jackson (JSON) ───────────────────────────────────── -->
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                      <version>${jackson.version}</version>
                    </dependency>

                  </dependencies>

                  <build>
                    <plugins>

                      <!-- Compiler -->
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <version>3.13.0</version>
                        <configuration>
                          <release>17</release>
                        </configuration>
                      </plugin>

                      <!-- Surefire — runs JUnit Platform (Cucumber) tests -->
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <version>3.2.5</version>
                        <configuration>
                          <includes>
                            <include>**/CucumberTestRunner.class</include>
                          </includes>
                        </configuration>
                      </plugin>

                      <!-- Exec — run the MCP server directly -->
                      <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>exec-maven-plugin</artifactId>
                        <version>3.3.0</version>
                        <configuration>
                          <mainClass>com.qa.mcp.PlaywrightMcpServer</mainClass>
                        </configuration>
                      </plugin>

                      <!-- Playwright install browsers during build -->
                      <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>exec-maven-plugin</artifactId>
                        <version>3.3.0</version>
                        <executions>
                          <execution>
                            <id>install-browsers</id>
                            <phase>generate-resources</phase>
                            <goals><goal>java</goal></goals>
                            <configuration>
                              <mainClass>com.microsoft.playwright.CLI</mainClass>
                              <arguments>
                                <argument>install</argument>
                                <argument>chromium</argument>
                              </arguments>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>

                    </plugins>
                  </build>

                </project>
                """;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private record Step(String action, String selector, String value, String timestamp) {}

    private List<Step> parseSteps(JsonNode node) {
        var list = new ArrayList<Step>();
        if (node.isArray()) {
            for (var s : node) {
                list.add(new Step(
                        s.path("action").asText("unknown"),
                        s.path("selector").asText(""),
                        s.path("value").asText(""),
                        s.path("timestamp").asText("")));
            }
        }
        return list;
    }

    /** Builds the Java step definition method for a recorded step. */
    private String buildStepDef(Step s) {
        var sb = new StringBuilder();
        String label = humanLabel(s.selector);
        switch (s.action) {
            case "navigate" -> {
                sb.append("    @Given(\"the user opens {string}\")\n");
                sb.append("    public void openUrl(String url) {\n");
                sb.append("        ").append(varName).append("Page.navigate();\n");
                sb.append("    }\n");
            }
            case "fill", "type" -> {
                sb.append("    @And(\"the user fills {string} with {string}\")\n");
                sb.append("    public void fillField(String field, String value) {\n");
                sb.append("        ").append(varName).append("Page.fill")
                        .append(toCamelCase(label, true)).append("(value);\n");
                sb.append("    }\n");
            }
            case "click", "getbytext", "click_text" -> {
                sb.append("    @And(\"the user clicks {string}\")\n");
                sb.append("    public void clickElement(String element) {\n");
                sb.append("        ").append(varName).append("Page.click")
                        .append(toCamelCase(label, true)).append("();\n");
                sb.append("    }\n");
            }
            case "select_option" -> {
                sb.append("    @And(\"the user selects {string} from {string}\")\n");
                sb.append("    public void selectOption(String option, String dropdown) {\n");
                sb.append("        ").append(varName).append("Page.select")
                        .append(toCamelCase(label, true)).append("(option);\n");
                sb.append("    }\n");
            }
            case "check" -> {
                sb.append("    @And(\"the user checks {string}\")\n");
                sb.append("    public void checkElement(String element) {\n");
                sb.append("        ").append(varName).append("Page.check")
                        .append(toCamelCase(label, true)).append("();\n");
                sb.append("    }\n");
            }
            case "submit" -> {
                sb.append("    @And(\"the user submits the form\")\n");
                sb.append("    public void submitForm() {\n");
                sb.append("        ").append(varName).append("Page.submitForm();\n");
                sb.append("    }\n");
            }
            default -> { return null; }
        }
        return sb.toString();
    }

    /** Generates the Locator field initializer using semantic locators. */
    private String buildLocatorInit(String selector) {
        if (selector.startsWith("role:")) {
            String[] parts = selector.substring(5).split(":", 2);
            String role = parts[0].toUpperCase();
            if (parts.length > 1) {
                return "page.getByRole(AriaRole." + role +
                        ", new Page.GetByRoleOptions().setName(\"" + escape(parts[1]) + "\"))";
            }
            return "page.getByRole(AriaRole." + role + ")";
        }
        if (selector.startsWith("text:"))
            return "page.getByText(\"" + escape(selector.substring(5)) + "\")";
        if (selector.startsWith("placeholder:"))
            return "page.getByPlaceholder(\"" + escape(selector.substring(12)) + "\")";
        if (selector.startsWith("label:"))
            return "page.getByLabel(\"" + escape(selector.substring(6)) + "\")";
        if (selector.startsWith("testid:"))
            return "page.getByTestId(\"" + escape(selector.substring(7)) + "\")";
        return "page.locator(\"" + escape(selector) + "\")";
    }

    private String buildMethodCode(Step s, String field) {
        return switch (s.action) {
            case "click", "getbytext", "click_text" ->
                    "    public void click" + toCamelCase(humanLabel(s.selector), true) + "() {\n" +
                            "        " + field + ".click();\n    }\n";
            case "fill", "type" ->
                    "    public void fill" + toCamelCase(humanLabel(s.selector), true) + "(String value) {\n" +
                            "        " + field + ".fill(value);\n    }\n";
            case "check" ->
                    "    public void check" + toCamelCase(humanLabel(s.selector), true) + "() {\n" +
                            "        " + field + ".check();\n    }\n";
            case "select_option" ->
                    "    public void select" + toCamelCase(humanLabel(s.selector), true) + "(String option) {\n" +
                            "        " + field + ".selectOption(option);\n    }\n";
            case "submit" ->
                    "    public void submitForm() {\n" +
                            "        page.locator(\"form\").first().evaluate(\"f => f.submit()\");\n    }\n";
            default -> null;
        };
    }

    /** Derives a readable field name from a raw selector string. */
    private String toFieldName(String selector) {
        String label = humanLabel(selector);
        return toCamelCase(label, false) + "Locator";
    }

    /** Strips selector prefixes and returns a human-readable label. */
    private String humanLabel(String selector) {
        for (String prefix : List.of("role:", "text:", "placeholder:", "label:", "testid:")) {
            if (selector.startsWith(prefix)) {
                String rest = selector.substring(prefix.length());
                // For role selectors strip the role part, keep name
                if (prefix.equals("role:")) {
                    int colon = rest.indexOf(':');
                    if (colon >= 0) rest = rest.substring(colon + 1);
                }
                return rest.trim();
            }
        }
        // CSS/XPath — extract last meaningful token
        String[] parts = selector.split("[>\\s]+");
        return parts[parts.length - 1].replaceAll("[#.:\\[\\]()='\"]", " ").trim();
    }

    private static String toCamelCase(String input, boolean capitalizeFirst) {
        if (input == null || input.isBlank()) return "element";
        String[] words = input.trim().replaceAll("[^a-zA-Z0-9 ]", " ").split("\\s+");
        var sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) continue;
            if (i == 0 && !capitalizeFirst) {
                sb.append(w.toLowerCase());
            } else {
                sb.append(Character.toUpperCase(w.charAt(0)))
                        .append(w.substring(1).toLowerCase());
            }
        }
        return sb.isEmpty() ? "element" : sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
