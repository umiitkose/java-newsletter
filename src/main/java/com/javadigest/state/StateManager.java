package com.javadigest.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.javadigest.model.Article;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Daha önce gönderilen makalelerin URL'lerini state.json'da tutar.
 * GitHub Actions her çalışmada bu dosyayı repo'ya commit'ler —
 * böylece aynı makale iki kez gönderilmez.
 */
public class StateManager {

    private static final Logger log = Logger.getLogger(StateManager.class.getName());
    private static final String STATE_FILE = "state.json";

    private final ObjectMapper json;
    private Set<String> seenIds;

    public StateManager() {
        this.json = new ObjectMapper();
        this.json.registerModule(new JavaTimeModule());
        this.seenIds = loadState();
    }

    /** Yeni makaleleri filtrele — daha önce görülmemişleri döndür */
    public List<Article> filterNew(List<Article> candidates) {
        List<Article> fresh = new ArrayList<>();
        for (Article a : candidates) {
            if (!seenIds.contains(a.stateKey())) {
                fresh.add(a);
            }
        }
        return fresh;
    }

    /** Gönderilen makaleleri state'e ekle ve diske yaz */
    public void markAsSeen(List<Article> articles) {
        articles.forEach(a -> seenIds.add(a.stateKey()));
        saveState();
    }

    // ─── Yardımcı metotlar ────────────────────────────────────────────────

    private Set<String> loadState() {
        File file = new File(STATE_FILE);
        if (!file.exists()) {
            log.info("state.json bulunamadı, sıfırdan başlanıyor.");
            return new HashSet<>();
        }
        try {
            List<String> ids = json.readValue(file, new TypeReference<>() {});
            log.info("state.json yüklendi: " + ids.size() + " kayıt.");
            return new HashSet<>(ids);
        } catch (Exception e) {
            log.warning("state.json okunamadı, sıfırlanıyor: " + e.getMessage());
            return new HashSet<>();
        }
    }

    private void saveState() {
        try {
            // Sadece son 500 ID'yi tut — dosyanın şişmemesi için
            List<String> toSave = seenIds.stream()
                    .sorted()
                    .limit(500)
                    .toList();
            json.writerWithDefaultPrettyPrinter().writeValue(new File(STATE_FILE), toSave);
            log.info("state.json güncellendi: " + toSave.size() + " kayıt.");
        } catch (Exception e) {
            log.warning("state.json yazılamadı: " + e.getMessage());
        }
    }
}
