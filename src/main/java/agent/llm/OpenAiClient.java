package agent.llm;

import agent.config.AgentConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Communicates with the OpenAI API to translate natural language prompts into Windows CLI commands.
 */
public class OpenAiClient {

    public record GeneratedCommand(String command, String explanation) {}

    private static final String SYSTEM_PROMPT = """
            You are an expert Windows system administrator.
            Your job: translate the user's natural-language request into a SINGLE Windows CLI command.

            Strict rules:
            - Produce commands that run on Windows (cmd.exe or Windows PowerShell). NEVER produce
              Unix/Linux/macOS-only commands (no `ls`, `grep`, `cat`, `rm -rf`, `sudo`, `apt`, etc.).
            - Prefer commands that also work under PowerShell Core (pwsh), since aliases like `dir`,
              `type`, `copy`, `del`, `echo`, `cls` and cmdlets like `Get-ChildItem`, `Get-Content`,
              `Select-String` behave consistently there.
            - Return exactly ONE command (a single line). Do not chain unrelated commands.
            - Do not wrap the command in markdown, backticks, or quotes.

            Respond ONLY with a JSON object of the form:
            {"command": "<the windows command>", "explanation": "<one short sentence>"}
            """;

    private final AgentConfig config;
    private final HttpClient httpClient;

    public OpenAiClient(AgentConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Translates a user request into a Windows command.
     */
    public GeneratedCommand generateCommand(String prompt) throws IOException, InterruptedException {
        if (!config.hasApiKey()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set. Add it to your environment or a .env file.");
        }

        String requestBody = buildRequestBody(prompt);
        String endpoint = config.getBaseUrl() + "/chat/completions";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenAI API request failed (HTTP " + response.statusCode() + "): " + response.body());
        }

        String content = extractAssistantMessage(response.body());
        return parseResponse(content);
    }

    private String buildRequestBody(String userPrompt) {
        return "{" +
                "\"model\":\"" + escapeJson(config.getModel()) + "\"," +
                "\"temperature\":0," +
                "\"messages\":[" +
                "{\"role\":\"system\",\"content\":\"" + escapeJson(SYSTEM_PROMPT.trim()) + "\"}," +
                "{\"role\":\"user\",\"content\":\"" + escapeJson(userPrompt) + "\"}" +
                "]" +
                "}";
    }

    /**
     * Extracts the content string from the OpenAI chat completion response JSON.
     */
    static String extractAssistantMessage(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isBlank()) {
            return "";
        }

        // Match "content": "..." within the message
        Pattern contentPattern = Pattern.compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher matcher = contentPattern.matcher(jsonResponse);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }

        return jsonResponse;
    }

    /**
     * Parses the LLM's output, handling markdown wrappers and json keys.
     */
    public static GeneratedCommand parseResponse(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return new GeneratedCommand("", "");
        }

        String cleaned = rawContent.trim();

        // Strip markdown code fences (e.g. ```json ... ```)
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
            cleaned = cleaned.trim();
        }

        // Extract command and explanation from JSON
        String command = extractJsonField(cleaned, "command");
        String explanation = extractJsonField(cleaned, "explanation");

        if (command != null && !command.isBlank()) {
            return new GeneratedCommand(command.trim(), explanation != null ? explanation.trim() : "");
        }

        // Fallback: if not valid JSON, treat the entire cleaned text as the command
        return new GeneratedCommand(cleaned, "");
    }

    private static String extractJsonField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }
        return null;
    }

    public static String escapeJson(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c <= 0x1F) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public static String unescapeJson(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escape) {
                switch (c) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 < input.length()) {
                            String hex = input.substring(i + 1, i + 5);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c);
                }
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
