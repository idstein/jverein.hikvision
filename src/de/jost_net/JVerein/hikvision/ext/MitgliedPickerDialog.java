package de.jost_net.JVerein.hikvision.ext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import de.jost_net.JVerein.Einstellungen;
import de.jost_net.JVerein.hikvision.Identity;
import de.jost_net.JVerein.hikvision.MitgliedAssignments;
import de.jost_net.JVerein.rmi.Mitglied;
import de.willuhn.datasource.rmi.DBIterator;

/**
 * Modal Mitglieder picker for opening an {@link AssignmentEditDialog} on
 * a Mitglied that doesn't yet appear in the Benutzer plan view (typically
 * a brand-new active member without any chip yet).
 *
 * Shows all jverein members with: name, externe number, jvId, employeeNo,
 * and a flag whether they already have a MitgliedAssignments entry. Live
 * text filter narrows by any of those columns.
 */
public final class MitgliedPickerDialog
{
  /** What gets returned to the caller. */
  public static class Picked
  {
    public final String jvId;
    public final String displayName;
    public final String employeeNo;
    public final String externe;
    Picked(String jvId, String name, String emp, String ext)
    { this.jvId = jvId; this.displayName = name; this.employeeNo = emp; this.externe = ext; }
  }

  private static class Row
  {
    String jvId, name, externe, employeeNo;
    boolean hasAssignment;
    boolean visible = true;
  }

  private MitgliedPickerDialog() {}

  public static Picked open(Shell parent) throws Exception
  {
    Shell sh = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
    sh.setText("Mitglied wählen");
    sh.setLayout(new GridLayout(2, false));

    MitgliedAssignments asn = MitgliedAssignments.load();

    List<Row> rows = new ArrayList<>();
    DBIterator<Mitglied> it = Einstellungen.getDBService().createList(Mitglied.class);
    while (it.hasNext())
    {
      Mitglied m = (Mitglied) it.next();
      if (m.getAustritt() != null) continue;     // active members only
      Row r = new Row();
      r.jvId = m.getID();
      String vn = m.getVorname() == null ? "" : m.getVorname().trim();
      String nn = m.getName() == null ? "" : m.getName().trim();
      r.name = (vn + " " + nn).trim();
      r.externe = m.getExterneMitgliedsnummer() == null ? "" : m.getExterneMitgliedsnummer().trim();
      try { r.employeeNo = Identity.of(m).employeeNo; }
      catch (Exception ignore) { r.employeeNo = ""; }
      r.hasAssignment = asn.get(r.jvId) != null;
      rows.add(r);
    }
    rows.sort(Comparator.comparing((Row r) -> r.name, String.CASE_INSENSITIVE_ORDER));

    new Label(sh, SWT.NONE).setText("Suche");
    Text searchField = new Text(sh, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
    searchField.setMessage("Name, externe Mitgliedsnummer, jv_id …");
    GridData sgd = new GridData(SWT.FILL, SWT.CENTER, true, false);
    sgd.widthHint = 560;
    searchField.setLayoutData(sgd);

    Table table = new Table(sh, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
    table.setHeaderVisible(true); table.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    tgd.heightHint = 360; tgd.widthHint = 700;
    table.setLayoutData(tgd);
    TableColumn n1 = new TableColumn(table, SWT.LEFT); n1.setText("Name");        n1.setWidth(220);
    TableColumn n2 = new TableColumn(table, SWT.LEFT); n2.setText("Externe Nr."); n2.setWidth(100);
    TableColumn n3 = new TableColumn(table, SWT.LEFT); n3.setText("jv_id");        n3.setWidth(80);
    TableColumn n4 = new TableColumn(table, SWT.LEFT); n4.setText("employeeNo");   n4.setWidth(100);
    TableColumn n5 = new TableColumn(table, SWT.LEFT); n5.setText("Zuweisung");    n5.setWidth(180);

    Runnable refresh = () -> {
      table.removeAll();
      for (Row r : rows)
      {
        if (!r.visible) continue;
        TableItem ti = new TableItem(table, SWT.NONE);
        ti.setText(0, r.name);
        ti.setText(1, r.externe);
        ti.setText(2, r.jvId);
        ti.setText(3, r.employeeNo);
        ti.setText(4, r.hasAssignment ? "vorhanden — wird überschrieben" : "noch keine — neu");
        ti.setData(r);
      }
    };
    refresh.run();

    searchField.addModifyListener(e -> {
      String q = searchField.getText().trim().toLowerCase();
      for (Row r : rows)
      {
        if (q.isEmpty()) { r.visible = true; continue; }
        String h = (r.name + " " + r.externe + " " + r.jvId + " " + r.employeeNo).toLowerCase();
        r.visible = h.contains(q);
      }
      refresh.run();
    });

    Composite btnRow = new Composite(sh, SWT.NONE);
    btnRow.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false, 2, 1));
    btnRow.setLayout(new GridLayout(2, true));
    Button okBtn = new Button(btnRow, SWT.PUSH);
    okBtn.setText("Auswählen");
    okBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Button cancelBtn = new Button(btnRow, SWT.PUSH);
    cancelBtn.setText("Abbrechen");
    cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    final Picked[] result = { null };
    Runnable pick = () -> {
      int idx = table.getSelectionIndex();
      if (idx < 0) return;
      Row r = (Row) table.getItem(idx).getData();
      result[0] = new Picked(r.jvId, r.name, r.employeeNo, r.externe);
      sh.close();
    };
    okBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { pick.run(); }
    });
    table.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetDefaultSelected(SelectionEvent e) { pick.run(); }
    });
    cancelBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { sh.close(); }
    });

    sh.pack(); sh.open();
    Display d = sh.getDisplay();
    while (!sh.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
    return result[0];
  }
}
