#!/usr/bin/env bash
# scripts/test/release-plan.test.sh — golden-case tests for scripts/release-plan.
#
# Each test scaffolds a temp git repo (versions.toml + cli/Cargo.toml + the 5
# module dirs with src/ + api/), drives `release-plan --repo <tmp>`, and asserts
# on exit code + stdout.
#
# Run: bash scripts/test/release-plan.test.sh
#
# Bash 3.2-safe (no associative arrays). macOS / Linux portable.

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RELEASE_PLAN="$SCRIPT_DIR/../release-plan"

PASS=0
FAIL=0
FAILED_TESTS=()

# Module skeleton shared across tests.
MODULES=(core plugin-network plugin-db plugin-prefs plugin-layout)

ok()   { echo "  PASS: $1"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL: $1"; echo "    $2"; FAIL=$((FAIL+1)); FAILED_TESTS+=("$1"); }

# assert_exit <expected> <actual> <msg>
assert_exit() {
  local exp="$1" act="$2" msg="$3"
  if [[ "$exp" == "$act" ]]; then ok "$msg (exit $act)"; else bad "$msg" "expected exit $exp, got $act"; fi
}
# assert_contains <haystack> <needle> <msg>
assert_contains() {
  if grep -qF "$2" <<<"$1"; then ok "$3"; else bad "$3" "missing: '$2' in output"; fi
}
# assert_not_contains <haystack> <needle> <msg>
assert_not_contains() {
  if grep -qF "$2" <<<"$1"; then bad "$3" "unexpected: '$2' in output"; else ok "$3"; fi
}

# ───────────────── temp-repo scaffolding ─────────────────
ROOT_TMP=""

setup_repo() {
  ROOT_TMP="$(mktemp -d)"
  cd "$ROOT_TMP"
  git init -q
  git config user.email t@t.test
  git config user.name test
  git config commit.gpgsign false

  # versions.toml
  cat > versions.toml <<'EOF'
[bom]
version = "0.1.0"
[modules]
core = "0.1.0"
plugin-network = "0.1.0"
plugin-db = "0.1.0"
plugin-prefs = "0.1.0"
plugin-layout = "0.1.0"
EOF

  # cli/Cargo.toml (minimal; only the version line matters)
  mkdir -p cli/src
  cat > cli/Cargo.toml <<'EOF'
[package]
name = "devlens"
version = "0.1.0"
edition = "2021"
EOF
  echo "fn main() {}" > cli/src/main.rs

  # 5 modules with a src file + an api file.
  for m in "${MODULES[@]}"; do
    mkdir -p "sdk/android/$m/src" "sdk/android/$m/api"
    echo "package $m;" > "sdk/android/$m/src/$m.kt"
    cat > "sdk/android/$m/api/$m.api" <<EOF
public final class $m/Api {
	public fun foo ()V
}
EOF
  done

  git add -A
  git commit -q -m "init"
}

teardown_repo() {
  cd "$SCRIPT_DIR"
  [[ -n "$ROOT_TMP" ]] && rm -rf "$ROOT_TMP"
  ROOT_TMP=""
}

# commit a module src change
touch_module_src() {
  local m="$1"
  echo "// change $(date +%s%N)" >> "sdk/android/$m/src/$m.kt"
  git add -A
  git commit -q -m "change $m src"
}

# add a public API line (api-add)
add_api_line() {
  local m="$1"
  echo "	public fun newThing ()V" >> "sdk/android/$m/api/$m.api"
  git add -A
  git commit -q -m "add api $m"
}

# remove a public API line (breaking)
remove_api_line() {
  local m="$1"
  # remove last line of the api file (portable: awk instead of sed $d)
  local f="sdk/android/$m/api/$m.api"
  awk 'NF{buf=buf $0 ORS} END{printf "%s", buf}' "$f" > "$f.tmp" 2>/dev/null || true
  # simpler portable approach: drop the final line
  if [[ "$(uname)" == "Darwin" ]]; then
    sed -i '' '$d' "$f"
  else
    sed -i '$d' "$f"
  fi
  rm -f "$f.tmp"
  git add -A
  git commit -q -m "remove api $m"
}

tag_module() {  # <module> <version>
  git tag "${1}-v${2}"
}
tag_release() { # <version>
  git tag "v${1}"
}

RP() { bash "$RELEASE_PLAN" --repo "$ROOT_TMP" "$@"; }

# ───────────────── tests ─────────────────

test_noop() {
  echo "=== test: no-op (no changes since tag) ==="
  setup_repo
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done
  tag_release "0.1.0"
  local out; out="$(RP --plan --format=bare)"; local rc=$?
  assert_exit 0 "$rc" "no-op plan exit"
  assert_contains "$out" "0.1.0" "no-op plan bare == current"
  teardown_repo
}

test_api_add_minor() {
  echo "=== test: api-add → minor ==="
  setup_repo
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done
  tag_release "0.1.0"
  add_api_line "plugin-network"
  local out; out="$(RP --plan --format=bare)"; local rc=$?
  assert_exit 0 "$rc" "api-add plan exit"
  assert_contains "$out" "0.2.0" "api-add → minor 0.2.0"
  # markdown trigger
  local md; md="$(RP --plan --format=markdown)"
  assert_contains "$md" "plugin-network" "markdown lists plugin-network"
  assert_contains "$md" "0.2.0" "markdown next 0.2.0"
  teardown_repo
}

test_breaking_pre10_minor() {
  echo "=== test: breaking pre-1.0 → minor (no 1.0 cross) ==="
  setup_repo
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done
  tag_release "0.1.0"
  remove_api_line "core"
  local out; out="$(RP --plan --format=bare)"; local rc=$?
  assert_exit 0 "$rc" "breaking pre-1.0 plan exit"
  assert_contains "$out" "0.2.0" "breaking pre-1.0 → minor 0.2.0 (no major cross)"
  assert_not_contains "$out" "1.0.0" "no 1.0 graduation"
  teardown_repo
}

test_breaking_post10_major() {
  echo "=== test: breaking major>=1 → major ==="
  setup_repo
  # start from 1.2.3
  sed -i.bak 's/0.1.0/1.2.3/g' versions.toml && rm -f versions.toml.bak
  sed -i.bak 's/version = "0.1.0"/version = "1.2.3"/' cli/Cargo.toml && rm -f cli/Cargo.toml.bak
  git add -A && git commit -q -m "bump to 1.2.3"
  for m in "${MODULES[@]}"; do tag_module "$m" "1.2.3"; done
  tag_release "1.2.3"
  remove_api_line "plugin-db"
  local out; out="$(RP --plan --format=bare)"; local rc=$?
  assert_exit 0 "$rc" "breaking post-1.0 plan exit"
  assert_contains "$out" "2.0.0" "breaking post-1.0 → major 2.0.0"
  teardown_repo
}

test_code_only_patch() {
  echo "=== test: code-only → patch ==="
  setup_repo
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done
  tag_release "0.1.0"
  touch_module_src "plugin-prefs"   # src only, no api change
  local out; out="$(RP --plan --format=bare)"; local rc=$?
  assert_exit 0 "$rc" "code-only plan exit"
  assert_contains "$out" "0.1.1" "code-only → patch 0.1.1"
  teardown_repo
}

test_cascade_core_to_plugins() {
  echo "=== test: core bump → cascade ALL 4 plugins ==="
  setup_repo
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done
  tag_release "0.1.0"
  add_api_line "core"   # core minor bump 0.1.0 → 0.2.0
  local md; md="$(RP --plan --format=markdown)"; local rc=$?
  assert_exit 0 "$rc" "cascade plan exit"
  assert_contains "$md" "cascade" "markdown shows cascade trigger"
  # M12: assert ALL 4 plugins cascade to 0.1.1 with trigger=cascade, core→0.2.0.
  local js; js="$(RP --plan --format=json)"
  local core_next core_trig
  core_next="$(jq -r '.modules["core"].next' <<<"$js")"
  core_trig="$(jq -r '.modules["core"].trigger' <<<"$js")"
  assert_contains "$core_next" "0.2.0" "core next == 0.2.0 (own api-add minor)"
  assert_contains "$core_trig" "api-add" "core trigger == api-add"
  local p
  for p in plugin-network plugin-db plugin-prefs plugin-layout; do
    local n t
    n="$(jq -r --arg p "$p" '.modules[$p].next' <<<"$js")"
    t="$(jq -r --arg p "$p" '.modules[$p].trigger' <<<"$js")"
    assert_contains "$n" "0.1.1" "cascade: $p next == 0.1.1 (min patch)"
    assert_contains "$t" "cascade" "cascade: $p trigger == cascade"
  done
  local bomline; bomline="$(grep -o '"bom": {[^}]*}' <<<"$js")"
  assert_contains "$bomline" "0.2.0" "bom next == max == 0.2.0"
  teardown_repo
}

test_allow10_rejects_guard() {
  echo "=== test: forced major while major==0 → guard exit 3 (or pass with --allow-1.0) ==="
  setup_repo
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done
  tag_release "0.1.0"
  add_api_line "core"   # something changed
  # without --allow-1.0: force major → guard fires (exit 3)
  RP --plan --bump=major >/dev/null 2>&1; local rc=$?
  assert_exit 3 "$rc" "guard fires on forced major at 0.x (exit 3)"
  # with --allow-1.0 → proceeds
  local out; out="$(RP --plan --bump=major --allow-1.0 --format=bare)"; rc=$?
  assert_exit 0 "$rc" "--allow-1.0 bypasses guard"
  assert_contains "$out" "1.0.0" "--allow-1.0 graduates to 1.0.0"
  teardown_repo
}

test_verify_drift_exit2() {
  echo "=== test: --verify drift → exit 2 ==="
  setup_repo
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done
  tag_release "0.1.0"
  # create a NEWER module tag than versions.toml declares → drift
  tag_module "core" "0.5.0"
  RP --verify >/dev/null 2>&1; local rc=$?
  assert_exit 2 "$rc" "verify exits 2 on tag-ahead drift"
  teardown_repo
}

test_verify_cargo_drift_exit2() {
  echo "=== test: --verify cli/Cargo.toml drift → exit 2 ==="
  setup_repo
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done
  tag_release "0.1.0"
  # bump cli/Cargo.toml without versions.toml → drift
  sed -i.bak 's/version = "0.1.0"/version = "0.9.9"/' cli/Cargo.toml && rm -f cli/Cargo.toml.bak
  git add -A && git commit -q -m cargo-drift
  RP --verify >/dev/null 2>&1; local rc=$?
  assert_exit 2 "$rc" "verify exits 2 on cli/Cargo.toml drift"
  teardown_repo
}

test_apply_idempotency() {
  echo "=== test: --apply idempotency ==="
  setup_repo
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done
  tag_release "0.1.0"
  add_api_line "plugin-network"
  # first apply
  RP --apply >/dev/null 2>&1; local rc1=$?
  assert_exit 0 "$rc1" "first --apply exit 0"
  # versions.toml now bumped
  local v; v="$(awk -F\" '/version = /{print $2; exit}' versions.toml)"
  assert_contains "$v" "0.2.0" "versions.toml [bom] bumped to 0.2.0"
  # cli/Cargo.toml stamped
  local cv; cv="$(awk -F\" '/^version = /{print $2; exit}' cli/Cargo.toml)"
  assert_contains "$cv" "0.2.0" "cli/Cargo.toml stamped 0.2.0"
  # second apply → no-op (idempotent). versions.toml unchanged.
  local before; before="$(cat versions.toml)"
  RP --apply >/dev/null 2>&1; local rc2=$?
  assert_exit 0 "$rc2" "second --apply exit 0"
  local after; after="$(cat versions.toml)"
  if [[ "$before" == "$after" ]]; then ok "second --apply did not mutate versions.toml"; else bad "second --apply mutated versions.toml" "non-idempotent"; fi
  teardown_repo
}

test_publish_list_status() {
  echo "=== test: --publish-list status pending/done (exact split) ==="
  setup_repo
  # set declared versions to 0.2.0 (a pending release)
  sed -i.bak 's/0.1.0/0.2.0/g' versions.toml && rm -f versions.toml.bak
  git add -A && git commit -q -m bump
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done   # old tags → 0.2.0 is pending
  tag_release "0.1.0"
  local js; js="$(RP --publish-list)"; local rc=$?
  assert_exit 0 "$rc" "publish-list exit 0"
  assert_contains "$js" '"status": "pending"' "publish-list shows pending"
  assert_contains "$js" '"version": "0.2.0"' "publish-list bom version 0.2.0"
  # now tag core at 0.2.0 → core done, others pending
  tag_module "core" "0.2.0"
  local js2; js2="$(RP --publish-list)"
  assert_contains "$js2" 'core-v0.2.0' "publish-list references core tag"
  # M12: EXACT pending/done split. After tagging core done:
  #   modules: 1 done (core) + 4 pending (plugins)
  #   bom: pending (no bom-v0.2.0 tag)
  #   release: pending (no v0.2.0 tag)
  local mod_done mod_pending
  mod_done="$(jq '[.modules[] | select(.status=="done")] | length' <<<"$js2")"
  mod_pending="$(jq '[.modules[] | select(.status=="pending")] | length' <<<"$js2")"
  if [[ "$mod_done" -eq 1 ]]; then ok "publish-list modules done == 1 (core)"; else bad "publish-list modules done" "expected 1, got $mod_done"; fi
  if [[ "$mod_pending" -eq 4 ]]; then ok "publish-list modules pending == 4 (plugins)"; else bad "publish-list modules pending" "expected 4, got $mod_pending"; fi
  assert_contains "$(jq -r '.bom.status' <<<"$js2")" "pending" "publish-list bom pending"
  assert_contains "$(jq -r '.release_status' <<<"$js2")" "pending" "publish-list release pending"
  teardown_repo
}

# H1: a CLI-only change (no module changes) MUST still advance the BOM so the
# bugfix ships. noop=false; modules all unchanged; bom patch-bumps.
test_cli_only_advances_bom() {
  echo "=== test: H1 CLI-only change advances BOM (patch) ==="
  setup_repo
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done
  tag_release "0.1.0"
  # CLI-only change (no sdk/android touch)
  echo "// cli bugfix" >> cli/src/main.rs
  git add -A && git commit -q -m "cli bugfix"
  local js; js="$(RP --plan --format=json)"; local rc=$?
  assert_exit 0 "$rc" "cli-only plan exit"
  local noop bom_next cli_changed
  noop="$(jq -r '.noop' <<<"$js")"
  bom_next="$(jq -r '.bom.next' <<<"$js")"
  cli_changed="$(jq -r '.cli.changed' <<<"$js")"
  assert_contains "$noop" "false" "H1: cli-only → noop=false"
  assert_contains "$cli_changed" "true" "H1: cli.changed=true"
  assert_contains "$bom_next" "0.1.1" "H1: bom patch-advances 0.1.0 → 0.1.1"
  # no module changed
  local p changed_any=false
  for p in "${MODULES[@]}"; do
    [[ "$(jq -r --arg p "$p" '.modules[$p].changed' <<<"$js")" == "true" ]] && changed_any=true
  done
  if [[ "$changed_any" == "false" ]]; then ok "H1: no module marked changed"; else bad "H1 module changed" "a module was marked changed on a CLI-only change"; fi
  teardown_repo
}

# H4: a sub-max module bump must still advance (republish) the BOM. Scenario:
# core/plugins at 1.0.0, plugin-db at 0.5.0; bump plugin-db → max(modules)=1.0.0
# would not advance bom(1.0.0), so the BOM is patch-bumped to 1.0.1 and the new
# plugin-db pin ships.
test_divergent_bom_advances() {
  echo "=== test: H4 divergent sub-max bump advances BOM ==="
  setup_repo
  # divergent declared versions: bom + most modules 1.0.0; plugin-db 0.5.0
  cat > versions.toml <<'EOF'
[bom]
version = "1.0.0"
[modules]
core = "1.0.0"
plugin-network = "1.0.0"
plugin-db = "0.5.0"
plugin-prefs = "1.0.0"
plugin-layout = "1.0.0"
EOF
  sed -i.bak 's/version = "0.1.0"/version = "1.0.0"/' cli/Cargo.toml && rm -f cli/Cargo.toml.bak
  git add -A && git commit -q -m "divergent baseline"
  git tag core-v1.0.0; git tag plugin-network-v1.0.0; git tag plugin-db-v0.5.0
  git tag plugin-prefs-v1.0.0; git tag plugin-layout-v1.0.0
  git tag v1.0.0
  # patch bump plugin-db (code-only) 0.5.0 → 0.5.1
  touch_module_src "plugin-db"
  local js; js="$(RP --plan --format=json)"; local rc=$?
  assert_exit 0 "$rc" "divergent plan exit"
  local noop bom_next bom_prev pdb_next
  noop="$(jq -r '.noop' <<<"$js")"
  bom_next="$(jq -r '.bom.next' <<<"$js")"
  bom_prev="$(jq -r '.bom.previous' <<<"$js")"
  pdb_next="$(jq -r '.modules["plugin-db"].next' <<<"$js")"
  assert_contains "$noop" "false" "H4: noop=false (module bumped)"
  assert_contains "$pdb_next" "0.5.1" "H4: plugin-db 0.5.0 → 0.5.1"
  assert_contains "$bom_prev" "1.0.0" "H4: bom previous == 1.0.0"
  assert_contains "$bom_next" "1.0.1" "H4: bom advances 1.0.0 → 1.0.1 (patch) despite max(modules)==1.0.0"
  teardown_repo
}

# M12: cascade vs own-larger-bump. core bumps (cascades patch to plugins), but
# plugin-db has its own LARGER bump (api-add → minor 0.2.0) which must win over
# the cascade patch (0.1.1). Other plugins take the cascade.
test_cascade_vs_own_larger_bump() {
  echo "=== test: cascade vs own larger bump ==="
  setup_repo
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done
  tag_release "0.1.0"
  add_api_line "core"         # core → 0.2.0 (minor), triggers cascade
  add_api_line "plugin-db"    # plugin-db own → 0.2.0 (minor) > cascade 0.1.1
  local js; js="$(RP --plan --format=json)"; local rc=$?
  assert_exit 0 "$rc" "cascade-vs-own plan exit"
  local pdb_next pdb_trig
  pdb_next="$(jq -r '.modules["plugin-db"].next' <<<"$js")"
  pdb_trig="$(jq -r '.modules["plugin-db"].trigger' <<<"$js")"
  assert_contains "$pdb_next" "0.2.0" "plugin-db keeps OWN larger bump 0.2.0 (not cascade 0.1.1)"
  assert_contains "$pdb_trig" "api-add" "plugin-db trigger == api-add (own), not cascade"
  # the other 3 plugins take the cascade patch
  local p
  for p in plugin-network plugin-prefs plugin-layout; do
    local n t
    n="$(jq -r --arg p "$p" '.modules[$p].next' <<<"$js")"
    t="$(jq -r --arg p "$p" '.modules[$p].trigger' <<<"$js")"
    assert_contains "$n" "0.1.1" "cascade: $p next == 0.1.1"
    assert_contains "$t" "cascade" "cascade: $p trigger == cascade"
  done
  teardown_repo
}

# M12: several modules changed in one plan with DIFFERENT classifications, no
# core bump (so no cascade muddies it). Each plugin keeps its own classification.
test_multi_module_different_classifications() {
  echo "=== test: multi-module different classifications ==="
  setup_repo
  for m in "${MODULES[@]}"; do tag_module "$m" "0.1.0"; done
  tag_release "0.1.0"
  # core UNCHANGED (no cascade). Distinct per-plugin classes:
  remove_api_line "plugin-network"   # breaking pre-1.0 → minor 0.2.0
  add_api_line "plugin-db"           # api-add → minor 0.2.0
  touch_module_src "plugin-prefs"    # code-only → patch 0.1.1
  # plugin-layout + core unchanged
  local js; js="$(RP --plan --format=json)"; local rc=$?
  assert_exit 0 "$rc" "multi-class plan exit"
  assert_contains "$(jq -r '.modules["plugin-network"].next' <<<"$js")" "0.2.0" "plugin-network breaking → 0.2.0"
  assert_contains "$(jq -r '.modules["plugin-network"].trigger' <<<"$js")" "breaking" "plugin-network trigger breaking"
  assert_contains "$(jq -r '.modules["plugin-db"].next' <<<"$js")" "0.2.0" "plugin-db api-add → 0.2.0"
  assert_contains "$(jq -r '.modules["plugin-db"].trigger' <<<"$js")" "api-add" "plugin-db trigger api-add"
  assert_contains "$(jq -r '.modules["plugin-prefs"].next' <<<"$js")" "0.1.1" "plugin-prefs code-only → 0.1.1"
  assert_contains "$(jq -r '.modules["plugin-prefs"].trigger' <<<"$js")" "patch" "plugin-prefs trigger patch"
  assert_contains "$(jq -r '.modules["plugin-layout"].changed' <<<"$js")" "false" "plugin-layout unchanged"
  assert_contains "$(jq -r '.modules["core"].changed' <<<"$js")" "false" "core unchanged (no cascade)"
  assert_contains "$(jq -r '.bom.next' <<<"$js")" "0.2.0" "bom == max == 0.2.0"
  teardown_repo
}

# H6: bootstrap-inert. With NO <module>-v* tags (but a v* release tag so the CLI
# is stable), --plan is a no-op for every module and --verify rc=0 BUT emits the
# bootstrap-inert ::warning:: (so the migration can't ship silently inert).
test_bootstrap_inert() {
  echo "=== test: H6 bootstrap-inert (no module tags → noop + verify warns) ==="
  setup_repo
  # NO per-module tags. A v* tag keeps cli_changed=false so the plan is a true
  # no-op (the bootstrap-inert condition under review).
  tag_release "0.1.0"
  # --plan: noop
  local js; js="$(RP --plan --format=json)"; local rc=$?
  assert_exit 0 "$rc" "bootstrap-inert plan exit"
  assert_contains "$(jq -r '.noop' <<<"$js")" "true" "H6: no module tags → plan noop=true"
  local p
  for p in "${MODULES[@]}"; do
    assert_contains "$(jq -r --arg p "$p" '.modules[$p].changed' <<<"$js")" "false" "H6: $p inert (changed=false)"
  done
  # --verify: rc=0 (consistent) but warns on stderr about missing bootstrap tags
  local vstderr; vstderr="$(RP --verify 2>&1 >/dev/null)"; local vrc=$?
  assert_exit 0 "$vrc" "H6: verify rc=0 (state consistent despite inert)"
  assert_contains "$vstderr" "bootstrap-inert" "H6: verify emits bootstrap-inert warning"
  assert_contains "$vstderr" "release-plan --bootstrap-tags" "H6: warning points at the fix"
  teardown_repo
}

# H6: --bootstrap-tags creates <module>-v<declared> + bom-v<declared> at HEAD,
# idempotently, and clears the bootstrap-inert warning afterward.
test_bootstrap_tags_command() {
  echo "=== test: H6 --bootstrap-tags creates tags idempotently ==="
  setup_repo
  # pre: verify warns (no module tags)
  local pre; pre="$(RP --verify 2>&1 >/dev/null)"
  assert_contains "$pre" "bootstrap-inert" "pre-bootstrap: verify warns"
  # run bootstrap-tags
  RP --bootstrap-tags >/dev/null 2>&1; local rc=$?
  assert_exit 0 "$rc" "bootstrap-tags exit 0"
  # all module tags + bom tag created at declared version (0.1.0)
  local m
  for m in "${MODULES[@]}"; do
    if git rev-parse -q --verify "refs/tags/${m}-v0.1.0" >/dev/null 2>&1; then ok "bootstrap created ${m}-v0.1.0"; else bad "bootstrap ${m}-v0.1.0" "tag missing"; fi
  done
  if git rev-parse -q --verify "refs/tags/bom-v0.1.0" >/dev/null 2>&1; then ok "bootstrap created bom-v0.1.0"; else bad "bootstrap bom-v0.1.0" "tag missing"; fi
  # idempotent: re-run creates nothing new
  local out2; out2="$(RP --bootstrap-tags 2>&1)"
  assert_contains "$out2" "already present" "bootstrap-tags idempotent (skips existing)"
  teardown_repo
}

# ───────────────── run all ─────────────────
echo "release-plan test suite — $(date)"
echo

test_noop
test_api_add_minor
test_breaking_pre10_minor
test_breaking_post10_major
test_code_only_patch
test_cascade_core_to_plugins
test_cascade_vs_own_larger_bump
test_allow10_rejects_guard
test_verify_drift_exit2
test_verify_cargo_drift_exit2
test_apply_idempotency
test_publish_list_status
test_cli_only_advances_bom
test_divergent_bom_advances
test_multi_module_different_classifications
test_bootstrap_inert
test_bootstrap_tags_command

echo
echo "============================================"
echo "PASS: $PASS   FAIL: $FAIL"
if [[ $FAIL -gt 0 ]]; then
  echo "Failed: ${FAILED_TESTS[*]}"
  exit 1
fi
echo "All golden cases passed."
exit 0
