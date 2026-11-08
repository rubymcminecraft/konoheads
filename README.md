# Kono Heads

[![Heads from Minecraft-Heads.com](https://img.shields.io/badge/Heads%20provided%20by-Minecraft--Heads.com-2e7d32?style=for-the-badge)](https://minecraft-heads.com)

A small client-side Fabric mod for Minecraft **26.3** that lets you search the
[Minecraft-Heads.com](https://minecraft-heads.com) custom head database in game and spawn heads.

## Features

- Press **H** (rebindable) or run `/heads` to open the head search screen
- Search by name, filter by category, scroll through results
- **Left-click** a head to get it (creative: straight into your inventory; otherwise runs `/give`, which needs permission)
- **Right-click** a head to copy its `/give` command

## Setup

Requires Fabric Loader 0.20+ and Fabric API for 26.3.

1. Register an app on Minecraft-Heads.com to get an **App UUID**, then in game run `/heads appuuid <uuid>`.
2. Optional: get an API key from your [API settings](https://minecraft-heads.com/settings/api) and run `/heads apikey <key>`.
   Without a key you get the "Best Heads" set; a Silver or Gold license unlocks all heads.
3. Run `/heads refresh` (or open the screen) to download the head list.

Settings live in `config/konoheads.json`. **Do not share that file — it contains your API key.**

## API usage

This mod follows the [Minecraft-Heads.com terms of use](https://minecraft-heads.com/terms-of-use) for API v2:

- Every request includes the registered App UUID.
- The head list is downloaded once at startup/reload and cached locally (`config/konoheads-cache.json`);
  it is re-downloaded only when the cache is older than 72 hours or on a manual refresh (limited to once per minute).
- Page requests are spaced out, rate-limit responses are respected, and API data is validated before use.
- No API data is bundled with the mod or redistributed.

## Building

Requires JDK 25.

```
./gradlew build
```

The jar is written to `build/libs/`.

## Credits

Head data and textures: [Minecraft-Heads.com](https://minecraft-heads.com).
