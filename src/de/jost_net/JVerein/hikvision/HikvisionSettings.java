package de.jost_net.JVerein.hikvision;

import de.willuhn.jameica.security.Wallet;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;

/**
 * Plugin configuration. Plain values live in
 * {@code ~/.jameica/cfg/de.jost_net.JVerein.hikvision.Settings.properties};
 * the Hikvision password is stored in Jameica's Wallet (encrypted with the
 * master password) to avoid leaking it in cleartext on disk.
 */
public class HikvisionSettings
{
  public static final de.willuhn.jameica.system.Settings SETTINGS =
      Application.getPluginLoader().getPlugin(Plugin.class).getResources().getSettings();

  private static Wallet wallet = null;

  // -- connection --
  public static String getControllerUrl()
  {
    return SETTINGS.getString("controller.url", "https://192.168.178.95");
  }
  public static void setControllerUrl(String s) { SETTINGS.setAttribute("controller.url", s); }

  public static String getControllerUser()
  {
    return SETTINGS.getString("controller.user", "admin");
  }
  public static void setControllerUser(String s) { SETTINGS.setAttribute("controller.user", s); }

  public static String getControllerPassword()
  {
    try { return (String) getWallet().get("controller.password"); }
    catch (Exception e) { Logger.error("unable to read controller password from wallet", e); return ""; }
  }
  public static void setControllerPassword(String s)
  {
    try { getWallet().set("controller.password", s); }
    catch (Exception e) { Logger.error("unable to write controller password to wallet", e); }
  }

  // -- Hikvision group / type mapping --
  public static String getMemberGroupId()
  {
    return SETTINGS.getString("member.groupId", "4fac306e263e4d05a44c011748794e57");
  }
  public static void setMemberGroupId(String s) { SETTINGS.setAttribute("member.groupId", s); }

  public static String getMemberGroupName()
  {
    return SETTINGS.getString("member.groupName", "Mitglieder");
  }
  public static void setMemberGroupName(String s) { SETTINGS.setAttribute("member.groupName", s); }

  public static String getSponsorGroupId()
  {
    return SETTINGS.getString("sponsor.groupId", "defaultUserGroup");
  }
  public static void setSponsorGroupId(String s) { SETTINGS.setAttribute("sponsor.groupId", s); }

  public static String getSponsorGroupName()
  {
    return SETTINGS.getString("sponsor.groupName", "BSV");
  }
  public static void setSponsorGroupName(String s) { SETTINGS.setAttribute("sponsor.groupName", s); }

  // -- name of the transponder Zusatzfeld --
  public static String getZusatzfeldName()
  {
    return SETTINGS.getString("zusatzfeld.name", "transponder");
  }
  public static void setZusatzfeldName(String s) { SETTINGS.setAttribute("zusatzfeld.name", s); }

  // -- pacing --
  public static int getInterCallPauseMs()
  {
    return SETTINGS.getInt("controller.interCallPauseMs", 2000);
  }
  public static void setInterCallPauseMs(int n) { SETTINGS.setAttribute("controller.interCallPauseMs", n); }

  // -- resilience (retry + per-call deadline) --
  /** Max attempts per controller round-trip. 1 = try once, no retry. A
   *  transient 401 / dropped connection / timeout is retried up to this many
   *  times (with a backoff) before the call fails. Clamped to ≥1. */
  public static int getMaxAttempts()
  {
    return Math.max(1, SETTINGS.getInt("controller.maxAttempts", 4));
  }
  public static void setMaxAttempts(int n) { SETTINGS.setAttribute("controller.maxAttempts", Math.max(1, n)); }

  /** Hard ceiling (ms) for a single controller round-trip, enforced
   *  independently of the JDK request timeout so a wedged call can't hang the
   *  background-task slot forever. On expiry the call times out and is
   *  retried (subject to {@link #getMaxAttempts}). Clamped to ≥1000. */
  public static int getCallDeadlineMs()
  {
    return Math.max(1000, SETTINGS.getInt("controller.callDeadlineMs", 30000));
  }
  public static void setCallDeadlineMs(int n) { SETTINGS.setAttribute("controller.callDeadlineMs", Math.max(1000, n)); }

  // -- auth --
  /** Use the ISAPI session token (one login, then cookie-authed calls) instead
   *  of a per-call HTTP Digest 401 challenge. Falls back to digest automatically
   *  if the controller doesn't support it. Validated on DS-K2702WX fw V1.7.4. */
  public static boolean getUseSessionAuth() { return SETTINGS.getBoolean("controller.useSessionAuth", true); }
  public static void setUseSessionAuth(boolean b) { SETTINGS.setAttribute("controller.useSessionAuth", b); }

  // -- scheduled delta sync --
  /** Master switch for the unattended periodic delta sync. Default off. */
  public static boolean getSyncScheduleEnabled() { return SETTINGS.getBoolean("sync.schedule.enabled", false); }
  public static void setSyncScheduleEnabled(boolean b) { SETTINGS.setAttribute("sync.schedule.enabled", b); }

  /** Cheap incremental tick interval in minutes, clamped to 60–240 (1–4 h). */
  public static int getSyncIntervalMinutes() { return clamp(SETTINGS.getInt("sync.schedule.intervalMinutes", 120), 60, 240); }
  public static void setSyncIntervalMinutes(int n) { SETTINGS.setAttribute("sync.schedule.intervalMinutes", clamp(n, 60, 240)); }

  /** Forced full-reconcile cadence in minutes — backstop for same-count
   *  out-of-band controller edits. Default nightly (1440), min 240. */
  public static int getForcedFullIntervalMinutes() { return Math.max(240, SETTINGS.getInt("sync.schedule.forcedFullMinutes", 1440)); }
  public static void setForcedFullIntervalMinutes(int n) { SETTINGS.setAttribute("sync.schedule.forcedFullMinutes", Math.max(240, n)); }

  /** When true a scheduled tick also WRITES (applies) the diff; when false it
   *  only refreshes the cached plan (no controller writes). Separate from the
   *  interactive {@link #getDryRun}. Default false until trusted. */
  public static boolean getAutoApply() { return SETTINGS.getBoolean("sync.schedule.autoApply", false); }
  public static void setAutoApply(boolean b) { SETTINGS.setAttribute("sync.schedule.autoApply", b); }

  /** When false (default), an auto-apply tick withholds DELETE (orphan-removal)
   *  — the highest-blast-radius write — leaving it for a reviewed manual sync. */
  public static boolean getAutoApplyDeletes() { return SETTINGS.getBoolean("sync.schedule.autoApplyDeletes", false); }
  public static void setAutoApplyDeletes(boolean b) { SETTINGS.setAttribute("sync.schedule.autoApplyDeletes", b); }

  private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

  // -- safety --
  public static boolean getDryRun()
  {
    return SETTINGS.getBoolean("sync.dryRun", true);
  }
  public static void setDryRun(boolean b) { SETTINGS.setAttribute("sync.dryRun", b); }

  /** When true, validate the controller's TLS certificate normally. When false,
   *  accept any certificate (typical for self-signed Hikvision controllers). */
  public static boolean getVerifySsl()
  {
    return SETTINGS.getBoolean("controller.verifySsl", false);
  }
  public static void setVerifySsl(boolean b) { SETTINGS.setAttribute("controller.verifySsl", b); }

  private static synchronized Wallet getWallet() throws Exception
  {
    if (wallet == null) wallet = new Wallet(HikvisionSettings.class);
    return wallet;
  }
}
