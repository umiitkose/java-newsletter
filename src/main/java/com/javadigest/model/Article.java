package com.javadigest.model;

import java.time.LocalDate;

public record Article(
        String id,        // URL — unique key olarak kullanılır
        String title,
        String url,
        String author,
        String source,    // "inside.java", "openjdk-mailing", "infoq" vs.
        LocalDate publishedDate,
        String tags       // "Amber, Valhalla" gibi
) {
    public Article {
        url = sanitizeUrl(url);
        id = sanitizeUrl(id);
    }

    /** state.json'da saklanan minimal kimlik */
    public String stateKey() {
        return id;
    }

    private static String sanitizeUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = raw.strip().replaceAll("\\s+", "");
        cleaned = cleaned.replace("https://openjdk.org/archives/list/",
                                  "https://mail.openjdk.org/archives/list/");
        return cleaned;
    }
}
