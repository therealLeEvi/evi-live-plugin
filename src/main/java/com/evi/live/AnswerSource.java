package com.evi.live;

import com.evi.live.inprocess.InProcessEngine;

/**
 * Where the suggestion poll gets its answer: the plugin's own engine, {@link InProcessTransport}, in production. The one seam
 * the poll path reads through, so a test can hand it a recorded answer body without an engine. Nothing here makes a request
 * of any kind: the plugin's only network traffic is {@code com.evi.live.market.WikiPriceClient}'s, to the OSRS Wiki.
 */
interface AnswerSource {
  /**
   * The answer body (the JSON the poll path parses) for this query, or null when there is none yet or none at all.
   * {@code query} is the URL-style query string the plugin builds from its own settings and session state, "" for none.
   */
  String get(String query);

  /** Why the last {@link #get} returned what it did: {@link InProcessTransport#PENDING} while still working, 200 with a body. */
  default int lastGetStatus() { return 0; }

  /** The engine's own answer behind the last {@link #get} (its price-history line, why there is no body, when the prices were
   *  fetched), or null for a source that has none (a test's recorded body). */
  default InProcessEngine.Answer lastAnswer() { return null; }
}
