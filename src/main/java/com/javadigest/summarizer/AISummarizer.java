package com.javadigest.summarizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadigest.model.Article;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Toplanan makaleleri yapay zeka ile özetler.
 * OpenAI API veya Ollama destekler.
 * Env: OPENAI_API_KEY veya OLLAMA_URL
 */
public class AISummarizer {

    private static final Logger log = Logger.getLogger(AISummarizer.class.getName());

    private final String provider;
    private final HttpClient http;
    private final ObjectMapper json;

    public AISummarizer(String provider) {
        this.provider = provider;
        this.http = HttpClient.newHttpClient();
        this.json = new ObjectMapper();
    }

    public String summarize(List<Article> articles) {
        if (articles.isEmpty()) return "";

        String prompt = buildPrompt(articles);

        try {
            return switch (provider) {
                case "ollama" -> callOllama(prompt);
                default -> callOpenAI(prompt);
            };
        } catch (Exception e) {
            log.warning("AI özet oluşturulamadı: " + e.getMessage());
            return "";
        }
    }

    private String buildPrompt(List<Article> articles) {
        StringBuilder sb = new StringBuilder();
        sb.append("Aşağıdaki Java ekosistemi içeriklerini Türkçe olarak 3-5 cümlede özetle. ");
        sb.append("Önemli temaları ve gelişmeleri vurgula:\n\n");

        for (Article a : articles) {
            sb.append("- [").append(a.source()).append("] ").append(a.title());
            if (a.author() != null && !a.author().isEmpty()) {
                sb.append(" (").append(a.author()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String callOpenAI(String prompt) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.fine("OPENAI_API_KEY tanımlı değil, özet atlanıyor.");
            return "";
        }

        Map<String, Object> body = Map.of(
                "model", "gpt-4o-mini",
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "Sen bir Java ekosistemi uzmanısın. Kısa ve öz özetler oluştur."),
                        Map.of("role", "user", "content", prompt)
                ),
                "max_tokens", 500,
                "temperature", 0.3
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warning("OpenAI API hatası: " + response.statusCode());
            return "";
        }

        JsonNode root = json.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText("");
    }

    private String callOllama(String prompt) throws Exception {
        String ollamaUrl = System.getenv("OLLAMA_URL");
        if (ollamaUrl == null) ollamaUrl = "http://localhost:11434";

        Map<String, Object> body = Map.of(
                "model", "llama3",
                "prompt", prompt,
                "stream", false
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warning("Ollama API hatası: " + response.statusCode());
            return "";
        }

        JsonNode root = json.readTree(response.body());
        return root.path("response").asText("");
    }
}
