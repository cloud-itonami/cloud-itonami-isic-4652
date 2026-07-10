# Business Model: Wholesale of Electronic and Telecommunications Equipment and Parts

## Classification
- Repository: `cloud-itonami-isic-4652`
- ISIC Rev.5: `4652` -- wholesale of electronic and telecommunications
  equipment and parts
- Domain: `downstream/telecom-electronics-equipment-wholesale`
- Social impact: national security, supply-chain integrity, transparency
- Governor: `:telecom-supply-chain-governor`
- License: AGPL-3.0-or-later

## Scope
This actor covers telecom/electronics equipment-order intake through
per-jurisdiction counterparty-diligence / covered-manufacturer /
sanctions regulatory verification, physical equipment dispatch (routers,
switches, base stations, radios and related networking/telecom
hardware, wholesaled to a counterparty), and invoice settlement for a
telecom/electronics-equipment wholesaler. It does **not**, by itself,
hold any operating authority required to run a telecom/electronics-
equipment-wholesale business in a given jurisdiction, perform the actual
physical warehouse pick/pack, or judge trading-book economics
(fulfillment routing and trading-book optimization is a follow-up
slice, not this R0). Whoever deploys a live instance supplies the
jurisdiction-specific operating authority, the real warehouse-
management/ERP dispatch integration, and bears that jurisdiction's
liability -- the software supplies the governed, spec-cited, audited
execution scaffold so the operator does not have to build the
compliance layer from scratch.

## Customer
- regional and independent telecom/networking-equipment wholesalers and
  value-added resellers (VARs)
- systems integrators and carriers sourcing routers, switches, base
  stations, radios and related electronic parts
- federal agencies, federal contractors and FCC-USF-funded carriers who
  need an auditable, spec-cited, sourcing-verified trade record
- procurement-compliance officers and counsel who need a structured,
  queryable covered-manufacturer/buyer-category audit trail rather than
  a shared spreadsheet

## Offer
- telecom/electronics equipment-order intake and directory management
  (equipment type, manufacturer, destination, buyer category,
  counterparty)
- per-jurisdiction contract / covered-manufacturer / sanctions
  regulatory verification with an official spec-basis citation
- physical equipment dispatch gated on full evidence, a credit-cleared
  counterparty, contract-terms on file, a covered-manufacturer/buyer-
  category clearance, and passed sanctions screening
- invoice settlement (custody / financial transfer) with double-invoice
  prevention
- evidence checklisting (credit-clearance record, contract/PO,
  sanctions-screening record)
- covered-manufacturer, buyer-category and sanctions exception workflows
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per trading desk / procurement-compliance
  seat
- support retainer with SLA
- ERP and accounts-receivable integration

## The `:telecom-supply-chain-governor` Decision Rule

This blueprint's `:itonami.blueprint/governor` is `:telecom-supply-
chain-governor`. It is the single authority that stands between
"telecom/networking equipment could be dispatched to a counterparty" and
"it is allowed to leave the wholesaler's control," and between "an
invoice could be settled" and "it is allowed to settle." Every rule it
enforces is traceable to the domain (Wholesale of Electronic and
Telecommunications Equipment and Parts, ISIC 4652) and to the three
`:social-impact` tags in `blueprint.edn` (`:national-security`,
`:supply-chain-integrity`, `:transparency`).

This is the rule the companion contract test
(`test/telecomtrade/governor_contract_test.clj`) encodes end-to-end: the
TelecomTradeAdvisor never dispatches equipment to a counterparty or
settles an invoice the Telecom Supply-Chain Governor would reject,
`:delivery/dispatch` and `:invoice/settle` NEVER auto-commit at any
phase, `:order/intake` (no direct capital risk) MAY auto-commit when
clean, and every decision (commit OR hold) leaves exactly one ledger
fact.

**Authorizes an equipment dispatch (`:delivery/dispatch`) or invoice
settlement (`:invoice/settle`) only when ALL of the following hold:**

1. **An official spec-basis citation exists for the jurisdiction** -- the
   governor will not authorize any `:sourcing/verify`, `:delivery/
   dispatch`, or `:invoice/settle` proposal whose jurisdiction has no
   entry in the `telecomtrade.facts` catalog (`:no-spec-basis`). This is
   the direct enforcement of `:transparency`: a jurisdiction whose
   telecom-supply-chain-sourcing requirements cannot be traced to an
   OFFICIAL public source is never guessed.
2. **The jurisdiction's required evidence is fully on file** -- for a
   dispatch or invoice the order's jurisdiction must have been verified
   with a complete counterparty-diligence evidence checklist on record:
   the credit-clearance record, the contract / purchase order, and the
   sanctions-screening (OFAC / equivalent) record
   (`:evidence-incomplete`). Deliberately does NOT include covered-
   manufacturer/buyer-category status -- that is check 5 below.
3. **The counterparty's credit has been cleared** -- refuses to move
   real equipment when credit has NOT been cleared (the leasing
   collateral-coverage discipline, applied to counterparty credit)
   (`:credit-uncleared`). Evaluated at `:delivery/dispatch`.
4. **Contract-terms are on file** -- the governor refuses to dispatch
   when no `:contract-terms` are recorded for the order
   (`:contract-missing`). Evaluated at `:delivery/dispatch`.
5. **The order is not a covered-manufacturer/restricted-buyer-category
   pairing** -- WHEN the order's `:manufacturer` IS a named entity on
   `telecomtrade.facts/covered-manufacturers` AND the order's `:buyer-
   category` IS one of `telecomtrade.facts/restricted-buyer-categories`
   (`:federal-agency`/`:federal-contractor`/`:fcc-usf-funded-carrier`),
   BOTH facts true at once, dispatch is refused
   (`:covered-manufacturer-buyer-restricted`). THIS IS THE DOMAIN-
   DEFINING CHECK -- see "The covered-manufacturer/buyer-category
   conjunction" below for the full reasoning and how it differs from
   every prior sibling's own domain-defining check.
6. **The counterparty has passed OFAC / equivalent sanctions screening**
   -- the governor reads the dedicated `:sanctions-screened?` fact and
   treats an unresolved sanctions-screening flag as a HARD, un-
   overridable hold (`:counterparty-sanctions-flag-unresolved`).
   Evaluated UNCONDITIONALLY at both `:delivery/dispatch` and
   `:invoice/settle`.
7. **The order has not already been dispatched, and the invoice has not
   already been settled** -- a double dispatch of the same order is
   refused off a dedicated `:dispatched?` fact, and a double invoice off
   a dedicated `:invoiced?` fact (never a `:status` value), the double-
   actuation guard every sibling actor in this fleet enforces
   (`:already-dispatched` / `:already-invoiced`).

**Rejects (HOLD, un-overridable, never even reaches a human) when any of
the above fail.**

**Always escalates to a human (never auto-commits) for `:delivery/
dispatch` and `:invoice/settle`**, even when every check above is clean.
Dispatching real telecom/networking equipment to a counterparty and
settling a real invoice (real money moving between counterparty and
wholesaler) are the two real-world actuation events this actor
performs; both are always a human trading supervisor's call. This is
enforced by TWO independent layers that agree on purpose: the
governor's confidence/actuation SOFT gate (a `:delivery/dispatch` /
`:invoice/settle` stake always escalates) and `telecomtrade.phase`'s
phase table, which never puts either op in any phase's `:auto` set.

## The covered-manufacturer/buyer-category conjunction: a genuinely different check shape

Every prior wholesale-trading sibling's own domain-defining check falls
into one of three shapes:

1. **A single coarse boolean** (`cloud-itonami-isic-4690`, general
   trading): "has SOME export-control process been completed," evaluated
   at the level of a jurisdiction citation.
2. **Two SEQUENTIAL, DEPENDENT checks** (`cloud-itonami-isic-4651`,
   computer/software wholesale): "has this item been classified at
   all," then, ONLY once that has an answer, "does the classification
   require a license for this destination, and is one on file." The
   second question cannot even be asked until the first is answered.
3. **Two sub-facts folded into ONE named rule because both are arms of
   the SAME real-world determination** (`cloud-itonami-isic-4669`'s
   Prior Informed Consent -- a filed transboundary notification AND the
   destination's own competent-authority consent are both steps of ONE
   bilateral procedure; `cloud-itonami-isic-4662`'s conflict-minerals
   chain-of-custody check is the same shape). Missing EITHER sub-fact is
   equally unsafe, because both belong to the same underlying act.

This vertical's `covered-manufacturer-buyer-restricted` check is a
FOURTH shape: **a conjunction of TWO INDEPENDENT facts that would be
true or false regardless of each other**, drawn from TWO UNRELATED
SOURCES:

- **Fact A**: is the equipment's MANUFACTURER a named entity on a real
  covered-list (`telecomtrade.facts/covered-manufacturer?`, reading
  Section 889(f)(3) of the NDAA FY2019 / the FCC Covered List)? This is
  a fact about the SELLER'S SUPPLY CHAIN -- true or false independent of
  who is buying.
- **Fact B**: is the BUYER'S OWN CATEGORY one the restriction actually
  reaches (`telecomtrade.facts/restricted-buyer-categories`)? This is a
  fact about the BUYER'S OWN procurement/funding relationship to the US
  federal government -- true or false independent of what is being
  sold.

Neither fact is a sub-step of determining the other, and neither is an
evidentiary arm of a single procedure the way Basel Convention
notification-then-consent are. They are two structurally unrelated
questions -- "who made this?" and "who is buying it, and how are they
funded?" -- whose CONJUNCTION is specifically what Section 889 and the
FCC's USF rule restrict. `test/telecomtrade/governor_contract_test.clj`
proves this is a genuine conjunction with a CONTROL TRIPLE:

- `covered-manufacturer-alone-does-not-block-a-commercial-unrestricted-
  buyer` (`eo-5`: Huawei Technologies Company + `:commercial-
  unrestricted` buyer) -- dispatches CLEANLY. Section 889 is a federal-
  procurement/federal-funding-NEXUS statute, not a general trade ban: a
  purely private commercial sale with no federal-agency, federal-
  contract/grant, or FCC-USF-funding nexus is outside every regime
  `covered-manufacturers` cites, even for a covered manufacturer's own
  equipment.
- `restricted-buyer-category-alone-does-not-block-a-non-covered-
  manufacturer` (`eo-7`: Nokia Corporation + `:federal-agency` buyer) --
  ALSO dispatches CLEANLY. A federal agency is free to buy a non-covered
  manufacturer's equipment; `:federal-agency` status alone triggers
  nothing.
- `covered-manufacturer-and-restricted-buyer-category-together-is-held-
  and-unoverridable` (`eo-6`: Huawei Technologies Company + `:federal-
  agency` buyer, BOTH facts true at once) -- HOLDS.
- `restriction-also-reaches-fcc-usf-funded-carrier-not-only-federal-
  agency` (`eo-9`: ZTE Corporation + `:fcc-usf-funded-carrier` buyer) --
  ALSO holds, proving the restriction is not `:federal-agency`-only.

### Why this is not modeled as a blanket manufacturer ban

An operator's first instinct might be "just refuse to trade ANY covered
manufacturer's equipment at all" -- a single boolean, matching the
general-trading sibling's own coarse shape. This would be DISHONEST
about what Section 889 and the FCC's rules actually restrict: they are
BUYER-CATEGORY-GATED restrictions, not a blanket prohibition on
covered-manufacturer equipment reaching any market participant
whatsoever. A purely commercial reseller with no federal nexus is a
real, lawful counterparty for covered-manufacturer equipment under these
specific regimes (see "A later, broader FCC rule" below for the ONE
real exception, deliberately scoped OUT of this R0). Modeling this
check as a blanket ban would over-restrict trade this actor has no
regulatory basis to block, and would silently misrepresent the real
legal posture in the audit ledger.

### Why this is not modeled as a blanket buyer-category ban either

The opposite collapse -- "any federal-agency/federal-contractor/FCC-USF-
funded-carrier order is restricted, regardless of manufacturer" -- would
be equally dishonest: Section 889 and the FCC's USF rule name SPECIFIC
manufacturers; they do not bar federal agencies from buying
telecommunications equipment from Cisco, Nokia, Ericsson or any other
non-covered vendor. `eo-7`'s control leg exists specifically to prove
this direction of the conjunction too.

### A later, broader FCC rule -- real, cited, deliberately out of scope for this R0

A November 2022 FCC rule (adopted in the "Protecting Against National
Security Threats to the Communications Networks or the Information and
Communications Technology Supply Chain through the Equipment
Authorization Program" proceeding) separately prohibits the FCC from
AUTHORIZING any NEW Covered List equipment for import or sale in the
United States AT ALL -- a buyer-BLIND bar on the item ever lawfully
entering the US market, not a per-order procurement restriction gated
on who is buying. This is real and cited in `telecomtrade.facts`'
`covered-manufacturers` namespace docstring, but it is DELIBERATELY NOT
modeled as a governor HARD check in this R0: it is a different
regulatory LEVER entirely (FCC equipment-authorization/certification
eligibility for the US market as a whole, evaluated once per equipment
model, not per wholesale order) than this vertical's buyer-category-
gated domain-defining check, and folding it in would erase the very
conjunction this build exists to demonstrate (a buyer-blind bar cannot
be proven via a buyer-category control triple). Correctly scoped OUT as
a follow-up, the same "extending coverage is additive" discipline every
sibling's own out-of-scope items follow (see README `Business-process
coverage`).

## Required Technologies

`blueprint.edn`'s `:itonami.blueprint/required-technologies` for this business,
and what each one is actually load-bearing for here (not a generic capability
list):

| Technology | What it is FOR in Wholesale of Electronic and Telecommunications Equipment and Parts |
|---|---|
| `:robotics` | An automated storage-and-retrieval system (AS/RS) / goods-to-person robotic shuttle that picks and stages ESD-safe (electrostatic-discharge-safe) router/switch/base-station/radio cartons at the wholesale distribution center for `:delivery/dispatch`. The governor never dispatches hardware itself: a dispatch-clearing action must have cleared the same sign-off a human trading supervisor would need (see Robotics Premise). |
| `:identity` | Trader, procurement-compliance-officer, and counterparty identity plus role-based access, so the governor's sign-off is tied to *who* authorized a dispatch or invoice, not just *that* someone did. |
| `:forms` | Structured intake for telecom/electronics equipment-order booking, per-jurisdiction evidence capture (credit-clearance record, contract/PO, sanctions-screening record), and covered-manufacturer / buyer-category / sanctions exception submission -- the data the Decision Rule above actually evaluates comes in through these forms. |
| `:dmn` | Encodes the `:telecom-supply-chain-governor` Decision Rule itself (spec-basis, evidence completeness, credit-clearance, contract-on-file, the covered-manufacturer/buyer-category conjunction, sanctions-screening, the double-actuation guards, the actuation gate) as an evaluable decision table rather than code buried in application logic -- this is what makes the governor auditable and swappable per-deployment. |
| `:bpmn` | Orchestrates the intake -> verify -> dispatch -> settle -> audit loop end-to-end (see `docs/operator-guide.md`) across telecom/electronics equipment-order intake, sourcing verification, physical dispatch, and invoice settlement, including the covered-manufacturer/buyer-category and sanctions escalation gates. |
| `:audit-ledger` | The immutable record of every verification, dispatch, invoice, covered-manufacturer/buyer-category flag, sanctions flag, and hold -- this is what "an auditable, spec-cited trade record for every dispatch and invoice" (Trust Controls, below) actually means in practice, and the evidence an operator needs if a dispatch or an invoice is later disputed by a counterparty, auditor, or federal regulator. |
| `:optimization` | Fulfillment routing and trading-book optimization -- selects the profitable fulfillment strategy across the order book. This R0 build deliberately scopes optimization OUT (see README `Business-process coverage`); the capability is correctly marked required, the integration is a follow-up slice. |

There is NO bespoke `:telecomtrade` capability library in this stack
(unlike the freight sibling's own bespoke `:logistics`): the telecom-
trading checks (credit-clearance, contract-on-file, covered-
manufacturer/buyer-category, sanctions-screening) are direct entity/
catalog reads in `telecomtrade.governor`, on top of the generic
robotics/identity/forms/dmn/bpmn/audit-ledger stack (see Capability
layer).

## Trust Controls
- a jurisdiction with no official spec-basis can never be verified,
  dispatched, or invoiced against
- a dispatch never starts with incomplete counterparty-diligence
  evidence
- a dispatch never starts with an uncleared counterparty credit, no
  contract-terms on file, or a covered-manufacturer order whose buyer
  category is one the restriction reaches
- an invoice never settles against an unresolved sanctions-screening
  flag
- covered-manufacturer/buyer-category and sanctions flags cannot be
  silently suppressed
- the same order can never be dispatched or invoiced twice
- a dispatch or invoice never auto-commits; both always need a human
  trading supervisor
- every dispatch and invoice (commit OR hold) leaves exactly one
  immutable ledger fact
- counterparty, credit, manufacturer/sourcing, buyer-category and
  sanctions data stays outside Git

## Jurisdiction coverage (honest)

`telecomtrade.facts/catalog` currently seeds 2 jurisdictions with an
official spec-basis: the United States (FCC Public Safety and Homeland
Security Bureau / Federal Acquisition Regulatory Council; Secure and
Trusted Communications Networks Act of 2019 -- FCC Covered List; Section
889 of the NDAA FY2019, Parts A and B; FAR clause 52.204-25) and the
United Kingdom (Department for Science, Innovation and Technology;
Office of Communications (Ofcom); Telecommunications (Security) Act
2021 and designated vendor directions restricting high-risk-vendor
equipment in UK public telecoms networks). This is DELIBERATELY a
SMALLER starting catalog than most prior siblings' own 4-jurisdiction
R0s -- and that is an honest reflection of this specific regulatory
topic, not a shortcut. Named-manufacturer telecom-supply-chain-sourcing
restrictions, in the specific 'the law names a manufacturer and gates
the restriction on the buyer's own category/funding relationship' shape
this actor models, are unusually US-centric among currently well-
documented regimes: the USA's Section 889 + FCC Covered List apparatus
is by far the most extensively documented and litigated version of this
mechanism.

The UK entry is included with a HONEST CONFIDENCE CAVEAT: this build is
confident the Telecommunications (Security) Act 2021 is real UK
legislation establishing telecom-security duties and a "designated
vendor direction" mechanism, and confident that the UK government's
well-publicized 2020 decision to exclude Huawei from the UK's 5G
network (and require its removal by the end of 2027) is real -- but is
LESS confident about the precise current text/number of every
implementing regulation and the exact date/scope of any specific
designated-vendor direction. An operator relying on the GBR entry for a
real UK sourcing determination must independently confirm current DSIT/
Ofcom guidance.

Two other jurisdictions were seriously considered and DELIBERATELY
EXCLUDED from this R0, honestly, rather than seeded with a low-
confidence citation to hit a quota:

- **Japan**: the Japanese government is understood to have applied
  cybersecurity-risk-based exclusion criteria to central-government
  telecom/IT procurement around December 2018, widely reported at the
  time as targeting Huawei/ZTE equipment. This build is NOT confident
  enough in the precise official document, its exact legal status
  (guideline vs. binding regulation), or whether the public text
  actually names specific manufacturers (rather than general risk
  criteria) to cite it as a `telecomtrade.facts/catalog` spec-basis
  entry without risking fabrication. Left out, pending independent
  verification -- adding it later is additive (one map entry).
- **EU (as a bloc)**: the EU's 5G Cybersecurity Toolbox (2020)
  recommends member states apply risk-based restrictions on "high-risk
  vendors," but (to this build's knowledge) does not itself name
  specific manufacturers at the EU level -- individual member states
  (e.g. Sweden's 2020 spectrum-auction conditions) have taken
  manufacturer-specific action independently. Because this actor's
  catalog is keyed by national jurisdiction with a manufacturer-naming
  regime, and the EU-level instrument does not itself name a
  manufacturer, it was left out rather than mis-cited as if it were
  equivalent to Section 889 or the UK Act.

Extending coverage is additive: add the next jurisdiction as one map
entry in `telecomtrade.facts/catalog`, citing a real official source --
never fabricate a jurisdiction's requirements to make coverage look
bigger. `telecomtrade.facts/covered-manufacturers` is similarly additive
and similarly honest: the five entries seeded are exactly the entities
named at Section 889(f)(3) of the NDAA FY2019, all independently
confirmed on the FCC's own Covered List -- no manufacturer is added on
suspicion or reputation alone.

## Maturity

`:implemented` -- `TelecomTradeAdvisor` + `Telecom Supply-Chain
Governor` run as real, tested code (see README `Run` for the exact test
count), following the SAME governed-actor architecture as the other
prior actors across this fleet, with its own distinct, independently-
named governor and its own conjunctive covered-manufacturer/buyer-
category check. See `docs/adr/0001-architecture.md` for the history and
design.

## Robotics Premise

`blueprint.edn` sets `:itonami.blueprint/robotics true`. In this domain
an automated storage-and-retrieval system (AS/RS) / goods-to-person
robotic shuttle picks and stages ESD-safe router/switch/base-station/
radio cartons at the wholesale distribution center for `:delivery/
dispatch`, under the actor, gated by the independent **Telecom Supply-
Chain Governor**. This vertical trades PHYSICAL telecom/networking
hardware exclusively (unlike the computer/software-wholesale sibling,
which splits across a physical dispatch path AND a non-physical
technology-release path) -- so, unlike that sibling's own MIXED,
path-specific robotics reasoning, this vertical's `:robotics true` is a
UNIFORM claim across its single actuation mechanism, the same shape the
fuel-wholesale and metal-wholesale siblings' own uniform robotics claims
establish. ESD-safe automated handling is a genuinely load-bearing,
well-precedented concern for electronic/telecom components specifically
(unlike bulk commodities), directly analogous to the computer/software-
wholesale sibling's own AS/RS citation. The governor never dispatches
hardware itself: a dispatch-clearing action must have cleared the same
sign-off a human trading supervisor would need -- a robot may stage a
carton at the dock, but only after the governor (every HARD check
clean) and a human supervisor both agree it is safe to -- the same
operating-state-machine-gated-by-governor premise every cloud-itonami
vertical restates (ADR-2607011000).
