# Descant

A pathfinding bot that plays your own character. Tell it where to go and it walks there — mining,
bridging, jumping and climbing as needed. Or just say what you want, and a language model works out
the steps.

Client-side only. Nothing to install on the server.

---

## Features

* **Goes anywhere.** `/descant goto -1240 63 880` and your character walks there by itself: A\* over
  the block grid, mining through obstructions, bridging gaps, sprint-jumping ledges, climbing ladders,
  swimming, opening doors.
* **Gathers.** `/descant find iron_ore true 16` hunts down sixteen iron ore wherever they are, mines
  each one, moves to the next and picks up the drops.
* **Banks the haul.** Point at a chest and a full inventory sends it there — then back to the job it
  was doing.
* **Crafts and smelts.** Walks to a crafting table when a recipe needs one, and runs a furnace itself:
  finds it, loads it, picks a fuel, waits, collects.
* **Stays alive.** Eats when hurt, raises a shield against arrows, fights back, swats ghast fireballs
  back where they came from, leaves neutral mobs alone.
* **Remembers places.** `/descant remember base`, then `/descant recall base`, per world. It also
  notes landmarks it walks past.
* **Understands plain English.** `/descant ai make me a wooden pickaxe` and it works out the whole
  chain — find wood, planks, table, sticks, pickaxe.
* **84 settings**, tab-completed, on a screen bound to `G`.

Press any movement key and the bot stops. It never fights you for control of your own character.

---

## Commands

| Command | Effect |
| --- | --- |
| `/descant goto <x> <y> <z>` · `<x> <z>` · `<y>` | Travel there. Two arguments means "that column, whatever height the ground is" — the one to use for long trips |
| `/descant walk …` · `/descant dig …` | Same, but never touching the world / mining from the first step |
| `/descant find <block\|mob> [keep going] [count]` | Go to the nearest one; `true` keeps going until the count is met |
| `/descant locate <block> [count]` | Coordinates of the nearest few, without moving |
| `/descant mine <x> <y> <z>` · `/descant place <block> [x y z]` | Break that block · put one down |
| `/descant craft <item> [n]` · `/descant smelt <item> [n]` | Make it · run a furnace |
| `/descant chest [looking\|nearest\|off]` | Pick the chest to bank into |
| `/descant deposit [haul\|food\|all\|<item>]` · `/descant take <…>` | Stash · fetch back out |
| `/descant remember <name>` · `/descant recall [name]` | Save this spot · go back to one |
| `/descant look` · `/descant inventory` · `/descant status` | What's around · what's carried · what it's doing |
| `/descant ai <what you want>` · `/descant ai stop` | Hand the job to a model · call it off |
| `/descant set [name] [value]` · `/descant config` | Settings by name · the settings screen (`G`) |
| `/descant stop` | Stop, now |

---

## Talking to it

Install [Ollama](https://ollama.com), run `ollama signin` once, and that is the whole setup — no API
key, nothing to pay:

```
/descant ai get 20 coal, smelt the iron in my furnace and put it all in the chest
```

The model never touches the game. It gets the same 24 actions the commands use, as a typed menu, and
each call runs to completion before it hears back — so it never has to poll, and it can only reach
things that were written and tested.

Other providers are on the settings screen: a local Ollama model for offline play, or Claude, ChatGPT,
Qwen or Kimi with an API key in `config/descant-keys.properties`. Ollama is the one that has been used
in anger; the paid four are implemented but not yet run through a full job.

---

## Installation

* Minecraft **26.2**, Fabric Loader **0.19.3+**
* [Fabric API](https://modrinth.com/mod/fabric-api) — required
* [Ollama](https://ollama.com) — optional, only for `/descant ai`

---

## FAQ

- **Does it need to be installed on the server?**

No. It drives your character through the ordinary movement keys, so what leaves your machine is a
normal movement packet. The server needs nothing.

- **Which AI model should I use?**

The default, `gemma4:cloud`, was picked by testing: asked to gather iron and bank it in a chest, it
called both actions in the right order in half a second. It is free on an Ollama account, as are
`gpt-oss:120b-cloud` and `nemotron-3-ultra:cloud`. The bigger names need a paid plan.

- **Why did it dig a hole through my base?**

`goto` mines through whatever is in the way. It will not break chests, furnaces or anything else
holding items, but it will tunnel through a wall — use `/descant walk`, which fails instead of digging.

- **It says "no route" and I can see the way.**

The search is capped so it cannot stall the game. Give it a nearer goal, or use the two-argument
`/descant goto <x> <z>` for long trips: naming an exact Y a thousand blocks out makes it tunnel down
to your number on arrival.

---

## Limitations

* No nether portals, boats, minecarts or elytra.
* Bridging needs an existing block to place against — no sneak-back-place over open air.
* Snow layers, slabs and fences are the recurring soft spot; it breaks its way out rather than
  modelling every case.
* Smithing tables, anvils, enchanting tables and brewing stands can be opened but not operated.
* No automated tests, and no measurement of how well any given model actually plays.

---

## Disclaimer

**Movement automation is against the rules on most multiplayer servers**, whether or not it looks
identical to walking. Singleplayer and your own servers are the intended home; anywhere else is yours
to check first. No anti-cheat evasion of any kind is built into this mod, and none will be added.

## Licence

**LGPL-3.0-only.** Full text in `LICENSE.txt`.
