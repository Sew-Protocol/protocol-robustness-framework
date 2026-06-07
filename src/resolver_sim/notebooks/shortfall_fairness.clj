^{:nextjournal.clerk/visibility {:code :show :result :show}}
(ns resolver-sim.notebooks.shortfall-fairness
  (:require [nextjournal.clerk :as clerk] [resolver-sim.notebooks.nav :as nav]))
^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (nav/top-nav-bar "notebooks/shortfall_fairness.clj"))
^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "# Shortfall Fairness Research")

(clerk/md "claim-deferred (yield/accounting.clj:184) is all-or-nothing. No mechanism for partial recovery, pro-rata distribution, or aggregate tracking.")

(clerk/md "| Stage | Current behavior | Fair? |
|-------|-----------------|-------|
| Shortfall application | Same ratio for all | Yes -- proportional |
| Claim on full recovery | All deferred cleared | Yes |
| Claim on partial recovery | Not possible | No mechanism exists |")

(clerk/md "## Implementation gap

claim-deferred takes one position, checks global liquidity-mode.
No concept of total deferred, recovered pool, or pro-rata distribution.

A pro-rata model would need:
1. :yield/total-deferred counter
2. :yield/recovered-pool counter
3. claim-deferred to distribute pool * (pos-deferred / total-deferred)
4. A recover-liquidity action that releases recovered funds to the pool")

(clerk/md "--- *Research notebook - shortfall fairness. June 2026.*")