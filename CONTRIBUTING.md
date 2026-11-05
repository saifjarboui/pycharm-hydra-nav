# Contributing

## Setup

```bash
export JAVA_HOME=~/Applications/PyCharm.app/Contents/jbr/Contents/Home   # PyCharm's own JDK
./gradlew test buildPlugin verifyPlugin
```

- The build downloads PyCharm `pycharmVersion` (`gradle.properties`). To use a local install instead, put `pycharmPath=/path/to/PyCharm.app` in `local.properties` (gitignored).
- `./gradlew runIde` opens a sandbox PyCharm with the plugin. Install from disk: `build/distributions/*.zip`.
- Format and conventions: Kotlin, no formatter config; follow the surrounding code.

## Rules for the plugin code

The plugin runs in PyCharm all day, often on a laptop, so it must cost nothing while the user types. The full list is in [CLAUDE.md](CLAUDE.md); the short version:

- No background work: timers, polling, startup activities, file watchers, custom indexes, project-wide scans.
- `getReferencesByElement` runs on every YAML scalar the editor touches: cheap PSI checks only, no file lookups, no Python resolution.
- Resolve only in `resolve()` (click, hover, rename), using the platform's cached resolvers. Keep references soft.
- No inspections, annotators or line markers that resolve on each keystroke unless asked for, and then say what they cost.
- The one exception is `HydraKwargCompletionContributor`, which runs in the typing popup and resolves a `_target_` only for a key or value inside that block.

## Compatibility

- Supported: PyCharm 2025.3 and newer (`sinceBuild = 253`, no `untilBuild`). Depend on the `PythonCore` plugin, not on `com.intellij.modules.python`.
- Build against the newest PyCharm, but do not use API that is missing in the oldest supported one. Kotlin companion calls (such as `TypeEvalContext.codeInsightFallback`) can break older builds: check with the verifier.
- `./gradlew verifyPlugin` runs the Plugin Verifier on the PyCharm builds JetBrains recommends for our range (`recommended()`), so new releases are picked up automatically. CI runs it only on release tags, to save GitHub credits, so run it locally before tagging. It must stay green. When you raise or lower the range, update `sinceBuild` in `build.gradle.kts` and this file.
- Newer PyCharm wins over older. If supporting a new version needs a code change that breaks the oldest supported one, make the change and drop the old version: raise `sinceBuild` in `build.gradle.kts` and this file. Do not add version-specific branches or compatibility shims.
- Keep the plugin dynamic: no `restart`-requiring extensions, no static state that outlives an unload.

## Tests

- Fixture tests live in `src/test/kotlin/hydranav`. Add one for every new behavior; completion, resolve and rename each have examples.
- A change that touches Python resolution needs a test with an inherited or re-exported class.

## README and Marketplace page

- The Marketplace description is the README text between `<!-- Plugin description -->` and `<!-- Plugin description end -->`. Edit the README, never `plugin.xml`.
- Images go in `docs/img/` and are referenced as `docs/img/x.gif`; the build rewrites them to absolute GitHub URLs. Keep each GIF under about 2 MB.
- "What's New" on Marketplace comes from `CHANGELOG.md`: one line per release, newest first, only `**bold**` and `` `code` ``.
- Source, issue tracker and license links are set on the Marketplace page (Edit), pointing to this repository.

## Releasing

1. Bump `pluginVersion` in `gradle.properties` and add a `**X.Y.Z**` line to the top of `CHANGELOG.md`.
2. Merge to `main`; CI must pass (tests, build). The Plugin Verifier runs on the tag, before publishing.
3. Tag and push: `git tag vX.Y.Z && git push origin vX.Y.Z`.
4. The tag build checks that the tag, `pluginVersion` and `CHANGELOG.md` agree, publishes to the Marketplace with `PUBLISH_TOKEN`, then creates a GitHub Release with the zip.
5. A version with a suffix (`0.2.0-beta.1`, tag `v0.2.0-beta.1`) is a beta: it goes to the Marketplace `beta` channel and the GitHub Release is marked pre-release. Users must add the beta channel URL to see it; stable users do not. Promote by releasing the plain version.
6. JetBrains reviews every new version before it goes live (hours to a day).

Changes with no user-visible effect (docs, CI, refactors) do not need a release; leave them on `main`.
