package de.jost_net.JVerein.hikvision;

import de.jost_net.JVerein.rmi.Mitglied;

/**
 * Maps a jverein Mitglied to a Hikvision employeeNo and decides whether
 * the user should be modelled as a normal Mitglied or a sponsor (visitor).
 *
 * Rules:
 *   - Mitglied has externemitgliedsnummer → employeeNo = int-normalized externe,
 *     userType=normal, group=Mitglieder
 *   - Mitglied has no externemitgliedsnummer → sponsor → employeeNo = "G{id}",
 *     userType=visitor, group=BSV
 *
 * After int-normalization, leading zeros disappear (so "0497" → "497"),
 * matching the convention we cleaned up on the controller.
 */
public class Identity
{
  public final String employeeNo;
  public final boolean isSponsor;

  private Identity(String emp, boolean sponsor) { this.employeeNo = emp; this.isSponsor = sponsor; }

  public static Identity of(Mitglied m) throws Exception
  {
    String ext = m.getExterneMitgliedsnummer();
    if (ext != null && !ext.trim().isEmpty())
    {
      try { return new Identity(String.valueOf(Integer.parseInt(ext.trim())), false); }
      catch (NumberFormatException e) { return new Identity(ext.trim(), false); }
    }
    return new Identity("G" + m.getID(), true);
  }

  /** Decode an employeeNo back to a search key (jverein id for G-prefix, externe otherwise). */
  public static String externeOrIdFor(String employeeNo)
  {
    if (employeeNo == null) return null;
    String e = employeeNo.trim();
    if (e.startsWith("G") && e.length() > 1)
    {
      String rest = e.substring(1);
      // numeric ⇒ jv_id
      try { Integer.parseInt(rest); return rest; } catch (NumberFormatException ignored) {}
    }
    try { return String.valueOf(Integer.parseInt(e)); } catch (NumberFormatException ignored) {}
    return e;
  }

  /** Is this employeeNo one we manage (numeric or G-prefix)? SKM* etc. are not. */
  public static boolean isManaged(String employeeNo)
  {
    if (employeeNo == null) return false;
    String e = employeeNo.trim();
    if (e.startsWith("G") && e.length() > 1)
    {
      try { Integer.parseInt(e.substring(1)); return true; } catch (NumberFormatException ignored) {}
    }
    try { Integer.parseInt(e); return true; } catch (NumberFormatException ignored) {}
    return false;
  }
}
