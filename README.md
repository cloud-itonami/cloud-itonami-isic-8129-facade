# cloud-itonami-isic-8129-facade

Open Business Blueprint for autonomous **facade and street-cleaning robot
dispatch** -- a role-suffix satellite of
[`cloud-itonami-isic-8129`](https://github.com/cloud-itonami/cloud-itonami-isic-8129)
(ISIC Rev.5 8129: other building and industrial cleaning activities),
following the same satellite pattern this fleet already uses for
`cloud-itonami-isic-6611-cryptoexchange` under ISIC 6611. Where the parent
actor is coordination-only (job/site logging, crew/equipment dispatch
*scheduling*, supply-order coordination -- never a robotics execution
layer, never city-specific), this satellite adds the two things a real
autonomous physical robot operating in public space actually needs: a
governed **physical dispatch** operation, and a data-driven
**deployment-zone** registry (public-right-of-way permits). This is not a
re-promotion of the parent's registry entry -- see
`docs/adr/0001-architecture.md` for the exact relationship.

The motivating use case is building-exterior and streetscape cleaning in a
dense urban district (e.g. Shibuya) -- but the actor itself is **generic
and worldwide**, per this fleet's standing convention of never hardcoding
a single city into actor code. The Shibuya-ku public right-of-way permit
is the *first entry* in a pure-data `deployment-zone` registry (modeled on
`etzhayyim/com-etzhayyim-kyoninka`'s jurisdiction-as-data pattern); adding
any other city or zone is a data addition, never a code change.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem/Datomic checkpoints) -- the same actor pattern as every
prior actor in this fleet -- here it is
**FacadeCleaningAdvisor ⊣ FacadeCleaningGovernor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:facade-street-cleaning-governor`,
is a distinct, independent build (confirmed unique across the
`cloud-itonami` org before this repo was created, and re-verified
immediately before the first commit).

> **Why an actor layer at all?** An LLM is great at drafting a readiness
> assessment or a dispatch proposal -- but it has no license to actually
> actuate a robot, no authority to decide a deployment zone's
> public-right-of-way permit is still valid, no way to independently
> confirm a cleaning fluid is verified-safe for a given surface, and no
> notion of when a "flag this concern" op quietly turns into a claim to
> have already bypassed a safety interlock. Letting it act directly
> invites a robot dispatched into an unpermitted public zone, an
> unverified chemical applied to an unscoped surface, or -- worst of all
> -- a fabricated claim to have already resolved a safety concern,
> exposing pedestrians, crews and clients to real injury and liability.
> This project seals the FacadeCleaningAdvisor into a single node and
> wraps it with an independent **FacadeCleaningGovernor**, a human
> **approval workflow that re-verifies against the live store at resume
> time** (not just at intake), and an immutable **audit ledger**.

## Scope: assessment + supervised dispatch only, never unsupervised actuation

This actor never performs or authorizes:

- direct actuator control of the robot outside a governed, human-approved
  `:cleaning/dispatch`
- unverified site access (operating at a site without an independently
  verified owner-consent record)
- unscoped chemical use (applying a cleaning fluid to a surface outside
  its verified safety-data-sheet scope)
- deployment-zone permitting decisions -- this actor only *reads*
  zone/permit state, it never grants, renews or overrides a permit

The governor's `scope-exclusion-violations` check re-scans every proposal
for this failure mode independently of the advisor's own framing, and
treats it as a HARD, permanent block regardless of confidence or how clean
everything else is. **The closed proposal-op allowlist structurally never
includes any op that directly actuates the robot, grants site access, or
overrides a chemical-scope restriction -- there is no such op to gate,
only one to permanently exclude.**

### Actuation

**Every proposal this actor generates is `:effect :propose`, never a
direct actuation.** `:cleaning/dispatch` -- the actual physical dispatch of
a robot into public space -- is **always** escalated to a human, at every
phase, with no auto-commit path ever (two independent layers enforce this:
the governor's `always-escalate-ops` and the phase table's `:auto` set,
which never includes `:cleaning/dispatch`). And unlike every prior sibling
actor read while building this repo, the human approval decision itself is
**re-verified against the live store at resume time** -- a deployment-zone
permit expiring or a site's owner-consent being revoked during the human
review window is not silently bypassed by a stale pre-interrupt verdict.

## The core contract

```
site/owner-consent + deployment-zone permit + SDS scope + request
        |
        v
   ┌───────────────────────┐   proposal      ┌────────────────────────────┐
   │ FacadeCleaning-        │ ─────────────▶ │ FacadeCleaningGovernor      │  (independent system)
   │ Advisor (sealed)      │  + citations    │ site-unverified ·           │
   └───────────────────────┘                 │ zone-unverified/permit ·    │
          │                 commit ◀┼ sds-out-of-scope ·                 │
          │                         │ effect-not-propose ·               │
    record + ledger        escalate ┼ evidence-missing ·                 │
          │              (ALWAYS for│ scope-excluded · op-not-allowed     │
          │       dispatch/safety-  └────────────────────────────┘
          │       concern)
          ▼
  human approval ── re-verified against LIVE store before honored ──▶ commit | hold
```

## Robotics premise

This actor **is** the physical-robot dispatch layer -- unlike most
coordination-only siblings in this fleet, `:cleaning/dispatch` proposes a
real physical dispatch, always gated by human sign-off and the independent
FacadeCleaningGovernor. It has **no code dependency on
[`kotoba-lang/giemon`](https://github.com/kotoba-lang/giemon)** (the
hardware fixture repo, built by a separate phase of this same multi-repo
project) -- the advisor/governor/store operate purely on request/store
maps, and any real hardware telemetry arrives as ordinary request/context
data, never as a required import.

## Features

- **Closed proposal-op allowlist**: `cleaning/assess`, `cleaning/dispatch`,
  `cleaning/log-completion`, `cleaning/flag-safety-concern` (all `:effect
  :propose`). No op in this allowlist actuates the robot directly, grants
  site access, or overrides a chemical-scope restriction.
- **Five HARD governor checks** (permanent, un-overridable):
  1. **Site unverified** -- the target site must exist AND be
     independently registered/verified/owner-consented in the store.
  2. **Zone unverified** -- the target deployment-zone must exist, be
     `:active`, and (if required) hold a currently valid public-right-of-
     way permit.
  3. **SDS out-of-scope** -- a named cleaning fluid must resolve to a
     registered/verified SDS record whose verified-surfaces cover the
     target surface.
  4. **Effect is `:propose`** -- any other `:effect` value is rejected.
  5. **Evidence missing** -- a completion log without photo/sensor
     evidence is rejected.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:cleaning/dispatch` and `:cleaning/flag-safety-concern` -- ALWAYS
    escalate, regardless of confidence or phase.
  - (LLM confidence below the floor also escalates, as with every sibling
    actor.)
- **Live-store re-verification at resume** -- `:request-approval` re-runs
  the governor against the current store before honoring a human's
  decision, closing the "fact changed during human review" gap present in
  the sibling actors this repo was modeled on.
- **Staged rollout** (Phase 0→3), with a fixed missing-phase-defaults-
  to-conservative bug: an unset `:phase` resolves to phase 1 (assisted),
  never phase 3 (see `facadecleaningops.phase`'s docstring for the fixed
  bug's provenance).
- **Append-only audit ledger** -- every decision is an immutable log
  entry.
- **langgraph StateGraph** -- one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

### Test suite

- `test/facadecleaningops/governor_test.clj` -- unit tests of governor
  hard checks, scope exclusion, and the self-trip regression test
- `test/facadecleaningops/actor_test.clj` -- full graph integration: clean
  commit, hard-hold, escalate→approve, escalate→reject, the
  missing-phase-defaults-to-conservative fix, and the safety-critical
  live-store re-verification at resume
- `test/facadecleaningops/store_contract_test.clj` -- Store protocol and
  MemStore implementation

### Modules

- `facadecleaningops.store` -- SSoT (MemStore, string-keyed site/zone/sds
  directories, append-only ledger)
- `facadecleaningops.advisor` -- contained intelligence node (mock +
  real-LLM seam, resolved via the `murakumo-main` KV alias)
- `facadecleaningops.governor` -- independent compliance layer
- `facadecleaningops.phase` -- staged rollout (0→3), conservative missing-
  phase default
- `facadecleaningops.operation` -- langgraph StateGraph, with live-store
  re-verification at resume
- `facadecleaningops.sim` -- demo driver

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`8129-facade`, the satellite registry entry).

## Business-process coverage (honest)

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Readiness/route/chemical-scope assessment (`:cleaning/assess`) | Real route-optimization/fleet-scheduling integration |
| Physical robot dispatch, ALWAYS human-gated, live-re-verified at resume (`:cleaning/dispatch`) | Direct hardware/actuator control -- structurally excluded, not a gap |
| Post-run completion logging with mandatory photo/sensor evidence (`:cleaning/log-completion`) | Real evidence-storage/CDN integration -- evidence refs only |
| Safety-concern flagging, ALWAYS human-gated (`:cleaning/flag-safety-concern`) | Directly resolving/clearing any safety concern -- permanently out of scope |
| Deployment-zone public-right-of-way permit validity check (data-driven, any city) | Applying for or granting a new permit -- this actor only reads permit state |
| SDS-scoped chemical-fluid verification | Formulating or certifying a new SDS record |
| Immutable audit ledger for every assess/dispatch/log/flag decision | Billing/invoicing/procurement -- owned by a separate phase of this project |

Extending coverage is additive: add the next op as its own governed op
with its own HARD checks and tests, following the SAME "an independent
governor re-verifies against the actor's own records before any real-world
act" pattern this repo's flagship checks already establish.

## Maturity

`:implemented` -- `FacadeCleaningAdvisor` + `FacadeCleaningGovernor` run
as real, tested code (see `Development` above), following the SAME
governed-actor architecture as every prior actor across this fleet, with
its own distinct, independently-named governor and the fleet's first
live-store re-verification at human-approval resume.

## License

Code and implementation templates are AGPL-3.0-or-later.
