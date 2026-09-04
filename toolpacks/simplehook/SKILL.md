# simplehook skill

Use this CLI for precise persistent Java method/constructor/field debugging rules and structured runtime logs.

## First steps

```bash
simplehook --help
simplehook doctor --json
simplehook rules list --json
```

Before adding a rule, validate it:

```bash
simplehook rules validate RULE.json --json
simplehook rules add RULE.json --dry-run --json
```

Then add/apply the rule, trigger the target behavior, and inspect `simplehook logs --rule RULE_ID --json`.

A rule can report `WAITING_FOR_CLASS` until the relevant ClassLoader loads the target class; this is not automatically a failure. Use `inspect class/methods/fields` to verify target signatures. Rule mutations and reload/apply change target runtime behavior. Arbitrary code evaluation is intentionally unsupported.
