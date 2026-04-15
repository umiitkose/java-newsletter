# ☕ Java Digest Bot

Java ekosisteminin önemli mimarlarının (Brian Goetz, Ron Pressler, Gavin Bierman ve diğerleri)
yeni yazılarını ve mailing list mesajlarını takip edip her gün Telegram ve Slack'e özetleyen bot.

## Takip Edilen Kaynaklar

| Kaynak | Yöntem |
|--------|--------|
| [inside.java](https://inside.java) | RSS (`/feed.xml`) + yazar filtresi |
| OpenJDK amber-spec-experts | Mailing list RSS / HTML scrape |
| OpenJDK valhalla-spec-observers | Mailing list RSS / HTML scrape |
| OpenJDK loom-dev | Mailing list RSS / HTML scrape |

## Takip Edilen İsimler

- Brian Goetz (Amber, Valhalla)
- Ron Pressler (Loom)
- Gavin Bierman (Amber — dil spec)
- Maurizio Cimadamore (Valhalla, Panama)
- Mark Reinhold (Leyden, genel mimari)
- Dan Smith (Amber)
- Angelos Bimpoudis (Amber)
- Viktor Klang (Loom)
- Alan Bateman (Core Libraries, Loom)
- Paul Sandoz (Panama, Babylon)

---

## Kurulum

### 1. Repoyu fork'la veya klonla

```bash
git clone https://github.com/senin-kullanici-adin/java-digest
cd java-digest
```

### 2. Telegram Bot Kur

1. Telegram'da [@BotFather](https://t.me/botfather)'a yaz
2. `/newbot` komutuyla yeni bot oluştur → **BOT_TOKEN** al
3. Bota mesaj at, sonra şu URL'den chat ID'ni bul:
   ```
   https://api.telegram.org/bot<BOT_TOKEN>/getUpdates
   ```
4. Kanal kullanacaksan: botu kanala admin olarak ekle, chat ID'si `-100...` formatında başlar

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

| Secret Adı | Açıklama |
|------------|----------|
| `TELEGRAM_BOT_TOKEN` | BotFather'dan alınan token |
| `TELEGRAM_CHAT_ID` | Kanal veya kullanıcı ID'si |
| `SLACK_WEBHOOK_GENERAL` | Genel kanal webhook (zorunlu) |
| `SLACK_WEBHOOK_AMBER` | #java-amber (opsiyonel) |
| `SLACK_WEBHOOK_VALHALLA` | #java-valhalla (opsiyonel) |
| `SLACK_WEBHOOK_LOOM` | #java-loom (opsiyonel) |
| `SLACK_WEBHOOK_LEYDEN` | #java-leyden (opsiyonel) |
| `SLACK_WEBHOOK_PANAMA` | #java-panama (opsiyonel) |

> Slack'te sadece `SLACK_WEBHOOK_GENERAL` zorunludur.
> Diğerleri eksikse o proje içerikleri GENERAL'e düşer.

### 5. İlk çalıştırma

Actions sekmesinden **"Java Digest — Günlük Özet"** workflow'unu seç →
**Run workflow** ile manuel tetikle.

---

## Çalışma Zamanı

Varsayılan: **Her gün 08:00 UTC** (Türkiye saati: 11:00 yaz / 10:00 kış)

Değiştirmek için `.github/workflows/daily-digest.yml` içindeki cron satırını düzenle:
```yaml
- cron: '0 8 * * *'   # saat 8:00 UTC
```

---

## Yerel Test

```bash
export TELEGRAM_BOT_TOKEN="..."
export TELEGRAM_CHAT_ID="..."
export SLACK_WEBHOOK_GENERAL="..."

mvn package -q
java -jar target/java-digest-1.0-SNAPSHOT.jar
```

---

## Mimari

```
GitHub Actions (cron)
        │
        ▼
   Main.java
    ├── RssFetcher         → inside.java + mailing list RSS
    ├── MailingListScraper → RSS başarısız olursa HTML fallback
    ├── StateManager       → state.json ile tekrar gönderimi önle
    ├── TelegramNotifier   → tek mesaj, yazara göre gruplu
    └── SlackNotifier      → proje tag'ine göre kanallara dağıt
```
