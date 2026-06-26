#!/usr/bin/env bash
# Determines the next version based on BCV .api file changes
# Compares current .api files with the ones from the last release tag
# Output contract:
#   stdout -> exactly ONE line: the bare semver (e.g. "0.1.1")
#   stderr -> human-readable info + "bump=..." diagnostics
# Callers capturing $() must receive only the bare version.

set -euo pipefail

CURRENT_VERSION=$(cat VERSION)
MAJOR=$(echo "$CURRENT_VERSION" | cut -d. -f1)
MINOR=$(echo "$CURRENT_VERSION" | cut -d. -f2)
PATCH=$(echo "$CURRENT_VERSION" | cut -d. -f3)

# Find the last release tag
LAST_TAG=$(git tag --sort=-version:refname -l 'v*' | head -n1)

if [ -z "$LAST_TAG" ]; then
    echo "No previous tag found. Using current version: $CURRENT_VERSION" >&2
    echo "$CURRENT_VERSION"
    echo "bump=none" >&2
    exit 0
fi

# Compare .api files between last tag and current HEAD
API_DIFF=$(git diff "$LAST_TAG" HEAD -- 'sdk/android/*/api/*.api' 2>/dev/null || true)

if [ -z "$API_DIFF" ]; then
    # No API changes — patch bump
    NEW_PATCH=$((PATCH + 1))
    NEW_VERSION="$MAJOR.$MINOR.$NEW_PATCH"
    echo "$NEW_VERSION"
    echo "bump=patch" >&2
    exit 0
fi

# Check for removed/changed lines (breaking changes)
REMOVED=$(echo "$API_DIFF" | grep '^-[^-]' | grep -v '^\-\-\-' || true)

if [ -n "$REMOVED" ]; then
    # Breaking change: symbols removed or signatures changed
    if [ "$MAJOR" -eq 0 ]; then
        # Pre-1.0: bump minor
        NEW_MINOR=$((MINOR + 1))
        NEW_VERSION="$MAJOR.$NEW_MINOR.0"
        echo "$NEW_VERSION"
        echo "bump=minor (breaking, pre-1.0)" >&2
    else
        # Post-1.0: bump major
        NEW_MAJOR=$((MAJOR + 1))
        NEW_VERSION="$NEW_MAJOR.0.0"
        echo "$NEW_VERSION"
        echo "bump=major (breaking)" >&2
    fi
    exit 0
fi

# Only additions (new public API) — minor bump
NEW_MINOR=$((MINOR + 1))
NEW_VERSION="$MAJOR.$NEW_MINOR.0"
echo "$NEW_VERSION"
echo "bump=minor (new API)" >&2
