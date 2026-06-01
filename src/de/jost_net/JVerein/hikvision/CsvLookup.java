package de.jost_net.JVerein.hikvision;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads {@code chip_kartennummer.csv} (columns Chip,Kartennummer) and
 * provides bidirectional lookup. The "Chip" column may be either a numeric
 * chip id (1..N) or an "Armband{N}" entry — both treated as opaque strings.
 */
public class CsvLookup
{
  private final Map<String, String> chipToCard = new HashMap<>();
  private final Map<String, String> cardToChip = new HashMap<>();

  public CsvLookup(String path) throws IOException
  {
    try (BufferedReader r = new BufferedReader(new FileReader(path)))
    {
      String line; boolean first = true;
      while ((line = r.readLine()) != null)
      {
        if (first) { first = false; continue; }   // skip header
        int comma = line.indexOf(',');
        if (comma < 0) continue;
        String chip = line.substring(0, comma).trim();
        String card = line.substring(comma + 1).trim();
        if (chip.isEmpty() || card.isEmpty()) continue;
        chipToCard.put(chip, card);
        cardToChip.put(card, chip);
      }
    }
  }

  public String cardForChip(String chip) { return chipToCard.get(chip); }
  public String chipForCard(String card) { return cardToChip.get(card); }
  public int size() { return chipToCard.size(); }
}
