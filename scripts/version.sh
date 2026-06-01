#!/usr/bin/env bash
#
# version.sh — language-agnostic version dissemination.
#
# Single source of truth: the repo-root `VERSION` file. A `version.json`
# manifest declares where that value must be written (the "targets"). This
# script reads both and can stamp (apply), check (verify), bump, and release.
#
# Deps: git, jq, perl (all stable, ubiquitous). No language runtime.
#
# Canonical copy — lives in nimbox/build-tools. Consumer repos fetch this via a
# thin `scripts/version` bootstrap (pinned) and carry only their own VERSION and
# version.json. The contract (VERSION + version.json + subcommands) is the
# standard across all Nimbox repos.

set -euo pipefail

# --- locate repo root (where VERSION + version.json live) --------------------

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

VERSION_FILE="VERSION"
MANIFEST="version.json"

SEMVER_RE='^[0-9]+\.[0-9]+\.[0-9]+$'

die() { echo "error: $*" >&2; exit 1; }

require() {
    command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"
}

require git
require jq
require perl

[ -f "$VERSION_FILE" ] || die "no $VERSION_FILE at repo root ($ROOT)"
[ -f "$MANIFEST" ]     || die "no $MANIFEST at repo root ($ROOT)"

# --- truth + manifest accessors ----------------------------------------------

read_version() {
    tr -d '[:space:]' < "$VERSION_FILE"
}

tag_prefix() {
    jq -r '.tagPrefix // "v"' "$MANIFEST"
}

target_count() {
    jq '.targets | length' "$MANIFEST"
}

# Render a target's value from the version and its optional template.
render() {
    # $1 = version, $2 = template ("{version}" default)
    local version="$1" template="$2"
    printf '%s' "${template//\{version\}/$version}"
}

# --- per-type write (apply) --------------------------------------------------

apply_json() {
    # $1 = file, $2 = jq path expr, $3 = value
    local file="$1" path="$2" value="$3" tmp
    [ -f "$file" ] || die "target file not found: $file"
    tmp="$(mktemp)"
    jq --indent 2 --arg v "$value" "$path = \$v" "$file" > "$tmp"
    mv "$tmp" "$file"
}

apply_properties() {
    # $1 = file, $2 = key, $3 = value
    local file="$1" key="$2" value="$3"
    [ -f "$file" ] || die "target file not found: $file"
    if grep -q "^${key}=" "$file"; then
        KEY="$key" VAL="$value" perl -i -pe 's/^\Q$ENV{KEY}\E=.*$/$ENV{KEY}=$ENV{VAL}/' "$file"
    else
        printf '%s=%s\n' "$key" "$value" >> "$file"
    fi
}

apply_regex() {
    # $1 = file, $2 = pattern (one capture group around the version), $3 = value
    local file="$1" pattern="$2" value="$3"
    [ -f "$file" ] || die "target file not found: $file"
    PAT="$pattern" VAL="$value" perl -i -pe 'if (/$ENV{PAT}/) { my $c = $1; s/\Q$c\E/$ENV{VAL}/ }' "$file"
}

# --- per-type read (verify) --------------------------------------------------

read_json() {
    jq -r "$2" "$1"
}

read_properties() {
    # $1 = file, $2 = key
    grep "^${2}=" "$1" | head -1 | sed "s/^${2}=//"
}

read_regex() {
    # $1 = file, $2 = pattern
    PAT="$2" perl -ne 'if (/$ENV{PAT}/) { print $1; exit }' "$1"
}

# --- iterate targets ---------------------------------------------------------

# Calls a function name ($1) for each target with:
#   <type> <file> <locator> <value>
# where <locator> is `path` (json), `key` (properties), or `pattern` (regex).
for_each_target() {
    local fn="$1" version count i t type file template value locator
    version="$(read_version)"
    count="$(target_count)"
    for (( i = 0; i < count; i++ )); do
        t="$(jq -c ".targets[$i]" "$MANIFEST")"
        type="$(jq -r '.type' <<<"$t")"
        file="$(jq -r '.file' <<<"$t")"
        template="$(jq -r '.template // "{version}"' <<<"$t")"
        value="$(render "$version" "$template")"
        case "$type" in
            json)       locator="$(jq -r '.path' <<<"$t")" ;;
            properties) locator="$(jq -r '.key' <<<"$t")" ;;
            regex)      locator="$(jq -r '.pattern' <<<"$t")" ;;
            *)          die "unknown target type \"$type\" in $MANIFEST" ;;
        esac
        "$fn" "$type" "$file" "$locator" "$value"
    done
}

_apply_one() {
    case "$1" in
        json)       apply_json "$2" "$3" "$4" ;;
        properties) apply_properties "$2" "$3" "$4" ;;
        regex)      apply_regex "$2" "$3" "$4" ;;
    esac
}

VERIFY_FAILURES=0
_verify_one() {
    local type="$1" file="$2" locator="$3" expected="$4" actual
    [ -f "$file" ] || { echo "DRIFT  $file: file not found" >&2; VERIFY_FAILURES=$((VERIFY_FAILURES + 1)); return; }
    case "$type" in
        json)       actual="$(read_json "$file" "$locator")" ;;
        properties) actual="$(read_properties "$file" "$locator")" ;;
        regex)      actual="$(read_regex "$file" "$locator")" ;;
    esac
    if [ "$actual" != "$expected" ]; then
        echo "DRIFT  $file ($locator): expected \"$expected\", found \"$actual\"" >&2
        VERIFY_FAILURES=$((VERIFY_FAILURES + 1))
    fi
}

# --- subcommands -------------------------------------------------------------

cmd_current() {
    read_version
    echo
}

cmd_apply() {
    for_each_target _apply_one
    echo "applied $(read_version) to $(target_count) target(s)"
}

cmd_set() {
    local v="${1:-}"
    [[ "$v" =~ $SEMVER_RE ]] || die "not a semver (X.Y.Z): \"$v\""
    printf '%s\n' "$v" > "$VERSION_FILE"
    cmd_apply
}

cmd_bump() {
    local part="${1:-}" version major minor patch
    version="$(read_version)"
    [[ "$version" =~ $SEMVER_RE ]] || die "$VERSION_FILE is not a semver: \"$version\""
    IFS='.' read -r major minor patch <<<"$version"
    case "$part" in
        major) major=$((major + 1)); minor=0; patch=0 ;;
        minor) minor=$((minor + 1)); patch=0 ;;
        patch) patch=$((patch + 1)) ;;
        *)     die "usage: version bump <major|minor|patch>" ;;
    esac
    cmd_set "${major}.${minor}.${patch}"
}

cmd_verify() {
    local tag="${1:-}" version
    version="$(read_version)"
    [[ "$version" =~ $SEMVER_RE ]] || die "$VERSION_FILE is not a semver: \"$version\""
    VERIFY_FAILURES=0
    for_each_target _verify_one
    if [ -n "$tag" ]; then
        local expected_tag="$(tag_prefix)${version}"
        if [ "$tag" != "$expected_tag" ]; then
            echo "DRIFT  tag \"$tag\" != \"$expected_tag\" (from $VERSION_FILE)" >&2
            VERIFY_FAILURES=$((VERIFY_FAILURES + 1))
        fi
    fi
    if [ "$VERIFY_FAILURES" -ne 0 ]; then
        die "$VERIFY_FAILURES version mismatch(es)"
    fi
    echo "ok: $version consistent across $(target_count) target(s)${tag:+ and tag $tag}"
}

cmd_release() {
    local version tag
    version="$(read_version)"
    [[ "$version" =~ $SEMVER_RE ]] || die "$VERSION_FILE is not a semver: \"$version\""
    [ -z "$(git status --porcelain)" ] || die "working tree not clean; commit or stash first"
    cmd_verify
    tag="$(tag_prefix)${version}"
    git tag -a "$tag" -m "Release $version"
    git push origin "$tag"
    echo "tagged and pushed $tag"
}

usage() {
    cat >&2 <<'EOF'
usage: version <command> [args]

  current               print the version (from VERSION)
  set <X.Y.Z>           set VERSION, then apply to all targets
  bump <major|minor|patch>
                        bump VERSION, then apply to all targets
  apply                 write VERSION into every version.json target
  verify [<tag>]        assert all targets == VERSION (and == tag if given)
  release               clean-tree check, verify, then tag + push
EOF
    exit 2
}

# --- dispatch ----------------------------------------------------------------

cmd="${1:-}"
shift || true
case "$cmd" in
    current) cmd_current "$@" ;;
    set)     cmd_set "$@" ;;
    bump)    cmd_bump "$@" ;;
    apply)   cmd_apply "$@" ;;
    verify)  cmd_verify "$@" ;;
    release) cmd_release "$@" ;;
    ""|-h|--help|help) usage ;;
    *) die "unknown command: $cmd (try --help)" ;;
esac
