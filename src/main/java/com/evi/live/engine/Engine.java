package com.evi.live.engine;

import com.evi.live.journal.CostBasis;
import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.ImportedFlip;
import com.evi.live.journal.ManualFlip;
import com.evi.live.journal.Offer;
import com.evi.live.journal.Profit;
import com.evi.live.journal.StoreState;
import com.evi.live.journal.Tax;
import com.evi.live.market.HourBucket;
import com.evi.live.market.ItemCatalog;
import com.evi.live.market.LatestPrices;
import com.evi.live.market.MarketAggregates;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ONE SUGGESTION REQUEST, decided: the self-contained port of bridge/server.mjs's GET /api/suggestion handler -- the order
 * the tiers run in, the checks around each pick, and the answer the plugin draws. Pure: every input is handed in (the
 * plugin's request, ONE account's slice of the journal, the deliberately shared parts of it, the market snapshot, the
 * preferences, the clock), and nothing here reads a disk, the network or a clock of its own.
 *
 * <p>THE ORDER IS THE BRIDGE'S, step for step, because the order IS behaviour (a blocklist several chains share, a heldBack
 * cut to three after every chain has added to it; the reachable probe ranks on COPIES and touches none of it):
 * <ol>
 *   <li>the holding tier (the plugin's live signal), then the journal's persisted position for THIS account, then the
 *       opt-in idle-stock tier -- the sell side first, and never while the GE is full;</li>
 *   <li>the sidebar's Block button joins the blocklist only AFTER the sell-side tiers (stock already held still gets its
 *       sell reminder);</li>
 *   <li>the personal-history tier through the pick chain -- under Auto a chain that only HELD picks back runs on
 *       (the 7 Oct "Auto never goes silent" fix (1));</li>
 *   <li>"Best of both" sets a history BUY aside, then the market tier ({@code includeMarket=1}) with this account's spread
 *       rank, the "fell through from history" note, and -- when nothing passed a chosen floor -- the reachable probe down
 *       MinProfitTier's own rungs, through every check;</li>
 *   <li>"Best of both" compared on what each pick is really worth ({@code worthOf});</li>
 *   <li>the Auto step-down to the 500 gp floor, judged at that floor (the 7 Oct leftover fix (2)), running on past
 *       held-back picks like the first chain; its market half carries the account's spread rank (8 Oct fix 2);</li>
 *   <li>further positions on the cash still unspent: sized like the main pick (fix (3)), a SELL main pick spending none of
 *       it (fix (4)), ranked and judged at the 500 floor after a step-down (fix (5)), the market ones from the account's
 *       spread rank onward (8 Oct fix 2);</li>
 *   <li>the open offer's price and cost basis, the running offers' prices and fill estimates, the fill and fill-history
 *       sentences, the slot and members notes, the advice channel, and the headline enrichment.</li>
 * </ol>
 *
 * <p>ACCOUNT SCOPING IS STRUCTURAL. Everything this method knows about offers, lots and cost basis comes from the poll's
 * {@link AccountView}; the shared journal ({@link SharedJournal}) carries only what the bridge deliberately shares across
 * accounts (the history tier's flips, the personal-use marks, the profit line); and the fill model alone reads every
 * account's offers, passed separately and named for it.
 *
 * <p>SYNCHRONOUS BY CONSTRUCTION: every ranking and every hook answers directly ({@link PickChain}), so the 5 Oct class of
 * bug -- an un-awaited async rank on the step-down, the probe and the extra positions skipping four checks -- cannot exist.
 * The Wiki's per-item series for the sell-support reading is an injected function ({@link SellSeries}), so this stays pure.
 *
 * <p>THE RISK LEVELS ARE HIDDEN: the level a request names reaches the engine only through {@link LevelSettings}, which
 * holds TODAY's values (the market tier reads the same bars at every level); nothing here invents level behaviour.
 */
public final class Engine {
  private Engine() {}

  /** MinProfitTier's own rungs, highest first: the reachable probe only ever names a setting the player can pick. */
  private static final double[] REACHABLE_RUNGS = {2000000, 1000000, 500000, 200000, 100000, 1};
  /** MAX_POSITIONS: the most positions EVI suggests at once, whatever a caller asks for. */
  public static final int MAX_POSITIONS = 3;
  /** How many held-back rows the answer carries (the rest go to the suggestion log only). */
  public static final int HELD_BACK_SHOWN = 3;
  private static final String MEMBERS_NOTE = " Note: this is a members item, so it can only be traded on a members world -- not this one.";

  // ========================================================================================== the inputs

  /**
   * The plugin's request as the bridge reads its query string ({@code URLSearchParams}): '+' is a space, %XX sequences are
   * UTF-8 decoded (a malformed one is kept as written), and {@link #get} answers with the FIRST value of a repeated name.
   */
  public static final class Query {
    private final Map<String, String> first = new LinkedHashMap<>();

    public static Query parse(String query) {
      Query q = new Query();
      if (query == null) return q;
      String s = query.startsWith("?") ? query.substring(1) : query;
      for (String part : s.split("&", -1)) {
        if (part.isEmpty()) continue;
        int eq = part.indexOf('=');
        String k = decode(eq < 0 ? part : part.substring(0, eq));
        String v = eq < 0 ? "" : decode(part.substring(eq + 1));
        q.first.putIfAbsent(k, v);
      }
      return q;
    }

    /** The first value of {@code name}, or null when absent. */
    public String get(String name) {
      return first.get(name);
    }

    public boolean has(String name) {
      return first.containsKey(name);
    }

    private static int hex(char c) {
      return Character.digit(c, 16);
    }

    static String decode(String s) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '+') out.write(' ');
        else if (c == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
          out.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
          i += 2;
        } else {
          byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
          if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
            b = s.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
            i++;
          }
          out.write(b, 0, b.length);
        }
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  /**
   * The trading preferences the bridge read from preferences.json on every request ({@code readPreferences}), with its
   * defaults: the scanner's focus, the items blocked with the sidebar's Block button, the spread across the top candidates,
   * and the opt-in capture cap. (The profit line's reset time is the journal's: {@link SharedJournal#of}.)
   */
  public static final class Prefs {
    /** "any", "bulk" or "gear". */
    public final String focus;
    /** Never suggested to BUY again (sell reminders still speak). Positive whole ids, in the stored order. */
    public final List<Integer> blocked;
    /**
     * How many of the top market candidates accounts are spread across: a whole number CLAMPED to 1-10 (a huge value walks
     * off the end of a thin hour's ranking for no gain); anything that is not a safe whole number is the default, 5.
     */
    public final double spread;
    public final boolean captureCap;

    private static final double MAX_SAFE_INTEGER = 9007199254740991.0;
    public static final Prefs DEFAULT = new Prefs("any", Collections.emptyList(), 5, false);

    public Prefs(String focus, List<Integer> blocked, double spread, boolean captureCap) {
      this.focus = Focus.FOCUSES.contains(focus) ? focus : "any";
      this.blocked = Collections.unmodifiableList(new ArrayList<>(blocked == null ? Collections.emptyList() : blocked));
      this.spread = JsValues.isInteger(spread) && Math.abs(spread) <= MAX_SAFE_INTEGER ? Math.min(10, Math.max(1, spread)) : 5;
      this.captureCap = captureCap;
    }

    /**
     * readPreferences over a parsed preferences.json: an unknown focus is "any"; blocked keeps only safe whole numbers
     * above 0 (one past an item id's range is kept by the JS and matches no item, so it is simply dropped here); spread is
     * clamped to 1-10 when it is a safe whole number, else 5; captureCap only when it is literally {@code true}. Anything
     * that is not an object reads as the defaults.
     */
    public static Prefs fromJson(JsonElement e) {
      if (e == null || !e.isJsonObject()) return DEFAULT;
      JsonObject o = e.getAsJsonObject();
      JsonElement f = o.get("focus");
      String focus = f != null && f.isJsonPrimitive() && f.getAsJsonPrimitive().isString() ? f.getAsString() : null;
      List<Integer> blocked = new ArrayList<>();
      JsonElement b = o.get("blocked");
      if (b != null && b.isJsonArray())
        for (JsonElement x : b.getAsJsonArray()) {
          Double v = safeInteger(x);
          if (v != null && v > 0 && v <= Integer.MAX_VALUE) blocked.add((int) (double) v);
        }
      Double spread = safeInteger(o.get("spread"));
      JsonElement c = o.get("captureCap");
      boolean captureCap = c != null && c.isJsonPrimitive() && c.getAsJsonPrimitive().isBoolean() && c.getAsBoolean();
      return new Prefs(focus, blocked, spread == null ? Double.NaN : spread, captureCap);
    }

    private static Double safeInteger(JsonElement x) {
      if (x == null || !x.isJsonPrimitive() || !x.getAsJsonPrimitive().isNumber()) return null;
      double v = x.getAsDouble();
      return JsValues.isInteger(v) && Math.abs(v) <= MAX_SAFE_INTEGER ? v : null;
    }
  }

  /**
   * The parts of the journal the bridge DELIBERATELY shares across every account on the machine, and nothing else: the
   * personal-history tier's flips (the maintainer, 26 Sept: an item that flips reliably does so whoever places the offer), the
   * personal-use marks (they name items, not accounts), and the profit line ({@link Profit#sinceAllAccounts}). Everything
   * account-scoped comes from the {@link AccountView} instead.
   */
  public static final class SharedJournal {
    /** {@code [...state.flips, ...state.importedFlips]}, as the history tier reads them. */
    public final List<History.Flip> flips;
    /** Items behind a buy marked personal use: excluded from the idle-stock tier only, never from a new flip. */
    public final List<Integer> personalUseItemIds;
    /** How many of each item are kept FOR USE (ascending item id). */
    public final SortedMap<Integer, Long> personalUseKept;
    public final Profit profit;

    public SharedJournal(List<History.Flip> flips, List<Integer> personalUseItemIds, SortedMap<Integer, Long> personalUseKept, Profit profit) {
      this.flips = Collections.unmodifiableList(new ArrayList<>(flips));
      this.personalUseItemIds = Collections.unmodifiableList(new ArrayList<>(personalUseItemIds == null ? Collections.emptyList() : personalUseItemIds));
      this.personalUseKept = personalUseKept == null ? new TreeMap<>() : personalUseKept;
      this.profit = profit;
    }

    /** From one {@code store.state(now)}; {@code profitSince} is the player's last profit reset (null: never). */
    public static SharedJournal of(StoreState state, Long profitSince) {
      List<History.Flip> flips = new ArrayList<>();
      for (ManualFlip f : state.flips)
        flips.add(new History.Flip(Boolean.TRUE.equals(f.removed()), f.itemId, f.profit, f.quantity, f.hold, f.lastSell == null ? null : (double) f.lastSell,
          f.confirmedAt == null ? null : (double) f.confirmedAt, f.item));
      for (ImportedFlip f : state.importedFlips)
        flips.add(new History.Flip(false, f.itemId, f.profit, f.quantity, f.hold, (double) f.lastSell, null, f.item));
      return new SharedJournal(flips, state.personalUseItemIds, state.personalUseKept, Profit.sinceAllAccounts(state, profitSince));
    }
  }

  /** The player fill model, as the market layer caches it ({@link FillModelCache}); null for none. */
  public interface FillModelSource {
    FillModel.Model model(Collection<Offer> allAccountsJournalOffers, long now);
  }

  /**
   * The market as the bridge's caches held it when the request arrived: MARKET-WIDE by nature, the same for every account.
   * A null field is "not available", read exactly as the bridge reads a failed or missing fetch (see each).
   */
  public static final class MarketData {
    /** /latest. REQUIRED: without it the bridge answers 502 (the request fails, nothing is suggested). */
    public LatestPrices latest;
    /** The latest Wiki hour (/1h): null when it could not be fetched (sizing then has no live reading; the market tier fails). */
    public HourBucket latestHour;
    /** /mapping: null before the first one arrived (the gates, the idle-stock tier and the market tier then fail the request). */
    public ItemCatalog catalog;
    /** {@code typicalVolumesForRequest()}: the 168-hour typical volume per item; null before the first build (the live hour is used). */
    public Map<Integer, Long> typicalVolumes;
    /** {@code robustPricesCached()}: the 336-hour steady price per item; empty or null ranks on the live quote. */
    public Map<Integer, MarketAggregates.RobustPrice> rankPrices;
    /** {@code archive.recentHourly()}: the archive's last hours, for the sell-support reading (filtered to its window here). */
    public List<HourBucket> recentHours;
    /** {@code thinMarketIndex()}: null when the archive is too short to have one. */
    public ThinMarket.Index thin;
    /** The crash watch: null when none runs (no crash demotions, no crash notes). */
    public CrashWatch.Watch crashWatch;
    /** The player fill model: null for none (no fill sentence). */
    public FillModelSource fillModel;
  }

  /**
   * The Wiki's hourly per-item series for the sell-support reading, as server.mjs reads it ({@code JSON.parse(body).data
   * || []} in its own try): null when there is no series (the fetch failed, or a body JSON.parse refuses), an EMPTY list for
   * a body with no usable array, else the points (a JSON null point stays null: the bridge then has no reading at all).
   * Synchronous and outside the engine: the caller fetches, caches and parses.
   */
  public interface SellSeries {
    List<SellSupport.Point> series(int itemId) throws Exception;
  }

  /** The opt-in forecast (plan decision 13: none in the self-contained build). Null answer: no forecast for this item. */
  public interface ForecastHook {
    PickChain.Forecast forecastFor(int itemId, String horizon) throws Exception;
  }

  /** The opt-in margin cushion (off by default; measured to block every candidate). Null answer: no reading. */
  public interface CushionHook {
    PickChain.Check cushionFor(Pick candidate) throws Exception;
  }

  /**
   * The correlation gate (not ported: it needs more archive than the plugin keeps, AdviceNotes). {@code held} is every item
   * this account is committed to -- including positions this answer has already added -- except the candidate's own. Null:
   * no reading (no index, too little shared history), which never blocks.
   */
  public interface CorrelationHook {
    PickChain.Check against(Pick candidate, List<Integer> held) throws Exception;
  }

  /** The injected functions. Each may be null: no series hook means the ARCHIVE IS THE SERIES (the plugin; the reading then
   *  needs every archived hour from H-12 to H-2, {@link SellSupport#readingFromArchive}), and no forecast, cushion or
   *  correlation. */
  public static final class Hooks {
    public SellSeries sellSeries;
    public ForecastHook forecast;
    public CushionHook cushion;
    public CorrelationHook correlation;
  }

  // ========================================================================================== the answer

  /** One {@code heldBack} row: a held-back pick (sell support), or an item and why (correlation, "Best of both"). */
  public static final class HeldBack {
    public final Pick pick;
    public final int itemId;
    public final String reason;

    HeldBack(Pick pick) {
      this.pick = pick;
      this.itemId = pick.itemId;
      this.reason = null;
    }

    HeldBack(int itemId, String reason) {
      this.pick = null;
      this.itemId = itemId;
      this.reason = reason;
    }
  }

  /** One running offer's fill estimate ({@code slotFill}). The id is the number the plugin sent, which may name no item. */
  public static final class SlotFill {
    public final double itemId;
    public final long estimatedFillMinutes;
    public final boolean likelyToFillInTime;

    SlotFill(double itemId, long estimatedFillMinutes, boolean likelyToFillInTime) {
      this.itemId = itemId;
      this.estimatedFillMinutes = estimatedFillMinutes;
      this.likelyToFillInTime = likelyToFillInTime;
    }
  }

  /** {@code slots}: the GE's capacity on this poll, the sell reserve, and the items the bridge believes are held. */
  public static final class Slots {
    public final Integer free;
    public final Integer collectable;
    public final boolean full;
    public final boolean tight;
    public final int sellSlotsOwed;
    public final boolean buysHeldForExits;
    public final List<Integer> positionItems;

    Slots(GeSlots.Capacity c, int sellSlotsOwed, boolean buysHeldForExits, List<Integer> positionItems) {
      this.free = c.free;
      this.collectable = c.collectable;
      this.full = c.full;
      this.tight = c.tight;
      this.sellSlotsOwed = sellSlotsOwed;
      this.buysHeldForExits = buysHeldForExits;
      this.positionItems = Collections.unmodifiableList(positionItems);
    }
  }

  /** The best profit reachable at a lower MinProfitTier rung, when nothing passed the chosen floor. A statement, never advice. */
  public static final class Reachable {
    public final String name;
    public final int itemId;
    public final double profit;
    public final double atMinimum;

    Reachable(String name, int itemId, double profit, double atMinimum) {
      this.name = name;
      this.itemId = itemId;
      this.profit = profit;
      this.atMinimum = atMinimum;
    }
  }

  /** The player's own best history pick, named because the floor -- not an empty history -- passed it over. */
  public static final class FellThrough {
    public final String name;

    FellThrough(String name) {
      this.name = name;
    }
  }

  /**
   * The answer: the body of the bridge's GET /api/suggestion ({@link ResponseJson#response} writes it), plus two
   * diagnostics the suggestion log records and the plugin never sees. A failed request ({@link #error} set) carries
   * nothing else, as the bridge's 502 does.
   */
  public static final class Response {
    public final String error;
    public Pick suggestion;
    public final List<Pick> additional = new ArrayList<>();
    public ItemPrice openItemPrice;
    public final List<ItemPrice> slotPrices = new ArrayList<>();
    public final List<SlotFill> slotFill = new ArrayList<>();
    public List<AdviceNote> relistAdvice = new ArrayList<>();
    public Slots slots;
    public Profit profit;
    public Reachable reachable;
    public FellThrough fellThroughFromHistory;
    /** The first {@link #HELD_BACK_SHOWN} rows of {@link #heldBackAll}. */
    public List<HeldBack> heldBack = new ArrayList<>();
    /** LOG ONLY: every row held back on this poll. */
    public List<HeldBack> heldBackAll = new ArrayList<>();
    /** LOG ONLY: buy picks the sell-support check demoted on this poll, whichever pick ended up shown. */
    public final List<Pick> demotedPicks = new ArrayList<>();
    /**
     * NOT SENT: the bag the idle-stock tier read on this poll (server.mjs's lastInventory: set only when that tier was reached
     * with a non-empty inventory=, keyed by item id, the quantity as parseInt read it; an id past an item id's range counts as
     * a reading but names no item, so it is not in the map). Null when the tier did not read one. The plugin's "Yours, not
     * stock" mark takes its kept count from it, as the bridge's /personal-use route does.
     */
    public Map<Integer, Double> inventoryRead;
    /** LOG ONLY: the cash READING (not the cap), null when none was sent. */
    public Double cashReading;
    /** For a failed request that was not a missing input: what failed (never sent to the plugin). */
    public final RuntimeException cause;

    Response(String error, RuntimeException cause) {
      this.error = error;
      this.cause = cause;
    }

    public boolean failed() {
      return error != null;
    }
  }

  /** What the bridge's catch turns into a 502: a market input the request needs is missing. */
  static final class Unavailable extends RuntimeException {
    Unavailable(String message) {
      super(message);
    }
  }

  // ========================================================================================== decide

  /** A 502 still carries the bag the idle-stock tier read before the failure: server.mjs set lastInventory before it threw. */
  private static Response failed(Response r, Run run) {
    if (run != null && run.inventoryRead != null) r.inventoryRead = Collections.unmodifiableMap(run.inventoryRead);
    return r;
  }

  /**
   * One GET /api/suggestion.
   *
   * @param query the plugin's request ({@link Query#parse} of the very query text it sends the bridge)
   * @param view the asking account's slice of the journal ({@link AccountView#of} for {@code query.get("account")};
   *             {@link AccountView#nobody()} for a poll that names none) -- it must be the account the query names
   * @param journal the deliberately shared journal parts ({@link SharedJournal#of})
   * @param allAccountsJournalOffers EVERY account's journal offers ({@code store.offers()}): read by the fill model only
   * @param market the market snapshot
   * @param prefs the trading preferences
   * @param hooks the injected sell-support series (and the opt-in forecast, cushion and correlation)
   * @param now the request's clock, epoch ms
   * @return the answer; {@link Response#failed()} where the bridge would answer 502
   */
  public static Response decide(Query query, AccountView view, SharedJournal journal, Collection<Offer> allAccountsJournalOffers, MarketData market,
                                Prefs prefs, Hooks hooks, long now) {
    String account = emptyAsNull(query.get("account"));
    AccountView v = view == null ? AccountView.nobody() : view;
    if (!Objects.equals(v.account, account))
      throw new IllegalArgumentException("the account view (" + v.account + ") is not the account the request names (" + account + ")");
    Run run = null;
    try {
      run = new Run(query, v, journal, allAccountsJournalOffers, market == null ? new MarketData() : market, prefs == null ? Prefs.DEFAULT : prefs,
        hooks == null ? new Hooks() : hooks, now, account);
      return run.run();
    } catch (Unavailable e) {
      // server.mjs: } catch(e) {return send(502,{error:e.message});} -- a market input the request needs is missing.
      return failed(new Response(e.getMessage(), null), run);
    } catch (RuntimeException e) {
      // Any other failure the bridge would not have caught itself is also its 502; the cause is kept for the log and the
      // tests. An Error (an assertion, the JVM) is never an answer and propagates.
      return failed(new Response(e.toString(), e), run);
    }
  }

  /** One request's state, built in the bridge's order. */
  private static final class Run {
    final Query q;
    final AccountView view;
    final SharedJournal journal;
    final Collection<Offer> allOffers;
    final MarketData market;
    final Prefs prefs;
    final Hooks hooks;
    final long now;
    final String account;

    final List<HeldBack> heldBack = new ArrayList<>();
    final List<Pick> demotedPicks = new ArrayList<>();
    Map<Integer, Double> inventoryRead;
    LatestPrices latest;
    HourBucket volumes;
    double minProfit;
    boolean minProfitChosen;
    boolean requireMarginOverTax;
    Double maxSpend;
    Double target;
    String risk;
    LevelSettings level;
    String forecastHorizon;
    String forecastPolicy;
    boolean requireCushion;
    boolean taxFreeOnly;
    boolean bigger;
    double maxStackShare;
    Sizing.ItemGate gate = Sizing.ItemGate.NONE;
    Set<Integer> blocklist;
    Set<Integer> exposure;
    boolean includeMarket;
    String wantSource;
    MarketTier.Ranking marketRanking;

    Run(Query q, AccountView view, SharedJournal journal, Collection<Offer> allOffers, MarketData market, Prefs prefs, Hooks hooks, long now, String account) {
      this.q = q;
      this.view = view;
      this.journal = journal;
      this.allOffers = allOffers == null ? Collections.emptyList() : allOffers;
      this.market = market;
      this.prefs = prefs;
      this.hooks = hooks;
      this.now = now;
      this.account = account;
    }

    Response run() {
      latest = market.latest;
      if (latest == null) throw new Unavailable("no /latest prices");
      // suggestionPolicy
      double askedFor = Math.max(0, orZero(number(q.get("minProfit"))));
      minProfitChosen = askedFor > 0;
      double cashParam = QueryValues.cashFromQuery(q.get("cash"));
      minProfit = minProfitChosen ? askedFor : Policy.autoMinProfit(cashParam);
      requireMarginOverTax = askedFor != 1;
      blocklist = new LinkedHashSet<>();
      addItemIds(blocklist, q.get("blocklist"));
      addItemIds(blocklist, q.get("exclude"));
      String riskParam = q.get("risk");
      risk = "low".equals(riskParam) || "medium".equals(riskParam) || "high".equals(riskParam) ? riskParam : "low";
      level = LevelSettings.forRequest(risk);
      maxSpend = QueryValues.maxSpendFromQuery(q.get("cash"), q.get("account"));
      target = Sizing.duration(number(q.get("duration")));
      volumes = market.latestHour;
      String forecastParam = q.get("forecast");
      forecastHorizon = "1h".equals(forecastParam) || "6h".equals(forecastParam) || "overnight".equals(forecastParam) ? forecastParam : null;
      forecastPolicy = "skip".equals(q.get("onForecast")) ? "skip" : "warn";
      boolean freeToPlayWorld = "0".equals(q.get("members"));
      String focus = Focus.resolveFocus(q.get("focus"), prefs.focus);
      if (freeToPlayWorld || account != null || !"any".equals(focus)) gate = RequestGates.of(catalog(), freeToPlayWorld, focus, view, target, now);
      maxStackShare = QueryValues.maxStackShare(q.get("stackShare"));
      taxFreeOnly = "starter".equals(q.get("profile"));
      requireCushion = "1".equals(q.get("cushion"));
      bigger = "bigger".equals(q.get("sizing"));
      GeSlots.Capacity capacity = GeSlots.slotCapacity(q.get("freeSlots"), q.get("collectable"));
      boolean geFull = capacity.full;
      String sourceParam = q.get("source");
      wantSource = "market".equals(sourceParam) || "both".equals(sourceParam) ? sourceParam : "history";
      includeMarket = "1".equals(q.get("includeMarket"));
      boolean history = !"market".equals(wantSource);

      // The slot reserve: only lots the plugin CONFIRMED are in the inventory owe an exit.
      Set<Integer> confirmedHeld = new LinkedHashSet<>();
      addItemIds(confirmedHeld, q.get("heldPositions"));
      List<Integer> positionItems = view.positionItems();
      GeSlots.Exposure ex = view.slotExposure(confirmedHeld);
      exposure = new LinkedHashSet<>(ex.exposure);
      int sellSlotsOwed = ex.sellSlotsOwed;
      boolean buysHeldForExits = capacity.free != null && !geFull && capacity.free <= sellSlotsOwed;
      double holdBuyPriceParam = number(q.get("holdBuyPrice"));
      double holdBuyPrice = JsValues.isFinite(holdBuyPriceParam) && holdBuyPriceParam > 0 ? holdBuyPriceParam : Double.NaN;

      // --- the holding tier: the plugin's live signal, then the journal's open position for THIS account
      Pick suggestion = null;
      if (!geFull) suggestion = holdingOf(number(q.get("holdItemId")), number(q.get("holdQty")), emptyAsNull(q.get("holdName")),
        emptyAsNull(q.get("holdBuyId")), holdBuyPrice, null);
      if (suggestion == null && !geFull) {
        FifoMatcher.OpenPosition open = view.pickPersistentOpenPosition(blocklist);
        if (open != null) {
          suggestion = holdingOf(open.itemId, open.remaining, open.item, open.buyId, open.unitCost, open.endedUnseen);
          if (suggestion != null) suggestion.persisted = true;
        }
      }
      // --- the idle-stock tier (opt-in): stock in the bag with no observed buy behind it
      if (suggestion == null && !geFull && "1".equals(q.get("includeInventory"))) {
        TreeMap<Integer, Long> inventory = new TreeMap<>();
        Map<Integer, Double> read = new LinkedHashMap<>();
        boolean anyInventory = false;
        String raw = q.get("inventory");
        for (String pair : (raw == null ? "" : raw).split(",", -1)) {
          String[] parts = pair.split(":", -1);
          double id = parseInt(parts[0]), qty = parts.length > 1 ? parseInt(parts[1]) : Double.NaN;
          if (!(JsValues.isFinite(id) && id > 0 && JsValues.isFinite(qty) && qty > 0)) continue;
          anyInventory = true; // Object.keys(inventory).length counts an id past an item id's range too
          Integer item = itemIdOf(id);
          if (item != null) inventory.put(item, (long) qty); // past the range: no price, so never offered
          if (item != null) read.put(item, qty);
        }
        if (anyInventory) inventoryRead = read;
        if (anyInventory) {
          ItemCatalog catalog = catalog();
          HoldingTiers.InventoryOptions o = new HoldingTiers.InventoryOptions();
          Set<Integer> inventoryBlocklist = new LinkedHashSet<>(blocklist);
          inventoryBlocklist.addAll(journal.personalUseItemIds);
          o.blocklist = inventoryBlocklist;
          o.positionItemIds = view.positionItemsWithCost();
          o.membersBlocked = freeToPlayWorld ? gate::membersBlocked : null;
          o.keptForUse = journal.personalUseKept;
          suggestion = HoldingTiers.computeInventorySuggestion(latest, inventory, HoldingTiers.namesOf(catalog.items()), o);
        }
      }
      // Blocked with the sidebar's Block button: never BOUGHT again, added only AFTER the sell-side tiers above.
      blocklist.addAll(prefs.blocked);

      // --- the personal-history tier
      if (suggestion == null && !geFull && !buysHeldForExits && history)
        suggestion = historyChainUnderAuto(onBlocked -> chain(historyRank(minProfit, maxSpend), support(minProfit), blocklist, onBlocked, demotedPicks::add));
      // "Best of both": a history BUY is set aside to be compared with the market's pick, rather than ending the search.
      Pick historyPick = HoldingTiers.comparableAsHistoryPick(suggestion, wantSource) ? suggestion : null;
      if (historyPick != null) suggestion = null;
      Reachable reachable = null;
      FellThrough fellThrough = null;
      if (suggestion == null && !geFull && !buysHeldForExits && includeMarket) {
        MarketTier.Ranking ranking = ranking();
        // Its OWN copy of the blocklist: the chain adds to the set it is handed.
        Pick marketPick = PickChain.pick(chain(ranking.ranked(minProfit), support(minProfit), new LinkedHashSet<>(blocklist), this::holdBack,
          demotedPicks::add));
        suggestion = marketPick;
        // Say so when the floor, not an empty history, is why the player's own history was passed over.
        if (suggestion != null && !minProfitChosen) {
          try {
            Pick ownBest = HistoryTier.computeSuggestion(journal.flips, latest, now, historyOptions(0, blocklist, maxSpend));
            if (ownBest != null && ownBest.itemId != suggestion.itemId) fellThrough = new FellThrough(ownBest.name);
          } catch (Exception e) {
            // the bridge's catch: no note
          }
        }
        // Nothing to show AND a minimum set: the best figure reachable at a lower rung, through the SAME checks.
        if (suggestion == null && minProfit > 0) {
          try {
            reachable = reachableProbe(history);
          } catch (Exception e) {
            reachable = null;
          }
        }
      }
      // --- "Best of both": keep whichever pick is worth more at the price buyers are paying; ties go to the history.
      if (historyPick != null) {
        if (suggestion == null) suggestion = historyPick;
        else {
          Worth own = worthOf(historyPick), wide = worthOf(suggestion);
          if (own.value >= wide.value) {
            heldBack.add(new HeldBack(suggestion.itemId, "A market-wide pick worth about " + JsValues.gp(wide.value) + " gp, set aside for your own "
              + historyPick.name + " at about " + JsValues.gp(own.value) + " gp."));
            suggestion = historyPick;
          } else {
            heldBack.add(new HeldBack(historyPick.itemId, "Your own " + historyPick.name + " is worth about " + JsValues.gp(own.value)
              + " gp at the price buyers are paying, against " + JsValues.gp(wide.value) + " gp for this market-wide pick, so EVI set your history aside this time."));
          }
        }
      }
      // --- Auto never goes silent: the step-down to the 500 gp floor, every safety check intact and judged AT that floor
      boolean steppedDown = false;
      if (suggestion == null && !minProfitChosen && !geFull && !buysHeldForExits && minProfit > Policy.AUTO_MIN_PROFIT) {
        Pick stepped = null;
        if (history) {
          try {
            stepped = historyChainUnderAuto(onBlocked -> chain(historyRank(Policy.AUTO_MIN_PROFIT, maxSpend), support(Policy.AUTO_MIN_PROFIT), blocklist,
              onBlocked, demotedPicks::add));
          } catch (Exception e) {
            stepped = null;
          }
        }
        if (stepped == null && includeMarket) {
          try {
            stepped = PickChain.pick(chain(bl -> ranking().rankAt(bl, maxSpend, Policy.AUTO_MIN_PROFIT), support(Policy.AUTO_MIN_PROFIT), blocklist,
              this::holdBack, demotedPicks::add));
          } catch (Exception e) {
            stepped = null;
          }
        }
        if (stepped != null) {
          suggestion = stepped;
          steppedDown = true;
          suggestion.belowUsualBar = minProfit;
          suggestion.reasoning = (suggestion.reasoning != null && !suggestion.reasoning.isEmpty() ? suggestion.reasoning + " " : "")
            + "Smaller than usual: nothing reached the " + JsValues.gp(minProfit)
            + " gp Auto is aiming for with this cash stack, so this is the best trade available right now. Every safety check still applies to it.";
        }
      }
      // --- further positions, only when asked for: each ranked on the cash still unspent, through every check
      Response out = new Response(null, null);
      int wantPositions = positionsWanted(q.get("maxSuggestions"));
      if (suggestion != null && wantPositions > 1 && !geFull && !buysHeldForExits) {
        int slotsLeft = capacity.free == null ? wantPositions - 1 : Math.max(0, capacity.free - sellSlotsOwed - 1);
        // Only a BUY spends cash (fix (4)): a holding SELL carries the item's live low as its buyPrice.
        Double budget = maxSpend == null ? null : Math.max(0, maxSpend - (suggestion.isBuy() ? (double) suggestion.buyPrice * suggestion.quantity : 0));
        // Ranked AND judged at the main pick's minimum: the 500 floor after a step-down (fix (5) and the leftover fix (2)).
        double extrasMinimum = steppedDown ? Policy.AUTO_MIN_PROFIT : minProfit;
        Pick previous = suggestion;
        while (out.additional.size() < wantPositions - 1 && slotsLeft > 0) {
          // The position just taken is exposure now (correlation refuses its twin) and never picked again.
          exposure.add(previous.itemId);
          blocklist.add(previous.itemId);
          Double spend = budget;
          if (spend != null && !(spend > 0)) break;
          Pick next = null;
          // Sized like the main pick (fix (3)): the same typical-hour sizing, capture cap and window share.
          if (history) next = PickChain.pick(chain(historyRank(extrasMinimum, spend), support(extrasMinimum), blocklist, null, null));
          if (next == null && includeMarket)
            next = PickChain.pick(chain(bl -> ranking().rankAt(bl, spend, extrasMinimum), support(extrasMinimum), blocklist, null, null));
          if (next == null) break;
          out.additional.add(next);
          if (budget != null) budget = Math.max(0, budget - (double) next.buyPrice * next.quantity);
          slotsLeft--;
          previous = next;
        }
      }
      // --- the open offer's own price, with what this account paid for it
      double openItemId = number(q.get("openItemId"));
      ItemPrice openItemPrice = JsValues.isFinite(openItemId) ? Prices.lookupItemPrice(latest, openItemId) : null;
      if (openItemPrice != null) {
        double unitCost = Double.NaN, heldQty = 1;
        if (number(q.get("holdItemId")) == openItemId && JsValues.isFinite(holdBuyPrice)) {
          unitCost = holdBuyPrice;
          double hq = number(q.get("holdQty"));
          heldQty = Double.isNaN(hq) || hq == 0 ? 1 : hq; // Number(x) || 1
        } else {
          CostBasis basis = view.heldCostBasis(openItemPrice.itemId);
          if (basis != null) {
            unitCost = basis.unitCost;
            heldQty = basis.quantity;
          }
        }
        openItemPrice = Prices.withCostBasis(openItemPrice, unitCost, heldQty);
      }
      out.openItemPrice = openItemPrice;
      // --- the running offers' prices and, with a pace set, how long their remaining quantity typically takes
      String slots = q.get("slots");
      for (String pair : (slots == null ? "" : slots).split(",", -1)) {
        String[] parts = pair.split(":", -1);
        double id = parseInt(parts[0]), remaining = parts.length > 1 ? parseInt(parts[1]) : Double.NaN;
        if (!(JsValues.isFinite(id) && id > 0 && JsValues.isFinite(remaining) && remaining > 0)) continue;
        ItemPrice p = Prices.lookupItemPrice(latest, id);
        if (p != null) out.slotPrices.add(p);
        if (target != null) {
          Integer item = itemIdOf(id); // volumes[String(id)]: an id past the range has no row
          Sizing.OfferFill f = Sizing.estimateOfferFill(remaining, item == null ? null : VolumeRow.of(volumes, item), target);
          if (f != null) out.slotFill.add(new SlotFill(id, f.estimatedFillMinutes, f.likelyToFillInTime));
        }
      }
      // --- the player's own fill record for an order this size (EVERY account's offers: the one deliberate exception)
      if (suggestion != null && suggestion.isBuy()) {
        FillModel.Model model = market.fillModel == null ? null : market.fillModel.model(allOffers, now);
        suggestion.reasoning = SafetyChecks.fillSentence(suggestion.reasoning, model, suggestion, VolumeRow.of(volumes, suggestion.itemId), target);
      }
      String capacityNote = GeSlots.slotNote(capacity);
      if (suggestion != null && capacityNote != null) suggestion.reasoning = (suggestion.reasoning == null ? "" : suggestion.reasoning) + " " + capacityNote;
      if (suggestion != null && !suggestion.isBuy() && freeToPlayWorld && gate.membersBlocked(suggestion.itemId))
        suggestion.reasoning = (suggestion.reasoning == null ? "" : suggestion.reasoning) + MEMBERS_NOTE;
      // --- the fill history behind whichever buy pick survived, stated unless a warning already said it
      ThinMarket.Stats thinStats = null;
      if (suggestion != null && suggestion.isBuy()) {
        try {
          thinStats = market.thin == null ? null : market.thin.get(suggestion.itemId);
          suggestion.reasoning = SafetyChecks.withThinContext(suggestion.reasoning, thinStats, suggestion, target);
        } catch (Exception e) {
          // the bridge's catch: no statement (thinStats keeps whatever was read)
        }
      }
      // --- the advice channel, for THIS account only
      AdviceNotes.Inputs advice = new AdviceNotes.Inputs();
      advice.view = view;
      advice.marketLatest = latest;
      advice.targetDurationMinutes = target;
      Set<Integer> slow = new LinkedHashSet<>();
      for (SlotFill f : out.slotFill) {
        Integer id = itemIdOf(f.itemId);
        if (!f.likelyToFillInTime && id != null) slow.add(id);
      }
      advice.slowFillItemIds = slow;
      advice.suggestedItemId = suggestion == null ? null : suggestion.itemId;
      advice.crashWatch = market.crashWatch;
      advice.now = now;
      out.relistAdvice = AdviceNotes.compose(advice);
      // --- the headline enrichment, the primary with its fill-history reading and every extra position without one
      Verdict.enrich(suggestion, thinStats == null ? null : (int) thinStats.hoursTraded, thinStats == null ? null : (int) thinStats.hours);
      for (Pick a : out.additional) Verdict.enrich(a, null, null);

      out.suggestion = suggestion;
      out.slots = new Slots(capacity, sellSlotsOwed, buysHeldForExits, positionItems);
      out.profit = journal.profit;
      out.reachable = reachable;
      out.fellThroughFromHistory = fellThrough;
      out.heldBackAll = Collections.unmodifiableList(new ArrayList<>(heldBack));
      out.heldBack = Collections.unmodifiableList(new ArrayList<>(heldBack.subList(0, Math.min(HELD_BACK_SHOWN, heldBack.size()))));
      out.demotedPicks.addAll(demotedPicks);
      out.inventoryRead = inventoryRead == null ? null : Collections.unmodifiableMap(inventoryRead);
      out.cashReading = JsValues.isFinite(cashParam) && cashParam >= 0 ? cashParam : null;
      return out;
    }

    // ---------------------------------------------------------------------------------------------- the tiers' pieces

    ItemCatalog catalog() {
      if (market.catalog == null) throw new Unavailable("the item mapping is not available yet");
      return market.catalog;
    }

    /**
     * server.mjs's market rankers ({@code rank} and {@code marketRankAt}, both with this account's spread rank), built
     * once: they need the mapping and the latest hour, and without either the bridge's fetch would have failed.
     */
    MarketTier.Ranking ranking() {
      if (marketRanking != null) return marketRanking;
      ItemCatalog catalog = catalog();
      if (volumes == null) throw new Unavailable("the latest hour's volumes are not available");
      MarketTier.Options shared = new MarketTier.Options();
      shared.targetDurationMinutes = target == null ? Double.NaN : target;
      shared.now = now;
      shared.maxStackShare = maxStackShare;
      shared.taxFreeOnly = taxFreeOnly;
      shared.requireMarginOverTax = requireMarginOverTax;
      shared.rankPrices = market.rankPrices;
      shared.rankBy = level.marketRankBy;
      shared.typicalVolumes = market.typicalVolumes;
      shared.captureCap = prefs.captureCap;
      if (bigger) {
        shared.volumeWindowShare = Policy.BIGGER_POSITIONS_WINDOW_SHARE;
        shared.minVolumeInWindow = Policy.BIGGER_POSITIONS_MIN_VOLUME_IN_WINDOW;
      }
      shared.gate = gate;
      shared.level = level;
      marketRanking = new MarketTier.Ranking(catalog.items(), latest, volumes, shared, maxSpend, SpreadRank.spreadRankFor(account, prefs.spread));
      return marketRanking;
    }

    /** {@code onBlocked: rows => heldBack.push(...rows)}. */
    void holdBack(List<PickChain.Blocked> rows) {
      for (PickChain.Blocked b : rows) heldBack.add(row(b));
    }

    /** server.mjs's holdingOf: the reminder, unless that item is already listed by this account, and only if it should pre-empt. */
    Pick holdingOf(double holdItemId, double holdQty, String name, String buyId, double buyPrice, FifoMatcher.EndedUnseenMark endedUnseen) {
      if (!JsValues.isFinite(holdItemId) || holdItemId <= 0 || !JsValues.isFinite(holdQty) || holdQty <= 0) return null;
      Integer item = itemIdOf(holdItemId); // latestPrices[String(id)]: only a whole id in range names an item
      if (item == null) return null;
      Pick s = HoldingTiers.computeHoldingSuggestion(latest, item, JsValues.exactLong(holdQty), name, buyId, buyPrice, endedUnseen);
      if (s == null) return null;
      if (view.hasLiveSellOffer(s.itemId)) return null;
      return HoldingTiers.holdingPreempts(s, minProfit) ? s : null;
    }

    HistoryTier.Options historyOptions(double mp, Set<Integer> bl, Double spend) {
      HistoryTier.Options o = new HistoryTier.Options();
      o.minProfit = mp;
      o.blocklist = bl;
      o.risk = risk;
      o.maxSpend = spend == null ? Double.NaN : spend;
      o.targetDurationMinutes = target == null ? Double.NaN : target;
      o.volumes = volumes;
      o.maxStackShare = maxStackShare;
      o.typicalVolumes = market.typicalVolumes;
      o.captureCap = prefs.captureCap;
      if (bigger) o.volumeWindowShare = Policy.BIGGER_POSITIONS_WINDOW_SHARE;
      o.gate = gate;
      o.requireMarginOverTax = requireMarginOverTax;
      return o;
    }

    /** The history tier at a minimum and a budget, as a pick chain's ranking. */
    PickChain.Rank historyRank(double mp, Double spend) {
      return bl -> HistoryTier.computeSuggestion(journal.flips, latest, now, historyOptions(mp, bl, spend));
    }

    /** One pickWithForecast: the request's forecast, cushion and correlation, and sell support at {@code support}'s bar. */
    PickChain.Options chain(PickChain.Rank rank, PickChain.SupportHook support, Set<Integer> bl, Consumer<List<PickChain.Blocked>> onBlocked,
                            Consumer<Pick> onDemoted) {
      PickChain.Options c = new PickChain.Options();
      c.rank = rank;
      c.forecastFor = hooks.forecast == null ? null : itemId -> {
        try {
          return hooks.forecast.forecastFor(itemId, forecastHorizon);
        } catch (Exception e) {
          return null; // forecastForSuggestion never throws: no forecast for that one call
        }
      };
      c.policy = forecastPolicy;
      c.horizon = forecastHorizon;
      c.cushionFor = hooks.cushion == null ? null : cand -> {
        try {
          return hooks.cushion.cushionFor(cand);
        } catch (Exception e) {
          return null;
        }
      };
      c.requireCushion = requireCushion;
      c.correlationFor = hooks.correlation == null ? null : cand -> {
        try {
          if (cand == null || !cand.isBuy()) return null;
          List<Integer> held = new ArrayList<>();
          for (Integer id : exposure) if (id != cand.itemId) held.add(id);
          if (held.isEmpty()) return null;
          return hooks.correlation.against(cand, held);
        } catch (Exception e) {
          return null;
        }
      };
      c.supportFor = support;
      c.blocklist = bl;
      c.onBlocked = onBlocked;
      c.onDemoted = onDemoted;
      return c;
    }

    /**
     * historyChainUnderAuto (fix (1), 7 Oct): under Auto a history chain that HELD picks back and returned nothing runs
     * again -- the held-back items are already on the shared blocklist, so each pass ranks the next candidates -- until a
     * pass returns a pick or holds nothing back. A chosen minimum runs it once.
     */
    Pick historyChainUnderAuto(Function<Consumer<List<PickChain.Blocked>>, PickChain.Options> chain) {
      int[] held = {0};
      Consumer<List<PickChain.Blocked>> onBlocked = rows -> {
        holdBack(rows);
        held[0] += rows.size();
      };
      Pick pick = PickChain.pick(chain.apply(onBlocked));
      while (pick == null && !minProfitChosen && held[0] > 0) {
        held[0] = 0;
        pick = PickChain.pick(chain.apply(onBlocked));
      }
      return pick;
    }

    // ---------------------------------------------------------------------------------------------- sell support

    /** The archive's hours inside the sell-support window, oldest first ({@code archive.recentHourly(from)}). */
    List<HourBucket> archivedSince() {
      double from = Math.floor(now / 3600000.0) * 3600 - SellSupport.SELL_SUPPORT_HOURS * 3600;
      List<HourBucket> out = new ArrayList<>();
      if (market.recentHours != null) for (HourBucket b : market.recentHours) if (b != null && b.ts >= from) out.add(b);
      out.sort((a, b) -> Long.compare(a.ts, b.ts));
      return out;
    }

    /** The injected series in the bridge's own try: anything it throws is "no series". */
    List<SellSupport.Point> seriesFor(int itemId) {
      if (hooks.sellSeries == null) return null;
      try {
        return hooks.sellSeries.series(itemId);
      } catch (Exception e) {
        return null;
      }
    }

    /**
     * supportForSuggestion(candidate, rankedAt): a CRASHING pick is demoted with the crash alert (outside the fail-open
     * try, as in the bridge), then a THIN one with its note (in its own try), then the sell-support reading judged against
     * {@code rankedAt} -- the minimum the chain RANKS at (the request's own, or the 500 floor of the step-down and the
     * positions after it). Any failure of the reading is no reading.
     */
    PickChain.SupportHook support(double rankedAt) {
      return cand -> {
        CrashWatch.Crash crashing = market.crashWatch == null ? null : market.crashWatch.isCrashing(cand.itemId);
        if (crashing != null) return SafetyChecks.precheck(cand, crashing, null, target);
        try {
          SellSupport.Result thin = SafetyChecks.precheck(cand, null, market.thin, target);
          if (thin != null) return thin;
        } catch (Exception e) {
          // thinMarketIndex()'s own catch: no note
        }
        try {
          // No series hook at all (the plugin): the archive IS the series, read as the bridge's merge reads it. A hook (the
          // parity tests replaying the bridge's recorded series) keeps the bridge's own gate exactly.
          SellSupport.Detail detail = hooks.sellSeries == null
            ? SellSupport.readingFromArchive(archivedSince(), cand.itemId, cand.buyPrice, now)
            : SellSupport.reading(seriesFor(cand.itemId), archivedSince(), cand.itemId, cand.buyPrice, now);
          return SellSupport.judge(detail, cand, rankedAt, minProfitChosen, requireMarginOverTax, level);
        } catch (Exception e) {
          return null;
        }
      };
    }

    /** worthOf's answer: the value compared, the quoted spread, and the supported figure (rounded; null for no reading). */
    static final class Worth {
      final double value;
      final double quoted;
      final Double supported;

      Worth(double value, double quoted, Double supported) {
        this.value = value;
        this.quoted = quoted;
        this.supported = supported;
      }
    }

    /**
     * worthOf: what a candidate is really worth, for comparing one tier against another -- the margin at the price buyers
     * have paid over the last 12 hours, or the quoted spread without a reading. Its gate is the bridge's own and NOT the
     * sell-support check's: only a MISSING series needs the archive to cover the window (an empty one is read, with the
     * archive merged in) -- the 7 Oct fix (7) changed supportForSuggestion only.
     */
    Worth worthOf(Pick c) {
      double qty = c.quantity != 0 ? c.quantity : 1;
      double quoted = (double) (c.sellPrice - Tax.estimateUnitTax(c.itemId, c.sellPrice) - c.buyPrice) * qty;
      try {
        List<HourBucket> archived = archivedSince();
        if (hooks.sellSeries == null) { // the plugin: the archive is the series (SellSupport.readingFromArchive)
          SellSupport.Detail d = SellSupport.readingFromArchive(archived, c.itemId, c.buyPrice, now);
          if (d != null && d.netAtAverage != null && JsValues.isFinite(d.netAtAverage))
            return new Worth(d.netAtAverage * qty, quoted, JsValues.jsRound(d.netAtAverage * qty));
          return new Worth(quoted, quoted, null);
        }
        List<SellSupport.Point> series = seriesFor(c.itemId);
        if (series == null && archived.size() < SellSupport.SELL_SUPPORT_HOURS) return new Worth(quoted, quoted, null);
        if (series != null) for (SellSupport.Point p : series) if (p == null) return new Worth(quoted, quoted, null); // JS throws: caught
        SellSupport.Detail detail = SellSupport.sellPriceSupport(SellSupport.mergeArchiveHours(series == null ? new ArrayList<>() : series, archived, c.itemId),
          c.itemId, c.buyPrice, now);
        if (detail != null && detail.netAtAverage != null && JsValues.isFinite(detail.netAtAverage))
          return new Worth(detail.netAtAverage * qty, quoted, JsValues.jsRound(detail.netAtAverage * qty));
      } catch (Exception e) {
        // the bridge's catch: the quoted spread
      }
      return new Worth(quoted, quoted, null);
    }

    // ---------------------------------------------------------------------------------------------- the reachable probe

    /**
     * Nothing passed a chosen floor: probe DOWN MinProfitTier's own rungs below it and report the first rung that yields a
     * trade -- through the same checks as the real path at THAT rung (sell support judged at the rung, forecast, cushion,
     * correlation; three attempts each; the market with the account's spread rank), reporting nothing to heldBack. Every
     * pass ranks on its OWN COPY of the request's blocklist (8 Oct fix 1): the shared one is what the Auto step-down ranks
     * on next, and a candidate demoted at one rung must still be judged at a lower one. The figure must clear the rung it
     * is advertised at, at the price buyers are paying. A statement of what exists, never a suggestion.
     */
    Reachable reachableProbe(boolean history) {
      for (double rung : REACHABLE_RUNGS) {
        if (!(rung < minProfit)) continue;
        List<Pick> candidates = new ArrayList<>();
        if (history) {
          try {
            Pick own = PickChain.pick(chain(historyRank(rung, maxSpend), support(rung), new LinkedHashSet<>(blocklist), rows -> { }, p -> { }));
            if (own != null) candidates.add(own);
          } catch (Exception e) {
            // the bridge's catch: no history candidate on this rung
          }
        }
        if (includeMarket) {
          try {
            Pick m = PickChain.pick(chain(bl -> ranking().rankAt(bl, maxSpend, rung), support(rung), new LinkedHashSet<>(blocklist), rows -> { }, p -> { }));
            if (m != null) candidates.add(m);
          } catch (Exception e) {
            // the bridge's catch: no market candidate on this rung
          }
        }
        Reachable best = null;
        for (Pick c : candidates) {
          Worth w = worthOf(c);
          Double profit = Policy.headlineProfitOf(JsValues.jsRound(w.quoted), w.supported == null ? Double.NaN : w.supported);
          if (profit != null && JsValues.isFinite(profit) && profit > 0 && profit >= rung && (best == null || profit > best.profit))
            best = new Reachable(c.name, c.itemId, profit, rung);
        }
        if (best != null) return best;
      }
      return null;
    }
  }

  // ========================================================================================== the query, as server.mjs reads it

  /** {@code Number(searchParams.get(k))}: an absent parameter is Number(null), which is 0. */
  static double number(String raw) {
    return raw == null ? 0 : JsValues.toNumber(raw);
  }

  /** {@code x || 0}. */
  static double orZero(double d) {
    return Double.isNaN(d) || d == 0 ? 0 : d;
  }

  /** {@code searchParams.get(k) || undefined}. */
  static String emptyAsNull(String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  /**
   * JS {@code parseInt(s, 10)}: white space, a sign, then decimal digits, as the NUMBER they spell (2^32 + 561 stays
   * 4294967857, three hundred and ten digits are Infinity); nothing parseable is NaN.
   */
  static double parseInt(String s) {
    String t = JsValues.trim(s);
    int i = 0;
    boolean neg = false;
    if (i < t.length() && (t.charAt(i) == '+' || t.charAt(i) == '-')) neg = t.charAt(i++) == '-';
    int start = i;
    while (i < t.length() && t.charAt(i) >= '0' && t.charAt(i) <= '9') i++;
    if (i == start) return Double.NaN;
    double v = Double.parseDouble(t.substring(start, i));
    return neg ? -v : v;
  }

  /**
   * The item a parsed number can name: only a whole number in int range. JS keeps every other number in its Set as the
   * number it is, where it equals no item id -- so it is dropped here, never narrowed ((int) of 2^32 + 561 would WRAP onto
   * Nature rune; tier-query-ids-past-int-range pins this for every id a request carries).
   */
  static Integer itemIdOf(double v) {
    return JsValues.isInteger(v) && v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? (int) v : null;
  }

  /** {@code (raw || '').split(',').map(s => parseInt(s, 10)).filter(Number.isFinite)}, into an item-id set. */
  static void addItemIds(Set<Integer> into, String raw) {
    for (String s : (raw == null ? "" : raw).split(",", -1)) {
      double v = parseInt(s);
      if (!JsValues.isFinite(v)) continue;
      Integer id = itemIdOf(v);
      if (id != null) into.add(id);
    }
  }

  /** positionsWanted: 1 unless the player opted into more, clamped to {@link #MAX_POSITIONS}; anything unusable is 1. */
  static int positionsWanted(String raw) {
    double asked = number(raw);
    if (!JsValues.isFinite(asked)) return 1;
    return (int) Math.min(MAX_POSITIONS, Math.max(1, Math.floor(asked)));
  }

  /** A pick chain's held-back row as the bridge pushes it. */
  static HeldBack row(PickChain.Blocked b) {
    return b.pick != null ? new HeldBack(b.pick) : new HeldBack(b.itemId, b.reason);
  }
}
