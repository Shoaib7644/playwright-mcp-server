package com.qa.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * IntentAnalyzer v6
 *
 * Converts a flat list of raw DOM events into structured business intents.
 *
 * Detection patterns (order matters — evaluated top-down):
 * ─────────────────────────────────────────────────────────
 * LOGIN          [FILL user/email] + [FILL pass] + [CLICK submit]
 * SEARCH         [FILL query] + ([PRESS Enter] | [CLICK search-btn])
 * TRANSFER       [FILL amount] + [CLICK transfer/send/submit]
 * FORM_SUBMIT    2+ FILL events + [CLICK submit/save/confirm]
 * NAVIGATION     [NAVIGATE url]  (standalone)
 * SELECT_FLOW    [SELECT_OPTION] optionally + [CLICK confirm]
 * UPLOAD_FLOW    [UPLOAD_FILE] + optional [CLICK confirm]
 * RAW_ACTION     anything not matched above (1:1 with DOM event)
 */
public class IntentAnalyzer {

    public enum IntentType {
        LOGIN, SEARCH, TRANSFER, FORM_SUBMIT,
        NAVIGATION, SELECT_FLOW, UPLOAD_FLOW, RAW_ACTION
    }

    public static class Intent {
        public final IntentType          type;
        public final String              description;
        public final List<ObjectNode>    sourceEvents;  // raw events that formed this intent
        public final Map<String, String> params;        // extracted parameters

        public Intent(IntentType type, String description,
                      List<ObjectNode> sourceEvents, Map<String, String> params) {
            this.type         = type;
            this.description  = description;
            this.sourceEvents = sourceEvents;
            this.params       = params;
        }

        /** Serialize to JSON for inclusion in the recording output. */
        public ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("intentType",   type.name());
            n.put("description",  description);
            n.put("eventCount",   sourceEvents.size());

            ObjectNode p = mapper.createObjectNode();
            params.forEach(p::put);
            n.set("params", p);

            var seqs = mapper.createArrayNode();
            sourceEvents.forEach(e -> seqs.add(e.path("sequenceNo").asInt()));
            n.set("sourceSequenceNumbers", seqs);
            return n;
        }

        /** Gherkin step string for this intent. */
        public String toGherkinStep(boolean outline) {
            return switch (type) {
                case LOGIN       -> outline
                        ? "When user logs in with \"<username>\" and \"<password>\""
                        : "When user logs in with \"" + params.getOrDefault("username","") + "\" and \"<password>\"";
                case SEARCH      -> outline
                        ? "When user searches for \"<query>\""
                        : "When user searches for \"" + params.getOrDefault("query","") + "\"";
                case TRANSFER    -> outline
                        ? "When user transfers \"<amount>\""
                        : "When user transfers \"" + params.getOrDefault("amount","") + "\"";
                case FORM_SUBMIT -> "When user submits the \"" + params.getOrDefault("formName","form") + "\" form";
                case NAVIGATION  -> "When user navigates to \"" + params.getOrDefault("url","") + "\"";
                case SELECT_FLOW -> "When user selects \"" + params.getOrDefault("value","") + "\" from \"" + params.getOrDefault("field","dropdown") + "\"";
                case UPLOAD_FLOW -> "When user uploads file \"" + params.getOrDefault("file","") + "\"";
                case RAW_ACTION  -> rawGherkin();
            };
        }

        private String rawGherkin() {
            if (sourceEvents.isEmpty()) return "When user performs an action";
            ObjectNode e   = sourceEvents.get(0);
            String at  = e.path("actionType").asText();
            String loc = e.path("locator").path("text").asText(
                         e.path("locator").path("ariaLabel").asText(
                         e.path("locator").path("id").asText("element")));
            String val = e.path("inputValue").asText("");
            return switch (at) {
                case "CLICK"         -> "When user clicks the \"" + loc + "\" element";
                case "FILL"          -> "When user enters \"" + val + "\" in the \"" + loc + "\" field";
                case "SCROLL"        -> "When user scrolls the page";
                case "PRESS_KEY"     -> "When user presses \"" + e.path("key").asText("") + "\"";
                case "SELECT_OPTION" -> "When user selects \"" + val + "\" from \"" + loc + "\"";
                case "CHECK"         -> "When user checks the \"" + loc + "\" checkbox";
                case "NAVIGATE"      -> "When user navigates to \"" + e.path("inputValue").asText("") + "\"";
                default              -> "When user performs " + at.toLowerCase().replace("_"," ");
            };
        }

        /** Page Object method name for this intent. */
        public String toMethodName() {
            return switch (type) {
                case LOGIN       -> "login";
                case SEARCH      -> "search";
                case TRANSFER    -> "transfer";
                case FORM_SUBMIT -> "submit" + cap(params.getOrDefault("formName","Form"));
                case NAVIGATION  -> "navigateTo";
                case SELECT_FLOW -> "select" + cap(params.getOrDefault("field","Option"));
                case UPLOAD_FLOW -> "uploadFile";
                case RAW_ACTION  -> {
                    if (sourceEvents.isEmpty()) yield "performAction";
                    String at = sourceEvents.get(0).path("actionType").asText("ACTION");
                    String loc = sourceEvents.get(0).path("locator").path("text")
                                 .asText(sourceEvents.get(0).path("locator").path("id").asText("element"));
                    yield toCamel(at.toLowerCase() + " " + loc);
                }
            };
        }

        /** Java method signature for this intent. */
        public String toMethodSignature(String pageClass) {
            return switch (type) {
                case LOGIN       -> "public " + pageClass + "Page login(String username, String password)";
                case SEARCH      -> "public " + pageClass + "Page search(String query)";
                case TRANSFER    -> "public " + pageClass + "Page transfer(String amount)";
                case FORM_SUBMIT -> "public " + pageClass + "Page " + toMethodName() + "(" + formParams() + ")";
                case NAVIGATION  -> "public " + pageClass + "Page navigateTo(String url)";
                case SELECT_FLOW -> "public " + pageClass + "Page " + toMethodName() + "(String value)";
                case UPLOAD_FLOW -> "public " + pageClass + "Page uploadFile(String filePath)";
                case RAW_ACTION  -> "public " + pageClass + "Page " + toMethodName() + "()";
            };
        }

        private String formParams() {
            return params.entrySet().stream()
                    .filter(e -> !e.getKey().equals("formName"))
                    .map(e -> "String " + toCamel(e.getKey()))
                    .reduce((a, b) -> a + ", " + b).orElse("");
        }

        private static String cap(String s) {
            return s == null || s.isBlank() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
        }
        private static String toCamel(String s) {
            if (s == null || s.isBlank()) return "action";
            String[] p = s.replaceAll("[^a-zA-Z0-9 ]"," ").trim().split("\\s+");
            StringBuilder sb = new StringBuilder(p[0].toLowerCase());
            for (int i = 1; i < p.length; i++) if (!p[i].isBlank())
                sb.append(Character.toUpperCase(p[i].charAt(0))).append(p[i].substring(1).toLowerCase());
            return sb.toString();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    public static List<Intent> analyze(List<ObjectNode> events) {
        List<Intent> intents = new ArrayList<>();
        int i = 0;
        while (i < events.size()) {
            ObjectNode e = events.get(i);
            String at = e.path("actionType").asText();

            // Skip FOCUS — never forms an intent
            if ("FOCUS".equals(at)) { i++; continue; }

            // Try to match an intent pattern starting at position i
            MatchResult match = tryMatch(events, i);
            if (match != null) {
                intents.add(match.intent);
                i += match.consumed;
            } else {
                // Single RAW_ACTION for unmatched events
                intents.add(new Intent(IntentType.RAW_ACTION,
                        at.toLowerCase().replace("_", " "),
                        List.of(e), Map.of()));
                i++;
            }
        }
        return intents;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PATTERN MATCHERS
    // ─────────────────────────────────────────────────────────────────────────

    private static MatchResult tryMatch(List<ObjectNode> events, int start) {
        // Collect lookahead window (up to 6 events, skipping FOCUS)
        List<ObjectNode> window = new ArrayList<>();
        for (int j = start; j < events.size() && window.size() < 6; j++) {
            String at = events.get(j).path("actionType").asText();
            if (!"FOCUS".equals(at)) window.add(events.get(j));
        }
        if (window.isEmpty()) return null;

        // Try each pattern in priority order
        MatchResult r;
        if ((r = tryLogin(window))       != null) return r;
        if ((r = trySearch(window))      != null) return r;
        if ((r = tryTransfer(window))    != null) return r;
        if ((r = tryFormSubmit(window))  != null) return r;
        if ((r = tryNavigation(window))  != null) return r;
        if ((r = trySelectFlow(window))  != null) return r;
        if ((r = tryUploadFlow(window))  != null) return r;
        return null;
    }

    /**
     * LOGIN: [FILL user/email/login] + [FILL password] + [CLICK submit/login/sign-in]
     */
    private static MatchResult tryLogin(List<ObjectNode> w) {
        if (w.size() < 3) return null;
        ObjectNode e0 = w.get(0), e1 = w.get(1), e2 = w.get(2);
        if (!"FILL".equals(e0.path("actionType").asText())) return null;
        if (!"FILL".equals(e1.path("actionType").asText())) return null;
        if (!"CLICK".equals(e2.path("actionType").asText())) return null;

        String field0 = fieldLabel(e0).toLowerCase();
        String field1 = fieldLabel(e1).toLowerCase();
        String btn    = btnLabel(e2).toLowerCase();

        boolean isUserField = field0.contains("user") || field0.contains("email")
                || field0.contains("login") || field0.contains("username")
                || field0.contains("phone");
        boolean isPassField = field1.contains("pass") || field1.contains("pwd")
                || e1.path("elementSnapshot").path("type").asText("").equals("password");
        boolean isSubmit    = btn.contains("log") || btn.contains("sign")
                || btn.contains("submit") || btn.contains("enter") || btn.contains("login");

        if (!isUserField || !isPassField) return null;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("username", e0.path("inputValue").asText(""));
        params.put("password", "***");

        return new MatchResult(new Intent(IntentType.LOGIN,
                "User login with username and password",
                List.of(e0, e1, e2), params), 3);
    }

    /**
     * SEARCH: [FILL query in search-field] + ([PRESS Enter] | [CLICK search-btn])
     */
    private static MatchResult trySearch(List<ObjectNode> w) {
        if (w.size() < 2) return null;
        ObjectNode e0 = w.get(0), e1 = w.get(1);
        if (!"FILL".equals(e0.path("actionType").asText())) return null;

        String field = fieldLabel(e0).toLowerCase();
        boolean isSearchField = field.contains("search") || field.contains("query")
                || field.contains("find") || field.contains("q");
        if (!isSearchField) return null;

        boolean nextIsEnter  = "PRESS_KEY".equals(e1.path("actionType").asText())
                && "Enter".equals(e1.path("key").asText());
        boolean nextIsSearch = "CLICK".equals(e1.path("actionType").asText())
                && btnLabel(e1).toLowerCase().contains("search");

        if (!nextIsEnter && !nextIsSearch) return null;

        Map<String, String> params = Map.of("query", e0.path("inputValue").asText(""));
        return new MatchResult(new Intent(IntentType.SEARCH,
                "Search for: " + e0.path("inputValue").asText(""),
                List.of(e0, e1), params), 2);
    }

    /**
     * TRANSFER: [FILL amount/value] + [CLICK transfer/send/submit/pay]
     */
    private static MatchResult tryTransfer(List<ObjectNode> w) {
        if (w.size() < 2) return null;
        ObjectNode e0 = w.get(0), e1 = w.get(1);
        if (!"FILL".equals(e0.path("actionType").asText())) return null;
        if (!"CLICK".equals(e1.path("actionType").asText())) return null;

        String field = fieldLabel(e0).toLowerCase();
        String btn   = btnLabel(e1).toLowerCase();
        boolean isAmountField = field.contains("amount") || field.contains("value")
                || field.contains("sum") || field.contains("money") || field.contains("price");
        boolean isTransferBtn = btn.contains("transfer") || btn.contains("send")
                || btn.contains("pay") || btn.contains("confirm");

        if (!isAmountField || !isTransferBtn) return null;

        Map<String, String> params = Map.of("amount", e0.path("inputValue").asText(""));
        return new MatchResult(new Intent(IntentType.TRANSFER,
                "Transfer amount: " + e0.path("inputValue").asText(""),
                List.of(e0, e1), params), 2);
    }

    /**
     * FORM_SUBMIT: 2+ FILL events (possibly mixed with SELECT) + CLICK submit/save/confirm
     * Collects up to 5 fill fields.
     */
    private static MatchResult tryFormSubmit(List<ObjectNode> w) {
        List<ObjectNode> fillEvents = new ArrayList<>();
        int i = 0;
        while (i < w.size() && i < 5) {
            String at = w.get(i).path("actionType").asText();
            if ("FILL".equals(at) || "SELECT_OPTION".equals(at)) {
                fillEvents.add(w.get(i));
                i++;
            } else break;
        }
        if (fillEvents.size() < 2) return null;
        if (i >= w.size()) return null;

        ObjectNode submitEvent = w.get(i);
        String at = submitEvent.path("actionType").asText();
        boolean isSubmit = "CLICK".equals(at) || "FORM_SUBMIT".equals(at);
        if (!isSubmit) return null;

        String btn = btnLabel(submitEvent).toLowerCase();
        boolean isSubmitBtn = btn.contains("submit") || btn.contains("save")
                || btn.contains("confirm") || btn.contains("apply")
                || btn.contains("next") || btn.contains("continue") || "FORM_SUBMIT".equals(at);
        if (!isSubmitBtn) return null;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("formName", "form");
        for (ObjectNode fe : fillEvents) {
            String fLabel = fieldLabel(fe);
            params.put(fLabel, fe.path("inputValue").asText(""));
        }

        List<ObjectNode> allEvents = new ArrayList<>(fillEvents);
        allEvents.add(submitEvent);

        return new MatchResult(new Intent(IntentType.FORM_SUBMIT,
                "Form submission with " + fillEvents.size() + " fields",
                allEvents, params), allEvents.size());
    }

    /**
     * NAVIGATION: standalone NAVIGATE event
     */
    private static MatchResult tryNavigation(List<ObjectNode> w) {
        ObjectNode e0 = w.get(0);
        if (!"NAVIGATE".equals(e0.path("actionType").asText())) return null;
        String url = e0.path("inputValue").asText(e0.path("pageUrl").asText(""));
        return new MatchResult(new Intent(IntentType.NAVIGATION,
                "Navigate to: " + url, List.of(e0), Map.of("url", url)), 1);
    }

    /**
     * SELECT_FLOW: [SELECT_OPTION] optionally followed by [CLICK confirm]
     */
    private static MatchResult trySelectFlow(List<ObjectNode> w) {
        ObjectNode e0 = w.get(0);
        if (!"SELECT_OPTION".equals(e0.path("actionType").asText())) return null;
        String field = fieldLabel(e0);
        String value = e0.path("inputValue").asText("");
        Map<String, String> params = Map.of("field", field, "value", value);

        // Check if next is a confirm click
        if (w.size() > 1) {
            String next = w.get(1).path("actionType").asText();
            String btn  = btnLabel(w.get(1)).toLowerCase();
            if ("CLICK".equals(next) && (btn.contains("ok") || btn.contains("confirm")
                    || btn.contains("apply") || btn.contains("select")))
                return new MatchResult(new Intent(IntentType.SELECT_FLOW,
                        "Select '" + value + "' from " + field, List.of(e0, w.get(1)), params), 2);
        }
        return new MatchResult(new Intent(IntentType.SELECT_FLOW,
                "Select '" + value + "' from " + field, List.of(e0), params), 1);
    }

    /**
     * UPLOAD_FLOW: [UPLOAD_FILE] optionally followed by [CLICK confirm]
     */
    private static MatchResult tryUploadFlow(List<ObjectNode> w) {
        ObjectNode e0 = w.get(0);
        if (!"UPLOAD_FILE".equals(e0.path("actionType").asText())) return null;
        String file = e0.path("inputValue").asText("");
        Map<String, String> params = Map.of("file", file);
        if (w.size() > 1 && "CLICK".equals(w.get(1).path("actionType").asText()))
            return new MatchResult(new Intent(IntentType.UPLOAD_FLOW,
                    "Upload file: " + file, List.of(e0, w.get(1)), params), 2);
        return new MatchResult(new Intent(IntentType.UPLOAD_FLOW,
                "Upload file: " + file, List.of(e0), params), 1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private static String fieldLabel(ObjectNode e) {
        var loc = e.path("locator");
        for (String f : new String[]{"ariaLabel","placeholder","name","id","text"}) {
            String v = loc.path(f).asText("");
            if (!v.isBlank()) return v;
        }
        return e.path("elementSnapshot").path("innerText").asText("field");
    }

    private static String btnLabel(ObjectNode e) {
        var snap = e.path("elementSnapshot");
        String txt = snap.path("innerText").asText("");
        if (!txt.isBlank()) return txt;
        var loc = e.path("locator");
        return loc.path("ariaLabel").asText(loc.path("text").asText(loc.path("id").asText("btn")));
    }

    private static String toCamel(String s) {
        if (s == null || s.isBlank()) return "action";
        String[] p = s.replaceAll("[^a-zA-Z0-9 ]"," ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder(p[0].toLowerCase());
        for (int i = 1; i < p.length; i++) if (!p[i].isBlank())
            sb.append(Character.toUpperCase(p[i].charAt(0))).append(p[i].substring(1).toLowerCase());
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  MATCH RESULT
    // ─────────────────────────────────────────────────────────────────────────
    private static class MatchResult {
        final Intent intent;
        final int consumed; // number of raw events consumed
        MatchResult(Intent intent, int consumed) {
            this.intent = intent; this.consumed = consumed;
        }
    }
}
