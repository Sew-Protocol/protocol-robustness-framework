# Sew Protocol — Evidence Design System (SPEDS)
v1.1 — High-Fidelity Visual Specification

SPEDS is the foundational visual language for the Sew Protocol. It is designed to turn deterministic protocol evidence into high-signal narratives for researchers, contributors, and the public.

---

## 1. Design Tokens (The Palette)

| Token | Role | Hex |
| :--- | :--- | :--- |
| **SYS_PRIMARY** | Integrity / Active State | `#7ADDDC` |
| **SYS_SUCCESS** | Verified / Refunded | `#03DAC6` |
| **SYS_STRUCTURAL** | Deep Architecture / Borders | `#004D59` |
| **SYS_ALERT** | Adversarial / Disputed | `#FF9800` |
| **SYS_ERROR** | Invariant Violation | `#EF4444` |
| **BG_CANVAS** | Base Background | `#020617` |
| **BG_SURFACE** | Component Background | `#0F172A` |

---

## 2. Core Visual Primitives

### P1: Actor Node (V-ACT)
**Purpose:** Identifies entities participating in the simulation.
- **Treatment:** Solid circles/squares with monospaced labels.
- **Sizing:** 64px (Large/Hero), 40px (Small/Detail).
- **Semantics:** 
  - `[BYR]` (Buyer), `[SLR]` (Seller), `[RES]` (Resolver), `[GOV]` (Governance).
- **Anti-Pattern:** Using avatars. Names/Faces distract from mechanism logic.

### P2: Escrow Flow Line (V-FLO)
**Purpose:** Defines the causal path of value and state.
- **Treatment:** Solid vectors with directional arrowheads.
- **Sizing:** 2pt stroke width.
- **Flow Types:** 
  - **Principal:** Solid `#7ADDDC`.
  - **Yield:** Dashed `#7ADDDC`.
  - **Adversarial Pressure:** Thick solid `#FF9800`.
- **Anti-Pattern:** Bezier curves. Precision requires linear/orthogonal paths.

### P3: Escalation Timeline (V-TIM)
**Purpose:** Maps temporal progression across dispute tiers.
- **Treatment:** A horizontal track divided into L0, L1, L2, Kleros zones.
- **Sizing:** 800px min-width for full desktop views.
- **Motion:** "Scan-line" cursor indicates current simulation time.
- **Anti-Pattern:** Gaps between tiers. Disputes are continuous transitions.

### P4: Attack Overlay (V-ATK)
**Purpose:** Marks a malicious event or attempt.
- **Treatment:** 45-degree diagonal stripes (Hazard pattern) in `#FF9800`.
- **Sizing:** Scale to fit the targeted flow line or node.
- **Motion:** Subtle "jitter" or high-frequency flicker to signal instability.
- **Anti-Pattern:** Red/Fire icons. Use hazard stripes to signal a technical "deviation."

### P5: Invariant Status Marker (V-INV)
**Purpose:** Verifies that protocol rules remain unbroken.
- **Treatment:** "Heartbeat" badge. Monospaced rule ID + Status.
- **Sizing:** 24px height.
- **Semantics:** 
  - `INV-SOLV: OK` (Teal), `INV-SOLV: FAIL` (Red).
- **Anti-Pattern:** Hiding the ID. Users must know *which* rule is being checked.

### P6: Confidence Indicator (V-CON)
**Purpose:** Expresses the depth of simulation coverage.
- **Treatment:** A segmented progress bar or "Signal Strength" icon.
- **Sizing:** 120px width.
- **Semantics:** 
  - 1 bar: Edge-case coverage.
  - 4 bars: Full Monte Carlo / Formal Verification.
- **Anti-Pattern:** Numerical percentages (e.g. 98%). Use qualitative segments to signal "Confidence Level."

### P7: Protocol Response Marker (V-RES)
**Purpose:** Visualizes the specific code guard that blocked an attack.
- **Treatment:** The "Intercept Shield." A heavy 4pt border in `#7ADDDC` that "catches" an orange flow.
- **Motion:** Instant "Impact" expansion.
- **Anti-Pattern:** Slow animations. Defense is instantaneous state-rejection.

### P8: Replay Badge (V-RPY)
**Purpose:** Links a visual to its deterministic proof.
- **Treatment:** Monospaced footer: `TRACE_ID: 8f2a...1b9c | REPLAY: VERIFIED`.
- **Sizing:** 12pt monospaced text.
- **Anti-Pattern:** Large centered badges. This is a footnote/citation, not the hero.

---

## 3. Composition Rules

### The "Evidence Card" (Social/Farcaster)
- **Top:** V-ACT (Primary) + V-INV (Current Status).
- **Center:** V-TIM + V-FLO + V-ATK (The Incident).
- **Bottom:** V-RES (The Fix) + V-RPY (The Proof).

### The "Interactive Lab" (Notebook)
- **Layer 1:** Background grid (8px).
- **Layer 2:** V-TIM spanning full width.
- **Layer 3:** Real-time V-INV updates as the user scrubs the timeline.

---

## 4. Motion & Transition Principles

1. **Causal Snapping:** Elements should "snap" into place following the flow of the timeline.
2. **Impact Flash:** On an attack interception (V-ATK + V-RES), use a 1-frame white-to-teal flash.
3. **Data Pulse:** Active flow lines should have a subtle brightness pulse to indicate "live" simulation.
4. **Terminal Drift:** Text in V-RPY should have a slight vertical scroll effect to imply active processing.

---

## 5. Information Hierarchy

1. **Primary:** What is the protocol state? (V-INV).
2. **Secondary:** What tried to change it? (V-ATK).
3. **Tertiary:** How was it stopped? (V-RES).
4. **Context:** Who and When? (V-ACT, V-TIM).
5. **Validation:** Is this real? (V-RPY).
