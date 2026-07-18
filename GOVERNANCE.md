# Governance

`cloud-itonami-isic-8129-facade` is an OSS open-business blueprint for
autonomous facade and street-cleaning robot operations (a role-suffix
satellite of ISIC Rev.5 8129 -- other building and industrial cleaning
activities -- adding physical-robot dispatch and deployment-zone
permitting on top of the parent's coordination-only scope).

## Maintainers

Maintainers may merge changes that preserve these invariants:
- a proposal for an unverified/unregistered/non-consented site, or a
  deployment-zone that is inactive or lacks a currently valid
  public-right-of-way permit, can never commit.
- the FacadeCleaningGovernor remains independent of the advisor.
- hard policy violations (non-`:propose` effect, an out-of-scope
  cleaning fluid, a completion log without evidence, direct-actuation or
  unverified-access content, an op outside the closed allowlist) cannot
  be overridden by human approval.
- `:cleaning/dispatch` is always escalated to a human, at every phase,
  with no auto-commit path -- ever.
- a human approval decision is re-verified against the live store before
  being honored, so a fact that changes during the review window cannot
  silently bypass a hard invariant.
- every assessment, dispatch, completion log and safety-concern flag is
  auditable.
- client, employee, supplier and pedestrian-safety-incident data stays
  outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, deployment-zone schema, public business model, operator
certification or license should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is
a separate trust mark and should require security, audit and data-flow
review, plus jurisdiction-specific public-right-of-way permitting review
for any deployment zone actually operated.

Certified operators can lose certification for:
- bypassing readiness-assessment, dispatch, completion-logging or
  safety-concern-flagging policy checks
- dispatching a robot into a deployment zone without a currently valid
  public-right-of-way permit
- mishandling client, employee, supplier or pedestrian-safety-incident
  data
- misrepresenting certification status
- failing to respond to safety incidents
