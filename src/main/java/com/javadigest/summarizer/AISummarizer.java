package com.javadigest.summarizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadigest.model.Article;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Toplanan makaleleri yapay zeka ile özetler.
 * OpenAI API, Gemini API veya Ollama destekler.
 * Env: OPENAI_API_KEY, GEMINI_API_KEY veya OLLAMA_URL
 */
public class AISummarizer {

    private static final Logger log = Logger.getLogger(AISummarizer.class.getName());

    private final String provider;
    private final boolean enabled;
    private final HttpClient http;
    private final ObjectMapper json;

    public AISummarizer(String provider, boolean enabled) {
        this.provider = provider;
        this.enabled = enabled;
        this.http = HttpClient.newHttpClient();
        this.json = new ObjectMapper();
    }

    public String summarize(List<Article> articles) {
        if (articles.isEmpty()) return "";
        if (!enabled) {
            return buildFallbackSummary(articles);
        }

        String prompt = buildPrompt(articles);

        try {
            String summary = switch (provider) {
                case "ollama" -> callOllama(prompt);
                case "gemini" -> callGemini(prompt);
                default -> callOpenAI(prompt);
            };
            if (summary == null || summary.isBlank()) {
                return buildFallbackSummary(articles);
            }
            return summary;
        } catch (Exception e) {
            log.warning("AI özet oluşturulamadı: " + e.getMessage());
            return buildFallbackSummary(articles);
        }
    }

    private String buildPrompt(List<Article> articles) {
        StringBuilder sb = new StringBuilder();
        sb.append("Aşağıdaki Java ekosistemi içeriklerini Türkçe olarak özetle.\n");
        sb.append("Kurallar:\n");
        sb.append("- Sadece Türkçe yaz.\n");
        sb.append("- 3-5 cümlelik genel bir özet ver.\n");
        sb.append("- URL veya markdown linki kullanma.\n");
        sb.append("- Teknik ama okunur bir dil kullan.\n");
        sb.append("- Öne çıkan tema ve eğilimleri belirt.\n\n");
        sb.append("İçerikler:\n");

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
            log.warning("OPENAI_API_KEY tanımlı değil, OpenAI özeti atlanıyor.");
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

    private String callGemini(String prompt) throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warning("GEMINI_API_KEY tanımlı değil, Gemini özeti atlanıyor.");
            return "";
        }

        String model = System.getenv("GEMINI_MODEL");
        if (model == null || model.isBlank()) {
            model = "gemini-2.5-flash";
        }

        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of(
                                "parts", List.of(
                                        Map.of("text", prompt)
                                )
                        )
                ),
                "generationConfig", Map.of(
                        "temperature", 0.3,
                        "maxOutputTokens", 500
                )
        );

        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model
                + ":generateContent?key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warning("Gemini API hatası: " + response.statusCode());
            return "";
        }

        JsonNode root = json.readTree(response.body());
        return root.path("candidates")
                .path(0)
                .path("content")
                .path("parts")
                .path(0)
                .path("text")
                .asText("");
    }

    private String buildFallbackSummary(List<Article> articles) {
        String sourceList = articles.stream()
                .map(Article::source)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .sorted()
                .limit(5)
                .reduce((a, b) -> a + ", " + b)
                .orElse("cesitli Java kaynaklari");

        int articleCount = articles.size();
        return "Bugün " + articleCount + " yeni Java içeriği tarandı. "
                + "Öne çıkan kaynaklar: " + sourceList + ". "
                + "İçerikler dil özellikleri, JVM performansı, OpenJDK gelişmeleri ve araç ekosistemindeki güncellemeler etrafında yoğunlaşıyor.";
    }
}
