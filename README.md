# Dev-A v2 — Telefonda SOCKS5 Proxy (TCP + UDP)

PC tarafindaki **dev_b.py / tun2socks** istemcisine internet vermek icin telefonda
calisan, ozel yazilmis **SOCKS5 sunucusu** (Every Proxy uygulamasinin yerini alir).

Paket adı : `tr.devlab.devasocks`
Dinleme   : `0.0.0.0:8000` (SOCKS5, RFC 1928)
Yapı      : `minSdk 26`, `targetSdk 35`, Kotlin

## Ne yapar?

| Ozellik                | Durum | Not |
|------------------------|-------|-----|
| TCP CONNECT (RFC 1928) | ✅     | tum HTTPS/HTTP/VPN akisi bu kanaldan gecer |
| UDP ASSOCIATE          | ✅     | kalıcı NAT eslemesi; DNS (UDP 53) icin kritik |
| BIND                   | ❌     | REP=0x07 dondurur (gerek yok) |
| Kimlik dogrulama       | ✅     | yalnizca NO-AUTH (adb/USB ve guvenilir ag) |
| Foreground Service     | ✅     | kalıcı bildirim; MIUI batarya kesmelerine dayanir |
| Mini UI                | ✅     | start/stop, port, aktif baglanti sayaci, canli log |

Every Proxy'den farki: kendi kodumuz, HTTPS akisini bozmaz, UDP ASSOCIATE destekler,
hata toleransli basina thread'li yapidadir.

## APK'yi nasil alirim? (PC'de Android Studio YOK)

En kolay yol **GitHub Actions** ile derlemek:

1. Bu klasoru (`DevA-v2`) kendi GitHub hesabinda YENI b bir repoya yukle (push).
   - Depo adı ornek: `deva-v2` — icinde `Settings > Actions` acik olmali.
2. Repo sayfasinda **Actions** sekmesine gir; "Android APK (Dev-A v2)" isimli is
   otomatik baslar (`push` tetikleyicili). Gerekirse "Run workflow" ile elle cagir.
3. Is islemi bittiginde (yaklasik 5-8 dk) o kosunun detayina gir,
   asagidaki **Artifacts** kisminin adi **DevA-v2-debug-APK** olan dosyayı indir (zip).
4. Zip'in icindeki `app-debug.apk`'i telefona kopyala (USB/WhatsApp/Drive farketmez).
5. Telefonda APK'ye dokun; "bilinmeyen kaynak" uyarisi gelirse **yine de yukle/z
   izin ver** de.

> Alternatif: ADB varsa `adb install -r app-debug.apk` ile de kurulur.

## Telefonda ilk kurulum

1. Uygulamayi ac, **BAŞLAT**'a bas; kalici bildirim gorunen kadar bekle.
2. Ust satir **● ÇALIŞIYOR** olur, `Port: 8000` gorunur.
3. Log penceresinde(client baglandikca) `[+] CONNECT ...` satirlari akar.

## Xiaomi MIUI / HyperOS pil izkimleri (ÖNEMLİ)

MIUI ekran kapalinca arkaplan uygulamasini oldurur. Bunlar yapilmali:

1. **Otomatik başlatma**: Ayarlar > Uygulamalar > Uygulama yönet > Dev-A v2 >
   **Otomatik başlatma** = AÇIK.
2. **Pil kısıtlaması**: aynı uygulama sayfasi > **Pil tasarrufu** >
   **Kısıtlama yok (No restrictions)** sec.
3. **Arka plan çalışma**: Ayarlar > Pil ve performans > Sagındaki dişli (Ayarlar
   simgesi) > **Uygulama pil tasarrufu** sayfasinda Dev-A v2 -> "Kısıtlama yok".
4. **Son uygulamalar kilidi**: Uygulamalar ekrani (recents) AC, Dev-A v2
   kartinin uzerindeki simgeye uzun bas ve **Kilidi Ac** sec (kilit isareti).
5. **Bildirim izni (Android 13+)**: ilk BAŞLAT'ta izin sorulur; çıkmadıysan
   Ayarlar > Uygulamalar > Dev-A v2 > Bildirimler = AÇIK.
6. Guvenlik uygulaması (Güvenlik / Security) varsa "Uygulama kilitleme/temizle"
   ozelliklerinde Dev-A v2'yi beyaz listeye al.

Kalıcı bildirim gösterilirken uygulama telefon yeniden baslatilincaya kadar
yasar (START_STICKY; sistem oldururse yeniden baslar).

## PC tarafi baglanti (dev_b.py degerleri)

`dev_b.py` **Common degerleri** (bazi kismi default):

```python
ANDROID_PORT  = 8000
PROXY_SCHEME  = "socks5"
```

`tun2socks` zaten `--dns tcp://8.8.8.8:53` ile baslatilir; bu nedenle DNS
sorgulari **TCP CONNECT** icerisinden gecer ve UDP ASSOCIATE olmadan bile calisir.
Dev-A v2'nin UDP ASSOCIATE desteği ekstra guvence (ve dogrudan WiFi kullanimini tam yollu yapar).

## ÖNEMLİ KISIT: adb + UDP

`adb forward` **yalnizca TCP** aktarir. Yani USB/adb kuprusu uzerinde:

- SOCKS5 TCP CONNECT ✅ tam calisir (HTTP/HTTPS/DNS-over-TCP dahil)
- SOCKS5 UDP ASSOCIATE ❌ PC'den telefona UDP paketi gecmez

UDP'yi test etmek istersen telefon ile PC aynı agda olmalı ve PC telefona
dogrudan WiFi IP'sinden erisebilmeli (`8000 portu` acik). `tools/test_socks.py`
icindeki `--host` o zaman telefonun IP'si olur.

## Test listesi (PC'den)

Python 3.z yeterli (sadece stdlib):

```
cd DevA-v2\tools
python test_socks.py --host 127.0.0.1 --port 1080          # adb koprusunden
python test_socks.py --host 192.168.1.42 --port 8000       # dogrudan WiFi
```

Beklenen:

1. `[test] TCP CONNECT -> example.com:80` → **PASS** (TCP CONNECT dogrulaması)
2. `[test] DNS over TCP CONNECT -> 8.8.8.8:53` → **PASS** (rcode=0)
3. `[test] UDP ASSOCIATE -> 8.8.8.8:53 DNS` → **PASS** (yalnizca dogrudan IP/WiFi
   modunda; adb kuprsünde bilincli olarak FAIL doner)

Ek elle testler: tarayicida herhangi bir site (adb modu), `nslookup google.com 8.8.8.8`
(dev/tun aktifken), video akisi (stabilite).

## Kaynaktan derleme (gerekirse)

- JDK 17 + Android SDK 35 gerekir; kuruluysa `gradlew assembleDebug` calisir.
- GitHub Actions is akisi ayni islemi zaten yapar; PC'de derleme kural olarak gerekmez.

## Sorun giderme

- **PORT 8000 meşgul** → telefonda başka proxy uygulaması (Every Proxy) acik;
  kapat (veya dev_b.py'de ANDROID_PORT'u degistir + adb forward portunu da).
- **Uygulama bir sure sonra durdu** → MIUI pil ayarlarinin 1-4. maddelerini
  eksik yapmis olabilirsin; hepsini tamamla.
- **PC tarafi baglanamiyor** → adb forward komutu (`adb forward tcp:1080 tcp:8000`)
  aktif mi? Telefonda Dev-A **ÇALIŞIYOR** mu? Log'da `[+] SOCKS5 dinleme
  basladi` satiri var mi?
- **DNS calisiyor ama siteler acilmiyor** → tun2socks argumentındaki
  `--dns tcp://8.8.8.8:53` ayarini kontrol et (dev_b.py default'u bu olmali).
- **HTTPS akisi kirik** → bu uygulama Every Proxy degil; olusuyorsa log'daki
  CONNECT satirlarinda hangi hedefte kirildigina bak.

## Guvenlik

Bu SOCKS5 sunucusu **NO-AUTH** dur; herkese aciktir. Sadece adb/USB kuprusuve
veya guvenilir yerel agda kullan; ag'a acmak zorunda kalirsan (WiFi) telefon ve
PC'n aynı kuresel agda, guvenilir bir ag oldugunundan emin ol.

## Dosya yolu ozeti

```
DevA-v2/
├── .github/workflows/build.yml     ← GitHub Actions: debug APK artifact
├── app/build.gradle.kts            ← modul derleme ayari (SDK 35, min 26)
├── app/src/main/AndroidManifest.xml
├── app/src/main/java/tr/devlab/devasocks/
│   ├── Socks5Server.kt             ← RFC 1928 (TCP CONNECT + UDP ASSOCIATE)
│   ├── SocksService.kt             ← foreground service + bildirim
│   ├── MainActivity.kt             ← mini UI (start/stop, port, sayaç, log)
│   ├── LogBus.kt                   ← canli log kulakcık
│   └── DevAState.kt                ← durum paylasim
├── app/src/main/res/...            ← layout, temalar, ikonlar
├── tools/test_socks.py             ← PC'den SOCKS5 (TCP + UDP) test araci
├── gradlew / gradlew.bat           ← Gradle 8.10.2 wrapper
└── README.md
```
