# PhotoDream – Übergabe an die HA-Integration: Kalender & Notifications

Dieses Dokument beschreibt **zwei neue Funktionen** der PhotoDream-Android-App, die auf
der Home-Assistant-Seite angebunden werden müssen:

1. **Kalender-Overlay** – Anzeige anstehender Kalender-Termine in der Slideshow.
2. **Notification-Overlay** – HA-gesteuerte Popup-Benachrichtigungen (optisch im HA-Stil,
   antippbar mit Callback, optional mit Sound).

Die App-Seite ist bereits fertig implementiert und gebaut. **Auf der HA-Seite ist noch
nichts umgesetzt** – das ist der Auftrag.

---

## 0. Kontext: bestehende Kommunikation (zur Erinnerung)

PhotoDream betreibt auf dem Gerät einen lokalen **HTTP-Server** (NanoHTTPD), erreichbar
unter `http://<device_ip>:<port>` (Default-Port **8080**, in den App-Settings änderbar).

Die bestehende Integration:
- registriert das Gerät via HA-Webhook `photo_dream_register`,
- **pusht die Geräte-Config** per `POST http://<device_ip>:<port>/configure` (JSON `DeviceConfig`),
- empfängt Status-Updates vom Gerät via dem in der Config gesetzten `webhook_url`.

Die beiden neuen Funktionen folgen genau diesem Muster: **HA pusht aktiv an die Geräte-IP.**
Es wird **kein** Long-Lived-Token in der App benötigt.

> Alle Endpunkte sind **HTTP** (kein TLS) im lokalen Netz. POST-Body ist JSON.
> Antworten sind JSON, z. B. `{"success": true, ...}`.

---

## 1. Kalender-Overlay

Kalender bestehen in HA typischerweise aus **mehreren `calendar.*`-Entities**. Die App
erwartet **eine bereits zusammengeführte, sortierbare Event-Liste**. Das Aggregieren
mehrerer Kalender ist **Aufgabe der HA-Seite**.

Die Kalenderanbindung besteht aus **zwei Teilen**:

### 1a) Anzeige-Einstellungen — im bestehenden `/configure`-Push

Das `display`-Objekt der `DeviceConfig` bekommt einen neuen optionalen Block `calendar`:

```jsonc
"display": {
  // ... bestehende Felder (clock, weather, interval_seconds, ...) ...
  "calendar": {
    "enabled": true,          // Overlay an/aus
    "position": 3,            // Position, gleiches Schema wie die Uhr (siehe unten)
    "max_events": 5,          // max. Anzahl angezeigter Termine
    "show_location": false,   // Ort je Termin anzeigen
    "font_size": 16           // Basis-Schriftgröße in sp
  }
}
```

**`position`-Schema** (identisch zur Uhr):
`0`=oben-links, `1`=oben-mitte, `2`=oben-rechts, `3`=unten-links, `4`=unten-mitte,
`5`=unten-rechts, `6`=zentriert.
*(Tipp: nicht dieselbe Position wie die Uhr wählen, sonst überlappen sie.)*

| Feld            | Typ     | Default | Beschreibung                          |
|-----------------|---------|---------|---------------------------------------|
| `enabled`       | bool    | `false` | Overlay anzeigen                      |
| `position`      | int     | `3`     | Position (0–6, s. o.)                 |
| `max_events`    | int     | `5`     | max. Termine                          |
| `show_location` | bool    | `false` | Ort je Termin anzeigen                |
| `font_size`     | int     | `16`    | Basis-Schriftgröße (sp)               |

### 1b) Event-Daten — neuer Endpoint `POST /calendar`

`POST http://<device_ip>:<port>/calendar`

```jsonc
{
  "events": [
    {
      "title": "Zahnarzt",
      "start": "2026-06-08T09:00:00+02:00",
      "end":   "2026-06-08T09:30:00+02:00",
      "all_day": false,
      "calendar": "Privat",      // Anzeigename des Quell-Kalenders
      "color": "#03a9f4",        // Akzentfarbe (Farb-Punkt vor dem Termin)
      "location": "Praxis Dr. X"
    },
    {
      "title": "Geburtstag Anna",
      "start": "2026-06-09",     // date-only = ganztägig
      "all_day": true,
      "calendar": "Familie",
      "color": "#8bc34a"
    }
  ]
}
```

**Antwort:** `{"success": true, "count": 2}`

| Feld       | Typ    | Pflicht | Beschreibung                                                        |
|------------|--------|---------|--------------------------------------------------------------------|
| `title`    | string | ja      | Termin-Titel                                                       |
| `start`    | string | ja      | ISO-8601. Mit Offset (`...+02:00`), als `Z`-Instant, **oder** date-only `YYYY-MM-DD` für ganztägig |
| `end`      | string | nein    | ISO-8601 (aktuell nur informativ, nicht angezeigt)                |
| `all_day`  | bool   | nein    | `true` → zeigt „ganztägig" statt Uhrzeit                           |
| `calendar` | string | nein    | Anzeigename des Quell-Kalenders                                   |
| `color`    | string | nein    | Hex-Farbe `#RRGGBB`/`#AARRGGBB` für den Farb-Punkt (Fallback weiß) |
| `location` | string | nein    | Ort (nur sichtbar bei `show_location: true`)                      |

**App-Verhalten:**
- Events werden **nach Datum gruppiert** mit Tages-Headern: „Heute", „Morgen",
  sonst z. B. „Mo, 09.06.".
- Sortierung nach `start` (aufsteigend), dann auf `max_events` gekürzt.
- Die App **cached** die zuletzt empfangene Liste im Foreground-Service. Eine frisch
  startende Slideshow zeigt sofort die letzten Events. → HA muss nicht exakt zum
  Slideshow-Start pushen.
- Overlay erscheint nur, wenn `display.calendar.enabled = true` **und** Events vorhanden sind.

**Empfohlene HA-Umsetzung:**
- Eine Automation (z. B. alle 5–15 min und bei `calendar`-State-Changes), die
  `calendar.get_events` über **alle gewünschten `calendar.*`-Entities** aufruft
  (Zeitfenster z. B. jetzt … +7 Tage), die Ergebnisse zu einer Liste zusammenführt,
  pro Quell-Kalender `calendar`-Name + `color` setzt, und per `rest_command` an
  `/calendar` pusht.
- `color` am besten pro Kalender konfigurierbar machen (Mapping entity → Farbe).

---

## 2. Notification-Overlay

`POST http://<device_ip>:<port>/notify`

Zeigt eine **Karte über der Slideshow** (HA-Notification-Optik: farbiger Akzentbalken,
fetter Titel, Text, optionales Bild). Beim Antippen wird optional eine **Callback-URL**
ausgelöst und die Karte geschlossen – **ohne** die Slideshow zu beenden.

```jsonc
{
  "title": "Haustür",
  "message": "Es klingelt an der Haustür",
  "icon": "mdi:doorbell",             // MDI-Icon-Name (HA-Style)
  "color": "#e0564e",                  // Akzent-/Quellfarbe (Icon-Tint + Timerbalken)
  "image_url": "https://ha.local/api/camera_proxy/camera.tuer?token=...",
  "duration": 10,                      // Sek.; <= 0 = bleibt bis Tap stehen
  "sound": true,                       // System-Notification-Sound abspielen
  "callback_url": "https://ha.local/api/webhook/tuerklingel_quittiert",
  "callback_method": "POST"            // "POST" (Body {}) oder "GET"
}
```

**Antwort:** `{"success": true, "shown": true}`
`shown` ist `false`, wenn aktuell **keine Slideshow läuft** (dann gibt es kein Overlay –
bewusst **kein** System-Toast-Fallback).

| Feld              | Typ    | Pflicht | Default  | Beschreibung                                              |
|-------------------|--------|---------|----------|----------------------------------------------------------|
| `message`         | string | **ja**  | –        | Haupttext (einzeilig, wird ggf. mit … gekürzt)           |
| `title`           | string | nein    | –        | Titel (fett); fehlt → nur Text                           |
| `icon`            | string | nein    | `mdi:bell`| MDI-Icon-Name, z. B. `mdi:doorbell` (siehe unten)       |
| `color`           | string | nein    | `#5B8DEF`| Hex-Quellfarbe: tönt Icon-Kachel + Timerbalken           |
| `image_url`       | string | nein    | –        | Bild rechts (per Glide geladen; **ohne** Auth-Header)    |
| `duration`        | int    | nein    | `8`      | Sekunden bis Auto-Dismiss; `<= 0` = persistent bis Tap   |
| `sound`           | bool   | nein    | `false`  | System-Notification-Ton beim Einblenden                  |
| `callback_url`    | string | nein    | –        | Wird beim Antippen aufgerufen (fire-and-forget)          |
| `callback_method` | string | nein    | `POST`   | `POST` (mit Body `{}`) oder `GET`                        |

**MDI-Icons:** Die App bündelt den kompletten Material-Design-Icons-Webfont (dasselbe Set,
das HA nutzt) plus eine Name→Codepoint-Tabelle. `data.icon` aus dem HA-`notify`-Payload kann
also direkt durchgereicht werden (`"mdi:doorbell"` oder `"doorbell"`). Unbekannte/leere Namen
fallen auf `mdi:bell` zurück. Die Optik orientiert sich am „Aurora"-Design: Frosted-Glass-Karte
oben zentriert, getönte Icon-Kachel, Titel + Text, optionales Bild, ablaufender Timerbalken.

**App-Verhalten:**
- Overlay wird oben-zentriert eingeblendet (Animation), nach `duration` Sek. ausgeblendet.
- **Tap auf die Karte** → ruft `callback_url` im Hintergrund-Thread auf und schließt die
  Karte. Die Slideshow läuft normal weiter (Tap daneben beendet sie wie gewohnt).
- `image_url` muss **ohne Authentifizierung** erreichbar sein (z. B. `camera_proxy` mit
  Token in der URL, oder ein öffentlich erreichbares Bild).
- `sound` nutzt den Default-Notification-Ton des Geräts; bei Stummschaltung passiert
  hörbar nichts, die Karte erscheint trotzdem.

**Empfohlene HA-Umsetzung:**
- Ein `rest_command` (z. B. `photodream_notify`) mit den obigen Feldern als Variablen,
  Ziel `http://{{ device_ip }}:{{ port }}/notify`.
- Optional eine `notify`-Plattform / ein Script-Wrapper, damit Automationen es bequem
  aufrufen können.
- Für den Tap-Callback: einen HA-**Webhook** anlegen
  (`callback_url = https://<ha>/api/webhook/<id>`) und darauf eine Automation triggern
  (z. B. „Türklingel quittiert" → Licht aus, Benachrichtigung stoppen, …).

---

## 3. Beispiel-Aufrufe (zum lokalen Testen)

```bash
# Kalender
curl -X POST http://192.168.1.50:8080/calendar \
  -H "Content-Type: application/json" \
  -d '{"events":[{"title":"Test","start":"2026-06-07T18:00:00+02:00","all_day":false,"calendar":"Privat","color":"#03a9f4"}]}'

# Notification mit Icon + Sound + Callback
curl -X POST http://192.168.1.50:8080/notify \
  -H "Content-Type: application/json" \
  -d '{"title":"Test","message":"Hallo aus HA","icon":"mdi:doorbell","color":"#e0564e","duration":10,"sound":true,"callback_url":"https://ha.local/api/webhook/test_cb"}'
```

```powershell
# PowerShell-Äquivalent
Invoke-RestMethod -Method Post -Uri "http://192.168.1.50:8080/notify" `
  -ContentType "application/json" `
  -Body '{"message":"Hallo aus HA","sound":true,"duration":8}'
```

---

## 4. Checkliste für die HA-Integration

- [ ] `DeviceConfig`/Config-Schema um `display.calendar` (`enabled`, `position`,
      `max_events`, `show_location`, `font_size`) erweitern und im `/configure`-Push mitsenden.
- [ ] Konfigurierbare Auswahl der einzubeziehenden `calendar.*`-Entities + Farb-Mapping.
- [ ] Automation/Logik: `calendar.get_events` über alle gewählten Entities → Liste mergen,
      sortieren, an `POST /calendar` pushen (periodisch + bei Änderungen).
- [ ] `rest_command` (+ optional `notify`/Script-Wrapper) für `POST /notify`.
- [ ] Webhook(s) für die `callback_url`-Quittierung der Notifications.
- [ ] Geräte-IP/Port aus dem bestehenden Registrierungs-/Discovery-Mechanismus
      wiederverwenden (gleiche Quelle wie der `/configure`-Push).

---

## 5. Referenz: App-seitige Definition

Die maßgeblichen Datenklassen (Feldnamen via `@SerializedName`) liegen in
`app/src/main/java/de/koshi/photodream/model/Config.kt`:
`CalendarConfig`, `CalendarEvent`, `CalendarData`, `NotificationPayload`.
Die Endpunkte sind in `app/src/main/java/de/koshi/photodream/server/HttpServerService.kt`
(`/calendar`, `/notify`) implementiert.
