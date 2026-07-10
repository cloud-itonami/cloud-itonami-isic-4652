# Governance

`cloud-itonami-isic-4652` is an OSS open-business blueprint for wholesale
of electronic and telecommunications equipment and parts.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a telecom-order whose jurisdiction has no official telecom-supply-
  chain-sourcing spec-basis can never be verified, dispatched or
  invoiced.
- the Telecom Supply-Chain Governor remains independent of the advisor.
- hard governor violations (a fabricated spec-basis, incomplete
  counterparty-diligence evidence, an uncleared counterparty credit, a
  missing contract, a covered-manufacturer order whose buyer category is
  one the restriction reaches, an unresolved OFAC-style sanctions flag,
  a double dispatch or a double invoice) cannot be overridden by human
  approval.
- `covered-manufacturer-buyer-restricted` remains a CONJUNCTION of
  covered-manufacturer status AND restricted buyer category -- never
  collapsed into a blanket manufacturer ban or a blanket buyer-category
  ban.
- every intake, sourcing verification, dispatch, settlement and hold is
  auditable.
- counterparty, credit, manufacturer/sourcing, buyer-category and
  sanctions-screening data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit and data-flow review.

Certified operators can lose certification for:
- bypassing dispatch or invoice-settlement policy checks
- mishandling counterparty, credit, manufacturer/sourcing, buyer-
  category, or sanctions-screening data
- misrepresenting certification status
- failing to respond to security incidents
