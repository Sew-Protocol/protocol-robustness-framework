# Private Discovery Workspace Template

A template for initializing sealed, confidential collaboration workspaces
for sensitive vulnerability or finding research before public disclosure.

## When to use this

- "I think I found a $100M bug"
- Private scenario draft exchange between researchers
- Confidential reproduction attempts
- Sealed priority history
- Later selective disclosure

## When NOT to use this

- Normal development (use project root or `prf-only/`)
- Public forensic evidence production (use `forensic-runner/`)

## Creating a new workspace

```bash
cp -r workspaces/private-discovery-template ~/prf-private/my-discovery
cd ~/prf-private/my-discovery
```

## Directory Structure

```
private-discovery-template/
├── README.md
├── sealed-log/             # Append-only event log
│   ├── 000001.entry.json   # Sealed entries (numeric, sequential)
│   ├── 000002.entry.json
│   └── head.json           # Pointer to latest entry hash
├── encrypted-inputs/       # At-rest encrypted scenario inputs
├── encrypted-notes/        # At-rest encrypted research notes
├── scenario-drafts/        # Working scenario definitions (unencrypted)
├── local-runs/             # Local reproduction attempts (not yet forensic)
├── disclosure-candidates/  # Sanitized findings ready for export
└── export/                 # Export path → forensic bundle creation
```

## Rules

1. **Append-only sealed log** — Every significant event (finding, hypothesis,
   reproduction, verification) is recorded as a sealed log entry. Entries
   are numbered sequentially and hash-linked.
2. **Encrypted at rest** (extension point) — Sensitive inputs and notes
   should be encrypted. Real encryption is not yet implemented in this
   template.
3. **No automatic publication** — This workspace NEVER publishes to IPFS,
   Nostr, GitHub, L1, or any public relay.
4. **Delayed public anchoring** — Anchoring is optional and happens only
   after selective export to a forensic bundle.
5. **Collaborator countersignatures** (extension point) — Multiple
   researchers can countersign sealed log entries.

## Export Path

Private discovery → Forensic bundle:

1. Draft scenario → sanitize (redact sensitive details)
2. Local run → verify against forensic policy
3. Sanitized scenario + evidence → forensic-runner template
4. Run forensic:run → produce public bundle
5. Attestation → public record

The sealed private log proves internal history and priority if needed,
but not everything must be public.

## See Also

- `docs/forensic/FORENSIC_WORKSPACE_SPEC_V1.md`
- `docs/forensic/FORENSIC_PREFLIGHT_SPEC_V1.md`
