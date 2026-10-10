package com.evi.live.engine;

/** The bridge's recorded per-item Wiki series as the engine's {@link Engine.SellSeries}, for tests outside this package
 *  (the in-process engine's parity run). Parses exactly as {@link EngineTranscriptTest#series} does. */
public final class RecordedSeries {
  private RecordedSeries() {}

  public interface Bodies {
    String body(int itemId);
  }

  public static Engine.SellSeries of(Bodies bodies) {
    return itemId -> EngineTranscriptTest.series(bodies.body(itemId));
  }
}
