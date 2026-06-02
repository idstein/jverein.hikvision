package de.jost_net.JVerein.hikvision.ext;

import java.io.File;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import org.json.JSONArray;
import org.json.JSONObject;

import de.jost_net.JVerein.Einstellungen;
import de.jost_net.JVerein.hikvision.ChipStore;
import de.jost_net.JVerein.hikvision.HikvisionClient;
import de.jost_net.JVerein.hikvision.HikvisionSettings;
import de.jost_net.JVerein.hikvision.Identity;
import de.jost_net.JVerein.hikvision.PlanCache;
import de.jost_net.JVerein.hikvision.SyncEngine;
import de.jost_net.JVerein.rmi.Mitglied;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.gui.extension.Extendable;
import de.willuhn.jameica.gui.extension.Extension;
import de.willuhn.jameica.gui.input.CheckboxInput;
import de.willuhn.jameica.gui.input.IntegerInput;
import de.willuhn.jameica.gui.input.PasswordInput;
import de.willuhn.jameica.gui.input.TextInput;
import de.willuhn.jameica.gui.internal.views.Settings;
import de.willuhn.jameica.gui.util.TabGroup;
import de.willuhn.jameica.messaging.Message;
import de.willuhn.jameica.messaging.MessageConsumer;
import de.willuhn.jameica.messaging.SettingsChangedMessage;
import de.willuhn.jameica.messaging.StatusBarMessage;
import de.willuhn.jameica.system.Application;
import de.willuhn.jameica.system.BackgroundTask;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.ProgressMonitor;

/**
 * Two tabs added to Datei → Einstellungen:
 *   "Hikvision"        — connection config + Sync/Import buttons + log
 *   "Hikvision Chips"  — manage chip ↔ Kartennummer entries with
 *                        add/edit/delete + CSV import/export
 */
public class SettingsView implements Extension
{
  private TextInput url;
  private TextInput user;
  private PasswordInput password;
  private TextInput memberGroupId;
  private TextInput memberGroupName;
  private TextInput sponsorGroupId;
  private TextInput sponsorGroupName;
  private IntegerInput regionPermissionGroup;
  private TextInput zusatzfeldName;
  private IntegerInput interCallPauseMs;
  private CheckboxInput dryRun;
  private Text logArea;
  private Button syncButton;
  private Button importButton;
  private org.eclipse.swt.widgets.ProgressBar syncProgress;
  private Label syncProgressLabel;

  private Table chipTable;
  private ChipStore store;

  private Table usersTable;
  private Label usersCount;
  private Button refreshButton;
  private Button testButton;
  private org.eclipse.swt.widgets.Combo filterCombo;
  private java.util.List<SyncEngine.PlanRow> currentPlanRows = java.util.Collections.emptyList();
  private org.eclipse.swt.widgets.ProgressBar usersProgress;

  private MessageConsumer consumer;

  @FunctionalInterface
  private interface OnClick { void onClick(); }

  @Override
  public void extend(Extendable extendable)
  {
    if (!(extendable instanceof Settings)) return;
    Settings settings = (Settings) extendable;

    try { store = ChipStore.defaultStore(); }
    catch (Exception e)
    {
      Logger.error("ChipStore konnte nicht geladen werden", e);
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(
          "ChipStore konnte nicht geladen werden: " + e.getMessage(), StatusBarMessage.TYPE_ERROR));
      return;
    }

    consumer = new MessageConsumer()
    {
      @Override public void handleMessage(Message m) throws Exception { storeConfig(); }
      @Override public Class<?>[] getExpectedMessageTypes()
      { return new Class[] { SettingsChangedMessage.class }; }
      @Override public boolean autoRegister() { return false; }
    };
    Application.getMessagingFactory().registerMessageConsumer(consumer);

    try
    {
      buildSyncTab(settings);
      buildChipsTab(settings);
      buildUsersTab(settings);
    }
    catch (Exception e)
    {
      Logger.error("unable to extend settings", e);
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(
          "Fehler beim Anzeigen der Hikvision-Einstellungen", StatusBarMessage.TYPE_ERROR));
    }
  }

  // ============================================================ Sync tab

  private void buildSyncTab(Settings settings) throws Exception
  {
    TabGroup tab = new TabGroup(settings.getTabFolder(), "Hikvision");
    tab.getComposite().addDisposeListener(e -> {
      Application.getMessagingFactory().unRegisterMessageConsumer(consumer);
    });

    url = new TextInput(HikvisionSettings.getControllerUrl(), 200);
    user = new TextInput(HikvisionSettings.getControllerUser(), 64);
    password = new PasswordInput(HikvisionSettings.getControllerPassword());
    memberGroupId = new TextInput(HikvisionSettings.getMemberGroupId(), 64);
    memberGroupName = new TextInput(HikvisionSettings.getMemberGroupName(), 64);
    sponsorGroupId = new TextInput(HikvisionSettings.getSponsorGroupId(), 64);
    sponsorGroupName = new TextInput(HikvisionSettings.getSponsorGroupName(), 64);
    regionPermissionGroup = new IntegerInput(HikvisionSettings.getRegionPermissionGroup());
    zusatzfeldName = new TextInput(HikvisionSettings.getZusatzfeldName(), 64);
    interCallPauseMs = new IntegerInput(HikvisionSettings.getInterCallPauseMs());
    dryRun = new CheckboxInput(HikvisionSettings.getDryRun());

    tab.addLabelPair("Controller-URL", url);
    tab.addLabelPair("Benutzer", user);
    tab.addLabelPair("Passwort", password);
    tab.addLabelPair("Mitglieder Gruppen-ID (UUID)", memberGroupId);
    tab.addLabelPair("Mitglieder Gruppen-Name", memberGroupName);
    tab.addLabelPair("Sponsor Gruppen-ID (UUID)", sponsorGroupId);
    tab.addLabelPair("Sponsor Gruppen-Name", sponsorGroupName);
    tab.addLabelPair("Region-Permission-Gruppe (Türrechte)", regionPermissionGroup);
    tab.addLabelPair("Zusatzfeld-Name (transponder)", zusatzfeldName);
    tab.addLabelPair("Pause zwischen Calls (ms)", interCallPauseMs);
    tab.addCheckbox(dryRun, "Trockenlauf — nur loggen, keine Schreibvorgänge");

    Composite c = tab.getComposite();

    syncButton = new Button(c, SWT.PUSH);
    syncButton.setText("Jetzt synchronisieren (jverein → Hikvision)");
    syncButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    syncButton.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onSyncClick(); }
    });

    importButton = new Button(c, SWT.PUSH);
    importButton.setText("Aus Hikvision importieren (überschreibt jverein-Transponder!)");
    importButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    importButton.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onImportClick(); }
    });

    syncProgress = new org.eclipse.swt.widgets.ProgressBar(c, SWT.HORIZONTAL | SWT.SMOOTH);
    syncProgress.setMinimum(0);
    syncProgress.setMaximum(100);
    syncProgress.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

    syncProgressLabel = new Label(c, SWT.NONE);
    syncProgressLabel.setText(" ");
    syncProgressLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

    logArea = new Text(c, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
    GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    gd.heightHint = 260;
    logArea.setLayoutData(gd);
  }

  private void storeConfig() throws ApplicationException
  {
    try
    {
      HikvisionSettings.setControllerUrl((String) url.getValue());
      HikvisionSettings.setControllerUser((String) user.getValue());
      HikvisionSettings.setControllerPassword((String) password.getValue());
      HikvisionSettings.setMemberGroupId((String) memberGroupId.getValue());
      HikvisionSettings.setMemberGroupName((String) memberGroupName.getValue());
      HikvisionSettings.setSponsorGroupId((String) sponsorGroupId.getValue());
      HikvisionSettings.setSponsorGroupName((String) sponsorGroupName.getValue());
      Object rpg = regionPermissionGroup.getValue();
      if (rpg instanceof Integer) HikvisionSettings.setRegionPermissionGroup((Integer) rpg);
      HikvisionSettings.setZusatzfeldName((String) zusatzfeldName.getValue());
      Object pause = interCallPauseMs.getValue();
      if (pause instanceof Integer) HikvisionSettings.setInterCallPauseMs((Integer) pause);
      HikvisionSettings.setDryRun(Boolean.TRUE.equals(dryRun.getValue()));
    }
    catch (Exception e) { throw new ApplicationException(e.getMessage(), e); }
  }

  private void onSyncClick()
  {
    startTabTask("Hikvision Sync", "Sync gestartet", (task, mon) -> {
      storeConfig();
      SyncEngine.Result r = SyncEngine.run(isDryRun(), syncTabListener(task, mon));
      appendLog("\nFertig (Sync). created=" + r.created + " deleted=" + r.deleted
          + " cardsAdded=" + r.cardsAdded + " cardsRemoved=" + r.cardsRemoved
          + " skipped=" + r.skippedMembers + " unknownCards=" + r.unknownCards
          + " errors=" + r.errors.size() + "\n");
      if (!r.errors.isEmpty())
      { appendLog("\nFehler:\n"); for (String e : r.errors) appendLog("  " + e + "\n"); }
    });
  }

  private void onImportClick()
  {
    if (!isDryRun() && !confirm(
        "Aus Hikvision importieren",
        "Dieser Vorgang überschreibt die transponder-Zusatzfelder aller "
        + "passenden jverein-Mitglieder mit den Werten aus dem "
        + "Zutrittssystem. Wirklich fortfahren?"))
      return;
    startTabTask("Hikvision Import", "Import gestartet", (task, mon) -> {
      storeConfig();
      SyncEngine.ImportResult r = SyncEngine.importFromHikvision(isDryRun(), syncTabListener(task, mon));
      appendLog("\nFertig (Import). updated=" + r.membersUpdated
          + " unchanged=" + r.membersUnchanged
          + " hikUnmatched=" + r.hikvisionUsersUnmatched
          + " unknownCards=" + r.unknownCards
          + " errors=" + r.errors.size() + "\n");
      if (!r.errors.isEmpty())
      { appendLog("\nFehler:\n"); for (String e : r.errors) appendLog("  " + e + "\n"); }
    });
  }

  @FunctionalInterface
  private interface MonitoredTask
  {
    void run(BackgroundTask task, ProgressMonitor mon) throws Exception;
  }

  /**
   * Submit a task to Jameica's BackgroundTask queue. The global status bar
   * shows the progress (and a cancel button). The in-tab ProgressBar
   * receives the same updates via {@link #syncTabListener}.
   *
   * Catches {@link java.io.InterruptedIOException} as a cancel (not an
   * error) — surfaces as STATUS_CANCEL in the status bar.
   */
  private void startTabTask(String taskName, String startMsg, MonitoredTask body)
  {
    syncButton.setEnabled(false);
    importButton.setEnabled(false);
    logArea.setText("");
    appendLog(startMsg + " (" + (isDryRun() ? "Trockenlauf" : "APPLY") + ") …\n");
    if (syncProgress != null && !syncProgress.isDisposed())
    { syncProgress.setMaximum(100); syncProgress.setSelection(0); }
    if (syncProgressLabel != null && !syncProgressLabel.isDisposed())
      syncProgressLabel.setText(" ");

    Application.getController().start(new HikvisionBackgroundTask()
    {
      @Override
      public void run(ProgressMonitor monitor) throws ApplicationException
      {
        try
        {
          monitor.setStatusText(taskName + " läuft …");
          body.run(this, monitor);
          monitor.setStatus(ProgressMonitor.STATUS_DONE);
          monitor.setPercentComplete(100);
        }
        catch (java.io.InterruptedIOException ie)
        {
          Logger.info(taskName + " cancelled by user");
          appendLog("\nABGEBROCHEN: " + ie.getMessage() + "\n");
          monitor.log("Abgebrochen: " + ie.getMessage());
          monitor.setStatus(ProgressMonitor.STATUS_CANCEL);
        }
        catch (Exception e)
        {
          Logger.error(taskName + " failed", e);
          appendLog("\nFEHLER: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
          monitor.log("FEHLER: " + e.getMessage());
          monitor.setStatus(ProgressMonitor.STATUS_ERROR);
          throw new ApplicationException(e.getMessage(), e);
        }
        finally
        {
          Display.getDefault().asyncExec(() -> {
            if (syncButton != null && !syncButton.isDisposed()) syncButton.setEnabled(true);
            if (importButton != null && !importButton.isDisposed()) importButton.setEnabled(true);
          });
        }
      }
    });
  }

  private boolean isDryRun() { return Boolean.TRUE.equals(dryRun.getValue()); }

  /**
   * Builds a ProgressListener that drives both Jameica's global status bar
   * (via the ProgressMonitor) AND the in-tab ProgressBar widgets on the
   * sync tab. The Jameica monitor is the canonical place — the in-tab bar
   * is a supplemental at-a-glance indicator next to the button.
   */
  private SyncEngine.ProgressListener syncTabListener(BackgroundTask task, ProgressMonitor mon)
  {
    return new SyncEngine.ProgressListener()
    {
      @Override public void log(String msg)
      {
        if (mon != null) mon.log(msg);
        appendLog(msg + "\n");
      }
      @Override public void progress(int done, int total) { progress(done, total, ""); }
      @Override public void progress(int done, int total, String phase)
      {
        if (mon != null)
        {
          int pct = total > 0 ? (int) (100L * Math.min(done, total) / total) : 0;
          mon.setPercentComplete(pct);
          mon.setStatusText(phase + "  " + done + " / " + total);
        }
        Display.getDefault().asyncExec(() -> {
          if (syncProgress != null && !syncProgress.isDisposed())
          {
            int safeTotal = Math.max(total, 1);
            syncProgress.setMaximum(safeTotal);
            syncProgress.setSelection(Math.min(done, safeTotal));
          }
          if (syncProgressLabel != null && !syncProgressLabel.isDisposed())
            syncProgressLabel.setText(phase + "  " + done + " / " + total);
        });
      }
      @Override public boolean isCancelled() { return task != null && task.isInterrupted(); }
    };
  }


  private void appendLog(String s)
  {
    Display.getDefault().asyncExec(() -> {
      if (logArea != null && !logArea.isDisposed()) logArea.append(s);
    });
  }

  private boolean confirm(String title, String message)
  {
    Shell sh = Display.getDefault().getActiveShell();
    MessageBox box = new MessageBox(sh, SWT.ICON_WARNING | SWT.YES | SWT.NO);
    box.setText(title);
    box.setMessage(message);
    return box.open() == SWT.YES;
  }

  // =========================================================== Chips tab

  private void buildChipsTab(Settings settings) throws Exception
  {
    TabGroup tab = new TabGroup(settings.getTabFolder(), "Hikvision Chips");
    Composite c = tab.getComposite();

    Label info = new Label(c, SWT.WRAP);
    info.setText("Chip ↔ Kartennummer-Zuordnungen werden in Jameica gespeichert "
        + "(cfg/Chips.json). Für Backup oder externe Verarbeitung können sie "
        + "als CSV exportiert / importiert werden.");
    GridData infoGd = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    infoGd.widthHint = 600;
    info.setLayoutData(infoGd);

    chipTable = new Table(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
    chipTable.setHeaderVisible(true);
    chipTable.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    tgd.heightHint = 400; tgd.widthHint = 600;
    chipTable.setLayoutData(tgd);

    TableColumn col1 = new TableColumn(chipTable, SWT.LEFT);
    col1.setText("Chip"); col1.setWidth(160);
    TableColumn col2 = new TableColumn(chipTable, SWT.LEFT);
    col2.setText("Kartennummer"); col2.setWidth(220);
    refreshChipTable();

    chipTable.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetDefaultSelected(SelectionEvent e) { onEditChip(); }
    });

    Composite btnRow = new Composite(c, SWT.NONE);
    GridData brGd = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    btnRow.setLayoutData(brGd);
    btnRow.setLayout(new GridLayout(5, false));

    mkButton(btnRow, "Hinzufügen…", () -> onAddChip());
    mkButton(btnRow, "Bearbeiten…", () -> onEditChip());
    mkButton(btnRow, "Löschen",     () -> onDeleteChip());
    mkButton(btnRow, "Aus CSV importieren…", () -> onImportCsv());
    mkButton(btnRow, "Als CSV exportieren…", () -> onExportCsv());
  }

  private void mkButton(Composite parent, String text, OnClick listener)
  {
    Button b = new Button(parent, SWT.PUSH);
    b.setText(text);
    b.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    b.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { listener.onClick(); }
    });
  }

  private void refreshChipTable()
  {
    if (chipTable == null || chipTable.isDisposed()) return;
    chipTable.removeAll();
    List<String[]> rows = store.rows();
    for (String[] row : rows)
    {
      TableItem ti = new TableItem(chipTable, SWT.NONE);
      ti.setText(0, row[0]);
      ti.setText(1, row[1]);
    }
  }

  private void onAddChip()
  {
    String[] vals = ChipEditDialog.open(Display.getDefault().getActiveShell(), "Chip hinzufügen", "", "");
    if (vals == null) return;
    try { store.put(vals[0], vals[1]); store.save(); refreshChipTable(); }
    catch (Exception e) { showError("Hinzufügen fehlgeschlagen", e.getMessage()); }
  }

  private void onEditChip()
  {
    int idx = chipTable.getSelectionIndex();
    if (idx < 0) return;
    TableItem ti = chipTable.getItem(idx);
    String oldChip = ti.getText(0);
    String oldCard = ti.getText(1);
    String[] vals = ChipEditDialog.open(Display.getDefault().getActiveShell(), "Chip bearbeiten", oldChip, oldCard);
    if (vals == null) return;
    try
    {
      if (!vals[0].equals(oldChip)) store.removeByChip(oldChip);
      store.put(vals[0], vals[1]);
      store.save();
      refreshChipTable();
    }
    catch (Exception e) { showError("Bearbeiten fehlgeschlagen", e.getMessage()); }
  }

  private void onDeleteChip()
  {
    int idx = chipTable.getSelectionIndex();
    if (idx < 0) return;
    TableItem ti = chipTable.getItem(idx);
    String chip = ti.getText(0);
    if (!confirm("Löschen", "Chip-Eintrag '" + chip + "' wirklich löschen?")) return;
    try { store.removeByChip(chip); store.save(); refreshChipTable(); }
    catch (Exception e) { showError("Löschen fehlgeschlagen", e.getMessage()); }
  }

  private void onImportCsv()
  {
    FileDialog fd = new FileDialog(Display.getDefault().getActiveShell(), SWT.OPEN);
    fd.setText("Chip-CSV importieren");
    fd.setFilterExtensions(new String[] { "*.csv", "*.*" });
    String path = fd.open();
    if (path == null) return;
    boolean overwrite = confirm("Import-Modus",
        "Bestehende Chips überschreiben?\n\n"
        + "Ja  = Eintrag wird überschrieben falls Chip bereits existiert\n"
        + "Nein = bestehende Einträge bleiben, nur neue werden hinzugefügt");
    try
    {
      int[] r = store.importCsv(new File(path), overwrite);
      refreshChipTable();
      showInfo("Import abgeschlossen", "Hinzugefügt: " + r[0] + "\nAktualisiert: " + r[1] + "\nÜbersprungen: " + r[2]);
    }
    catch (Exception e) { showError("Import fehlgeschlagen", e.getMessage()); }
  }

  private void onExportCsv()
  {
    FileDialog fd = new FileDialog(Display.getDefault().getActiveShell(), SWT.SAVE);
    fd.setText("Chip-CSV exportieren");
    fd.setFileName("chip_kartennummer.csv");
    fd.setFilterExtensions(new String[] { "*.csv", "*.*" });
    String path = fd.open();
    if (path == null) return;
    try { store.exportCsv(new File(path)); showInfo("Export abgeschlossen", store.size() + " Einträge geschrieben nach\n" + path); }
    catch (Exception e) { showError("Export fehlgeschlagen", e.getMessage()); }
  }

  private void showError(String title, String message)
  {
    MessageBox box = new MessageBox(Display.getDefault().getActiveShell(), SWT.ICON_ERROR | SWT.OK);
    box.setText(title); box.setMessage(message); box.open();
  }

  private void showInfo(String title, String message)
  {
    MessageBox box = new MessageBox(Display.getDefault().getActiveShell(), SWT.ICON_INFORMATION | SWT.OK);
    box.setText(title); box.setMessage(message); box.open();
  }

  // =========================================================== Users tab

  private void buildUsersTab(Settings settings) throws Exception
  {
    TabGroup tab = new TabGroup(settings.getTabFolder(), "Hikvision Benutzer");
    Composite c = tab.getComposite();

    Label info = new Label(c, SWT.WRAP);
    info.setText("Diff-Übersicht: was würde der nächste Sync (jverein → Hikvision) tun? "
        + "Filter unten links wählt die Aktion (alle / nur neu / nur geändert / nur löschen / "
        + "nur unverwaltete Hikvision-Einträge / nur bereits in sync).");
    GridData infoGd = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    infoGd.widthHint = 800;
    info.setLayoutData(infoGd);

    Composite btnRow = new Composite(c, SWT.NONE);
    GridData brGd = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    btnRow.setLayoutData(brGd);
    btnRow.setLayout(new GridLayout(5, false));

    refreshButton = new Button(btnRow, SWT.PUSH);
    refreshButton.setText("Aktualisieren");
    refreshButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    refreshButton.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onRefreshUsers(); }
    });

    testButton = new Button(btnRow, SWT.PUSH);
    testButton.setText("Test Verbindung");
    testButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    testButton.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onTestConnection(); }
    });

    new Label(btnRow, SWT.NONE).setText("Filter:");
    filterCombo = new org.eclipse.swt.widgets.Combo(btnRow, SWT.READ_ONLY | SWT.DROP_DOWN);
    filterCombo.setItems(new String[] {
        "Alle", "Nur neu (CREATE)", "Nur geändert (UPDATE)", "Nur löschen (DELETE)",
        "Nur unverwaltet (HIK_ONLY)", "Nur in sync (OK)" });
    filterCombo.select(0);
    filterCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    filterCombo.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { renderPlanRows(); }
    });

    usersCount = new Label(btnRow, SWT.NONE);
    usersCount.setText("(noch nicht abgerufen)");
    usersCount.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    usersProgress = new org.eclipse.swt.widgets.ProgressBar(c, SWT.HORIZONTAL | SWT.SMOOTH);
    usersProgress.setMinimum(0); usersProgress.setMaximum(100);
    usersProgress.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

    usersTable = new Table(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    usersTable.setHeaderVisible(true);
    usersTable.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    tgd.heightHint = 400; tgd.widthHint = 1000;
    usersTable.setLayoutData(tgd);

    String[][] cols = {
        { "Status", "90" }, { "employeeNo", "100" }, { "Name", "170" },
        { "Typ", "70" }, { "Gruppe", "100" },
        { "Karten ist", "150" }, { "Karten soll", "150" }, { "Hinweis", "260" }
    };
    for (String[] col : cols)
    {
      TableColumn tc = new TableColumn(usersTable, SWT.LEFT);
      tc.setText(col[0]); tc.setWidth(Integer.parseInt(col[1]));
    }

    // Render from disk cache immediately so the tab isn't blank on open —
    // no Hikvision call until the user clicks Aktualisieren.
    loadFromCache();
  }

  private void loadFromCache()
  {
    PlanCache.Cached cached = PlanCache.load();
    if (cached == null || cached.plan == null)
    {
      if (usersCount != null && !usersCount.isDisposed())
        usersCount.setText("(noch nicht abgerufen — bitte 'Aktualisieren' klicken)");
      return;
    }
    currentPlanRows = cached.plan.rows;
    String age = formatAge(cached.timestamp);
    String summary = cached.plan.rows.size() + " Einträge — "
        + cached.plan.create + " neu, " + cached.plan.update + " geändert, "
        + cached.plan.delete + " löschen, " + cached.plan.hikOnly + " unverwaltet, "
        + cached.plan.ok + " in sync"
        + (cached.plan.unknownCards > 0 ? "  (⚠ " + cached.plan.unknownCards + " unbekannte Chips)" : "")
        + "  · letzter Abruf: " + age;
    if (usersCount != null && !usersCount.isDisposed()) usersCount.setText(summary);
    renderPlanRows();
  }

  private static String formatAge(long ts)
  {
    if (ts <= 0) return "?";
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
    String stamp = sdf.format(new java.util.Date(ts));
    long mins = (System.currentTimeMillis() - ts) / 60000L;
    if (mins < 1) return stamp + " (gerade eben)";
    if (mins < 60) return stamp + " (vor " + mins + " Min)";
    long hrs = mins / 60;
    if (hrs < 48) return stamp + " (vor " + hrs + " Std)";
    return stamp + " (vor " + (hrs / 24) + " Tagen)";
  }

  private void onTestConnection()
  {
    testButton.setEnabled(false);
    Thread t = new Thread(() -> {
      try
      {
        storeConfig();
        HikvisionClient client = new HikvisionClient(
            HikvisionSettings.getControllerUrl(), HikvisionSettings.getControllerUser(),
            HikvisionSettings.getControllerPassword(), HikvisionSettings.getInterCallPauseMs());
        String xml = client.getDeviceInfoXml();
        String model = xtract(xml, "model");
        String fw = xtract(xml, "firmwareVersion");
        String sn = xtract(xml, "serialNumber");
        Display.getDefault().asyncExec(() -> showInfo("Verbindung OK",
            "Model: " + model + "\nFirmware: " + fw + "\nSerial: " + sn));
      }
      catch (Exception e)
      {
        Logger.error("Test connection failed", e);
        Display.getDefault().asyncExec(() -> showError("Verbindung fehlgeschlagen",
            e.getClass().getSimpleName() + ": " + e.getMessage()));
      }
      finally
      {
        Display.getDefault().asyncExec(() -> {
          if (testButton != null && !testButton.isDisposed()) testButton.setEnabled(true);
        });
      }
    }, "jverein.hikvision-test");
    t.setDaemon(true);
    t.start();
  }

  private static String xtract(String xml, String tag)
  {
    String open = "<" + tag + ">", close = "</" + tag + ">";
    int a = xml.indexOf(open);
    if (a < 0) return "(?)";
    int b = xml.indexOf(close, a + open.length());
    if (b < 0) return "(?)";
    return xml.substring(a + open.length(), b);
  }

  private void onRefreshUsers()
  {
    refreshButton.setEnabled(false);
    testButton.setEnabled(false);
    Display.getDefault().asyncExec(() -> {
      if (usersCount != null && !usersCount.isDisposed()) usersCount.setText("lädt …");
      if (usersTable != null && !usersTable.isDisposed()) usersTable.removeAll();
      if (usersProgress != null && !usersProgress.isDisposed())
      { usersProgress.setMaximum(100); usersProgress.setSelection(0); }
    });

    Application.getController().start(new HikvisionBackgroundTask()
    {
      @Override
      public void run(ProgressMonitor monitor) throws ApplicationException
      {
        try
        {
          monitor.setStatusText("Hikvision Aktualisierung läuft …");
          storeConfig();
          ChipStore chipStore = ChipStore.defaultStore();
          HikvisionClient client = new HikvisionClient(
              HikvisionSettings.getControllerUrl(), HikvisionSettings.getControllerUser(),
              HikvisionSettings.getControllerPassword(), HikvisionSettings.getInterCallPauseMs());

          SyncEngine.Plan plan = SyncEngine.computePlan(chipStore, client, usersTabListener(this, monitor));

          currentPlanRows = plan.rows;
          final String summary = plan.rows.size() + " Einträge — "
              + plan.create + " neu, " + plan.update + " geändert, " + plan.delete + " löschen, "
              + plan.hikOnly + " unverwaltet, " + plan.ok + " in sync"
              + (plan.unknownCards > 0 ? "  (⚠ " + plan.unknownCards + " unbekannte Chips)" : "")
              + "  · letzter Abruf: gerade eben";

          Display.getDefault().asyncExec(() -> {
            if (usersCount != null && !usersCount.isDisposed()) usersCount.setText(summary);
            renderPlanRows();
          });
          monitor.setStatus(ProgressMonitor.STATUS_DONE);
          monitor.setPercentComplete(100);
        }
        catch (java.io.InterruptedIOException ie)
        {
          Logger.info("user refresh cancelled by user");
          Display.getDefault().asyncExec(() -> {
            if (usersCount != null && !usersCount.isDisposed())
              usersCount.setText("Abgebrochen: " + ie.getMessage());
          });
          monitor.log("Abgebrochen: " + ie.getMessage());
          monitor.setStatus(ProgressMonitor.STATUS_CANCEL);
        }
        catch (Exception e)
        {
          Logger.error("user refresh failed", e);
          Display.getDefault().asyncExec(() -> {
            if (usersCount != null && !usersCount.isDisposed()) usersCount.setText("Fehler");
            showError("Aktualisieren fehlgeschlagen", e.getClass().getSimpleName() + ": " + e.getMessage());
          });
          monitor.log("FEHLER: " + e.getMessage());
          monitor.setStatus(ProgressMonitor.STATUS_ERROR);
          throw new ApplicationException(e.getMessage(), e);
        }
        finally
        {
          Display.getDefault().asyncExec(() -> {
            if (refreshButton != null && !refreshButton.isDisposed()) refreshButton.setEnabled(true);
            if (testButton != null && !testButton.isDisposed()) testButton.setEnabled(true);
          });
        }
      }
    });
  }

  /** Listener that drives Jameica's status bar AND the Benutzer-tab ProgressBar. */
  private SyncEngine.ProgressListener usersTabListener(BackgroundTask task, ProgressMonitor mon)
  {
    return new SyncEngine.ProgressListener()
    {
      @Override public void log(String msg) { if (mon != null) mon.log(msg); Logger.info(msg); }
      @Override public void progress(int done, int total) { progress(done, total, ""); }
      @Override public void progress(int done, int total, String phase)
      {
        if (mon != null)
        {
          int pct = total > 0 ? (int) (100L * Math.min(done, total) / total) : 0;
          mon.setPercentComplete(pct);
          mon.setStatusText(phase + "  " + done + " / " + total);
        }
        Display.getDefault().asyncExec(() -> {
          if (usersProgress != null && !usersProgress.isDisposed())
          {
            int safeTotal = Math.max(total, 1);
            usersProgress.setMaximum(safeTotal);
            usersProgress.setSelection(Math.min(done, safeTotal));
          }
          if (usersCount != null && !usersCount.isDisposed())
            usersCount.setText(phase + "  " + done + " / " + total + " …");
        });
      }
      @Override public boolean isCancelled() { return task != null && task.isInterrupted(); }
    };
  }

  /** Re-render the table from {@link #currentPlanRows} applying the filter dropdown. */
  private void renderPlanRows()
  {
    if (usersTable == null || usersTable.isDisposed()) return;
    usersTable.removeAll();
    SyncEngine.Status wanted = null;
    int sel = filterCombo == null ? 0 : filterCombo.getSelectionIndex();
    switch (sel)
    {
      case 1: wanted = SyncEngine.Status.CREATE;   break;
      case 2: wanted = SyncEngine.Status.UPDATE;   break;
      case 3: wanted = SyncEngine.Status.DELETE;   break;
      case 4: wanted = SyncEngine.Status.HIK_ONLY; break;
      case 5: wanted = SyncEngine.Status.OK;       break;
      default: wanted = null;                       break;
    }
    int shown = 0;
    for (SyncEngine.PlanRow r : currentPlanRows)
    {
      if (wanted != null && r.status != wanted) continue;
      TableItem ti = new TableItem(usersTable, SWT.NONE);
      ti.setText(0, statusLabel(r.status));
      ti.setText(1, r.employeeNo == null ? "" : r.employeeNo);
      ti.setText(2, r.name == null ? "" : r.name);
      ti.setText(3, r.userType == null ? "" : r.userType);
      ti.setText(4, r.groupName == null ? "" : r.groupName);
      ti.setText(5, String.join(",", r.currentCards));
      ti.setText(6, String.join(",", r.desiredCards));
      ti.setText(7, r.detail == null ? "" : r.detail);
      shown++;
    }
    if (usersCount != null && !usersCount.isDisposed() && wanted != null)
    {
      String txt = usersCount.getText();
      // append filtered count without losing the summary
      int splitIdx = txt.indexOf("  (");
      if (splitIdx > 0) txt = txt.substring(0, splitIdx);
      usersCount.setText(txt + "  [Filter: " + shown + " sichtbar]");
    }
  }

  private static String statusLabel(SyncEngine.Status s)
  {
    if (s == null) return "?";
    switch (s)
    {
      case OK: return "OK";
      case CREATE: return "NEU";
      case UPDATE: return "GEÄNDERT";
      case DELETE: return "LÖSCHEN";
      case HIK_ONLY: return "HIK-ONLY";
    }
    return s.name();
  }
}
