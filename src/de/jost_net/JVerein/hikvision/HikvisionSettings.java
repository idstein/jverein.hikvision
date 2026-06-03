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

  public static int getRegionPermissionGroup()
  {
    return SETTINGS.getInt("region.permissionGroup", 3);
  }
  public static void setRegionPermissionGroup(int n) { SETTINGS.setAttribute("region.permissionGroup", n); }

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

  // -- region-permission-group naming --
  // Hikvision DS-K firmware doesn't expose the Permission Group display
  // names via ISAPI (they only live in the controller's web UI). We store
  // a user-set name per region id locally so the Türrechte view and the
  // Settings dropdown can show meaningful labels.

  public static String getRegionName(int id)
  { return SETTINGS.getString("region.name." + id, ""); }

  public static void setRegionName(int id, String name)
  {
    if (name == null || name.trim().isEmpty())
      SETTINGS.setAttribute("region.name." + id, (String) null);
    else
      SETTINGS.setAttribute("region.name." + id, name.trim());
  }

  private static synchronized Wallet getWallet() throws Exception
  {
    if (wallet == null) wallet = new Wallet(HikvisionSettings.class);
    return wallet;
  }
}
