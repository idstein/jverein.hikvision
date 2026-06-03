package de.jost_net.JVerein.hikvision.ext;

import de.jost_net.JVerein.hikvision.gui.action.HikvisionBenutzerAction;
import de.jost_net.JVerein.hikvision.gui.action.HikvisionChipsAction;
import de.jost_net.JVerein.hikvision.gui.action.HikvisionGruppenAction;
import de.jost_net.JVerein.hikvision.gui.action.HikvisionRechteAction;
import de.jost_net.JVerein.gui.navigation.MyItem;
import de.willuhn.datasource.GenericIterator;
import de.willuhn.jameica.gui.NavigationItem;
import de.willuhn.jameica.gui.extension.Extendable;
import de.willuhn.jameica.gui.extension.Extension;
import de.willuhn.logging.Logger;

/**
 * Adds a "Zugangssystem" subtree to the OpenJVerein navigation. Registered
 * in plugin.xml as an extension of {@code jverein.main} — that's the
 * navigation root JVerein declares in its own plugin.xml. Reuses
 * JVerein's {@code MyItem} so the icons / styling match the surrounding
 * tree.
 *
 * Placement strategy:
 *   - Prefer to attach under the "Mitglieder" child of OpenJVerein
 *     (matches user request)
 *   - Fall back to OpenJVerein root if Mitglieder isn't there yet —
 *     happens if our extension fires before JVerein's MyExtension
 *     populates the tree. Either way the entries are reachable; only
 *     the parent differs.
 */
public class NavigationExtension implements Extension
{
  @Override
  public void extend(Extendable extendable)
  {
    if (!(extendable instanceof NavigationItem)) return;
    NavigationItem jverein = (NavigationItem) extendable;

    try
    {
      NavigationItem parent = findChild(jverein, "Mitglieder");
      if (parent == null)
      {
        Logger.info("Mitglieder-Knoten nicht gefunden — Zugangssystem-Untermenü wird unter OpenJVerein angelegt");
        parent = jverein;
      }

      NavigationItem hik = new MyItem(parent, "Zugangssystem", null);
      parent.addChild(hik);
      // Icon reuse — jverein and jameica both ship their image resources on the
      // shared classpath, so SWTUtil.getImage() resolves any of these names:
      //   "user-friends.png" / "users.png"  ← jverein (same icons as Mitglieder / Familienverband)
      //   "stock_keyring.png" / "locked.png" ← jameica (keyring + lock)
      hik.addChild(new MyItem(hik, "Benutzer",              new HikvisionBenutzerAction(), "user-friends.png"));
      hik.addChild(new MyItem(hik, "Transponder",           new HikvisionChipsAction(),    "stock_keyring.png"));
      hik.addChild(new MyItem(hik, "Organisationsgruppen",  new HikvisionGruppenAction(),  "users.png"));
      hik.addChild(new MyItem(hik, "Türrechte",             new HikvisionRechteAction(),   "locked.png"));
    }
    catch (Exception e)
    {
      Logger.error("Konnte Hikvision-Navigation nicht anlegen", e);
    }
  }

  private static NavigationItem findChild(NavigationItem parent, String name) throws Exception
  {
    GenericIterator<NavigationItem> it = parent.getChildren();
    if (it == null) return null;
    while (it.hasNext())
    {
      NavigationItem n = it.next();
      if (name.equals(n.getName())) return n;
    }
    return null;
  }
}
