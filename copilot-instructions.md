# Playwright MCP Server — Copilot Agent Instructions
<!-- 
  FILE LOCATION GUIDE (read this first):
  ───────────────────────────────────────
  VS Code / GitHub Copilot:  .github/copilot-instructions.md   ← primary
                          OR .vscode/copilot-instructions.md
  IntelliJ AI Assistant:     .github/copilot-instructions.md   ← same file, auto-read
  Eclipse Copilot:           .github/copilot-instructions.md   ← same file, auto-read

  This file is automatically loaded into every Copilot chat context.
  KEEP IT CONCISE — every line costs tokens on every message.
  Rules here replace verbose user prompts. One rule here = 0 tokens per conversation.
-->

## Server Identity
- Name: `playwright-mcp-server`  Version: `4.0.0`
- Tools: `start_recording` · `stop_recording` · `playback_recording` · `generate_playwright_bdd` · `execute_atomic_action`
- Schema version in all recordings: `4.0.0`

---

## Tool Quick Reference

| What I say | Tool to call | Required args |
|---|---|---|
| "open/navigate/go to [url]" | `start_recording` | `url` |
| "start recording [name]" | `start_recording` | `url`, `sessionName` |
| "stop / done / save recording" | `stop_recording` | _(none)_ |
| "replay / playback / run [file]" | `playback_recording` | `jsonRecording` |
| "generate BDD / convert to script" | `generate_playwright_bdd` | `jsonRecording`, `featureName` |
| "click / fill / press on active page" | `execute_atomic_action` | `action`, `selector`, `value` |

---

## Recording Workflow — Exact Steps

```
1. start_recording  url="<url>"  sessionName="<Feature - Scenario>"
2. [User interacts with browser — do NOT call any tools during this phase]
3. stop_recording   → returns JSON block
4. Save JSON to ./recordings/<name>.json
```

**Do NOT call any other tool between `start_recording` and `stop_recording`.**  
**Do NOT navigate programmatically during recording** — user drives the browser.

---

## Locator Priority (v4 engine — highest to lowest)

1. `href`  → `page.locator("a[href='/path']")`  — nav links, always unique  
2. `css-id` → `page.locator("#escaped-id")`  — IDs with special chars handled  
3. `testId` → `page.getByTestId("...")`  
4. `xpath`  → `page.locator("xpath=...")`  — scoped to parent with ID  
5. `role`   → `page.getByRole(AriaRole.X, …setName("…")).nth(n)` if ambiguous  
6. `css`    → `page.locator("nth-scoped > path").nth(n)` if matchCount > 1  
7. `text`   → `page.getByText("…").nth(n)` — LAST RESORT only  

**When reading a recording JSON, always use `locator.playwrightLocator` field.**  
Never use bare `page.getByText()` without `.nth()` when `matchCount > 1`.

---

## Playback Rules

- **FOCUS events → skip**. They are informational, not interactive.
- **HOVER events → skip if immediately followed by CLICK on same locator.**
- After NAVIGATE → wait for the next actionable element with `page.waitForLoadState()`.
- If `locator.matchCount > 1` → scope with `.nth(locator.nthIndex)`.
- On any failure → call `execute_atomic_action` with `action=screenshot` immediately.

---

## BDD Generation Rules

**Always generate all 4 files:**
```
src/test/resources/features/<FeatureName>.feature
src/test/java/com/qa/pages/<FeatureName>Page.java
src/test/java/com/qa/stepdefs/<FeatureName>Steps.java
src/test/java/com/qa/runners/<FeatureName>Runner.java
```

**Page Object rules:**
- Constructor injection only — no static `page` fields
- Locators as `private final Locator` fields, initialised in constructor
- Action methods return `this` (fluent) unless navigation occurs → return new Page Object
- Never put assertions inside Page Objects
- Use `scopedPlaywrightExpr()` logic: append `.nth(nthIndex)` when `matchCount > 1`

**Step definition rules:**
- FOCUS steps → omit entirely
- HOVER+CLICK collapse → one CLICK step
- Credentials/passwords → `getProperty("key")` never hardcoded
- Assertions → `assertThat(page.locator(...)).hasText(...)` not `assertTrue`

---

## Short-form Commands I Understand

| Short phrase | What it means |
|---|---|
| `record <url>` | `start_recording url=<url> sessionName="Recording"` |
| `record "<name>" on <url>` | `start_recording` with both args |
| `stop` | `stop_recording` |
| `replay <json>` | `playback_recording jsonRecording=<json>` |
| `bdd "<name>"` | `generate_playwright_bdd featureName=<name>` using last recording |
| `click <selector>` | `execute_atomic_action action=click selector=<selector>` |
| `fill <selector> with <value>` | `execute_atomic_action action=fill selector=<selector> value=<value>` |

---

## Token-Saving Conventions

**Use these short forms in your prompts to reduce token consumption:**

Instead of:
> "Please start a recording session on the URL https://example.com and name the session Login Flow"

Write:
> `record "Login Flow" on https://example.com`

Instead of:
> "Now stop the recording and give me the JSON"

Write:
> `stop`

Instead of:
> "Generate a BDD script from the above JSON with the feature name Login"

Write:
> `bdd "Login"`

Instead of:
> "Replay the recording from the JSON I just got back"

Write:
> `replay <paste json here>`

---

## Framework Stack (for code generation)

```
Java 17 · Playwright 1.44 · Cucumber 7 · JUnit 5 · Maven
Package root: com.qa
Page Objects:    com.qa.pages
Step Defs:       com.qa.stepdefs
Runners:         com.qa.runners
Feature files:   src/test/resources/features/
Recordings:      ./recordings/
```

---

## What NOT to Do

- ❌ Do not call `execute_atomic_action` during an active recording session
- ❌ Do not use `page.getByText("x")` without `.nth()` when text is shared across elements
- ❌ Do not hardcode passwords or tokens in generated code
- ❌ Do not add `Thread.sleep()` — use `page.waitForSelector()` or `page.waitForLoadState()`
- ❌ Do not put assertions inside Page Object classes
- ❌ Do not call `start_recording` twice without `stop_recording` in between
- ❌ Do not generate XPath as primary locator if `href`, `id`, or `testId` is available

---

## Where to Place This File

```
your-project/
├── .github/
│   └── copilot-instructions.md    ← THIS FILE (VS Code, IntelliJ, Eclipse all read here)
├── src/
├── recordings/
└── pom.xml
```

**VS Code:** Auto-loaded when `github.copilot.chat.codeGeneration.instructions` is enabled (default in Copilot 1.x+).  
**IntelliJ:** Auto-loaded by AI Assistant when file is at `.github/copilot-instructions.md`.  
**Eclipse:** Read by GitHub Copilot plugin from `.github/copilot-instructions.md`.  
**Claude Desktop:** Place at project root — reference in your MCP server config as a resource.
