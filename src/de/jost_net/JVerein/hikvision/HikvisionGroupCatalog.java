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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;

/**
 * Hikvision-side group inventory. Holds the two concepts the controller
 * keeps separate:
 *
 *  - {@link Group} — organisational user groups ({@code userGroupNodeID}):
 *    BSV / Vorstand / Mitglieder / Robby Bubble. Every user belongs to
 *    exactly one. Source: {@code UserGroupMgr/SearchUserGroup}.
 *  - {@link RegionPermissionGroup} — door-access permission groups
 *    (Berechtigungsgruppen, {@code regionPermissionGroupIDList}): the thing
 *    that actually grants access to doors. A user may hold several. Source:
 *    {@code DoorRegionMgr/SearchRegionPermissionGroup}.
 *
 * Materialise via {@link #refreshFromHikvision} (authoritative — pulls both
 * lists straight from the controller, including groups with no members) or
 * {@link #fromPlan} (fallback — derives the userGroups seen on existing
 * users from a computed plan; no region data).
 *
 * Persisted to {@code cfg/HikvisionGroups.json} — independent of the larger
 * {@code PlanCache.json}.
 */
public class HikvisionGroupCatalog
{
  /** Organisational user group ({@code userGroupNodeID}). */
  public static class Group
  {
    public final String uuid;
    public final String name;
    public int memberCount;
    Group(String uuid, String name) { this.uuid = uuid; this.name = name; }
    @Override public String toString() { return name + " (" + memberCount + ")"; }
  }

  /** Door-access permission group ({@code regionPermissionGroupIDList}). */
  public static class RegionPermissionGroup
  {
    public final int id;
    public final String name;
    public int memberCount;       // userNum — individually-assigned users
    public int userGroupCount;    // userGroupNum — whole userGroups assigned
    public final List<String> doors = new ArrayList<>();   // region node names, for display
    RegionPermissionGroup(int id, String name) { this.id = id; this.name = name; }
    public String displayName() { return (name == null || name.isEmpty()) ? ("Gruppe " + id) : name; }
    @Override public String toString()
    {
      String d = doors.isEmpty() ? "" : "  [" + String.join(", ", doors) + "]";
      return displayName() + d;
    }
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
   *  the catalog cache isn't there yet (older versions or never-fetched). */
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
          RegionPermissionGroup rr = new RegionPermissionGroup(r.optInt("id"), r.optString("name"));
          rr.memberCount = r.optInt("memberCount", 0);
          rr.userGroupCount = r.optInt("userGroupCount", 0);
          JSONArray dn = r.optJSONArray("doors");
          if (dn != null) for (int j = 0; j < dn.length(); j++) rr.doors.add(dn.optString(j, ""));
          c.regions.add(rr);
        }
      }
      catch (Exception e)
      { Logger.error("HikvisionGroups.json kaputt — fallback auf PlanCache: " + e.getMessage(), e); c = null; }
    }
    if (c == null)
    {
      // fallback to deriving userGroups from the PlanCache (no region data)
      PlanCache.Cached cached = PlanCache.load();
      if (cached == null || cached.plan == null) c = new HikvisionGroupCatalog(0);
      else c = fromPlan(cached.plan, cached.timestamp);
    }
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
                               .put("name", r.name == null ? "" : r.name)
                               .put("memberCount", r.memberCount)
                               .put("userGroupCount", r.userGroupCount)
                               .put("doors", new JSONArray(r.doors)));
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

  /** Populate the organisational user groups from {@code matchResults} of
   *  {@code UserGroupMgr/SearchUserGroup} (nodeID / nodeName / userNum). */
  private static void addUserGroups(HikvisionGroupCatalog c, JSONArray matchResults)
  {
    if (matchResults == null) return;
    for (int i = 0; i < matchResults.length(); i++)
    {
      JSONObject g = matchResults.getJSONObject(i);
      String uuid = g.optString("nodeID", "");
      if (uuid.isEmpty()) continue;
      Group gg = new Group(uuid, g.optString("nodeName", ""));
      gg.memberCount = g.optInt("userNum", 0);
      c.groups.add(gg);
    }
    c.groups.sort(Comparator.comparing((Group g) -> g.name == null ? "" : g.name));
  }

  /** Populate the region-permission groups from {@code matchResults} of
   *  {@code DoorRegionMgr/SearchRegionPermissionGroup}. */
  private static void addRegionPermissionGroups(HikvisionGroupCatalog c, JSONArray matchResults)
  {
    if (matchResults == null) return;
    for (int i = 0; i < matchResults.length(); i++)
    {
      JSONObject r = matchResults.getJSONObject(i);
      int id = r.optInt("regionPermissionGroupID", -1);
      if (id < 0) continue;
      RegionPermissionGroup rr = new RegionPermissionGroup(id, r.optString("regionPermissionGroupName", ""));
      rr.memberCount = r.optInt("userNum", 0);
      rr.userGroupCount = r.optInt("userGroupNum", 0);
      JSONArray dl = r.optJSONArray("doorIDList");
      if (dl != null) for (int j = 0; j < dl.length(); j++)
      {
        String dn = dl.getJSONObject(j).optString("regionNodeName", "");
        if (!dn.isEmpty() && !rr.doors.contains(dn)) rr.doors.add(dn);
      }
      c.regions.add(rr);
    }
    c.regions.sort(Comparator.comparing((RegionPermissionGroup r) -> r.name == null ? "" : r.name));
  }

  /** Build a catalog holding only the org user groups (no region data).
   *  Used by callers that only need the userGroup name→UUID mapping. */
  public static HikvisionGroupCatalog fromUserGroups(JSONArray userGroups, long timestamp)
  {
    HikvisionGroupCatalog c = new HikvisionGroupCatalog(timestamp);
    addUserGroups(c, userGroups);
    return c;
  }

  /** Build a catalog from both controller lists. */
  public static HikvisionGroupCatalog fromControllerLists(JSONArray userGroups, JSONArray regionGroups, long timestamp)
  {
    HikvisionGroupCatalog c = new HikvisionGroupCatalog(timestamp);
    addUserGroups(c, userGroups);
    addRegionPermissionGroups(c, regionGroups);
    return c;
  }

  /** Derive a userGroup catalog from an already-computed Plan (fallback
   *  only — no region data; only groups that exist on a user appear). */
  public static HikvisionGroupCatalog fromPlan(SyncEngine.Plan plan, long timestamp)
  {
    HikvisionGroupCatalog c = new HikvisionGroupCatalog(timestamp);
    Map<String, Group> byUuid = new LinkedHashMap<>();
    for (SyncEngine.PlanRow r : plan.rows)
    {
      boolean fromHikvision = r.status == SyncEngine.Status.OK
                           || r.status == SyncEngine.Status.UPDATE
                           || r.status == SyncEngine.Status.DISABLE
                           || r.status == SyncEngine.Status.REACTIVATE
                           || r.status == SyncEngine.Status.DELETE
                           || r.status == SyncEngine.Status.INCOMPLETE
                           || r.status == SyncEngine.Status.HIK_ONLY;
      if (!fromHikvision) continue;
      if (r.groupId != null && !r.groupId.isEmpty())
        byUuid.computeIfAbsent(r.groupId, k -> new Group(k, safe(r.groupName))).memberCount++;
    }
    c.groups.addAll(byUuid.values());
    c.groups.sort(Comparator.comparing((Group g) -> g.name == null ? "" : g.name));
    return c;
  }

  /** Authoritative fetch of both group lists straight from the controller.
   *  No CardInfo, no jverein DB scan, no PlanCache write. */
  public static HikvisionGroupCatalog refreshFromHikvision(HikvisionClient client, ProgressListener pl) throws IOException
  {
    if (pl != null) pl.progress(0, 0, "Organisationsgruppen abrufen");
    JSONArray userGroups = client.listUserGroups();
    if (pl != null) pl.progress(0, 0, "Berechtigungsgruppen abrufen");
    JSONArray regionGroups = client.listRegionPermissionGroups();
    HikvisionGroupCatalog c = fromControllerLists(userGroups, regionGroups, System.currentTimeMillis());
    save(c);
    return c;
  }

  private static String safe(String s) { return s == null ? "" : s; }
}
