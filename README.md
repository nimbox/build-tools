# build-tools

The shared release machinery for Nimbox repositories: reusable GitHub
workflows, a release gate action, the `bump` ship command, and the Gradle
plugins every canexer repository applies. Everything is referenced from
`main`, so a change here reaches every repository on its next run.

## Overview

**Workflows** (`.github/workflows`), called with `uses:` from a repository's
own workflow:

* `java-build.yaml`, `node-build.yaml`: build and test on push.
* `java-release.yaml`, `node-release.yaml`, `canexer-application-release.yaml`:
  verify the tag, build, publish to GitHub Packages, create the release.
* `create-release.yaml`: create the GitHub release for the pushed tag.

**Actions** (`actions/`), used as a step:

* `verify-version`: the release gate. Fails the job when the pushed tag does
  not match the repository's `VERSION`. See [Release gate](#release-gate).

**bump** (`scripts/bump.sh`): the ship command. Bumps `VERSION`, stamps it
into every declared file, commits, tags, and pushes. See [bump](#bump).

**Gradle plugins** (`gradle-plugins/`):

* `com.nimbox.tools.versioning`: derives the project version from the git tag.
* `com.nimbox.canexer.artifact`: stamps the `Nimbox-*` manifest and installs
  the artifact on a box. See [Gradle plugins](#gradle-plugins).

## bump

The repo-root `VERSION` file is the single source of truth. `version.json`
declares where that value is stamped (the targets) and which branch releases
ship from. The main command runs the whole guarded release: it asserts you
are on the release branch, clean, and in sync with origin, then bumps
`VERSION`, stamps every target, commits `Release X.Y.Z`, tags `vX.Y.Z`, and
pushes branch and tag atomically. CI then verifies the tag before publishing.

```
bump major | minor | patch | X.Y.Z   guarded release (commit, tag, atomic push)
bump current                         print the version (from VERSION)
bump apply                           stamp VERSION into every target
bump verify [<tag>]                  assert all targets == VERSION (and == tag)
```

`scripts/bump.sh` in this repository is the only copy of the logic. It needs
`git`, `jq`, and `perl`, plus `npm` for npm targets.

### Adopting it in a repository

A repository carries three files: `VERSION`, `version.json`, and the
launcher `scripts/bump` (executable). The launcher fetches `bump.sh` from
`main` on every run and is the whole of `templates/bump`; copy it as is:

```bash
#!/usr/bin/env bash
set -euo pipefail
script="$(mktemp)"
trap 'rm -f "$script"' EXIT
curl -fsSL https://raw.githubusercontent.com/nimbox/build-tools/main/scripts/bump.sh -o "$script"
bash "$script" "$@"
```

A failed download aborts with the curl error rather than running an empty or
truncated script.

### version.json

`tagPrefix` (default `v`), `branch` (default `main`), and one entry per
target. Each target has a `type`, a locator, and an optional `template` in
which `{version}` is replaced:

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

## Release gate

`actions/verify-version` asserts that the pushed tag agrees with the
repository's `VERSION` and every `version.json` target, so a tag cut by hand
on a stale commit cannot publish. It runs `scripts/bump.sh verify` from this
repository's checkout, so the ref of the action pins the ref of the script.

The release workflows run it before they build, so a repository calling them
is gated with nothing to add. A repository with its own release pipeline adds
one step after checkout, in the job that publishes:

```yaml
steps:
  - uses: actions/checkout@v7
  - id: verify
    uses: nimbox/build-tools/actions/verify-version@main
```

Inputs:

* `tag`: the tag to verify. Default: the ref that triggered the workflow.
* `required`: fail when the repository has no `VERSION`. Default `false`,
  which skips with a notice.

Output: `version`, the released version without the tag prefix, for image
tags and release titles (`${{ steps.verify.outputs.version }}`).

## Gradle plugins

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
