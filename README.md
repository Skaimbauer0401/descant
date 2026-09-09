# Descant

**Tell your Minecraft character what to do in plain English, and it goes and does it.**

```
/descant ai make me a wooden pickaxe
/descant ai get 20 coal, smelt the iron in my furnace and put it all in the chest
/descant ai there's a village north of here somewhere — go find it and remember where it is
```

A language model drives a real pathfinding bot: it walks, mines, bridges, jumps, crafts, smelts,
fights and banks the haul, working the steps out for itself. Client-side only, and free to run.

---

## The setup is two lines

1. Install [Ollama](https://ollama.com).
2. Run `ollama signin` once.

That is all. No API key, no billing, no account beyond the free one — the mod talks to the Ollama
daemon on your own machine, which proxies a cloud model for you. Then type `/descant ai` and ask for
something.

---

## How the model drives the bot

It never touches the game directly. It is handed **24 actions** — the same ones the chat commands
use — as a typed menu with a written description of each, and it answers with calls that run through
one door. So it can only ever do things that were written and tested, and a bad answer is a wrong
action rather than a corrupted world.

Two decisions make it work with small, free models:

* **Every call runs to completion before the model hears back.** `goto` accepts a route in
  milliseconds but the walk takes a minute; reporting straight away would leave the model polling
  `status` until something changed, which small models do badly — they give up early or fill their
  context with a hundred identical replies. One call, one finished job, one report of where it
  actually ended up.
* **It is not allowed to hand the work back.** *"Please place a crafting table for me"* is a bot that
  has stopped working, and models reach for it constantly. A missing material is not a reason to ask,
  it is the next job. It only asks where there is genuinely no function — enchanting, anvils, trading,
  or a choice only you can make.

`/descant ai stop` calls it off, and stops the bot with it. `/descant api` writes the whole action
menu out as JSON if you want to see exactly what the model is given.

---

## Which model

The default is **`gemma4:cloud`**, chosen by testing rather than reputation: asked to gather iron and
bank it in a chest, it called both actions in the right order in half a second.

| Model | Free? | |
| --- | --- | --- |
| `gemma4:cloud` | ✅ | The default. Fast and gets the order right |
| `gpt-oss:120b-cloud` | ✅ | Stopped after the mining step in the same test |
| `nemotron-3-ultra:cloud` | ✅ | Correct, but over a minute per turn |
| minimax, glm-5, deepseek-v4, qwen3.5, kimi | ❌ | Need a paid Ollama plan or credits |

## Other providers

All on the settings screen (`G`), all optional:

| Provider | What it needs | Cost |
| --- | --- | --- |
| **Ollama cloud** *(default)* | `ollama signin`, once | Free |
| **Ollama local** | A tool-capable model on your own GPU | Free, and works offline |
| **Claude** | `ANTHROPIC_API_KEY` | Per token |
| **ChatGPT** | `OPENAI_API_KEY` | Per token |
| **Qwen** | `DASHSCOPE_API_KEY` | Per token |
| **Kimi** | `MOONSHOT_API_KEY` | Per token |

Keys are read from your environment first, then `config/descant-keys.properties` — never from a
setting, since settings get printed into chat and into the schema the model itself reads. Ollama is
the provider that has actually been used in anger; the paid four are implemented but have not yet been
run through a full job.

---

## What it can be asked for

* **Travel** — walks, mines through obstructions, bridges gaps, sprint-jumps ledges, climbs ladders,
  swims, opens doors.
* **Gathering** — hunts a block or mob wherever it is, mines it, moves to the next until the count is
  met, picks up the drops.
* **Banking** — a full inventory goes to the chest you pointed at, then back to the job.
* **Crafting and smelting** — walks to a crafting table when a recipe needs one; loads a furnace,
  picks a fuel, waits and collects.
* **Staying alive** — eats when hurt, shields against arrows, fights back, swats ghast fireballs back
  where they came from, leaves neutral mobs alone.
* **Places** — `remember` and `recall` names per world; it also notes landmarks it walks past.

Every one of them is also a chat command, if you would rather drive it yourself:

| Command | Effect |
| --- | --- |
| `/descant goto <x> <y> <z>` · `<x> <z>` · `<y>` | Travel there. Two arguments means "that column, whatever height the ground is" — the one for long trips |
| `/descant walk …` · `/descant dig …` | Same, but never touching the world / mining from the first step |
| `/descant find <block\|mob> [keep going] [count]` | Nearest one; `true` keeps going until the count is met |
| `/descant locate <block>` · `/descant look` | Coordinates without moving · what's around you |
| `/descant mine <x> <y> <z>` · `/descant place <block>` | Break that block · put one down |
| `/descant craft <item> [n]` · `/descant smelt <item> [n]` | Make it · run a furnace |
| `/descant chest [looking\|nearest\|off]` · `/descant deposit` · `/descant take` | Bank the haul, or fetch it back |
| `/descant remember <name>` · `/descant recall [name]` | Save this spot · go back to one |
| `/descant set [name] [value]` · `/descant config` | 84 settings · the settings screen (`G`) |
| `/descant stop` | Stop, now |

Press any movement key and the bot stops. It never fights you for control of your own character.

---

## Installation

* Minecraft **26.2**, Fabric Loader **0.19.3+**
* [Fabric API](https://modrinth.com/mod/fabric-api) — required
* [Ollama](https://ollama.com) — for `/descant ai`, which is the point of the mod

---

## FAQ

- **Does it need to be installed on the server?**

No. It drives your character through the ordinary movement keys, so what leaves your machine is a
normal movement packet. The server needs nothing.

- **Does the AI cost anything?**

No. A free Ollama account runs the default model. You only pay if you deliberately switch to Claude,
ChatGPT, Qwen or Kimi.

- **Is my world sent to a server?**

With a cloud model, the conversation is — your instruction, the action menu, and what the bot reports
back. Pick **Ollama local** on the settings screen to keep everything on your machine.

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
* No measurement yet of how well any given model actually plays.

---

## Disclaimer

**Movement automation is against the rules on most multiplayer servers**, whether or not it looks
identical to walking. Singleplayer and your own servers are the intended home; anywhere else is yours
to check first. No anti-cheat evasion of any kind is built into this mod, and none will be added.

## Licence

**LGPL-3.0-only.** Full text in `LICENSE.txt`.
