package com.evi.live.journal;

/**
 * THE ONE PLACE the plugin's data folder is named in text a player or a log reads ("copy it to .runelite/plugin-data/evi-flipping/
 * import/..."). RuneLite derives the folder itself from the plugin's {@code internalName} ({@code Plugin#getPluginDirectory()}
 * is {@code plugin-data/<internalName>/}); this only keeps the WORDS in step with it.
 *
 * <p>{@link #INTERNAL_NAME} and {@code internalName} in EviLivePlugin's {@code @PluginDescriptor} must agree
 * (EviLiveSidebarLinesTest fails until they do), and every sentence, log line and test follows this constant. 4.0.0 moved it
 * from {@code evi-live} to {@code evi-flipping} (the maintainer, 7 Oct 2026): a new plugin listing, so a new folder. The
 * config group ({@code evilive}) is independent of it, so settings carry over; the old folder is not read.
 *
 * <p>In this package (the lowest one that names it) so the journal can use it without depending on the plugin class.
 */
public final class PluginFolder {
  private PluginFolder() {}

  /** The plugin's internalName as {@code @PluginDescriptor} declares it, and so the data folder's own name. */
  public static final String INTERNAL_NAME = "evi-flipping";
  /** The data folder as a player finds it, relative to their home folder, with no trailing slash. */
  public static final String PATH = ".runelite/plugin-data/" + INTERNAL_NAME;
}
