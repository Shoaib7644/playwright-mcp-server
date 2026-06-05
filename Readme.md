# Playwright MCP Server
### A Java/Maven bridge between an LLM and a local Chromium browser

---

## Architecture Overview

```
┌─────────────────────┐      JSON-RPC / stdio      ┌──────────────────────────────┐
│   MCP Host          │ ◄─────────────────────────► │  PlaywrightMcpServer.java    │
│  (Claude Desktop /  │                             │  (MCP Tools)                 │
│   Cursor / Cline)   │                             │                              │
└─────────────────────┘                             │  ┌────────────────────────┐  │
                                                    │  │  Playwright Java       │  │
                                                    │  │  ┌──────────────────┐  │  │
                                                    │  │  │ Chromium Browser │  │  │
                                                    │  │  │  (headed/headless│  │  │
                                                    │  │  └──────────────────┘  │  │
                                                    │  └────────────────────────┘  │
                                                    │                              │
                                                    │  ┌────────────────────────┐  │
                                                    │  │  BddCodeGenerator      │  │
                                                    │  │  (Feature / Steps / POM│  │
                                                    │  └────────────────────────┘  │
                                                    └──────────────────────────────┘
```

**How the recording bridge works:**  
`page.exposeFunction("__mcpCapture", ...)` registers a Java callback that the in-page  
JavaScript capture script calls on every click / fill / change event — no CDP polling needed.

---

## Prerequisites

| Tool | Version |
|------|---------|
| JDK  | 17+     |
| Maven| 3.9+    |
| OS   | Windows / macOS / Linux |

---

## Quick Start

```bash
# 1. Clone / unzip the project
cd playwright-mcp-server

# 2. Build the fat jar
mvn clean package -DskipTests

# 3. Install Chromium (once)
mvn exec:java@install-browsers

# 4. Register with your MCP host (see mcp-config.json)
#    e.g. for Claude Desktop, edit:
#    ~/Library/Application Support/Claude/claude_desktop_config.json  (macOS)
#    %APPDATA%\Claude\claude_desktop_config.json                       (Windows)

# 5. Restart the MCP host — the tool list will include the 5 Playwright tools
```

---

## Exposed MCP Tools

### `start_recording`
```json
{ "url": "https://your-app.example.com" }
```
Launches Chromium (headed), navigates to `url`, and injects the JS capture script.  
Every click, fill, and navigation is captured and queued as a JSON step.

---

### `stop_recording`
```json
{}
```
Closes the browser and returns the full JSON recording:

```json
[
  { "action": "navigate",  "selector": "",                           "value": "https://app.example.com", "timestamp": "..." },
  { "action": "fill",      "selector": "placeholder:Email address",  "value": "user@example.com",        "timestamp": "..." },
  { "action": "fill",      "selector": "placeholder:Password",       "value": "secret",                  "timestamp": "..." },
  { "action": "click",     "selector": "role:button:Sign in",        "value": "",                        "timestamp": "..." }
]
```

---

### `playback_recording`
```json
{
  "jsonRecording": "[ ... ]",
  "url": "https://app.example.com"
}
```
Opens a fresh Chromium instance and replays every step with 300 ms pacing.

---

### `generate_playwright_bdd`
```json
{
  "jsonRecording": "[ ... ]",
  "featureName":   "User Login"
}
```
Returns four code artifacts inline:

| Artifact | Package / Path |
|----------|---------------|
| `pom.xml` | project root |
| `UserLogin.feature` | `src/test/resources/features/` |
| `UserLoginSteps.java` | `com.qa.steps` |
| `UserLoginPage.java` | `com.qa.pages` |
| `CucumberTestRunner.java` | `com.qa.runner` |

---

### `execute_atomic_action`
```json
{
  "action":   "fill",
  "selector": "placeholder:Search",
  "value":    "Playwright Java"
}
```

Supported actions:

| action | Playwright call |
|--------|----------------|
| `navigate` | `page.navigate(value)` |
| `click` | `resolveLocator(selector).click()` |
| `fill` | `resolveLocator(selector).fill(value)` |
| `type` | `resolveLocator(selector).type(value)` |
| `check` | `resolveLocator(selector).check()` |
| `select_option` | `resolveLocator(selector).selectOption(value)` |
| `getByText` | `page.getByText(selector).first().click()` |
| `getByRole` | `page.getByRole(AriaRole.valueOf(selector))` |
| `getByPlaceholder` | `page.getByPlaceholder(selector).fill(value)` |
| `press` | `resolveLocator(selector).press(value)` |
| `hover` | `resolveLocator(selector).hover()` |
| `wait` | `page.waitForTimeout(ms)` |

---

## Selector Priority

The server and generated Page Objects always prefer **semantic locators** in this order:

1. `role:<AriaRole>:<accessible name>` → `getByRole(...).setName(...)`
2. `text:<visible text>` → `getByText(...)`
3. `placeholder:<placeholder>` → `getByPlaceholder(...)`
4. `label:<label text>` → `getByLabel(...)`
5. `testid:<data-testid>` → `getByTestId(...)`
6. CSS / XPath fallback → `locator(...)`

---

## Generated BDD Project Structure

```
generated-project/
├── pom.xml
└── src/
    ├── main/java/com/qa/pages/
    │   └── UserLoginPage.java          ← Page Object Model
    └── test/
        ├── java/com/qa/
        │   ├── steps/
        │   │   └── UserLoginSteps.java ← @Given @When @Then
        │   └── runner/
        │       └── CucumberTestRunner.java
        └── resources/
            └── features/
                └── UserLogin.feature   ← Gherkin
```

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| `page.exposeFunction` for capture | Zero CDP overhead; works in iframes; JVM callback is synchronous |
| `addInitScript` on context | Ensures the script runs before any page scripts, even after navigation |
| `CopyOnWriteArrayList` for steps | Thread-safe without locking; Playwright fires browser events on its own thread |
| Fat jar via `maven-shade-plugin` | Single artifact for MCP host — no classpath setup required |
| Logback → STDERR only | MCP stdio protocol uses STDOUT exclusively for JSON-RPC; mixing logs breaks the protocol |
| Semantic locator priority | ARIA-first selectors survive UI refactors; generated tests stay green longer |

---

## Running Tests (Generated Framework)

```bash
cd generated-project
mvn test
# Reports: target/cucumber-reports/report.html
```

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `Executable not found` | Run `mvn exec:java@install-browsers` to download Chromium |
| `Connection closed` in MCP host | Check that ALL logs go to STDERR (see `logback.xml`) |
| Steps not captured | The target page may use a strict CSP — add the domain to CSP bypass in `BrowserContext` |
| `AriaRole` value not found | Use lowercase role name in selector, e.g. `role:button:Submit` |
