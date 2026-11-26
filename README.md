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
- **Topics and tags**: the front page filters by topic and by the tags the
  sources put on their own articles. A topic is a name and a keyword list you
  can edit; each article shows which keyword put it in a topic. Rules can
  boost, bury or hide by topic or tag.
- **Trust and blocking**: a level per source, set only by you.
- **Algorithm**: every weight, threshold and rule is editable, and the whole
  configuration exports to and imports from one JSON file.
- **Interests**: follow a topic to give its articles extra weight, get a
  notification when a background check finds new ones, and optionally have a
  web search bring in articles from sites you have not added. Search runs on
  your own key through a configurable connector (Kagi, Brave and Tavily
  presets). Sites a search turns up are listed as unrated sources to trust,
  block or follow.
- **JavaScript pages**: a listing page that is empty until its scripts run can
  be loaded in a hidden browser before it is read.
- **Browser**: articles open in the app, in the phone's own web engine, with
  ad and tracker requests blocked using standard filter lists (EasyList,
  EasyPrivacy, Peter Lowe's) plus your own rules. Each page lists what was
  blocked and by which rule, blocking can be switched off per site, and a
  reader view shows the article alone.
- **Models**: optional. With your own API key for any OpenAI-compatible
  provider, a model writes the recipe when layout detection fails and repairs
  scraped sources that break. Each answer is run against the real page and
  only applied if it extracts a plausible article list. The model, request
  parameters and prompts are all editable, every request is logged in full,
  and replaced recipes are kept to restore. The key is stored encrypted on the
  phone and is never exported or backed up.

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
