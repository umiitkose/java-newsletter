package com.javadigest.fetcher;

import com.javadigest.model.Article;

import java.time.LocalDate;

/**
 * JEP (JDK Enhancement Proposal) sayfasında gözlemlenen tek bir değişikliği temsil eder.
 *
 * Türler:
 *   NEW              — daha önce hiç görmediğimiz bir JEP yayınlandı
 *   STATUS_CHANGED   — JEP statüsü değişti (örn. Candidate → Targeted)
 *   RELEASE_TARGETED — JEP ilk kez bir JDK release'ine bağlandı (örn. JDK 27)
 *   RELEASE_CHANGED  — JEP başka bir release'e taşındı (örn. 26 → 27)
 *   COMPLETED        — JEP statüsü Integrated/Completed/Closed/Delivered oldu
 */
public record JepEvent(
        Type type,
        String jepId,
        String title,
        String component,
        String previousStatus,
        String newStatus,
        Integer previousRelease,
        Integer newRelease
) {

    public enum Type {
        NEW, STATUS_CHANGED, RELEASE_TARGETED, RELEASE_CHANGED, COMPLETED
    }

    public String url() {
        return "https://openjdk.org/jeps/" + jepId;
    }

    /** Olayın insan-okur özeti (örn. Slack başlıkları için). */
    public String headline() {
        return switch (type) {
            case NEW -> "Yeni JEP: " + title;
            case STATUS_CHANGED -> "JEP " + jepId + " — " + previousStatus + " → " + newStatus;
            case RELEASE_TARGETED -> "JEP " + jepId + " JDK " + newRelease + "'e hedeflendi";
            case RELEASE_CHANGED -> "JEP " + jepId + " release değişti: JDK "
                    + previousRelease + " → JDK " + newRelease;
            case COMPLETED -> "JEP " + jepId + " tamamlandı"
                    + (newRelease != null ? " (JDK " + newRelease + ")" : "");
        };
    }

    /**
     * Mevcut digest akışına dahil edilebilmesi için Article temsili.
     * tags alanına olay türü + component yazılır, böylece SlackNotifier
     * route'lama yapabilir.
     */
    public Article toArticle() {
        String displayTitle = "JEP " + jepId + ": " + title;
        if (type == Type.STATUS_CHANGED) {
            displayTitle += " — " + previousStatus + " → " + newStatus;
        } else if (type == Type.RELEASE_TARGETED) {
            displayTitle += " — JDK " + newRelease + " hedefli";
        } else if (type == Type.RELEASE_CHANGED) {
            displayTitle += " — JDK " + previousRelease + " → JDK " + newRelease;
        } else if (type == Type.COMPLETED) {
            displayTitle += " — Tamamlandı"
                    + (newRelease != null ? " (JDK " + newRelease + ")" : "");
        } else if (type == Type.NEW) {
            displayTitle = "Yeni " + displayTitle;
        }

        String stateKey = "jep-" + jepId + "-" + type.name().toLowerCase()
                + "-" + (newStatus != null ? newStatus : "")
                + "-" + (newRelease != null ? newRelease : "");

        String tags = "openjdk-jep " + (component != null ? component : "");

        return new Article(
                stateKey,
                displayTitle,
                url(),
                "",
                "openjdk-jep",
                LocalDate.now(),
                tags.trim()
        );
    }
}
