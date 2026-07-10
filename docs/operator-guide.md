# Operator Guide

## First Deployment
1. Register traders, procurement-compliance officers, telecom/
   electronics equipment-orders, and counterparties.
2. Import equipment-order, counterparty, credit, manufacturer/sourcing,
   buyer-category, and sanctions-screening history.
3. Seed the per-jurisdiction spec-basis catalog (`telecomtrade.facts/
   catalog`) for the jurisdictions you actually trade in, and the
   covered-manufacturer named-entity list (`telecomtrade.facts/
   covered-manufacturers`), citing real official sources only.
4. Run read-only spec-basis validation per jurisdiction.
5. Configure covered-manufacturer / buyer-category / sanctions
   escalation and accounts-receivable accounts.
6. Publish a dry-run dispatch/invoice and audit export.

## Minimum Trading Controls
- spec-basis validation before any verification, dispatch, or invoice
- full counterparty-diligence evidence (credit-clearance record,
  contract/PO, sanctions-screening record) before any dispatch
- an actual `:buyer-category` recorded on every order -- never inferred,
  never defaulted to `:commercial-unrestricted`
- a covered-manufacturer/buyer-category check before any dispatch --
  the order is blocked ONLY when BOTH the manufacturer is on the
  covered list AND the buyer category is one the restriction reaches
  (see `telecomtrade.governor/covered-manufacturer-buyer-restricted-
  violations` for the exact conjunction)
- credit-clearance, contract-on-file, and sanctions checks before any
  dispatch; sanctions checks before any invoice
- covered-manufacturer / buyer-category / sanctions escalation gate
- audit export for every dispatch, invoice, and hold
- backup manual dispatch and invoicing process

## A Day in the Life: Intake → Verify → Dispatch → Settle → Audit

Wholesale of Electronic and Telecommunications Equipment and Parts
(ISIC 4652, `cloud-itonami-isic-4652`) runs on the same intake / advise
/ govern / decide / commit-or-hold loop as every itonami blueprint, but
here the loop is concrete: a regional telecom/networking-equipment
wholesaler needs to bring an equipment-order (say, a 6-unit 5G
base-station lot to a commercial reseller in the USA) from intake
through sourcing verification to a physical dispatch and an invoice
settlement. Walking through the loop end to end:

1. **Intake.** The trader books the equipment-order through `:forms`:
   order-id, equipment-description, equipment-type (router / switch /
   base-station / radio / network-appliance), manufacturer, buyer-
   category (federal-agency / federal-contractor / fcc-usf-funded-
   carrier / commercial-unrestricted), counterparty, price,
   contract-terms, and the order's own diligence record (credit-
   cleared?, sanctions-screened?). This creates a telecom-order record
   at `:order/intake` status. The TelecomTradeAdvisor only normalizes
   the patch; it does not invent the order-id, counterparty,
   manufacturer, buyer-category, or any commercial/diligence value.
2. **Verify.** The TelecomTradeAdvisor drafts a per-jurisdiction GENERIC
   counterparty-diligence evidence checklist (`:sourcing/verify`) from
   `telecomtrade.facts`, citing the jurisdiction's official spec-basis
   (owner authority, legal basis, provenance) and listing the required
   evidence (credit-clearance record, contract/PO, sanctions-screening
   record). The `:telecom-supply-chain-governor` sign-off gate must
   clear: it checks the jurisdiction actually has an official spec-basis
   on file (never invent one). A jurisdiction with no spec-basis is a
   HARD hold at the governor node -- it never even reaches a human. This
   verification always escalates to a human for approval; it is never
   auto.
3. **Dispatch.** Before real telecom/networking equipment can leave the
   wholesaler's control, the `:telecom-supply-chain-governor` sign-off
   gate runs the full HARD check set against the order's own ground
   truth: the spec-basis exists, the evidence checklist is complete, the
   counterparty's credit has been cleared, contract-terms are on file,
   the order is NOT a covered-manufacturer/restricted-buyer-category
   pairing (the order's `:manufacturer` is checked against
   `telecomtrade.facts/covered-manufacturers`; independently, the
   order's `:buyer-category` is checked against `telecomtrade.facts/
   restricted-buyer-categories`; the dispatch is refused ONLY when BOTH
   are true), and the counterparty has passed sanctions screening. Any
   failure is a HARD hold that a human cannot override. Critically, a
   covered manufacturer's equipment going to a `:commercial-unrestricted`
   buyer is NOT held by this check, and a restricted-category buyer
   ordering a non-covered manufacturer's equipment is ALSO not held --
   only the conjunction of BOTH facts is. If every check is clean, the
   proposal STILL always escalates to a human trading supervisor --
   `:delivery/dispatch` never auto-commits at any phase. On approval,
   the dispatch record (`<JURISDICTION>-DISPATCH-000001`) is drafted and
   the order's `:dispatched?` flag is set.
4. **Settle.** Once the order has actually been dispatched, the invoice
   is settled (`:invoice/settle`): the money side of the trade, custody
   / financial transfer. The governor re-checks the spec-basis, the
   evidence completeness, the sanctions screening, and that this order's
   invoice has not already been settled. As with the dispatch, a clean
   invoice STILL always escalates to a human -- `:invoice/settle` never
   auto-commits. On approval the invoice record is drafted
   (`<JURISDICTION>-INVOICE-000001`) and the order's `:invoiced?` flag
   is set.
5. **Audit.** The verification, the dispatch sign-off, the dispatch
   record, the invoice sign-off, and the invoice record are all
   appended to the `:audit-ledger` -- immutable and exportable, so a
   counterparty, auditor, or federal regulator dispute can be traced
   back to the exact spec-basis citation, evidence checklist,
   manufacturer/buyer-category determination, and supervisor sign-off
   that authorized the dispatch and invoice. If something is wrong with
   the counterparty or the order (a credit deterioration, a sanctions
   hit, a covered manufacturer paired with a restricted buyer category),
   that gets raised as a flag and routed through the escalation gate
   instead of being silently suppressed -- a dispatch for that order
   then waits on governor sign-off of the flag's resolution.

Any deviation from this loop is exactly what the Trust Controls in
`docs/business-model.md` exist to catch: an order verified against a
fabricated spec-basis, a dispatch started with incomplete evidence, an
uncleared counterparty credit or a contract gap, a covered-manufacturer
order dispatched to a restricted-category buyer, sanctions screening
suppressed to force a dispatch through, or an invoice posted without a
human sign-off.

## Feel the Decision Gate: `clojure -M:dev:run`

This vertical has no companion playable prototype. The fastest hands-on
way to feel why the `:telecom-supply-chain-governor` gate exists -- and
why its domain-defining check is a CONJUNCTION of two independent
facts, not a blanket manufacturer ban or a blanket buyer-category ban
-- is the bundled demo, which walks a clean order through intake →
verify → dispatch → settle (pausing for human approval), then walks the
CONTROL TRIPLE that proves the conjunction:

- a covered manufacturer (Huawei Technologies Company) + a
  `:commercial-unrestricted` buyer → dispatches CLEANLY (still escalates
  for approval, like any clean dispatch, but the governor never holds
  it),
- a non-covered manufacturer (Nokia Corporation) + a `:federal-agency`
  buyer → ALSO dispatches CLEANLY,
- the SAME covered manufacturer + a `:federal-agency` buyer → HOLD
  (`:covered-manufacturer-buyer-restricted`),
- a DIFFERENT covered manufacturer (ZTE Corporation) + a
  `:fcc-usf-funded-carrier` buyer → ALSO HOLD, proving the restriction
  reaches more than one buyer category,

and then exercises every remaining HARD-hold failure mode in isolation:

- a jurisdiction with no official spec-basis → HOLD (`:no-spec-basis`),
- a counterparty whose credit has not been cleared → HOLD
  (`:credit-uncleared`),
- an order with no contract-terms on file → HOLD (`:contract-missing`),
- a counterparty that has not passed OFAC-style sanctions screening →
  HOLD (`:counterparty-sanctions-flag-unresolved`),
- a double dispatch of the same order → HOLD (`:already-dispatched`),
- a double invoice of the same order → HOLD (`:already-invoiced`).

Each HOLD settles at the governor node and never reaches a human
approver -- the same failure mode the audit ledger is built to catch and
the minimum trading controls above are built to prevent. It is not a
substitute for those controls, but it is the fastest way for a new
operator (or a reviewer) to feel, hands-on, why the gate exists before
touching a real deployment.

## Certification
Certified operators must prove spec-basis-grounded verification,
evidence-backed dispatch readiness (credit-clearance, contract-on-file,
covered-manufacturer/buyer-category clearance, sanctions screening),
and human review for every dispatch- and invoice-affecting action.
