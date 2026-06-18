# jverein.hikvision

Jameica plugin that synchronises jVerein members to a Hikvision DS-K access
controller (tested against `DS-K2702WX-E1(P)` firmware V1.7.4 via ISAPI).

Runs **manually** from a button in Jameica's Settings dialog — no polling,
no background scheduler. jVerein is the source of truth: each click
computes the diff between jVerein and the controller and applies the
changes one-way (jVerein → Hikvision).

## What gets synced

For each jVerein member:

| Condition | Action |
|---|---|
| `austritt` is set | member is **deleted** from the controller |
| transponder zusatzfeld is empty / `0` / `null` | member is **not present** on the controller |
| has chips listed in `transponder` zusatzfeld (e.g. `1,2,Armband1`) | **created** with those chips, **updated** if chips changed |

Identity mapping:

- Member with `externemitgliedsnummer` → Hikvision `employeeNo = int(externe)`,
  `userType=normal`, group = configured Mitglieder group.
- Member without `externemitgliedsnummer` → treated as a **sponsor**.
  `employeeNo = G{jv_id}`, `userType=visitor`, group = configured sponsor
  group (BSV / default).

Hikvision entries the plugin does **not** manage (e.g. `SKM0000NNN`
admin/loaner entries, anything not int-parseable and not `G`-prefixed) are
left strictly alone — never touched, never deleted.

## Requires

- **Jameica 2.10+ with JVerein 3.1+ installed.** That's it on the jVerein
  side — this plugin runs inside Jameica and talks to JVerein's database
  directly via `Einstellungen.getDBService()`. **No HTTP / REST is used
  for the jVerein side**, so `jverein.rest` is *not* a dependency.
- A reachable Hikvision DS-K-series access controller with ISAPI enabled
  (tested against DS-K2702WX-E1(P) firmware V1.7.4).

## Install

1. Download the latest plugin zip from `releases/nightly/` (or build it
   yourself — see *Building*).
2. In Jameica: **Datei → Einstellungen → Plugins → "Plugin aus Datei
   installieren"**, point at `jverein.hikvision-0.1.0-nightly.zip`,
   restart Jameica.
3. In **Datei → Einstellungen** there is now a **Hikvision** tab.
   Fill in:
   - Controller URL (e.g. `https://192.168.178.95`)
   - Controller user / password (digest auth — password lives in Jameica's
     encrypted Wallet, not the plain properties file)
   - Path to `chip_kartennummer.csv` (the chip ↔ Kartennummer lookup)
   - Mitglieder org-group UUID + name (default userGroup for members)
   - Sponsor (guest) org-group UUID + name (default userGroup for sponsors)
   - Zusatzfeld name (default `transponder`)

   Door-access **Berechtigungsgruppen** are no longer a single global
   setting — they are assigned per member (0..n) via *Zugangssystem →
   Benutzer → Zuweisung bearbeiten* and synced to each user's
   `regionPermissionGroupIDList`.
   - Pause between calls (ms — the DS-K controller has a small concurrent-
     session pool; `2000` is the safe default)
4. Click **Speichern** to persist settings, then **Jetzt synchronisieren**.
   With **Trockenlauf** checked (the default), the sync only logs what it
   would do. Uncheck it and click again to actually write.

The first sync against an unsynced controller can take several minutes
because of the controller's session-budget pacing (the `Pause zwischen
Calls` setting).

## CSV format

```
Chip,Kartennummer
1,0357097919
2,0357107233
…
Armband1,0334009169
Armband2,0334114990
```

Chip values can be numeric or `Armband{N}` (or anything else opaque) —
the value in jverein's `transponder` zusatzfeld must match the `Chip`
column.

## Building

```bash
ant -buildfile build/build.xml build-dependencies   # one-time: download prebuilt jameica + jverein
ant -buildfile build/build.xml nightly              # build releases/nightly/jverein.hikvision-…-nightly.zip
```

Targets jameica 2.12 + jverein 4.1.5 (pinned in `build/build.properties`).
The plugin compiles directly against the prebuilt distribution JARs from
each upstream — no transitive source build needed.

## Hikvision ISAPI gotchas (worth knowing if you debug)

- The controller invalidates the HTTP digest nonce after every request.
  This plugin does a fresh challenge-response per call (`HikvisionClient`);
  any client that reuses a digest session (e.g. Python's
  `requests.Session() + HTTPDigestAuth`) will fail on the second call with
  a wrapped `<userCheck><statusValue>401</statusValue>…</userCheck>` body.
- `UserInfo.employeeNo` is the primary key and cannot be modified in
  place. Renames are done create-first / move card / delete-old.
- Create endpoints want **a single object**, not an array:
  `{"UserInfo": {…}}`, not `{"UserInfo": [{…}]}`.
- Groups **can** be listed via ISAPI (despite older notes to the contrary):
  - org user groups: `POST /ISAPI/AccessControl/UserGroupMgr/SearchUserGroup`
    → `matchResults[].nodeID/nodeName/userNum`
  - door permission groups (Berechtigungsgruppen):
    `POST /ISAPI/AccessControl/DoorRegionMgr/SearchRegionPermissionGroup`
    → `matchResults[].regionPermissionGroupID/regionPermissionGroupName/doorIDList`
  Both search bodies are **1-based** (`searchResultPosition` ≥ 1) and cap
  `maxResults` at 33.
- Door access is granted by the user's `regionPermissionGroupIDList`
  (a list — a member may hold several), **not** by the org `userGroupNode`.
  The org userGroup is just where the user lives in the tree.

## Status

`0.1.0` — handles the bootstrap (create / delete / card-diff) cleanly.
Does **not** yet handle in-place property changes (e.g. a member who
changes from regular to sponsor — `userType` change). That requires the
delete-and-recreate pattern, which is on the roadmap but for now those
cases need a manual rename on the controller side.

## License

GPLv3 — see `build/COPYING`.
