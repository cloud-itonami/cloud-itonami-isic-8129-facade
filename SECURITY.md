# Security Policy

This project handles autonomous physical robot dispatch into public space,
deployment-zone permitting, and cleaning-fluid safety-data-sheet scoping.
Treat vulnerabilities as potentially high impact even when the demo data is
synthetic -- a governor bypass here could translate into an unpermitted or
unsafe physical robot dispatch.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real client, employee, supplier or pedestrian-safety-incident data exposure
- authorization bypass
- FacadeCleaningGovernor bypass, including any weakening of the
  `:request-approval` live-store re-verification
- audit-ledger tampering
- deployment-zone permit or SDS-scope check bypass
- over-disclosure in safety-concern reports or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on client/employee/supplier data, policy enforcement, deployment-zone
  permitting, or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real client, employee, supplier and pedestrian-safety-incident data
  outside this repository.
- Run policy tests before deployment, including the
  `stale-verdict-does-not-bypass-fresh-hard-violation-at-resume--*` tests.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
- Never dispatch a physical robot in production against anything but a
  currently valid, independently verified deployment-zone permit.
