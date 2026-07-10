# cloud-itonami-isic-4652

Open Business Blueprint for **ISIC Rev.5 4652**: Wholesale of Electronic
and Telecommunications Equipment and Parts -- telecom/electronics
equipment-order intake, per-jurisdiction counterparty-diligence /
covered-manufacturer / sanctions regulatory verification, physical
equipment dispatch, and invoice settlement for a telecom/networking-
equipment wholesaler.

This repository publishes a telecom/electronics-equipment-wholesale
actor -- equipment-order intake, per-jurisdiction contract /
covered-manufacturer / sanctions regulatory verification, physical
dispatch, and invoice settlement -- as an OSS business that any
qualified operator can fork, deploy, run, improve and sell, so a
regional telecom/networking-equipment wholesaler never surrenders
counterparty, credit, sourcing and sanctions-screening data to a closed
trade-compliance / ERP SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **TelecomTradeAdvisor ⊣
Telecom Supply-Chain Governor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:telecom-supply-chain-governor`,
is a UNIQUE keyword fleet-wide (grep-verified: no other blueprint
declares it) -- a fresh, independent build.

**Like the fuel-wholesale / general-trading / metal-wholesale /
textile-wholesale / waste-wholesale / computer-and-software-wholesale
siblings, this vertical is SELF-CONTAINED**: there is no
`kotoba-lang/telecomtrade` to delegate supply-chain-sourcing validation
to, so the credit-clearance / contract-on-file / covered-manufacturer /
buyer-category / sanctions-screening checks live as direct entity/
catalog reads in `telecomtrade.governor` (off dedicated facts on the
`telecom-order` record and `telecomtrade.facts`' two catalogs), rather
than wrapping an external capability library's own validated function.

> **Why an actor layer at all?** An LLM is great at drafting an order
> summary, normalizing records, and reading a credit file -- but it
> has **no notion of which jurisdiction's telecom-supply-chain-sourcing
> regime is official, no license to dispatch real equipment or settle a
> real invoice, and no way to know on its own whether an item's
> manufacturer is actually a named entity on a real covered-list,
> whether a buyer's own category actually puts them within reach of the
> restriction that listing triggers, or whether OFAC / equivalent
> sanctions screening has actually been passed**. Letting it dispatch
> equipment or settle an invoice directly invites fabricated regulatory
> citations, covered-manufacturer equipment reaching a restricted buyer,
> and an invoice settling against a sanctioned party -- exposing the
> operator to real enforcement and financial liability, for whoever runs
> it. This project seals the TelecomTradeAdvisor into a single node and
> wraps it with an independent **Telecom Supply-Chain Governor**, a
> human **approval workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers telecom/electronics equipment-order intake through
covered-manufacturer / sanctions regulatory verification, physical
dispatch, and invoice settlement. It does **not**, by itself, hold any
operating authority required to run a telecom/electronics-equipment-
wholesale business in a given jurisdiction, and it does not claim to. It
also does not perform the actual physical warehouse pick/pack or route
optimization itself, or judge trading-book economics -- fulfillment/
route optimization (the blueprint's own `:optimization` technology) is a
follow-up slice, not in this R0. Whoever deploys and operates a live
instance (a qualified trading supervisor / procurement-compliance
officer) supplies any jurisdiction-specific operating authority, the
real warehouse/ERP dispatch integration, and the real ERP / accounts-
receivable integrations, and bears that jurisdiction's liability -- the
software supplies the governed, spec-cited, audited execution scaffold
so that operator does not have to build the compliance layer from
scratch.

### Actuation

**Physically dispatching real telecom/networking equipment and settling
a real invoice are never autonomous, at any phase, by construction.**
Two independent layers enforce this (`telecomtrade.governor`'s
`:delivery/dispatch`/`:invoice/settle` high-stakes gate and
`telecomtrade.phase`'s phase table, which never puts either op in any
phase's `:auto` set) -- see `telecomtrade.phase`'s docstring and
`test/telecomtrade/phase_test.clj`'s
`delivery-dispatch-never-auto-at-any-phase`/
`invoice-settle-never-auto-at-any-phase`. The actor may draft, check and
recommend; a human trading supervisor is always the one who actually
dispatches equipment or settles an invoice. A two-member actuation shape
(`#{:delivery/dispatch :invoice/settle}`), matching every dual-
actuation sibling's own shape (unlike the computer/software-wholesale
sibling's own three-member shape -- this vertical trades PHYSICAL
telecom/networking hardware exclusively, with no analogous
non-physical technology-release path).

## The core contract

```
telecom-order intake + jurisdiction facts (telecomtrade.facts, spec-cited)
        |
        v
   ┌───────────────────────┐   proposal      ┌─────────────────────────┐
   │ TelecomTradeAdvisor    │ ─────────────▶ │ Telecom Supply-Chain     │  (independent system)
   │ (sealed)               │  + citations    │ Governor                 │
   └───────────────────────┘                 │ spec-basis · evidence-  │
          │                 commit ◀┼ incomplete · credit-           │
          │                         │ uncleared · contract-missing ·  │
    record + ledger        escalate ┼ covered-manufacturer-buyer-     │
          │              (ALWAYS for│ restricted · counterparty-      │
          │       :delivery/        │ sanctions-flag-unresolved ·     │
          │       dispatch/         │ already-dispatched/invoiced     │
          │       :invoice/         └─────────────────────────┘
          │       settle)
          ▼
      human approval
```

**The TelecomTradeAdvisor never dispatches equipment or settles an
invoice the Telecom Supply-Chain Governor would reject, and never does
so without a human sign-off.** Hard violations (fabricated regulatory
requirements; unsupported evidence; an uncleared counterparty credit; no
contract-terms on file; a covered-manufacturer order whose buyer
category is one the restriction reaches; an unresolved sanctions-
screening flag; a double dispatch/invoice) force **hold** and *cannot*
be approved past; a clean dispatch/invoice proposal still always routes
to a human.

## Run

```bash
clojure -M:dev:run     # walk clean orders + the control triple that proves the covered-manufacturer/buyer-category conjunction, plus HARD-hold cases, through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

`blueprint.edn` sets `:itonami.blueprint/robotics true`: an automated
storage-and-retrieval system (AS/RS) / goods-to-person robotic shuttle
picks and stages ESD-safe (electrostatic-discharge-safe) router/switch/
base-station/radio cartons at the wholesale distribution center for
`:delivery/dispatch`, under the actor, gated by the independent
**Telecom Supply-Chain Governor**. The governor never dispatches
hardware itself: a dispatch-clearing action must have cleared the same
sign-off a human trading supervisor would need -- a robot may stage a
carton at the dock, but only after the governor and a human supervisor
both agree it is safe to. Unlike the computer/software-wholesale
sibling's own MIXED, path-specific robotics reasoning (that vertical
splits across a physical dispatch path and a non-physical technology-
release path), this vertical trades PHYSICAL hardware exclusively, so
`:robotics true` is a UNIFORM claim across its single actuation
mechanism. See `docs/business-model.md` Robotics Premise for the full
reasoning.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Telecom Supply-Chain Governor, dispatch/invoice draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`4652`). Like the fuel-wholesale / general-trading / metal-wholesale /
textile-wholesale / waste-wholesale / computer-and-software-wholesale
siblings, this vertical is NOT backed by a separate bespoke domain
capability lib: the telecom-trading checks (credit-clearance, contract-
on-file, covered-manufacturer/buyer-category, sanctions-screening) are
direct entity/catalog reads in `telecomtrade.governor`, on top of the
generic robotics/identity/forms/dmn/bpmn/audit-ledger stack.

## Layout

| File | Role |
|---|---|
| `src/telecomtrade/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + dispatch AND invoice history. The double-actuation guards check dedicated `:dispatched?`/`:invoiced?` booleans rather than a `:status` value |
| `src/telecomtrade/registry.cljc` | Dispatch/invoice draft records (record construction only -- the Telecom Supply-Chain Governor's checks are direct entity/catalog reads, so there are no pure range-check functions to host here) |
| `src/telecomtrade/facts.cljc` | Per-jurisdiction spec-basis catalog PLUS the `covered-manufacturers` named-entity list and `restricted-buyer-categories` set -- the TWO independent catalogs this vertical's domain-defining check ANDs together |
| `src/telecomtrade/telecomtradeadvisor.cljc` | **TelecomTradeAdvisor** -- `mock-advisor` ‖ `llm-advisor`; intake/sourcing-verification/dispatch/invoice proposals |
| `src/telecomtrade/governor.cljc` | **Telecom Supply-Chain Governor** -- 6 HARD checks (spec-basis · evidence-incomplete · credit-uncleared · contract-missing · covered-manufacturer-buyer-restricted · counterparty-sanctions-flag-unresolved) + 2 double-actuation guards + 1 soft (confidence/actuation gate) |
| `src/telecomtrade/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted verify → supervised (dispatch/invoice always human; order intake is the ONLY auto-eligible op) |
| `src/telecomtrade/operation.cljc` | **OperationActor** -- langgraph StateGraph |
| `src/telecomtrade/sim.cljc` | demo driver |
| `test/telecomtrade/*_test.clj` | governor contract (including the control triple proving the covered-manufacturer/buyer-category conjunction) · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers telecom/electronics equipment-order intake through
covered-manufacturer / sanctions regulatory verification, physical
dispatch, and invoice settlement -- the core governed lifecycle:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Telecom/electronics equipment-order intake + per-jurisdiction evidence checklisting, HARD-gated on an official spec-basis citation (`:order/intake`/`:sourcing/verify`) | Real warehouse-management/ERP integration, fulfillment routing and trading-book economics |
| Physical dispatch, HARD-gated on full evidence, a credit-cleared counterparty, contract-terms on file, the covered-manufacturer/buyer-category conjunction, passed sanctions screening, and no double-dispatch (`:delivery/dispatch`) | The FCC's broader November 2022 buyer-blind equipment-authorization ban on NEW Covered List equipment entering the US market at all -- a different regulatory lever (per-equipment-model certification, not per-order procurement) than this build's buyer-category-gated check; see `docs/business-model.md` |
| Invoice settlement, HARD-gated on full evidence, passed sanctions screening, and no double-invoice (`:invoice/settle`) | |
| Immutable audit ledger for every intake/verification/dispatch/invoice decision | |

Extending coverage is additive: add the next gate (e.g. a re-export
screening check, or the FCC's own equipment-authorization bar as its own
op) as its own governed op with its own HARD checks and tests, following
the SAME "an independent governor re-verifies against the actor's own
records before any real-world act" pattern this repo's flagship ops
already establish.

## Jurisdiction coverage (honest)

`telecomtrade.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `telecomtrade.facts/catalog` --
currently 2 seeded (USA, GBR) out of ~194 jurisdictions worldwide, a
deliberately smaller starting catalog than most prior siblings' own
4-jurisdiction R0s because this specific regulatory topic (named-
manufacturer telecom-supply-chain-sourcing restrictions) is unusually
US-centric among currently well-documented regimes -- see
`docs/business-model.md` `Jurisdiction coverage (honest)` for the full
reasoning, including two jurisdictions (Japan, the EU) that were
seriously considered and deliberately EXCLUDED for lack of confidence
in precise citations, rather than seeded to hit a quota. Adding a
jurisdiction is additive: one map entry in `telecomtrade.facts/catalog`,
citing a real official source -- never fabricate a jurisdiction's
requirements to make coverage look bigger.

## Maturity

`:implemented` -- `TelecomTradeAdvisor` + `Telecom Supply-Chain
Governor` run as real, tested code (see `Run` above), following the SAME
governed-actor architecture as the other prior actors across this
fleet, with its own distinct, independently-named governor and its own
conjunctive covered-manufacturer/buyer-category check (no analog in any
sibling). See `docs/adr/0001-architecture.md` for the history and
design.

## License

Code and implementation templates are AGPL-3.0-or-later.
