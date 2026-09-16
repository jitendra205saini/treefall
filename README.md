# TreeFall

Break one log and the whole tree topples over, then replants itself.

Built for **Paper 1.26.3**.

## Why it is different

TreeFall does not keep a list of wood types. It reads Minecraft's own block
tags — `minecraft:logs`, `minecraft:leaves`, `minecraft:saplings` — so when
Mojang adds a new tree it works on day one, with no update and no new config.

Poplar, added in 26.3, works without the word "poplar" appearing anywhere in
the source. So will whatever comes next.

The sapling is worked out from the log's own name (`poplar_log` →
`poplar_sapling`), with three exceptions for mangrove, crimson and warped.

## What it does

- **Fells the whole tree** from one broken log, following diagonal branches
- **Topples it** as falling blocks, with higher blocks pushed harder so the
  tree leans over as it comes down
- **Brings the crown down too** — leaves, and anything growing on the tree:
  jungle vines, cocoa pods, shelf mushrooms, bee nests, hanging moss, and the
  wart blocks that form a nether fungus canopy
- **Replants** — including all four saplings for 2×2 trees like dark oak and
  pale oak, which cannot regrow from a single one
- **Respects claims** — every block is checked through a real `BlockBreakEvent`,
  so GriefPrevention, WorldGuard, Towny and anything else stay in charge
- **Blocks the obvious exploit** — logs a player placed by hand are remembered
  and refused, so a stacked-up fake tree cannot be farmed. No CoreProtect or
  Prism needed

## Commands

| Command | Permission | |
|---|---|---|
| `/tf info` | — | current settings |
| `/tf reload` | `treefall.admin` | reload config.yml |
| `/tf toggle [player]` | `treefall.toggle.other` for others | turn it off for yourself |
| `/tf forcebreak [radius]` | `treefall.forcebreak` | fell every tree in range |
| `/tf forcegrow [radius]` | `treefall.forcegrow` | bone-meal every sapling in range |

## Permissions

| Node | Default | |
|---|---|---|
| `treefall.use` | everyone | fell trees |
| `treefall.use.<wood>` | everyone | per-species, when `per-tree-permissions` is on |
| `treefall.admin` | op | reload, break guarded saplings |
| `treefall.bypass.cooldown` | op | skip the cooldown |

## Configuration

Every setting is commented in [`config.yml`](src/main/resources/config.yml).
The ones worth knowing about:

| Setting | Default | |
|---|---|---|
| `falling-blocks` | `true` | the toppling animation |
| `fall-spread` | `1.0` | how far the tree leans — try `2.0` |
| `max-falling-blocks` | `200` | entity budget; this is the lag control |
| `max-leaves` | `2000` | how much of the crown breaks |
| `max-distance` | `12` | leash on the crown search |
| `decorations` | 14 blocks | anything growing on a tree |
| `respect-claims` | `true` | check each block with protection plugins |
| `track-placed-logs` | `true` | anti-farm |

Found something still floating after a tree falls? Add the block to
`decorations` — no code change needed.

## Building

Needs JDK 25.

```
mvn clean package
```

The jar lands in `target/TreeFall.jar`.

## License

[MIT](LICENSE) — use it, change it, ship it. Just keep the copyright notice.
