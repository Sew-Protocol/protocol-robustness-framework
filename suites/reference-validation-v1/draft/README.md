# Draft reference scenarios

JSON artifacts here are **not** verified by CI. They document planned or in-progress evidence rows (Sybil ring, reorg sensitivity, etc.).

Promoted to CI (`manifest.edn`): `yield-accrual-efficiency-v1` → `scenarios/S88_yield-accrual-efficiency.json`.

When a draft scenario is promoted, add it to `../manifest.edn` and refresh expected outputs:

```bash
make refresh-reference-validation-v1
```
