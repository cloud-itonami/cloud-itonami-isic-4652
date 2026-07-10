# Contributing

`cloud-itonami-isic-4652` accepts contributions to the OSS blueprint, the
Telecom Supply-Chain Governor, decision-rule tests, documentation and
operator model.

## Development
The capability layer is SELF-CONTAINED. There is no pre-existing bespoke
telecom/electronics-equipment-wholesale capability library to wrap; the
counterparty-credit / contract-on-file / covered-manufacturer / buyer-
category / sanctions-screening checks live directly in
`telecomtrade.governor`. This repo holds the business blueprint, the
langgraph-clj actor and the operator contracts.

```bash
clojure -M:dev:test
clojure -M:lint
```

## Rules
- Do not commit real counterparty, credit, manufacturer/sourcing, buyer-
  category, or sanctions-screening data.
- Keep equipment dispatch and invoice settlement behind the Telecom
  Supply-Chain Governor.
- Treat supply-chain-sourcing workflows as high-risk: add tests for
  spec-basis, evidence completeness, credit clearance, contract-on-file,
  covered-manufacturer/buyer-category status, sanctions screening and
  audit logging.
- Keep `covered-manufacturer-buyer-restricted` a CONJUNCTION of the two
  independent facts (`telecomtrade.facts/covered-manufacturer?` and
  `telecomtrade.facts/restricted-buyer-categories`) -- do not collapse
  it into a blanket manufacturer ban or a blanket buyer-category ban
  (see `docs/adr/0001-architecture.md` Decision 4 for why, and the
  control-triple tests in `test/telecomtrade/governor_contract_test.clj`
  that would break if either side alone started triggering the hold).
- Never fabricate a jurisdiction's telecom-supply-chain-sourcing
  requirements in `telecomtrade.facts/catalog`, or a manufacturer's
  covered-list membership in `telecomtrade.facts/covered-manufacturers`
  -- cite a real official source or leave the entry out.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which governor invariant is
affected, how it was tested, whether operator or certification docs need
updates.
