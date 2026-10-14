# YouTube streaming

YouTube audio extraction uses InnerTubeX `v0.8.6` (upstream commit
`af31fd2ba65cb8cb56461c103517be96564cd91b`). Its published dependency is
`com.github.MetrolistGroup.innertubex:innertubex:v0.8.6` from JitPack.
Builds require Kotlin 2.4.20 and Android SDK 37.0. The app's target SDK,
minimum Android version and Media3 version remain unchanged.

`YouTubeAudioStreamService` adapts the library's `InnerTubeExtractor`,
`YtConfigParserImpl`, `YouTubeCipherService` and public client fallback strategy.
Player requests, supported client profiles and cipher execution belong to
InnerTubeX. `YouTubeAudioFormatPolicy` restricts the library's format inventory
to original audio, compatible codecs and the selected bitrate ceiling; it does
not implement a second extractor. JioSaavn, Internet Archive, YouTube Music
catalog queries and lyrics retain their existing integrations.

Direct audio and HLS use the existing Media3 playback path. SABR is explicitly
disabled because the app's Media3 sources do not implement that transport.
Anonymous playback remains anonymous; no Premium entitlement, cookies or
PO-token credentials are fabricated. Restricted or unavailable videos can
still fail when none of the library's compatible anonymous clients work.

Resolution and stream probes run off the UI thread with bounded concurrency,
a 35-second extraction deadline, expiring URL memoization and bounded client
fallback. Direct streams use the library's bounded range requirements beneath
the existing disk cache. Cache keys identify the video, format and content
length rather than the expiring URL. HTTP rejection refreshes a stream only
when the byte representation remains compatible; representation changes use
the existing player's bounded retry at the saved playback position. HLS
sessions receive distinct opaque URI markers so refreshing a session recreates
its playlist tracker. Bitrate and codec metadata describe the actual stream;
YouTube audio is never labeled lossless.

No tests were run for this migration. The cloud network blocked JitPack, so
build verification used an Android AAR compiled from the unchanged upstream
tag's common and Android/JVM source sets in a temporary local repository.
That repository is not part of the project or its dependency configuration.
Downloading the published JitPack artifact and live device playback were not
verified. The saved cloud environment draft includes JitPack access and SDK
37.0; apply it in Environment Settings for normal dependency resolution.
