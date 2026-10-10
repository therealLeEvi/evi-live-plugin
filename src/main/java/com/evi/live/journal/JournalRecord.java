package com.evi.live.journal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One line of the journal ({@code events.jsonl}). The twelve kinds store.mjs writes and replays; the line
 * format itself lives in {@link JournalCodec}.
 */
public abstract class JournalRecord {
  private JournalRecord() {}

  /** The {@code type} field of the line. */
  public abstract String type();

  /** {type:'packet', received, packet}: a validated slot snapshot, journalled only when something changed. */
  public static final class PacketRecord extends JournalRecord {
    public final long received;
    public final Packet packet;

    public PacketRecord(long received, Packet packet) {
      this.received = received;
      this.packet = packet;
    }

    @Override public String type() { return "packet"; }
  }

  /** {type:'flip', flip}: a manually reviewed flip. */
  public static final class FlipRecord extends JournalRecord {
    public final ManualFlip flip;

    public FlipRecord(ManualFlip flip) { this.flip = flip; }

    @Override public String type() { return "flip"; }
  }

  /** flip-removed, flip-restored, flip-reopened: {type, id}. */
  public static final class FlipMark extends JournalRecord {
    public static final String REMOVED = "flip-removed", RESTORED = "flip-restored", REOPENED = "flip-reopened";
    private final String type;
    public final String id;

    public FlipMark(String type, String id) {
      if (!REMOVED.equals(type) && !RESTORED.equals(type) && !REOPENED.equals(type)) throw new IllegalArgumentException(type);
      this.type = type;
      this.id = id;
    }

    @Override public String type() { return type; }
  }

  /** personal-use / personal-use-undo: {type, buyId}, one specific observed purchase. */
  public static final class PersonalUse extends JournalRecord {
    public final boolean undo;
    public final String buyId;

    public PersonalUse(boolean undo, String buyId) {
      this.undo = undo;
      this.buyId = buyId;
    }

    @Override public String type() { return undo ? "personal-use-undo" : "personal-use"; }
  }

  /**
   * personal-use-item: {type, itemId, kept?}. kept is the raw number as written (it can be any integer
   * the caller passed); apply() only honours it when it is a whole number from 1 to 2^31-1, as JS does.
   * Null means the line carries no kept count: the blanket, pre-1-Oct kind of mark.
   */
  public static final class PersonalUseItem extends JournalRecord {
    public final int itemId;
    public final Double kept;

    public PersonalUseItem(int itemId, Double kept) {
      this.itemId = itemId;
      this.kept = kept;
    }

    @Override public String type() { return "personal-use-item"; }
  }

  /** personal-use-item-undo: {type, itemId}. */
  public static final class PersonalUseItemUndo extends JournalRecord {
    public final int itemId;

    public PersonalUseItemUndo(int itemId) { this.itemId = itemId; }

    @Override public String type() { return "personal-use-item-undo"; }
  }

  /** flips-imported: {type, flips}. */
  public static final class FlipsImported extends JournalRecord {
    public final List<ImportedFlip> flips;

    public FlipsImported(List<ImportedFlip> flips) { this.flips = Collections.unmodifiableList(new ArrayList<>(flips)); }

    @Override public String type() { return "flips-imported"; }
  }

  /** flips-import-removed: {type, source}. */
  public static final class FlipsImportRemoved extends JournalRecord {
    public final String source;

    public FlipsImportRemoved(String source) { this.source = source; }

    @Override public String type() { return "flips-import-removed"; }
  }

  /** purchase-recorded: {type, purchase:{offerId,itemId,name,quantity,unitPrice,at,account}}. */
  public static final class PurchaseRecorded extends JournalRecord {
    public final String offerId;
    public final int itemId;
    public final String name;
    public final long quantity;
    public final long unitPrice;
    public final long at;
    public final String account;

    public PurchaseRecorded(String offerId, int itemId, String name, long quantity, long unitPrice, long at, String account) {
      this.offerId = offerId;
      this.itemId = itemId;
      this.name = name;
      this.quantity = quantity;
      this.unitPrice = unitPrice;
      this.at = at;
      this.account = account;
    }

    @Override public String type() { return "purchase-recorded"; }
  }

  /** purchase-record-removed: {type, offerId}. */
  public static final class PurchaseRecordRemoved extends JournalRecord {
    public final String offerId;

    public PurchaseRecordRemoved(String offerId) { this.offerId = offerId; }

    @Override public String type() { return "purchase-record-removed"; }
  }

  /** position-closed: {type, buyId, reason, at}. */
  public static final class PositionClosed extends JournalRecord {
    public final String buyId;
    public final String reason;
    public final long at;

    public PositionClosed(String buyId, String reason, long at) {
      this.buyId = buyId;
      this.reason = reason;
      this.at = at;
    }

    @Override public String type() { return "position-closed"; }
  }

  /** position-reopened: {type, buyId}. */
  public static final class PositionReopened extends JournalRecord {
    public final String buyId;

    public PositionReopened(String buyId) { this.buyId = buyId; }

    @Override public String type() { return "position-reopened"; }
  }
}
