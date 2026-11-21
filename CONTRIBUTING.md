# Contributing to JustRead

Thanks for helping. JustRead is a small, focused EPUB reader, and contributions that keep it calm
and quick are very welcome: bug fixes, better EPUB support, accessibility, performance, and careful
UI polish.

## Before you start

- **Bugs:** open an issue with the steps to reproduce it, what you expected, and your device and
  Android version. If a particular book triggers it, say so. Please don't attach
  books you don't have the rights to share.
- **Features:** open an issue to talk it through first. JustRead deliberately leaves things out
  (accounts, stores, ads, feeds), so a quick chat saves wasted work.
- **Small fixes** like typos or obvious one-line bugs can go straight to a pull request.

## Setting up

You need JDK 17 or newer and the Android SDK, which Android Studio installs for you.

```sh
git clone https://github.com/JobinBiju/JustRead.git
cd JustRead
./gradlew :app:installDebug   # build and install on a device or emulator
```

Or open the project in Android Studio and run the `app` configuration.

## Where things live

The app is one module, `app/`. The code is in `app/src/main/kotlin/app/reader/`:

| File | What it does |
|---|---|
| `Epub.kt`, `Xml.kt`, `Css.kt` | Reading the EPUB package, its XHTML and its CSS |
| `Paginator.kt`, `PageArt.kt` | Laying text out into pages and drawing them |
| `Curl.kt` | The page curl: geometry, shader and fallback |
| `Reader.kt`, `Panels.kt` | The reader screen and its sheets |
| `Library.kt`, `App.kt` | The library and the app's state |
| `Settings.kt`, `Theme.kt`, `Widgets.kt` | Reader settings, themes, colours and shared controls |

`Platform.kt` holds storage, ZIP and sensor code, and `android/MainActivity.kt` is the activity.

## Making a change

1. Fork the repo and branch from `main`, e.g. `fix/footnote-popover` or `feat/highlights`.
2. Keep each pull request to one change. Small pull requests get reviewed faster.
3. Follow the existing style: Kotlin's official code style (`kotlin.code.style=official`), and
   comments that explain *why*, not *what*.
4. Try your change in the real app. For anything visual, check both light and dark themes, and
   Android 13+ (shader curl) as well as an older version (strip curl) if you touched `Curl.kt`.
5. Make sure it builds: `./gradlew :app:assembleDebug`.
   The **Build** workflow runs the same check on every pull request.

### Commit messages

Commits follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(reader): add a sepia night theme
fix(epub): read covers declared only in the NCX
docs: explain the strip fallback
```

Common types: `feat`, `fix`, `perf`, `refactor`, `docs`, `chore`. The scope is optional and names
the area: `epub`, `layout`, `curl`, `reader`, `library`, `ui`, `app`.

### Pull requests

Describe what changed and why, link the issue, and add screenshots or a short recording for UI
changes.

## Releases

Maintainers release by pushing a version tag. The **Release** workflow builds signed APKs for each
ABI and publishes them as a GitHub release:

```sh
git tag v1.2.3
git push origin v1.2.3
```

The tag sets the version: versionName `1.2.3`, versionCode `10203`.

## Licence

JustRead is licensed under the [GNU GPL v3.0](LICENSE). By contributing, you agree that your
contributions are licensed under the same terms.
