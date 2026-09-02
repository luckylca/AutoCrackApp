# Toolpack Boundaries

## ui-inspect

Owns windows, View/Compose-aware tree, coordinate pick, relationships,
properties, listeners, creation/inflate/add stacks, images and live View
mutation. It never owns method hooking or class-loader hooks.

## runtime-inspect

Owns process facts, running and declared activities, class loaders, class
search/description, and object handles/preview/dump/release. It does not mutate
runtime state except optional handle pin/release lifecycle operations.

## memory-dump

Owns maps, ranges, modules, SO, Dex, runtime XML and runtime assets. It may
compose root `/proc` access with target-runtime capabilities. All large dumps
are artifacts and every command states its actual strategy.

## runtime-control

Owns Activity start, process kill, SO injection, FLAG_SECURE mutation, WebView
debug/eval and other explicit runtime changes. Commands require a concrete
target and return before/after state when available.

## simplehook

Remains a separate command, rule schema and responsibility: method,
constructor and field hooks; record/before/after/replace/skip behavior; logging,
conditions and persistent rules. It shares only Runtime transport, registries
and class-loader observation.

No Toolpack registers model-facing functions. The Agent composes all commands
through Bash. All commands implement `--json` with deterministic success/error
envelopes.
