# Playwright MCP Server v6

## Intelligent Playwright + MCP + BDD Automation Framework Generator

A production-ready Java-based Model Context Protocol (MCP) server that bridges LLMs with a live Chromium browser using Playwright Java.

This framework records real browser interactions, analyzes user intent, generates maintainable BDD automation frameworks, supports intelligent playback with recovery, and exposes atomic browser tools for AI agents.

---

# Table of Contents

1. Overview
2. Key Features
3. Architecture
4. Core Components
5. Technology Stack
6. Prerequisites
7. Installation & Setup
8. MCP Integration
9. Exposed MCP Tools
10. Smart Wait Engine
11. Intent Analysis Engine
12. Intelligent BDD Generation
13. Generated Framework Structure
14. UiActions Abstraction Layer
15. Locator Strategy
16. Parallel Playback Support
17. Failure Recovery System
18. Supported Browser Actions
19. Environment Variables
20. Build & Run
21. Example Workflow
22. Sample Recording JSON
23. Troubleshooting Guide
24. Performance & Design Decisions
25. Security Considerations
26. Future Enhancements
27. Best Practices
28. FAQ
29. License

---

# Overview

Playwright MCP Server is an intelligent automation orchestration layer that enables Large Language Models (LLMs) to:

* Launch and control browsers
* Record user interactions
* Replay workflows reliably
* Generate enterprise-grade BDD frameworks
* Execute atomic browser operations
* Analyze raw browser events into business intents
* Create reusable Page Object Models automatically

The framework is optimized for:

* AI-assisted QA automation
* Self-healing browser interactions
* Intent-driven BDD generation
* Framework-agnostic automation design
* Parallel execution
* Enterprise automation scalability

---

# Key Features

## Browser Recording Engine

* Records clicks, fills, navigations, key presses, uploads, dropdowns, and scrolls
* Captures semantic locators automatically
* Supports headed and headless execution
* CSP bypass support
* Smart event de-duplication
* Session-based recording management

## Smart Wait Engine (v6)

Automatic synchronization before and after every action:

* DOM ready checks
* Network idle detection
* Element visibility validation
* Enabled-state verification
* DOM stabilization logic
* Action-aware waits

## Intent Analyzer

Converts raw DOM events into business-level intents:

| Raw Events                        | Detected Intent |
| --------------------------------- | --------------- |
| Username + Password + Login Click | LOGIN           |
| Search Fill + Enter               | SEARCH          |
| Multiple Form Fields + Submit     | FORM_SUBMIT     |
| Amount + Transfer Button          | TRANSFER        |
| Upload + Confirm                  | UPLOAD_FLOW     |
| Dropdown + Confirm                | SELECT_FLOW     |

## Intelligent BDD Generator

Automatically generates:

* Feature files
* Step definitions
* Page Objects
* Runner classes
* UiActions abstraction layer
* Playwright implementation layer
* Scenario Outlines
* Data-driven examples

## Parallel Playback Engine

* ThreadLocal Playwright sessions
* Multi-threaded replay
* Chunk-based event distribution
* Isolated browser contexts
* Concurrent execution support

## Recovery & Failure Handling

* Screenshot capture on failure
* HTML dump generation
* Automatic DOM re-sync
* URL validation
* Retry engine
* Smart recovery handling

## Framework Abstraction Layer

Generated frameworks use:

```java
ui.click(locator);
ui.fill(locator, value);
ui.navigate(url);
```

This enables future migration to:

* Selenium
* Cypress
* WebDriverIO
* Appium
* Any UI engine

without changing Page Objects.

---

# Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                     MCP HOST / LLM                         │
│        Claude / Cursor / Cline / Custom AI Agent           │
└──────────────────────┬──────────────────────────────────────┘
                       │
                 JSON-RPC over stdio
                       │
┌──────────────────────▼──────────────────────────────────────┐
│                 Playwright MCP Server                      │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │                MCP Tool Layer                       │  │
│  │                                                      │  │
│  │  start_recording                                    │  │
│  │  stop_recording                                     │  │
│  │  playback_recording                                 │  │
│  │  generate_playwright_bdd                            │  │
│  │  execute_atomic_action                              │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              Intent Analysis Engine                 │  │
│  │                                                      │  │
│  │  LOGIN                                               │  │
│  │  SEARCH                                              │  │
│  │  FORM_SUBMIT                                         │  │
│  │  TRANSFER                                            │  │
│  │  RAW_ACTION                                          │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              Smart Wait Engine                      │  │
│  │                                                      │  │
│  │  DOM Ready                                           │  │
│  │  Network Idle                                        │  │
│  │  Element Visibility                                  │  │
│  │  Enabled State                                       │  │
│  │  DOM Stabilization                                   │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              BDD Code Generator                     │  │
│  │                                                      │  │
│  │  Feature Files                                       │  │
│  │  Step Definitions                                    │  │
│  │  Page Objects                                        │  │
│  │  Runners                                              │  │
│  │  UiActions                                            │  │
│  └──────────────────────────────────────────────────────┘  │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│                  Playwright Java                           │
│                    Chromium Browser                        │
└─────────────────────────────────────────────────────────────┘
```

---

# Core Components

## 1. PlaywrightMcpServer.java

Main orchestration engine responsible for:

* MCP tool registration
* Browser lifecycle management
* Event recording
* Playback execution
* Smart waits
* Retry handling
* Parallel execution
* Failure recovery
* Session management

## 2. IntentAnalyzer.java

Transforms low-level browser events into meaningful business actions.

### Supported Intent Types

| Intent      | Description                  |
| ----------- | ---------------------------- |
| LOGIN       | User authentication flow     |
| SEARCH      | Search interaction flow      |
| TRANSFER    | Monetary/data transfer flow  |
| FORM_SUBMIT | Generic form submission      |
| NAVIGATION  | URL navigation               |
| SELECT_FLOW | Dropdown selection flow      |
| UPLOAD_FLOW | File upload flow             |
| RAW_ACTION  | Unmatched individual actions |

## 3. BddCodeGenerator.java

Generates complete automation frameworks.

### Generated Artifacts

* Feature files
* Step definitions
* Page Objects
* Runner classes
* UiActions interface
* Playwright implementation
* Data-driven scenarios
* Assertions
* Utility methods

---

# Technology Stack

| Category           | Technology                   |
| ------------------ | ---------------------------- |
| Language           | Java 17+                     |
| Build Tool         | Maven                        |
| Browser Automation | Playwright Java              |
| BDD Framework      | Cucumber                     |
| MCP Protocol       | Model Context Protocol       |
| JSON Processing    | Jackson                      |
| Reporting          | Allure + Cucumber Reports    |
| Parallel Execution | Java Executors + ThreadLocal |
| Browser            | Chromium                     |
| Logging            | java.util.logging            |

---

# Prerequisites

| Tool    | Version                 |
| ------- | ----------------------- |
| JDK     | 17+                     |
| Maven   | 3.9+                    |
| Node.js | Optional                |
| OS      | Windows / Linux / macOS |
| RAM     | 8 GB Recommended        |

---

# Installation & Setup

## 1. Clone Repository

```bash
git clone <repository-url>
cd playwright-mcp-server
```

## 2. Build Project

```bash
mvn clean package -DskipTests
```

## 3. Install Playwright Browsers

```bash
mvn exec:java@install-browsers
```

## 4. Verify Build

Generated JAR:

```text
target/playwright-mcp-server.jar
```

---

# MCP Integration

## Claude Desktop Configuration

### macOS

```text
~/Library/Application Support/Claude/claude_desktop_config.json
```

### Windows

```text
%APPDATA%\Claude\claude_desktop_config.json
```

### Example Configuration

```json
{
  "mcpServers": {
    "playwright": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/playwright-mcp-server.jar"
      ]
    }
  }
}
```

Restart Claude Desktop after configuration.

---

# Exposed MCP Tools

# 1. start_recording

Launches Chromium and starts interaction recording.

## Input

```json
{
  "url": "https://example.com",
  "sessionName": "Login Flow",
  "headless": false
}
```

## Features

* CSP bypass
* JS injection
* Browser lifecycle management
* Event queue setup
* Session metadata generation

---

# 2. stop_recording

Stops recording and returns structured JSON.

## Input

```json
{}
```

## Output

* Raw browser events
* Structured intents
* Session metadata
* Timing data
* Recording statistics

---

# 3. playback_recording

Replays recorded events intelligently.

## Input

```json
{
  "jsonRecording": "...",
  "headless": true,
  "parallel": false,
  "threads": 1
}
```

## Features

* Smart waits
* Retry engine
* Recovery logic
* Parallel execution
* Failure artifact generation

---

# 4. generate_playwright_bdd

Generates complete BDD automation framework.

## Input

```json
{
  "jsonRecording": "...",
  "featureName": "User Login",
  "framework": "cucumber",
  "tags": "@smoke @regression"
}
```

## Supported Frameworks

| Framework | Runner       |
| --------- | ------------ |
| cucumber  | JUnit 5      |
| testng    | TestNG       |
| serenity  | Serenity BDD |

## Generated Files

| File                | Purpose                   |
| ------------------- | ------------------------- |
| Feature File        | Gherkin Scenarios         |
| Step Definitions    | Step Implementations      |
| Page Objects        | UI Layer                  |
| Runner              | Test Execution            |
| UiActions           | Abstraction Layer         |
| PlaywrightUiActions | Playwright Implementation |

---

# 5. execute_atomic_action

Executes single Playwright actions.

## Example

```json
{
  "action": "fill",
  "selector": "placeholder:Search",
  "value": "Playwright"
}
```

## Supported Actions

| Action        | Description            |
| ------------- | ---------------------- |
| navigate      | Navigate to URL        |
| click         | Click element          |
| fill          | Fill input             |
| type          | Type text              |
| clear         | Clear field            |
| press         | Press keyboard key     |
| hover         | Hover element          |
| check         | Check checkbox         |
| uncheck       | Uncheck checkbox       |
| select_option | Select dropdown option |
| upload_file   | Upload file            |
| screenshot    | Capture screenshot     |
| get_text      | Extract text           |
| is_visible    | Visibility validation  |
| get_html      | Extract HTML           |
| wait          | Explicit wait          |
| scroll        | Scroll page            |

---

# Smart Wait Engine

The Smart Wait Engine automatically stabilizes browser state.

## Wait Strategy

| Action        | Pre-Wait          | Post-Wait           |
| ------------- | ----------------- | ------------------- |
| CLICK         | Visible           | Load + Network Idle |
| FILL          | Visible + Enabled | Stable DOM          |
| NAVIGATE      | DOM Ready         | Network Idle        |
| SELECT_OPTION | Visible           | Stable DOM          |
| CHECK         | Visible           | Stable DOM          |
| UPLOAD_FILE   | Attached          | Stable DOM          |

## Benefits

* Reduces flaky tests
* Removes hard-coded waits
* Stabilizes asynchronous pages
* Improves replay reliability

---

# Intent Analysis Engine

The Intent Analyzer converts low-level browser actions into business workflows.

## Example

### Raw Events

```text
FILL username
FILL password
CLICK login
```

### Generated Intent

```text
LOGIN(username, password)
```

### Generated Gherkin

```gherkin
When user logs in with "john" and "<password>"
```

---

# Intelligent BDD Generation

The generator creates enterprise-ready automation frameworks.

## Generated Feature Example

```gherkin
@smoke @regression
Feature: User Login

  Background:
    Given the browser is open

  Scenario: User Login
    When user logs in with "john" and "<password>"
```

## Generated Page Object Example

```java
public class LoginPage {

    private final UiActions ui;

    private final String USERNAME_FIELD = "#username";
    private final String PASSWORD_FIELD = "#password";

    public LoginPage login(String username, String password) {
        ui.fill(USERNAME_FIELD, username);
        ui.fill(PASSWORD_FIELD, password);
        return this;
    }
}
```

---

# Generated Framework Structure

```text
generated-project/
│
├── pom.xml
│
├── src/
│   ├── main/
│   │   └── java/com/qa/
│   │       ├── actions/
│   │       │   ├── UiActions.java
│   │       │   └── PlaywrightUiActions.java
│   │       │
│   │       └── pages/
│   │           └── UserLoginPage.java
│   │
│   └── test/
│       ├── java/com/qa/
│       │   ├── runners/
│       │   │   └── UserLoginRunner.java
│       │   │
│       │   └── stepdefs/
│       │       └── UserLoginSteps.java
│       │
│       └── resources/
│           ├── features/
│           │   └── UserLogin.feature
│           │
│           └── TestData/
│               └── testdata.properties
```

---

# UiActions Abstraction Layer

## Purpose

Decouples Page Objects from Playwright.

## Benefits

* Framework independence
* Easy migration
* Cleaner architecture
* Better maintainability
* Improved scalability

## Example

```java
public interface UiActions {
    void click(String selector);
    void fill(String selector, String value);
    void navigate(String url);
}
```

---

# Locator Strategy

The framework prioritizes resilient semantic locators.

## Priority Order

| Priority | Locator      |
| -------- | ------------ |
| 1        | href         |
| 2        | id           |
| 3        | data-testid  |
| 4        | role         |
| 5        | placeholder  |
| 6        | label        |
| 7        | text         |
| 8        | CSS          |
| 9        | nth fallback |

## Examples

```text
role:button:Login
placeholder:Search
label:Email
testid:submit-btn
```

---

# Parallel Playback Support

## Features

* Multi-threaded execution
* Thread-isolated browser sessions
* Chunk-based replay
* ThreadLocal Playwright instances

## Example

```json
{
  "parallel": true,
  "threads": 4
}
```

---

# Failure Recovery System

On action failure the framework:

1. Captures screenshot
2. Dumps HTML
3. Logs failure details
4. Validates URL
5. Attempts DOM re-sync
6. Continues execution where possible

## Failure Artifact Directory

```text
./mcp-failures/
```

Generated artifacts:

```text
failure.png
failure.html
failure.log
```

---

# Environment Variables

| Variable              | Default        | Purpose               |
| --------------------- | -------------- | --------------------- |
| MCP_RETRY_MAX         | 3              | Retry attempts        |
| MCP_TIMEOUT_MS        | 30000          | Timeout configuration |
| MCP_RETRY_DELAY_MS    | 800            | Retry delay           |
| MCP_MAX_STEP_DELAY_MS | 500            | Playback pacing       |
| MCP_FAILURE_DIR       | ./mcp-failures | Failure artifacts     |
| MCP_LOG_LEVEL         | INFO           | Logging level         |

---

# Build & Run

## Package Project

```bash
mvn clean package
```

## Run Server

```bash
java -jar target/playwright-mcp-server.jar
```

## Run Generated Framework

```bash
mvn test
```

## Run Specific Tags

```bash
mvn test -Dcucumber.filter.tags="@smoke"
```

---

# Example Workflow

## Step 1 — Start Recording

```json
{
  "url": "https://example.com"
}
```

## Step 2 — Perform Browser Actions

* Login
* Search
* Submit forms
* Upload files

## Step 3 — Stop Recording

```json
{}
```

## Step 4 — Generate Framework

```json
{
  "featureName": "User Login"
}
```

## Step 5 — Execute Tests

```bash
mvn test
```

---

# Sample Recording JSON

```json
{
  "schemaVersion": "6.0.0",
  "sessionName": "Login Test",
  "status": "COMPLETED",
  "events": [
    {
      "actionType": "FILL",
      "selector": "#username",
      "inputValue": "john"
    },
    {
      "actionType": "CLICK",
      "selector": "#login"
    }
  ],
  "intents": [
    {
      "intentType": "LOGIN",
      "description": "User login"
    }
  ]
}
```

---

# Troubleshooting Guide

| Problem                      | Solution                            |
| ---------------------------- | ----------------------------------- |
| Browser executable not found | Run Playwright browser installation |
| MCP host disconnects         | Ensure logs go to STDERR only       |
| Events not captured          | Verify CSP bypass                   |
| Playback failures            | Increase timeout values             |
| Parallel replay instability  | Reduce thread count                 |
| Elements not found           | Verify selector strategy            |
| Flaky playback               | Enable Smart Wait defaults          |
| Headless issues              | Run in headed mode                  |
| Permissions issues           | Verify OS browser permissions       |
| Chromium crashes             | Increase system memory              |

## Browser Installation

```bash
mvn exec:java@install-browsers
```

## Debug Logging

```bash
export MCP_LOG_LEVEL=DEBUG
```

---

# Performance & Design Decisions

| Design Choice           | Reason                              |
| ----------------------- | ----------------------------------- |
| ThreadLocal Playwright  | Safe parallel execution             |
| Smart waits             | Reduce flaky automation             |
| Intent-based generation | Human-readable BDD                  |
| UiActions abstraction   | Framework independence              |
| Retry engine            | Reliability improvement             |
| CSP bypass              | Capture events from restricted apps |
| Semantic locators       | Better selector stability           |
| Structured recovery     | Non-destructive replay              |

---

# Security Considerations

* CSP bypass is enabled intentionally for automation recording
* Sensitive passwords are masked during intent generation
* Browser contexts are isolated
* Failure artifacts may contain sensitive information
* Generated frameworks should not commit secrets

---

# Future Enhancements

Planned improvements:

* Selenium UiActions implementation
* Firefox and WebKit support
* AI-powered self-healing selectors
* Visual validation engine
* API testing integration
* Test impact analysis
* Cloud execution support
* Docker support
* Kubernetes scaling
* CI/CD templates
* Reporting dashboard

---

# Best Practices

## Recommended

* Use semantic locators
* Prefer role/testid selectors
* Keep generated frameworks version controlled
* Store secrets externally
* Run parallel execution carefully
* Use Scenario Outlines for test data

## Avoid

* Hard-coded waits
* Absolute XPath selectors
* Sharing browser contexts across threads
* Storing credentials in source code

---

# FAQ

## Does this support headless execution?

Yes.

## Can generated frameworks run independently?

Yes. Generated projects are standalone Maven automation frameworks.

## Does this support parallel execution?

Yes.

## Can Selenium be plugged in later?

Yes. The UiActions abstraction was specifically designed for this.

## Does the framework support retries?

Yes. Playback includes retry and recovery logic.

## Can I generate data-driven frameworks?

Yes. Scenario Outline generation is supported.

---

# License

This project is intended for educational, enterprise automation, and AI-assisted testing use cases.

Please add your preferred license:

* MIT
* Apache 2.0
* Proprietary Enterprise License

---

# Final Notes

Playwright MCP Server v6 is designed as an AI-native automation framework generation platform.

It combines:

* Playwright browser automation
* MCP protocol tooling
* Intent analysis
* Smart synchronization
* Enterprise BDD generation
* Parallel execution
* Recovery-aware replay

into a single extensible architecture.

The result is a scalable foundation for AI-assisted QA automation systems, autonomous browser agents, and intelligent framework generation pipelines.
