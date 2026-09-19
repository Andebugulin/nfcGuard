# Store listing metadata

Read by F-Droid and IzzyOnDroid, which scrape this directory from the tagged
commit. There is no Fastfile and no Play service account — the Play Console
listing is still edited by hand, so keep the two in sync manually.

Layout (`metadata/android/en-US/`):

| Path | Limit | Notes |
|---|---|---|
| `title.txt` | 30 | App name as shown on F-Droid. Play's title is set in the console. |
| `short_description.txt` | 80 | Heavily indexed by Play search. |
| `full_description.txt` | 4000 | Plain text with `*` bullets renders on both stores. |
| `changelogs/<versionCode>.txt` | 500 | One file per release; add before tagging. |
| `images/icon.png` | — | 512×512 |
| `images/featureGraphic.png` | — | 1024×500 |
| `images/phoneScreenshots/1.png` … | — | Portrait, min 2. Ordered by filename. |

Screenshots in `docs/assets/` are 1920×1440 landscape and are for the website
only — do not reuse them here.
