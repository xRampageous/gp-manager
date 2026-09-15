# Account and profile isolation

GP Manager stores tracking data under the RuneLite data directory:

```text
~/.runelite/gp-manager/
  sessions.json                 # unassigned legacy only
  accounts/<rs-profile-key>/
    sessions.json
    sessions.backup.json
  exports/
```

## Identity

- Durable owner key: RuneLite **RS profile key** (`ConfigManager.getRSProfileKey()`), which is derived from account hash plus world-type profile (standard, league, deadman, …).
- Account hash (`Client.getAccountHash()`) is used to detect login/logout transitions. Display names are never used as the sole durable identity.
- Before an RS profile key is available, the store stays **unbound** and refuses durable writes. In-memory tracking may warm up after login once identity binds.

## Login / logout / switch

1. On login or `RuneScapeProfileChanged`, pending work for the previous owner is flushed, then the new account scope is loaded.
2. Logout pauses lifecycle and flushes the current owner. Data is not merged into another account.
3. When account hash becomes invalid, the coordinator unbinds and clears in-memory owners so the next login cannot inherit the previous account’s General/history.

## Legacy data

Root `sessions.json` from pre-isolation builds remains **unassigned**. It is never auto-attached to the first account that logs in. Claiming requires an explicit action (`claimUnassignedLegacy`) so unrelated histories are never silently merged.

## Settings profiles vs account isolation

- **Account isolation** — each RS profile key has its own General tracker, custom sessions, targets, analytics, and history files.
- **RuneLite settings profiles** — plugin enablement and config presets. They share the same account data folder when the same RS profile is logged in; they do not split accounting history.

## Multi-client access

Each account scope uses an exclusive write lock and monotonic revisions. A save whose revision is older than the on-disk revision is refused (no silent last-writer-wins). A second client that cannot acquire the lock receives a failed save status with retry details.

## Reset commits

Replacement is **committed** when primary `sessions.json` has been replaced with a validated payload. Backup alignment follows. If backup alignment fails after a destructive reset, the obsolete backup is invalidated so cleared data cannot reappear through fallback recovery.
