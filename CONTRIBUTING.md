# Contributing

`cloud-itonami-isic-8129-facade` accepts contributions to the OSS blueprint,
capability bindings, policy tests, documentation and operator model. This
repo is a role-suffix satellite of `cloud-itonami-isic-8129` -- see
`docs/adr/0001-architecture.md` for how it relates to its parent.

## Development

```bash
clojure -M:test
clojure -M:lint
clojure -M:run   # sim.cljc demo driver
```

## Rules

- Do not commit real client, employee, supplier, deployment-zone-permit or
  pedestrian-safety-incident data.
- Keep readiness assessment, physical robot dispatch, completion logging and
  safety-concern flagging behind the FacadeCleaningGovernor.
- Treat this actor as high-risk: it dispatches a physical robot into public
  space. Add tests for site/zone/SDS verification, effect discipline, scope
  exclusion, escalation and audit logging for any new behavior.
- `:cleaning/dispatch` must never be added to any phase's `:auto` set. It is
  a permanent, structural fact of this actor's design that physical dispatch
  always requires human sign-off -- not a rollout milestone to relax later.
- Never phrase a governor scope-exclusion term as a bare noun (e.g. "robot",
  "chemical", "site") -- phrase it as the finalization/execution ACTION (e.g.
  "directly actuated the robot", "entered the site without owner consent"),
  and add/extend the
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion` regression
  test for any new term. A bare-noun term will self-trip this actor's own
  legitimate `:cleaning/flag-safety-concern` happy path -- see
  `facadecleaningops.governor/scope-excluded-terms`'s docstring.
- Never add an op that performs direct actuator control, unverified site
  access, or unscoped chemical use to the closed proposal-op allowlist --
  those actions are structurally excluded from this actor's vocabulary, not
  merely gated.
- Do not weaken `facadecleaningops.operation`'s `:request-approval` node's
  live-store re-verification (re-running `governor/check` against the LIVE
  store before honoring a human approval decision). This is the actor's
  single most safety-critical behavior; any change to it requires new tests
  in `actor_test.clj` mirroring
  `stale-verdict-does-not-bypass-fresh-hard-violation-at-resume--*`.
- Do not add a code dependency on `kotoba-lang/giemon` (the hardware
  fixture repo) or on any payment/procurement library -- this actor's
  advisor/governor/store operate on request/store maps only.
- Document any new deployment-zone or business-model assumption in `docs/`.

## Pull Requests

PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether the architecture doc needs updates.
