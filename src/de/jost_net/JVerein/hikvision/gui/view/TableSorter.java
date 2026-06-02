package de.jost_net.JVerein.hikvision.gui.view;

import java.util.Arrays;
import java.util.Comparator;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * Wires column-header click → sort the SWT Table in place. Tries numeric
 * comparison when both cells parse as numbers (so "Region 9" sorts before
 * "Region 10"), falls back to case-insensitive string compare otherwise.
 * Toggles direction on repeated clicks on the same column.
 */
public final class TableSorter
{
  private TableSorter() {}

  public static void install(Table table)
  {
    TableColumn[] cols = table.getColumns();
    for (int i = 0; i < cols.length; i++)
    {
      final int col = i;
      cols[i].addSelectionListener(new SelectionAdapter() {
        @Override public void widgetSelected(SelectionEvent e)
        {
          TableColumn sortCol = table.getColumn(col);
          int dir = table.getSortDirection();
          if (table.getSortColumn() == sortCol)
            dir = (dir == SWT.UP) ? SWT.DOWN : SWT.UP;
          else
            dir = SWT.UP;
          sort(table, col, dir);
          table.setSortColumn(sortCol);
          table.setSortDirection(dir);
        }
      });
    }
  }

  /**
   * Re-apply the table's currently set sort (sortColumn + sortDirection) to
   * the items it now holds. Call this after wiping + repopulating a table
   * so a previously active sort survives (filter changes, refreshes, etc.).
   * No-op if no sort is set.
   */
  public static void reapplyIfSorted(Table table)
  {
    TableColumn sortCol = table.getSortColumn();
    int dir = table.getSortDirection();
    if (sortCol == null || (dir != SWT.UP && dir != SWT.DOWN)) return;
    int col = -1;
    for (int i = 0; i < table.getColumnCount(); i++)
      if (table.getColumn(i) == sortCol) { col = i; break; }
    if (col < 0) return;
    sort(table, col, dir);
  }

  private static void sort(Table table, int col, int dir)
  {
    TableItem[] items = table.getItems();
    TableItem[] sorted = items.clone();
    Comparator<TableItem> cmp = (a, b) -> {
      String sa = a.getText(col), sb = b.getText(col);
      try { return Long.compare(Long.parseLong(sa.trim()), Long.parseLong(sb.trim())); }
      catch (NumberFormatException ignored) {}
      try { return Double.compare(Double.parseDouble(sa.trim()), Double.parseDouble(sb.trim())); }
      catch (NumberFormatException ignored) {}
      return sa.compareToIgnoreCase(sb);
    };
    if (dir == SWT.DOWN) cmp = cmp.reversed();
    Arrays.sort(sorted, cmp);

    // Snapshot the row content (TableItem can't be reordered in place — it must
    // be disposed + recreated for moves).
    int ncols = table.getColumnCount();
    String[][] data = new String[sorted.length][ncols];
    for (int r = 0; r < sorted.length; r++)
      for (int c = 0; c < ncols; c++)
        data[r][c] = sorted[r].getText(c);

    table.removeAll();
    for (String[] row : data)
    {
      TableItem ti = new TableItem(table, SWT.NONE);
      ti.setText(row);
    }
  }
}
