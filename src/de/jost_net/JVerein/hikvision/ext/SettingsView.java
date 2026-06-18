package de.jost_net.JVerein.hikvision.ext;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.json.JSONArray;
import org.json.JSONObject;

import de.jost_net.JVerein.hikvision.HikvisionClient;
import de.jost_net.JVerein.hikvision.HikvisionGroupCatalog;
import de.jost_net.JVerein.hikvision.HikvisionSettings;
import de.jost_net.JVerein.hikvision.MitgliedAssignments;
import de.jost_net.JVerein.hikvision.PlanCache;
import de.jost_net.JVerein.hikvision.ProgressListener;
import de.jost_net.JVerein.hikvision.SyncEngine;
import de.willuhn.jameica.gui.GUI;
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
 * "Hikvision"-Reiter in Datei → Einstellungen. Konfiguration und
 * Verbindungs-Helpers (Test Verbindung, Aus Hikvision laden), damit
 * die gesamte Konfiguration in einem Settings-Aufruf abschliessbar ist.
 *
 * Die eigentliche Synchronisierung lebt in OpenJVerein > Mitglieder >
 * Hikvision > Benutzer — Settings ist nur Setup.
 */
public class SettingsView implements Extension
{
  private TextInput url;
  private TextInput user;
  private PasswordInput password;
  private CheckboxInput verifySsl;

  private SelectInput memberGroupSelect;
  private SelectInput sponsorGroupSelect;
  private TextInput memberGroupIdFallback;
  private TextInput memberGroupNameFallback;
  private TextInput sponsorGroupIdFallback;
  private TextInput sponsorGroupNameFallback;

  private TextInput zusatzfeldName;
  private IntegerInput interCallPauseMs;
  private LabelInput statusLabel;
  private Button testBtn;
  private Button fetchBtn;
  private Button migrateBtn;

  private HikvisionGroupCatalog catalog;
  private MessageConsumer consumer;
  private TabGroup tab;

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

    try { buildTab(settings); }
    catch (Exception e)
    {
      Logger.error("unable to extend settings", e);
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(
          "Fehler beim Anzeigen der Hikvision-Einstellungen", StatusBarMessage.TYPE_ERROR));
    }
  }

  private void buildTab(Settings settings) throws Exception
  {
    tab = new TabGroup(settings.getTabFolder(), "Zugangssystem");
    tab.getComposite().addDisposeListener(new DisposeListener() {
      @Override public void widgetDisposed(DisposeEvent e)
      { Application.getMessagingFactory().unRegisterMessageConsumer(consumer); }
    });

    url = new TextInput(HikvisionSettings.getControllerUrl(), 200);
    user = new TextInput(HikvisionSettings.getControllerUser(), 64);
    password = new PasswordInput(HikvisionSettings.getControllerPassword());
    verifySsl = new CheckboxInput(HikvisionSettings.getVerifySsl());
    zusatzfeldName = new TextInput(HikvisionSettings.getZusatzfeldName(), 64);
    interCallPauseMs = new IntegerInput(HikvisionSettings.getInterCallPauseMs());

    tab.addLabelPair("Controller-URL", url);
    tab.addLabelPair("Benutzer", user);
    tab.addLabelPair("Passwort", password);
    tab.addCheckbox(verifySsl, "TLS-Zertifikat prüfen (deaktivieren bei selbstsignierten Controllern)");

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

    boolean haveAny = catalog != null && (!catalog.groups.isEmpty() || !catalog.regions.isEmpty());
    statusLabel = new LabelInput(haveAny
        ? "Aus Cache vom " + formatStamp(catalog.timestamp) + "  ·  "
            + catalog.groups.size() + " Organisationsgruppen, " + catalog.regions.size() + " Berechtigungsgruppen"
        : "Noch kein Cache. Klicke 'Aus Hikvision laden' um Auswahllisten zu füllen.");
    tab.addLabelPair("Status", statusLabel);

    tab.addLabelPair("Zusatzfeld-Name (transponder)", zusatzfeldName);
    tab.addLabelPair("Pause zwischen Calls (ms)", interCallPauseMs);

    Composite btnRow = new Composite(tab.getComposite(), SWT.NONE);
    btnRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    btnRow.setLayout(new GridLayout(2, true));

    testBtn = new Button(btnRow, SWT.PUSH);
    testBtn.setText("Test Verbindung");
    testBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    testBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onTest(); }
    });

    fetchBtn = new Button(btnRow, SWT.PUSH);
    fetchBtn.setText("Gruppen aus Hikvision laden");
    fetchBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    fetchBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onFetch(); }
    });

    // ----- Mirror sync between jverein Zusatzfeld and MitgliedAssignments
    // Both flows are idempotent (running twice gives the same result).
    Composite migRow = new Composite(tab.getComposite(), SWT.NONE);
    migRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    migRow.setLayout(new GridLayout(2, true));
    migrateBtn = new Button(migRow, SWT.PUSH);
    migrateBtn.setText("Von Zusatzfeld importieren");
    migrateBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    migrateBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onMigrate(); }
    });
    Button writeBackBtn = new Button(migRow, SWT.PUSH);
    writeBackBtn.setText("Zu Zusatzfeld exportieren");
    writeBackBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    writeBackBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onWriteBackZusatzfeld(); }
    });
  }

  private SelectInput makeGroupSelect(String currentUuid)
  {
    List<HikvisionGroupCatalog.Group> items = new ArrayList<>(catalog.groups);
    HikvisionGroupCatalog.Group preselect = null;
    for (HikvisionGroupCatalog.Group g : items)
    { if (g.uuid != null && g.uuid.equals(currentUuid)) { preselect = g; break; } }
    return new SelectInput(items.toArray(), preselect);
  }

  // ----------------------------------------------------------------- actions

  private void onTest()
  {
    testBtn.setEnabled(false);
    try { persistConnectionFields(); }  // must run on the UI thread — reads SWT inputs
    catch (Exception e)
    {
      Logger.error("Test connection: read settings failed", e);
      showError("Verbindung fehlgeschlagen", e.getClass().getSimpleName() + ": " + e.getMessage());
      testBtn.setEnabled(true);
      return;
    }
    Thread t = new Thread(() -> {
      try
      {
        HikvisionClient client = currentClient();
        String xml = client.getDeviceInfoXml();
        String model = xtract(xml, "model");
        String fw = xtract(xml, "firmwareVersion");
        String sn = xtract(xml, "serialNumber");
        showInfo("Verbindung OK", "Model: " + model + "\nFirmware: " + fw + "\nSerial: " + sn);
      }
      catch (Exception e)
      {
        Logger.error("Test connection failed", e);
        showError("Verbindung fehlgeschlagen", e.getClass().getSimpleName() + ": " + e.getMessage());
      }
      finally
      {
        Display.getDefault().asyncExec(() -> {
          if (testBtn != null && !testBtn.isDisposed()) testBtn.setEnabled(true);
        });
      }
    }, "jverein.hikvision-settings-test");
    t.setDaemon(true); t.start();
  }

  /**
   * Fetches the controller's group lists (org userGroups +
   * Berechtigungsgruppen) so the Settings dropdowns can be populated
   * in-place. Saves the group catalog so the other views (Benutzer /
   * Organisationsgruppen / Berechtigungsgruppen) also benefit.
   */
  private void onFetch()
  {
    fetchBtn.setEnabled(false);
    testBtn.setEnabled(false);
    try { persistConnectionFields(); }  // must run on the UI thread — reads SWT inputs
    catch (Exception e)
    {
      Logger.error("Fetch: read settings failed", e);
      showError("Laden fehlgeschlagen", e.getClass().getSimpleName() + ": " + e.getMessage());
      fetchBtn.setEnabled(true);
      testBtn.setEnabled(true);
      return;
    }
    if (statusLabel != null) statusLabel.setValue("lädt UserInfo + CardInfo …");
    Thread t = new Thread(() -> {
      try
      {
        // Lightweight path: UserInfo only — no CardInfo, no jverein DB scan,
        // no PlanCache side effects. Settings only needs group + region data,
        // and both live on each UserInfo record.
        HikvisionGroupCatalog c = HikvisionGroupCatalog.refreshFromHikvision(
            currentClient(),
            new ProgressListener() {
              @Override public void log(String msg) { Logger.info(msg); }
              @Override public void progress(int done, int total) {}
              @Override public void progress(int done, int total, String phase)
              {
                Display.getDefault().asyncExec(() -> {
                  if (statusLabel != null) statusLabel.setValue(phase + "  " + done + " / " + total + " …");
                });
              }
            });
        Display.getDefault().asyncExec(() -> {
          showInfo("Hikvision geladen",
              c.groups.size() + " Organisationsgruppen, " + c.regions.size() + " Berechtigungsgruppen geladen.\n"
              + "Bitte den Dialog schliessen und erneut öffnen, damit die Auswahllisten erscheinen.");
          if (statusLabel != null)
            statusLabel.setValue("Aktualisiert. Dialog schliessen + neu öffnen, um Dropdowns zu sehen.");
        });
      }
      catch (Exception e)
      {
        Logger.error("Hikvision fetch failed", e);
        showError("Laden fehlgeschlagen", e.getClass().getSimpleName() + ": " + e.getMessage());
      }
      finally
      {
        Display.getDefault().asyncExec(() -> {
          if (fetchBtn != null && !fetchBtn.isDisposed()) fetchBtn.setEnabled(true);
          if (testBtn != null && !testBtn.isDisposed()) testBtn.setEnabled(true);
        });
      }
    }, "jverein.hikvision-settings-fetch");
    t.setDaemon(true); t.start();
  }

  /**
   * Flow (a): full rebuild of {@code MitgliedAssignments.json} from the
   * jverein Zusatzfeld + latest Hikvision PlanCache. Idempotent —
   * existing entries are overwritten with what jverein + cache say.
   * Manual edits to the assignment store (group choice via the dialog)
   * are lost; the canonical source after this is the Zusatzfeld + cache.
   */
  private void onMigrate()
  {
    MessageBox confirm = new MessageBox(GUI.getShell(), SWT.ICON_QUESTION | SWT.YES | SWT.NO);
    confirm.setText("Von Zusatzfeld importieren");
    confirm.setMessage("Liest für jedes aktive Mitglied das Zusatzfeld '"
        + HikvisionSettings.getZusatzfeldName() + "' (Transponder) und übernimmt aus dem "
        + "letzten Hikvision-Aktualisierungslauf die aktuelle Gruppenzuordnung.\n\n"
        + "ÜBERSCHREIBT bestehende Einträge in MitgliedAssignments — manuelle "
        + "Gruppen- oder Transponder-Änderungen aus dem Dialog werden zurückgesetzt.\n"
        + "Das jverein-Zusatzfeld bleibt unverändert.\n\n"
        + "Idempotent — kann gefahrlos mehrfach ausgeführt werden.\n\nFortfahren?");
    if (confirm.open() != SWT.YES) return;

    migrateBtn.setEnabled(false);
    Thread t = new Thread(() -> {
      try
      {
        MitgliedAssignments.MigrationResult r = MitgliedAssignments.migrateFromZusatzfeld(true,
            new ProgressListener() {
              @Override public void log(String msg) { Logger.info(msg); }
              @Override public void progress(int done, int total) {}
              @Override public void progress(int done, int total, String phase) {}
            });
        StringBuilder sb = new StringBuilder();
        sb.append("Im Store: ").append(r.totalInStore).append(" Zuweisungen\n");
        sb.append("Neu angelegt: ").append(r.created).append("\n");
        sb.append("Aktualisiert: ").append(r.updated).append("\n");
        sb.append("Unverändert (bereits vorhanden): ").append(r.unchanged).append("\n");
        sb.append("Mit Hikvision-Cache abgeglichen: ").append(r.matchedFromHikvision).append("\n");
        sb.append("Default Mitglieder: ").append(r.defaultedActive).append("\n");
        sb.append("Default Sponsor: ").append(r.defaultedSponsor).append("\n");
        sb.append("Austritt-Mitglieder mit Hikvision-Record (für DISABLE): ").append(r.includedDeparted).append("\n");
        sb.append("Übersprungen (kein Transponder + kein Hikvision-Record): ").append(r.skippedNoRelevance).append("\n");
        sb.append("Orphan-Einträge entfernt (jvId nicht mehr in jverein): ").append(r.orphansRemoved).append("\n");
        if (!r.planCacheAvailable)
          sb.append("\nHINWEIS: kein PlanCache gefunden — alle Gruppen sind Defaults. "
              + "Erst in Benutzer-Ansicht 'Aktualisieren' klicken und Migration erneut ausführen.");
        sb.append("\n\nDatei: ").append(MitgliedAssignments.defaultFile().getAbsolutePath());
        showInfo("Migration abgeschlossen", sb.toString());
      }
      catch (Exception e)
      {
        Logger.error("MitgliedAssignments migration failed", e);
        showError("Migration fehlgeschlagen", e.getClass().getSimpleName() + ": " + e.getMessage());
      }
      finally
      {
        Display.getDefault().asyncExec(() -> {
          if (migrateBtn != null && !migrateBtn.isDisposed()) migrateBtn.setEnabled(true);
        });
      }
    }, "jverein.hikvision-migrate-assignments");
    t.setDaemon(true); t.start();
  }

  /**
   * Bulk mirror: walks every active jverein Mitglied and writes their
   * MitgliedAssignments transponder list to the jverein Zusatzfeld
   * (clearing the field for Mitglieder no longer in the store). Useful
   * after a migration or one-off store edits to bring the jverein UI
   * back in sync without going through individual saves.
   */
  private void onWriteBackZusatzfeld()
  {
    MessageBox confirm = new MessageBox(GUI.getShell(), SWT.ICON_QUESTION | SWT.YES | SWT.NO);
    confirm.setText("Zu Zusatzfeld exportieren");
    confirm.setMessage("Überschreibt das Zusatzfeld '" + HikvisionSettings.getZusatzfeldName()
        + "' jeder aktiven jverein-Mitgliedschaft mit dem aktuellen Transponder-Inhalt aus "
        + "MitgliedAssignments. Mitglieder ohne Zuweisung bekommen das Feld geleert.\n\n"
        + "Idempotent — kann gefahrlos mehrfach ausgeführt werden.\n\nFortfahren?");
    if (confirm.open() != SWT.YES) return;

    new Thread(() -> {
      try
      {
        de.jost_net.JVerein.hikvision.MitgliedAssignments store =
            de.jost_net.JVerein.hikvision.MitgliedAssignments.load();
        int wrote = store.syncAllToZusatzfeld(new ProgressListener() {
          @Override public void log(String msg) { Logger.info(msg); }
          @Override public void progress(int done, int total) {}
          @Override public void progress(int done, int total, String phase) {}
        });
        showInfo("Rückschreiben abgeschlossen",
            wrote + " jverein-Zusatzfelder aktualisiert.\n"
            + "(Werte die bereits übereinstimmten, wurden nicht angefasst.)");
      }
      catch (Exception ex)
      {
        Logger.error("Zusatzfeld Rückschreiben fehlgeschlagen", ex);
        showError("Rückschreiben fehlgeschlagen", ex.getClass().getSimpleName() + ": " + ex.getMessage());
      }
    }, "jverein.hikvision-writeback-zusatzfeld").start();
  }

  /** Save URL / user / password / verifySsl right now so test+fetch use them. */
  private void persistConnectionFields() throws Exception
  {
    HikvisionSettings.setControllerUrl((String) url.getValue());
    HikvisionSettings.setControllerUser((String) user.getValue());
    HikvisionSettings.setControllerPassword((String) password.getValue());
    HikvisionSettings.setVerifySsl(Boolean.TRUE.equals(verifySsl.getValue()));
    Object pause = interCallPauseMs.getValue();
    if (pause instanceof Integer) HikvisionSettings.setInterCallPauseMs((Integer) pause);
  }

  private HikvisionClient currentClient()
  {
    return new HikvisionClient(
        HikvisionSettings.getControllerUrl(),
        HikvisionSettings.getControllerUser(),
        HikvisionSettings.getControllerPassword(),
        HikvisionSettings.getInterCallPauseMs(),
        HikvisionSettings.getVerifySsl());
  }

  private static String xtract(String xml, String tag)
  {
    String open = "<" + tag + ">", close = "</" + tag + ">";
    int a = xml.indexOf(open); if (a < 0) return "(?)";
    int b = xml.indexOf(close, a + open.length()); if (b < 0) return "(?)";
    return xml.substring(a + open.length(), b);
  }

  // ----------------------------------------------------------------- store

  private void store() throws ApplicationException
  {
    try
    {
      persistConnectionFields();

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

      HikvisionSettings.setZusatzfeldName((String) zusatzfeldName.getValue());
    }
    catch (Exception e) { throw new ApplicationException(e.getMessage(), e); }
  }

  private static String formatStamp(long ts)
  {
    if (ts <= 0) return "?";
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
    return sdf.format(new java.util.Date(ts));
  }

  // ----------------------------------------------------------------- dialogs

  private void showError(String title, String message)
  {
    Display.getDefault().asyncExec(() -> {
      MessageBox box = new MessageBox(GUI.getShell(), SWT.ICON_ERROR | SWT.OK);
      box.setText(title); box.setMessage(message); box.open();
    });
  }
  private void showInfo(String title, String message)
  {
    Display.getDefault().asyncExec(() -> {
      MessageBox box = new MessageBox(GUI.getShell(), SWT.ICON_INFORMATION | SWT.OK);
      box.setText(title); box.setMessage(message); box.open();
    });
  }
}
