(ns telecomtrade.governor
  "Telecom Supply-Chain Governor -- the independent compliance layer that
  earns the TelecomTradeAdvisor the right to commit. The LLM has no
  notion of jurisdictional telecom-supply-chain-sourcing law, whether a
  counterparty's credit has actually been cleared, whether contract
  terms are actually on file, whether OFAC / equivalent sanctions
  screening has actually been passed, whether an item's own
  MANUFACTURER is actually a named entity on a real covered-list, and
  independently whether the BUYER'S OWN CATEGORY actually puts them
  within reach of the restriction that listing triggers, or when an act
  stops being a draft and becomes a real equipment dispatch or a real
  invoice settlement, so this MUST be a separate system able to *reject*
  a proposal and fall back to HOLD.

  Like every principal-trading sibling's own governor, this telecom/
  electronics-equipment-wholesale vertical has NO pre-existing telecom-
  trading capability library to delegate to -- so the domain checks
  (credit-clearance, contract-on-file, covered-manufacturer/buyer-
  category, sanctions-screening) are direct entity/catalog reads,
  evaluated directly here, NOT delegated to a separate library's
  validated function.

  `:itonami.blueprint/governor` is `:telecom-supply-chain-governor`,
  grep-verified UNIQUE fleet-wide -- no naming-collision precedent
  question, a fresh independent build following the SAME governed-actor
  architecture (langgraph StateGraph + independent Governor + Phase
  0->3 rollout) established by `cloud-itonami-isic-6511` and applied by
  every wholesale-trading sibling in this cluster.

  CRITICAL STRUCTURAL DIFFERENCE from `cloud-itonami-isic-4651`
  (`techtrade.governor`, wholesale of computers/peripherals/software --
  this build's closest thematic cousin, dual-use/encryption EXPORT-
  CONTROL classification): `techtrade.governor`'s domain-defining
  question is 'what is this item's own TECHNICAL CLASSIFICATION, and
  does THAT classification require a license for this DESTINATION' --
  answered by TWO SEQUENTIAL, DEPENDENT checks
  (`eccn-classification-missing` then `license-required-unauthorized`;
  the second is a no-op unless the first already passed). THIS
  vertical's domain-defining question is different IN KIND, not just in
  degree: 'is this item's MANUFACTURER a named entity on a real
  government list, and does the BUYER'S OWN CATEGORY put them within
  reach of the restriction that listing triggers' -- a supply-chain-
  trust / national-security-SOURCING question that has nothing to do
  with the item's own technical specification (an EAR99-equivalent
  router and a hypothetically export-controlled router are EQUALLY
  'covered' if Huawei made either one; an identical router made by Cisco
  or Nokia is NEVER 'covered' no matter its own spec). `covered-
  manufacturer-buyer-restricted-violations` below is therefore modeled
  as a SINGLE HARD check evaluating a CONJUNCTION of TWO INDEPENDENT
  facts (`telecomtrade.facts/covered-manufacturer?` reads the order's
  own `:manufacturer`; `telecomtrade.facts/restricted-buyer-categories`
  reads the order's own `:buyer-category`) -- NOT a sequential
  classify-then-license pipeline where the second question cannot even
  be asked until the first has an answer. Both facts here are equally
  askable, at any time, independently of the other; what makes the
  order restricted is that BOTH happen to be true AT ONCE. This is also
  structurally distinct from the waste-wholesale/metal-wholesale/
  textile-wholesale siblings' own 'fold two sub-facts into one named
  rule' precedent (`cloud-itonami-isic-4669`'s `prior-informed-consent-
  missing`, `cloud-itonami-isic-4662`'s conflict-minerals-provenance
  check): those checks fold TWO EVIDENTIARY ARMS OF THE SAME REAL-WORLD
  DETERMINATION (a filed notification + the destination's own consent
  are both steps of ONE Prior Informed Consent procedure) -- a missing
  EITHER one is EQUALLY unsafe, because both arms belong to the same
  underlying act. Here, `covered-manufacturer?` and a restricted
  `:buyer-category` are NOT two arms of one determination -- they come
  from TWO UNRELATED SOURCES (a named-entity list vs. a buyer's own
  procurement/funding relationship) that would be independently true or
  false regardless of the other, and it is specifically their
  CONJUNCTION, not either one alone, that the law restricts. See
  `docs/adr/0001-architecture.md` Decision 4 for the full three-way
  contrast (single boolean / two sequential dependent checks / two-arms-
  fold-into-one / two-independent-facts-conjunction) and
  `test/telecomtrade/governor_contract_test.clj`'s control TRIPLE
  (`eo-5`/`eo-6`/`eo-7`) proving neither fact alone triggers the hold.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them. The confidence/actuation gate is SOFT: it asks
  a human to look (low confidence / actuation), and the human may
  approve -- but see `telecomtrade.phase`: for `:stake :delivery/
  dispatch`/`:invoice/settle` (a real dispatch or invoice settlement) NO
  phase ever allows auto-commit either. Two independent layers agree
  that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source
                                       (`telecomtrade.facts`), or invent
                                       one?
    2. Evidence incomplete         -- for `:delivery/dispatch`/
                                       `:invoice/settle`, has the
                                       jurisdiction actually been
                                       verified with a full counterparty-
                                       diligence evidence checklist on
                                       file?
    3. Credit uncleared            -- for `:delivery/dispatch`, the
                                       counterparty's credit has NOT been
                                       cleared (the leasing collateral-
                                       coverage discipline, applied to
                                       counterparty credit). Evaluated
                                       before the equipment leaves.
    4. Contract missing            -- for `:delivery/dispatch`, no
                                       contract-terms are on file for the
                                       order. Evaluated before the
                                       equipment leaves.
    5. Covered-manufacturer /
       buyer-category restricted   -- for `:delivery/dispatch`, WHEN the
                                       order's `:manufacturer` IS a named
                                       entity on `telecomtrade.facts/
                                       covered-manufacturers` AND the
                                       order's `:buyer-category` IS one
                                       of `telecomtrade.facts/restricted-
                                       buyer-categories` (`:federal-
                                       agency`/`:federal-contractor`/
                                       `:fcc-usf-funded-carrier`) --
                                       BOTH facts, independently true.
                                       THIS is the domain-defining check
                                       -- see namespace docstring above.
                                       A `:commercial-unrestricted` buyer
                                       is NEVER blocked by this check,
                                       even for a covered manufacturer's
                                       equipment (Section 889 does not
                                       reach a purely private commercial
                                       sale); a non-covered
                                       manufacturer's equipment is NEVER
                                       blocked by this check, even for a
                                       federal-agency buyer.
    6. Counterparty sanctions flag
       unresolved                  -- for `:delivery/dispatch` and
                                       `:invoice/settle`, the counterparty
                                       has NOT passed OFAC / equivalent
                                       sanctions screening -- a HARD,
                                       un-overridable hold. Evaluated
                                       UNCONDITIONALLY at both actuation
                                       ops.

  Two more guards, double-dispatch/double-invoice prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-dispatched-violations`/
  `already-invoiced-violations` refuse to dispatch/invoice the SAME
  telecom-order twice, off dedicated `:dispatched?`/`:invoiced?` facts
  (never a `:status` value) -- the SAME 'check a dedicated boolean, not
  status' discipline every prior governor's guards establish, informed
  by `cloud-itonami-isic-6492`'s status-lifecycle bug
  (ADR-2607071320)."
  (:require [telecomtrade.facts :as facts]
            [telecomtrade.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Dispatching real telecom/networking equipment to a counterparty and
  settling a real invoice (real money moving between counterparty and
  wholesaler) are the two real-world actuation events this actor
  performs -- a two-member set, matching every dual-actuation sibling's
  own shape (unlike the dual-use/export-classification sibling's own
  three-member shape -- see namespace docstring)."
  #{:delivery/dispatch :invoice/settle})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:sourcing/verify` (or `:delivery/dispatch`/`:invoice/settle`)
  proposal with no spec-basis citation is a HARD violation -- never
  invent a jurisdiction's telecom-supply-chain-sourcing requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:sourcing/verify :delivery/dispatch :invoice/settle} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:delivery/dispatch`/`:invoice/settle`, the jurisdiction's
  required GENERIC counterparty-diligence evidence (credit-clearance
  record, contract/PO, sanctions-screening record) must actually be
  satisfied -- do not trust the advisor's self-reported confidence
  alone. Deliberately does NOT check covered-manufacturer/buyer-category
  status -- that is `covered-manufacturer-buyer-restricted-violations`
  below, its OWN dedicated check rather than a checklist item (see
  namespace docstring)."
  [{:keys [op subject]} st]
  (when (contains? #{:delivery/dispatch :invoice/settle} op)
    (let [eo (store/telecom-order st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction eo) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(信用審査記録/契約書またはPO/制裁スクリーニング記録)が充足していない状態での提案"}]))))

(defn- credit-uncleared-violations
  "For `:delivery/dispatch`, refuses to dispatch real telecom/networking
  equipment to a counterparty whose credit has NOT been cleared --
  counterparty credit not cleared (the leasing collateral-coverage
  discipline, applied to counterparty credit). Evaluated ahead of any
  physical handoff."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (let [eo (store/telecom-order st subject)]
      (when (not (true? (:credit-cleared? eo)))
        [{:rule :credit-uncleared
          :detail (str subject " の取引先信用審査(credit-clearance)が未了 -- 出荷提案は進められない")}]))))

(defn- contract-missing-violations
  "For `:delivery/dispatch`, refuses to dispatch real telecom/networking
  equipment when no contract-terms are on file for the order."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (let [eo (store/telecom-order st subject)]
      (when (or (nil? (:contract-terms eo)) (= "" (:contract-terms eo)))
        [{:rule :contract-missing
          :detail (str subject " に契約条項(contract-terms)の記録が無い -- 出荷提案は進められない")}]))))

(defn- covered-manufacturer-buyer-restricted-violations
  "For `:delivery/dispatch`, refuses to dispatch WHEN BOTH, independently:
  (a) the order's `:manufacturer` IS a named entity on
  `telecomtrade.facts/covered-manufacturers` (Section 889(f)(3), NDAA
  FY2019; the FCC Covered List), AND (b) the order's `:buyer-category`
  IS one of `telecomtrade.facts/restricted-buyer-categories`
  (`:federal-agency`/`:federal-contractor`/`:fcc-usf-funded-carrier`).
  THIS IS THE DOMAIN-DEFINING CHECK -- see namespace docstring for the
  full contrast with every prior sibling's own domain-defining check
  SHAPE.

  Neither fact alone fires this check -- this is a CONJUNCTION, proven
  by a control TRIPLE in `telecomtrade.store/demo-data`:
  `eo-5` (Huawei Technologies Company, `:commercial-unrestricted` buyer)
  dispatches CLEANLY -- Section 889 does not reach a purely private
  commercial sale with no federal-agency/federal-contract/FCC-USF-
  funding nexus, even for a covered manufacturer's own equipment.
  `eo-7` (Nokia Corporation, a NON-covered manufacturer, `:federal-
  agency` buyer) ALSO dispatches CLEANLY -- a federal agency is free to
  buy a non-covered manufacturer's equipment; `:federal-agency` alone
  triggers nothing. `eo-6` (Huawei Technologies Company, `:federal-
  agency` buyer -- BOTH facts true at once) HOLDS. `eo-9` (ZTE
  Corporation, `:fcc-usf-funded-carrier` buyer) proves the restriction
  also reaches the USF-funding-nexus category, not merely
  `:federal-agency`.
  `test/telecomtrade/governor_contract_test.clj`'s
  `covered-manufacturer-alone-does-not-block-a-commercial-unrestricted-
  buyer`, `restricted-buyer-category-alone-does-not-block-a-non-covered-
  manufacturer`, and `covered-manufacturer-and-restricted-buyer-
  category-together-is-held-and-unoverridable` prove all three legs of
  this control triple end-to-end."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (let [eo (store/telecom-order st subject)
          covered? (facts/covered-manufacturer? (:manufacturer eo))
          restricted-buyer? (contains? facts/restricted-buyer-categories (:buyer-category eo))]
      (when (and covered? restricted-buyer?)
        [{:rule :covered-manufacturer-buyer-restricted
          :detail (str subject " (メーカー=" (:manufacturer eo)
                       " はcovered-listに掲載, 買主区分=" (name (:buyer-category eo))
                       " は制限対象区分) -- covered-listメーカー機器の"
                       "当該買主区分への出荷は制限されている")}]))))

(defn- counterparty-sanctions-flag-unresolved-violations
  "For `:delivery/dispatch` and `:invoice/settle`, an unresolved
  sanctions-screening flag -- the counterparty has NOT passed OFAC /
  equivalent sanctions screening -- is a HARD, un-overridable hold.
  Evaluated UNCONDITIONALLY at both actuation ops: neither equipment nor
  money moves against an unscreened counterparty."
  [{:keys [op subject]} st]
  (when (contains? #{:delivery/dispatch :invoice/settle} op)
    (let [eo (store/telecom-order st subject)]
      (when (not (true? (:sanctions-screened? eo)))
        [{:rule :counterparty-sanctions-flag-unresolved
          :detail (str subject " の取引先制裁スクリーニング(OFAC等)が未了 -- 出荷・請求提案は進められない")}]))))

(defn- already-dispatched-violations
  "For `:delivery/dispatch`, refuses to dispatch the SAME telecom-order
  twice, off a dedicated `:dispatched?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (when (store/telecom-order-already-dispatched? st subject)
      [{:rule :already-dispatched
        :detail (str subject " は既に出荷済み")}])))

(defn- already-invoiced-violations
  "For `:invoice/settle`, refuses to settle the SAME telecom-order's
  invoice twice, off a dedicated `:invoiced?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :invoice/settle)
    (when (store/telecom-order-already-invoiced? st subject)
      [{:rule :already-invoiced
        :detail (str subject " は既に請求済み")}])))

(defn check
  "Censors a TelecomTradeAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (credit-uncleared-violations request st)
                           (contract-missing-violations request st)
                           (covered-manufacturer-buyer-restricted-violations request st)
                           (counterparty-sanctions-flag-unresolved-violations request st)
                           (already-dispatched-violations request st)
                           (already-invoiced-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
