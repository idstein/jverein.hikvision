package de.jost_net.JVerein.hikvision;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;

import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;

/**
 * Persists the last computed {@link SyncEngine.Plan} to
 * {@code ~/.jameica/{plugin work-dir}/PlanCache.json} so the
 * "Hikvision Benutzer" tab can render the previously-fetched state
 * immediately on open — without hitting the controller. Hikvision is
 * only queried when the user explicitly clicks Aktualisieren or runs
 * a sync.
 *
 * Schema is deliberately permissive (uses optString / optInt with
 * defaults) so adding fields to PlanRow in future versions is safe
 * without a migration step.
 */
public final class PlanCache
{
  private PlanCache() {}

  public static File defaultFile()
  {
    String workDir = Application.getPluginLoader().getPlugin(Plugin.class).getResources().getWorkPath();
    return new File(workDir, "PlanCache.json");
  }

  public static class Cached
  {
    public long timestamp;
    public SyncEngine.Plan plan;
  }

  public static void save(SyncEngine.Plan plan)
  {
    File f = defaultFile();
    try
    {
      JSONArray rows = new JSONArray();
      for (SyncEngine.PlanRow r : plan.rows)
      {
        JSONObject o = new JSONObject();
        o.put("status", r.status == null ? "" : r.status.name());
        o.put("employeeNo", n(r.employeeNo));
        o.put("name", n(r.name));
        o.put("userType", n(r.userType));
        o.put("groupName", n(r.groupName));
        o.put("groupId", n(r.groupId));
        o.put("desiredGroupName", n(r.desiredGroupName));
        o.put("desiredGroupId", n(r.desiredGroupId));
        o.put("currentCards", new JSONArray(r.currentCards));
        o.put("desiredCards", new JSONArray(r.desiredCards));
        o.put("currentRegionIds", new JSONArray(r.currentRegionIds));
        o.put("desiredRegionIds", new JSONArray(r.desiredRegionIds));
        o.put("desiredRegionNames", new JSONArray(r.desiredRegionNames));
        o.put("currentEnabled", r.currentEnabled);
        o.put("desiredEnabled", r.desiredEnabled);
        o.put("currentValidEnd", r.currentValidEnd == null ? "" : r.currentValidEnd.getTime());
        o.put("desiredValidEnd", r.desiredValidEnd == null ? "" : r.desiredValidEnd.getTime());
        o.put("detail", n(r.detail));
        o.put("jvereinName", n(r.jvereinName));
        rows.put(o);
      }
      JSONObject root = new JSONObject();
      root.put("timestamp", System.currentTimeMillis());
      root.put("ok", plan.ok);
      root.put("create", plan.create);
      root.put("update", plan.update);
      root.put("disable", plan.disable);
      root.put("reactivate", plan.reactivate);
      root.put("delete", plan.delete);
      root.put("incomplete", plan.incomplete);
      root.put("hikOnly", plan.hikOnly);
      root.put("unknownCards", plan.unknownCards);
      root.put("membersSkipped", plan.membersSkipped);
      root.put("userTotal", plan.userTotal);
      root.put("cardTotal", plan.cardTotal);
      root.put("rows", rows);

      File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
      try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp, StandardCharsets.UTF_8)))
      { w.write(root.toString(2)); }
      Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    catch (Exception e)
    {
      Logger.error("PlanCache.save failed: " + e.getMessage(), e);
    }
  }

  /** Returns null if no cache file or it can't be parsed. */
  public static Cached load()
  {
    File f = defaultFile();
    if (!f.exists()) return null;
    try
    {
      String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
      JSONObject root = new JSONObject(raw);
      Cached c = new Cached();
      c.timestamp = root.optLong("timestamp", 0);
      c.plan = new SyncEngine.Plan();
      c.plan.ok = root.optInt("ok", 0);
      c.plan.create = root.optInt("create", 0);
      c.plan.update = root.optInt("update", 0);
      c.plan.disable = root.optInt("disable", 0);
      c.plan.reactivate = root.optInt("reactivate", 0);
      c.plan.delete = root.optInt("delete", 0);
      c.plan.incomplete = root.optInt("incomplete", 0);
      c.plan.hikOnly = root.optInt("hikOnly", 0);
      c.plan.unknownCards = root.optInt("unknownCards", 0);
      c.plan.membersSkipped = root.optInt("membersSkipped", 0);
      c.plan.userTotal = root.optInt("userTotal", -1);
      c.plan.cardTotal = root.optInt("cardTotal", -1);
      JSONArray rows = root.optJSONArray("rows");
      if (rows != null) for (int i = 0; i < rows.length(); i++)
      {
        JSONObject o = rows.getJSONObject(i);
        SyncEngine.PlanRow r = new SyncEngine.PlanRow();
        String st = o.optString("status", "");
        try { r.status = st.isEmpty() ? null : SyncEngine.Status.valueOf(st); }
        catch (IllegalArgumentException ignored) { r.status = null; }
        r.employeeNo = o.optString("employeeNo", "");
        r.name = o.optString("name", "");
        r.userType = o.optString("userType", "");
        r.groupName = o.optString("groupName", "");
        r.groupId = o.optString("groupId", "");
        r.desiredGroupName = o.optString("desiredGroupName", "");
        r.desiredGroupId = o.optString("desiredGroupId", "");
        r.detail = o.optString("detail", "");
        r.jvereinName = o.optString("jvereinName", "");
        r.currentCards = toList(o.optJSONArray("currentCards"));
        r.desiredCards = toList(o.optJSONArray("desiredCards"));
        r.currentRegionIds = toIntList(o.optJSONArray("currentRegionIds"));
        r.desiredRegionIds = toIntList(o.optJSONArray("desiredRegionIds"));
        r.desiredRegionNames = toList(o.optJSONArray("desiredRegionNames"));
        r.currentEnabled = o.optBoolean("currentEnabled", true);
        r.desiredEnabled = o.optBoolean("desiredEnabled", true);
        long cve = o.optLong("currentValidEnd", 0);
        if (cve > 0) r.currentValidEnd = new java.util.Date(cve);
        long dve = o.optLong("desiredValidEnd", 0);
        if (dve > 0) r.desiredValidEnd = new java.util.Date(dve);
        c.plan.rows.add(r);
      }
      return c;
    }
    catch (Exception e)
    {
      Logger.error("PlanCache.load failed (will start empty): " + e.getMessage(), e);
      return null;
    }
  }

  /** Mark the cache as stale (e.g. after a sync apply) by deleting it. */
  public static void invalidate()
  {
    File f = defaultFile();
    if (f.exists()) f.delete();
  }

  private static java.util.List<String> toList(JSONArray a)
  {
    java.util.List<String> out = new ArrayList<>();
    if (a == null) return out;
    for (int i = 0; i < a.length(); i++) out.add(a.optString(i, ""));
    return out;
  }

  private static java.util.List<Integer> toIntList(JSONArray a)
  {
    java.util.List<Integer> out = new ArrayList<>();
    if (a == null) return out;
    for (int i = 0; i < a.length(); i++) out.add(a.optInt(i));
    return out;
  }

  private static String n(String s) { return s == null ? "" : s; }
}
