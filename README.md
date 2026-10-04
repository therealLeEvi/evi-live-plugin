# EVI Live (Local)

**EVI works out what to flip on the Grand Exchange, and shows it to you while you trade.**

It reads your own offers as you place them, and learns what has actually worked for you. Then it
tells you what to buy, how many, and at what price — in the sidebar, and in the offer box itself.

You place every trade yourself. EVI only ever shows you things.

> ### ⚠️ This plugin does nothing on its own
> EVI is two halves. This plugin is the part inside RuneLite; the thinking happens in a small
> **companion app you run on your own computer**. Until you install and start it, the sidebar will
> simply say it cannot reach the bridge.
>
> **Get it here: https://github.com/therealLeEvi/evi-live-bridge** — free, open source, and it runs
> entirely on your own machine.

---

## Getting started

1. **Install the companion app.** Download it from the
   [bridge repository](https://github.com/therealLeEvi/evi-live-bridge) and start it.
2. **That is it.** The app pairs this plugin itself — they run on the same computer, so it writes the
   key where the plugin already looks. Within a few seconds the sidebar says *Paired locally* if
   RuneLite started after the app, or *Paired automatically* if it was already running. No key to
   copy, no client restart.

If RuneLite was not installed when you first ran the app, it will say so in its own window and you can
pair by hand instead: copy the line labelled *RuneLite plugin key* and paste it into the EVI panel.
That is not the scanner key, which is the one the app's setup page asks for to open itself.

That is the whole setup. From then on EVI watches the offers you place and starts suggesting trades.
The longer you use it, the more it has to go on — but it gives useful suggestions from day one, with
no history at all.

---

## What you get

**A trade to make.** The sidebar shows one suggestion at a time by default: what to buy, how many, at
what price, and why. Not a wall of options — one thing to do next. If you would rather run several
positions at once, a setting lets you ask for more.

**A way to tell it what you did.** Under each suggestion is a row of five icons: you took it, it is
yours rather than stock, you no longer have it, skip it, or never suggest that item again. Telling
EVI you took a trade is what lets it measure whether following it actually made you GP — without
that it can only guess, from an offer happening to appear afterwards.

**The price, where you need it.** While a buy or sell box is open, EVI shows the price it suggests
for that item, for any item you have open. Press **F8** (you can change this) and it types the
number into the box you already have open. You still press Enter and click Confirm yourself.

**A check on your own offers.** EVI watches what you already have standing and tells you when one
needs attention. It will warn you if you are selling below what the stock cost you, if the market
has moved away from your price, or if an offer looks unlikely to fill in time.

**Suggestions sized to you.** It knows how much cash you are carrying and will not suggest a trade
you cannot afford, more of an item than the market is actually buying, or more than the Grand
Exchange buy limit allows.

**Settings that fit how you trade.** How long you are willing to wait for a trade, the smallest
profit worth your time, how cautious to be, and whether to rank from your own history or the whole
market.

**Your profit over time.** The companion app includes a browser dashboard for reviewing your trades
and charting what you have actually made. It is included with the bridge — nothing extra to buy.

---

## What it will never do

This matters, so it is stated plainly rather than buried:

- **It never trades for you.** It never opens a menu, clicks a button, chooses an item, or confirms
  an offer. Every trade is placed and confirmed by you. The hotkey fills in a number in a box you
  already opened — the same thing RuneLite's own 25% / 50% / 100% quantity buttons do.
- **Nothing you do leaves your computer.** The plugin talks to `127.0.0.1` — your own machine — and
  nowhere else. No account, no sign-up, no analytics, no server belonging to anyone else.
- **Your account name is never sent.** Accounts are told apart by a scrambled pseudonym.

---

## Privacy, in full

Everything below is sent only to the companion app on your own computer.

- **Your Grand Exchange offers** — item, price, quantity, and how much has filled.
- **Your coin count**, so suggestions fit what you can actually afford.
- **Whether the world is members or free-to-play**, so you are not offered members' items on a
  free-to-play world.
- **Whether items it believes you still hold are in your inventory.** It reports only those item
  IDs back, never the rest of your inventory.
- **Your full inventory** — only if you switch on *Suggest selling idle inventory*, which is **off
  by default**.

Nothing is read from your bank or your chat, and nothing is collected about other players.

---

## Questions or problems

There is a Discord for support: **https://discord.gg/gFcEBHknVN**

Both halves are open source, so you can read exactly what they do:

- **This plugin:** https://github.com/therealLeEvi/evi-live-plugin
- **The companion app:** https://github.com/therealLeEvi/evi-live-bridge — its `LOCAL-API.md` lists
  every request this plugin makes, and its README covers setup and exactly what is stored.

## License

BSD 2-Clause. See `LICENSE`.
