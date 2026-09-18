# ZenithProxy Villager Trader Plugin

Automatically buys and sells items with villagers.

Includes automatic restocking, storing, and highly configurable trade options.

## Usage

You need the following setup ingame:

1. A villager trading hall. Its best to be compact - the plugin won't go searching for villagers outside render distance.
1. Chests to restock trade inputs from. You can set up a hopper system to constantly refill the chests.
1. Chests to store the items bought from trades. You can set up a hopper system to transfer items out to larger storage systems.

### Commands

* `trader on/off`
* `trader add <id> <profession> <inputItem1> <outputItem> <inputItem1ChestPos> <outputChestPos>`
  * One input item trades
* `trader add <id> <profession> <inputItem1> <inputItem2> <outputItem> <inputItem1ChestPos> <inputItem2ChestPos> <outputChestPos>`
  * Two input items trades
* `trader set help`
  * Prints many additional trade configuration subcommands, like enchantments, prices, and restock settings
* `trader del <id>`
* `trader clear`
* `trader list`
* `trader waitForInteractionTimeout <ticks>`

### Actions Loop

This module is intended to be run continuously. 

It will repeatedly attempt all configured trades one at a time.

## dev/plus Branch

The `dev/plus` branch extends the upstream `mainline` behavior while keeping the original
upstream trading paths fully intact as fallbacks.

### One-shot shift-click purchasing (V2)

The biggest change is a new fast-purchase path for enchanted books:

- The module selects the configured target trade and shift-clicks **once** to buy the full
  12 available trades in about **1 second**, instead of the upstream path which clicks
  trade-by-trade and took ~11 seconds per 12 books. This matches what a real player does.
- `canShiftClickPurchaseV2()` decides when the one-shot path is safe. It only counts villager
  offers whose **enchantment and level exactly match** the configured target
  (`sameEnchantedBook()`), so a librarian offering several different enchanted books is still
  considered safe to shift-click once: only the selected book can be bought.
- `ShiftMerchantResult` performs the actual click with an extra guard: before sending the
  shift-click it verifies that the merchant result slot actually contains the selected target
  item. On race conditions where the `SelectTrade` packet has not yet been reflected in the
  container cache (the result slot would otherwise show the default/highlighted offer, such as
  a librarian's bookshelf or lantern), the click is safely skipped instead of buying the wrong
  item. A later pass retries the purchase automatically.
- If a villager really does have multiple offers with the *same* enchant and level, the module
  falls back to the **upstream** logic: batched shift-clicks, then per-trade left-clicks.

### Storage sweep across all configured trades

- After a purchase, the module also stores any other configured trade output currently in the
  inventory (`findStorableOutputTrade` / `findAnyStorableOutputTrade`), and routes the player
  to the matching output chest before continuing.
- Output counting is enchantment-aware (`countOutputItem`), so different enchanted books never
  get mixed up when deciding what to store.

### Offer matching

- `findMatchingTrade` resolves the configured trade to the actual offer on a villager by
  profession, output item, enchantments and inputs, and selects it before purchasing.

## Thanks

Special thanks to @Devin for providing a reference trading module and explanation
