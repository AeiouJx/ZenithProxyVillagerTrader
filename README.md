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

### Display-frame setup (automatic configuration)

`trader scan` reads item frames in the configured area centred on the bot and writes ordinary trade entries compatible with the original configuration format. The default area is 64×64 blocks; set it with `trader scanRange <blocks>`.

* Put an **emerald** in an item frame attached directly to its restock container.
* Optionally put a **book** in another item frame attached directly to its restock container.
* Put each item you want to buy in an item frame with its output container exactly **two blocks below** the frame.
* Run `trader scan`, then `trader list` to review the generated trades before enabling the module.

Useful filters:

* Diamond armour, diamond sword, and diamond tools are assigned to their vanilla villager professions automatically.
* A displayed enchanted book must contain exactly one enchantment; its name and level become the generated trade ID and enchantment rule.
* `trader restockWait <seconds>` controls how long the module waits after a full pass buys nothing.

### Actions Loop

This module is intended to be run continuously. 

It will repeatedly attempt all configured trades one at a time.

## Thanks

Special thanks to @Devin for providing a reference trading module and explanation
