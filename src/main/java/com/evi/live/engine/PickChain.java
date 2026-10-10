package com.evi.live.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The checks every BUY candidate passes after its tier ranked it (bridge/suggestions.mjs {@code pickWithForecast}):
 * forecast (opt-in), margin cushion (opt-in), correlation with what is already held, and sell support, with up to
 * {@code maxAttempts} candidates tried. A failed check puts the item in the blocklist and asks the tier again. Pure.
 *
 * <p>SYNCHRONOUS BY CONSTRUCTION. On 5 Oct the JS {@code rank} became async on three paths (the Auto step-down, the
 * reachable probe, the additional positions) and was not awaited, so the candidate was a Promise, its {@code action}
 * undefined, and every check below was skipped for those paths. Here {@link Rank#rank} returns the candidate itself and
 * every hook returns its answer: there is no future to forget to wait for, on any path.
 *
 * <p>What each outcome means, as in the JS:
 * <ul>
 *   <li>not a buy (a holding or idle-stock reminder): returned at once, unchecked;</li>
 *   <li>forecast: its sentence and the policy note are appended; it may DROP the candidate only when
 *       {@link #FORECAST_MAY_DROP_CANDIDATES} (false: measured to call direction backwards);</li>
 *   <li>cushion or correlation blocks: the item is blocklisted and the next candidate is tried (correlation records why);</li>
 *   <li>sell support BLOCKED (thinner than its own tax at the price buyers pay): HELD BACK, with its warning first in the
 *       reasoning -- never shown, however large the quantity makes it look (27 Sept, 172,266 blood runes);</li>
 *   <li>sell support WARNED: DEMOTED -- set aside for the next candidate, and shown, flagged, only if nothing passes.</li>
 * </ul>
 * The blocklist is the CALLER's set and is mutated, exactly as the JS mutates the Set it is handed: several call sites
 * share one, so the order of calls changes what the later ones see, and a port that copied it would change answers.
 *
 * <p>Plan decision 13 (drop the opt-in cushion and forecast in the self-contained build): the hooks are kept and are OFF
 * by default -- a null hook is never consulted.
 */
public final class PickChain {
  private PickChain() {}

  /** FORECAST_MAY_DROP_CANDIDATES: the forecast is shown but removes nothing (calibration found it inverted). */
  public static final boolean FORECAST_MAY_DROP_CANDIDATES = false;
  /** UNFAVORABLE_FORECAST_CONFIDENCE: what "meaningfully unfavourable" would need, were dropping allowed. */
  public static final double UNFAVORABLE_FORECAST_CONFIDENCE = 55;

  /** A tier's ranking against the current blocklist. Returns the candidate itself -- never a future. */
  public interface Rank {
    Pick rank(Set<Integer> blocklist);
  }

  /** What the forecast hook hands back: the direction and confidence, and the sentence the caller built for it. */
  public static final class Forecast {
    public final Object detail;
    public final int dir;
    public final double confidence;
    /** The fill-outlook sentence for this forecast (null for none). */
    public final String outlookSentence;
    public final Object outlook;

    public Forecast(Object detail, int dir, double confidence, String outlookSentence, Object outlook) {
      this.detail = detail;
      this.dir = dir;
      this.confidence = confidence;
      this.outlookSentence = outlookSentence;
      this.outlook = outlook;
    }
  }

  public interface ForecastHook {
    Forecast forecastFor(int itemId);
  }

  /** A cushion or correlation answer: blocked or not, and a note. */
  public static final class Check {
    public final boolean blocked;
    public final String note;

    public Check(boolean blocked, String note) {
      this.blocked = blocked;
      this.note = note;
    }
  }

  public interface CheckHook {
    Check check(Pick candidate);
  }

  public interface SupportHook {
    SellSupport.Result supportFor(Pick candidate);
  }

  /**
   * The bridge's sell-support hook FAILS OPEN: server.mjs's supportForSuggestion wraps the reading and the judgement in
   * {@code try { ... } catch { return null; }}, so one bad series means "no reading" and the candidate goes on, never a failed
   * poll. {@link #pick} itself propagates a hook's exception, exactly as pickWithForecast does, so the catch belongs to
   * whoever wires the hook: wrap the reading-and-judge part ({@link SellSupport#reading} then {@link SellSupport#judge}) in
   * this. The crash check runs OUTSIDE that try in the bridge and must stay outside it here.
   *
   * <p>WHAT IT ABSORBS, pinned by TierIntentTest.aBrokenSupportReadingFailsOpen: every {@link Exception} -- whatever
   * the reading can throw (an ArithmeticException from a GP figure past a long, a NullPointerException from a missing
   * field, a parse error), and a checked exception that reached it undeclared, since the JS {@code catch} has no notion
   * of a type and absorbs anything a reading throws. What it PROPAGATES: an {@link Error} (an AssertionError, a stack
   * overflow, running out of memory) is a fault in the JVM or a test, never "no reading", and swallowing it would turn a
   * broken build green. The hook's answer otherwise passes through untouched: the same Result, or null.
   */
  public static SupportHook failOpen(SupportHook hook) {
    if (hook == null) return null;
    return candidate -> {
      try {
        return hook.supportFor(candidate);
      } catch (Exception e) {
        return null;
      }
    };
  }

  /** One candidate held back for a stated reason: the whole pick (sell support), or an item and why (correlation). */
  public static final class Blocked {
    public final Pick pick;
    public final int itemId;
    public final String reason;

    Blocked(Pick pick) {
      this.pick = pick;
      this.itemId = pick.itemId;
      this.reason = null;
    }

    Blocked(int itemId, String reason) {
      this.pick = null;
      this.itemId = itemId;
      this.reason = reason;
    }
  }

  /** The chain's inputs. Only {@link #rank} and {@link #blocklist} are required; every hook is off when null. */
  public static final class Options {
    public Rank rank;
    public ForecastHook forecastFor;
    /** 'warn' or 'skip'. */
    public String policy;
    /** The forecast horizon; null or empty switches the forecast off. */
    public String horizon;
    public CheckHook cushionFor;
    public boolean requireCushion;
    public CheckHook correlationFor;
    public SupportHook supportFor;
    public Set<Integer> blocklist;
    public int maxAttempts = 3;
    public Consumer<List<Blocked>> onBlocked;
    public Consumer<Pick> onDemoted;
  }

  /** decideForecast: 'keep' or 'retry'. Always 'keep' while {@link #FORECAST_MAY_DROP_CANDIDATES} is false. */
  public static String decideForecast(Forecast forecast, String policy) {
    if (forecast == null) return "keep";
    if (!FORECAST_MAY_DROP_CANDIDATES) return "keep";
    if ("skip".equals(policy) && forecast.dir == -1 && forecast.confidence >= UNFAVORABLE_FORECAST_CONFIDENCE) return "retry";
    return "keep";
  }

  /** forecastPolicyNote: the plain statement that "skip" is inactive, so a setting never silently does nothing. */
  public static String forecastPolicyNote(String policy) {
    return "skip".equals(policy) && !FORECAST_MAY_DROP_CANDIDATES
      ? "Note: \"skip on an unfavourable forecast\" is currently inactive. Measured against 90 days of price history, this forecast's direction calls were wrong more often than chance, and skipping on them threw away better trades than it avoided -- so the forecast is shown but no longer removes a candidate."
      : null;
  }

  /** pickWithForecast: the first candidate that passes every check, the best demoted one, or null. */
  public static Pick pick(Options o) {
    Set<Integer> blocklist = o.blocklist;
    List<Blocked> blocked = new ArrayList<>();
    List<Pick> demoted = new ArrayList<>();
    for (int attempt = 0; attempt < o.maxAttempts; attempt++) {
      Pick candidate = o.rank.rank(blocklist);
      if (candidate == null) break;
      if (!candidate.isBuy()) return candidate;
      if (o.forecastFor != null && o.horizon != null && !o.horizon.isEmpty()) {
        Forecast forecast = o.forecastFor.forecastFor(candidate.itemId);
        if (forecast != null) {
          candidate.forecast = forecast.detail;
          candidate.fillOutlook = forecast.outlook;
          if (forecast.outlookSentence != null && !forecast.outlookSentence.isEmpty()) candidate.reasoning += " " + forecast.outlookSentence;
          String note = forecastPolicyNote(o.policy);
          if (note != null) candidate.reasoning += " " + note;
          if ("retry".equals(decideForecast(forecast, o.policy))) {
            blocklist.add(candidate.itemId);
            continue;
          }
        }
      }
      if (o.cushionFor != null && o.requireCushion) {
        Check cushion = o.cushionFor.check(candidate);
        if (cushion != null) {
          if (cushion.blocked) {
            blocklist.add(candidate.itemId);
            continue;
          }
          if (cushion.note != null && !cushion.note.isEmpty()) candidate.reasoning += " " + cushion.note;
        }
      }
      // Several slots in items that move together is one position wearing several hats. A null answer (no archive,
      // too little shared history, nothing held) never blocks anything.
      if (o.correlationFor != null) {
        Check correlated = o.correlationFor.check(candidate);
        if (correlated != null && correlated.blocked) {
          blocked.add(new Blocked(candidate.itemId, correlated.note));
          blocklist.add(candidate.itemId);
          continue;
        }
      }
      // Last: the one check that costs a fetch in the bridge.
      if (o.supportFor != null) {
        SellSupport.Result support = o.supportFor.supportFor(candidate);
        if (support != null && support.blocked) {
          candidate.reasoning = joinTrim(support.warning, candidate.reasoning);
          candidate.sellSupport = support.detail;
          blocked.add(new Blocked(candidate));
          blocklist.add(candidate.itemId);
          continue;
        }
        if (support != null && support.detail != null) candidate.sellSupport = support.detail;
        if (support != null && support.warning != null && !support.warning.isEmpty()) {
          candidate.reasoning = support.warning + " " + (candidate.reasoning == null ? "" : candidate.reasoning);
          candidate.sellSupport = support.detail;
          demoted.add(candidate);
          if (o.onDemoted != null) o.onDemoted.accept(candidate);
          blocklist.add(candidate.itemId);
          continue;
        }
      }
      return candidate;
    }
    if (!demoted.isEmpty()) {
      Pick best = demoted.get(0);
      best.demoted = true;
      best.reasoning += " No other candidate passed this check right now, which is why this one is shown.";
      return best;
    }
    if (!blocked.isEmpty() && o.onBlocked != null) o.onBlocked.accept(Collections.unmodifiableList(blocked));
    return null;
  }

  // `${warning || ''} ${reasoning || ''}`.trim(), with JS's trim.
  private static String joinTrim(String warning, String reasoning) {
    return JsValues.trim((warning == null ? "" : warning) + " " + (reasoning == null ? "" : reasoning));
  }
}
