# MusicDatabase schema history

The production database is `xtreme_music_db`, currently version 8. It has no
Room destructive migration fallback. An unknown upgrade or downgrade must fail
without deleting or recreating the user's database; do not recover by resetting
it. Room runs migrations and final schema validation transactionally.

| Version | Entity schema change |
| --- | --- |
| 1 | Tracks, playlists, ordered playlist-track relationships, track relationship index |
| 2 | Same entities; 1 → 2 ensures the relationship index exists |
| 3 | Track singers, writer, language and year |
| 4 | Track provider/source |
| 5 | Track ISRC |
| 6 | Listening events and their four indices |
| 7 | Artwork cache |
| 8 | Same entity schema as 7; ensures both history/cache tables and required indices exist |

All versions 1–7 migrate incrementally to 8. The sole supported downgrade is
8 → 7, because those entity schemas are identical. Other downgrades are refused.

Schema export was disabled in the original historical releases. The retained
1–7 JSON files are fixtures reconstructed from the corresponding entity source
and migration changes, not original compiler exports. Their Room identity hashes
are calculated from those entity definitions; version 1 includes the relationship
index already declared in its original Entity. Fresh databases and older migrated
databases can differ in SQL column defaults added by ALTER TABLE, so both variants
are covered by the regression cases. Version 8 has schema export enabled through
KSP; retain its compiler-generated JSON alongside this history on future changes.

`MusicDatabaseMigrationTest` opens representative version 1–8 files through Room,
checks all preexisting values and relationship order, checks monotonic generated
IDs, repairs missing legacy indices, verifies transactional validation failures,
and confirms unsupported future downgrades keep their data and version intact.
Never change the old fixtures simply to make a failing migration test pass.
