package de.jost_net.JVerein.hikvision.ext;

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
import org.eclipse.swt.widgets.Text;

/**
 * Minimal two-field SWT dialog. Returns {chip, kartennummer} on OK,
 * {@code null} on cancel.
 */
public final class ChipEditDialog
{
  private ChipEditDialog() {}

  public static String[] open(Shell parent, String title, String initialChip, String initialCard)
  {
    Shell sh = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
    sh.setText(title);
    sh.setLayout(new GridLayout(2, false));

    new Label(sh, SWT.NONE).setText("Chip");
    Text chip = new Text(sh, SWT.BORDER);
    chip.setText(initialChip);
    chip.setLayoutData(gd(220));

    new Label(sh, SWT.NONE).setText("Kartennummer");
    Text card = new Text(sh, SWT.BORDER);
    card.setText(initialCard);
    card.setLayoutData(gd(220));

    Composite btnRow = new Composite(sh, SWT.NONE);
    GridData rowGd = new GridData(SWT.RIGHT, SWT.CENTER, true, false, 2, 1);
    btnRow.setLayoutData(rowGd);
    btnRow.setLayout(new GridLayout(2, true));
    Button ok = new Button(btnRow, SWT.PUSH);
    ok.setText("OK");
    ok.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Button cancel = new Button(btnRow, SWT.PUSH);
    cancel.setText("Abbrechen");
    cancel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    final String[] result = new String[1 + 2];
    result[0] = null;   // sentinel: cancel

    ok.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e)
      {
        result[0] = "ok";
        result[1] = chip.getText().trim();
        result[2] = card.getText().trim();
        sh.close();
      }
    });
    cancel.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { sh.close(); }
    });

    sh.pack();
    sh.open();
    Display d = sh.getDisplay();
    while (!sh.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }

    if (result[0] == null) return null;
    return new String[] { result[1], result[2] };
  }

  private static GridData gd(int width)
  {
    GridData g = new GridData(SWT.FILL, SWT.CENTER, true, false);
    g.widthHint = width;
    return g;
  }
}
