# Liceo Campus Tracker — Java desktop app

A Java (Swing) version of the campus tracker for Windows, Mac or Linux. It uses the same Supabase
database as the phone web app, so buildings and rooms added in one show up in the other.
It uses only the standard JDK — there are no libraries to download.

## Run it

- **Quick way:** double-click `run.bat` (or run `java -jar LiceoCampusTracker.jar`). Needs Java 17 or newer.
- **In IntelliJ IDEA:** File → Open → choose this `java` folder. Right-click `src` → Mark Directory as → Sources Root.
  Open `src/liceo/Main.java` and press the green Run arrow.

## What it does

- Welcome window: **Student** or **Admin** (log in / register; admin needs the admin code) or **Guest**.
- Search buildings and rooms, filter the list, and see every building as a pin on the campus map.
- Click the map to pick a spot: the details show the distance and direction from that spot to the selected place.
- Logged-in users can add buildings and rooms and attach a photo. Students can edit their own; admins can edit or delete anything.
- Offline: the last loaded data and the map squares you have seen are saved on the computer and shown read-only.

Not in the desktop version: GPS, the live camera view and offline editing — those are in the phone web app.

## How the code is organised (`src/liceo`)

| Package | Classes | Job |
| --- | --- | --- |
| `liceo` | `Main`, `Config` | Starts the app; project settings |
| `liceo.model` | `Place` (abstract), `Building`, `Room`, `Geo` | The data. `Building` and `Room` extend `Place` and override `badge()`, `summary()`, `table()` |
| `liceo.data` | `SupabaseClient`, `CampusStore`, `Json`, `ApiException` | Talks to Supabase, keeps the lists, search, permissions, offline copy |
| `liceo.ui` | `MainFrame`, `MapPanel`, `LoginDialog`, `PlaceForms`, `PlaceCell`, `Theme` | The windows |

OOP ideas used: inheritance and abstract classes (`Place`), polymorphism (the list and the store work with `Place`
without caring which kind it is), encapsulation (private fields with getters and setters), an interface
(`MapPanel.Listener`), a custom exception (`ApiException`), and an enum (`CampusStore.Filter`).

Map data © OpenStreetMap contributors.
