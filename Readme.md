# Playwright MCP Server v7.1

## Intelligent Playwright + MCP + BDD Automation Framework Generator
### Salesforce Lightning · SPA · Enterprise-Grade · AI-Native

A production-ready Java-based Model Context Protocol (MCP) server that bridges LLMs with a live Chromium browser using Playwright Java.

This framework records real browser interactions, analyses user intent, generates maintainable BDD automation frameworks, supports intelligent playback with recovery, and exposes atomic browser tools for AI agents — with full Salesforce Lightning / LWC compatibility and enterprise-grade thread safety.

---

## What's New in v7.1 (vs v6)

### Critical Bug Fixes

| # | Area | v6 Behaviour | v7.1 Fix |
|---|------|-------------|---------|
| 1 | `execute_atomic_action` | Browser opened via `openTLPage()` was **never closed** — silent leak per call | `opened` flag tracks ownership; `closeTLBrowser()` called in `finally` only when this call opened it |
| 2 | `PlaywrightUiActions.navigate()` | Used `NETWORKIDLE` — **deadlocks** on Salesforce due to persistent WebSocket long-polling | Replaced with `DOMCONTENTLOADED` + `waitForSalesforceLightning()` |
| 3 | Generated `PRESS_KEY` step | Emitted `page.keyboard().press(key)` — **compilation error** (`page` is never in scope in step classes) | Delegates to `context.getUiActions().press(null, key)` via UiActions abstraction |
| 4 | Generated `ScenarioContext` | Referenced in all step classes but **never generated** — project would not compile | New `BddCodeGenerator.scenarioContext()` method; emitted as section 4 of `generate_playwright_bdd` output |
| 5 | `Locator.type()` | **Deprecated** in current Playwright Java SDK | `FILL` → `fill()` with scroll+click-to-focus; `TYPE` → `pressSequentially()` with configurable delay |

---

### Thread Safety and Concurrency

| # | Area | v6 Behaviour | v7.1 Fix |
|---|------|-------------|---------|
| 6 | `start_recording` concurrent calls | No guard — two concurrent calls corrupt `recBrowser`/`recContext`/`recPage` | `ReentrantLock RECORDING_LOCK` wraps full initialization sequence; `tryLock()` rejects concurrent callers immediately |
| 7 | `stop_recording` race | Could call `closeRecBrowser()` against partially-constructed browser mid-`start_recording` | `stop_recording` also acquires `RECORDING_LOCK` via `tryLock()` — serialized with startup |
| 8 | `pollingThread` + `pollActive` race | Two `volatile` fields — never atomically consistent; NPE window and double-start gap | Replaced with single `AtomicReference<Thread> POLLING_THREAD`; `compareAndSet` is the double-start guard; `getAndSet(null)` is the NPE-safe stop |
| 9 | FILL debounce index scan | `CopyOnWriteArrayList.indexOf(prev)` under `INGEST_LOCK` — O(n²) on long recordings | `pendingFillByLocator` map tracks latest FILL event per locator key; O(1) lookup |

---

### Salesforce Lightning Engine (New)

| Feature | Detail |
|---------|--------|
| `waitForSalesforceLightning(Page)` | 4-step Aura/LWC stabilization: `one-app` bootstrap marker → `[data-aura-rendered-by]` visible → `.slds-spinner` count = 0 → LWC micro-task buffer |
| Non-SF early return | Step 1 uses full `LIGHTNING_TIMEOUT`; if `one-app` is absent the method returns immediately — zero overhead on non-Salesforce pages |
| All navigate paths unified | `start_recording`, `playback_recording`, `execute_atomic_action`, and generated `PlaywrightUiActions.navigate()` all use `DOMCONTENTLOADED` + `waitForSalesforceLightning()` |
| LWC micro-task buffer | `LWC_MICROTASK_BUFFER_MS` (default 1 000 ms, env-overridable) replaces hardcoded magic constant |
| FILL click-to-focus | `scrollIntoViewIfNeeded()` + `click()` before `fill()` in both server replay and generated `PlaywrightUiActions` — required for LWC/Aura shadow-DOM inputs |
| Three dedicated SF timeouts | `MCP_SF_PAGE_TIMEOUT_MS` (90 s), `MCP_SF_ELEMENT_TIMEOUT_MS` (30 s), `MCP_SF_LIGHTNING_TIMEOUT_MS` (20 s) |

---

### BDD Generation Enhancements

| Feature | v6 | v7.1 |
|---------|----|------|
| Scenario Outline placeholders | External `dataRows` columns only | Derived from locator metadata: ariaLabel → placeholder → name → id → innerText → CSS |
| RAW_ACTION FILL in outline mode | Hardcoded recorded value | `<placeholderName>` token; Examples column auto-added |
| ScenarioContext generation | Never generated | Emitted as section 4; PicoContainer-compatible; full `set/get/has/reset` API |
| `cucumber-picocontainer` in pom.xml | Missing | Added |
| `WaitUntilState` import in generated class | Missing | Added |
| Stale `Playwright` field in step class | Present | Removed |
| `ScenarioContext` import in step class | Missing | Added |
| `pressSequentially()` in generated code | Deprecated `type()` | `pressSequentially()` with `TYPE_KEY_DELAY_MS` delay |
| Recognized intent columns in Examples | Only external | `LOGIN` (username/password), `SEARCH` (query), `TRANSFER` (amount), `SELECT_FLOW` (field), `UPLOAD_FLOW` (filePath) auto-added |

---

### New Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `MCP_SF_PAGE_TIMEOUT_MS` | 90 000 | Full Salesforce page / SSO load timeout |
| `MCP_SF_ELEMENT_TIMEOUT_MS` | 30 000 | Lightning element wait |
| `MCP_SF_LIGHTNING_TIMEOUT_MS` | 20 000 | Aura/LWC render stabilization |
| `MCP_LWC_BUFFER_MS` | 1 000 | LWC async render micro-task buffer |
| `MCP_TYPE_KEY_DELAY_MS` | 50 | Inter-key delay for `pressSequentially()` |

---

## Table of Contents

1. [Overview](#overview)
2. [What's New in v7.1](#whats-new-in-v71-vs-v6)
3. [Key Features](#key-features)
4. [Architecture](#architecture)
5. [Core Components](#core-components)
6. [Technology Stack](#technology-stack)
7. [Prerequisites](#prerequisites)
8. [Installation and Setup](#installation-and-setup)
9. [MCP Integration](#mcp-integration)
10. [Exposed MCP Tools](#exposed-mcp-tools)
11. [Smart Wait Engine](#smart-wait-engine)
12. [Salesforce Lightning Engine](#salesforce-lightning-engine)
13. [Intent Analysis Engine](#intent-analysis-engine)
14. [Intelligent BDD Generation](#intelligent-bdd-generation)
15. [Generated Framework Structure](#generated-framework-structure)
16. [ScenarioContext](#scenariocontext)
17. [UiActions Abstraction Layer](#uiactions-abstraction-layer)
18. [Locator Strategy](#locator-strategy)
19. [Parallel Playback Support](#parallel-playback-support)
20. [Failure Recovery System](#failure-recovery-system)
21. [Concurrency Model](#concurrency-model)
22. [Supported Browser Actions](#supported-browser-actions)
23. [Environment Variables](#environment-variables)
24. [Build and Run](#build-and-run)
25. [Example Workflow](#example-workflow)
26. [Sample Recording JSON](#sample-recording-json)
27. [Troubleshooting Guide](#troubleshooting-guide)
28. [Performance and Design Decisions](#performance-and-design-decisions)
29. [Security Considerations](#security-considerations)
30. [Future Enhancements](#future-enhancements)
31. [Best Practices](#best-practices)
32. [FAQ](#faq)
33. [License](#license)

---

## Overview

Playwright MCP Server is an intelligent automation orchestration layer that enables Large Language Models (LLMs) to:

- Launch and control browsers
- Record user interactions on any web application including Salesforce Lightning orgs
- Replay workflows reliably with Salesforce-safe wait strategies
- Generate enterprise-grade BDD frameworks with fully compilable output
- Execute atomic browser operations
- Analyze raw browser events into business intents
- Create reusable Page Object Models automatically

The framework is optimized for:

- AI-assisted QA automation
- Salesforce Lightning / LWC automation
- Self-healing browser interactions
- Intent-driven BDD generation
- Framework-agnostic automation design
- Parallel execution
- Enterprise automation scalability

---

## Key Features

### Browser Recording Engine

- Records clicks, fills, navigations, key presses, uploads, drop downs, and scrolls
- Captures semantic locators automatically with 9-tier priority strategy
- Supports headed and headless execution
- CSP bypass support
- Smart event de-duplication via fingerprint guard
- FILL debounce: collapses rapid same-field keystrokes to final value (3-second window)
- Session-based recording management
- Lightning iframe CAPTURE_SCRIPT re-injection on frame navigation

### Smart Wait Engine (v7.1)

Automatic synchronisation before and after every action:

- DOM readyState checks
- Salesforce Lightning Aura/LWC stabilization (replaces `NETWORKIDLE`)
- Element visibility validation
- Enabled-state verification
- DOM stabilization logic
- Action-aware pre/post waits
- SLDS spinner dismissal detection

### Salesforce Lightning Engine (New in v7.1)

Four-step stabilization sequence that fires only on Salesforce pages:

1. `one-app` bootstrap marker attached
2. `[data-aura-rendered-by]` component visible
3. `.slds-spinner` count reaches zero
4. LWC micro-task buffer flush

Non-Salesforce pages return immediately at step 1 with zero overhead.

### Intent Analyzer

Converts raw DOM events into business-level intents using a sliding-window greedy matcher (window size = 6):

| Raw Events | Detected Intent |
|-----------|----------------|
| Username + Password + Login Click | LOGIN |
| Search Fill + Enter | SEARCH |
| Multiple Form Fields + Submit | FORM_SUBMIT |
| Amount + Transfer Button | TRANSFER |
| Upload + Confirm | UPLOAD_FLOW |
| Dropdown + Confirm | SELECT_FLOW |
| Unmatched events | RAW_ACTION |

### Intelligent BDD Generator

Automatically generates compilable, production-ready output:

- Feature files (Scenario and Scenario Outline)
- Step definitions with correct imports and PicoContainer DI
- Page Objects
- Runner classes (Cucumber / TestNG / Serenity)
- UiActions interface
- Playwright implementation (Salesforce-safe)
- ScenarioContext shared state carrier (new in v7.1)
- Scenario Outlines with Examples tables derived from locator metadata
- Parameterized steps with placeholder names from ariaLabel / placeholder / name / id

### Parallel Playback Engine

- ThreadLocal Playwright sessions (one instance per thread)
- Multi-threaded replay via `FixedThreadPool`
- Chunk-based event distribution
- Isolated browser contexts
- Concurrent execution support

### Recovery and Failure Handling

- Screenshot capture on failure
- HTML dump generation
- Automatic DOM re-sync
- URL validation after failure
- Retry engine (configurable attempts and delay)
- Smart recovery handling

### Framework Abstraction Layer

Generated frameworks use:

```java
ui.click(locator);
ui.fill(locator, value);
ui.navigate(url);
ui.press(null, "Enter");            // global keyboard press
ui.press(selector, "Tab");          // element-scoped key press
ui.pressSequentially(sel, text);    // per-character for autocomplete fields
```

Enables future migration to Selenium, Cypress, WebDriverIO, or Appium without changing Page Objects.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     MCP HOST / LLM                          │
│        Claude / Cursor / Cline / Custom AI Agent            │
└──────────────────────┬──────────────────────────────────────┘
                       │ JSON-RPC over stdio
┌──────────────────────▼──────────────────────────────────────┐
│              Playwright MCP Server v7.1                     │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                MCP Tool Layer                        │   │
│  │  start_recording  stop_recording                     │   │
│  │  playback_recording  generate_playwright_bdd         │   │
│  │  execute_atomic_action                               │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌────────────────────┐  ┌────────────────────────────┐     │
│  │  RECORDING_LOCK    │  │  POLLING_THREAD            │     │
│  │  (ReentrantLock)   │  │  (AtomicReference<Thread>) │     │
│  │  start + stop      │  │  compareAndSet start guard │     │
│  │  serialized        │  │  getAndSet NPE-free stop   │     │
│  └────────────────────┘  └────────────────────────────┘     │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │         Salesforce Lightning Engine (NEW)            │   │
│  │  one-app → [data-aura-rendered-by]                   │   │
│  │  → slds-spinner=0 → LWC micro-task buffer            │   │
│  │  Non-SF: zero overhead early return at step 1        │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Smart Wait Engine                       │   │
│  │  DOM Ready · Element Visibility · Enabled State      │   │
│  │  DOMCONTENTLOADED replaces NETWORKIDLE               │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Intent Analysis Engine                  │   │
│  │  LOGIN · SEARCH · TRANSFER · FORM_SUBMIT             │   │
│  │  NAVIGATION · SELECT_FLOW · UPLOAD_FLOW              │   │
│  │  RAW_ACTION (fallback) · placeholderName()           │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              BDD Code Generator                      │   │
│  │  Feature · Steps · PageObject · ScenarioContext      │   │
│  │  UiActions · PlaywrightUiActions · Runners · pom     │   │
│  │  Outline placeholder derivation from locator meta    │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│                  Playwright Java 1.44+                      │
│       Chromium · Salesforce Lightning · SPA · Web           │
└─────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. PlaywrightMcpServer.java

Main orchestration engine. Key responsibilities:

- MCP tool registration and dispatch
- Browser lifecycle management with `RECORDING_LOCK` protection
- Event recording with FILL debounce and fingerprint de-duplication
- Salesforce-safe playback with `waitForSalesforceLightning()`
- Smart Wait Engine (pre/post action per action type)
- Retry engine with failure artifact generation
- Parallel execution via `FixedThreadPool` + `ThreadLocal`
- Session metadata and code-gen hints
- `AtomicReference<Thread>` polling thread management

### 2. IntentAnalyzer.java

Transforms low-level browser events into meaningful business actions.

Sliding-window greedy matcher, window size = 6. Each matched intent consumes its source events; unmatched events become individual `RAW_ACTION` intents.

**New in v7.1:**
- `placeholderName(ObjectNode)` — public static; derives camelCase placeholder names from locator metadata
- `sanitizeLabel(String)` — strips filler verbs before camelCase conversion
- `toGherkinStep(boolean outline)` — outline mode activates placeholder tokens for FILL and recognized intents
- `rawGherkin(boolean outline)` — replaces zero-arg version; only FILL parameterized in outline mode

### 3. BddCodeGenerator.java

Generates complete, compilable automation frameworks.

**New in v7.1:**
- `scenarioContext()` — static method generating the `ScenarioContext.java` shared state carrier
- `featureFile()` — derives Examples table columns from locator metadata; merges external and intent-derived columns
- `playwrightUiActions()` — Salesforce-safe navigate, inline `waitForSalesforceLightning()`, `pressSequentially()`, fill with scroll+click-to-focus
- `buildRawStepMethod()` — `PRESS_KEY` delegates to `context.getUiActions().press(null, key)`
- `pomXml()` — includes `cucumber-picocontainer`

---

## Technology Stack

| Category | Technology |
|----------|-----------|
| Language | Java 17+ |
| Build Tool | Maven 3.9+ |
| Browser Automation | Playwright Java 1.44+ |
| BDD Framework | Cucumber 7 |
| MCP Protocol | Model Context Protocol (stdio) |
| JSON Processing | Jackson 2.17+ |
| Reporting | Allure + Cucumber Reports |
| Parallel Execution | Java Executors + ThreadLocal |
| Concurrency | ReentrantLock + AtomicReference |
| DI Container | PicoContainer (Cucumber) |
| Browser | Chromium |
| Logging | java.util.logging |

---

## Prerequisites

| Tool | Version |
|------|---------|
| JDK | 17+ |
| Maven | 3.9+ |
| Node.js | Optional |
| OS | Windows / Linux / macOS |
| RAM | 8 GB recommended (16 GB for parallel runs) |

---

## Installation and Setup

### 1. Clone Repository

```bash
git clone <repository-url>
cd playwright-mcp-server
```

### 2. Build Project

```bash
mvn clean package -DskipTests
```

### 3. Install Playwright Browsers

```bash
mvn exec:java@install-browsers
```

### 4. Verify Build

```
target/playwright-mcp-server.jar
```

---

## MCP Integration

### Claude Desktop Configuration

**macOS**
```
~/Library/Application Support/Claude/claude_desktop_config.json
```

**Windows**
```
%APPDATA%\Claude\claude_desktop_config.json
```

### Example Configuration

```json
{
  "mcpServers": {
    "playwright": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/playwright-mcp-server.jar"],
      "env": {
        "MCP_SF_PAGE_TIMEOUT_MS": "90000",
        "MCP_LWC_BUFFER_MS": "1000"
      }
    }
  }
}
```

Restart Claude Desktop after configuration.

---

## Exposed MCP Tools

### 1. `start_recording`

Launches Chromium and starts interaction recording.

```json
{
  "url": "https://your-org.lightning.force.com",
  "sessionName": "Login Flow",
  "headless": false
}
```

**Features:**
- CSP bypass
- `CAPTURE_SCRIPT` injection and frame re-injection on navigation
- `waitForSalesforceLightning()` after initial navigate
- `RECORDING_LOCK` protects full initialization — concurrent calls rejected immediately with an actionable error
- FILL debounce (3-second window per locator key)
- Fingerprint-based event de-duplication
- Session metadata generation

---

### 2. `stop_recording`

Stops recording, drains all queues, analyzes intents, returns structured JSON.

```json
{}
```

**Output:**
- Raw browser events with `sequenceNo`, `elapsedMs`, `suggestedPlaywrightCode`
- Structured intents (type + params + sourceEvents)
- Session metadata (timeouts, locator engine version, viewport)
- Timing data and recording statistics
- Code generation hints

---

### 3. `playback_recording`

Replays recorded events with Smart Wait Engine.

```json
{
  "jsonRecording": "...",
  "headless": true,
  "parallel": false,
  "threads": 1
}
```

**Features:**
- `DOMCONTENTLOADED` + `waitForSalesforceLightning()` on NAVIGATE events
- FILL: scroll + click-to-focus + `fill()`
- TYPE: `pressSequentially()` with `TYPE_KEY_DELAY_MS` inter-key delay
- Retry engine per event
- Screenshot and HTML artifact on failure
- `recoverPageState()` after failure
- Parallel execution via `FixedThreadPool` + `ThreadLocal`

---

### 4. `generate_playwright_bdd`

Generates a complete, compilable BDD automation framework.

```json
{
  "jsonRecording": "...",
  "featureName": "User Login",
  "framework": "cucumber",
  "tags": "@smoke @regression",
  "dataRows": "[{\"username\":\"john\"},{\"username\":\"jane\"}]"
}
```

**Supported Frameworks:**

| Framework | Runner |
|-----------|--------|
| `cucumber` | JUnit 5 Platform Suite |
| `testng` | TestNG `AbstractTestNGCucumberTests` with `@DataProvider(parallel=true)` |
| `serenity` | Serenity BDD `CucumberWithSerenity` |

**Generated Sections (v7.1):**

| # | Section | Path |
|---|---------|------|
| 1 | Feature File | `src/test/resources/features/<Name>.feature` |
| 2 | Step Definitions | `src/test/java/com/qa/stepdefs/<Name>Steps.java` |
| 3 | Page Object | `src/test/java/com/qa/pages/<Name>Page.java` |
| 4 | **ScenarioContext** (new) | `src/test/java/com/qa/context/ScenarioContext.java` |

Plus on first project setup: `UiActions.java`, `PlaywrightUiActions.java`, runner class, `pom.xml`.

---

### 5. `execute_atomic_action`

Executes a single Playwright action with Smart Wait pre-conditions.

```json
{
  "action": "fill",
  "selector": "placeholder:Search",
  "value": "Playwright"
}
```

**v7.1:** Browser opened by this call is now correctly closed in a `finally` block. Pre-existing browsers from parallel playback are never touched.

---

## Smart Wait Engine

### Pre-Action Wait Strategy

| Action | Wait |
|--------|------|
| CLICK / DOUBLE_CLICK / HOVER | Visible (`SALESFORCE_ELEMENT_TIMEOUT`) |
| FILL / TYPE / CLEAR | Visible + enabled + `Locator.waitFor()` |
| SELECT_OPTION / CHECK | Visible |
| UPLOAD_FILE | Attached |
| NAVIGATE | `DOMCONTENTLOADED` + `waitForSalesforceLightning()` |

### Post-Action Wait Strategy

| Action | Wait |
|--------|------|
| CLICK / DOUBLE_CLICK / FORM_SUBMIT | `DOMCONTENTLOADED` + `waitForSalesforceLightning()` |
| FILL / TYPE | 200 ms stabilization |
| NAVIGATE | `DOMCONTENTLOADED` + `waitForSalesforceLightning()` |

---

## Salesforce Lightning Engine

### Why Not NETWORKIDLE?

Salesforce Lightning keeps persistent WebSocket connections open for Streaming API, CometD, and live updates. `NETWORKIDLE` never fires on these pages — it deadlocks the test runner. `DOMCONTENTLOADED` fires as soon as HTML is parsed, and `waitForSalesforceLightning()` handles the render stabilization.

### Four-Step Sequence

```
Step 1: waitForSelector("one-app", ATTACHED, LIGHTNING_TIMEOUT)
        └── if absent → return immediately (not a Salesforce page)

Step 2: waitForSelector("[data-aura-rendered-by]", VISIBLE, LIGHTNING_TIMEOUT)
        └── Confirms Aura has rendered at least one component

Step 3: waitForFunction("() => document.querySelectorAll('.slds-spinner').length === 0")
        └── All SLDS loading spinners dismissed

Step 4: waitForTimeout(LWC_MICROTASK_BUFFER_MS)
        └── LWC async render queue flushed (default 1 000 ms, env-overridable)
```

### Non-Salesforce Overhead

Step 1 resolves in under 100 ms on standard web apps (selector not found → catch block → early return). No measurable overhead.

---

## Intent Analysis Engine

### Supported Intent Types

| Intent | Description | Matcher Pattern |
|--------|-------------|----------------|
| LOGIN | Authentication flow | FILL(user) + FILL(password-type) + CLICK |
| SEARCH | Search interaction | FILL(search-like) + optional PRESS_KEY(Enter) or CLICK |
| TRANSFER | Monetary/data transfer | FILL(amount-like) + CLICK(transfer/send) |
| FORM_SUBMIT | Generic form submission | FORM_SUBMIT event or CLICK(submit-role) |
| NAVIGATION | URL navigation | NAVIGATE event or CLICK(link/href) |
| SELECT_FLOW | Dropdown selection | SELECT_OPTION or CLICK(combobox/listbox role) |
| UPLOAD_FLOW | File upload | UPLOAD_FILE event |
| RAW_ACTION | Unmatched individual action | Fallback — one event per intent |

### Placeholder Name Derivation (New in v7.1)

`placeholderName(ObjectNode)` derives a camelCase placeholder from locator metadata in priority order:

```
ariaLabel    "Email Address"   → emailAddress
placeholder  "Enter password"  → password
name         "username"        → username
id           "loginEmail"      → loginEmail
innerText    "First Name"      → firstName
cssSelector  "#email-field"    → emailField
fallback                       → value
```

The same method is called by both `IntentAnalyzer` (step token) and `BddCodeGenerator` (column header) — they can never diverge.

---

## Intelligent BDD Generation

### Scenario Mode (no dataRows)

```gherkin
@smoke @regression
Feature: User Login

  Background:
    Given the browser is open

  Scenario: User Login
    When user logs in with "john" and "<password>"
    When user navigates to "https://example.com/dashboard"
```

### Scenario Outline Mode (dataRows supplied)

Placeholder names are derived from locator metadata — not hardcoded recorded values:

```gherkin
@smoke @regression
Feature: Create Account

  Background:
    Given the browser is open

  Scenario Outline: Create Account — <accountName>
    When user logs in with "<username>" and "<password>"
    When user enters "<accountName>" in the "Account Name" field
    When user enters "<emailAddress>" in the "Email Address" field
    When user submits the "New Account" form

    Examples:
      | username | password | accountName | emailAddress          |
      | john     | pass1    | Acme Corp   | acme@example.com      |
      | jane     | pass2    | Globex Inc  | globex@example.com    |
```

External `dataRows` columns appear first; derived locator-metadata columns are appended. Cells without an external value emit `<colName>` as a visible reminder to supply test data.

---

## Generated Framework Structure

```
generated-project/
│
├── pom.xml                                    ← includes cucumber-picocontainer (v7.1)
│
└── src/
    ├── main/
    │   └── java/com/qa/
    │       └── actions/
    │           ├── UiActions.java             ← abstraction interface
    │           └── PlaywrightUiActions.java   ← Salesforce-safe implementation
    │
    └── test/
        ├── java/com/qa/
        │   ├── context/
        │   │   └── ScenarioContext.java       ← NEW in v7.1: shared state carrier
        │   │
        │   ├── pages/
        │   │   └── UserLoginPage.java
        │   │
        │   ├── runners/
        │   │   └── UserLoginRunner.java
        │   │
        │   └── stepdefs/
        │       └── UserLoginSteps.java
        │
        └── resources/
            ├── features/
            │   └── UserLogin.feature
            │
            └── TestData/
                └── testdata.properties
```

---

## ScenarioContext

`ScenarioContext` is a PicoContainer-managed shared state carrier injected into every step class by constructor. New in v7.1 — generated automatically as section 4 of `generate_playwright_bdd`.

### API

```java
// Playwright handles — set by Hooks.java @Before
context.setPage(page);              // also wires UiActions automatically
context.getPage();                  // throws IllegalStateException if not set
context.getUiActions();             // throws IllegalStateException if not set
context.setUiActions(mock);         // override for unit tests only

// Scenario-scoped test data store
context.set("createdId", id);
context.get("createdId");           // null if absent
context.get("createdId", "none");   // with fallback
context.has("createdId");           // boolean
context.getAllTestData();           // unmodifiable snapshot for @After logging

// Lifecycle
context.reset();                    // called by Hooks.java @After
```

### Usage in Step Class

```java
public class LoginSteps {
    private final ScenarioContext context;

    public LoginSteps(ScenarioContext context) {
        this.context = context;
    }

    @When("user logs in with {string} and {string}")
    public void userLogsIn(String username, String password) {
        context.getUiActions().fill("#username", username);
        context.getUiActions().fill("#password", password);
        context.getUiActions().click("#login");
        context.set("loggedInUser", username);
    }
}
```

### Hooks.java Integration

```java
@Before
public void setUp() {
    context.setPage(pageFactory.createPage()); // wires UiActions automatically
}

@After
public void tearDown(Scenario scenario) {
    if (scenario.isFailed()) {
        Allure.addAttachment("Test Data", context.getAllTestData().toString());
    }
    context.reset();
}
```

---

## UiActions Abstraction Layer

### Interface (v7.1)

```java
public interface UiActions {
    void navigate(String url);
    void click(String selector);
    void fill(String selector, String value);
    void pressSequentially(String selector, String text); // new in v7.1
    void press(String selector, String key);              // null selector = global keyboard
    void selectOption(String selector, String value);
    void check(String selector);
    void uncheck(String selector);
    void hover(String selector);
    void clear(String selector);
    void uploadFile(String selector, String filePath);
    void scroll(int deltaX, int deltaY);
    String getText(String selector);
    boolean isVisible(String selector);
    void waitForSelector(String selector);
}
```

### `press()` null-selector routing

```java
context.getUiActions().press(null, "Enter");     // → page.keyboard().press("Enter")
context.getUiActions().press("#search", "Tab");  // → locator.press("Tab")
```

This is how generated `PRESS_KEY` steps work — `null` selector means global keyboard event, not element-scoped.

---

## Locator Strategy

### Priority Order

| Priority | Strategy | Example |
|----------|----------|---------|
| 1 | href / href-partial | `a[href*='/accounts/']` |
| 2 | id (CSS) | `#username` |
| 3 | data-testid | `testid:submit-btn` |
| 4 | ARIA role + name | `role:button:Login` |
| 5 | placeholder | `placeholder:Search` |
| 6 | label | `label:Email` |
| 7 | text | `text:Sign In` |
| 8 | CSS | `div.slds-form > input` |
| 9 | nth fallback | `selector::nth=2` |

### Dynamic href Sanitization

URLs with dynamic record IDs (e.g. `/accounts/0012300001abc/view`) are automatically converted to partial-match selectors (`a[href*='/accounts/']`) so replay is not tied to a specific record ID.

---

## Parallel Playback Support

```json
{
  "jsonRecording": "...",
  "parallel": true,
  "threads": 4
}
```

- Events split into N equal chunks, one per thread
- Each thread gets its own `Playwright` + `Browser` + `BrowserContext` + `Page` via `ThreadLocal`
- `FixedThreadPool` manages thread lifecycle with a 10-minute termination timeout
- `closeTLBrowser()` called in `finally` per thread regardless of outcome
- Sequential fallback when `parallel=false` or `threads=1`

---

## Failure Recovery System

On any action failure:

1. **Screenshot** captured to `MCP_FAILURE_DIR/<timestamp>-seq<N>/failure.png`
2. **HTML dump** saved to `failure.html`
3. **Error log** saved to `error.txt` (action, seq, URL, exception message)
4. **DOM re-sync**: `DOMCONTENTLOADED` wait + `waitForSalesforceLightning()`
5. **URL validation**: current URL compared to expected `pageUrl` from event
6. **Execution continues** at next event

---

## Concurrency Model

```
RECORDING_LOCK (ReentrantLock)        ← outer lock
  └── INGEST_LOCK (synchronized)      ← inner lock
        └── recordedEvents            ← CopyOnWriteArrayList

Lock ordering rule: RECORDING_LOCK always acquired before INGEST_LOCK.
Never acquire RECORDING_LOCK while INGEST_LOCK is held.

POLLING_THREAD (AtomicReference<Thread>)
  startPolling(): compareAndSet(null, candidate) → exactly one thread wins
  stopPolling():  getAndSet(null)               → NPE-safe atomic clear

TL_PW / TL_BROWSER / TL_CTX / TL_PAGE (ThreadLocal)
  One Playwright instance per thread — never shared.
  execute_atomic_action: opened=true flag → close only what this call opened.
```

---

## Supported Browser Actions

| Action | Description |
|--------|-------------|
| `navigate` | Navigate to URL (DOMCONTENTLOADED + SF wait) |
| `click` | Click element |
| `double_click` / `dblclick` | Double click |
| `right_click` | Right click |
| `fill` | Fill input (scroll + click-to-focus + fill) |
| `type` | Type per-character via `pressSequentially()` |
| `clear` | Clear field |
| `press` | Keyboard key (element or global) |
| `hover` | Hover element |
| `focus` | Focus element |
| `check` | Check checkbox |
| `uncheck` | Uncheck checkbox |
| `select_option` | Select dropdown option |
| `upload_file` | Upload single file |
| `upload_files` | Upload multiple files (comma-separated) |
| `scroll` | Scroll by delta |
| `scroll_to_element` | Scroll element into view |
| `keyboard_press` | Global keyboard press |
| `keyboard_type` | Global keyboard type |
| `wait` | Explicit timeout |
| `wait_for_selector` | Wait for element visible |
| `wait_for_hidden` | Wait for element hidden |
| `wait_for_url` | Wait for URL match |
| `wait_for_navigation` | Wait for DOMCONTENTLOADED |
| `wait_for_network_idle` | SF-safe idle (Lightning stabilization) |
| `screenshot` | Capture full-page screenshot |
| `get_text` | Extract inner text |
| `get_value` | Extract input value |
| `get_attribute` | Extract element attribute |
| `get_html` | Extract innerHTML |
| `is_visible` | Visibility check |
| `is_enabled` | Enabled-state check |
| `is_checked` | Checked-state check |
| `count` | Count matching elements |
| `get_url` | Current page URL |
| `get_title` | Current page title |

---

## Environment Variables

### Core Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `MCP_RETRY_MAX` | 3 | Retry attempts per action |
| `MCP_TIMEOUT_MS` | 30 000 | General element timeout |
| `MCP_RETRY_DELAY_MS` | 800 | Delay between retries |
| `MCP_MAX_STEP_DELAY_MS` | 500 | Max inter-step pacing delay |
| `MCP_FAILURE_DIR` | `./mcp-failures` | Failure artifact directory |
| `MCP_LOG_LEVEL` | `INFO` | `INFO` or `DEBUG` |
| `MCP_TYPE_KEY_DELAY_MS` | 50 | Inter-key delay for `pressSequentially()` |

### Salesforce-specific Variables (New in v7.1)

| Variable | Default | Purpose |
|----------|---------|---------|
| `MCP_SF_PAGE_TIMEOUT_MS` | 90 000 | Full SF page / SSO load timeout |
| `MCP_SF_ELEMENT_TIMEOUT_MS` | 30 000 | Lightning element wait |
| `MCP_SF_LIGHTNING_TIMEOUT_MS` | 20 000 | Aura/LWC render stabilization |
| `MCP_LWC_BUFFER_MS` | 1 000 | LWC micro-task buffer flush |

---

## Build and Run

### Package Project

```bash
mvn clean package
```

### Run Server

```bash
java -jar target/playwright-mcp-server.jar
```

### Run with Salesforce-optimized timeouts

```bash
MCP_SF_PAGE_TIMEOUT_MS=120000 \
MCP_LWC_BUFFER_MS=2000 \
java -jar target/playwright-mcp-server.jar
```

### Enable Debug Logging

```bash
export MCP_LOG_LEVEL=DEBUG
java -jar target/playwright-mcp-server.jar
```

### Run Generated Framework

```bash
mvn test
```

### Run Specific Tags

```bash
mvn test -Dcucumber.filter.tags="@smoke"
```

---

## Example Workflow

### Step 1 — Start Recording

```json
{ "url": "https://your-org.lightning.force.com" }
```

### Step 2 — Perform Browser Actions

- Login via SSO
- Navigate to a record
- Fill form fields
- Submit

### Step 3 — Stop Recording

```json
{}
```

### Step 4 — Generate Framework

```json
{
  "featureName": "Create Account",
  "framework": "cucumber",
  "tags": "@smoke @regression",
  "dataRows": "[{\"accountName\":\"Acme\",\"email\":\"a@a.com\"},{\"accountName\":\"Globex\",\"email\":\"b@b.com\"}]"
}
```

### Step 5 — Place Generated Files

Copy each section to its indicated path. Section 4 (`ScenarioContext`) is generated once — do not regenerate per feature.

### Step 6 — Execute Tests

```bash
mvn test
```

---

## Sample Recording JSON

```json
{
  "schemaVersion": "7.1.0",
  "sessionName": "Login Test",
  "status": "COMPLETED",
  "totalEvents": 3,
  "totalIntents": 1,
  "totalDurationMs": 4200,
  "session": {
    "browserType": "chrome",
    "headless": false,
    "sfPageTimeoutMs": 90000,
    "lightningTimeoutMs": 20000,
    "typeKeyDelayMs": 50,
    "locatorEngine": "v7.1-salesforce-safe"
  },
  "events": [
    {
      "sequenceNo": 1,
      "actionType": "FILL",
      "elapsedMs": 1200,
      "locator": {
        "strategy": "placeholder",
        "primary": "placeholder:Username",
        "ariaLabel": "Username",
        "playwrightLocator": "page.getByPlaceholder(\"Username\")"
      },
      "inputValue": "john@example.com",
      "suggestedPlaywrightCode": "page.getByPlaceholder(\"Username\").first().scrollIntoViewIfNeeded();\npage.getByPlaceholder(\"Username\").first().click();\npage.getByPlaceholder(\"Username\").first().fill(\"john@example.com\");"
    },
    {
      "sequenceNo": 2,
      "actionType": "FILL",
      "elapsedMs": 2100,
      "locator": {
        "strategy": "css-id",
        "primary": "#password",
        "playwrightLocator": "page.locator(\"#password\")"
      },
      "inputValue": "***REDACTED***"
    },
    {
      "sequenceNo": 3,
      "actionType": "CLICK",
      "elapsedMs": 3000,
      "locator": {
        "strategy": "role",
        "primary": "role:button:Log In",
        "ariaLabel": "Log In"
      }
    }
  ],
  "intents": [
    {
      "intentType": "LOGIN",
      "params": { "username": "john@example.com", "password": "***REDACTED***" }
    }
  ]
}
```

---

## Troubleshooting Guide

| Problem | Solution |
|---------|----------|
| Browser executable not found | Run `mvn exec:java@install-browsers` |
| MCP host disconnects | Ensure all logging goes to STDERR only — stdout is reserved for MCP JSON-RPC |
| Events not captured | Verify CSP bypass; check `__mcpCaptureActive__` in browser console |
| `NETWORKIDLE` timeout on Salesforce | Update to v7.1 — `NETWORKIDLE` removed from all code paths |
| LWC components not interactable | Increase `MCP_LWC_BUFFER_MS`; verify `.slds-spinner` is gone |
| SSO redirect loses capture queue | `onFrameNavigated` re-injects `CAPTURE_SCRIPT`; synthetic NAVIGATE emitted via `onLoad` |
| Playback failures | Increase `MCP_SF_ELEMENT_TIMEOUT_MS`; check locator strategy |
| `ScenarioContext` injection fails | Confirm `cucumber-picocontainer` is in pom.xml (v7.1 pom includes it) |
| Step class compilation error: `page` not found | Update to v7.1 — `PRESS_KEY` uses `context.getUiActions().press(null, key)` |
| Parallel replay instability | Reduce thread count; increase `MCP_SF_PAGE_TIMEOUT_MS` |
| Elements not found | Prefer `ariaLabel` or `testid` selectors over CSS |
| Flaky playback on Lightning | Enable `MCP_LOG_LEVEL=DEBUG`; check spinner wait log entries |
| Headless issues on Salesforce | Run headed (`"headless": false`); some SF orgs block headless user agents |
| Chromium crashes | Increase system memory; add `--no-sandbox` flag for CI environments |

---

## Performance and Design Decisions

| Design Choice | Reason |
|---------------|--------|
| `ReentrantLock` for recording lifecycle | Prevents browser corruption from concurrent `start_recording` calls; `tryLock()` gives immediate rejection feedback |
| `AtomicReference<Thread>` for polling | Single atomic slot eliminates the NPE window and double-start race that two `volatile` fields cannot prevent |
| `DOMCONTENTLOADED` replacing `NETWORKIDLE` | `NETWORKIDLE` deadlocks on Salesforce due to persistent WebSockets; `DOMCONTENTLOADED` + Lightning stabilization is faster and reliable |
| `waitForSalesforceLightning()` early return | Non-SF pages exit at step 1 in under 100 ms — zero overhead |
| `pressSequentially()` with 50 ms delay | Clears debounced React `onChange` handlers while keeping a 20-field form under 5 seconds |
| scroll + click-to-focus before `fill()` | LWC shadow-DOM inputs require focus before accepting programmatic value assignment |
| `placeholderName()` shared static | Single source of truth for step token and Examples column — they can never diverge |
| FILL debounce 3-second window | Collapses rapid keystrokes to final value without losing intermediate navigation events |
| ThreadLocal Playwright | One `Playwright` per thread satisfies the Playwright single-thread constraint for parallel execution |
| `opened` flag in `handleAtomicAction` | Explicit browser ownership tracking — prevents closing a browser that belongs to a parallel playback session |
| O(1) FILL debounce map | Replaces `CopyOnWriteArrayList.indexOf()` O(n²) scan — scales to long recordings |

---

## Security Considerations

- CSP bypass is enabled intentionally for automation recording — disable in production environments
- Passwords are masked (`***REDACTED***`) during intent generation and code suggestion
- Browser contexts are isolated per session
- Failure artifacts may contain page HTML including sensitive data — restrict `MCP_FAILURE_DIR` access
- Generated frameworks must not commit credentials or session tokens
- `TYPE_KEY_DELAY_MS` should be set appropriately for environments with keystroke logging

---

## Future Enhancements

- Selenium `UiActions` implementation for migration path
- Firefox and WebKit support via Playwright
- AI-powered self-healing selectors
- Visual validation engine (screenshot diff)
- API testing integration (REST and GraphQL)
- Test impact analysis
- Cloud execution (BrowserStack / Sauce Labs)
- Docker support
- Kubernetes scaling
- CI/CD templates (GitHub Actions / Jenkins / Azure DevOps)
- Allure reporting dashboard
- Multi-org Salesforce support (sandbox / production routing)

---

## Best Practices

### Recommended

- Use semantic locators — prefer `ariaLabel`, `testid`, `placeholder` over CSS
- Use Scenario Outlines for any test requiring more than one data set
- Use `ScenarioContext` for data sharing between steps — never use static fields
- Keep `MCP_LWC_BUFFER_MS` at 1 000 ms minimum for Salesforce Lightning
- Run parallel execution with `threads` at or below CPU core count
- Version control generated frameworks; regenerate only when the flow changes materially
- Store secrets in environment variables, never in feature files
- Use `MCP_LOG_LEVEL=DEBUG` during initial recording to verify event capture

### Avoid

- `NETWORKIDLE` — removed in v7.1 for good reason; do not re-add it
- Hardcoded `Thread.sleep()` in step definitions
- Absolute XPath selectors — brittle on DOM changes
- Sharing `Page` or `BrowserContext` across threads
- Storing credentials in source code or feature files
- Calling `withRetry()` from inside Playwright event callbacks

---

## FAQ

**Does this support Salesforce Lightning?**
Yes — fully. v7.1 was specifically architected for Salesforce. `NETWORKIDLE` is removed; `waitForSalesforceLightning()` handles Aura/LWC stabilization. Three dedicated timeout variables are provided for SF-specific tuning.

**Does this support headless execution?**
Yes. Set `"headless": true` in any tool input. Note: some Salesforce orgs block headless user agents — run headed if recording fails.

**Can generated frameworks run independently?**
Yes. Generated projects are standalone Maven automation frameworks with all dependencies declared in pom.xml.

**Does this support parallel execution?**
Yes. Set `"parallel": true, "threads": N` in `playback_recording`. Each thread gets its own isolated Playwright instance.

**Can Selenium be plugged in later?**
Yes. The `UiActions` interface decouples Page Objects from Playwright — implement `SeleniumUiActions` and swap at the `ScenarioContext` level without touching Page Objects or step classes.

**Why did my v6 generated project not compile?**
v6 had three compilation errors: `page` not in scope in `PRESS_KEY` steps; `ScenarioContext` referenced but never generated; `cucumber-picocontainer` missing from pom.xml. All three are fixed in v7.1.

**What changed in the navigate strategy?**
v6 used `LoadState.LOAD` + `LoadState.NETWORKIDLE`. v7.1 uses `WaitUntilState.DOMCONTENTLOADED` + `waitForSalesforceLightning()`. The result is faster on standard sites and actually works on Salesforce.

**Is the framework compatible with Java 17?**
Yes. Switch expressions, `instanceof` pattern matching, and sealed-class-adjacent design are used throughout. Minimum Java version is 17.

**Can I use this with non-Salesforce applications?**
Yes. The `waitForSalesforceLightning()` method exits immediately on non-SF pages (under 100 ms overhead). All other features work identically.

---

## License

This project is intended for educational, enterprise automation, and AI-assisted testing use cases.

Please add your preferred license:
- MIT
- Apache 2.0
- Proprietary Enterprise License

---

## Final Notes

Playwright MCP Server v7.1 is a production-hardened evolution of v6, specifically designed for enterprise environments including Salesforce Lightning orgs.

The five pillars of improvement over v6:

**Correctness** — All five v6 compilation and runtime bugs fixed. Generated output compiles and runs without manual intervention.

**Thread safety** — `ReentrantLock` + `AtomicReference` replace fragile `volatile` field pairs. Concurrent `start_recording` calls are safely rejected. The polling thread cannot double-start or leave a dangling reference.

**Salesforce reliability** — `DOMCONTENTLOADED` + 4-step Lightning stabilization replaces `NETWORKIDLE`. SSO redirects, Aura renders, LWC micro-tasks, and SLDS spinners are all handled.

**BDD completeness** — `ScenarioContext` generated; `pom.xml` complete with PicoContainer; all imports correct; `PRESS_KEY` compiles; Scenario Outline placeholders derived from locator metadata.

**API modernity** — Deprecated `Locator.type()` replaced by `fill()` and `pressSequentially()`. Configurable inter-key delay. `UiActions` interface extended with `pressSequentially()` and improved `press()` null-selector routing.

The result is a framework that generates output you can drop into a Maven project and run — no manual fixes required.