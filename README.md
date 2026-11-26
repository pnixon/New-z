# New-z

A personal news front page for Android. It follows specific sites, by feed
where one exists and by scraping the listing page where it does not, and
orders the articles with a formula you can read and change.

## Install

Every push builds an APK and publishes it to the `latest` release:

https://github.com/pnixon/New-z/releases/download/latest/newz.apk

Open that address on the phone and install the file. Later builds install
over earlier ones.

## What is here so far

- **Sources**: RSS and Atom feeds, and scraped pages described by CSS
  selectors. Adding a site by address looks for a feed first, then detects the
  article list from the page layout.
- **Front page**: ranked by a visible formula (recency, your trust in the
  source, how many of your sources carry the story, whether you have read it,
  and your own rules). Every article shows its score line by line.
- **Filtered**: every article held back, with the reason.
- **Trust and blocking**: a level per source, set only by you.
- **Algorithm**: every weight, threshold and rule is editable, and the whole
  configuration exports to and imports from one JSON file.

## Layout

- `core/` is plain Kotlin: feed parsing, recipes, layout detection, ranking.
  It has no Android dependency and carries the tests.
- `app/` is the Android app: storage, background refresh and the screens.

## Working on it

```
./gradlew :core:test            # logic tests
./gradlew :app:assembleDebug    # needs the Android SDK
```

On a machine without the Android SDK, set `NEWZ_CORE_ONLY=1` to leave the app
module out of the build.

`app/debug.keystore` is a shared debug signing key, committed so that every
build carries the same signature. It protects nothing; do not reuse it for a
store release.
