# Project rules

## Battery: the plugin does work only when the user asks for it

The plugin runs inside PyCharm all day, often on a laptop. It must cost nothing while the user is just typing.

- No background work: no timers, polling, `Alarm`, `executeOnPooledThread`, startup activities, file watchers or `VirtualFileListener`s.
- No project-wide scans or custom indexes. Look things up only from the element the user clicked, on demand.
- `getReferencesByElement` runs on every YAML scalar the editor touches. Keep it to cheap PSI checks: no file lookups and no Python resolution there.
- Do resolution only in `resolve()`, which runs on click, hover or rename. Use the platform's cached resolvers (`PyPsiFacade`, VFS lookups) and never walk the whole tree.
- References stay soft, so the unresolved-reference highlighting never calls `resolve()` on every line.
- No inspections, annotators or line markers that resolve on each keystroke, unless the user asks for one. Mention the battery cost when they do.
- Exception the user asked for: `HydraKwargCompletionContributor` runs in the typing popup, and resolves the `_target_` only for a key or value inside a `_target_` block.
