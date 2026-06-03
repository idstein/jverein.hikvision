package de.jost_net.JVerein.hikvision;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;

/**
 * Hikvision-side group + region-permission inventory.
 *
 * Two ways to materialise:
 *  - {@link #refreshFromHikvision} — lightweight: pulls only UserInfo
 *    (no CardInfo, no jverein DB scan, no PlanCache side effects).
 *    Used by the Settings tab to populate the group / region dropdowns.
 *  - {@link #fromPlan} — derives from an already-computed
 *    {@link SyncEngine.Plan}. Called by computePlan as a side effect so
 *    the Benutzer-side refresh also updates the catalog.
 *
 * Persisted to its own small file ({@code cfg/HikvisionGroups.json}) —
 * independent of the larger {@code PlanCache.json} so Settings refreshes
 * don't invalidate the Benutzer view's plan and vice versa.
 */
public class HikvisionGroupCatalog
{
  public static class Group
  {
    public final String uuid;
    public final String name;
    public int memberCount;
    Group(String uuid, String name) { this.uuid = uuid; this.name = name; }
    @Override public String toString() { return name + " (" + memberCount + ")"; }
  }

  public static class RegionPermissionGroup
  {
    public final int id;
    public String name = "";   // looked up from UserRightPlanTemplate when available
    public int memberCount;
    RegionPermissionGroup(int id) { this.id = id; }
    public String displayName()
    { return (name == null || name.isEmpty()) ? ("Region " + id) : name; }
    @Override public String toString() { return displayName() + " (" + memberCount + " Benutzer)"; }
  }

  public final List<Group> groups = new ArrayList<>();
  public final List<RegionPermissionGroup> regions = new ArrayList<>();
  public final long timestamp;

  private HikvisionGroupCatalog(long timestamp) { this.timestamp = timestamp; }

  // ============================================================ cache file

  private static File cacheFile()
  {
    String workDir = Application.getPluginLoader().getPlugin(Plugin.class).getResources().getWorkPath();
    return new File(workDir, "HikvisionGroups.json");
  }

  /** Loads from the dedicated catalog cache; falls back to the PlanCache if
   *  the catalog cache isn't there yet (older versions or never-fetched).
   *  Always overlays user-configured region names from HikvisionSettings. */
  public static HikvisionGroupCatalog fromCache()
  {
    File f = cacheFile();
    HikvisionGroupCatalog c = null;
    if (f.exists())
    {
      try
      {
        String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(raw);
        c = new HikvisionGroupCatalog(root.optLong("timestamp", 0));
        JSONArray gs = root.optJSONArray("groups");
        if (gs != null) for (int i = 0; i < gs.length(); i++)
        {
          JSONObject g = gs.getJSONObject(i);
          Group gg = new Group(g.optString("uuid"), g.optString("name"));
          gg.memberCount = g.optInt("memberCount", 0);
          c.groups.add(gg);
        }
        JSONArray rs = root.optJSONArray("regions");
        if (rs != null) for (int i = 0; i < rs.length(); i++)
        {
          JSONObject r = rs.getJSONObject(i);
          RegionPermissionGroup rr = new RegionPermissionGroup(r.optInt("id"));
          rr.memberCount = r.optInt("memberCount", 0);
          rr.name = r.optString("name", "");
          c.regions.add(rr);
        }
      }
      catch (Exception e)
      { Logger.error("HikvisionGroups.json kaputt — fallback auf PlanCache: " + e.getMessage(), e); c = null; }
    }
    if (c == null)
    {
      // fallback to deriving from the PlanCache (legacy / no dedicated catalog yet)
      PlanCache.Cached cached = PlanCache.load();
      if (cached == null || cached.plan == null) c = new HikvisionGroupCatalog(0);
      else c = fromPlan(cached.plan, cached.timestamp);
    }
    annotateRegionNames(c);   // always overlay user-configured names
    return c;
  }

  public static void save(HikvisionGroupCatalog c)
  {
    File f = cacheFile();
    try
    {
      JSONArray gs = new JSONArray();
      for (Group g : c.groups)
        gs.put(new JSONObject().put("uuid", g.uuid == null ? "" : g.uuid)
                               .put("name", g.name == null ? "" : g.name)
                               .put("memberCount", g.memberCount));
      JSONArray rs = new JSONArray();
      for (RegionPermissionGroup r : c.regions)
        rs.put(new JSONObject().put("id", r.id)
                               .put("memberCount", r.memberCount)
                               .put("name", r.name == null ? "" : r.name));
      JSONObject root = new JSONObject().put("timestamp", c.timestamp)
                                        .put("groups", gs).put("regions", rs);
      File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
      try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp, StandardCharsets.UTF_8)))
      { w.write(root.toString(2)); }
      Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    catch (Exception e) { Logger.error("HikvisionGroupCatalog.save failed: " + e.getMessage(), e); }
  }

  // ============================================================ aggregators

  /** Build catalog from a raw UserInfo JSON array — no CardInfo, no PlanRow. */
  public static HikvisionGroupCatalog fromUsers(JSONArray users, long timestamp)
  {
    HikvisionGroupCatalog c = new HikvisionGroupCatalog(timestamp);
    Map<String, Group> byUuid = new LinkedHashMap<>();
    Map<Integer, RegionPermissionGroup> byRegion = new HashMap<>();
    for (int i = 0; i < users.length(); i++)
    {
      JSONObject u = users.getJSONObject(i);
      String uuid = u.optString("userGroupNodeID", "");
      String name = u.optString("userGroupNodeName", "");
      if (!uuid.isEmpty())
      {
        Group g = byUuid.computeIfAbsent(uuid, k -> new Group(k, name));
        g.memberCount++;
      }
      JSONArray rp = u.optJSONArray("regionPermissionGroupIDList");
      if (rp != null)
        for (int j = 0; j < rp.length(); j++)
          byRegion.computeIfAbsent(rp.optInt(j), RegionPermissionGroup::new).memberCount++;
    }
    c.groups.addAll(byUuid.values());
    c.groups.sort(Comparator.comparing((Group g) -> g.name == null ? "" : g.name));
    c.regions.addAll(byRegion.values());
    c.regions.sort(Comparator.comparingInt(g -> g.id));
    return c;
  }

  /** Derive catalog from an already-computed Plan (the heavier path —
   *  used as a side effect of {@link SyncEngine#computePlan}). */
  public static HikvisionGroupCatalog fromPlan(SyncEngine.Plan plan, long timestamp)
  {
    HikvisionGroupCatalog c = new HikvisionGroupCatalog(timestamp);
    Map<String, Group> byUuid = new LinkedHashMap<>();
    Map<Integer, RegionPermissionGroup> byRegion = new HashMap<>();
    for (SyncEngine.PlanRow r : plan.rows)
    {
      boolean fromHikvision = r.status == SyncEngine.Status.OK
                           || r.status == SyncEngine.Status.UPDATE
                           || r.status == SyncEngine.Status.DELETE
                           || r.status == SyncEngine.Status.HIK_ONLY;
      if (!fromHikvision) continue;
      if (r.groupId != null && !r.groupId.isEmpty())
        byUuid.computeIfAbsent(r.groupId, k -> new Group(k, safe(r.groupName))).memberCount++;
      for (Integer rp : r.regionPermissionGroups)
      {
        if (rp == null) continue;
        byRegion.computeIfAbsent(rp, RegionPermissionGroup::new).memberCount++;
      }
    }
    c.groups.addAll(byUuid.values());
    c.groups.sort(Comparator.comparing((Group g) -> g.name == null ? "" : g.name));
    c.regions.addAll(byRegion.values());
    c.regions.sort(Comparator.comparingInt((RegionPermissionGroup g) -> g.id));
    return c;
  }

  /** Lightweight Settings-side fetch: UserInfo only. No CardInfo, no
   *  jverein DB scan, no PlanCache write. Region-permission names are
   *  pulled from HikvisionSettings (user-configured in Türrechte). */
  public static HikvisionGroupCatalog refreshFromHikvision(HikvisionClient client, ProgressListener pl) throws IOException
  {
    JSONArray users = client.listAllUsers(pl);
    HikvisionGroupCatalog c = fromUsers(users, System.currentTimeMillis());
    annotateRegionNames(c);
    save(c);
    return c;
  }

  /** Apply user-configured region names (from HikvisionSettings) to the
   *  catalog's regions. Hikvision DS-K firmware doesn't expose Permission
   *  Group display names via ISAPI — the names only live in the
   *  controller's web UI — so the only reliable source is what the user
   *  set locally via the Türrechte view's inline editor. */
  public static void annotateRegionNames(HikvisionGroupCatalog c)
  {
    for (RegionPermissionGroup r : c.regions)
    {
      String n = HikvisionSettings.getRegionName(r.id);
      if (n != null && !n.isEmpty()) r.name = n;
    }
  }

  private static String safe(String s) { return s == null ? "" : s; }
}
