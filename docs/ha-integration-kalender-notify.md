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

## 1b. Wetter – Tageshoch / Tagestief (im `/configure`-Push)

Der `weather`-Block im `display`-Objekt der `DeviceConfig` zeigt jetzt unter der
großen Temperatur eine Meta-Zeile: **„Regen · 14° / 21°"** (Zustand · Tagestief / Tageshoch).
Dafür müssen **zwei neue Felder** mitgeschickt werden:

```jsonc
"display": {
  // ...
  "weather": {
    "enabled": true,
    "condition": "rainy",        // HA weather-State -> Lucide-Icon (Mapping unten)
    "temperature": 20,           // aktuelle Temperatur (große Zahl)
    "temperature_unit": "°",     // "°", "°C" oder "°F"
    "temp_low": 14,              // NEU – Tagestief  (Meta-Zeile)
    "temp_high": 21              // NEU – Tageshoch (Meta-Zeile)
  }
}
```

| Feld               | Typ    | Pflicht | Beschreibung |
|--------------------|--------|---------|--------------|
| `enabled`          | bool   | ja      | Wetter anzeigen |
| `condition`        | string | ja      | HA `weather`-State (`sunny`, `rainy`, `pouring`, `cloudy`, `partlycloudy`, `fog`, `snowy`, `lightning`, `windy`, `clear-night`, `hail`, `exceptional`, …) |
| `temperature`      | number | ja      | aktuelle Temperatur |
| `temperature_unit` | string | nein    | Default `°C`; für die Mockup-Optik `"°"` |
| `temp_low`         | number | nein    | **Tagestief** – ohne dieses Feld bleibt die Meta-Zeile nur der Zustand |
| `temp_high`        | number | nein    | **Tageshoch** |

**Wichtig (HA): Hoch/Tief gibt es NICHT mehr als Attribut.** Home Assistant hat das
`forecast`-Attribut am `weather`-Entity entfernt. Heute holt man die Tageswerte über den
Service **`weather.get_forecasts`** (`type: daily`), erster Eintrag = heute:

```yaml
# Beispiel: Tageshoch/-tief ermitteln und in den /configure-Payload geben
action: weather.get_forecasts
target: { entity_id: weather.home }
data: { type: daily }
response_variable: fc
# fc["weather.home"].forecast[0].temperature  -> Tageshoch  -> temp_high
# fc["weather.home"].forecast[0].templow      -> Tagestief  -> temp_low
```

(Gilt für alle Quellen – OpenWeatherMap, Met.no, … – das Feld heißt im Forecast
`temperature` für das Hoch und `templow` für das Tief.)

---

## 1c. Media-Player („Now Playing")

Zeigt den aktiven `media_player` als Widget. Besteht aus **zwei Teilen**:

### 1c-1) Anzeige-Modus — im `/configure`-Push (`display.media`)

```jsonc
"display": {
  // ...
  "media": {
    "mode": "compact",          // "off" | "compact" | "focus"
    "fanart_api_key": "xxxxxxxx" // optional: HD-Künstlerbilder (focus); ohne -> TheAudioDB-Fallback
  }
}
```
- **off** = Player aus. **compact** = kleine Karte oben links. **focus** = großes Cover mittig,
  Slideshow läuft (abgedunkelt) weiter, Uhr klein oben links, Agenda blendet aus.
- Player erscheint **nur bei aktiver Wiedergabe**: `playing` → sichtbar; `paused` → sichtbar,
  aber die App blendet ihn nach **30 s Pause selbst aus** (Sicherheitsnetz für media_player, die
  nie sauber auf `idle`/`off` wechseln); `idle`/`off` → versteckt. HA sollte State-Wechsel trotzdem
  pushen (inkl. `idle`/`off`), die App verlässt sich aber nicht darauf.
- `fanart_api_key`: kostenloser fanart.tv-Projekt-Key (für Künstler-Hintergrund im focus-Modus).
  Ohne Key nutzt die App TheAudioDB (free, keine Registrierung) als Fallback.

### 1c-2) Now-Playing-State — neuer Endpoint `POST /media`

`POST http://<device_ip>:<port>/media` (häufig pushen: bei State-/Track-Wechsel; Position
wird zwischen Pushes lokal hochgezählt):

```jsonc
{
  "state": "playing",                         // playing | paused | idle | off
  "title": "Bohemian Rhapsody",               // media_title
  "artist": "Queen",                          // media_artist
  "source": "Spotify · Wohnzimmer",           // freier Text
  "source_icon": "spotify",                   // MDI-Name (spotify/speaker/youtube/radio/cast/music …)
  "cover_url": "https://ha.local/api/media_player_proxy/media_player.spotify?token=...",
  "position": 73,                             // LIVE-Position in Sek. (s. u. — nicht roh!)
  "duration": 354,                            // media_duration (Sekunden)
  "can_prev": true,
  "can_next": true,
  "controls": {
    "play_pause_url": "https://ha.local/api/webhook/mp_kitchen_playpause",
    "next_url": "https://ha.local/api/webhook/mp_kitchen_next",
    "prev_url": "https://ha.local/api/webhook/mp_kitchen_prev"
  }
}
```

**Antwort:** `{"success": true, "state": "playing"}`

| Feld          | Typ    | Beschreibung |
|---------------|--------|--------------|
| `state`       | string | `playing`/`paused` → Player sichtbar; `idle`/`off` → versteckt |
| `title`/`artist` | string | Titel / Künstler |
| `source`      | string | Quelle als Text (z. B. „Spotify · Wohnzimmer") |
| `source_icon` | string | MDI-Name fürs Quell-Icon |
| `cover_url`   | string | **voll-qualifizierte** `entity_picture`-URL (Token darf drin sein; App lädt direkt) |
| `position`/`duration` | number | Sekunden → Fortschrittsbalken. **`position` muss die LIVE-Position sein**, nicht das rohe `media_position` (s. u.). Fehlt `duration`/`<=0`, wird kein Balken gezeigt (z. B. Bluetooth). |
| `controls`    | object | **Webhook-URLs**, die die App bei Tap auf Prev/Play-Pause/Next per `POST {}` aufruft |

**App-Verhalten / Steuerung:** Tippt man Prev/Play-Pause/Next, schickt die App ein `POST {}` an
die jeweilige `controls.*_url`. Lege dafür in HA **Webhooks** an, die `media_player.media_previous_track`
/ `media_play_pause` / `media_next_track` auf dem Ziel-Entity auslösen. State wird gecacht, eine frisch
gestartete Slideshow zeigt sofort die laufende Wiedergabe.

**Empfohlene HA-Umsetzung:** Automation auf `media_player`-**State-Changes** → `/media` pushen;
`cover_url` = `state_attr(entity,'entity_picture')` mit vorangestellter HA-Basis-URL; drei
`webhook`-Trigger für die Transport-Befehle.

> **⚠️ Wichtig — `position` als Live-Wert senden:**
> HAs Attribut `media_position` ist **eingefroren** zwischen State-Changes (es ändert sich nur bei
> Play/Pause/Seek/Track-Wechsel). Die Live-Position ist:
> ```jinja
> {{ (state_attr(entity,'media_position') | float(0))
>    + (now() - state_attr(entity,'media_position_updated_at')).total_seconds() }}
> ```
> Sendet man das rohe `media_position`, klebt/springt der Balken. Mit dem Live-Wert genügt ein Push
> **pro State-Change** (Play/Pause/Seek/Track) — die App tickt den Balken zwischen den Pushes selbst
> weiter. Ein 2-s-`time_pattern`-Spam ist **nicht** nötig (und bringt mit rohem `media_position`
> sogar das Stottern zurück). Bei Track-/State-Wechsel rebasen wir auf den gepushten Wert.

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

**Antwort:** `{"success": true, "shown": true, "via": "slideshow"|"overlay", "overlay_permission": true}`

Notifications funktionieren **immer** – läuft die Slideshow, wird die (frosted) In-Slideshow-Karte
gezeigt (`via: "slideshow"`); läuft sie nicht, ein **System-Overlay-Fenster** (`via: "overlay"`).
Das Overlay braucht die Berechtigung **„Über anderen Apps anzeigen" (SYSTEM_ALERT_WINDOW)** –
ist die nicht erteilt, ist `overlay_permission: false` und `shown: false` (nur dann erscheint nichts).

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
- [ ] **Wetter:** `display.weather` um `temp_low` / `temp_high` erweitern, via
      `weather.get_forecasts` (`type: daily`, Eintrag[0]: `temperature`→high, `templow`→low).
- [ ] **Media-Player:** `display.media` (`mode`, optional `fanart_api_key`) im `/configure`-Push;
      `media_player`-State per `POST /media` pushen; 3 Webhooks für Prev/Play-Pause/Next.
- [ ] `rest_command` (+ optional `notify`/Script-Wrapper) für `POST /notify`.
- [ ] Webhook(s) für die `callback_url`-Quittierung der Notifications.
- [ ] Geräte-IP/Port aus dem bestehenden Registrierungs-/Discovery-Mechanismus
      wiederverwenden (gleiche Quelle wie der `/configure`-Push).

---

## 4b. Slideshow-Anzeigeoptionen — im `/configure`-Push

Zwei optionale Bool-Felder direkt im `display`-Objekt der `DeviceConfig`:

```jsonc
"display": {
  // ... bestehende Felder (clock, weather, interval_seconds, pan_speed, mode, ...) ...
  "skip_wrong_aspect": false,       // nur Medien laden, deren Format ~zum Display passt
  "always_play_full_video": false   // Video nicht abbrechen, erst zu Ende spielen
}
```

| Feld                      | Typ  | Default | Beschreibung |
|---------------------------|------|---------|--------------|
| `skip_wrong_aspect`       | bool | `false` | Lädt nur Bilder/Videos, deren Seitenverhältnis bis ~20 % zum Display passt (gleiche Grenze, ab der Videos sonst Balken bekommen). Medien ohne bekannte Maße werden behalten. Greift bei Profilwechsel **und** beim Umschalten dieses Flags sofort (Playlist wird neu geladen). |
| `always_play_full_video`  | bool | `false` | Läuft der Slideshow-Timer ab, während ein Video spielt, wird **nicht** abgebrochen — es wird erst einmal komplett zu Ende gespielt, dann weitergeschaltet (Vorschub = später von beiden: Intervall **oder** ein voller Durchlauf). Kurze Videos loopen wie bisher bis zum Intervall. |

> `skip_wrong_aspect` nutzt `exifInfo` (Breite/Höhe inkl. Orientierung) aus den Immich-Suchergebnissen — HA muss dafür nichts liefern, die App holt das direkt von Immich.

---

## 5. Referenz: App-seitige Definition

Die maßgeblichen Datenklassen (Feldnamen via `@SerializedName`) liegen in
`app/src/main/java/de/koshi/photodream/model/Config.kt`:
`CalendarConfig`, `CalendarEvent`, `CalendarData`, `NotificationPayload`.
Die Endpunkte sind in `app/src/main/java/de/koshi/photodream/server/HttpServerService.kt`
(`/calendar`, `/notify`) implementiert.
