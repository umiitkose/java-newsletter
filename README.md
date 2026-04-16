# ☕ Java Digest Bot

Java ekosisteminin önemli mimarlarının (Brian Goetz, Ron Pressler, Gavin Bierman ve diğerleri)
yeni yazılarını, mailing list mesajlarını ve JEP durum değişikliklerini takip edip
her gün **Slack** kanalına linksiz Türkçe özet olarak yayınlayan bot.

## Takip Edilen Kaynaklar

### Yazar Filtreli (sadece takip edilen isimler)

| Kaynak | Yöntem |
|--------|--------|
| [inside.java](https://inside.java) | RSS + yazar filtresi |
| [InfoQ Java](https://www.infoq.com/java/) | RSS + yazar filtresi |

### OpenJDK Mailing Listleri (yazar filtreli)

| Liste | Konu |
|-------|------|
| amber-spec-experts | Records, Pattern Matching, Sealed Classes |
| valhalla-spec-observers | Value Types, Null Safety |
| loom-dev | Virtual Threads, Structured Concurrency |
| jdk-dev | Genel JDK geliştirme |
| panama-dev | Foreign Function & Memory API |
| leyden-dev | Startup performansı, AOT |
| compiler-dev | HotSpot derleyici |
| zgc-dev | ZGC garbage collector |

### Topluluk Blogları (anahtar kelime filtreli)

| Kaynak | Açıklama |
|--------|----------|
| [dev.java](https://dev.java) | Oracle resmi Java geliştirici portali |
| [Java Almanac](https://javaalmanac.io) | Java sürüm ve platform değişiklikleri (GitHub Atom feed) |
| [Foojay.io](https://foojay.io) | Java topluluk haberleri, JEP analizleri |
| [Baeldung](https://www.baeldung.com) | Java tutorial ve derinlemesine yazılar |
| [DZone Java](https://dzone.com/java) | Java zone makaleleri |
| [Spring Blog](https://spring.io/blog) | Spring Framework güncellemeleri |
| [Quarkus Blog](https://quarkus.io/blog) | Quarkus framework haberleri |
| [JetBrains Blog](https://blog.jetbrains.com/idea/) | IntelliJ IDEA ve Java araç haberleri |

### JEP Durum Takibi

[openjdk.org/jeps](https://openjdk.org/jeps/0) sayfasından Amber, Valhalla, Loom, Panama, Leyden ve Lilliput projelerinin JEP durumlarını izler. Durum değişikliği olduğunda bildirim gönderir.

## Takip Edilen İsimler

| İsim | Proje |
|------|-------|
| Brian Goetz | Amber, Valhalla |
| Ron Pressler | Loom |
| Gavin Bierman | Amber — dil spec |
| Maurizio Cimadamore | Valhalla, Panama |
| Mark Reinhold | Leyden, genel mimari |
| Dan Smith | Amber |
| Angelos Bimpoudis | Amber |
| Viktor Klang | Loom |
| Alan Bateman | Core Libraries, Loom |
| Paul Sandoz | Panama, Babylon |

> Yazarlar ve anahtar kelimeler `config.yml` dosyasından yönetilir.

---

## Kurulum

### 1. Repoyu fork'la veya klonla

```bash
git clone https://github.com/umiitkose/java-newsletter
cd java-newsletter
```

### 2. Yapılandırma

Tüm ayarlar `config.yml` dosyasında merkezi olarak yönetilir:

```yaml
authors:         # takip edilen yazarlar
keywords:        # topluluk blogları için anahtar kelime filtresi
rss:             # RSS kaynakları (authorFiltered + community)
mailingLists:    # OpenJDK mailing listleri
jep:             # JEP durum takibi (enabled: true/false)
ai:              # AI özet (enabled: true/false, provider: openai/gemini/ollama)
pages:           # GitHub Pages (enabled: true/false, outputDir: docs)
```

### 3. Slack Webhook Kur

Her kanal için ayrı webhook oluştur:

1. [api.slack.com/apps](https://api.slack.com/apps) → **Create New App → From scratch**
2. **Incoming Webhooks** → Activate → **Add New Webhook to Workspace**
3. İstediğin kanalı seç → Webhook URL'sini kopyala

Önerilen kanal yapısı:
```
#java-general   → genel içerikler
#java-amber     → Records, Pattern Matching, Sealed Classes
#java-valhalla  → Value Types, Null Safety
#java-loom      → Virtual Threads, Structured Concurrency
#java-leyden    → Startup, AOT
#java-panama    → Native Interop, Vector API
```

### 4. GitHub Secrets Ekle

Repo → **Settings → Secrets and variables → Actions → New repository secret**

| Secret Adı | Açıklama | Zorunlu |
|------------|----------|---------|
| `SLACK_WEBHOOK_GENERAL` | Genel kanal webhook | Evet |
| `SLACK_WEBHOOK_AMBER` | #java-amber | Hayır |
| `SLACK_WEBHOOK_VALHALLA` | #java-valhalla | Hayır |
| `SLACK_WEBHOOK_LOOM` | #java-loom | Hayır |
| `SLACK_WEBHOOK_LEYDEN` | #java-leyden | Hayır |
| `SLACK_WEBHOOK_PANAMA` | #java-panama | Hayır |
| `OPENAI_API_KEY` | LLM özet için OpenAI API key | Hayır |
| `GEMINI_API_KEY` | LLM özet için Gemini API key | Hayır |

> Uygulama sadece Slack'e yayın yapar.

### 5. İlk çalıştırma

Actions sekmesinden **"Java Digest — Günlük Özet"** workflow'unu seç →
**Run workflow** ile manuel tetikle.

`mode=test` ile çalıştırırsan `FORCE_SUMMARY` otomatik açılır; yeni içerik olmasa bile son içeriklerden test amaçlı Slack özeti gönderilir. Bu modda `state.json` ve `docs/` güncellenmez.

---

## Çalışma Zamanı

| Zamanlama | Açıklama |
|-----------|----------|
| Her gün 08:00 UTC | Günlük digest (Türkiye: 11:00 yaz / 10:00 kış) |
| Pazartesi 09:00 UTC | Haftalık özet (son 7 günün içerikleri) |

Değiştirmek için `.github/workflows/daily-digest.yml` içindeki cron satırını düzenle.

---

## Yerel Test

```bash
# Minimum: sadece makale toplama
mvn package -q
java -jar target/java-digest-1.0-SNAPSHOT.jar

# Slack yayın ile:
export SLACK_WEBHOOK_GENERAL="..."
java -jar target/java-digest-1.0-SNAPSHOT.jar

# AI özet aktif (config.yml'da ai.enabled: true olmalı):
export OPENAI_API_KEY="sk-..."
java -jar target/java-digest-1.0-SNAPSHOT.jar

# Gemini ile AI özet:
# config.yml -> ai.provider: gemini
export GEMINI_API_KEY="..."
export GEMINI_MODEL="gemini-2.5-flash"   # opsiyonel
export SUMMARY_MAX_ARTICLES="12"         # opsiyonel, AI ozetlenecek makale limiti
java -jar target/java-digest-1.0-SNAPSHOT.jar
```

AI ozetleme oncelikle su projelere odaklanir: Amber, Valhalla, Loom, Panama, Leyden ve JEP degisimleri. Limit dolmazsa kalan son iceriklerden tamamlanir.

---

## Mimari

```
GitHub Actions (cron — günlük/haftalık)
        │
        ▼
   Main.java (CompletableFuture ile paralel fetch)
    │
    ├── config.yml ─────────── DigestConfig (merkezi yapılandırma)
    │
    ├── Fetcher'lar (paralel çalışır)
    │   ├── RssFetcher
    │   │   ├── fetchAuthorFilteredFeeds()  → inside.java, InfoQ (yazar filtreli)
    │   │   ├── fetchCommunityBlogs()       → Foojay, Quarkus, DZone... (keyword filtreli)
    │   │   └── fetchMailingLists()         → 8 OpenJDK mailing list (RSS)
    │   ├── MailingListScraper             → RSS başarısız olursa HTML fallback
    │   └── JepTracker                     → JEP durum değişikliklerini izle
    │
    ├── StateManager ──────── state.json ile tekrar gönderimi önle
    ├── AISummarizer ──────── OpenAI / Gemini / Ollama ile Turkce ozet (hata durumunda fallback)
    │
    ├── SlackNotifier ─────── linksiz genel ozet + kaynak bazli maddeler
    │
    └── DigestPageGenerator ── docs/ klasörüne Markdown arşiv sayfası
```

## Proje Yapısı

```
java-digest/
├── config.yml                          # merkezi yapılandırma
├── state.json                          # gönderilen makale ID'leri
├── jep-state.json                      # JEP durum geçmişi
├── docs/                               # GitHub Pages arşivi
│   ├── index.md
│   └── 2026-04-15.md
├── pom.xml
├── .github/workflows/daily-digest.yml
└── src/main/java/com/javadigest/
    ├── Main.java
    ├── config/
    │   └── DigestConfig.java
    ├── fetcher/
    │   ├── RssFetcher.java
    │   ├── MailingListScraper.java
    │   └── JepTracker.java
    ├── generator/
    │   └── DigestPageGenerator.java
    ├── model/
    │   └── Article.java
    ├── notifier/
    │   └── SlackNotifier.java
    ├── state/
    │   └── StateManager.java
    └── summarizer/
        └── AISummarizer.java
```

---

## Lisans

MIT
