# Hydra Defaults Navigation (PyCharm plugin)

[![Marketplace](https://img.shields.io/jetbrains/plugin/v/35797?label=Marketplace)](https://plugins.jetbrains.com/plugin/35797)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/35797)](https://plugins.jetbrains.com/plugin/35797)
[![Build](https://github.com/saifjarboui/pycharm-hydra-nav/actions/workflows/build.yml/badge.svg)](https://github.com/saifjarboui/pycharm-hydra-nav/actions/workflows/build.yml)

<!-- Plugin description -->
Navigate and edit [Hydra](https://hydra.cc) configs from PyCharm:

- **Defaults navigation**: Cmd/Ctrl+click a `defaults:` entry to open its YAML file; renaming the file or the group folder updates the entry.
- **`_target_` navigation**: jump to the Python module, class or method it names, installed packages and re-exports included.
- **Interpolations**: Cmd/Ctrl+click `${data.image_size}`, `${.x}`, `${oc.select:...}` or `${oc.env:KEY}` to open the key it reads, or the `.env` line that defines it.
- **Parameter keys**: click a key beside `_target_` to reach the `__init__` parameter; rename updates the key; F1 shows the Python docs.
- **Completion**: `defaults:` options, group keys (nested too), `override`/`optional`/`_self_` and `@package` names, `_target_` paths, `${...}` keys, unset parameters, `Literal`/`bool`/default values.
- **Hide Comments and Docs**: one action folds every comment and docstring in the editor.
- **No background work**: everything runs on click, hover or completion, so it costs nothing while you type.

![defaults navigation](docs/img/defaults.gif)

Cmd/Ctrl+click on a Hydra `defaults:` entry opens its file:

```yaml
defaults:
  - model: resnet                # → <config root>/model/resnet.yaml
  - override /trainer: gpu       # → <config root>/trainer/gpu.yaml
  - data@eval: cifar10           # → <config root>/data/cifar10.yaml
  - base                         # → base.yaml next to this file
```

The plugin looks for `<group>/<option>.yaml` in the current file's folder, then in each folder above it, and opens the first match. It needs no setting. Renaming the target file updates the entry.

![_target_ navigation](docs/img/target.gif)

Cmd/Ctrl+click on a `_target_` opens the Python code it names, one dotted segment at a time:

```yaml
_target_: src.models.resnet.ResNetClassifier   # click the class → the class, `resnet` → the module
_target_: src.models.resnet.ResNetClassifier.from_config   # a method or nested class works too
```

PyCharm's own import resolver does the lookup, so re-exports like `torch.optim.Adam` and installed packages work too. Renaming the class or module updates the entry.

![interpolation navigation](docs/img/interpolation.gif)

Cmd/Ctrl+click on an interpolation opens the key it reads:

```yaml
img_size: ${data.image_size}   # → image_size: in data/*.yaml (a chooser if several data options define it)
seed: ${seed}                  # → seed: in this file or a primary config such as train.yaml
lr: ${.base_lr}                # → base_lr: next to lr: in the same mapping
wd: ${..weight_decay}          # → weight_decay: next to the mapping that holds wd:
dim: ${oc.select:model.dim,64} # → model.dim, ignoring the default
host: ${oc.env:DB_HOST}        # → DB_HOST= in the nearest .env file
```

Which `data` option runs is only known at run time, so every candidate is offered. What links:

- `${a.b}`: the key, searched in this file's folder and the folders above it.
- `${.x}` and `${..x}`: a sibling, or the parent's sibling, in this file.
- `${oc.select:key,default}`: the `key`.
- `${oc.env:KEY}`: the `KEY=` line of the nearest `.env` file, going up from this file's folder. It's only a guess at where the value comes from, since Hydra doesn't load `.env` by itself.

The `.env` link is safe by construction. It runs only when you click, and it reads only a file named `.env` (not a symlink) in a folder of this project. It never reads your real environment, starts a process, opens a network connection or writes a file. It doesn't show, copy or store the value: it only moves the caret to the key.
- Other resolvers: not linked.

Cmd/Ctrl+click on a key beside `_target_` opens that parameter of the target's `__init__`, and renaming the parameter updates the key. F1 on a `_target_` segment shows the Python docs.

```yaml
_target_: src.models.resnet.ResNetClassifier
lr: 1e-4          # → the `lr` parameter of ResNetClassifier.__init__
```

Ctrl+Space completes `defaults:` options (the `*.yaml` files of the group), `_target_` paths (packages, modules, classes, methods) and `${...}` keys.

![argument completion and jump to __init__](docs/img/completion.gif)

These also pop up while you type:

- On a `defaults:` line, the group keys (`- mo` or `- override /mo` → `model: `, nested groups as `model/encoder: `), from the group folders next to the config.
- On a bare `- `, `override `, `optional ` and `_self_`; after `@`, `_global_` and `_group_`.
- On a new key in a `_target_` block, the parameters not set yet (of `__init__`, or of the method), plus `_partial_`, `_recursive_` and `_convert_`.
- On the value of such a key, the parameter's `Literal[...]` values, `true`/`false` for `bool`, `null` for `Optional`, and its default.

The `_target_` suggestions look up the Python code on each keystroke in that block. PyCharm caches the lookup.

Code > HydraNav: Hide Comments and Docs (also in the editor's right-click menu) toggles folding of every comment and Python docstring in the current editor. A green check shows next to it while they're hidden. Bind a shortcut to it in Keymap if you want one.
<!-- Plugin description end -->

## Check the `.env` safety claims yourself

```bash
git grep -nE "java\.net|HttpRequests|ProcessBuilder|GeneralCommandLine|Runtime\.getRuntime|System\.getenv|writeText|WriteAction|executeOnPooledThread" -- src/main
```

It prints nothing: the plugin's code has no network, process, environment, file-write or background-thread calls. The file read is `HydraEnvReference.resolve()` in `HydraDefaultsReferenceContributor.kt`. This is a check of this repository's code, not an outside audit.

## Build and install

```bash
export JAVA_HOME=~/Applications/PyCharm.app/Contents/jbr/Contents/Home   # PyCharm's own Java
./gradlew test buildPlugin
```

Then in PyCharm: Settings → Plugins → ⚙ → Install Plugin from Disk… → `build/distributions/pycharm-hydra-nav-*.zip`.

`./gradlew runIde` opens a test PyCharm with the plugin loaded.

The build downloads PyCharm `pycharmVersion` (`gradle.properties`). To build against a local install instead, set `pycharmPath=/path/to/PyCharm.app` in `local.properties` (gitignored).

`./gradlew verifyPlugin` runs the Plugin Verifier against the supported PyCharm releases.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
