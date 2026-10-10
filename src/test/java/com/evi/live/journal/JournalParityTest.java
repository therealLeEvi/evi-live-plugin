package com.evi.live.journal;

import static com.evi.live.journal.ParityJson.check;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every PUBLIC golden transcript (synthetic, recorded from the real bridge by the project's own
 * recorder) replayed through the Java journal engine, with every journal-owned
 * answer compared with what the bridge said. See {@link TranscriptDriver} for exactly what is compared.
 */
public final class JournalParityTest {
  public static void main(String[] args) throws Exception {
    String index = ParityJson.readText("/parity/transcripts/index.txt");
    check(index != null, "parity/transcripts/index.txt is missing from the test resources");
    List<String> diffs = new ArrayList<>();
    Set<String> locks = new LinkedHashSet<>();
    int fixtures = 0, steps = 0, compared = 0;
    for (String name : index.split("\n")) {
      if (name.isBlank()) continue;
      JsonElement root = ParityJson.read("/parity/transcripts/" + name.trim() + ".json.gz");
      check(root != null, "listed fixture " + name + " is missing");
      JsonObject t = root.getAsJsonObject();
      check("evi-golden-transcript/1".equals(t.get("format").getAsString()), name + ": unexpected format");
      check("public".equals(t.get("tier").getAsString()), name + ": a non-public fixture in the public resources");
      JsonObject boot = t.getAsJsonObject("input").getAsJsonObject("boot");
      check(ParityJson.isNull(boot.get("journal")), name + ": a public fixture must not carry a journal");
      locks.add(t.getAsJsonObject("engineLock").get("combined").getAsString());
      TranscriptDriver d = new TranscriptDriver(name);
      d.boot(null, boot.get("preferences"));
      d.run(t.getAsJsonArray("steps"));
      diffs.addAll(d.diffs);
      fixtures++;
      steps += t.getAsJsonArray("steps").size();
      compared += d.compared;
    }
    if (!diffs.isEmpty()) {
      StringBuilder sb = new StringBuilder(diffs.size() + " parity divergence(s) from the JS bridge:");
      for (String d : diffs.subList(0, Math.min(40, diffs.size()))) sb.append("\n  ").append(d);
      throw new AssertionError(sb.toString());
    }
    // Inert-test guard: the four checks that shipped silently inert all passed a green suite.
    check(fixtures >= 34 && compared >= 280, "too little compared: " + fixtures + " fixtures, " + compared + " comparisons");
    check(locks.size() == 1, "public fixtures were recorded under different engines: " + locks);
    System.out.println("PASS: journal parity with the JS bridge over " + fixtures + " public golden transcripts (" + steps + " steps, "
      + compared + " compared answers: packet ingest and refusals, full /api/state bodies, buy-limit usage, not-held, personal use, profit reset and every reply's profit line, bridge restarts through the Java line codec), engine lock "
      + locks.iterator().next().substring(0, 16));
  }
}
