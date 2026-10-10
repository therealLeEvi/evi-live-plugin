package com.evi.live.engine;

import com.evi.live.journal.Offer;
import com.evi.live.market.HourBucket;
import java.util.Collection;
import java.util.List;

/**
 * The player fill model as server.mjs's {@code playerFillModel} keeps it: rebuilt AT MOST HOURLY, from every journal offer
 * watched from placement (ANY account's -- the maintainer, 7 Oct: the fill model is the one deliberate exception to account scoping,
 * a timing estimate learned from more history), over the archived hours those offers fall in. Never fatal: a failed build
 * is remembered as "no model" for the hour, and the suggestion simply carries no fill sentence.
 *
 * <p>The cache is part of the behaviour, not an optimisation: a model built at one poll answers every poll for the next hour
 * even after new offers finish, exactly as the bridge's does. {@link Engine#decide} asks for it LAZILY, only for a BUY pick,
 * at the point the bridge does, so the hour starts when the bridge's would. Owned by the engine thread; not thread-safe.
 */
public final class FillModelCache implements Engine.FillModelSource {
  /** {@code (from) -> readArchive(dir, from)}: the archived hours from {@code from} (seconds) onward, oldest first. */
  public interface ArchiveFrom {
    List<HourBucket> load(long fromTs) throws Exception;
  }

  private final ArchiveFrom archive;
  private FillModel.Model model;
  private long at;

  public FillModelCache(ArchiveFrom archive) {
    this.archive = archive;
  }

  @Override public FillModel.Model model(Collection<Offer> allAccountsJournalOffers, long now) {
    if (model != null && now - at < 3600000) return model;
    try {
      List<Offer> watched = SafetyChecks.watchedOffers(allAccountsJournalOffers);
      if (watched.isEmpty()) return null;
      long from = (long) SafetyChecks.fillModelFrom(watched);
      FillModel.Model built = FillModel.build(watched, archive.load(from), now);
      model = built;
      at = now;
      return built;
    } catch (Exception e) {
      model = null;
      at = now;
      return null;
    }
  }
}
