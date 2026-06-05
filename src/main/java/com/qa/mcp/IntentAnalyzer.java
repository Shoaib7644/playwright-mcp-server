package com.qa.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * IntentAnalyzer — sliding-window greedy intent classifier for recorded Playwright events.
 *
 * Intent types (in priority order):
 *   LOGIN, SEARCH, TRANSFER, FORM_SUBMIT, NAVIGATION,
 *   SELECT_FLOW, UPLOAD_FLOW, RAW_ACTION
 *
 * Window size = 6 events. Each intent consumes its matched events; remainder is
 * emitted as individual RAW_ACTION intents.
 */
public class IntentAnalyzer {

    // ── Intent type enum ──────────────────────────────────────────────────────
    public enum IntentType {
        LOGIN, SEARCH, TRANSFER, FORM_SUBMIT, NAVIGATION,
        SELECT_FLOW, UPLOAD_FLOW, RAW_ACTION
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  INTENT MODEL
    // ═════════════════════════════════════════════════════════════════════════
    public static class Intent {

        public final IntentType           type;
        public final Map<String, String>  params;
        public final List<ObjectNode>     sourceEvents;

        public Intent(IntentType type,
                      Map<String, String> params,
                      List<ObjectNode> sourceEvents) {
            this.type         = type;
            this.params       = Collections.unmodifiableMap(new LinkedHashMap<>(params));
            this.sourceEvents = Collections.unmodifiableList(new ArrayList<>(sourceEvents));
        }

        // ── Gherkin step generation ───────────────────────────────────────────

        /**
         * Returns the Gherkin step string for this intent.
         *
         * When outline=true:
         *   - Recognized intents use their fixed {@code <placeholder>} tokens.
         *   - RAW_ACTION FILL steps derive placeholder names from locator metadata
         *     so the Examples table column name matches the step token exactly.
         *   - All other RAW_ACTION types remain literal (no parameterizable value).
         *
         * [IA-1 + IA-2 FIX] Original zero-arg rawGherkin() removed.
         * toGherkinStep(boolean) now routes to rawGherkin(boolean outline).
         */
        public String toGherkinStep(boolean outline) {
            return switch (type) {
                case LOGIN -> outline
                        ? "When user logs in with \"<username>\" and \"<password>\""
                        : "When user logs in with \""
                          + params.getOrDefault("username", "") + "\" and \"<password>\"";

                case SEARCH -> outline
                        ? "When user searches for \"<query>\""
                        : "When user searches for \"" + params.getOrDefault("query", "") + "\"";

                case TRANSFER -> outline
                        ? "When user transfers \"<amount>\""
                        : "When user transfers \"" + params.getOrDefault("amount", "") + "\"";

                case FORM_SUBMIT ->
                        "When user submits the \""
                                + params.getOrDefault("formName", "form") + "\" form";

                case NAVIGATION ->
                        "When user navigates to \"" + params.getOrDefault("url", "") + "\"";

                case SELECT_FLOW -> outline
                        ? "When user selects \"<"
                          + toCamel(params.getOrDefault("field", "option"))
                          + ">\" from \""
                          + params.getOrDefault("field", "dropdown") + "\""
                        : "When user selects \""
                          + params.getOrDefault("value", "") + "\" from \""
                          + params.getOrDefault("field", "dropdown") + "\"";

                case UPLOAD_FLOW -> outline
                        ? "When user uploads file \"<filePath>\""
                        : "When user uploads file \"" + params.getOrDefault("file", "") + "\"";

                case RAW_ACTION -> rawGherkin(outline);
            };
        }

        /**
         * Derives a Scenario Outline placeholder name from the richest available
         * locator metadata on a FILL event.
         *
         * Priority: ariaLabel → placeholder attr → name attr → id → inner text → CSS fallback.
         * Result is lower-camelCase, matching the column header emitted by
         * {@code BddCodeGenerator.featureFile()}.
         *
         * [TASK-6 / BG-8] Declared public static so BddCodeGenerator.featureFile()
         * can call IntentAnalyzer.Intent.placeholderName(ev) without an instance.
         *
         * @param event a raw recorded ObjectNode — safe to call on any actionType
         * @return camelCase placeholder name, never null, never blank
         */
        public static String placeholderName(ObjectNode event) {
            if (event == null) return "value";
            JsonNode loc  = event.path("locator");
            JsonNode snap = event.path("elementSnapshot");

            // Priority 1 — aria-label (most human-readable)
            String v = loc.path("ariaLabel").asText("").trim();
            if (!v.isBlank()) return toCamel(sanitizeLabel(v));

            // Priority 2 — placeholder attribute
            v = loc.path("placeholder").asText("").trim();
            if (!v.isBlank()) return toCamel(sanitizeLabel(v));

            // Priority 3 — name attribute (form field name)
            v = loc.path("name").asText("").trim();
            if (!v.isBlank()) return toCamel(sanitizeLabel(v));

            // Priority 4 — element id
            v = loc.path("id").asText("").trim();
            if (!v.isBlank()) return toCamel(sanitizeLabel(v));

            // Priority 5 — inner text (truncated)
            v = snap.path("innerText").asText("").trim();
            if (!v.isBlank() && v.length() <= 40) return toCamel(sanitizeLabel(v));

            // Priority 6 — CSS selector last segment
            v = loc.path("cssSelector").asText("").trim();
            if (!v.isBlank()) {
                String last = v.replaceAll(".*[>\\s]", "")
                        .replaceAll("^[.#]", "")
                        .replaceAll("[^a-zA-Z0-9]", " ")
                        .trim();
                if (!last.isBlank()) return toCamel(last);
            }

            return "value";
        }

        /** Strip leading filler verbs and articles from label strings. */
        private static String sanitizeLabel(String raw) {
            return raw.replaceAll("(?i)^(enter|input|type|your|the|a|an)\\s+", "")
                    .replaceAll("[^a-zA-Z0-9 ]", " ")
                    .trim();
        }

        /**
         * Raw Gherkin step for RAW_ACTION intents.
         *
         * [TASK-6 FIX] outline boolean parameter replaces the original zero-arg method.
         * Only FILL actions are parameterized in outline mode — CLICK, SCROLL, PRESS_KEY,
         * NAVIGATE are structural steps with no user-supplied value that varies across rows.
         */
        private String rawGherkin(boolean outline) {
            if (sourceEvents.isEmpty()) return "When user performs an action";

            ObjectNode e  = sourceEvents.get(0);
            String at     = e.path("actionType").asText();
            String loc    = e.path("locator").path("text").asText(
                    e.path("locator").path("ariaLabel").asText(
                            e.path("locator").path("id").asText("element")));
            String val    = e.path("inputValue").asText("");

            // [TASK-6] In outline mode, FILL steps use a derived placeholder instead of
            // the hardcoded recorded value — placeholder name matches the Examples column.
            if (outline && "FILL".equals(at)) {
                String placeholder = placeholderName(e);
                return "When user enters \"<" + placeholder + ">\" in the \""
                        + loc + "\" field";
            }

            return switch (at) {
                case "CLICK"         -> "When user clicks the \""    + loc + "\" element";
                case "FILL"          -> "When user enters \""        + val + "\" in the \"" + loc + "\" field";
                case "SCROLL"        -> "When user scrolls the page";
                case "PRESS_KEY"     -> "When user presses \""       + e.path("key").asText("") + "\"";
                case "SELECT_OPTION" -> "When user selects \""       + val + "\" from \"" + loc + "\"";
                case "CHECK"         -> "When user checks the \""    + loc + "\" checkbox";
                case "NAVIGATE"      -> "When user navigates to \""  + e.path("inputValue").asText("") + "\"";
                default              -> "When user performs "        + at.toLowerCase().replace("_", " ");
            };
        }

        // ── JSON serialization ────────────────────────────────────────────────
        public ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("intentType", type.name());
            ObjectNode p = mapper.createObjectNode();
            params.forEach(p::put);
            n.set("params", p);
            ArrayNode se = mapper.createArrayNode();
            sourceEvents.forEach(se::add);
            n.set("sourceEvents", se);
            return n;
        }

        // ── Helpers ───────────────────────────────────────────────────────────
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
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ANALYZER ENTRY POINT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Analyzes a list of recorded events and returns a greedy, ordered list of
     * detected intents. The window size is 6 — each candidate window is tested
     * against all matchers in priority order; the first match consumes its events.
     * Unmatched events become individual RAW_ACTION intents.
     *
     * @param events ordered list of recorded ObjectNode events
     * @return ordered list of detected Intent objects
     */
    public static List<Intent> analyze(List<ObjectNode> events) {
        if (events == null || events.isEmpty()) return Collections.emptyList();

        List<Intent>    result    = new ArrayList<>();
        List<ObjectNode> remaining = new ArrayList<>(events);

        while (!remaining.isEmpty()) {
            int windowSize = Math.min(6, remaining.size());
            List<ObjectNode> window = remaining.subList(0, windowSize);

            Intent matched = tryMatch(window);
            if (matched != null) {
                result.add(matched);
                remaining.subList(0, matched.sourceEvents.size()).clear();
            } else {
                // No intent matched — emit the first event as a raw action
                result.add(rawAction(remaining.get(0)));
                remaining.remove(0);
            }
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PATTERN MATCHERS
    // ═════════════════════════════════════════════════════════════════════════

    /** Try all matchers in priority order against the current window. */
    private static Intent tryMatch(List<ObjectNode> w) {
        Intent m;
        if ((m = matchLogin(w))      != null) return m;
        if ((m = matchSearch(w))     != null) return m;
        if ((m = matchTransfer(w))   != null) return m;
        if ((m = matchFormSubmit(w)) != null) return m;
        if ((m = matchNavigation(w)) != null) return m;
        if ((m = matchSelectFlow(w)) != null) return m;
        if ((m = matchUploadFlow(w)) != null) return m;
        return null;
    }

    /**
     * LOGIN: FILL(username-like) + FILL(password) + CLICK(submit-like)
     * Optionally preceded by NAVIGATE.
     */
    private static Intent matchLogin(List<ObjectNode> w) {
        int i = 0;
        if (w.size() > i && isAction(w.get(i), "NAVIGATE")) i++;
        if (w.size() <= i + 2) return null;

        ObjectNode fillUser = w.get(i);
        ObjectNode fillPass = w.get(i + 1);
        ObjectNode click    = w.get(i + 2);

        if (!isAction(fillUser, "FILL"))  return null;
        if (!isAction(fillPass, "FILL"))  return null;
        if (!isAction(click,    "CLICK")) return null;

        String userLoc = locatorText(fillUser).toLowerCase();
        String passSnap = fillPass.path("elementSnapshot").path("type").asText("").toLowerCase();
        String passLoc  = locatorText(fillPass).toLowerCase();

        boolean isUser = userLoc.contains("user") || userLoc.contains("email")
                || userLoc.contains("login") || userLoc.contains("username");
        boolean isPass = "password".equals(passSnap)
                || passLoc.contains("pass") || passLoc.contains("pwd");
        if (!isUser || !isPass) return null;

        String username = fillUser.path("inputValue").asText("");
        if ("***REDACTED***".equals(username)) username = "<username>";
        String password = "***REDACTED***"; // always mask

        Map<String, String> p = new LinkedHashMap<>();
        p.put("username", username);
        p.put("password", password);

        return new Intent(IntentType.LOGIN, p, w.subList(0, i + 3));
    }

    /**
     * SEARCH: FILL(search-like) + optional PRESS_KEY(Enter) or CLICK(search button)
     */
    private static Intent matchSearch(List<ObjectNode> w) {
        if (w.isEmpty()) return null;
        ObjectNode fill = w.get(0);
        if (!isAction(fill, "FILL")) return null;

        String loc = locatorText(fill).toLowerCase();
        boolean isSearch = loc.contains("search") || loc.contains("query")
                || loc.contains("find") || loc.contains("lookup");
        if (!isSearch) return null;

        String query = fill.path("inputValue").asText("");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("query", query);

        int consumed = 1;
        if (w.size() > 1) {
            ObjectNode next = w.get(1);
            if (isAction(next, "PRESS_KEY") && "Enter".equals(next.path("key").asText(""))) consumed = 2;
            else if (isAction(next, "CLICK") && locatorText(next).toLowerCase().contains("search")) consumed = 2;
        }
        return new Intent(IntentType.SEARCH, p, w.subList(0, consumed));
    }

    /**
     * TRANSFER: FILL(amount-like) + optional FILL + CLICK(transfer/send-like)
     */
    private static Intent matchTransfer(List<ObjectNode> w) {
        if (w.size() < 2) return null;
        ObjectNode fill = w.get(0);
        if (!isAction(fill, "FILL")) return null;

        String loc = locatorText(fill).toLowerCase();
        boolean isAmount = loc.contains("amount") || loc.contains("transfer")
                || loc.contains("send") || loc.contains("value");
        if (!isAmount) return null;

        String amount = fill.path("inputValue").asText("");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("amount", amount);

        // Find the confirming click
        int consumed = 1;
        for (int i = 1; i < Math.min(w.size(), 4); i++) {
            ObjectNode ev = w.get(i);
            if (isAction(ev, "CLICK")) {
                String cl = locatorText(ev).toLowerCase();
                if (cl.contains("transfer") || cl.contains("send")
                        || cl.contains("submit") || cl.contains("confirm")) {
                    consumed = i + 1;
                    break;
                }
            }
        }
        return new Intent(IntentType.TRANSFER, p, w.subList(0, consumed));
    }

    /**
     * FORM_SUBMIT: FORM_SUBMIT event, or CLICK on a submit-role element.
     */
    private static Intent matchFormSubmit(List<ObjectNode> w) {
        if (w.isEmpty()) return null;
        ObjectNode ev = w.get(0);
        if (isAction(ev, "FORM_SUBMIT")) {
            String name = ev.path("locator").path("ariaLabel").asText(
                    ev.path("locator").path("id").asText("form"));
            Map<String, String> p = new LinkedHashMap<>();
            p.put("formName", name);
            return new Intent(IntentType.FORM_SUBMIT, p, List.of(ev));
        }
        if (isAction(ev, "CLICK")) {
            String snap = ev.path("elementSnapshot").path("type").asText("").toLowerCase();
            String loc  = locatorText(ev).toLowerCase();
            String role = ev.path("locator").path("ariaRole").asText("").toLowerCase();
            boolean isSubmit = "submit".equals(snap) || "submit".equals(role)
                    || loc.contains("submit") || loc.contains("save")
                    || loc.contains("confirm") || loc.contains("continue");
            if (!isSubmit) return null;
            Map<String, String> p = new LinkedHashMap<>();
            p.put("formName", locatorText(ev));
            return new Intent(IntentType.FORM_SUBMIT, p, List.of(ev));
        }
        return null;
    }

    /**
     * NAVIGATION: NAVIGATE event, or CLICK on a link/breadcrumb.
     */
    private static Intent matchNavigation(List<ObjectNode> w) {
        if (w.isEmpty()) return null;
        ObjectNode ev = w.get(0);
        if (isAction(ev, "NAVIGATE")) {
            String url = ev.path("inputValue").asText(ev.path("pageUrl").asText(""));
            Map<String, String> p = new LinkedHashMap<>();
            p.put("url", url);
            return new Intent(IntentType.NAVIGATION, p, List.of(ev));
        }
        if (isAction(ev, "CLICK")) {
            String role = ev.path("locator").path("ariaRole").asText("").toLowerCase();
            String href = ev.path("locator").path("href").asText("");
            boolean isNav = "link".equals(role) || !href.isBlank();
            if (!isNav) return null;
            Map<String, String> p = new LinkedHashMap<>();
            p.put("url", href.isBlank() ? locatorText(ev) : href);
            return new Intent(IntentType.NAVIGATION, p, List.of(ev));
        }
        return null;
    }

    /**
     * SELECT_FLOW: SELECT_OPTION event, or CLICK on a combobox/listbox.
     */
    private static Intent matchSelectFlow(List<ObjectNode> w) {
        if (w.isEmpty()) return null;
        ObjectNode ev = w.get(0);
        if (!isAction(ev, "SELECT_OPTION")) {
            if (!isAction(ev, "CLICK")) return null;
            String role = ev.path("locator").path("ariaRole").asText("").toLowerCase();
            if (!role.contains("combobox") && !role.contains("listbox")
                    && !role.contains("option")) return null;
        }
        String field = locatorText(ev);
        String value = ev.path("inputValue").asText(
                ev.path("selectValues").path(0).asText(""));
        Map<String, String> p = new LinkedHashMap<>();
        p.put("field", field);
        p.put("value", value);
        return new Intent(IntentType.SELECT_FLOW, p, List.of(ev));
    }

    /**
     * UPLOAD_FLOW: UPLOAD_FILE event.
     */
    private static Intent matchUploadFlow(List<ObjectNode> w) {
        if (w.isEmpty()) return null;
        ObjectNode ev = w.get(0);
        if (!isAction(ev, "UPLOAD_FILE")) return null;
        String file = ev.path("inputValue").asText("");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("file", file);
        return new Intent(IntentType.UPLOAD_FLOW, p, List.of(ev));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private static Intent rawAction(ObjectNode event) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("action", event.path("actionType").asText("UNKNOWN"));
        p.put("locator", event.path("locator").path("primary").asText(""));
        p.put("value",   event.path("inputValue").asText(""));
        return new Intent(IntentType.RAW_ACTION, p, List.of(event));
    }

    private static boolean isAction(ObjectNode event, String actionType) {
        return actionType.equals(event.path("actionType").asText(""));
    }

    /** Best human-readable label for an event's locator. */
    private static String locatorText(ObjectNode event) {
        JsonNode loc = event.path("locator");
        String v;
        v = loc.path("ariaLabel").asText("").trim();    if (!v.isBlank()) return v;
        v = loc.path("placeholder").asText("").trim();  if (!v.isBlank()) return v;
        v = loc.path("text").asText("").trim();         if (!v.isBlank()) return v;
        v = loc.path("id").asText("").trim();           if (!v.isBlank()) return v;
        v = loc.path("name").asText("").trim();         if (!v.isBlank()) return v;
        v = loc.path("cssSelector").asText("").trim();  if (!v.isBlank()) return v;
        return "element";
    }
}