# ADR 0001: Facade and Street-Cleaning Robot-Dispatch Actor Architecture

## Status
Accepted

## Context

`cloud-itonami-isic-8129-facade` is a role-suffix **satellite** of the
already-`:implemented` `cloud-itonami-isic-8129` actor (ISIC Rev.5 8129:
other building and industrial cleaning activities), following the same
satellite pattern this fleet already uses for
`cloud-itonami-isic-6611-cryptoexchange` under ISIC 6611. This is **not**
a re-promotion of the parent's registry entry -- the parent
(`industrialcleaningops.*`) remains exactly what it was: a coordination-
only actor with no robotics execution layer and no city-specificity. This
satellite adds two structural capabilities the parent never needed:

1. **Physical robot dispatch execution** (`:cleaning/dispatch`) -- the
   parent only ever *schedules* a crew/equipment dispatch as a proposal
   for a human to act on later; this satellite actually proposes
   dispatching an autonomous robot, always gated by human sign-off.
2. **Data-driven deployment-zone permitting** -- a public-right-of-way
   permit registry, modeled as pure data (never a hardcoded city), so a
   robot may only be dispatched into a zone that is `:active` and (when
   required) holds a currently valid permit.

The motivating use case is building-exterior and streetscape cleaning in
a dense urban district -- Shibuya is the first seeded zone -- but nothing
in the schema or the governor names Shibuya specifically. Adding another
city or zone anywhere in the world is a pure data addition to the
`deployment-zone` registry, never a code change (mirrored on
`etzhayyim/com-etzhayyim-kyoninka`'s jurisdiction-as-data pattern).

The threat model is acute, more so than the parent's, because this actor
proposes **physical actuation in public space**:
- **Operational scope creep**: a system designed for "cleaning dispatch"
  can drift into direct actuator control, unverified site entry, or
  unscoped chemical application if the governance layer is not explicit.
- **LLM drift**: an LLM-backed advisor can hallucinate content that
  implies bypassing an actuator interlock or an SDS surface restriction
  (e.g. "actuate the robot directly" or "apply the chemical outside its
  verified surface scope").
- **Human override under time pressure**: even with escalation, an
  operator under time pressure might approve a dispatch without noticing
  that a deployment-zone permit expired, or a site's owner-consent was
  revoked, in the minutes between the escalation being raised and the
  operator clicking approve. This is the specific gap this actor closes
  that every sibling actor read while building this repo did not: see
  "Live-Store Re-Verification at Resume" below.

This actor has **no code dependency on `kotoba-lang/giemon`** (the
hardware fixture repo, owned by a separate phase of this same multi-repo
project) -- the advisor/governor/store operate purely on request/store
maps; any real hardware telemetry arrives as ordinary request/context
data handed to the advisor, never as a required import. It also has no
dependency on `cloud-murakumo`, Stripe, or any procurement/payment
library (a separate phase owns that), and contains no 3D/rendering code
(a separate phase owns the simulation app).

## Decision

Implement the facade/street-cleaning robot-dispatch actor as:

### 1. Closed Allowlist (Advisor Layer)

The `facadecleaningops.advisor` namespace restricts its proposal
vocabulary to exactly these operations:
- `:cleaning/assess` -- readiness/route/chemical-scope check for a
  proposed dispatch, **never itself grants dispatch**
- `:cleaning/dispatch` -- the actual physical robot dispatch proposal
- `:cleaning/log-completion` -- post-run record with evidence
- `:cleaning/flag-safety-concern` -- always-escalate anomaly flag

Any operation touching direct actuator control, unverified site access,
or unscoped chemical use is **permanently out of scope**. There is no
keyword, no `case`/`cond` branch, no vocabulary path anywhere in
`facadecleaningops.advisor` that can construct such a proposal --
anything outside the four ops above collapses to the same `{:op :unknown
:confidence 0.0}` catch-all every sibling advisor in this fleet uses.
This is a **structural**, not merely a denied-downstream, guarantee.

### 2. Hard Invariants (Governor Layer)

The `facadecleaningops.governor/check` function enforces five classes of
hard violations that always route to `:hold` (no approval path, no
exception, no override):

1. **Site unverified**: the target site must exist AND be independently
   `:registered?`/`:verified?`/`:owner-consent?` (all three) in the
   store. Re-derived from the site's own record every time -- ground
   truth, never proposal self-report.

2. **Zone unverified**: the target deployment-zone must exist, be
   `:zone/status :active`, and -- if `:zone/permit-required?` -- hold a
   permit that is `:status :granted` and not expired as-of `today`.
   Ported from `kyoninka.governor`'s `PermitGovernor`
   `valid-permit?`/`permit-violations` discipline.

3. **SDS out-of-scope**: when a proposal names a `:fluid-id`, it must
   resolve to a registered/verified SDS record whose
   `:verified-surfaces` contains the proposal's own `:surface`. A
   foreign-key-into-a-verified-record check (never a text-scan),
   extending this actor family's supply-chain vendor-verification
   pattern (ISIC 8129's own `vendor-unverified-violations`) to the
   chemical-scope boundary.

4. **No direct actuation**: the proposal's `:effect` must be `:propose`
   only. Universal no-actuation invariant present in every actor in this
   fleet.

5. **Evidence missing**: `:cleaning/log-completion` without a non-empty
   `:evidence` vector (photo/sensor refs) is a HARD, permanent block --
   a follow-up record without verified evidence is never accepted
   (named trigger from the ISIC 8121 building-cleaning blueprint,
   ADR-2607103900: "a chemical-handling task outside verified
   safety-data-sheet scope, ... a follow-up record without verified
   evidence").

Plus **scope exclusion**: any proposal outside the closed four-op
allowlist, or whose content touches direct actuator control, unverified
site access, or overriding a verified-surface/SDS restriction, is a
HARD, PERMANENT block, evaluated unconditionally on every proposal.
Every scope-excluded term is phrased as the finalization/execution
ACTION (e.g. "directly actuated the robot"), never a bare noun (e.g.
"robot"), to avoid self-tripping this actor's own legitimate
`:cleaning/flag-safety-concern` proposals -- a known, regression-tested
bug class in this fleet (see
`default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
`governor_test.clj`).

Hard violations are **non-overridable**. There is no escalation path, no
human approval route, and no threshold above which they are waived.

### 3. Escalation Invariants (Human Sign-Off)

- `:cleaning/dispatch` -- **ALWAYS** escalates to a human, regardless of
  confidence, regardless of how clean the proposal otherwise is. The
  actual physical dispatch of a robot into public space always requires
  human sign-off in this design; no phase ever auto-approves it (matches
  itonami's own `:cert/assess`-always-high-stakes and kyoninka's
  launch-always-high-stakes pattern).
- `:cleaning/flag-safety-concern` -- **ALWAYS** escalates, for the same
  reason every sibling actor's safety-concern flag always escalates:
  surfacing a concern for a human is the entire point of the op.
- **Low advisor confidence** (`< 0.6`) -- escalates regardless of op.

`facadecleaningops.phase`'s 0→3 rollout table independently agrees:
`:cleaning/dispatch` and `:cleaning/flag-safety-concern` are never
members of any phase's `:auto` set, at any phase -- two layers, not one,
enforce the same invariant.

### 4. Live-Store Re-Verification at Resume (novel in this repo)

Every sibling actor read while building this repo (including this
satellite's own parent, `industrialcleaningops.operation`) computes the
governor verdict once, in the `:govern` node, before the interrupt, and
then the `:request-approval` node -- which runs after a human has had an
arbitrary amount of time to review -- only inspects the human's
`:status`, never re-checking whether the world changed in the meantime.
That is a real bypass path specifically for this actor: a deployment-
zone's public-right-of-way permit can expire, a site's owner-consent can
be revoked, or new pedestrian-safety telemetry can arrive during exactly
the review window a HARD governor block is supposed to be protecting
against.

`facadecleaningops.operation`'s `:request-approval` node therefore
**re-runs `governor/check` against the LIVE store** (the same mutable
store object the whole graph is closed over, not a snapshot captured at
intake time) **before even looking at the human's approval decision**.
If a fresh HARD violation now exists, the proposal is held regardless of
what the human clicked, and the ledger records this as a distinct
`:approval-superseded-by-fresh-hold` disposition (not an ordinary
`:approval-rejected`, so operators can tell the two apart in the audit
trail). See `actor_test.clj`'s
`stale-verdict-does-not-bypass-fresh-hard-violation-at-resume--zone-permit-expires`
and `--site-consent-revoked` tests, which mutate the live store between
interrupt and resume and assert the human's `:approved` decision is
still overridden.

### 5. Missing-Phase Defaults to Conservative (fixed bug class, applied here from the start)

`facadecleaningops.phase/default-phase` is **1** (assisted, still
requires human approval for every write), not 3 (supervised, the most
permissive tier). This fleet has a documented, previously-fixed bug
class where a caller who simply omits `:phase` from `context` -- an
ordinary, non-malicious code path, not just malformed input --
accidentally received the MOST permissive tier instead of the safest
default, because `default-phase` was set to 3. The fix's rationale is
recorded verbatim in `orgs/gftdcojp/cloud-itonami/src/itonami/phase.cljc`'s
own `default-phase` docstring: "'can only add caution' has to hold for a
MISSING phase too, not only an explicitly-set low one." This repo
implements the conservative default from the start rather than
retrofitting it, and `actor_test.clj`'s
`missing-phase-defaults-to-conservative-not-permissive` and
`missing-phase-still-hard-holds-a-phase-2-only-op` tests exercise this
end-to-end through the full graph, not just as a `phase.cljc` unit test.

### 6. Closed Allowlist Rationale

A closed allowlist ("`only` these ops are allowed") is stronger than a
denylist ("`never` these ops"). A denylist is fragile: if the design
omits a forbidden action from the denylist, the actor can drift into new
scope. A closed allowlist forces the design to be explicit about every
permitted operation, and any new operation requires deliberate design
review and testing.

Example: if the architect listed forbidden actions as `#{:direct-actuate
:bypass-interlock}`, an LLM advisor trained on general text might
propose `:override-safety-stop` or `:force-dispatch`, operations that
were never explicitly forbidden and thus would slip through. With a
closed allowlist, any op outside `#{:cleaning/assess :cleaning/dispatch
:cleaning/log-completion :cleaning/flag-safety-concern}` is **structurally
rejected** at the governance layer.

### 7. What This Actor DOES

- **Readiness/route/chemical-scope assessment**: pre-flight evaluation of
  a proposed dispatch (site, zone, fluid, surface).
- **Physical robot dispatch proposal**: always human-gated, always
  live-re-verified at resume.
- **Post-run completion logging**: with mandatory photo/sensor evidence.
- **Safety-concern flagging**: statistical/observed-anomaly surfacing
  with mandatory human escalation.

### 8. What This Actor DOES NOT (Hard Boundaries, Permanently Out of Scope)

- **Direct actuator control** -- no vocabulary anywhere in this actor for
  actuating/controlling equipment outside a governed, human-approved
  `:cleaning/dispatch`.
- **Unverified site access** -- no authority to operate at a site without
  an independently verified owner-consent record.
- **Unscoped chemical use** -- no authority to apply a cleaning fluid to
  a surface outside its verified SDS scope.
- **Deployment-zone permitting decisions** -- this actor only *reads*
  zone/permit state; it never grants, renews, or overrides a
  public-right-of-way permit.
- **Hardware fixture control** -- no dependency on `kotoba-lang/giemon`;
  the advisor/governor operate on request/store maps only.
- **Procurement/payment** -- no dependency on `cloud-murakumo`, Stripe,
  or any procurement library (a separate phase of this project owns
  that).
- **3D rendering/simulation** -- no rendering code (a separate phase of
  this project owns the simulation app).

These are not "high-risk operations requiring escalation" -- they are
entirely outside the actor's design vocabulary. The governor will
**permanently `:hold`** any proposal that touches these categories.

## Consequences

### Positive

- **No operational scope creep**: the allowlist is explicit and must be
  reviewed to expand.
- **LLM safety**: even if an LLM advisor hallucinates a direct-actuation
  or unverified-access proposal, the advisor layer's structural
  vocabulary limit and the governor's independent scope-scan both catch
  it.
- **Structural exclusion**: direct actuation, unverified access, and
  unscoped chemical use are not negotiable; they are structurally absent
  from the actor's vocabulary.
- **Closes the "review-window bypass" gap**: unlike every sibling actor
  read while building this repo, a fact that changes during human review
  cannot silently slip a dispatch past a hard invariant.
- **Auditability**: every held proposal, including a stale-verdict
  supersession, leaves a distinct ledger entry.

### Negative

- **Strictness**: legitimate new dispatch operations require design
  review and code change. Operators cannot expand scope dynamically via
  config (a feature for safety, not a bug).
- **Re-verification cost**: `:request-approval` now performs a second
  governor check on resume -- a deliberate, small, worthwhile cost given
  what it prevents (a stale-verdict bypass of a hard safety invariant).

## Implementation Details

### Store Protocol

```clojure
(defprotocol Store
  (site [s site-id])                ; registered site/building, owner-consent status
  (zone [s zone-id])                ; deployment-zone permitting record (pure data)
  (sds [s fluid-id])                ; safety-data-sheet record, verified-surfaces
  (dispatch-record [s dispatch-id])
  (ledger [s])                      ; append-only audit trail
  (commit-record! [s record])
  (append-ledger! [s fact])
  (with-sites [s sites]) (with-zones [s zones]) (with-sds [s sds-records])
  (set-zone! [s zone-id zone-record])   ; simulate a fact changing mid-review
  (set-site! [s site-id site-record]))
```

Only `MemStore` (atom-based) is implemented in this R0. A `DatomicStore`
behind the same protocol, following `kotoba-lang/langchain-store`'s
`:db-api` seam (ADR-2607141600), was scoped as a stretch goal and was
**not** attempted in this build -- `MemStore` alone is the documented gap
here, per the task's explicit allowance to skip it and document rather
than fake it.

### Advisor Interface

```clojure
(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

; Proposal:
{:op :cleaning/assess|:cleaning/dispatch|:cleaning/log-completion|:cleaning/flag-safety-concern
 :site-id str :zone-id str
 :effect :propose
 :confidence 0.0-1.0
 :rationale "explanation"}
```

### Governor Verdicts

```clojure
{:ok? bool          ; true if proposal clears all gates (no hard/escalate)
 :violations [...]  ; hard violations (if :hard? true)
 :confidence n      ; advisor confidence (0.0-1.0)
 :hard? bool        ; irreversible :hold flag
 :escalate? bool    ; human sign-off required flag
 :high-stakes? bool}
```

### State Graph

```text
:intake -> :advise -> :govern -> :decide -+-> :commit             (:ok? true)
                                           +-> :request-approval    (:escalate? true, interrupt-before,
                                           |                         RE-VERIFIES against live store on resume)
                                           +-> :hold                (:hard? true)
```

## References

- ADR-2731008129 (`cloud-itonami-isic-8129` -- the parent actor this repo
  is a satellite of)
- ADR-2607103900 (`cloud-itonami-isic-8121` building-cleaning blueprint --
  origin of the "verified safety-data-sheet scope" / "follow-up record
  without verified evidence" named triggers this actor's HARD checks 3
  and 5 implement)
- `kyoninka.governor` (`etzhayyim/com-etzhayyim-kyoninka`) -- the
  `PermitGovernor` mandatory-permit-at-launch pattern this actor's zone
  check is ported from
- `orgs/gftdcojp/cloud-itonami/src/itonami/phase.cljc` -- provenance of
  the missing-phase-defaults-to-conservative fix
- ADR-2607011000: Itonami Actor Pattern (langgraph StateGraph)
- CLAUDE.md, Actors section: Standing regulations for actor design in
  this workspace
- 8423 (Public Order and Safety Administrative Operations): the cleanest
  fully-`:implemented` reference actor this repo's module shape and
  architecture-doc structure follow
