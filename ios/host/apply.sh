#!/bin/sh
set -eu

pin=b14aa9e6da9df15f467d8aad0090888373cc9b21
overlay=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
check_only=0
if [ "${1-}" = --check ]; then
    check_only=1
    shift
fi
if [ "$#" -ne 1 ]; then
    printf 'Usage: %s [--check] KERNARIUM_CHECKOUT\n' "$0" >&2
    exit 2
fi
checkout=$(git -C "$1" rev-parse --show-toplevel)
head=$(git -C "$checkout" rev-parse HEAD)
if [ "$head" != "$pin" ]; then
    printf 'Expected Kernarium %s, found %s\n' "$pin" "$head" >&2
    exit 1
fi
if [ -n "$(git -C "$checkout" status --porcelain --untracked-files=no)" ]; then
    printf 'Use a clean checkout of the pinned Kernarium revision.\n' >&2
    exit 1
fi
patch="$overlay/cocode-kernarium.patch"
git -C "$checkout" apply --check "$patch"
if [ "$check_only" -eq 0 ]; then
    git -C "$checkout" apply "$patch"
fi
