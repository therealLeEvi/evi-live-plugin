package com.evi.live.inprocess;

import java.util.concurrent.CompletableFuture;

/** What answers a poll's query: the {@link InProcessEngine}, or a test's stand-in. Returns at once; the future carries the answer
 *  (null when a newer query replaced this one or the engine has stopped). */
public interface Answerer {
  CompletableFuture<InProcessEngine.Answer> request(String query, long at);
}
