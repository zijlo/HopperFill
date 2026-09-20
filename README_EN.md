# HopperFill

[简体中文](README.md) | **English**

HopperFill is a Minecraft Fabric mod that adds region scanning, automatic hopper-line filling, a visual hoe-based selection tool, and `/hf give` to pack items into shulker boxes in one command. Both Creative and Survival are supported (a few commands are Creative-only).

## Features

- **Region scanning** — Select a region and it tallies every block and item inside (items inside item frames are detected and take priority). Results are printed to chat, aligned using real font widths.
- **Multi-item slice detection** — A hopper can only hold one kind of item. If a single slice contains several item types, scanning fails immediately, lists the conflicting positions, and tells you to add those items to "skip blocks" and retry (the hoe state is reset automatically, so you can just re-select).
- **Skip blocks (blacklist)** — Add blocks that should be ignored while scanning (dirt, stone, and other filler) in the `/hf set gui` screen.
- **Automatic hopper-line filling** — Uses 16-stack / 64-stack templates to write the scan results into a continuous line of hoppers. In Survival it consumes the matching materials from your inventory.
- **Visual hoe workflow** — Right-click with the hoe to select step by step; 4 clicks cover the whole scan → fill flow (Survival consumes materials automatically).
- **Post-scan shortcuts** — When the 2nd right-click (scan) finishes, chat shows a clickable row:
  - `[清空漏斗]` (Clear Hoppers) — only shown when **every block in the region is a container** (chest / barrel / hopper / shulker box / furnace…). Clears their contents; Survival gets the items back, Creative is emptied outright.
  - `[满盒物品]` (Full Boxes) — only shown in **Creative**; fills one clean shulker box per item type found in the region.
  - Clicking either one also **resets the hoe state**, so the next right-click starts again from "region corner 1" instead of resuming a stale selection.
- **Clear `/hf clear`** — With coordinates, clears the hopper line; without coordinates, clears **every container inside the hoe selection** (Survival is refunded first).
- **Item giving `/hf give`** (Creative only):
  - Four modes: `all` / `stackable` / `nonstackable` / `box`
  - Amount options: `all` (full stacks), `all-1` (full stacks minus one), or an explicit number
  - Shulker boxes cycle through all 16 dye colours
  - Spawn eggs, items unobtainable in Survival, and shulker boxes themselves are excluded
- **Full boxes `/hf givebox`** (Creative only) — Gives one clean, full shulker box per item type found in the region.
- **Unified settings screen `/hf set gui`**:
  - Three tabs: Blacklist (items), Skip Blocks (blocks), Full-box Items
  - REI-style item icon grid, sorted by Creative inventory groups
  - Search by Chinese name or English ID
  - "Addable / Added" dual view; added entries are dimmed with a checkmark and can be removed with a click
  - **Survival-obtainable entries only** — Blacklist / Full-box Items no longer list command blocks, barriers, spawn eggs, bedrock, and other Survival-unobtainable items; Skip Blocks filters out Creative-only blocks such as command blocks, structure blocks, barriers, and light blocks

## Commands

| Command | Description |
|---|---|
| `/hf` | Show usage help |
| `/hf set template` | Open template settings (vanilla hopper GUI, 16/64 toggle) |
| `/hf set gui` | Open the settings screen (blacklist / skip blocks / full-box items) |
| `/hf scan <from> <to>` | Scan a region and tally its contents |
| `/hf fill <from> <to> <lineStart> <lineEnd>` | Fill the hopper line (Survival consumes materials) |
| `/hf clear <lineStart> <lineEnd>` | Clear the hopper line (Survival is refunded, Creative is emptied) |
| `/hf clear` | Clear **every container inside the hoe selection** (Survival refunded) — the no-argument form |
| `/hf givebox <from> <to>` | Creative: one clean full shulker box per item type in the region |
| `/hf hoe on/off` | Enable / disable the hoe tool |
| `/hf give all [amount]` | Creative: give obtainable items (packed into shulker boxes) |
| `/hf give stackable [amount]` | Creative: stackable items only |
| `/hf give nonstackable [amount]` | Creative: non-stackable items only |
| `/hf give box` | Creative: give clean full shulker boxes for the "full-box items" list |

`amount` accepts `all` (64 for 64-stack items, 16 for 16-stack items), `all-1` (63 / 15), or an explicit number (used as-is for 64-stack items, scaled proportionally for 16-stack items).

## Hoe Workflow (4 clicks)

| Click | Action |
|---|---|
| 1st | Set region corner 1 |
| 2nd | Set region corner 2 → automatic scan; clickable shortcuts appear when it finishes |
| 3rd | Set hopper-line start |
| 4th | Set hopper-line end → fill (Survival switches to incremental per-tick filling; right-click the hoe again to stop early) |

Any error along the way (multi-item slice / non-stackable items / broken hopper line / not enough materials) aborts with a message and resets the hoe — just right-click again to restart.

## Supported Versions & Downloads

| Minecraft series | File | Java |
|---|---|---|
| 1.21.11 – 1.21.x | `hopperfill-4.0.1-fabric-mc1.21.x.jar` | 21 |
| 26.1 – 26.3 | `hopperfill-4.0.1-fabric-mc26.1.x.jar` | 25 |

Current version: **4.0.1**. Download from [Releases](https://github.com/zijlo/HopperFill/releases) or [Modrinth](https://modrinth.com/user/zijlo).

> The `mc1.21.x` build targets **1.21.11** and declares `>=1.21.11 <1.22`, because the client screens use APIs introduced in 1.21.9. If you need 1.21.0 – 1.21.10, build per version with `build-121x.sh` (one jar per patch version).

> The `mc26.1.x` build is **a single jar covering 26.1 through 26.3**. 26.3 moved the render pipeline from `com.mojang.blaze3d` to `com.mojang.renderpearl` and replaced `Player.drop(ItemStack, boolean)` with a signature taking a `Prediction`. Both spots are now resolved **reflectively by name**, so the same jar works on 26.1 / 26.1.1 / 26.1.2 / 26.2 / 26.3 (verified at the symbol level). Should a future 26.x break the API again, use `build-26x.sh` to cut a per-version jar.

## Requirements

- Minecraft 1.21.x or 26.x
- Fabric Loader 0.19.3+
- Fabric API (matching your Minecraft version)
- Java 21 (1.21.x) / Java 25 (26.x)

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) and the matching [Fabric API](https://modrinth.com/mod/fabric-api).
2. Drop the `hopperfill-<version>-fabric-mc<series>.jar` matching your game version into `.minecraft/mods/`.
3. Launch the game and run `/hf` to see the usage.

## Usage

1. `/hf set template` — configure the 16-stack and 64-stack templates.
2. `/hf set gui` — add the blocks to skip (and full-box items) in the GUI.
3. `/hf scan <from> <to>` — scan the region and check the tally.
4. `/hf fill <from> <to> <lineStart> <lineEnd>` — fill along the hopper line (bring materials in Survival).
5. Or hold the hoe and follow the 4-click prompts to do scan and fill.

## Source Layout

Both modules (`hopperfill-1.21-survival` uses Yarn mappings, `hopperfill-26-survival` uses official Mojang mappings) share the same structure and contain only 12 files each:

| File | Responsibility |
|---|---|
| `HopperFillMod` | Mod entry point; registers networking / commands / events / tick |
| `client/HopperFillClient` | Client entry point; chat formatting of scan results |
| `client/gui/SettingsScreen` | Settings screen (three tabs) |
| `client/gui/TemplateScreen` | Template editor screen |
| `command/HopperFillCommand` | All `/hf` commands + `give` amount strategy |
| `data/TemplateStorage` | Per-player persistence (templates / blacklist / skip blocks / full-box items) |
| `fill/FillService` | Hopper-line geometry + fill validation + one-shot fill + incremental Survival session |
| `region/RegionScanner` | Region slice scanning, multi-item detection, container detection |
| `tool/HoeToolHandler` | 4-click hoe state machine + clickable shortcuts |
| `gui/TemplateScreenHandler` | Container logic for the template screen |
| `network/SettingsNetwork` | All payloads and send/receive logic for the settings screen |
| `util/ItemFilter` | Survival-obtainable / exclusion lists |

## Building

```bash
# 1.21.x (Yarn mappings, JDK 21)
cd hopperfill-1.21-survival && ./gradlew build

# 26.x (official Mojang mappings, JDK 25)
cd hopperfill-26-survival && ./gradlew build
```

The artifact name is `hopperfill-<mod_version>-fabric-<artifact_suffix>.jar`, derived from
`archives_base_name`, `mod_version`, and `artifact_suffix` in `gradle.properties`.
The multi-version build scripts rewrite `artifact_suffix` per patch version.

## License

[MIT](LICENSE)
