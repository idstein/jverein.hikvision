package de.jost_net.JVerein.hikvision.ext;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;

import de.jost_net.JVerein.hikvision.HikvisionGroupCatalog;
import de.jost_net.JVerein.hikvision.HikvisionSettings;
import de.willuhn.jameica.gui.extension.Extendable;
import de.willuhn.jameica.gui.extension.Extension;
import de.willuhn.jameica.gui.input.CheckboxInput;
import de.willuhn.jameica.gui.input.IntegerInput;
import de.willuhn.jameica.gui.input.LabelInput;
import de.willuhn.jameica.gui.input.PasswordInput;
import de.willuhn.jameica.gui.input.SelectInput;
import de.willuhn.jameica.gui.input.TextInput;
import de.willuhn.jameica.gui.internal.views.Settings;
import de.willuhn.jameica.gui.util.TabGroup;
import de.willuhn.jameica.messaging.Message;
import de.willuhn.jameica.messaging.MessageConsumer;
import de.willuhn.jameica.messaging.SettingsChangedMessage;
import de.willuhn.jameica.messaging.StatusBarMessage;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;

/**
 * "Hikvision"-Reiter in Datei → Einstellungen — nur Konfiguration.
 *
 * Mitglieder-/Sponsor-Gruppen und Region-Permission-Gruppen erscheinen
 * als Dropdowns, deren Inhalt aus dem {@link HikvisionGroupCatalog}
 * gespeist wird (= letzter Stand der Benutzer-Cache). Die UUIDs werden
 * im Hintergrund gespeichert, der Benutzer sieht nur Namen wie
 * "BSV" / "Mitglieder" / "Region 3". Wenn der Cache leer ist, fällt
 * die Anzeige auf Text-Felder zurück und ein Hinweis sagt was zu tun
 * ist.
 *
 * Daten-Views (Benutzer, Chips, Organisationsgruppen, Türrechte) leben
 * in der Navigation unter OpenJVerein > Hikvision.
 */
public class SettingsView implements Extension
{
  private TextInput url;
  private TextInput user;
  private PasswordInput password;

  // dropdowns when catalog has data; fallback text inputs otherwise
  private SelectInput memberGroupSelect;
  private SelectInput sponsorGroupSelect;
  private SelectInput regionPermissionSelect;
  private TextInput memberGroupIdFallback;
  private TextInput memberGroupNameFallback;
  private TextInput sponsorGroupIdFallback;
  private TextInput sponsorGroupNameFallback;
  private IntegerInput regionPermissionFallback;

  private TextInput zusatzfeldName;
  private IntegerInput interCallPauseMs;
  private CheckboxInput dryRun;

  private HikvisionGroupCatalog catalog;
  private MessageConsumer consumer;

  @Override
  public void extend(Extendable extendable)
  {
    if (!(extendable instanceof Settings)) return;
    Settings settings = (Settings) extendable;

    catalog = HikvisionGroupCatalog.fromCache();

    consumer = new MessageConsumer()
    {
      @Override public void handleMessage(Message m) throws Exception { store(); }
      @Override public Class<?>[] getExpectedMessageTypes()
      { return new Class[] { SettingsChangedMessage.class }; }
      @Override public boolean autoRegister() { return false; }
    };
    Application.getMessagingFactory().registerMessageConsumer(consumer);

    try
    {
      TabGroup tab = new TabGroup(settings.getTabFolder(), "Hikvision");
      tab.getComposite().addDisposeListener(new DisposeListener() {
        @Override public void widgetDisposed(DisposeEvent e)
        { Application.getMessagingFactory().unRegisterMessageConsumer(consumer); }
      });

      url = new TextInput(HikvisionSettings.getControllerUrl(), 200);
      user = new TextInput(HikvisionSettings.getControllerUser(), 64);
      password = new PasswordInput(HikvisionSettings.getControllerPassword());
      zusatzfeldName = new TextInput(HikvisionSettings.getZusatzfeldName(), 64);
      interCallPauseMs = new IntegerInput(HikvisionSettings.getInterCallPauseMs());
      dryRun = new CheckboxInput(HikvisionSettings.getDryRun());

      tab.addLabelPair("Controller-URL", url);
      tab.addLabelPair("Benutzer", user);
      tab.addLabelPair("Passwort", password);

      // --- group / region selectors (dropdowns when catalog available) ---
      boolean haveCatalog = catalog != null && !catalog.groups.isEmpty();
      if (haveCatalog)
      {
        memberGroupSelect = makeGroupSelect(HikvisionSettings.getMemberGroupId());
        sponsorGroupSelect = makeGroupSelect(HikvisionSettings.getSponsorGroupId());
        tab.addLabelPair("Mitglieder-Gruppe", memberGroupSelect);
        tab.addLabelPair("Sponsor-Gruppe", sponsorGroupSelect);
      }
      else
      {
        memberGroupIdFallback = new TextInput(HikvisionSettings.getMemberGroupId(), 64);
        memberGroupNameFallback = new TextInput(HikvisionSettings.getMemberGroupName(), 64);
        sponsorGroupIdFallback = new TextInput(HikvisionSettings.getSponsorGroupId(), 64);
        sponsorGroupNameFallback = new TextInput(HikvisionSettings.getSponsorGroupName(), 64);
        tab.addLabelPair("Mitglieder Gruppen-ID (UUID)", memberGroupIdFallback);
        tab.addLabelPair("Mitglieder Gruppen-Name", memberGroupNameFallback);
        tab.addLabelPair("Sponsor Gruppen-ID (UUID)", sponsorGroupIdFallback);
        tab.addLabelPair("Sponsor Gruppen-Name", sponsorGroupNameFallback);
      }

      boolean haveRegions = catalog != null && !catalog.regions.isEmpty();
      if (haveRegions)
      {
        regionPermissionSelect = makeRegionSelect(HikvisionSettings.getRegionPermissionGroup());
        tab.addLabelPair("Region-Permission (Türrechte)", regionPermissionSelect);
      }
      else
      {
        regionPermissionFallback = new IntegerInput(HikvisionSettings.getRegionPermissionGroup());
        tab.addLabelPair("Region-Permission-Gruppe (Türrechte)", regionPermissionFallback);
      }

      if (!haveCatalog || !haveRegions)
      {
        LabelInput hint = new LabelInput(
          "Tipp: Öffne OpenJVerein > Mitglieder > Hikvision > Benutzer und klicke "
          + "'Aktualisieren'. Danach erscheinen hier Auswahllisten statt UUID-Felder.");
        tab.addLabelPair(" ", hint);
      }
      else
      {
        LabelInput src = new LabelInput("Aus Cache vom " + formatStamp(catalog.timestamp)
            + "  ·  " + catalog.groups.size() + " Gruppen, " + catalog.regions.size() + " Region-Permissions");
        tab.addLabelPair(" ", src);
      }

      tab.addLabelPair("Zusatzfeld-Name (transponder)", zusatzfeldName);
      tab.addLabelPair("Pause zwischen Calls (ms)", interCallPauseMs);
      tab.addCheckbox(dryRun, "Trockenlauf — nur loggen, keine Schreibvorgänge");
    }
    catch (Exception e)
    {
      Logger.error("unable to extend settings", e);
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(
          "Fehler beim Anzeigen der Hikvision-Einstellungen", StatusBarMessage.TYPE_ERROR));
    }
  }

  private SelectInput makeGroupSelect(String currentUuid)
  {
    List<HikvisionGroupCatalog.Group> items = new ArrayList<>(catalog.groups);
    HikvisionGroupCatalog.Group preselect = null;
    for (HikvisionGroupCatalog.Group g : items)
    { if (g.uuid != null && g.uuid.equals(currentUuid)) { preselect = g; break; } }
    return new SelectInput(items.toArray(), preselect);
  }

  private SelectInput makeRegionSelect(int currentId)
  {
    List<HikvisionGroupCatalog.RegionPermissionGroup> items = new ArrayList<>(catalog.regions);
    HikvisionGroupCatalog.RegionPermissionGroup preselect = null;
    for (HikvisionGroupCatalog.RegionPermissionGroup g : items)
    { if (g.id == currentId) { preselect = g; break; } }
    return new SelectInput(items.toArray(), preselect);
  }

  private void store() throws ApplicationException
  {
    try
    {
      HikvisionSettings.setControllerUrl((String) url.getValue());
      HikvisionSettings.setControllerUser((String) user.getValue());
      HikvisionSettings.setControllerPassword((String) password.getValue());

      if (memberGroupSelect != null)
      {
        Object v = memberGroupSelect.getValue();
        if (v instanceof HikvisionGroupCatalog.Group)
        {
          HikvisionGroupCatalog.Group g = (HikvisionGroupCatalog.Group) v;
          HikvisionSettings.setMemberGroupId(g.uuid);
          HikvisionSettings.setMemberGroupName(g.name);
        }
      }
      else if (memberGroupIdFallback != null)
      {
        HikvisionSettings.setMemberGroupId((String) memberGroupIdFallback.getValue());
        HikvisionSettings.setMemberGroupName((String) memberGroupNameFallback.getValue());
      }

      if (sponsorGroupSelect != null)
      {
        Object v = sponsorGroupSelect.getValue();
        if (v instanceof HikvisionGroupCatalog.Group)
        {
          HikvisionGroupCatalog.Group g = (HikvisionGroupCatalog.Group) v;
          HikvisionSettings.setSponsorGroupId(g.uuid);
          HikvisionSettings.setSponsorGroupName(g.name);
        }
      }
      else if (sponsorGroupIdFallback != null)
      {
        HikvisionSettings.setSponsorGroupId((String) sponsorGroupIdFallback.getValue());
        HikvisionSettings.setSponsorGroupName((String) sponsorGroupNameFallback.getValue());
      }

      if (regionPermissionSelect != null)
      {
        Object v = regionPermissionSelect.getValue();
        if (v instanceof HikvisionGroupCatalog.RegionPermissionGroup)
          HikvisionSettings.setRegionPermissionGroup(((HikvisionGroupCatalog.RegionPermissionGroup) v).id);
      }
      else if (regionPermissionFallback != null)
      {
        Object rpg = regionPermissionFallback.getValue();
        if (rpg instanceof Integer) HikvisionSettings.setRegionPermissionGroup((Integer) rpg);
      }

      HikvisionSettings.setZusatzfeldName((String) zusatzfeldName.getValue());
      Object pause = interCallPauseMs.getValue();
      if (pause instanceof Integer) HikvisionSettings.setInterCallPauseMs((Integer) pause);
      HikvisionSettings.setDryRun(Boolean.TRUE.equals(dryRun.getValue()));
    }
    catch (Exception e) { throw new ApplicationException(e.getMessage(), e); }
  }

  private static String formatStamp(long ts)
  {
    if (ts <= 0) return "?";
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
    return sdf.format(new java.util.Date(ts));
  }
}
