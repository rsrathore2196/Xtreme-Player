# LastWave feature adaptations

The lyrics-only keep-screen-on behavior and the HTTP streaming add-on integration
are adapted from LastWave Native, by LastWave contributors, under GPL-3.0.
The license is reproduced in `LICENSE-GPL-3.0.txt`.

Source: https://github.com/Clash-Projects/LastWave-Native
Revision: `72ad3080b0007b105f13e399fc472f4db2ad749d`

Reference files:
- `app/src/main/java/com/lastwave/app/ui/player/PlayerHost.kt`
- `app/src/main/java/com/lastwave/app/data/addon/AddonClient.kt`
- `app/src/main/java/com/lastwave/app/data/addon/AddonModels.kt`
- `app/src/main/java/com/lastwave/app/ui/settings/ModulesScreen.kt`

Xtreme uses its existing reactive settings, dispatchers, playback session and
recording verification. The custom server implements `manifest.json`, `search`
and `stream/{id}`; progressive, HLS, and DASH transports are handled by Media3.
Screen-on ownership is additionally limited to a resumed, visible lyrics view.
No LastWave private backend credentials, native signing implementation, telemetry,
provider modules, or download implementation are included.
