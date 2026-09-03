# development

## workflows

In the `.github/workflows` directory, you can find the following workflows:

* **`java-release.yaml`**: Used to release a new version of a java
  library to the github java repository.


## bump

`bump` is the ship command every Nimbox repository uses. The repo-root
`VERSION` file is the single source of truth; `version.json` declares where
that value is stamped (the targets) and which branch releases ship from. The
main command runs the whole guarded release: it asserts you are on the release
branch, clean, and in sync with origin, then bumps `VERSION`, stamps every
target, commits `Release X.Y.Z`, tags `vX.Y.Z`, and pushes branch and tag
atomically. The release workflows above run `verify` against the pushed tag
before publishing.

```
bump major | minor | patch | X.Y.Z   guarded release (commit, tag, atomic push)
bump current                         print the version (from VERSION)
bump apply                           stamp VERSION into every target
bump verify [<tag>]                  assert all targets == VERSION (and == tag)
```

**Where the logic lives.** `scripts/bump.sh` in this repository is the only
copy. Consumers fetch it from `main` on every run, so a change here reaches
every repository the next time `bump` is invoked. The release workflows fetch
the same file for `verify`.

**Adopting it in a repository.** A consumer carries three files and nothing
else: `VERSION`, `version.json`, and the launcher `scripts/bump` (executable):

```bash
#!/usr/bin/env bash
set -euo pipefail
script="$(mktemp)"
trap 'rm -f "$script"' EXIT
curl -fsSL https://raw.githubusercontent.com/nimbox/build-tools/main/scripts/bump.sh -o "$script"
bash "$script" "$@"
```

The launcher is the whole of `templates/bump`; copy it as is. It needs `curl`,
and `bump.sh` itself needs `git`, `jq`, and `perl` (plus `npm` for npm
targets). A failed download aborts with the curl error rather than running an
empty or truncated script.

**`version.json`.** `tagPrefix` (default `v`), `branch` (default `main`), and
one entry per target. Each target has a `type`, a locator, and an optional
`template` in which `{version}` is replaced:

```json
{
  "tagPrefix": "v",
  "branch": "main",
  "targets": [
    { "type": "json",       "file": "app/manifest.json", "path": ".version" },
    { "type": "properties", "file": "gradle.properties", "key": "version" },
    { "type": "regex",      "file": "src/version.py",   "pattern": "^__version__ = \"([^\"]+)\"" },
    { "type": "npm",        "directory": ".",           "workspaces": true }
  ]
}
```

* **`json`**: `file` plus a jq `path` set to the value.
* **`properties`**: `file` plus a `key`; the `key=value` line is replaced or
  appended.
* **`regex`**: `file` plus a `pattern` with exactly one capture group around
  the version; the captured text is replaced.
* **`npm`**: `directory` holding `package.json` (default `.`); stamping is
  delegated to `npm version`, which also writes `package-lock.json`.
  `"workspaces": true` includes the workspace packages.

## gradle-plugins

Plugins every canexer repository applies, published to GitHub Packages and
versioned with this repository's tag.

* **`com.nimbox.tools.versioning`**: derives the project version from the
  git tag.
* **`com.nimbox.canexer.artifact`**: stamps the `Nimbox-*` manifest on an
  application war or a connector jar and registers `installToServer`, which
  uploads the archive to a box's control plane
  (`POST /server/manager/install`) and waits for the install job to settle.

  ```
  ./gradlew installToServer                 # the development box
  ./gradlew installToServer -Pbox=demotwo   # another box
  ```

**Which box.** The first of these that is set:

1. `-Pbox=<name>` on the command line.
2. `CANEXER_BOX` in the environment, for a build run outside the composite.
3. `canexer.box`, a system property the `canexer-workspace` composite sets from
   the name on this machine's data volume, which adoption wrote: the development
   box.
4. No box, but `CANEXER_URL` in the environment: the install goes to that
   server.
5. Nothing at all: the install goes to `http://localhost:8088`.

**Where its server is.** A named box (1 to 3) is reached at the `url`
in its descriptor `~/.nimbox/boxes/<name>/box.json`, and a descriptor
without one is refused, never defaulted. `NIMBOX_BOXES` moves the
descriptors directory. Only 4 and 5 install without a box name, and so
cannot obtain a tower token; they are for an open manager plane.

**How the upload gets in.** The first of these that applies:

1. `CANEXER_SERVER_SECRET` in the environment is presented as the
   bearer, for CI.
2. The manager plane answers an unauthenticated probe: a DEVELOPMENT
   or INTEGRATION server. The upload goes as is.
3. The plane refuses: a box that takes tower tokens only. The plugin
   asks the tower for a five-minute token scoped to the box, with the
   session `tower login` stores in `~/.nimbox/tower.json`
   (`NIMBOX_TOWER_URL` points at another tower), and presents it on
   the upload and the job polls.
