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
    /** state.json'da saklanan minimal kimlik */
    public String stateKey() {
        return id;
    }
}
