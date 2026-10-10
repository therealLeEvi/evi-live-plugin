# EVI Live

**EVI works out what to flip on the Grand Exchange, and shows it to you while you trade.**

It tells you what to buy, how many, and at what price -- in the sidebar, and in the offer box itself -- and it keeps a
record of your own trades with the Grand Exchange tax worked out exactly, so you can see what you have actually made.

You place every trade yourself. EVI only ever shows you things.

Everything runs inside this plugin. There is nothing else to install.

---

## Getting started

1. **Install EVI Live from the Plugin Hub** and log in.
2. **Give it a few minutes the first time.** EVI downloads two weeks of public Grand Exchange price history from the
   OSRS Wiki before it suggests anything to buy. The sidebar shows how far it has got ("Loading price history: 140 of
   336 hours"). This takes about 16 to 21 minutes, once. Advice about offers you already have works from the first
   minute.
3. **That is it.** From then on EVI watches the offers you place and suggests trades. It gives useful suggestions with
   no history at all; your own trades simply give it more to go on.

If RuneLite was closed for a while, EVI fetches only the hours it missed when you next start it, which takes about a
minute after a day away.

---

## What you get

**A trade to make.** The sidebar shows one suggestion at a time by default: what to buy, how many, at what price, and
why. If you would rather run several positions at once, *Suggestions at once* lets you ask for more.

**A way to tell it what you did.** Under each suggestion is a row of five buttons:

- **Took it** -- you acted on this suggestion. This is what lets EVI measure whether following it actually made GP.
  Press again to take it back.
- **Mine** -- something you are holding was bought for your own use, not to flip. It stops being suggested and never
  counts toward profit.
- **Gone** -- something you are holding is no longer in your hands (used in-game, or sold while EVI was not running).
- **Skip** -- set this suggestion aside for 4 hours and show the next-best one.
- **Block** -- never suggest buying this item again on this character. Blocked items are listed under *Blocked items*
  in the sidebar, each with an Unblock button.

**The price, where you need it.** While a buy or sell box is open, EVI shows the price it suggests for that item.
Press **F8** (you can change this) and it types the number into the box you already have open. You still press Enter
and click Confirm yourself.

**A check on your own offers.** EVI watches what you already have standing and tells you when one needs attention:
selling below what the stock cost you, the market moving away from your price, or an offer unlikely to fill in time.

**Suggestions sized to you.** EVI reads your coin count (platinum tokens included) and will not suggest a trade you
cannot afford, more of an item than the market is actually trading, or more than the Grand Exchange buy limit allows.

**A trade journal.** Every offer you place is recorded, matched buy to sell, with the Grand Exchange tax worked out
exactly. The sidebar shows your profit since you last pressed *Reset profit count*.

---

## The settings that matter most

All of these are in RuneLite's settings under EVI Live. The defaults are a sensible start.

| setting | default | what it does |
| --- | --- | --- |
| Min predicted profit | Auto | Skips suggestions predicted to make less than this. Auto scales with your cash. |
| Trade pace | No preference | How long you will wait for one trade. Longer paces allow bigger, slower trades. |
| Max share of cash per trade | 25% of cash | The most of your cash one market-wide suggestion may use. |
| Suggestion focus | All items | Gear, bulk consumables and ammo, or everything. Sales are unaffected. |
| Suggestions at once | 1 | For a large cash stack: more than one suggestion, each using only the cash the earlier ones left. |
| Position sizing | Careful | *Bigger positions* buys more of thinly traded items. |
| Suggest from | Your history first | Your own history first, the best of both, or the whole market. |
| Include market-wide suggestions | On | Suggests from the whole Grand Exchange, not only items you have traded before. |
| Suggest selling idle inventory | Off | Also suggests selling valuable items in your inventory that EVI never saw you buy. |
| Show GE hints / Fill suggestion hotkey | On / F8 | The price in the offer box, and the key that fills it in. |
| Notify on offer warnings | Off | Uses RuneLite's notifier the first time EVI warns about one of your offers. |

---

## What EVI sends over the network

EVI downloads public Grand Exchange prices from the OSRS Wiki's price service (**prices.runescape.wiki**). **That is
the only internet address it contacts**, and it does so through RuneLite's own HTTP client. All of EVI's networking is
in one file, `WikiPriceClient.java`.

**What it fetches, and when**

- **The latest prices**, about once a minute, **only while you are logged in**. Each one is about 72 KB, so roughly
  4 MB for every hour you play.
- **One hour of price history each hour**, a few minutes after the hour closes (about 42 KB each). This happens
  whenever RuneLite is open, **logged in or not**.
- **The item list** (names and buy limits), once a day, also whenever RuneLite is open. It is kept on disk, so a
  restart does not fetch it again.
- **Catching up:** if RuneLite was closed for a while, EVI fetches the hours it missed, up to two weeks back, one
  request at a time and at least 2.5 seconds apart.
- **The first time**, that means the whole two weeks: 337 hourly requests, about 14 MB in total. **It takes about 16 to
  21 minutes** (2.5 seconds between requests sets a floor of 14 minutes; the Wiki's own answer time adds the rest).
  Until then EVI does not suggest buys, because it ranks them on the full two weeks.

Every one of these is an all-items request, **the same for every player**. EVI never asks the Wiki about a particular
item, so the Wiki cannot tell which items you are looking at.

**What is sent, and what is not**

- The Wiki can see your IP address, as with any website you visit.
- Each request names EVI, its version and its public source page in the User-Agent header, which is what the Wiki asks
  of every program using its prices.
- **Nothing about you or your account is sent**: no name, no account, no offers, no trades, no gold amount.

---

## What EVI stores on your computer

Everything EVI keeps is in your RuneLite folder, under `.runelite/plugin-data/evi-flipping/`. EVI never sends any of it
anywhere.

| folder or file | what it holds |
| --- | --- |
| `journal/` | Your trade journal: the Grand Exchange offers you place (item, price, quantity, how much filled), one file per account. Accounts are told apart by a scrambled name, never your account name. |
| `prices/` | The price history from the Wiki: about 10 MB for the two weeks EVI keeps, older hours deleted as new ones arrive, plus the cached item list. With two RuneLite clients open, the history is shared between them. |
| `suggestions/` | The suggestions EVI showed you and which ones you marked *Took it*, so it can check whether following them made GP. |
| `identity-salt.txt` | The random value that scrambles account names. Keep it if you move the folder, or old journal files no longer match your accounts. |
| `import/` | Only if you use the one-time import below. |
| `share/` | Only if you press *Save my trade log...*: the trade log you chose to save (see below). |

Your settings, your *Block* list (one per character) and the *Reset profit count* time are kept with RuneLite's own
settings, like any plugin's.

EVI reads your Grand Exchange offers, your coin count, whether the world is members, and whether items it believes you
hold are in your inventory. It reads your whole inventory only if you switch on *Suggest selling idle inventory*. It
never reads your bank or your chat, and collects nothing about other players.

---

## Sharing your trade log (optional)

EVI can save a copy of your trade log as a file on your computer, and it does so only when you press *Save my trade
log...* at the bottom of the sidebar. **EVI never uploads it.** Nothing is sent anywhere unless you send the file
yourself.

- **What the file contains:** every Grand Exchange offer EVI saw (item, buy or sell, price, quantity, how much filled,
  when, and whether it was cancelled), EVI's suggested price when it suggested that item, and your finished flips with
  their profit or loss.
- **What it does not contain:** your account, your character name, your cash stack or your bank.
- **Times are exact**, because that is what makes the data useful. On a rarely traded item, someone watching that item
  could still recognise a trade by its time.
- **Where it is saved:** `.runelite/plugin-data/evi-flipping/share/`. It is a CSV file you can open and read in any
  spreadsheet program before deciding anything.

If you choose to share it: on the EVI Discord (**https://discord.gg/gFcEBHknVN**) go to **#share-your-log**, press
**Create Ticket**, and upload the file in the private channel that opens. Only you and the EVI team can see it.

*Not now* hides the invitation until the next EVI update.

---

## Bringing over an older EVI history

If you used an earlier version of EVI that kept its trade record in a separate companion app, you can bring that
record across once:

`.runelite` is in your user folder: on Windows type `%USERPROFILE%\.runelite` into File Explorer's address bar; on macOS
and Linux it is `~/.runelite`. Create the `import` folder if it is not there.

1. Copy the old app's `data/events.jsonl` into `.runelite/plugin-data/evi-flipping/import/events.jsonl`.
2. Copy `.runelite/plugin-data/evi-live/identity-salt.txt` (the older EVI version's folder) into the same `import` folder.
   It lets EVI recognise which of the old records were yours; it is only read.
3. Optionally copy the old app's `data/preferences.json` to `.runelite/plugin-data/evi-flipping/import/preferences.json`. That
   brings your blocked items (to every character, once) and your profit-count start.
4. Switch on *Import old trade history* under *Trade history* in EVI's settings, and log in on each account.

Each account's trades are copied once. While the setting is off, nothing is read, and the files in `import/` are only ever read, never changed.

---

## What it will never do

- **It never trades for you.** It never opens a menu, clicks a button, chooses an item, or confirms an offer. The
  hotkey fills in a number in a box you already opened -- the same thing RuneLite's own quantity buttons do.
- **It sends nothing about you anywhere.** The only network traffic is the public price downloads above.
- **It never promises a fill or a profit.** Every estimate is labelled as one, and when the data is too thin to say,
  EVI says so instead of guessing.

---

## Questions or problems

See the [FAQ](FAQ.md). There is a Discord for support: **https://discord.gg/gFcEBHknVN**

The source is here, so you can read exactly what EVI does: **https://github.com/therealLeEvi/evi-live-plugin**

## License

BSD 2-Clause. See `LICENSE`.
