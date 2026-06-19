package de.jost_net.JVerein.hikvision;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import de.jost_net.JVerein.Einstellungen;
import de.jost_net.JVerein.rmi.Felddefinition;
import de.jost_net.JVerein.rmi.Mitglied;
import de.jost_net.JVerein.rmi.Zusatzfelder;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;

/**
 * Plugin-owned per-Mitglied store — the <b>source of truth</b> for what the
 * sync pushes to Hikvision. Holds, per jverein Mitglied:
 *
 *  - transponder chip ids;
 *  - the org userGroup ({@code Mitglieder} / {@code Vorstand} / {@code BSV} /
 *    {@code Robby Bubble}) — see {@link Assignment#groupManaged};
 *  - the door Berechtigungsgruppen ({@code regionPermissionGroups}) written
 *    to each user's {@code regionPermissionGroupIDList} on sync.
 *
 * Backed by {@code cfg/MitgliedAssignments.json}, a JSON array of objects
 * keyed by {@code jvId} (the jverein Mitglied primary key). Edited via the
 * Benutzer view's assignment dialog; changes reach the controller only when
 * a sync is run. {@link #syncAllToZusatzfeld} mirrors the transponder list
 * back into the jverein Zusatzfeld for visibility (one direction only).
 */
public class MitgliedAssignments
{
  public static class Assignment
  {
    public final String jvId;
    public String externe = "";
    public String employeeNo = "";
    public final List<String> transponder = new ArrayList<>();
    /** Org userGroup chosen for this member. Only authoritative when
     *  {@link #groupManaged} is true (explicitly set via the dialog);
     *  otherwise the sync engine derives the desired group automatically
     *  (sponsors → BSV, members in the guest group → Mitglieder, members
     *  already in a member group → unchanged). */
    public String hikvisionGroup = "";
    /** True once the user explicitly picked an Org-Gruppe in the dialog.
     *  Legacy/migrated entries are false → governed by the auto rule, so a
     *  stale stored group never produces a spurious "move" on sync. */
    public boolean groupManaged = false;
    /** Door-access Berechtigungsgruppen (region-permission group NAMES) this
     *  member should hold. Resolved to controller ids at sync time and
     *  written to the user's regionPermissionGroupIDList. Default empty (no
     *  individually-granted door access). */
    public final List<String> regionPermissionGroups = new ArrayList<>();
    /** Epoch millis of last in-store mutation. 0 = legacy entry without
     *  recorded timestamp. Used by incremental refresh to scope its
     *  Hikvision fetch to just the recently-edited assignments. */
    public long modifiedAt = 0L;

    public Assignment(String jvId) { this.jvId = jvId; }

    public boolean isComplete()
    { return !transponder.isEmpty(); }
  }

  private final File backing;
  private final LinkedHashMap<String, Assignment> byJvId = new LinkedHashMap<>();

  /** Sidecar meta — last successful full refresh + the totals the
   *  controller reported then. Kept in a separate file so the assignments
   *  JSON stays a plain array (forward-compatible with existing readers). */
  private long lastFullRefresh = 0L;
  private int lastFullUserTotal = -1;
  private int lastFullCardTotal = -1;

  private MitgliedAssignments(File backing) { this.backing = backing; }

  public static File defaultFile()
  {
    String workDir = Application.getPluginLoader().getPlugin(Plugin.class).getResources().getWorkPath();
    return new File(workDir, "MitgliedAssignments.json");
  }

  private static File metaFileFor(File backing)
  { return new File(backing.getParentFile(), "MitgliedAssignments.meta.json"); }

  public static MitgliedAssignments load() throws IOException
  {
    MitgliedAssignments s = new MitgliedAssignments(defaultFile());
    s.reload();
    s.loadMeta();
    return s;
  }

  public synchronized void reload() throws IOException
  {
    byJvId.clear();
    if (!backing.exists()) return;
    String raw = new String(Files.readAllBytes(backing.toPath()), StandardCharsets.UTF_8).trim();
    if (raw.isEmpty()) return;
    JSONArray arr = new JSONArray(raw);
    for (int i = 0; i < arr.length(); i++)
    {
      JSONObject o = arr.getJSONObject(i);
      String jvId = o.optString("jvId", "").trim();
      if (jvId.isEmpty()) continue;
      Assignment a = new Assignment(jvId);
      a.externe = o.optString("externe", "");
      a.employeeNo = o.optString("employeeNo", "");
      a.hikvisionGroup = o.optString("hikvisionGroup", "");
      a.groupManaged = o.optBoolean("groupManaged", false);
      a.modifiedAt = o.optLong("modifiedAt", 0L);
      JSONArray tr = o.optJSONArray("transponder");
      if (tr != null) for (int j = 0; j < tr.length(); j++)
      {
        String t = tr.optString(j, "").trim();
        if (!t.isEmpty()) a.transponder.add(t);
      }
      JSONArray rg = o.optJSONArray("regionPermissionGroups");
      if (rg != null) for (int j = 0; j < rg.length(); j++)
      {
        String t = rg.optString(j, "").trim();
        if (!t.isEmpty() && !a.regionPermissionGroups.contains(t)) a.regionPermissionGroups.add(t);
      }
      byJvId.put(jvId, a);
    }
    Logger.info("MitgliedAssignments loaded " + byJvId.size() + " entries from " + backing);
  }

  public synchronized void save() throws IOException
  {
    JSONArray arr = new JSONArray();
    for (Assignment a : byJvId.values())
    {
      JSONObject o = new JSONObject();
      o.put("jvId", a.jvId);
      o.put("externe", a.externe == null ? "" : a.externe);
      o.put("employeeNo", a.employeeNo == null ? "" : a.employeeNo);
      o.put("hikvisionGroup", a.hikvisionGroup == null ? "" : a.hikvisionGroup);
      if (a.groupManaged) o.put("groupManaged", true);
      o.put("transponder", new JSONArray(a.transponder));
      o.put("regionPermissionGroups", new JSONArray(a.regionPermissionGroups));
      if (a.modifiedAt > 0L) o.put("modifiedAt", a.modifiedAt);
      arr.put(o);
    }
    if (backing.getParentFile() != null) backing.getParentFile().mkdirs();
    File tmp = new File(backing.getParentFile(), backing.getName() + ".tmp");
    try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp, StandardCharsets.UTF_8)))
    { w.write(arr.toString(2)); }
    Files.move(tmp.toPath(), backing.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  public synchronized Assignment get(String jvId) { return byJvId.get(jvId); }
  public synchronized void put(Assignment a) { a.modifiedAt = System.currentTimeMillis(); byJvId.put(a.jvId, a); }
  /** Mark an existing Assignment as just-mutated. Required when callers
   *  modify {@code transponder}/{@code hikvisionGroup}/etc. in-place
   *  without going through {@link #put} (in-place edits and conflict
   *  resolution in AssignmentEditDialog). */
  public synchronized boolean touch(String jvId)
  {
    Assignment a = byJvId.get(jvId);
    if (a == null) return false;
    a.modifiedAt = System.currentTimeMillis();
    return true;
  }
  public synchronized boolean remove(String jvId) { return byJvId.remove(jvId) != null; }
  public synchronized int size() { return byJvId.size(); }
  public synchronized Collection<Assignment> all() { return new ArrayList<>(byJvId.values()); }

  // ---------------------------------------------------------------- meta

  public synchronized long getLastFullRefresh()  { return lastFullRefresh; }
  public synchronized int  getLastFullUserTotal() { return lastFullUserTotal; }
  public synchronized int  getLastFullCardTotal() { return lastFullCardTotal; }

  /** Record a successful full refresh. Persists to the sidecar meta file. */
  public synchronized void recordFullRefresh(int userTotal, int cardTotal) throws IOException
  {
    lastFullRefresh = System.currentTimeMillis();
    lastFullUserTotal = userTotal;
    lastFullCardTotal = cardTotal;
    saveMeta();
  }

  private synchronized void loadMeta()
  {
    File mf = metaFileFor(backing);
    if (!mf.exists()) return;
    try
    {
      String raw = new String(Files.readAllBytes(mf.toPath()), StandardCharsets.UTF_8).trim();
      if (raw.isEmpty()) return;
      JSONObject o = new JSONObject(raw);
      lastFullRefresh   = o.optLong("lastFullRefresh", 0L);
      lastFullUserTotal = o.optInt("lastFullUserTotal", -1);
      lastFullCardTotal = o.optInt("lastFullCardTotal", -1);
    }
    catch (Exception e)
    { Logger.warn("MitgliedAssignments.meta.json unlesbar — verworfen: " + e.getMessage()); }
  }

  private synchronized void saveMeta() throws IOException
  {
    JSONObject o = new JSONObject();
    o.put("lastFullRefresh", lastFullRefresh);
    o.put("lastFullUserTotal", lastFullUserTotal);
    o.put("lastFullCardTotal", lastFullCardTotal);
    File mf = metaFileFor(backing);
    if (mf.getParentFile() != null) mf.getParentFile().mkdirs();
    File tmp = new File(mf.getParentFile(), mf.getName() + ".tmp");
    try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp, StandardCharsets.UTF_8)))
    { w.write(o.toString(2)); }
    Files.move(tmp.toPath(), mf.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Reverse index: chip id → jvIds it's assigned to (usually one, but the
   *  schema doesn't forbid multiple — collisions surface in the UI). */
  public synchronized Map<String, List<String>> chipToJvIds()
  {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (Assignment a : byJvId.values())
      for (String chip : a.transponder)
        out.computeIfAbsent(chip, k -> new ArrayList<>()).add(a.jvId);
    return out;
  }

  // =====================================================================
  // Mirror write-back: plugin store → jverein transponder Zusatzfeld
  // =====================================================================
  //
  // The plugin store is the source of truth for sync, but mirroring back
  // into the Zusatzfeld keeps jverein's Mitglied detail view in sync —
  // users still see the chip list where they expect it.

  /** Write the chip list of a single Mitglied back into the Zusatzfeld.
   *  Idempotent: if the field already matches, nothing happens. Returns
   *  true if a write occurred. Silently no-ops if the Felddefinition
   *  doesn't exist (so the plugin still works in installs without it). */
  public boolean writeAssignmentToZusatzfeld(String jvId) throws Exception
  {
    Felddefinition def = findTransponderDef();
    if (def == null) return false;
    Assignment a;
    synchronized (this) { a = byJvId.get(jvId); }
    String csv = a == null ? "" : String.join(",", a.transponder);
    return writeZusatzfeldRow(jvId, def, csv);
  }

  /** Bulk write-back: for every Mitglied in the store, write its chip CSV
   *  to the Zusatzfeld. Also clears the Zusatzfeld for Mitglieder NOT in
   *  the store (so a removed assignment removes the jverein-visible value
   *  too). Returns count of rows written. */
  public int syncAllToZusatzfeld(ProgressListener pl) throws Exception
  {
    Felddefinition def = findTransponderDef();
    if (def == null)
    {
      if (pl != null) pl.log("WARN: Felddefinition '" + HikvisionSettings.getZusatzfeldName()
          + "' nicht gefunden — Rückschreiben übersprungen.");
      return 0;
    }
    Map<String, String> wantByJvId;
    synchronized (this)
    {
      wantByJvId = new HashMap<>();
      for (Assignment a : byJvId.values())
        wantByJvId.put(a.jvId, String.join(",", a.transponder));
    }
    int wrote = 0, seen = 0;
    DBIterator<Mitglied> it = Einstellungen.getDBService().createList(Mitglied.class);
    while (it.hasNext())
    {
      Mitglied m = (Mitglied) it.next();
      seen++;
      String want = wantByJvId.getOrDefault(m.getID(), "");
      String have = readZusatzfeld(m.getID(), def.getID());
      if (have == null) have = "";
      if (want.equals(have)) continue;
      if (writeZusatzfeldRow(m.getID(), def, want)) wrote++;
      if (pl != null && seen % 100 == 0) pl.progress(seen, 0, "Rückschreiben");
    }
    if (pl != null) pl.log("Rückschreiben: " + wrote + " jverein-Zusatzfelder aktualisiert (von " + seen + " geprüft).");
    return wrote;
  }

  /** Locate the configured transponder Felddefinition. Null if missing. */
  private static Felddefinition findTransponderDef() throws Exception
  {
    DBIterator<Felddefinition> defs = Einstellungen.getDBService().createList(Felddefinition.class);
    defs.addFilter("name = ?", HikvisionSettings.getZusatzfeldName());
    if (!defs.hasNext())
    {
      Logger.warn("Felddefinition '" + HikvisionSettings.getZusatzfeldName()
          + "' nicht gefunden — Rückschreiben in jverein-Zusatzfeld nicht möglich.");
      return null;
    }
    return (Felddefinition) defs.next();
  }

  /** Find-or-create the Zusatzfelder row for (mitglied, felddefinition)
   *  and set the value. Returns true if a write happened. */
  private static boolean writeZusatzfeldRow(String mitgliedId, Felddefinition def, String value) throws Exception
  {
    DBIterator<Zusatzfelder> it = Einstellungen.getDBService().createList(Zusatzfelder.class);
    it.addFilter("mitglied = ?", mitgliedId);
    it.addFilter("felddefinition = ?", def.getID());
    Zusatzfelder z;
    if (it.hasNext()) z = (Zusatzfelder) it.next();
    else
    {
      if (value == null || value.isEmpty()) return false;   // nothing to write, no row to create
      z = (Zusatzfelder) Einstellungen.getDBService().createObject(Zusatzfelder.class, null);
      z.setMitglied(Integer.parseInt(mitgliedId));
      z.setFelddefinition(Integer.parseInt(def.getID()));
    }
    z.setFeld(value == null || value.isEmpty() ? null : value);
    z.store();
    return true;
  }

  private static String readZusatzfeld(String mitgliedId, String felddefinitionId) throws Exception
  {
    DBIterator<Zusatzfelder> it = Einstellungen.getDBService().createList(Zusatzfelder.class);
    it.addFilter("mitglied = ?", mitgliedId);
    it.addFilter("felddefinition = ?", felddefinitionId);
    if (!it.hasNext()) return null;
    Zusatzfelder z = (Zusatzfelder) it.next();
    return z.getFeld();
  }
}
