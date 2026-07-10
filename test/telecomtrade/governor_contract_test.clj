(ns telecomtrade.governor-contract-test
  "The governor contract as executable tests. The single invariant under
  test:

    TelecomTradeAdvisor never dispatches telecom/networking equipment to
    a counterparty or settles an invoice the Telecom Supply-Chain
    Governor would reject, `:delivery/dispatch`/`:invoice/settle` NEVER
    auto-commit at any phase, `:order/intake` (no direct capital risk)
    MAY auto-commit when clean, and every decision (commit OR hold)
    leaves exactly one ledger fact.

  This file ALSO proves the fleet-differentiating claim from
  `telecomtrade.governor`'s namespace docstring end-to-end: `covered-
  manufacturer-buyer-restricted` is a genuine CONJUNCTION of two
  INDEPENDENT facts (manufacturer-covered-list status × buyer-category),
  not a sequential classify-then-license pipeline (contrast
  `cloud-itonami-isic-4651`) and not a fold-two-arms-of-one-determination
  rule (contrast `cloud-itonami-isic-4669`/`cloud-itonami-isic-4662`) --
  the CONTROL TRIPLE below (`eo-5`/`eo-6`/`eo-7`) proves neither fact
  alone triggers the hold, and `eo-9` proves the restriction reaches a
  SECOND restricted buyer category, not just `:federal-agency`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [telecomtrade.facts :as facts]
            [telecomtrade.store :as store]
            [telecomtrade.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :trading-supervisor :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify!
  "Walks `subject` through sourcing verify -> approve, leaving a
  sourcing assessment on file. Uses distinct thread-ids per call site by
  suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :sourcing/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :order/intake :subject "eo-1"
                   :patch {:id "eo-1" :counterparty "Northbridge Network Integrators LLC"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Northbridge Network Integrators LLC" (:counterparty (store/telecom-order db "eo-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest sourcing-verify-always-needs-approval
  (testing "sourcing verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :sourcing/verify :subject "eo-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "eo-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a sourcing/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :sourcing/verify :subject "eo-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "eo-2")) "no assessment written"))))

(deftest dispatch-without-assessment-is-held
  (testing "delivery/dispatch before any sourcing verification -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :delivery/dispatch :subject "eo-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest credit-uncleared-is-held-and-unoverridable
  (testing "a counterparty whose credit has not been cleared -> HOLD, and never reaches request-approval -- the leasing collateral-coverage discipline applied to counterparty credit"
    (let [[db actor] (fresh)
          _ (verify! actor "t5pre" "eo-3")
          res (exec-op actor "t5" {:op :delivery/dispatch :subject "eo-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:credit-uncleared} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest contract-missing-is-held-and-unoverridable
  (testing "an order with no contract-terms on file -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          _ (verify! actor "t6pre" "eo-4")
          res (exec-op actor "t6" {:op :delivery/dispatch :subject "eo-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:contract-missing} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

;; ----------------------------- the control triple -----------------------------

(deftest covered-manufacturer-alone-does-not-block-a-commercial-unrestricted-buyer
  (testing "eo-5: Huawei Technologies Company (covered) + :commercial-unrestricted buyer -> dispatches CLEANLY (governor never holds; still always escalates for human approval, like any clean :delivery/dispatch)"
    (let [[db actor] (fresh)
          eo (store/telecom-order db "eo-5")]
      (is (true? (facts/covered-manufacturer? (:manufacturer eo))))
      (is (not (contains? facts/restricted-buyer-categories (:buyer-category eo))))
      (let [_ (verify! actor "t7pre" "eo-5")
            res (exec-op actor "t7" {:op :delivery/dispatch :subject "eo-5"} operator)]
        (is (= :interrupted (:status res)) "governor did NOT hold -- escalates for approval like any clean dispatch")
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:dispatched? (store/telecom-order db "eo-5")))))))))

(deftest restricted-buyer-category-alone-does-not-block-a-non-covered-manufacturer
  (testing "eo-7: Nokia Corporation (NOT covered) + :federal-agency buyer -> dispatches CLEANLY (governor never holds)"
    (let [[db actor] (fresh)
          eo (store/telecom-order db "eo-7")]
      (is (false? (facts/covered-manufacturer? (:manufacturer eo))))
      (is (contains? facts/restricted-buyer-categories (:buyer-category eo)))
      (let [_ (verify! actor "t8pre" "eo-7")
            res (exec-op actor "t8" {:op :delivery/dispatch :subject "eo-7"} operator)]
        (is (= :interrupted (:status res)) "governor did NOT hold -- escalates for approval like any clean dispatch")
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:dispatched? (store/telecom-order db "eo-7")))))))))

(deftest covered-manufacturer-and-restricted-buyer-category-together-is-held-and-unoverridable
  (testing "eo-6: Huawei Technologies Company (covered) + :federal-agency buyer -- BOTH facts true at once -> HARD hold :covered-manufacturer-buyer-restricted, never reaches request-approval"
    (let [[db actor] (fresh)
          eo (store/telecom-order db "eo-6")]
      (is (true? (facts/covered-manufacturer? (:manufacturer eo))))
      (is (contains? facts/restricted-buyer-categories (:buyer-category eo)))
      (let [_ (verify! actor "t9pre" "eo-6")
            res (exec-op actor "t9" {:op :delivery/dispatch :subject "eo-6"} operator)]
        (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
        (is (not= :interrupted (:status res)))
        (is (some #{:covered-manufacturer-buyer-restricted} (-> (store/ledger db) last :basis)))
        (is (empty? (store/dispatch-history db)))))))

(deftest restriction-also-reaches-fcc-usf-funded-carrier-not-only-federal-agency
  (testing "eo-9: ZTE Corporation (covered) + :fcc-usf-funded-carrier buyer (GBR jurisdiction) -> ALSO a HARD hold :covered-manufacturer-buyer-restricted -- the restriction is not federal-agency-only"
    (let [[db actor] (fresh)
          eo (store/telecom-order db "eo-9")]
      (is (true? (facts/covered-manufacturer? (:manufacturer eo))))
      (is (= :fcc-usf-funded-carrier (:buyer-category eo)))
      (is (contains? facts/restricted-buyer-categories (:buyer-category eo)))
      (let [_ (verify! actor "t10pre" "eo-9")
            res (exec-op actor "t10" {:op :delivery/dispatch :subject "eo-9"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:covered-manufacturer-buyer-restricted} (-> (store/ledger db) last :basis)))
        (is (empty? (store/dispatch-history db)))))))

;; ----------------------------- remaining checks -----------------------------

(deftest counterparty-sanctions-flag-unresolved-is-held-and-unoverridable
  (testing "a counterparty that has not passed OFAC / equivalent sanctions screening -> HOLD, and never reaches request-approval (evaluated at both dispatch and invoice)"
    (let [[db actor] (fresh)
          _ (verify! actor "t11pre" "eo-8")
          res (exec-op actor "t11" {:op :delivery/dispatch :subject "eo-8"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:counterparty-sanctions-flag-unresolved} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest delivery-dispatch-always-escalates-then-human-decides
  (testing "a clean, fully-verified, credit-cleared, contract-on-file, sanctions-screened, non-restricted order still ALWAYS interrupts for human approval -- :delivery/dispatch is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t12pre" "eo-1")
          r1 (exec-op actor "t12" {:op :delivery/dispatch :subject "eo-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, dispatch record drafted"
        (let [r2 (approve! actor "t12")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:dispatched? (store/telecom-order db "eo-1"))))
          (is (= 1 (count (store/dispatch-history db))) "one draft dispatch record"))))))

(deftest invoice-settle-always-escalates-then-human-decides
  (testing "a clean, already-dispatched order still ALWAYS interrupts for human approval -- :invoice/settle is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t13pre" "eo-1")
          _ (exec-op actor "t13dispatch" {:op :delivery/dispatch :subject "eo-1"} operator)
          _ (approve! actor "t13dispatch")
          r1 (exec-op actor "t13" {:op :invoice/settle :subject "eo-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, invoice record drafted"
        (let [r2 (approve! actor "t13")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:invoiced? (store/telecom-order db "eo-1"))))
          (is (= 1 (count (store/invoice-history db))) "one draft invoice record"))))))

(deftest delivery-dispatch-double-dispatch-is-held
  (testing "dispatching the same telecom-order twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t14pre" "eo-1")
          _ (exec-op actor "t14a" {:op :delivery/dispatch :subject "eo-1"} operator)
          _ (approve! actor "t14a")
          res (exec-op actor "t14" {:op :delivery/dispatch :subject "eo-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-dispatched} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/dispatch-history db))) "still only the one earlier dispatch"))))

(deftest invoice-settle-double-invoice-is-held
  (testing "settling the same telecom-order's invoice twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t15pre" "eo-1")
          _ (exec-op actor "t15dispatch" {:op :delivery/dispatch :subject "eo-1"} operator)
          _ (approve! actor "t15dispatch")
          _ (exec-op actor "t15a" {:op :invoice/settle :subject "eo-1"} operator)
          _ (approve! actor "t15a")
          res (exec-op actor "t15" {:op :invoice/settle :subject "eo-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-invoiced} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/invoice-history db))) "still only the one earlier invoice"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :order/intake :subject "eo-1"
                          :patch {:id "eo-1" :counterparty "Northbridge Network Integrators LLC"}} operator)
      (exec-op actor "b" {:op :sourcing/verify :subject "eo-2"} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
