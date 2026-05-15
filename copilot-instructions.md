# Playwright MCP Server — Copilot Agent Instructions
<!--
  FILE LOCATION — place at:  <project-root>/.github/copilot-instructions.md
  Auto-read by: VS Code Copilot · IntelliJ AI Assistant · Eclipse Copilot
  KEEP CONCISE — every line loads into every chat context.
-->

---

## 1. Server Identity

| Property | Value |
|---|---|
| Name | `playwright-mcp-server` |
| Version | `6.0.0` · Schema `6.0.0` |
| Transport | stdio (JSON-RPC 2.0) |
| Tools | `start_recording` · `stop_recording` · `playback_recording` · `generate_playwright_bdd` · `execute_atomic_action` |

---

## 2. Short-form Commands (always use these — saves 80% tokens)

| Type this | Expands to |
|---|---|
| `record "<name>" on <url>` | `start_recording url=<url> sessionName=<name>` |
| `stop` | `stop_recording` |
| `replay <json>` | `playback_recording jsonRecording=<json>` |
| `bdd "<name>"` | `generate_playwright_bdd featureName=<name>` using last recording |
| `bdd "<name>" framework=testng` | same + TestNG runner |
| `bdd "<name>" tags="@smoke @e2e"` | same + custom tags |
| `click <selector>` | `execute_atomic_action action=click selector=<selector>` |
| `fill <selector> with <value>` | `execute_atomic_action action=fill selector=<selector> value=<value>` |
| `screenshot` | `execute_atomic_action action=screenshot value=./screenshot.png` |

---

## 3. Recording Workflow — Exact Steps

```
1. start_recording  url="<url>"  sessionName="<Feature - Scenario>"
2. [User interacts with browser — call NO tools during this phase]
3. stop_recording   → returns JSON with events + detected intents
4. Save JSON to ./recordings/<name>.json
```

**Hard rules:**
- Never call any tool between `start_recording` and `stop_recording`
- Never navigate programmatically during recording — user drives the browser
- Always pass `sessionName` — it becomes the BDD feature and scenario name
- Always use `headless: false` during recording (user must see the browser)

---

## 4. Locator Priority (v6 engine — highest to lowest)

1. `href`        → `page.locator("a[href='/path']")` — nav links, always unique
2. `css-id`      → `page.locator("#escaped-id")`
3. `testId`      → `page.getByTestId("...")`
4. `xpath`       → `page.locator("xpath=...")`
5. `role`        → `page.getByRole(AriaRole.X, …setName("…")).nth(n)`
6. `placeholder` → `page.getByPlaceholder("...")`
7. `css`         → `page.locator("nth-scoped > path").nth(n)` if `matchCount > 1`
8. `text`        → `page.getByText("…").nth(n)` — last resort only

Always read `locator.playwrightLocator` from recording JSON.  
Never use bare `page.getByText()` without `.nth()` when `matchCount > 1`.

---

## 5. Playback Rules

- `FOCUS` events → **skip** (informational — never replay)
- `HOVER` immediately before `CLICK` on same locator → skip the HOVER
- After `NAVIGATE` → `page.waitForLoadState()` before next action
- `matchCount > 1` → `.nth(locator.nthIndex)` scoping
- On any failure → screenshot immediately, then attempt `recoverPageState()`
- Max step delay: **500ms** — never use human recording timing (old cap was 2500ms)

---

## 6. BDD Generation Rules

### 6.1 Generate exactly 3 files — nothing else

```
src/test/resources/features/<Name>.feature
src/test/java/com/qa/pages/<Name>Page.java
src/test/java/com/qa/stepdefs/<Name>Steps.java
```

If a Page Object or Steps class already exists → append new methods only, do not replace the whole file.

### 6.2 Files that must NEVER be generated or overwritten

| File | Why |
|---|---|
| `pom.xml` | Stable — never touch |
| `UiActions.java` | Exists at `com.qa.actions` — never regenerate |
| `PlaywrightUiActions.java` | Exists — never regenerate |
| Any `*Runner.java` | Two runners cover all features — never generate per-feature runners |
| Any `*.properties` | One `test.properties` for whole project — never generate per-feature |
| Any `*.md` | Not code — never generate |

### 6.3 Feature file rules

- Default tags: `@smoke @regression` on every Scenario
- Use `Scenario Outline` + `Examples` table when `dataRows` param is supplied
- `FOCUS` events → no Gherkin step generated
- Consecutive `HOVER` + `CLICK` on same element → single `When I click` step
- Never hardcode passwords in Gherkin — use `"<password>"` placeholder

### 6.4 Page Object rules — CRITICAL

**Correct pattern (matches existing `ParabankAccountOpeningPage` and `EcommerceworkflowPage`):**

```java
// ✅ CORRECT — String constants, matches existing framework
private final String USERNAME_FIELD = "#loginPanel > form > div:nth-of-type(1) > input";
private final String LOGIN_BUTTON   = "a[href='/login']";

public LoginPage enterUsername(String value) {
    ui.fill(USERNAME_FIELD, value);   // delegates to UiActions
    return this;
}
```

```java
// ❌ WRONG — Locator object fields, do NOT generate this
private final Locator usernameField = page.getByPlaceholder("Email");
```

Additional rules:
- Constructor: `public <Name>Page(Page page, UiActions ui)` — always both args
- `waitForPageLoad()` → calls `ui.waitForNetworkIdle()`
- `getCurrentUrl()` → calls `ui.getCurrentUrl()`
- `getPageTitle()` → calls `ui.getPageTitle()`
- Getter methods: `getText()`, `isVisible()`, `isEnabled()`, `waitForVisible()`
- Never put assertions inside Page Objects
- Full JavaDoc on every public method

### 6.5 Step Definition rules — CRITICAL

**No `@Before` / `@After` ever — these live in `Hooks.java` only.**  
**No browser lifecycle code — managed centrally by `Hooks.java`.**

Correct skeleton:
```java
public class <Name>Steps {
    private final ScenarioContext context;
    private final <Name>Page      <name>Page;

    // Cucumber injects shared context via constructor (PicoContainer)
    public <Name>Steps(ScenarioContext context) {
        this.context   = context;
        this.<name>Page = new <Name>Page(context.getPage(), context.getUiActions());
    }

    // Only @Given / @When / @Then / @And here — NEVER @Before or @After
}
```

Additional rules:
- All UI actions delegate to `<name>Page` — no direct Playwright calls in steps
- Test data via `TestConfig.get("key")` — never inline `getProperty()` in steps
- Assertions: `Assertions.assertTrue(condition, "descriptive failure message")`
- `@Step(Allure)` annotation on every step method
- `@When("user clicks the {string} button")` style for reusable steps

### 6.6 Intent → Gherkin mapping

The MCP server detects these patterns. One Intent = one Gherkin step, not one per DOM event.

| Intent detected | Gherkin step generated |
|---|---|
| `LOGIN` | `When user logs in with "<username>" and "<password>"` |
| `SEARCH` | `When user searches for "<query>"` |
| `TRANSFER` | `When user transfers "<amount>"` |
| `FORM_SUBMIT` | `When user submits the "<formName>" form` |
| `NAVIGATION` | `When user navigates to "<url>"` |
| `SELECT_FLOW` | `When user selects "<value>" from "<field>"` |
| `UPLOAD_FLOW` | `When user uploads file "<path>"` |
| `RAW_ACTION` | `When user clicks the "<element>" element` |

---

## 7. Framework Architecture — Read-Only Reference

```
src/test/
├── java/com/qa/
│   ├── actions/
│   │   ├── UiActions.java                ← 21-method interface (NEVER regenerate)
│   │   └── PlaywrightUiActions.java      ← SmartWait + Retry (NEVER regenerate)
│   ├── pages/                            ← one Page Object per app page
│   │   ├── ParabankAccountOpeningPage.java
│   │   └── EcommerceworkflowPage.java
│   ├── runners/                          ← EXACTLY 2 runners total
│   │   ├── SmokeTestRunner.java          ← @smoke, all features
│   │   └── RegressionTestRunner.java     ← @regression, all features
│   ├── stepdefs/
│   │   ├── ScenarioContext.java          ← shared Page + UiActions per scenario
│   │   ├── Hooks.java                    ← @Before / @After ONLY HERE
│   │   ├── ParabankAccountOpeningSteps.java
│   │   └── EcommerceworkflowSteps.java
│   └── utils/
│       └── TestConfig.java               ← single config reader, all features
└── resources/
    ├── features/                         ← all .feature files
    └── TestData/
        └── test.properties               ← ONE file, namespaced by module
```

### Shared class responsibilities

**`ScenarioContext`** — PicoContainer-injected shared state. Holds `Playwright`, `Browser`, `BrowserContext`, `Page`, `UiActions`. One instance per scenario.

**`Hooks.java`** — the only class with `@Before`/`@After`. `@Before` creates browser + page + context, stores in `ScenarioContext`. `@After` takes failure screenshot (if `scenario.isFailed()`), closes context → browser → playwright.

**`TestConfig.get("key")`** — reads `TestData/test.properties`, falls back to env var (`KEY_NAME` → `key.name`), then to provided default. Never duplicate this logic in Steps classes.

### test.properties namespace convention

```properties
# ── URLs ────────────────────────────────────────
app.parabank.url=https://parabank.parasoft.com/parabank/index.htm
app.ecommerce.url=https://automationexercise.com/login

# ── ParaBank ─────────────────────────────────────
parabank.username=sahmed9
parabank.password=${PARABANK_PASSWORD}

# ── ECommerce ────────────────────────────────────
ecommerce.email=sahmed9@mailinator.com
ecommerce.password=${ECOMMERCE_PASSWORD}
ecommerce.card.name=John Doe
ecommerce.card.number=345262728
ecommerce.card.cvc=311
ecommerce.card.expiry.month=05
ecommerce.card.expiry.year=2027

# ── Browser / Timeouts ───────────────────────────
browser.headless=true
timeout.default=30000
retry.max=3
```

---

## 8. SmartWait Engine (v6 — built-in, no manual waits needed)

| Action | Pre-wait applied automatically |
|---|---|
| `CLICK` | DOM ready → element visible |
| `FILL` | DOM ready → element visible → element enabled |
| `SELECT` | DOM ready → element visible → element enabled |
| `NAVIGATE` | Post: LOAD + NETWORKIDLE |
| `UPLOAD_FILE` | DOM ready → element attached |

Never add `Thread.sleep()` or `page.waitForTimeout()`.  
Add `ui.waitForVisible(selector)` only when AJAX content appears after an action.

---

## 9. MCP Server Environment Variables

```bash
MCP_TIMEOUT_MS=30000        # per-action timeout (60000 for slow sites)
MCP_RETRY_MAX=3             # retry attempts on transient failure
MCP_RETRY_DELAY_MS=800      # ms between retries
MCP_MAX_STEP_DELAY_MS=500   # max playback step delay
MCP_LOG_LEVEL=INFO          # DEBUG for verbose locator output
MCP_FAILURE_DIR=./mcp-failures  # screenshot + HTML dump on failure
```

---

## 10. Absolute Prohibitions

```
❌ @Before / @After in any Steps class — only in Hooks.java
❌ new Playwright.create() in any Steps class — only in Hooks.java
❌ Generate *Runner.java per feature — SmokeTestRunner + RegressionTestRunner are permanent
❌ Generate *.properties per feature — add keys to test.properties only
❌ Overwrite pom.xml / UiActions.java / PlaywrightUiActions.java — they exist, never touch
❌ Generate *.md files — not code
❌ private final Locator field in Page Objects — use private final String CONSTANT
❌ page.getByText("x") without .nth() when matchCount > 1
❌ Thread.sleep() or page.waitForTimeout() — use SmartWait / ui.waitForVisible()
❌ Hardcoded credentials in any .java or .feature file
❌ Assertions inside Page Object methods
❌ XPath as primary locator when href / id / testId is available
❌ execute_atomic_action during active recording session
❌ start_recording without stop_recording before calling it again
❌ getProperty() copy-pasted into Steps — use TestConfig.get()
```

---

## 11. Run Commands Reference

```bash
# Smoke tests — all features
mvn test -Dtest=SmokeTestRunner

# Regression — all features
mvn test -Dtest=RegressionTestRunner

# Visible browser (debug)
mvn test -Dtest=SmokeTestRunner -Dheadless=false

# Filter by tag
mvn test -Dtest=SmokeTestRunner "-Dcucumber.filter.tags=@e2e"

# Slow site — increase timeout
MCP_TIMEOUT_MS=60000 mvn test -Dtest=SmokeTestRunner

# Reports
open target/cucumber-reports/smoke-report.html
mvn allure:serve
```

---

## 12. Tech Stack

```
Java 17 · Playwright 1.44 · Cucumber 7.18 · JUnit 5.10 · Maven 3.6+
Allure 2.27 · TestNG 7.9 (alternate runner) · SLF4J 2.0

Package root:   com.qa
Page Objects:   com.qa.pages
Step Defs:      com.qa.stepdefs
Runners:        com.qa.runners
Utils:          com.qa.utils
Features:       src/test/resources/features/
Recordings:     ./recordings/
Test data:      src/test/resources/TestData/test.properties
Failure dumps:  ./mcp-failures/
```
