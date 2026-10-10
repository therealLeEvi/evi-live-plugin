package com.evi.live;

import com.evi.live.inprocess.Answerer;
import com.evi.live.inprocess.InProcessEngine;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * The plugin's own engine behind the poll's one seam ({@link AnswerSource}), so the poll path, the DTOs and the panel read one
 * answer shape. It makes no request of any kind.
 *
 * <p>{@link #get} hands the query to the engine's own thread ({@link InProcessEngine}, "evi-engine") and waits at most
 * {@link #WAIT_MS} for the answer, on the poll's thread -- never the client thread, never unbounded, and well inside the
 * poll's two seconds. An answer that takes longer is still delivered: the next poll for the same query is given it at once
 * if it is under {@link #REUSE_MS} old. Until one exists the status is {@link #PENDING}, and the plugin leaves the sidebar as
 * it is rather than blanking it.
 *
 * <p>The sidebar's other actions (Block, Personal use, Gone, I took this one, Reset profit) do not pass through here: the plugin
 * keeps each one itself (BlockedItems, the plugin journal's marks, SuggestionRecords, the profit reset key; see EviLivePlugin).
 */
final class InProcessTransport implements AnswerSource {
  /** The longest a poll waits for the engine before leaving the sidebar as it is. */
  static final long WAIT_MS = 750;
  /** How old a finished answer for the same query may be and still be shown. */
  static final long REUSE_MS = 10_000;
  /** {@link #lastGetStatus()} while the engine has not answered this query yet: the sidebar is left as it is. */
  static final int PENDING = -2;
  /** {@link #lastGetStatus()} when the engine answered with no body: {@link #lastAnswer()} says why. */
  static final int UNAVAILABLE = 503;

  private final Answerer engine;
  private final LongSupplier clock;
  private volatile int lastStatus;
  /** The newest finished answer (any thread writes it), kept for {@link #REUSE_MS}. */
  private volatile InProcessEngine.Answer last;
  private volatile long lastAt;
  /** The answer the last {@link #get} returned, written only by the poll thread that called it. */
  private volatile InProcessEngine.Answer used;

  InProcessTransport(Answerer engine, LongSupplier clock) {
    this.engine = engine;
    this.clock = clock;
  }

  @Override public String get(String query) {
    String q = query == null ? "" : query;
    long now = clock.getAsLong();
    CompletableFuture<InProcessEngine.Answer> f = engine.request(q, now);
    InProcessEngine.Answer a = null;
    try {
      a = f.get(WAIT_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      // still working: its answer is kept when it lands, for the next poll -- timed from its ARRIVAL, or an answer slower than
      // REUSE_MS would be stale on landing and never shown ("Waiting for prices" stuck after a slow start; review, 10 Oct)
      f.thenAccept(done -> remember(done, clock.getAsLong()));
    } catch (InterruptedException e) {
      a = null; // nothing in EVI interrupts the poll thread; treated as "still working"
    } catch (ExecutionException e) {
      a = null;
    }
    if (a != null) remember(a, now);
    else {
      InProcessEngine.Answer prev = last;
      if (prev != null && prev.query.equals(q) && now - lastAt < REUSE_MS) a = prev;
    }
    if (a == null) {
      lastStatus = PENDING;
      return null;
    }
    used = a;
    lastStatus = a.body == null ? UNAVAILABLE : 200;
    return a.body;
  }

  private void remember(InProcessEngine.Answer a, long at) {
    if (a == null) return;
    last = a;
    lastAt = at;
  }

  @Override public int lastGetStatus() {
    return lastStatus;
  }

  /** The answer the last {@link #get} returned (its price-history line, why there is no body, when the prices were fetched),
   *  or null before any. Read on the same poll thread, right after {@link #get}. */
  @Override public InProcessEngine.Answer lastAnswer() {
    return used;
  }
}
