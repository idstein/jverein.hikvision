package de.jost_net.JVerein.hikvision;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;

/**
 * Chip ↔ Kartennummer mapping store, backed by a JSON file in Jameica's
 * config area ({@code ~/.jameica/cfg/de.jost_net.JVerein.hikvision.Chips.json}).
 * Picked up by Jameica's data backup, no external CSV file to maintain.
 *
 * Chip ids are opaque strings — numeric ({@code "1"}, {@code "604"}) or
 * non-numeric ({@code "Armband1"}).
 *
 * Thread-safety: all mutating methods synchronize on the instance. The
 * sync engine reads through {@link #cardForChip}/{@link #chipForCard}
 * which are also synchronized.
 */
public class ChipStore
{
  private final File backing;
  private final LinkedHashMap<String, String> chipToCard = new LinkedHashMap<>();
  private final LinkedHashMap<String, String> cardToChip = new LinkedHashMap<>();

  public ChipStore(File backing) throws IOException
  {
    this.backing = backing;
    load();
  }

  public static ChipStore defaultStore() throws IOException
  {
    String workDir = Application.getPluginLoader().getPlugin(Plugin.class).getResources().getWorkPath();
    File f = new File(workDir, "Chips.json");
    return new ChipStore(f);
  }

  public synchronized void load() throws IOException
  {
    chipToCard.clear();
    cardToChip.clear();
    if (!backing.exists()) return;
    String raw = new String(Files.readAllBytes(backing.toPath()), StandardCharsets.UTF_8).trim();
    if (raw.isEmpty()) return;
    JSONArray arr = new JSONArray(raw);
    for (int i = 0; i < arr.length(); i++)
    {
      JSONObject o = arr.getJSONObject(i);
      String chip = o.optString("chip", "").trim();
      String card = o.optString("kartennummer", "").trim();
      if (chip.isEmpty() || card.isEmpty()) continue;
      chipToCard.put(chip, card);
      cardToChip.put(card, chip);
    }
    Logger.info("ChipStore loaded " + chipToCard.size() + " entries from " + backing);
  }

  /** Atomic save: write to .tmp, then move into place. */
  public synchronized void save() throws IOException
  {
    JSONArray arr = new JSONArray();
    for (Map.Entry<String, String> e : chipToCard.entrySet())
    {
      arr.put(new JSONObject().put("chip", e.getKey()).put("kartennummer", e.getValue()));
    }
    File tmp = new File(backing.getParentFile(), backing.getName() + ".tmp");
    try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp, StandardCharsets.UTF_8)))
    {
      w.write(arr.toString(2));
    }
    Files.move(tmp.toPath(), backing.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  public synchronized String cardForChip(String chip) { return chipToCard.get(chip); }
  public synchronized String chipForCard(String card) { return cardToChip.get(card); }
  public synchronized int size() { return chipToCard.size(); }

  public synchronized List<String[]> rows()
  {
    List<String[]> out = new ArrayList<>(chipToCard.size());
    for (Map.Entry<String, String> e : chipToCard.entrySet()) out.add(new String[] { e.getKey(), e.getValue() });
    return out;
  }

  /** Insert or update. Throws if the card is already mapped to a different chip. */
  public synchronized void put(String chip, String kartennummer) throws IllegalStateException
  {
    chip = chip == null ? "" : chip.trim();
    kartennummer = kartennummer == null ? "" : kartennummer.trim();
    if (chip.isEmpty() || kartennummer.isEmpty())
      throw new IllegalArgumentException("chip und Kartennummer dürfen nicht leer sein");
    String otherChip = cardToChip.get(kartennummer);
    if (otherChip != null && !otherChip.equals(chip))
      throw new IllegalStateException("Kartennummer " + kartennummer + " ist bereits Chip " + otherChip + " zugeordnet");
    String oldCard = chipToCard.put(chip, kartennummer);
    if (oldCard != null) cardToChip.remove(oldCard);
    cardToChip.put(kartennummer, chip);
  }

  public synchronized boolean removeByChip(String chip)
  {
    String card = chipToCard.remove(chip);
    if (card == null) return false;
    cardToChip.remove(card);
    return true;
  }

  // ----------------------------------------------------- CSV import/export

  /** Import from a Chip,Kartennummer CSV (skip header). Returns counts. */
  public synchronized int[] importCsv(File csv, boolean replaceExisting) throws IOException
  {
    int added = 0, updated = 0, skipped = 0;
    try (BufferedReader r = new BufferedReader(new FileReader(csv, StandardCharsets.UTF_8)))
    {
      String line; boolean first = true;
      while ((line = r.readLine()) != null)
      {
        if (first) { first = false; continue; }
        int c = line.indexOf(',');
        if (c < 0) { skipped++; continue; }
        String chip = line.substring(0, c).trim();
        String card = line.substring(c + 1).trim();
        if (chip.isEmpty() || card.isEmpty()) { skipped++; continue; }
        boolean exists = chipToCard.containsKey(chip);
        if (exists && !replaceExisting) { skipped++; continue; }
        try { put(chip, card); if (exists) updated++; else added++; }
        catch (IllegalStateException e) { skipped++; Logger.warn("CSV row übersprungen: " + e.getMessage()); }
      }
    }
    save();
    return new int[] { added, updated, skipped };
  }

  /** Export current entries to a CSV file (Chip,Kartennummer with header). */
  public synchronized void exportCsv(File csv) throws IOException
  {
    try (BufferedWriter w = new BufferedWriter(new FileWriter(csv, StandardCharsets.UTF_8)))
    {
      w.write("Chip,Kartennummer\n");
      for (Map.Entry<String, String> e : chipToCard.entrySet())
      {
        w.write(e.getKey()); w.write(','); w.write(e.getValue()); w.write('\n');
      }
    }
  }
}
