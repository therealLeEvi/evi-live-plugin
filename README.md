# EVI Live (Local)

A passive Grand Exchange companion for **EVI**, a local, personal flip-tracking tool. This plugin observes the Grand Exchange offers you place yourself, plus the few inventory details listed under Privacy below, and reports them to a bridge running on your own computer — it never sends anything anywhere else, and it never places, edits, or confirms an offer on your behalf.

## What it does

- **Passive observation.** While you play normally, the plugin reports your own Grand Exchange offers (item, price, quantity, fill progress) to a small local bridge listening on `127.0.0.1` — nothing leaves your machine, and nothing is sent to any third-party server.
- **A price/quantity hint in the offer prompt.** While a buy or sell prompt is open, a line of text shows EVI's suggested price for that item (and quantity, when it's EVI's own ranked pick based on your reviewed trade history) — for example, "EVI: press F8 for buy price 1,703 gp". This works for any item you have open, not only EVI's top suggestion.
- **An optional fill hotkey** (default **F8**, configurable). Pressing it while the hint is shown fills the value into the field you already have open — the same way RuneLite's own built-in Grand Exchange panel fills its 25%/50%/100% quantity presets. It never submits or confirms anything; you still press Enter and click Confirm yourself.
- **A highlighted row in item search**, and a clickable **"EVI item: `<name>`"** row with the item's icon, so you can jump straight to the suggested item the same way other Grand Exchange assistant plugins' own equivalent row works — using RuneLite's own standard widget-click plugin API, the same mechanism every plugin's own buttons already use, not simulated keyboard or mouse input.
- **A sidebar panel** showing EVI's current top suggestion (item, quantity, buy/sell price, and the reasoning behind it) and a **Skip this suggestion** button for when you'd rather not act on it right now.

## This plugin needs a companion bridge running locally

This plugin is one half of EVI: the other half is a small local bridge (Node.js) and a browser-based scanner/dashboard that actually tracks your trade history, computes suggestions, and shows your profit over time. Both run entirely on your own computer — no data is sent to any server operated by anyone else. Without the bridge running and paired, this plugin has nothing to show.

**Full source for the bridge: https://github.com/therealLeEvi/evi-live-bridge** — it is a small
Node.js server you run yourself. It listens on `127.0.0.1` only, keeps its records in a folder next
to itself, and the only outbound requests it makes are to two public sources: the OSRS Wiki
real-time price API and the official Old School RuneScape news feed. There is no account, no
sign-up, and no server operated by anyone else. Its `LOCAL-API.md` documents every endpoint this
plugin uses, and its README covers setup and exactly what is stored.

The browser dashboard that reviews trades and charts profit over time is a separate, personal piece
and is not published; the plugin and the bridge do not need it.

## Pairing

On first use, open the plugin's sidebar panel, enter the pairing key shown by the local bridge/scanner, and save it. The plugin then starts sending your own offer data to `127.0.0.1` only.

## Privacy

- All network activity is to `127.0.0.1` (your own computer) only. No external servers, no analytics, no telemetry.
- Besides your own Grand Exchange offers, the plugin reads exactly these, all sent only to your own bridge:
  - your **coin count**, to size suggestions to what you can afford;
  - the **world type** (members or free-to-play), so members-only items are not suggested on a free-to-play world;
  - whether items the bridge believes you still hold from earlier purchases are **in your inventory** — it reports back only those item IDs, never the rest of your inventory;
  - your **full inventory**, only if you turn on *Suggest selling idle inventory*, which is **off by default**.
- Nothing about other players is collected, and nothing from chat or your bank. Your account name is never sent; accounts are told apart by a salted pseudonym.
- No automation: the plugin never opens a menu, clicks a button, selects an item, confirms an offer, or otherwise acts in the game world on its own. Every action described above either just displays information, or fills a text field you already have open — you still make and confirm every trade yourself.

## License

BSD 2-Clause — see [LICENSE](LICENSE).
