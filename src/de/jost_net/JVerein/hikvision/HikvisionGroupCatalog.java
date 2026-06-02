package de.jost_net.JVerein.hikvision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the Hikvision-side group + region-permission inventory from
 * the cached {@link PlanCache}. No new Hikvision call — the data was
 * already pulled when {@link SyncEngine#computePlan} ran.
 *
 * Two indices:
 *  - Organisationsgruppen: distinct (userGroupNodeID, userGroupNodeName)
 *    with member count.
 *  - Türrechte / Region-Permission-Groups: distinct integer ids with
 *    member count. (Hikvision DS-K firmware doesn't expose a name lookup
 *    for these via ISAPI — use the controller web UI for naming.)
 *
 * Members of multi-region users count toward each region they belong to.
 * Unmanaged Hikvision entries (SKM* etc.) are included — they're real
 * users on the controller too.
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
    public int memberCount;
    RegionPermissionGroup(int id) { this.id = id; }
    @Override public String toString() { return "Region " + id + " (" + memberCount + " Benutzer)"; }
  }

  public final List<Group> groups = new ArrayList<>();
  public final List<RegionPermissionGroup> regions = new ArrayList<>();
  public final long timestamp;

  private HikvisionGroupCatalog(long timestamp) { this.timestamp = timestamp; }

  public static HikvisionGroupCatalog fromCache()
  {
    PlanCache.Cached cached = PlanCache.load();
    if (cached == null || cached.plan == null) return new HikvisionGroupCatalog(0);
    return fromPlan(cached.plan, cached.timestamp);
  }

  public static HikvisionGroupCatalog fromPlan(SyncEngine.Plan plan, long timestamp)
  {
    HikvisionGroupCatalog c = new HikvisionGroupCatalog(timestamp);
    Map<String, Group> byUuid = new LinkedHashMap<>();
    Map<Integer, RegionPermissionGroup> byRegion = new HashMap<>();
    for (SyncEngine.PlanRow r : plan.rows)
    {
      // Only count rows that came from Hikvision (currentCards populated OR an existing-side
      // status). Pure CREATE rows describe what jverein *wants* — they don't reflect
      // controller state and would skew the counts.
      boolean fromHikvision = r.status == SyncEngine.Status.OK
                           || r.status == SyncEngine.Status.UPDATE
                           || r.status == SyncEngine.Status.DELETE
                           || r.status == SyncEngine.Status.HIK_ONLY;
      if (!fromHikvision) continue;
      if (r.groupId != null && !r.groupId.isEmpty())
      {
        Group g = byUuid.computeIfAbsent(r.groupId, k -> new Group(k, safe(r.groupName)));
        g.memberCount++;
      }
      for (Integer rp : r.regionPermissionGroups)
      {
        if (rp == null) continue;
        RegionPermissionGroup rg = byRegion.computeIfAbsent(rp, RegionPermissionGroup::new);
        rg.memberCount++;
      }
    }
    c.groups.addAll(byUuid.values());
    c.groups.sort(Comparator.comparing((Group g) -> g.name == null ? "" : g.name));
    c.regions.addAll(byRegion.values());
    c.regions.sort(Comparator.comparingInt((RegionPermissionGroup g) -> g.id));
    return c;
  }

  private static String safe(String s) { return s == null ? "" : s; }
}
