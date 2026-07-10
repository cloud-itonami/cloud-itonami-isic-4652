# ADR-0001: TelecomTradeAdvisor ⊣ Telecom Supply-Chain Governor architecture

## Status

Accepted. `cloud-itonami-isic-4652` published directly as `:implemented`
in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-4652` publishes an OSS business blueprint for
wholesale of electronic and telecommunications equipment and parts
(telecom/electronics equipment-order intake, per-jurisdiction contract /
covered-manufacturer / sanctions regulatory verification, physical
dispatch, and invoice settlement). Like every prior actor in this
fleet, the blueprint alone is not an implementation: this ADR records
the governed-actor architecture that establishes it as real, tested
code, following the same langgraph StateGraph + independent Governor +
Phase 0->3 rollout pattern established by `cloud-itonami-isic-6511`
(life insurance) and applied across the PRINCIPAL wholesale-trading
siblings: `cloud-itonami-isic-4671` (fuel wholesale, single-commodity
excise/sanctions focus, origin of this build's own two-member
sequential-dual-actuation shape), `cloud-itonami-isic-4690`
(general/diversified wholesale trading, coarse multi-commodity export-
control/sanctions focus), `cloud-itonami-isic-4662` (metal wholesale,
metal-type-gated conflict-minerals provenance), `cloud-itonami-isic-
4669` (waste wholesale, hazard-classification-gated bilateral Prior
Informed Consent), and `cloud-itonami-isic-4651` (computer/software
wholesale, dual-use/encryption EXPORT-CONTROL classification -- this
build's closest thematic cousin, and the sibling this build most
deliberately differentiates from).

ISIC 4652 is a PRINCIPAL trading model like all five siblings above --
the wholesaler takes title and resells. Its defining regulatory
exposure is DIFFERENT IN KIND from the computer/software-wholesale
sibling's own export-CLASSIFICATION exposure, not merely a variant of
it: `techtrade.governor`'s domain-defining question is "what is this
item's own TECHNICAL CLASSIFICATION, and does that classification
require a license for this DESTINATION" -- a question about the ITEM.
This vertical's domain-defining question is "is this item's
MANUFACTURER a named entity on a real government list, and does the
BUYER'S OWN CATEGORY put them within reach of the restriction that
listing triggers" -- a question about SOURCING TRUST and the BUYER'S
OWN procurement/funding relationship, unrelated to the item's own
technical spec. This build's defining design decision (Decision 4) is
modeling this as a SINGLE HARD check evaluating a CONJUNCTION of TWO
INDEPENDENT facts, a check SHAPE with no precedent anywhere else in
this fleet's wholesale-trading cluster -- see Decision 4 for the full
three-way contrast with the general-trading sibling's single boolean,
the computer/software-wholesale sibling's two sequential dependent
checks, and the waste-wholesale/metal-wholesale siblings' own
fold-two-arms-into-one-rule precedent.

Like every principal-trading sibling, this vertical has NO bespoke
domain capability library in `kotoba-lang` to wrap (verified: no
`kotoba-lang/telecomtrade`-style repo exists). This build therefore uses
self-contained domain logic. The telecom-trading checks (credit-
clearance, contract-on-file, covered-manufacturer/buyer-category,
sanctions-screening) are direct entity/catalog reads in `telecomtrade.
governor`, off dedicated `:credit-cleared?` / `:contract-terms` /
`:manufacturer` / `:buyer-category` / `:sanctions-screened?` facts on
the `telecom-order` record and `telecomtrade.facts`' two catalogs --
NO pure range-check functions are needed.

This blueprint's own `:itonami.blueprint/governor` keyword,
`:telecom-supply-chain-governor`, is grep-verified UNIQUE among the
actor fleet repos checked (no `telecom-supply-chain`/`telecomtrade`
match via GitHub code search across the `cloud-itonami` org at build
time) -- no naming-collision precedent question, a fresh independent
build.

## Decision

### Decision 1: fresh governor identity, no reuse precedent needed

`:telecom-supply-chain-governor` is grep-verified unique via GitHub code
search across the `cloud-itonami` org at build time (no `telecomtrade`/
`telecom-supply-chain-governor`/`electronics-trading-governor` match).
This build follows the SAME governed-actor architecture as every prior
actor, but with its own distinct governor identity.

### Decision 2: self-contained domain logic, direct entity/catalog reads (no `kotoba-lang/telecomtrade` to wrap, and no range-check functions to host)

Like the fuel-wholesale, general-trading, metal-wholesale, textile-
wholesale, waste-wholesale and computer/software-wholesale siblings,
this telecom/electronics-equipment-wholesale vertical needs no range-
check functions: there is no pre-existing telecom-trading capability
library to delegate to, AND the governor's domain checks are direct
entity/catalog reads off the `telecom-order` record's own dedicated
facts and `telecomtrade.facts`' two catalogs -- not measured-value-vs-
limit range comparisons. So `telecomtrade.registry` is RECORD
CONSTRUCTION ONLY, and `telecomtrade.governor` reads the order's
manufacturer/buyer-category directly against the catalogs.

### Decision 3: two-member actuation set, matching the sequential-dual-actuation precedent -- deliberately NOT the three-member shape

This vertical performs exactly TWO real-world actuation events,
SEQUENTIALLY on the same order entity: `:delivery/dispatch` (physical
equipment leaves the wholesaler's control), then `:invoice/settle` (the
money side) -- `#{:delivery/dispatch :invoice/settle}`, matching the
fuel-wholesale, general-trading, metal-wholesale, textile-wholesale and
waste-wholesale siblings' own shape, and DELIBERATELY NOT the computer/
software-wholesale sibling's own three-member shape
(`#{:delivery/dispatch :technology/release :invoice/settle}`).

**Why NOT a third op.** The computer/software-wholesale sibling's third
op, `:technology/release`, exists because that vertical trades software/
source-code/technical-data items for which the controlled event is
sometimes NOT a physical cross-border shipment at all (the deemed-export
doctrine, 15 C.F.R. §734.13). This vertical trades PHYSICAL telecom/
networking hardware exclusively -- routers, switches, base stations,
radios and related electronic parts. Every order in this vertical's
scope leaves the wholesaler's control the same way: a physical
dispatch. There is no analogous non-physical release channel to give a
second op honest content, so adding one would be a hollow structural
mimicry of the computer/software-wholesale sibling rather than a
response to a real distinct act this vertical actually performs.

### Decision 4: `covered-manufacturer-buyer-restricted` -- a CONJUNCTION of two independent facts; the defining design decision of this build, and a fourth check SHAPE in this fleet

This is the decision that most distinguishes this vertical from every
prior sibling, including the computer/software-wholesale sibling. Three
check shapes already exist in this fleet's wholesale-trading cluster:

1. **A single coarse boolean** (`shosha.governor`'s `export-license-
   uncleared-violations`, general trading) -- "has SOME export-control
   process been completed," at jurisdiction-citation resolution.
2. **Two SEQUENTIAL, DEPENDENT checks** (`techtrade.governor`'s
   `eccn-classification-missing-violations` then `license-required-
   unauthorized-violations`, computer/software wholesale) -- the second
   question cannot even be asked until the first has an answer.
3. **Two sub-facts folded into ONE named rule because both are arms of
   the SAME determination** (`wastetrade.governor`'s `prior-informed-
   consent-missing-violations`; the metal-wholesale sibling's own
   conflict-minerals check) -- a filed notification and the
   destination's own consent are both steps of ONE bilateral Basel
   Convention procedure; missing either is equally unsafe because both
   belong to the same act.

This vertical's domain-defining check does not fit ANY of the three.
`covered-manufacturer-buyer-restricted-violations` fires when BOTH,
INDEPENDENTLY:

1. `telecomtrade.facts/covered-manufacturer?` reads the order's own
   `:manufacturer` against a named-entity list (Section 889(f)(3) of the
   NDAA FY2019; the FCC Covered List) -- a fact about the SELLER'S
   SUPPLY CHAIN.
2. `telecomtrade.facts/restricted-buyer-categories` reads the order's
   own `:buyer-category` (`:federal-agency`/`:federal-contractor`/
   `:fcc-usf-funded-carrier`, vs. `:commercial-unrestricted`) -- a fact
   about the BUYER'S OWN procurement/funding relationship to the US
   federal government.

Unlike shape 2, fact 2 is not gated on fact 1 having a particular value
first -- both are askable, and meaningfully true-or-false, at any time,
in either order, entirely independently of the other. Unlike shape 3,
these are not two evidentiary arms of the SAME real-world procedure --
they come from TWO UNRELATED SOURCES (a named-entity list published by
Congress/the FCC vs. a buyer's own procurement/funding status) that
would be true or false regardless of each other. What the law restricts
is specifically their CONJUNCTION: a covered manufacturer's equipment
reaching a buyer whose category the restriction reaches. Four design
options were considered:

- **Option A (rejected): a single coarse `:covered-manufacturer-order?`
  boolean, matching the general-trading sibling's shape**, treating any
  order naming a covered manufacturer as restricted outright regardless
  of buyer. Rejected: this would be a BLANKET manufacturer ban Section
  889 does not actually impose -- Section 889 and the FCC's USF rule are
  federal-procurement/federal-funding-NEXUS statutes, not general trade
  bans; a purely private commercial sale with no federal-agency,
  federal-contract/grant, or FCC-USF-funding nexus is outside every
  regime `covered-manufacturers` cites. Modeling this as a blanket ban
  would over-restrict trade this actor has no regulatory basis to
  block, and would misrepresent the real legal posture in the audit
  ledger -- exactly the kind of dishonesty this fleet's 'never fabricate
  a requirement' discipline exists to prevent, applied here to
  over-restriction rather than under-restriction.
- **Option B (rejected): a single coarse `:restricted-buyer-category?`
  boolean, ignoring manufacturer entirely** -- treating any federal-
  agency/federal-contractor/USF-funded-carrier order as restricted
  regardless of what is being bought. Rejected: equally dishonest in the
  OTHER direction -- Section 889 and the FCC's USF rule name SPECIFIC
  manufacturers; they do not bar a federal agency from buying
  telecommunications equipment from Cisco, Nokia, Ericsson or any other
  non-covered vendor.
- **Option C (rejected): model this as TWO sequential checks, matching
  the computer/software-wholesale sibling's own shape** (e.g.
  "manufacturer-not-yet-screened" then "screened-and-restricted-for-
  buyer"). Rejected: unlike ECCN classification (real expert
  classification WORK that must happen before a license question can
  even be posed), covered-manufacturer status is a simple, always-
  available NAMED-ENTITY LOOKUP -- there is no expert determination step
  that gates when the buyer-category question becomes askable. Modeling
  a lookup as a two-step dependent pipeline would manufacture a
  sequencing this domain does not actually have.
- **Option D (chosen): a SINGLE HARD check evaluating the CONJUNCTION of
  the two independent facts directly** (`(and covered? restricted-
  buyer?)`), proven genuinely conjunctive (not either fact alone) by a
  CONTROL TRIPLE in `telecomtrade.store/demo-data`: `eo-5` (covered
  manufacturer, `:commercial-unrestricted` buyer) dispatches cleanly;
  `eo-7` (non-covered manufacturer, `:federal-agency` buyer) ALSO
  dispatches cleanly; `eo-6` (covered manufacturer AND `:federal-agency`
  buyer, both facts true at once) HOLDS. `eo-9` (a DIFFERENT covered
  manufacturer, `:fcc-usf-funded-carrier` buyer) proves the restriction
  reaches more than one buyer category.
  `test/telecomtrade/governor_contract_test.clj`'s
  `covered-manufacturer-alone-does-not-block-a-commercial-unrestricted-
  buyer`, `restricted-buyer-category-alone-does-not-block-a-non-covered-
  manufacturer`, `covered-manufacturer-and-restricted-buyer-category-
  together-is-held-and-unoverridable`, and `restriction-also-reaches-
  fcc-usf-funded-carrier-not-only-federal-agency` prove all four legs
  end-to-end.

**A later, broader FCC rule, deliberately out of scope.** A November
2022 FCC rule prohibits the FCC from AUTHORIZING any NEW Covered List
equipment for import or sale in the United States AT ALL -- buyer-
BLIND, unlike Section 889/the USF rule. This is real and cited in
`telecomtrade.facts`, but deliberately NOT modeled as a second governor
HARD check in this R0: it is a different regulatory LEVER (per-
equipment-model FCC certification eligibility for the US market as a
whole) than this build's buyer-category-gated per-order check, and
folding it in would blur the very conjunction Decision 4 exists to
demonstrate. Scoped OUT as a follow-up (see README `Business-process
coverage`).

### Decision 5: `telecomtrade.facts` is TWO independent catalogs, not one

`telecomtrade.facts` hosts `catalog` (per-jurisdiction spec-basis, the
SAME shape every sibling's own jurisdiction catalog uses) AND
`covered-manufacturers` (a named-entity list keyed by manufacturer, plus
`restricted-buyer-categories` and `buyer-category-basis`) as separate,
independently-queried structures -- deliberately NOT folded into one
table keyed by jurisdiction. `covered-manufacturer?` and
`restricted-buyer-categories` membership are properties of the ORDER's
own manufacturer and buyer, not properties of "which jurisdiction's
paperwork process applies" -- collapsing them into the generic
`required-evidence` checklist (the way the general-trading sibling's own
checklist stays coarse) would reproduce the SAME information-loss
Decision 4's Options A/B were rejected for, inside a checklist instead
of a boolean.

### Decision 6: dedicated double-actuation-guard booleans

`:dispatched?` / `:invoiced?` are dedicated booleans on the
`telecom-order` record, never a single `:status` value -- the same
discipline every prior governor's guards establish, informed by
`cloud-itonami-isic-6492`'s real status-lifecycle bug
(ADR-2607071320).

### Decision 7: Store protocol, MemStore + DatomicStore parity, with keyword-field round-trip for the load-bearing `:buyer-category`

`telecomtrade.store/Store` is implemented by both `MemStore` (atom-
backed, default for dev/tests/demo) and `DatomicStore`
(`langchain.db`-backed), proven to satisfy the same contract in
`test/telecomtrade/store_contract_test.clj`. Like the computer/software-
wholesale sibling's own DatomicStore (and UNLIKE the fuel-wholesale
sibling's own simpler encoding), this one round-trips KEYWORD-valued
fields (`:equipment-type`, `:buyer-category`, `:status`) through an
EDN-string encoding rather than storing them as bare strings --
necessary because `:buyer-category` is structurally load-bearing (it is
read directly via `contains?` against a set of keywords by
`covered-manufacturer-buyer-restricted-violations`), so losing the
keyword-ness on a Datomic round-trip would be a real parity bug, not
just cosmetic; `store_contract_test.clj`'s `datomic-empty-store-is-
usable` asserts `:equipment-type` AND `:buyer-category` both read back
as keywords specifically.

### Decision 8: Phase 0->3 with `:delivery/dispatch`/`:invoice/settle` NEVER auto

`telecomtrade.phase`'s phase table puts `:order/intake` (no direct
capital or supply-chain-sourcing risk) in phase 3's `:auto` set as its
only member; `:delivery/dispatch` and `:invoice/settle` are deliberately
ABSENT from every phase's `:auto` set, including phase 3 -- a permanent
structural fact. `telecomtrade.governor`'s high-stakes gate enforces the
same invariant independently: two layers agree that actuation is always
a human trading supervisor's call.

### Decision 9: `:robotics true`, a UNIFORM claim -- unlike the computer/software-wholesale sibling's own MIXED, path-specific reasoning

`:itonami.blueprint/robotics` is `true`. Because this vertical trades
PHYSICAL telecom/networking hardware EXCLUSIVELY (Decision 3 -- no
non-physical technology-release path exists here), the robotics claim
is UNIFORM across the vertical's single actuation mechanism, the same
shape the fuel-wholesale and metal-wholesale siblings' own uniform
robotics claims establish, rather than the computer/software-wholesale
sibling's own explicitly MIXED, path-specific reasoning (that sibling
has to reason about `:delivery/dispatch` and `:technology/release`
separately because only one of its two paths is physical). Automated
storage-and-retrieval systems (AS/RS) with ESD-safe
(electrostatic-discharge-safe) handling of router/switch/base-station/
radio cartons are a genuinely load-bearing, well-precedented concern for
electronic/telecom components specifically (unlike bulk commodities),
directly analogous to the computer/software-wholesale sibling's own
AS/RS citation for ITS OWN physical dispatch path. The governor never
dispatches hardware itself: a dispatch-clearing action must have
cleared the same sign-off a human trading supervisor would need.

## Alternatives considered

- **Wrapping a bespoke `kotoba-lang/telecomtrade` capability library.**
  Considered and explicitly ruled out: no such library exists. Forcing a
  false capability-library integration would be dishonest; this build
  correctly uses self-contained domain logic instead.
- **Hosting pure range-check functions in the registry.** Considered and
  ruled out: the telecom-trading domain checks are direct entity/catalog
  reads (credit cleared? contract on file? covered manufacturer?
  restricted buyer category? sanctions screened?), not measured-value-
  vs-limit range comparisons, so there are no range checks to host.
  `telecomtrade.registry` is record construction only.
- **A third actuation op mirroring the computer/software-wholesale
  sibling's `:technology/release`.** Considered and rejected -- see
  Decision 3: this vertical trades physical hardware exclusively, with
  no analogous non-physical release channel to give a third op honest
  content.
- **Collapsing the domain-defining check into a single coarse boolean
  (blanket manufacturer ban, or blanket buyer-category ban), or
  splitting it into two sequential dependent checks matching the
  computer/software-wholesale sibling's shape.** Considered and rejected
  -- see Decision 4's Options A/B/C: each would misrepresent what
  Section 889 and the FCC's rules actually restrict, or manufacture a
  sequencing this domain does not have.
- **Modeling the FCC's November 2022 buyer-blind equipment-authorization
  ban as a second governor HARD check in this R0.** Considered and
  rejected -- see Decision 4's closing note: a different regulatory
  lever (per-equipment-model certification, not per-order procurement)
  that would blur the very conjunction this build exists to demonstrate.
  Correctly scoped OUT as a follow-up.
- **Seeding a third or fourth jurisdiction (e.g. Japan, the EU) to match
  most prior siblings' own 4-jurisdiction R0 catalogs.** Considered and
  rejected in favor of an honestly SMALLER 2-jurisdiction catalog (USA,
  GBR) -- see `docs/business-model.md` `Jurisdiction coverage (honest)`
  for the specific citations this build was and was not confident
  enough in.
- **Building fulfillment routing and trading-book optimization in this
  R0.** Rejected in favor of a scoped R0 slice (the `:optimization`
  capability is correctly marked required, the integration is a
  follow-up), consistent with this fleet's 'extending coverage is
  additive' convention.

## Consequences

- Fresh independent actor in this fleet, following the SAME governed-
  actor architecture as every prior sibling.
- Establishes the fleet's FOURTH domain-defining-check SHAPE
  (conjunction of two independent facts), distinct from the single-
  boolean / two-sequential-dependent-checks / fold-two-arms-into-one-
  rule shapes every prior sibling established -- a template for any
  future vertical whose defining regulatory concern genuinely combines
  two unrelated facts (a named-entity/sourcing fact and a buyer/
  counterparty-category fact) via AND, rather than sequencing or folding
  them.
- Reverts to the two-member sequential-dual-actuation shape (Decision
  3), demonstrating that shape is not obsolete now that a three-member
  sibling exists -- the right shape depends on whether the vertical
  actually has a non-physical release channel, not on precedent alone.
- `MemStore` || `DatomicStore` parity is proven by
  `test/telecomtrade/store_contract_test.clj`, including keyword-field
  round-trip parity (`:equipment-type`, `:buyer-category`) matching the
  computer/software-wholesale sibling's own discipline for load-bearing
  keyword fields.
- Lint is clean; the full test count and demo behavior are recorded in
  README `Run` / `docs/business-model.md` Maturity; the demo
  (`clojure -M:dev:run`) walks one clean dispatch + invoice lifecycle,
  the four-leg control-triple proving the covered-manufacturer/buyer-
  category conjunction, and the remaining HARD-hold scenarios (no
  spec-basis, credit-uncleared, contract-missing, counterparty-
  sanctions-flag-unresolved, double-dispatch, double-invoice),
  end-to-end.
- `blueprint.edn`'s `:robotics true` is a reasoned, uniform call
  documented in README and `docs/business-model.md`.

## References

- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of the
  general governed-actor architecture pattern)
- `cloud-itonami-isic-4671/docs/adr/0001-architecture.md` (fuel-
  wholesale sibling; origin of the two-member sequential-dual-actuation
  shape this build reverts to, and the self-contained-domain-logic
  pattern this build follows)
- `cloud-itonami-isic-4690/docs/adr/0001-architecture.md` (general-
  trading sibling; origin of the single-coarse-boolean domain-check
  shape this build's Decision 4 Options A/B deliberately avoid)
- `cloud-itonami-isic-4651/docs/adr/0001-architecture.md` (computer/
  software-wholesale sibling; this build's closest thematic cousin and
  the sibling whose two-sequential-dependent-checks shape, three-member
  actuation set, and mixed path-specific robotics reasoning this build
  most deliberately differentiates from -- see Decisions 3, 4, 9)
- `cloud-itonami-isic-4669/docs/adr/0001-architecture.md` (waste-
  wholesale sibling; origin of the fold-two-arms-into-one-rule domain-
  check shape this build's Decision 4 Option C-adjacent reasoning
  contrasts with)
- `cloud-itonami-isic-4662/docs/adr/0001-architecture.md` (metal-
  wholesale sibling; a second instance of the fold-two-sub-facts-into-
  one-rule precedent, and origin of the automated crane/stacker-
  reclaimer uniform-robotics citation this build's own uniform-robotics
  reasoning follows)
- Section 889 of the John S. McCain National Defense Authorization Act
  for Fiscal Year 2019 (Pub. L. 115-232), Parts A and B, §889(f)(3)
  (named covered entities); FAR clause 52.204-25 (USA, U.S. Congress /
  Federal Acquisition Regulatory Council)
- Secure and Trusted Communications Networks Act of 2019 (Pub. L.
  116-124) -- FCC Covered List; FCC Report and Order, In re Protecting
  Against National Security Threats to the Communications Supply Chain
  (Nov. 2019, USF rule); FCC Order, Protecting Against National Security
  Threats to the Communications Networks or the Information and
  Communications Technology Supply Chain through the Equipment
  Authorization Program (Nov. 2022) (USA, Federal Communications
  Commission)
- Telecommunications (Security) Act 2021; designated vendor directions
  (UK, Department for Science, Innovation and Technology; Office of
  Communications (Ofcom))
- OFAC sanctions programs (31 C.F.R. Chapter V) (US, Treasury) -- cited
  for the generic `counterparty-sanctions-flag-unresolved` check, the
  SAME mechanism every sibling in this fleet re-verifies
