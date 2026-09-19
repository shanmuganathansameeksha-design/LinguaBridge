import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * LinguaBridge Translator Server v1.10
 * Java 21 - built-in com.sun.net.httpserver only, no external dependencies.
 *
 * Endpoint:  POST http://localhost:8080/translate
 * Request:   { "text": "Hello", "source": "en", "target": "fr" }
 * Response:  { "translation": "Bonjour" }
 *
 * Design rules enforced here:
 *  - Every request MUST contain text, source and target. Missing field = HTTP 400.
 *  - There is NO default language anywhere. Hindi is never assumed.
 *  - The lookup key is source|target|text, so BOTH languages decide the result.
 *  - The server is completely stateless: nothing from a previous request is
 *    stored or reused. Each request is resolved fresh from the dictionary.
 *  - Unknown phrase or language pair => "Translation not available yet".
 */
public class TranslatorServer {

    private static final int PORT = 8080;

    /** Dictionary. Key = source|target|normalizedText, value = translation. */
    private static final Map<String, String> TRANSLATIONS = new HashMap<>();

    public static void main(String[] args) throws IOException {
        // Force UTF-8 console output so Tamil/Hindi text prints correctly in CMD
        // (run "chcp 65001" in CMD before starting the server).
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        loadTranslations();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/translate", TranslatorServer::handleTranslate);
        server.setExecutor(null);
        server.start();

        System.out.println("========================================");
        System.out.println(" LinguaBridge Translator Server v1.10");
        System.out.println(" Running on  : http://localhost:" + PORT);
        System.out.println(" Endpoint    : POST /translate");
        System.out.println(" Dictionary  : " + TRANSLATIONS.size() + " entries loaded");
        System.out.println(" Stop server : press Ctrl+C");
        System.out.println("========================================");
    }

    // ------------------------------------------------------------------
    // HTTP handling
    // ------------------------------------------------------------------

    private static void handleTranslate(HttpExchange exchange) throws IOException {
        try {
            addCorsHeaders(exchange);
            String method = exchange.getRequestMethod();

            // CORS preflight sent by the browser before the real POST.
            if (method.equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!method.equalsIgnoreCase("POST")) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed. Use POST.\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            System.out.println("----------------------------------------");
            System.out.println("Received: " + body);

            // Read ALL THREE values from the request. No defaults, ever.
            String text   = extractJsonString(body, "text");
            String source = extractJsonString(body, "source");
            String target = extractJsonString(body, "target");

            if (text == null || source == null || target == null) {
                System.out.println("ERROR: request is missing text, source or target.");
                sendJson(exchange, 400, "{\"error\":\"Request must contain text, source and target.\"}");
                return;
            }

            source = source.trim().toLowerCase(Locale.ROOT);
            target = target.trim().toLowerCase(Locale.ROOT);
            String cleanText = text.trim();

            System.out.println("From: " + source);
            System.out.println("To: " + target);
            System.out.println("Text: " + cleanText);

            String translation = translate(cleanText, source, target);

            System.out.println("Translation: " + translation);

            sendJson(exchange, 200, "{\"translation\":\"" + escapeJson(translation) + "\"}");

        } catch (Exception e) {
            System.out.println("ERROR: " + e);
            try {
                sendJson(exchange, 500, "{\"error\":\"Internal server error.\"}");
            } catch (IOException ignored) {
                // Response may already be committed; nothing more we can do.
            }
        } finally {
            exchange.close();
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        Headers h = exchange.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
        h.set("Access-Control-Max-Age", "86400");
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ------------------------------------------------------------------
    // Translation logic (stateless - uses ONLY this request's values)
    // ------------------------------------------------------------------

    private static String translate(String text, String source, String target) {
        if (text.isEmpty()) {
            return "Translation not available yet";
        }
        if (source.equals(target)) {
            return text; // same language selected on both sides: nothing to translate
        }
        String result = TRANSLATIONS.get(key(source, target, text));
        return (result != null) ? result : "Translation not available yet";
    }

    private static String key(String source, String target, String text) {
        return source + "|" + target + "|" + normalize(text);
    }

    /** Case-insensitive matching; identity for Tamil/Hindi scripts. */
    private static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT);
    }

    private static void add(String source, String target, String text, String translation) {
        TRANSLATIONS.put(key(source, target, text), translation);
    }

    private static void loadTranslations() {
        // English -> French
        add("en", "fr", "hello",        "Bonjour");
        add("en", "fr", "how are you?", "Comment allez-vous ?");
        add("en", "fr", "thank you",    "Merci");
        add("en", "fr", "good morning", "Bonjour");
        add("en", "fr", "good night",   "Bonne nuit");

        // English -> Hindi
        add("en", "hi", "hello",        "नमस्ते");
        add("en", "hi", "how are you?", "आप कैसे हैं?");
        add("en", "hi", "thank you",    "धन्यवाद");
        add("en", "hi", "good morning", "सुप्रभात");
        add("en", "hi", "good night",   "शुभ रात्रि");

        // English -> Tamil
        add("en", "ta", "hello",        "வணக்கம்");
        add("en", "ta", "how are you?", "நீங்கள் எப்படி இருக்கிறீர்கள்?");
        add("en", "ta", "thank you",    "நன்றி");
        add("en", "ta", "good morning", "காலை வணக்கம்");
        add("en", "ta", "good night",   "இனிய இரவு");

        // English -> German
        add("en", "de", "hello",        "Hallo");
        add("en", "de", "how are you?", "Wie geht es Ihnen?");
        add("en", "de", "thank you",    "Danke");
        add("en", "de", "good morning", "Guten Morgen");
        add("en", "de", "good night",   "Gute Nacht");

        // French -> English ("Bonjour" maps to "Hello" as the primary meaning)
        add("fr", "en", "bonjour",              "Hello");
        add("fr", "en", "comment allez-vous ?", "How are you?");
        add("fr", "en", "merci",                "Thank you");
        add("fr", "en", "bonne nuit",           "Good night");

        // Hindi -> English
        add("hi", "en", "नमस्ते",       "Hello");
        add("hi", "en", "आप कैसे हैं?", "How are you?");
        add("hi", "en", "धन्यवाद",      "Thank you");
        add("hi", "en", "सुप्रभात",     "Good morning");
        add("hi", "en", "शुभ रात्रि",   "Good night");

        // Tamil -> English
        add("ta", "en", "வணக்கம்",                          "Hello");
        add("ta", "en", "நீங்கள் எப்படி இருக்கிறீர்கள்?",   "How are you?");
        add("ta", "en", "நன்றி",                            "Thank you");
        add("ta", "en", "காலை வணக்கம்",                     "Good morning");
        add("ta", "en", "இனிய இரவு",                        "Good night");

        // German -> English
        add("de", "en", "hallo",              "Hello");
        add("de", "en", "wie geht es ihnen?", "How are you?");
        add("de", "en", "danke",              "Thank you");
        add("de", "en", "guten morgen",       "Good morning");
        add("de", "en", "gute nacht",         "Good night");
    }

    // ------------------------------------------------------------------
    // Minimal JSON helpers (no external libraries)
    // ------------------------------------------------------------------

    /**
     * Extracts a string value for the given key from a flat JSON object.
     * Handles escaped characters including \" \\ \n \t and \\uXXXX,
     * so Tamil/Hindi text and quotes inside the text are parsed correctly.
     * Returns null if the key is missing or its value is not a string.
     */
    private static String extractJsonString(String json, String jsonKey) {
        String pattern = "\"" + jsonKey + "\"";
        int from = 0;
        while (true) {
            int keyIndex = json.indexOf(pattern, from);
            if (keyIndex < 0) {
                return null;
            }
            int i = keyIndex + pattern.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i < json.length() && json.charAt(i) == ':') {
                i++;
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
                if (i < json.length() && json.charAt(i) == '"') {
                    return readJsonStringValue(json, i + 1);
                }
                return null; // value exists but is not a string
            }
            from = keyIndex + 1; // matched text was not a key; keep searching
        }
    }

    private static String readJsonStringValue(String json, int start) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    case 'u'  -> {
                        if (i + 5 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                // malformed \\u sequence: skip it
                            }
                        }
                    }
                    default   -> sb.append(next);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return null; // unterminated string
    }

    private static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}