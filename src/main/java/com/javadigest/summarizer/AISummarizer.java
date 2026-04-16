package com.javadigest.summarizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadigest.model.Article;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Toplanan makaleleri yapay zeka ile özetler.
 * OpenAI API, Gemini API veya Ollama destekler.
 * Env: OPENAI_API_KEY, GEMINI_API_KEY veya OLLAMA_URL
 */
public class AISummarizer {

    private static final Logger log = Logger.getLogger(AISummarizer.class.getName());
    private static final int ARTICLE_CONTEXT_MAX_CHARS = 1400;
    private static final int ARTICLE_FETCH_TIMEOUT_MS = 8_000;
    private static final int GEMINI_MAX_RETRIES = 3;
    private static final Pattern IDX_SUMMARY_JSON_PATTERN = Pattern.compile(
            "\"idx\"\\s*:\\s*(\\d+)\\s*,\\s*\"summary\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"",
            Pattern.DOTALL
    );
    private static final Pattern IDX_LINE_PATTERN = Pattern.compile("(?m)^\\s*(\\d{1,2})[\\)\\.:\\-]\\s*(.+)$");

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
            String summary = callProvider(prompt, 500);
            if (summary == null || summary.isBlank()) {
                return buildFallbackSummary(articles);
            }
            return summary;
        } catch (Exception e) {
            log.warning("AI özet oluşturulamadı: " + e.getMessage());
            return buildFallbackSummary(articles);
        }
    }

    public Map<String, String> summarizePerArticle(List<Article> articles) {
        if (articles.isEmpty()) return Map.of();
        if (!enabled) return buildFallbackPerArticleSummaries(articles);

        List<Article> target = articles.stream().limit(30).toList();
        Map<String, String> articleContexts = target.parallelStream()
                .collect(Collectors.toMap(
                        Article::stateKey,
                        article -> fetchArticleContext(article.url()),
                        (a, b) -> a
                ));
        String prompt = buildPerArticlePrompt(target, articleContexts);

        try {
            String raw = callProvider(prompt, 1200);
            Map<String, String> parsed = parsePerArticleSummaries(raw, target);
            if (parsed.isEmpty()) {
                return buildFallbackPerArticleSummaries(target);
            }
            return parsed;
        } catch (Exception e) {
            log.warning("Makale bazlı AI özet oluşturulamadı: " + e.getMessage());
            return buildFallbackPerArticleSummaries(target);
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

    private String buildPerArticlePrompt(List<Article> articles, Map<String, String> articleContexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Asagidaki Java icerikleri icin her maddeye ayri kisa Turkce ozet uret.\n");
        sb.append("Yaniti SADECE JSON array olarak ver.\n");
        sb.append("Format: [{\"idx\":1,\"summary\":\"...\"}]\n");
        sb.append("Kurallar:\n");
        sb.append("- Her madde icin 1-2 cumle.\n");
        sb.append("- Teknik ama kisa ol.\n");
        sb.append("- URL yazma.\n");
        sb.append("- JSON disinda hicbir metin yazma.\n\n");
        sb.append("Maddeler:\n");

        for (int i = 0; i < articles.size(); i++) {
            Article a = articles.get(i);
            sb.append(i + 1).append(") ")
                    .append("[").append(a.source()).append("] ")
                    .append(a.title());
            if (a.author() != null && !a.author().isBlank()) {
                sb.append(" (").append(a.author()).append(")");
            }
            if (a.tags() != null && !a.tags().isBlank()) {
                sb.append(" <").append(a.tags()).append(">");
            }
            String context = articleContexts.getOrDefault(a.stateKey(), "");
            if (!context.isBlank()) {
                sb.append("\n   Icerik: ").append(context);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String callProvider(String prompt, int maxTokens) throws Exception {
        return switch (provider) {
            case "ollama" -> callOllama(prompt);
            case "gemini" -> callGeminiWithRetry(prompt, maxTokens);
            default -> callOpenAI(prompt, maxTokens);
        };
    }

    private String callOpenAI(String prompt, int maxTokens) throws Exception {
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
                "max_tokens", maxTokens,
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

    private String callGeminiWithRetry(String prompt, int maxTokens) throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warning("GEMINI_API_KEY tanımlı değil, Gemini özeti atlanıyor.");
            return "";
        }

        String primaryModel = System.getenv("GEMINI_MODEL");
        if (primaryModel == null || primaryModel.isBlank()) {
            primaryModel = "gemini-2.5-flash";
        }
        String fallbackModel = System.getenv("GEMINI_FALLBACK_MODEL");
        if (fallbackModel == null || fallbackModel.isBlank()) {
            fallbackModel = "gemini-2.5-flash-lite";
        }

        List<String> models = primaryModel.equals(fallbackModel)
                ? List.of(primaryModel)
                : List.of(primaryModel, fallbackModel);

        GeminiCallResult lastResult = null;
        for (String model : models) {
            for (int attempt = 1; attempt <= GEMINI_MAX_RETRIES; attempt++) {
                GeminiCallResult result = callGeminiOnce(model, prompt, maxTokens, apiKey);
                lastResult = result;
                if (result.success()) {
                    return result.text();
                }

                boolean retryable = result.statusCode() == 429 || result.statusCode() == 503;
                if (!retryable || attempt == GEMINI_MAX_RETRIES) {
                    break;
                }

                long waitMs = (long) Math.pow(2, attempt) * 1000L;
                log.warning("Gemini gecici hata (" + result.statusCode()
                        + "), tekrar deneniyor: model=" + model + ", bekleme=" + waitMs + "ms");
                sleep(waitMs);
            }
            log.warning("Gemini model denemesi bitti: " + model + ", sonraki modele geciliyor.");
        }

        if (lastResult != null) {
            log.warning("Gemini API hatası: " + lastResult.statusCode() + " - " + lastResult.message());
        }
        return "";
    }

    private GeminiCallResult callGeminiOnce(String model, String prompt, int maxTokens, String apiKey) throws Exception {
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
                        "maxOutputTokens", maxTokens
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
            return new GeminiCallResult(response.statusCode(), "", response.body());
        }

        JsonNode root = json.readTree(response.body());
        String text = root.path("candidates")
                .path(0)
                .path("content")
                .path("parts")
                .path(0)
                .path("text")
                .asText("");
        return new GeminiCallResult(200, text, "OK");
    }

    private Map<String, String> parsePerArticleSummaries(String raw, List<Article> articles) {
        Map<String, String> summaries = new HashMap<>();
        String normalized = normalizeJson(raw);
        if (normalized.isBlank()) return summaries;

        try {
            JsonNode root = json.readTree(normalized);
            JsonNode items = root;
            if (!root.isArray() && root.has("items")) {
                items = root.path("items");
            }
            if (!items.isArray()) return summaries;

            for (JsonNode item : items) {
                int idx = item.path("idx").asInt(-1);
                String summary = item.path("summary").asText("").trim();
                if (idx < 1 || idx > articles.size() || summary.isBlank()) continue;
                summaries.put(articles.get(idx - 1).stateKey(), summary);
            }
        } catch (Exception e) {
            log.warning("Makale bazlı AI JSON parse hatası: " + e.getMessage());
        }

        if (summaries.isEmpty()) {
            summaries.putAll(parseSummariesWithRegex(raw, articles));
        }

        // JSON/regex kısmi başarılı olsa bile tüm maddeleri doldur.
        Map<String, String> fallback = buildFallbackPerArticleSummaries(articles);
        fallback.forEach(summaries::putIfAbsent);

        return summaries;
    }

    private Map<String, String> parseSummariesWithRegex(String raw, List<Article> articles) {
        Map<String, String> summaries = new HashMap<>();
        if (raw == null || raw.isBlank()) return summaries;

        Matcher jsonMatcher = IDX_SUMMARY_JSON_PATTERN.matcher(raw);
        while (jsonMatcher.find()) {
            int idx = parseIntSafe(jsonMatcher.group(1));
            String summary = unescapeJsonString(jsonMatcher.group(2));
            putSummaryByIdx(summaries, articles, idx, summary);
        }

        if (!summaries.isEmpty()) {
            return summaries;
        }

        Matcher lineMatcher = IDX_LINE_PATTERN.matcher(raw);
        while (lineMatcher.find()) {
            int idx = parseIntSafe(lineMatcher.group(1));
            String summary = lineMatcher.group(2) != null ? lineMatcher.group(2).trim() : "";
            summary = summary.replaceAll("[\\]\\}\\\"]+$", "").trim();
            putSummaryByIdx(summaries, articles, idx, summary);
        }

        return summaries;
    }

    private void putSummaryByIdx(Map<String, String> summaries, List<Article> articles, int idx, String summary) {
        if (idx < 1 || idx > articles.size()) return;
        if (summary == null || summary.isBlank()) return;
        summaries.put(articles.get(idx - 1).stateKey(), summary);
    }

    private int parseIntSafe(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private String unescapeJsonString(String raw) {
        if (raw == null) return "";
        return raw
                .replace("\\n", " ")
                .replace("\\t", " ")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeJson(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        trimmed = trimmed
                .replace("```json", "")
                .replace("```JSON", "")
                .replace("```", "")
                .trim();

        int arrStart = trimmed.indexOf('[');
        int arrEnd = trimmed.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) {
            return trimmed.substring(arrStart, arrEnd + 1).trim();
        }

        int objStart = trimmed.indexOf('{');
        int objEnd = trimmed.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return trimmed.substring(objStart, objEnd + 1).trim();
        }

        return trimmed;
    }

    private Map<String, String> buildFallbackPerArticleSummaries(List<Article> articles) {
        Map<String, String> fallback = new HashMap<>();
        for (Article article : articles) {
            String source = article.source() == null || article.source().isBlank() ? "Java ekosistemi" : article.source();
            fallback.put(article.stateKey(), source + " kaynaginda one cikan gelisme paylasiliyor.");
        }
        return fallback;
    }

    private String fetchArticleContext(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Java-Digest-Bot/1.0")
                    .timeout(ARTICLE_FETCH_TIMEOUT_MS)
                    .followRedirects(true)
                    .get();
            String text = doc.body() != null ? doc.body().text() : "";
            text = normalizeWhitespace(text);
            if (text.length() > ARTICLE_CONTEXT_MAX_CHARS) {
                text = text.substring(0, ARTICLE_CONTEXT_MAX_CHARS) + "...";
            }
            return text;
        } catch (Exception e) {
            return "";
        }
    }

    private String normalizeWhitespace(String input) {
        if (input == null) return "";
        return input.replaceAll("\\s+", " ").trim();
    }

    private void sleep(long waitMs) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    private record GeminiCallResult(int statusCode, String text, String message) {
        private boolean success() {
            return statusCode == 200 && text != null && !text.isBlank();
        }
    }
}
