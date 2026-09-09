# mcbot

**A pathfinding bot that plays your own character — by command, or in plain words.**

Two things live in this mod.

The first is a **navigator**. `/mcbot goto -1240 63 880` and your character walks there: A\* over the
block grid, mining through what is in the way, bridging gaps, sprint-jumping ledges, climbing
ladders, swimming, eating when it gets hurt and fighting back when something jumps it.

The second is an **agent**. `/mcbot ai make me a wooden pickaxe` hands those same abilities to a
language model as a menu of 24 typed functions, and it works the chain out itself — find wood, craft
planks, craft a table, craft sticks, craft the pickaxe.

It is **client-side only**. Nothing is installed on the server. No position is ever forced: the bot
steers by writing the same input record vanilla writes when you hold `W`, so what leaves your machine
is an ordinary movement packet with nothing unusual in it.

> ⚠️ **Movement automation is against the rules on most multiplayer servers.** That it is
> indistinguishable from walking at the packet level does not make it allowed. Singleplayer and your
> own servers are the intended home; anywhere else is yours to check first. **No anti-cheat evasion of
> any kind is built into this mod, and none will be added.**

---

## Sixty seconds

1. Fabric Loader `0.19.3+` for **Minecraft 26.2**, then drop **Fabric API** and mcbot into `mods/`.
2. In game:
   - `/mcbot goto -240 500` — travel to that column, at whatever height the ground turns out to be.
     **This is the form to use for long trips**: naming an exact Y a thousand blocks out means guessing
     the terrain height, and guessing wrong makes the bot tunnel down to your number on arrival.
   - `/mcbot find iron_ore true 16` — hunt iron ore, mine it, move to the next, stop at sixteen, pick
     up the drops.
   - `/mcbot chest looking` then `/mcbot deposit` — bank the haul in the chest under your crosshair.
   - Press **`G`** for the settings screen (or `/mcbot config`).
3. For the talking half: install [Ollama](https://ollama.com), run `ollama signin` once, then
   `/mcbot ai get me some iron and put it in that chest`.

**Press any movement key and the bot stops.** It should never fight you for control of your own
character.

---

## What it can do

| | |
| --- | --- |
| **Travel** | `goto` in three forms — exact block, column (`x z`), or height (`y`). `walk` refuses to touch the world; `dig` mines from the first step. Diagonals, ladders and vines, doors, swimming, falls it can survive. |
| **Terrain** | Breaks obstructions, bridges gaps, pillars up, sprint-jumps gaps up to three blocks wide, treats bottom slabs and stairs as half-supports, and breaks its way out of a physical dead end rather than standing there. |
| **Staying alive** | Eats when hurt or hungry, fights back, and finds air when it is drowning. Projectiles get the answer that actually works on them: a ghast fireball is **swatted** back the way it came, an arrow or a wither skull is **blocked** with a shield, and a wind charge is ignored because nothing else is right. Neutral mobs are left alone unless they start it. |
| **Gathering** | `find <block or mob>` goes to the nearest one — add `true` and it keeps going, mining each and moving to the next until the count is met. Picks up what it drops. When the inventory fills it walks to the chest you named, banks the haul, and resumes the job where it left off. |
| **Working** | `craft` (2×2 in hand, 3×3 at a table it finds for itself), `smelt` (finds a furnace, loads it, picks a fuel, waits, collects), `place`, `mine`, `use`, `equip`, `armour`, `drop`, `deposit`, `take`. |
| **Perception** | `look` — coordinates, facing, dimension, biome, time, weather, light level, creatures nearby. `locate` — exact coordinates of the nearest several, spread out so one vein counts once, **without moving**. |
| **Memory** | `remember <name>` / `recall` / `forget`, scoped per world. It also quietly notes landmarks it walks past, so "go back to that village" can be answered. |

---

## Telling it what you want

```
/mcbot ai get 20 coal, smelt the iron in my furnace and put it all in the chest
/mcbot ai stop
```

The model never touches the game. It is handed **the same 24 actions the chat commands use**, as a
typed schema with a written description each, and its calls run through the one door everything else
goes through — so it can only reach things that were written and tested.

Two decisions make it work with small models:

- **Calls run to completion before the model hears back.** `goto` returns the instant a route is
  *accepted*, long before the bot arrives; reporting that immediately would leave the model polling
  `status` until something changed, which small models do badly — they either give up early or fill
  their context with a hundred identical replies. One call, one finished task, one report of where it
  actually ended up.
- **It is not allowed to hand the work back.** "Please place a crafting table for me" is a bot that
  has stopped working, and models reach for it constantly. A missing material is not a reason to ask —
  it is the next job. It asks only where there is genuinely no function: enchanting, anvils, trading,
  brewing, or a choice only you can make.

### Providers

| Provider | How it is reached | Cost |
| --- | --- | --- |
| `ollama-cloud` **(default)** | The local Ollama daemon, proxied. `ollama signin` once. | Free on the starter models |
| `ollama-local` | The same daemon, model on your own GPU | Free, and offline |
| `claude` | `ANTHROPIC_API_KEY` | Per token |
| `chatgpt` | `OPENAI_API_KEY` | Per token |
| `qwen` | `DASHSCOPE_API_KEY` | Per token |
| `kimi` | `MOONSHOT_API_KEY` | Per token |

The default model is **`gemma4:cloud`**, picked by measurement rather than reputation: asked to gather
iron and bank it in a chest, it called both actions in the right order in half a second.
`gpt-oss:120b-cloud` stopped after the mining step, and `nemotron-3-ultra:cloud` took over a minute
for one turn — free, but a very long time to watch nothing happen. Those three are what a **free**
Ollama account can run today; minimax, glm-5, deepseek-v4, qwen3.5 and the Kimi models all want a
subscription or credits. *(Checked September 2026. Which models are free changes as often as which
models exist, so the settings screen fetches the real list rather than trusting this table.)*

Keys are read from **the environment first**, then `config/mcbot-keys.properties` — and never from a
setting. Settings get listed by `/mcbot set`, echoed into chat, and written into the schema the model
itself reads; that is three separate ways for a credential to end up somewhere it cannot be taken back
from.

**Honest status:** Ollama is the provider that has actually driven the bot through real tasks. Claude,
ChatGPT, Qwen and Kimi are implemented against their published contracts and will fetch their model
lists (so a dead key shows up on the settings screen rather than mid-task), but none has yet been run
through a full multi-turn job.

---

## Commands

| Command | Effect |
| --- | --- |
| `/mcbot goto <x> <y> <z>` · `<x> <z>` · `<y>` | Travel — the number of arguments picks the kind of goal |
| `/mcbot walk …` · `/mcbot dig …` | The same three forms, never touching the world / mining from the start |
| `/mcbot find <block\|mob> [keep going] [count]` | Go to the nearest one; `true` keeps going until the count is met |
| `/mcbot locate <block> [count]` | Coordinates of the nearest several, without moving |
| `/mcbot mine <x> <y> <z>` · `/mcbot place <block> [x y z]` | Break that block · put one down |
| `/mcbot craft <item> [n]` · `/mcbot smelt <item> [n]` · `/mcbot use` | Make it · run a furnace · right-click what it is looking at |
| `/mcbot inventory` · `/mcbot equip` · `/mcbot armour` · `/mcbot drop` | Carry, hold, wear, throw away |
| `/mcbot chest [looking\|nearest\|off]` | Pick the container to bank into — `looking` is the one under your crosshair |
| `/mcbot deposit [haul\|food\|all\|<item>]` · `/mcbot take <…> [n]` | Stash · fetch back out |
| `/mcbot remember <name>` · `/mcbot recall [name]` · `/mcbot forget <name>` | Named places, per world |
| `/mcbot look` · `/mcbot status` · `/mcbot path` | Surroundings · what it is doing · toggle the route display |
| `/mcbot set [name] [value]` · `/mcbot config` | 84 settings, tab-completed · the settings screen |
| `/mcbot ai <what you want>` · `/mcbot ai stop` | Hand the job to a model · call it off |
| `/mcbot api` | Write the action menu out as `mcbot-actions.json` — the exact schema a model gets |
| `/mcbot stop` | Stop, now |

---

## Settings

**84 of them**, all tab-completed on `/mcbot set`, and all on a screen bound to **`G`**: a *Simple*
tab for the ones anyone actually changes — the model, travelling, scaffolding, banking the haul,
protecting your build, the route display, memory — and an *Advanced* tab for the pathfinder's own
numbers. Only settings you have
actually changed are written to disk, so the file stays readable and a future default can still move.

Every cost in the planner is expressed in **game ticks**, so the A\* cost function and its heuristic
share a unit and can be compared honestly. What is *not* a setting is anything that is a fact about
Minecraft rather than a preference — those are constants, deliberately unreachable.

---

## How it works, for the curious

- **Steering goes through the vanilla input record.** A mixin at the tail of `KeyboardInput.tick()`
  overwrites both `keyPresses` *and* the cached `moveVector` — the second one is what the walking
  physics actually reads, and writing only the first gives you a bot that jumps on the spot forever.
  Bot movement then runs through ordinary physics and the ordinary input packet.
- **The A\* is time-sliced on the client thread, not moved onto a worker.** World access off-thread is
  unsafe, so the search does about 3 ms of work per tick and returns. Consistent reads, no stutter, no
  locking — at the cost of a cap on search size.
- **Partial paths plus replanning are what make long trips work.** When the budget runs out the search
  returns the route to whatever got closest, the bot walks it, and it replans from where it landed —
  which is also how it copes with terrain that had not loaded when planning started.
- **Every judgement about the world lives in exactly one place** (`WorldView`). Walkable, standable,
  breakable, *fillable* — one answer each, so the planner and the executor can never disagree. This
  rule exists because they once did: "is this space already filled?" had been copy-pasted three times
  and drifted, and a snowed-over block read as full to one and empty to another.
- **Nothing is judged against one global number.** Fallback quality is measured per cost coefficient,
  timeouts per movement against that movement's own estimate, sprint per whether the *next* move
  continues in the same direction. A flat timeout has to be generous enough for the slowest legitimate
  move, which leaves it far too patient with the common failure.

---

## Inspirations

**[Baritone](https://github.com/cabaletta/baritone)** — the movement layer is a deliberate,
studied reimplementation of Baritone's model rather than a loose homage. Read out of the actual
sources and copied: the `Goal` abstraction (a pathfinder never needs to know *where* it is going, only
"am I there?" and "how much further?"), the octile heuristic, seven best-so-far fallback paths blended
at different cost coefficients, backtrack cost favouring on replan, per-movement timeouts, the
collinearity rule for sprinting, the positional parkour launch trigger, and the vanilla-derived cost
constants. Also copied: what Baritone *doesn't* do — no path smoothing, no landing brake, no ladder
move type. Each of those absences turned out to fix a symptom that had been chased for days. **mcbot
is LGPL-3.0 because Baritone is.**

**[Voyager](https://voyager.minedojo.org/)** (NVIDIA / Caltech, 2023) — the idea that a language model
can play Minecraft if you stop handing it a keyboard and hand it a **library of named skills with
written descriptions** instead. mcbot's action API is that idea, typed: name, description, typed
parameters, one door in. The system prompt is treated as the real program, and its budget as a budget
— anything a single action's own description can say belongs there, not in the prompt that is paid for
on every round of every task.

**[Mindcraft](https://github.com/kolbytn/mindcraft)** — the LLM-bot framework that has the thing mcbot
still lacks: a task harness that can say *"model X completes the wooden-pickaxe chain 8 times in 10"*.
The test set here exists on paper — chest-then-find sequencing, count inclusion, the health threshold,
the pickaxe dependency chain — it has simply never been automated. That is the next honest step.

**What is different:** Voyager and Mindcraft both drive a headless
[Mineflayer](https://github.com/PrismarineJS/mineflayer) client — a second, separate player. Baritone
drives your own character but has no language layer. mcbot is the pair of them: an in-client bot,
playing **your** character in **your** game, that you can also just talk to.

---

## Not yet

Written down because a mod page that lists only what works is not much use.

- No nether portals, no boats or minecarts, no elytra.
- Bridging only places against an existing full-cube neighbour — no sneak-back-place over open air.
- Parkour-place (jump, then place a block mid-air to extend the reach) is the one Baritone parkour
  feature not copied; it needs mid-air placement, which the placer does not do yet.
- No chunk cache, so search size is capped by what the client thread can afford per tick. This is the
  biggest remaining architectural gap.
- Awkward-block geometry — snow layers, slabs, leaves, fences — is the recurring soft spot. The escape
  hatch is break-to-unstuck rather than modelling every permutation.
- Stations beyond the furnace: smithing tables, anvils, enchanting tables and brewing stands can be
  *opened* but not operated. The furnace is now the pattern to copy for each.
- The hosted providers (Claude, ChatGPT, Qwen, Kimi) have not yet driven a full multi-turn task.
- No automated tests, no CI, and no measurement of how well any given model actually plays.

---

## Requirements

| | |
| --- | --- |
| Minecraft | **26.2** |
| Loader | Fabric `0.19.3+` |
| Depends on | Fabric API `0.155.2+26.2` |
| Java | 25 |
| Side | **Client only** — the server needs nothing |
| Optional | [Ollama](https://ollama.com), for the `/mcbot ai` half |

## Licence

**LGPL-3.0-only.** The movement layer is derived from [Baritone](https://github.com/cabaletta/baritone)
(LGPL-3.0) by deliberate study and reimplementation, and this mod is licensed to match.
