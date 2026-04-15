package com.javadigest.generator;

import com.javadigest.model.Article;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Digest sonuçlarını Markdown dosyası olarak docs/ klasörüne yazar.
 * GitHub Pages ile otomatik yayınlanabilir.
 */
public class DigestPageGenerator {

    private static final Logger log = Logger.getLogger(DigestPageGenerator.class.getName());
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy");

    private final String outputDir;

    public DigestPageGenerator(String outputDir) {
        this.outputDir = outputDir;
    }

    public void generate(List<Article> articles, String aiSummary) {
        if (articles.isEmpty()) return;

        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);

            String date = LocalDate.now().format(FILE_DATE);
            Path filePath = dir.resolve(date + ".md");

            String content = buildMarkdown(articles, aiSummary);
            Files.writeString(filePath, content);

            updateIndex(dir);

            log.info("Digest sayfası oluşturuldu: " + filePath);
        } catch (IOException e) {
            log.warning("Sayfa oluşturulamadı: " + e.getMessage());
        }
    }

    private String buildMarkdown(List<Article> articles, String aiSummary) {
        StringBuilder md = new StringBuilder();
        String today = LocalDate.now().format(DISPLAY_DATE);

        md.append("# Java Digest — ").append(today).append("\n\n");
        md.append("> ").append(articles.size()).append(" yeni içerik\n\n");

        if (aiSummary != null && !aiSummary.isBlank()) {
            md.append("## Özet\n\n");
            md.append(aiSummary).append("\n\n");
            md.append("---\n\n");
        }

        Map<String, List<Article>> bySource = articles.stream()
                .collect(Collectors.groupingBy(Article::source));

        for (Map.Entry<String, List<Article>> entry : bySource.entrySet()) {
            md.append("## ").append(formatSourceName(entry.getKey())).append("\n\n");

            for (Article a : entry.getValue()) {
                md.append("- **[").append(a.title()).append("](").append(a.url()).append(")**");
                if (a.author() != null && !a.author().isEmpty()) {
                    md.append(" — ").append(a.author());
                }
                if (a.tags() != null && !a.tags().isBlank()) {
                    md.append(" `").append(a.tags()).append("`");
                }
                md.append("\n");
            }
            md.append("\n");
        }

        md.append("---\n\n");
        md.append("*Java Digest Bot tarafından otomatik oluşturuldu.*\n");

        return md.toString();
    }

    private void updateIndex(Path dir) throws IOException {
        List<String> digestFiles = Files.list(dir)
                .filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.md"))
                .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                .map(p -> p.getFileName().toString())
                .toList();

        StringBuilder index = new StringBuilder();
        index.append("# Java Digest Arşivi\n\n");
        index.append("Java ekosisteminin günlük özet arşivi.\n\n");

        for (String file : digestFiles) {
            String date = file.replace(".md", "");
            index.append("- [").append(date).append("](").append(file).append(")\n");
        }

        index.append("\n---\n\n");
        index.append("*[GitHub](https://github.com/umiitkose/java-newsletter) | Java Digest Bot*\n");

        Files.writeString(dir.resolve("index.md"), index.toString());
    }

    private String formatSourceName(String source) {
        return switch (source) {
            case "inside.java" -> "Inside Java";
            case "infoq" -> "InfoQ";
            case "openjdk-jep" -> "JEP Durum Değişiklikleri";
            default -> {
                if (source.startsWith("openjdk-")) {
                    yield "OpenJDK " + source.substring(8);
                }
                yield source.substring(0, 1).toUpperCase() + source.substring(1);
            }
        };
    }
}
