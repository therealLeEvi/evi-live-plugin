package com.evi.live.journal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which records of a BRIDGE journal (data/events.jsonl, every account on the machine in one file) belong
 * to ONE account, for the plugin's one-time, opt-in import. Pure: records in, records out.
 *
 * <p>Ownership is always EXPLICIT EQUALITY with a non-null account -- never "no account means everyone's"
 * (the 6 Oct 2026 bug that showed a pure's holdings to the main):
 * <ul>
 *   <li>a packet: its {@code account}; a recorded purchase: its {@code account};</li>
 *   <li>a reviewed flip: its own {@code account} (or, on a flip journalled without one, its buy's owner);</li>
 *   <li>a flip mark: the owner of the flip it marks;</li>
 *   <li>personal use, a closed or reopened position, a removed recorded purchase: the owner of the offer
 *       it names, resolved through the bridge's login-time aliases exactly as the store resolves it.</li>
 * </ul>
 * Records tied to NO account -- an item-level "for my own use" mark and imported flip histories -- cannot
 * be attributed and are left out and counted. None of them changes the profit line or the holdings.
 *
 * <p>EXCEPT, when asked (since 8 Oct 2026 in both modes; the built-in engine since 8 Oct): the item-level marks ("Yours, not
 * stock" and its undo) are the bridge's MACHINE-WIDE choices, so they are copied into every importing account's selection, in
 * journal order, and counted as {@code itemMarks}. The plugin's store is machine-wide too, so they apply to every account
 * exactly as in the bridge.
 *
 * <p>AND (8 Oct 2026, approved): the flip histories imported into the bridge from another tracker ({@code flips-imported}: a
 * Flipping Copilot export, a flips.csv) name the CHARACTER each trade was made on -- its display name, not EVI's account id. Given
 * the importing character's display name, each such record is copied with only the flips made under THAT name
 * ({@link CharacterNames#same}: case, space / underscore / hyphen / non-breaking space as the game treats them), in journal
 * order, and only those the bridge still holds after every record (an import removed later is not taken; a repeated
 * fingerprint is not taken twice). A flip naming no character, or another one, is never taken. The bridge reads every
 * imported flip machine-wide in its personal-history ranking ({@code [...state.flips, ...state.importedFlips]}), and so does the
 * plugin's store: once a character is imported, every account on the machine ranks from its history, as in the bridge.
 *
 * <p>AND (8 Oct 2026, approved): a character renamed since keeps its trades under its FORMER name too, when the player says so
 * ({@link OldNames}, {@code oldNames} in import/preferences.json): such a flip is taken as if it carried the current name.
 */
public final class BridgeImport {
  private BridgeImport() {}

  public static final class Selection {
    public final List<JournalRecord> records;
    public final int total;
    public final int otherAccounts;
    public final int notTiedToAnAccount;
    public final int unresolved;
    /** Item-level marks copied in for every account (0 unless asked for). */
    public final int itemMarks;
    /** Imported flips (another tracker's) taken for this character, and those left for other characters' names. */
    public final int flips, flipsOtherCharacters;
    /**
     * Of {@link #flips}, those kept under one of this character's FORMER names ({@link OldNames}); and the flips under a former
     * name the player's mapping refused (taken by no character; also counted in {@link #flipsOtherCharacters}).
     */
    public final int flipsViaOldNames, flipsOldNamesRefused;

    Selection(List<JournalRecord> records, int total, int otherAccounts, int notTiedToAnAccount, int unresolved) {
      this(records, total, otherAccounts, notTiedToAnAccount, unresolved, 0, 0, 0);
    }

    Selection(List<JournalRecord> records, int total, int otherAccounts, int notTiedToAnAccount, int unresolved, int itemMarks, int flips,
              int flipsOtherCharacters) {
      this(records, total, otherAccounts, notTiedToAnAccount, unresolved, itemMarks, flips, flipsOtherCharacters, 0, 0);
    }

    Selection(List<JournalRecord> records, int total, int otherAccounts, int notTiedToAnAccount, int unresolved, int itemMarks, int flips,
              int flipsOtherCharacters, int flipsViaOldNames, int flipsOldNamesRefused) {
      this.records = Collections.unmodifiableList(records);
      this.total = total;
      this.otherAccounts = otherAccounts;
      this.notTiedToAnAccount = notTiedToAnAccount;
      this.unresolved = unresolved;
      this.itemMarks = itemMarks;
      this.flips = flips;
      this.flipsOtherCharacters = flipsOtherCharacters;
      this.flipsViaOldNames = flipsViaOldNames;
      this.flipsOldNamesRefused = flipsOldNamesRefused;
    }
  }

  /**
   * The records of {@code all} that belong to {@code account}, in journal order. Throws when the bridge
   * journal does not replay (the import is then refused and nothing is written).
   */
  public static Selection select(List<JournalRecord> all, String account) {
    return select(all, account, false);
  }

  /**
   * As {@link #select(List, String)}; with {@code itemMarks}, the item-level marks are kept for this account too (see the class
   * note), each counted in {@link Selection#itemMarks} rather than as not tied to an account.
   */
  public static Selection select(List<JournalRecord> all, String account, boolean itemMarks) {
    return select(all, account, itemMarks, null);
  }

  /**
   * As {@link #select(List, String, boolean)}, with the importing character's display name ({@code characterName}; null: not
   * known, and no imported flip is taken). See the class note.
   */
  public static Selection select(List<JournalRecord> all, String account, boolean itemMarks, String characterName) {
    return select(all, account, itemMarks, characterName, OldNames.NONE);
  }

  /**
   * As {@link #select(List, String, boolean, String)}, with the player's stated former names ({@code oldNames}, never null): a flip
   * kept under a former name is taken exactly as if it carried the current name it maps to, and one under a refused former name
   * by no character ({@link OldNames}).
   */
  public static Selection select(List<JournalRecord> all, String account, boolean itemMarks, String characterName, OldNames oldNames) {
    if (oldNames == null) throw new IllegalArgumentException("oldNames");
    if (!Packet.id(account)) throw new JournalException("Invalid account");
    Store whole = Store.replay(all, r -> {
      throw new IllegalStateException("an import replay must not write");
    });
    // The imported flips the bridge still holds after every record, by IDENTITY: a flip of a later-removed import, or one whose
    // fingerprint an earlier record already brought, is not among them.
    Map<ImportedFlip, Boolean> held = new IdentityHashMap<>();
    for (ImportedFlip f : whole.importedFlipsNow()) held.put(f, Boolean.TRUE);
    Map<String, String> offerOwner = new HashMap<>();
    for (Offer o : whole.offers()) offerOwner.put(o.offerId, o.account);
    // A recorded purchase removed later is no longer an offer, but its records still name it.
    for (JournalRecord r : all) {
      if (r instanceof JournalRecord.PurchaseRecorded) {
        JournalRecord.PurchaseRecorded p = (JournalRecord.PurchaseRecorded) r;
        offerOwner.putIfAbsent(p.offerId, p.account);
      }
    }
    Map<String, String> flipOwner = new HashMap<>();
    List<JournalRecord> out = new ArrayList<>();
    int other = 0, global = 0, unresolved = 0, marks = 0, flipsTaken = 0, flipsOther = 0, viaOld = 0, oldRefused = 0;
    String me = CharacterNames.key(characterName);
    for (JournalRecord r : all) {
      String owner;
      boolean attributable = true;
      if (r instanceof JournalRecord.FlipsImported && characterName == null) {
        global++; // no character named: nothing to tie these to, exactly as before names were read
        continue;
      }
      if (r instanceof JournalRecord.FlipsImported) {
        List<ImportedFlip> mine = new ArrayList<>();
        boolean others = false;
        for (ImportedFlip f : ((JournalRecord.FlipsImported) r).flips) {
          if (!held.containsKey(f) || f.account == null) continue;
          String k = CharacterNames.key(f.account);
          String now = oldNames.currentOf(k); // a stated former name: compared as its current name, and only as that
          if (oldNames.refused(k)) oldRefused++;
          else if (me != null && me.equals(now != null ? now : k)) {
            mine.add(f);
            if (now != null) viaOld++;
            continue;
          }
          flipsOther++;
          others = true;
        }
        if (!mine.isEmpty()) {
          out.add(mine.size() == ((JournalRecord.FlipsImported) r).flips.size() ? r : new JournalRecord.FlipsImported(mine));
          flipsTaken += mine.size();
        } else if (others) other++;
        else global++; // every flip names no character, or was removed in the bridge later
        continue;
      }
      if (itemMarks && (r instanceof JournalRecord.PersonalUseItem || r instanceof JournalRecord.PersonalUseItemUndo)) {
        out.add(r); // machine-wide in the bridge, machine-wide in the plugin: every importing account takes it
        marks++;
        continue;
      }
      if (r instanceof JournalRecord.PacketRecord) {
        owner = ((JournalRecord.PacketRecord) r).packet.account;
      } else if (r instanceof JournalRecord.PurchaseRecorded) {
        owner = ((JournalRecord.PurchaseRecorded) r).account;
      } else if (r instanceof JournalRecord.PurchaseRecordRemoved) {
        owner = offerOwner.get(((JournalRecord.PurchaseRecordRemoved) r).offerId);
      } else if (r instanceof JournalRecord.PositionClosed) {
        owner = offerOwner.get(whole.resolve(((JournalRecord.PositionClosed) r).buyId));
      } else if (r instanceof JournalRecord.PositionReopened) {
        owner = offerOwner.get(whole.resolve(((JournalRecord.PositionReopened) r).buyId));
      } else if (r instanceof JournalRecord.PersonalUse) {
        owner = offerOwner.get(whole.resolve(((JournalRecord.PersonalUse) r).buyId));
      } else if (r instanceof JournalRecord.FlipRecord) {
        ManualFlip f = ((JournalRecord.FlipRecord) r).flip;
        owner = f.account != null ? f.account : (f.buyId == null ? null : offerOwner.get(whole.resolve(f.buyId)));
        if (f.id != null && owner != null) flipOwner.putIfAbsent(f.id, owner);
      } else if (r instanceof JournalRecord.FlipMark) {
        owner = flipOwner.get(((JournalRecord.FlipMark) r).id);
      } else {
        owner = null;
        attributable = false; // personal-use-item(-undo) when not asked for; flips-import-removed (applied through `held`)
      }
      if (!attributable) global++;
      else if (owner == null) unresolved++;
      else if (owner.equals(account)) out.add(r);
      else other++;
    }
    return new Selection(out, all.size(), other, global, unresolved, marks, flipsTaken, flipsOther, viaOld, oldRefused);
  }

  /**
   * The imported records first, then every record the plugin journalled for this account that the import does
   * not already hold. Only a PACKET can be held twice: the plugin sent the bridge the very same packet, received
   * at a slightly different time, so it is the same packet when its session and seq match. Every other record
   * in a plugin events file is a press made IN the plugin (a mark, a close), which never reached the bridge --
   * a press goes to one place -- so it is always kept, even when its line is byte-identical to an imported one.
   * (Until 8 Oct 2026 such a line was dropped: an imported mark, its imported undo, then the same mark pressed
   * again in the plugin lost the new mark at the next rebuild. Marks carry no time, so identical lines are
   * normal.)
   */
  public static List<JournalRecord> merge(List<JournalRecord> imported, List<JournalRecord> existing) {
    Dedup d = Dedup.of(imported);
    List<JournalRecord> out = new ArrayList<>(imported);
    for (JournalRecord r : existing) {
      if (d.contains(r)) continue;
      out.add(r);
    }
    return out;
  }

  /**
   * What an import already holds, by {@link #merge}'s rule: a packet by its session and seq; nothing else (a
   * plugin-written record is never one the import holds). The plugin keeps one per account so a line appended
   * to the events file later is skipped exactly as a rebuild's merge would skip it.
   */
  public static final class Dedup {
    private final Set<String> packets = new HashSet<>();

    private Dedup() {}

    public static Dedup of(List<JournalRecord> imported) {
      Dedup d = new Dedup();
      for (JournalRecord r : imported) {
        if (r instanceof JournalRecord.PacketRecord) d.packets.add(packetKey((JournalRecord.PacketRecord) r));
      }
      return d;
    }

    public boolean contains(JournalRecord r) {
      return r instanceof JournalRecord.PacketRecord && packets.contains(packetKey((JournalRecord.PacketRecord) r));
    }
  }

  private static String packetKey(JournalRecord.PacketRecord r) {
    return r.packet.session + "\n" + r.packet.seq;
  }
}
