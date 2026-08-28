package agent.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Application configuration loaded from environment variables and optional .env file.
 */
public class AgentConfig {

    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int timeoutSeconds;

    public AgentConfig(String apiKey, String model, String baseUrl, int timeoutSeconds) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = (model != null && !model.isBlank()) ? model.trim() : DEFAULT_MODEL;
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : DEFAULT_BASE_URL;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
    }

    /**
     * Loads configuration from System environment variables and an optional .env file.
     */
    public static AgentConfig load() {
        return load(new File(".env"));
    }

    /**
     * Loads configuration from System environment variables and a specified .env file.
     */
    public static AgentConfig load(File envFile) {
        Map<String, String> envMap = new HashMap<>();

        // 1. Read .env file if it exists
        if (envFile != null && envFile.exists() && envFile.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eqIdx = line.indexOf('=');
                    if (eqIdx > 0) {
                        String key = line.substring(0, eqIdx).trim();
                        String val = line.substring(eqIdx + 1).trim();
                        if ((val.startsWith("\"") && val.endsWith("\"")) ||
                            (val.startsWith("'") && val.endsWith("'"))) {
                            val = val.substring(1, val.length() - 1);
                        }
                        envMap.put(key, val);
                    }
                }
            } catch (IOException ignored) {
                // Silently ignore .env read errors
            }
        }

        // 2. System env overrides or supplements .env
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = envMap.get("OPENAI_API_KEY");
        }

        String model = System.getenv("OPENAI_MODEL");
        if (model == null || model.isBlank()) {
            model = envMap.getOrDefault("OPENAI_MODEL", DEFAULT_MODEL);
        }

        String baseUrl = System.getenv("OPENAI_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = envMap.getOrDefault("OPENAI_BASE_URL", DEFAULT_BASE_URL);
        }

        String timeoutStr = System.getenv("COMMAND_TIMEOUT_SECONDS");
        if (timeoutStr == null || timeoutStr.isBlank()) {
            timeoutStr = envMap.get("COMMAND_TIMEOUT_SECONDS");
        }

        int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        if (timeoutStr != null && !timeoutStr.isBlank()) {
            try {
                timeoutSeconds = Integer.parseInt(timeoutStr.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        return new AgentConfig(apiKey, model, baseUrl, timeoutSeconds);
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
