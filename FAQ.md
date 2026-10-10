# EVI Live: frequently asked questions

## A fresh install says "Loading price history" and suggests no buys. Is it broken?

No. The first time EVI runs it downloads two weeks of hourly Grand Exchange prices from the OSRS Wiki: 337 requests,
spaced at least 2.5 seconds apart so EVI is a polite user of a free service. That takes about 16 to 21 minutes, once.

EVI ranks buys on the full two weeks, because a short window is easily fooled by one unusual day. So until the history
is complete it does not suggest buys at all. Everything else works from the first minute: advice about offers you
already have, sell reminders for what you hold, prices in the offer box, and the trade journal.

After that, EVI only fetches the hours it missed while RuneLite was closed. After a day away that takes about a
minute. If the Wiki does not answer for some hours, the sidebar says so and EVI asks again next time RuneLite starts.

## Why does EVI keep suggesting runes and other cheap bulk items?

Because they are what passes its checks most often. Every buy EVI suggests must:

- earn more than the Grand Exchange tax on the item, with margin to spare;
- trade often enough that you can actually get out again;
- fit within the item's buy limit and what the market trades in a typical hour.

Cheap items that trade in huge volumes clear all three easily. Each unit earns only a little, so the profit comes from
the quantity. Expensive gear often has a much bigger margin per item but trades too rarely to pass the second check,
and that check is there because an item you cannot sell is how GP gets stuck.

If you would rather not trade bulk, set *Suggestion focus* to *Gear*, or raise *Min predicted profit*. A longer *Trade
pace* changes the picks too, because EVI then sizes a trade over a longer window.

## All my Grand Exchange slots are full and EVI suggests nothing to buy.

That is deliberate. With all 8 slots in use there is nowhere to place another offer, so EVI does not rank buys at all;
it says so in the sidebar rather than pretending nothing was good enough. If some offers have finished, collecting
them frees a slot.

EVI also holds back a new buy when your free slots are needed to sell stock you are holding with no sell offer placed.
Place those sells and suggestions resume. Filling every slot with purchases and leaving stock with no way out is how
capital gets stuck.

## What do the buttons under a suggestion do?

- **Took it** -- you acted on this suggestion. EVI records it on your computer so it can measure whether following its
  suggestions actually made GP. Press again to take it back.
- **Mine** -- for something you are holding: it was bought for your own use, not to flip. EVI stops suggesting you sell
  it and it never counts toward profit. Only that purchase is affected; buying the item again later is not.
- **Gone** -- for something you are holding but no longer have: used in-game, or sold while EVI was not running. EVI
  closes that purchase. Whatever part of it EVI saw sold still counts toward profit.
- **Skip** -- set this suggestion aside for 4 hours and show the next-best one. Offers you have already placed are not
  affected.
- **Block** -- never suggest buying this item again on this character.

Block only stops BUY suggestions. If you already hold some of the item, EVI still reminds you to sell it, because going
quiet about stock you own is how GP ends up stuck.

## I blocked an item on one character and my other character still gets it suggested.

That is by design: blocks are kept per character, because what one account wants to trade is often not what another
does. Each character's blocked items are listed under *Blocked items* in the sidebar, each with an Unblock button, and
that list shows the character you are logged in as.

Before you log in, EVI uses the list of the character you were last logged in as, so a blocked item does not flash up
on the login screen. The list itself is hidden until you log in, so nothing can be unblocked on the wrong character.

If you imported blocks from an older EVI version, they were added to every character once. Unblocking an imported item
on one character does not bring it back on another.

## Why does EVI say only one thing about each of my sell offers?

Because two pieces of advice about the same offer can contradict each other, and the wrong one can cost GP. For each
sell offer EVI picks one sentence:

- if its relist advice has something to say (your price has drifted from the market, or the offer has stood for a good part of
  your trade pace without filling), that is the sentence you see;
- if the market is below what the stock cost you, EVI will **not** tell you to "take the current price", because that
  locks in a loss. Once the offer has stood for 15 minutes, the relist advice names the loss plainly so you can decide;
- otherwise, when the market has moved more than 5% from your price, EVI points that out.

Buy offers are unaffected.

## How risky is a suggestion?

*Placeholder: this entry will explain what the risk levels mean, with their measured odds, once risk levels return. EVI
does not show odds on its buttons or tooltips.*

## Does EVI send anything about me?

No. The only network traffic is public price downloads from the OSRS Wiki (prices.runescape.wiki): the same all-items
requests for every player, with nothing about you, your account, your offers or your gold. The Wiki can see your IP
address, as with any website. The README has the full list of what is fetched and when.

## Why does EVI ask me to share my trade log?

Real offers from many players are the only way to check and improve how well EVI predicts whether an offer will fill.
One player's offers are not enough. Sharing is entirely voluntary: press *Not now* and the invitation stays away until
the next EVI update. The README's "Sharing your trade log" section lists exactly what the file holds.

## Does EVI send my trades anywhere?

No. *Save my trade log...* only saves a file on your computer, in `.runelite/plugin-data/evi-flipping/share/`. It stays
there unless you upload it yourself.

## Where is my data, and how do I move it to another computer?

Everything is in `.runelite/plugin-data/evi-flipping/` in your RuneLite folder. Copy the whole folder, including
`identity-salt.txt`, which is what lets EVI recognise your accounts in its journal. Your settings and blocked items are
kept with RuneLite's own settings.

## I used EVI before version 4.0.0. What changed in my settings?

Your settings carry over, with two exceptions:

- **Exit-risk check** now offers only *Off* and *~6 hours* (the ~1 hour and Overnight choices gave no forecast and are
  gone). It starts at *Off* for everyone, so if you used it, switch it on again under *Safety checks*.
- **Suggestion focus** no longer has *Same as scanner*. If it was set to that, it is now *All items*, which is what it
  meant without the scanner. *Gear* and *Bulk* are kept.

EVI now keeps its files in `.runelite/plugin-data/evi-flipping/`. Earlier versions used
`.runelite/plugin-data/evi-live/`, which held only the old pairing key; nothing there is needed any more, and you can
delete that folder.

## I used an older EVI with a companion app. Can I keep my history?

Yes, once. The README's "Bringing over an older EVI history" section says which files to copy and which setting to
switch on. Nothing needs to be downloaded or installed.

## I renamed my character, and the trades I made under my old name did not come across.

Trades the old companion app took from another tracker are kept under the character name they were made on, and each
character takes only its own name's. Before importing, add your old name to `import/preferences.json`, mapped to your
current name: `"oldNames": {"OldName": "Current Name"}` (one entry per old name, inside the file's outer braces; if
there is no such file, the whole file can be `{"oldNames": {"OldName": "Current Name"}}`). Nothing is ever guessed: an
old name only counts when you list it. An account is imported once, so add the mapping before you log in with the
setting on.
