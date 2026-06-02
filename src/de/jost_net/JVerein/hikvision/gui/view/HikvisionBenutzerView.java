package de.jost_net.JVerein.hikvision.gui.view;

import java.io.InterruptedIOException;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import de.jost_net.JVerein.hikvision.ChipStore;
import de.jost_net.JVerein.hikvision.HikvisionClient;
import de.jost_net.JVerein.hikvision.HikvisionSettings;
import de.jost_net.JVerein.hikvision.PlanCache;
import de.jost_net.JVerein.hikvision.SyncEngine;
import de.jost_net.JVerein.hikvision.ext.HikvisionBackgroundTask;
import de.willuhn.jameica.gui.AbstractView;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.jameica.system.Application;
import de.willuhn.jameica.system.BackgroundTask;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.ProgressMonitor;

/**
 * OpenJVerein > Hikvision > Benutzer
 *
 * Diff preview of the next jverein → Hikvision sync. Loads from the
 * persistent {@link PlanCache} on open so no controller call happens
 * on UI interactions. Filter dropdown narrows by status. Sync and
 * Import buttons live here (the action belongs with the view that
 * shows what it will do).
 */
public class HikvisionBenutzerView extends AbstractView
{
  private Table table;
  private Label countLabel;
  private Combo filterCombo;
  private ProgressBar progress;
  private Text logArea;
  private Button refreshBtn, syncBtn, importBtn;
  private org.eclipse.swt.widgets.Button dryRunCheckbox;
  private java.util.List<SyncEngine.PlanRow> currentRows = java.util.Collections.emptyList();

  @Override
  public void bind() throws Exception
  {
    GUI.getView().setTitle("Zugangssystem Benutzer");
    Composite parent = getParent();
    parent.setLayout(new GridLayout(2, false));
    Composite c = parent;

    Label info = new Label(c, SWT.WRAP);
    info.setText("Diff-Übersicht: was würde der nächste Sync (jverein → Hikvision) tun? "
        + "Filter unten links wählt die Aktion. Letzter Stand wird aus dem lokalen Cache geladen — "
        + "klicke Aktualisieren um den Hikvision-Controller neu abzufragen.");
    GridData infoGd = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    infoGd.widthHint = 800;
    info.setLayoutData(infoGd);

    // --- toolbar (refresh / test / filter / count) ---
    Composite toolbar = new Composite(c, SWT.NONE);
    toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    toolbar.setLayout(new GridLayout(4, false));

    refreshBtn = new Button(toolbar, SWT.PUSH);
    refreshBtn.setText("Aktualisieren");
    refreshBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onRefresh(); }
    });

    new Label(toolbar, SWT.NONE).setText("Filter:");
    filterCombo = new Combo(toolbar, SWT.READ_ONLY | SWT.DROP_DOWN);
    filterCombo.setItems(new String[] {
        "Alle", "Nur neu (CREATE)", "Nur geändert (UPDATE)", "Nur löschen (DELETE)",
        "Nur unverwaltet (HIK_ONLY)", "Nur in sync (OK)" });
    filterCombo.select(0);
    filterCombo.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { renderRows(); }
    });

    countLabel = new Label(toolbar, SWT.NONE);
    countLabel.setText("(noch nicht abgerufen)");
    countLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    progress = new ProgressBar(c, SWT.HORIZONTAL | SWT.SMOOTH);
    progress.setMinimum(0); progress.setMaximum(100);
    progress.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

    // --- table ---
    table = new Table(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    table.setHeaderVisible(true);
    table.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    tgd.heightHint = 400; tgd.widthHint = 1000;
    table.setLayoutData(tgd);
    String[][] cols = {
        { "Status", "90" }, { "employeeNo", "100" }, { "Name", "170" },
        { "Typ", "70" }, { "Gruppe", "100" },
        { "Karten ist", "150" }, { "Karten soll", "150" }, { "Hinweis", "260" } };
    for (String[] col : cols)
    {
      TableColumn tc = new TableColumn(table, SWT.LEFT);
      tc.setText(col[0]); tc.setWidth(Integer.parseInt(col[1]));
    }
    TableSorter.install(table);

    // --- action row: dry-run + Sync + Import ---
    Composite actionRow = new Composite(c, SWT.NONE);
    actionRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    actionRow.setLayout(new GridLayout(3, false));

    dryRunCheckbox = new org.eclipse.swt.widgets.Button(actionRow, SWT.CHECK);
    dryRunCheckbox.setText("Trockenlauf");
    dryRunCheckbox.setToolTipText("Wenn aktiv: nur loggen was passieren würde, keine Schreibvorgänge auf Hikvision oder jverein.");
    dryRunCheckbox.setSelection(HikvisionSettings.getDryRun());
    dryRunCheckbox.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e)
      { HikvisionSettings.setDryRun(dryRunCheckbox.getSelection()); }
    });
    dryRunCheckbox.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

    syncBtn = new Button(actionRow, SWT.PUSH);
    syncBtn.setText("Jetzt synchronisieren (jverein → Hikvision)");
    syncBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    syncBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onSync(); }
    });

    importBtn = new Button(actionRow, SWT.PUSH);
    importBtn.setText("Aus Hikvision importieren (überschreibt jverein-Transponder!)");
    importBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    importBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onImport(); }
    });

    // --- log area ---
    logArea = new Text(c, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
    GridData lgd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    lgd.heightHint = 180;
    logArea.setLayoutData(lgd);

    loadCachedPlan();
  }

  // ============================================================ cache + render

  private void loadCachedPlan()
  {
    PlanCache.Cached cached = PlanCache.load();
    if (cached == null || cached.plan == null)
    {
      countLabel.setText("(noch nicht abgerufen — bitte 'Aktualisieren' klicken)");
      return;
    }
    currentRows = cached.plan.rows;
    countLabel.setText(summaryFor(cached.plan) + "  · letzter Abruf: " + formatAge(cached.timestamp));
    renderRows();
  }

  private String summaryFor(SyncEngine.Plan p)
  {
    return p.rows.size() + " Einträge — " + p.create + " neu, " + p.update
        + " geändert, " + p.delete + " löschen, " + p.hikOnly + " unverwaltet, " + p.ok + " in sync"
        + (p.unknownCards > 0 ? "  (⚠ " + p.unknownCards + " unbekannte Transponder)" : "");
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

  private void renderRows()
  {
    if (table == null || table.isDisposed()) return;
    table.removeAll();
    SyncEngine.Status wanted;
    switch (filterCombo.getSelectionIndex())
    {
      case 1: wanted = SyncEngine.Status.CREATE; break;
      case 2: wanted = SyncEngine.Status.UPDATE; break;
      case 3: wanted = SyncEngine.Status.DELETE; break;
      case 4: wanted = SyncEngine.Status.HIK_ONLY; break;
      case 5: wanted = SyncEngine.Status.OK; break;
      default: wanted = null;
    }
    for (SyncEngine.PlanRow r : currentRows)
    {
      if (wanted != null && r.status != wanted) continue;
      TableItem ti = new TableItem(table, SWT.NONE);
      ti.setText(0, statusLabel(r.status));
      ti.setText(1, r.employeeNo == null ? "" : r.employeeNo);
      ti.setText(2, r.name == null ? "" : r.name);
      ti.setText(3, r.userType == null ? "" : r.userType);
      ti.setText(4, r.groupName == null ? "" : r.groupName);
      ti.setText(5, String.join(",", r.currentCards));
      ti.setText(6, String.join(",", r.desiredCards));
      ti.setText(7, r.detail == null ? "" : r.detail);
    }
    // Preserve the user's column sort across filter / refresh — without this
    // the table header keeps the ↑/↓ indicator but the rows are unsorted.
    TableSorter.reapplyIfSorted(table);
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

  // ============================================================ actions

  private void onRefresh()
  {
    final boolean dry = dryRunCheckbox.getSelection();
    startTask("Zugangssystem Aktualisierung", dry, (task, mon) -> {
      ChipStore chips = ChipStore.defaultStore();
      HikvisionClient client = new HikvisionClient(
          HikvisionSettings.getControllerUrl(), HikvisionSettings.getControllerUser(),
          HikvisionSettings.getControllerPassword(), HikvisionSettings.getInterCallPauseMs(),
          HikvisionSettings.getVerifySsl());
      SyncEngine.Plan plan = SyncEngine.computePlan(chips, client, listener(task, mon));
      currentRows = plan.rows;
      Display.getDefault().asyncExec(() -> {
        if (countLabel != null && !countLabel.isDisposed())
          countLabel.setText(summaryFor(plan) + "  · letzter Abruf: gerade eben");
        renderRows();
      });
    });
  }

  private void onSync()
  {
    final boolean dry = dryRunCheckbox.getSelection();   // UI thread — capture before submitting
    startTask("Zugangssystem Sync", dry, (task, mon) -> {
      SyncEngine.Result r = SyncEngine.run(dry, listener(task, mon));
      log("\nFertig (Sync). created=" + r.created + " deleted=" + r.deleted
          + " cardsAdded=" + r.cardsAdded + " cardsRemoved=" + r.cardsRemoved
          + " errors=" + r.errors.size() + "\n");
      // refresh cache view post-sync
      Display.getDefault().asyncExec(this::loadCachedPlan);
    });
  }

  private void onImport()
  {
    final boolean dry = dryRunCheckbox.getSelection();   // UI thread — capture before submitting
    if (!dry && !confirm("Aus Hikvision importieren",
        "Dieser Vorgang überschreibt die transponder-Zusatzfelder aller passenden jverein-Mitglieder "
        + "mit den Werten aus dem Zutrittssystem. Wirklich fortfahren?"))
      return;
    startTask("Zugangssystem Import", dry, (task, mon) -> {
      SyncEngine.ImportResult r = SyncEngine.importFromHikvision(dry, listener(task, mon));
      log("\nFertig (Import). updated=" + r.membersUpdated + " unchanged=" + r.membersUnchanged
          + " hikUnmatched=" + r.hikvisionUsersUnmatched + " errors=" + r.errors.size() + "\n");
    });
  }

  // ============================================================ task plumbing

  @FunctionalInterface
  private interface Body { void run(BackgroundTask task, ProgressMonitor mon) throws Exception; }

  private void startTask(String name, boolean dryRun, Body body)
  {
    setActionsEnabled(false);
    log("");
    log(name + " gestartet (" + (dryRun ? "Trockenlauf" : "APPLY") + ") …\n");
    if (progress != null && !progress.isDisposed()) { progress.setMaximum(100); progress.setSelection(0); }
    Application.getController().start(new HikvisionBackgroundTask() {
      @Override public void run(ProgressMonitor mon) throws ApplicationException
      {
        try
        {
          mon.setStatusText(name + " läuft …");
          HikvisionSettings.SETTINGS.toString();  // ensure plugin Settings class is loaded
          body.run(this, mon);
          mon.setStatus(ProgressMonitor.STATUS_DONE);
          mon.setPercentComplete(100);
        }
        catch (InterruptedIOException ie)
        {
          Logger.info(name + " cancelled by user");
          log("\nABGEBROCHEN: " + ie.getMessage() + "\n");
          mon.log("Abgebrochen: " + ie.getMessage());
          mon.setStatus(ProgressMonitor.STATUS_CANCEL);
        }
        catch (Exception e)
        {
          Logger.error(name + " failed", e);
          log("\nFEHLER: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
          mon.log("FEHLER: " + e.getMessage());
          mon.setStatus(ProgressMonitor.STATUS_ERROR);
          throw new ApplicationException(e.getMessage(), e);
        }
        finally
        {
          Display.getDefault().asyncExec(() -> setActionsEnabled(true));
        }
      }
    });
  }

  private de.jost_net.JVerein.hikvision.ProgressListener listener(BackgroundTask task, ProgressMonitor mon)
  {
    return new de.jost_net.JVerein.hikvision.ProgressListener() {
      @Override public void log(String msg)
      {
        if (mon != null) mon.log(msg);
        HikvisionBenutzerView.this.log(msg + "\n");
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
          if (progress != null && !progress.isDisposed())
          {
            int safe = Math.max(total, 1);
            progress.setMaximum(safe); progress.setSelection(Math.min(done, safe));
          }
          if (countLabel != null && !countLabel.isDisposed())
            countLabel.setText(phase + "  " + done + " / " + total + " …");
        });
      }
      @Override public boolean isCancelled() { return task != null && task.isInterrupted(); }
    };
  }

  private void setActionsEnabled(boolean en)
  {
    if (refreshBtn != null && !refreshBtn.isDisposed()) refreshBtn.setEnabled(en);
    if (syncBtn != null && !syncBtn.isDisposed()) syncBtn.setEnabled(en);
    if (importBtn != null && !importBtn.isDisposed()) importBtn.setEnabled(en);
  }

  private void log(String s)
  {
    Display.getDefault().asyncExec(() -> {
      if (logArea != null && !logArea.isDisposed())
      {
        if (s.isEmpty()) logArea.setText("");
        else logArea.append(s);
      }
    });
  }

  private boolean confirm(String title, String msg)
  {
    MessageBox b = new MessageBox(Display.getDefault().getActiveShell(), SWT.ICON_WARNING | SWT.YES | SWT.NO);
    b.setText(title); b.setMessage(msg); return b.open() == SWT.YES;
  }
  private void error(String title, String msg)
  { MessageBox b = new MessageBox(Display.getDefault().getActiveShell(), SWT.ICON_ERROR | SWT.OK);
    b.setText(title); b.setMessage(msg); b.open(); }
  private void info(String title, String msg)
  { MessageBox b = new MessageBox(Display.getDefault().getActiveShell(), SWT.ICON_INFORMATION | SWT.OK);
    b.setText(title); b.setMessage(msg); b.open(); }
}
