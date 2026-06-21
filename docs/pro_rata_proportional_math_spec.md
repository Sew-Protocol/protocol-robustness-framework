# Pro-Rata / Proportional Allocation Mathematical Specification and Verification

This document presents the formal mathematical specification, proofs of key invariants, and implementation verification steps for the canonical pro-rata / proportional allocation function (`largest-remainder-alloc`) in the simulation and settlement engine.

## 1. The Pro-Rata Allocation Problem

In decentralized finance (DeFi) and dispute-resolution systems, we frequently need to distribute a discrete integer amount of resources $A \in \mathbb{N}$ (e.g., token base units/wei) across a set of $n$ claimants. Each claimant $i$ has a weight $w_i \in \mathbb{R}^+$ (e.g., stake basis, withdrawal request amount).

Let:
- $W = \sum_{j=1}^{n} w_j$ be the total weight.
- $Ideal_i = A \times \frac{w_i}{W}$ be the exact real-valued proportional share of claimant $i$.

Because the allocated resource must be distributed in indivisible base units (integers), we cannot simply award $Ideal_i$. We must compute an integer allocation vector $\mathbf{a} = [a_1, a_2, \ldots, a_n] \in \mathbb{N}^n$ such that:
1. **Conservation (Zero Leakage)**: $\sum_{i=1}^n a_i = A$
2. **Fairness / Proportionality**: Each $a_i$ is as close as possible to $Ideal_i$.

## 2. The Hare-Quota (Largest Remainder) Method

The simulator uses the **Hare-Quota (Largest Remainder) Method** to solve this allocation problem. The algorithm consists of two distinct stages:

### Stage 1: Lower-Quota Floor Allocation
First, we allocate the integer floor of the ideal share to each claimant:
$$a_i^{\text{floor}} = \lfloor Ideal_i \rfloor$$

Let $S^{\text{floor}} = \sum_{i=1}^n a_i^{\text{floor}}$ be the total units allocated in Stage 1.

The remaining unallocated units (the "shortage") is:
$$H = A - S^{\text{floor}}$$

By definition of the floor function, $Ideal_i - 1 < a_i^{\text{floor}} \le Ideal_i$.
Summing this inequality over all $i$:
$$A - n < S^{\text{floor}} \le A$$
Which implies:
$$0 \le H < n$$

Thus, the shortage $H$ is a non-negative integer strictly less than the number of claimants $n$.

### Stage 2: Fractional Remainder Distribution
To satisfy conservation, we must distribute exactly $H$ additional units, at most one per claimant.
For each claimant, we compute the fractional remainder:
$$r_i = Ideal_i - a_i^{\text{floor}}$$
Note that $0 \le r_i < 1$.

We sort the claimants by their remainder $r_i$ in descending order. The top $H$ claimants in this sorted list receive an additional unit:
$$a_i = \begin{cases} a_i^{\text{floor}} + 1 & \text{if } i \in \text{Top } H \text{ by remainder} \\ a_i^{\text{floor}} & \text{otherwise} \end{cases}$$

#### Tie-Breaking (Determinism)
If there is a tie ($r_j = r_k$), the tie is broken deterministically by the original input sequence index (lower index gets priority). This guarantees identical execution traces across simulations and replays.

---

## 3. Mathematical Proof of Invariants

We prove three core properties for the Hare-quota allocation method:

### Theorem 1: Conservation of Value
The sum of final allocations equals the total available amount $A$.
$$\sum_{i=1}^n a_i = A$$

**Proof:**
By construction, the final allocation is:
$$\sum_{i=1}^n a_i = \sum_{i=1}^n \left( a_i^{\text{floor}} + \mathbb{I}(i \in \text{Top } H) \right)$$
where $\mathbb{I}$ is the indicator function.
$$\sum_{i=1}^n a_i = \sum_{i=1}^n a_i^{\text{floor}} + \sum_{i=1}^n \mathbb{I}(i \in \text{Top } H)$$
$$\sum_{i=1}^n a_i = S^{\text{floor}} + H$$
Since $H = A - S^{\text{floor}}$:
$$\sum_{i=1}^n a_i = S^{\text{floor}} + (A - S^{\text{floor}}) = A$$
Thus, no dust is generated, and no extra tokens are minted or leaked. $\blacksquare$

### Theorem 2: Quota Rule Compliance
Every claimant's allocation satisfies the upper and lower quota bounds:
$$\lfloor Ideal_i \rfloor \le a_i \le \lceil Ideal_i \rceil$$

**Proof:**
Since $a_i$ is either $a_i^{\text{floor}}$ or $a_i^{\text{floor}} + 1$:
1. If $a_i = a_i^{\text{floor}} = \lfloor Ideal_i \rfloor$:
   The lower bound holds trivially. The upper bound holds because $\lfloor Ideal_i \rfloor \le \lceil Ideal_i \rceil$.
2. If $a_i = a_i^{\text{floor}} + 1 = \lfloor Ideal_i \rfloor + 1$:
   - If $Ideal_i$ is an integer, then the remainder $r_i = 0$. Since we only distribute units to positive remainders (or up to $H$ elements), if all remainders are 0, $H=0$, so no addition occurs. Thus, $Ideal_i$ must be non-integer.
   - For non-integers, $\lfloor Ideal_i \rfloor + 1 = \lceil Ideal_i \rceil$.
   - Thus, $a_i = \lceil Ideal_i \rceil$.

In all cases, $\lfloor Ideal_i \rfloor \le a_i \le \lceil Ideal_i \rceil$. $\blacksquare$

---

## 4. Implementation Flow & Evidence Validation

In `resolver-sim.yield.exact-math/largest-remainder-alloc`, exact ratios (`clojure.lang.Ratio`) are used for all intermediate calculations, avoiding any floating-point drift or precision loss.

### Step-by-Step Runtime Verification

On every call to `largest-remainder-alloc`, the system executes the following checks:

```clojure
;; Step 1: Pre-conditions check
{:pre [(let [tot (ratio total-available)]
         (and (not (nil? tot)) (>= tot 0)))
       (sequential? claims)]}

;; Step 2: Intermediate state checks (inside let)
_ (assert (>= shortage 0) "Shortage must be non-negative")
_ (assert (< shortage n) "Shortage must be strictly less than the number of claims")

;; Step 3: Post-condition verification of conservation
_ (assert (= total-units total-allocated) "Conservation violation")

;; Step 4: Post-condition verification of Quota Rule compliance
(dotimes [i n]
  (let [idl (nth ideal i)
        [flr rem] (quantize-base-units idl)
        cel (if (zero? rem) flr (inc flr))
        alloc (nth final-units i)]
    (assert (and (>= alloc flr) (<= alloc cel)) "Quota rule violation")))
```

This strict checking guarantees that any violation of the pro-rata mathematical contract immediately halts execution and prevents corrupted states from being written or committed.

---

## 5. Application: Pro-Rata Rational Resolver Threshold

### 5.1 Problem

The strategy payoff model (`optimal-strategy-under-load` in `evidence_costs.clj`) takes
`num-disputes` as its primary load signal and derives:

$$\text{effort-per-dispute}_i = \frac{B_i}{D_i}$$

where $B_i$ is resolver $i$'s effort budget and $D_i$ its dispute count.
This determines the **load level** (`:light` / `:medium` / `:heavy` / `:extreme`) and
hence the honest/lazy/malicious payoff crossover — the *rational threshold* at which a
resolver switches strategy.

**Bug (pre-fix):** `D_i = D_{\text{total}}$ for every resolver, i.e. the global epoch
dispute count was used regardless of how many disputes each resolver actually handles.
This systematically mis-estimates `effort-per-dispute` and therefore the strategy
crossover point, especially for heterogeneous resolver populations.

### 5.2 Correct Model

In a pool of $m$ resolvers with budgets $\{B_1, \ldots, B_m\}$ and total budget
$\mathcal{B} = \sum_{j=1}^{m} B_j$, the pro-rata dispute assignment is:

$$D_i = D_{\text{total}} \times \frac{B_i}{\mathcal{B}} \quad (\text{in general, rounded by Hare-quota})$$

The resulting effort-per-dispute:

$$\text{effort-per-dispute}_i = \frac{B_i}{D_i} \approx \frac{B_i}{D_{\text{total}} \cdot B_i / \mathcal{B}} = \frac{\mathcal{B}}{D_{\text{total}}}$$

which is **constant across resolvers** (up to integer rounding). This confirms the
invariant that under pro-rata assignment the effort-per-dispute signal is uniform, and
load classifications are consistent across the resolver pool.

### 5.3 Implementation: `prorata-dispute-load`

Located in `resolver-sim.stochastic.evidence-costs`. Delegates to
`largest-remainder-alloc` for the integer allocation, inheriting all guarantees
from Sections 2–4:

| Guarantee | Mapping |
|-----------|---------|
| Conservation | $\sum_i D_i = D_{\text{total}}$ — no dispute is lost or double-counted |
| Quota Rule | $\lfloor q_i \rfloor \le D_i \le \lceil q_i \rceil$ where $q_i = D_{\text{total}} \cdot B_i / \mathcal{B}$ |
| Non-negativity | $D_i \ge 0$ for all $i$ |
| Completeness | Every resolver ID appears in result |
| Determinism | Tie-breaking by input index — no hidden RNG |

A belt-and-suspenders conservation assertion is also placed at the `prorata-dispute-load`
boundary (independent of the inner assertion in `largest-remainder-alloc`).

### 5.4 Usage in `apply-load-optimal`

In `resolver-sim.sim.defection/apply-load-optimal`:

```clojure
;; 1. Build per-resolver budget map (falls back to global cfg budget)
resolver-budgets {id → effort-budget-per-epoch}

;; 2. Hare-quota allocation of total disputes
per-resolver-disputes (ec/prorata-dispute-load
                        (keys resolver-histories)
                        resolver-budgets
                        total-disputes
                        effort-budget-per-epoch)

;; 3. Per-resolver threshold evaluation
;; Each resolver gets its own :_epoch-trials → its own load snap
resolver-params (assoc params :_epoch-trials (get per-resolver-disputes id 0))
load-snap       (when (pos? resolver-disputes)
                  (load-optimal-snapshot resolver-params cfg rng))
```

Zero-dispute guard: if `total-disputes = 0` (epoch with no cases), `load-snap` is `nil`
and `decision` short-circuits to `{:to from :skip? true}` — no strategy switch occurs.

### 5.5 Test Coverage

New test namespace: `resolver-sim.stochastic.evidence-costs-prorata-test`
- 17 tests, 24 assertions covering G1–G5 individually
- Proportionality under exact-ratio, 2x, and 3-way budgets
- All edge cases: zero total, single resolver, empty, all-zero weights, missing budgets
- Rational threshold integration smoke test (confirms effort-per-dispute equalises)
