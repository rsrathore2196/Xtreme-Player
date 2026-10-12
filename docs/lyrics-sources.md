# Lyrics selection

`LyricsProvider` coordinates `LyricsSources` and normalizes documents with
`LyricsDocuments`/`BetterLyricsParser`. The existing player UI and playback clock
remain in use. No provider timestamps are eased or shifted to create apparent sync.

Available sources, in tie-break order:

| Source | Route / format |
| --- | --- |
| lrc.red | ISRC TTML; validated title/artist/duration search otherwise |
| BiniLyrics | Recording search on lyrics-api.binimum.org, then TTML document |
| BetterLyrics | getLyrics; TTML, enhanced LRC or timed JSON |
| BetterLyrics Portato | qq/getLyrics; QRC/karaoke timestamps |
| PaxSenix | Public Apple lyrics by validated Apple catalog song ID |
| PaxSenix: Spotify | Authenticated Spotify search and lyrics |
| PaxSenix: Musixmatch | Authenticated Musixmatch lyrics |
| LyricsPlus | Syllable JSON, with duration queries in seconds |
| Unison | Community TTML, enhanced LRC, line LRC or plain text |
| Musixmatch | Official API when configured; public LyricsPlus Musixmatch routes otherwise |
| YouTube captions | Transcript for the exact playing YouTube video ID |
| LRCLIB | Validated line LRC and plain lyrics |
| YouTube Music | Lyrics tab for the exact playing YouTube video ID |
| KuGou | Validated lyric search and base64 LRC |
| Genius | Validated song page, lyrics containers only |

Potentially rich sources are queried with four concurrent lookups and per-source
12-second deadlines. An actual syllable-timed document ranks ahead of word timing,
then line timing, then plain text. Coverage breaks equal-quality ties. Only after
rich lookups finish without word/syllable timing are line/plain-only sources used.
Early rich results can display while remaining providers are searched; later
results can upgrade them. Documents from different recordings are never merged.

TTML boundaries, JSON millisecond/second units, LRC offsets, repeated line stamps,
and enhanced/QRC word stamps are preserved. Missing word endings are not invented.
Real fragments survive even when another word lacks reliable timing. Metadata,
recording versions, and duration are checked where the source exposes them.
Line-only/plain sources cannot guarantee syllable alignment to vocals.

HTTP requests cancel when the song changes; responses are size-bounded and closed.
BiniLyrics and lrc.red share matching document downloads. Cache version 3 replaces
older provider-first entries and expires after 30 minutes. Refresh bypasses all
cache aliases; valid cached lyrics remain available during an API failure.

## Optional authenticated sources

Provide `PAXSENIX_API_KEY` and/or `MUSIXMATCH_API_KEY` as Gradle properties or build
environment variables. No keys are committed. Without a PaxSenix key its Spotify
and Musixmatch routes are skipped; the public Apple route and other providers
remain available. Musixmatch uses the public aggregator when no official key is
configured. These values become application BuildConfig fields, so use only keys
intended for distribution in a client application.

Endpoint/schema references were inspected from the publicly available provider
adapters at https://github.com/kushagrasinghx/BitChord/tree/main/shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics .
Live provider calls could not be verified in the cloud workspace because its
network policy blocks those domains. No tests were run at the user's request.
