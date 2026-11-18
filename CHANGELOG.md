**0.1.5** Clicking a key in a config that gets its `_target_` through `defaults:` (e.g. `data/small.yaml` extending `base`) now jumps to the parameter of the target's `__init__`.
**0.1.4** Renaming a config group folder updates the `defaults:` entries that use it. Completes nested groups (`model/encoder`), `override` / `optional` / `_self_`, and `_global_` / `_group_` after `@`.
**0.1.3-beta.1** Beta: supports PyCharm 2025.3 and newer, plugin icon.
**0.1.3** Supports PyCharm 2025.3 and newer, plugin icon. Links relative `${.x}` / `${..x}` and `${oc.select:key,default}` interpolations, and `${oc.env:KEY}` to the `.env` file that defines it (on click only, project `.env` files only, no network, process or environment access).
**0.1.2** Release notes on Marketplace, written from CHANGELOG.md.
**0.1.1** Demo GIFs in the description and README.
**0.1.0** Navigation and completion for Hydra `defaults:`, `_target_` and `${...}`.
