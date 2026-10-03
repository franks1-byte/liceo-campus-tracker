# Liceo Campus Tracker

An installable web app for finding buildings and classrooms at Liceo de Cagayan University.
One public link works on phones and computers, and it can be added to the home screen like an app.

**Live link:** https://franks1-byte.github.io/liceo-campus-tracker/

## What it does

- **Map and search** – every building is a pin on the campus map; search rooms, buildings and codes.
- **GPS** – shows where you are, sorts places by distance, and "Guide me" draws a line to the place with distance and direction.
- **Camera view (home screen)** – hold the phone up and building labels float where each building really is, HUD-tracker style, with a lock-on when you point at one. A GTA-style minimap sits in the corner and turns as you turn; tap it for the full map.
- **Camera** – take a photo of a building or room when adding it (falls back to the gallery if there is no camera).
- **Offline mode** – the app, the campus map and the place list are saved on the device. Places added offline are queued and upload on their own when the connection comes back.
- **Online mode** – data and photos are stored in Supabase and shared with everyone.
- **Roles** – guests look around without an account, students add places and edit their own, admins manage everything. On first open the app asks who you are: Student (log in / register), Admin (log in / register with the admin code) or Guest.

## How it is built

| Part | What |
| --- | --- |
| `index.html`, `styles.css`, `app.js` | The app (plain HTML, CSS and JavaScript, no build step) |
| `sw.js`, `manifest.webmanifest` | Offline support and "install as app" |
| `config.js` | Supabase project address, public key and campus coordinates |
| `supabase/schema.sql` | Database tables, roles and security rules |
| `vendor/` | Leaflet (map) and supabase-js, bundled so they work offline |

Map tiles are from OpenStreetMap.

## Run it on your computer

GPS, camera and offline mode need `https` or `localhost`, so open it through a small local server rather than double-clicking the file:

```
python -m http.server 8000
```

then visit http://localhost:8000.
