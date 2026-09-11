(ns telecomtrade.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean order through
  intake -> sourcing verification -> equipment dispatch (escalate/
  approve/commit) -> invoice settlement (escalate/approve/commit), then
  shows the CONTROL TRIPLE that proves the domain-defining check
  (`covered-manufacturer-buyer-restricted`) is a genuine CONJUNCTION of
  two INDEPENDENT facts, not either one alone:

    - eo-5: manufacturer IS on the covered list (Huawei Technologies
      Company) but the buyer IS `:commercial-unrestricted` -- dispatches
      CLEANLY end-to-end (Section 889 does not reach a purely private
      commercial sale with no federal-agency/federal-contract/FCC-USF-
      funding nexus).
    - eo-6: SAME covered manufacturer, but the buyer IS `:federal-agency`
      -- BOTH facts true at once -> HARD hold
      `:covered-manufacturer-buyer-restricted`.
    - eo-7: a NON-covered manufacturer (Nokia Corporation), buyer IS
      `:federal-agency` -- dispatches CLEANLY (a restricted buyer
      category alone, without a covered manufacturer, triggers nothing).
    - eo-9: a DIFFERENT covered manufacturer (ZTE Corporation), buyer IS
      `:fcc-usf-funded-carrier` (the USF-funding-nexus restricted
      category, not `:federal-agency`) -> ALSO a HARD hold, proving the
      restriction reaches more than one buyer category, and exercises
      the GBR jurisdiction spec-basis.

  Then a jurisdiction with no spec-basis, a counterparty whose credit
  has not been cleared, an order with no contract-terms on file, a
  counterparty that has not passed OFAC-style sanctions screening, a
  double dispatch, and a double invoice.

  Like every sibling actor's domain checks, this actor's checks
  (`credit-uncleared`, `contract-missing`,
  `covered-manufacturer-buyer-restricted`,
  `counterparty-sanctions-flag-unresolved`) are evaluated directly at
  `:delivery/dispatch` (and the sanctions check at `:invoice/settle`
  too) rather than via a separate screening op -- a real dispatch
  decision validates counterparty credit, contract-on-file, covered-
  manufacturer/buyer-category status and sanctions screening at the
  point of the act itself, not as a discrete pre-screening ceremony.
  Each check is still exercised directly and independently below, one
  order per HARD-hold scenario, following the SAME 'exercise the
  failure mode directly, never only via a happy-path actuation'
  discipline `parksafety`'s ADR-2607071922 Decision 5 and every sibling
  since establish."
  (:require [langgraph.graph :as g]
            [telecomtrade.store :as store]
            [telecomtrade.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :trading-supervisor :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== order/intake eo-1 (USA, non-covered manufacturer, commercial-unrestricted buyer, clean) ==")
    (println (exec-op actor "t1" {:op :order/intake :subject "eo-1"
                                  :patch {:id "eo-1" :counterparty "Northbridge Network Integrators LLC"}} operator))

    (println "== sourcing/verify eo-1 (escalates -- human approves) ==")
    (println (exec-op actor "t2" {:op :sourcing/verify :subject "eo-1"} operator))
    (println (approve! actor "t2"))

    (println "== delivery/dispatch eo-1 (always escalates -- :delivery/dispatch) ==")
    (let [r (exec-op actor "t3" {:op :delivery/dispatch :subject "eo-1"} operator)]
      (println r)
      (println "-- human trading supervisor approves --")
      (println (approve! actor "t3")))

    (println "== invoice/settle eo-1 (always escalates -- :invoice/settle) ==")
    (let [r (exec-op actor "t4" {:op :invoice/settle :subject "eo-1"} operator)]
      (println r)
      (println "-- human trading supervisor approves --")
      (println (approve! actor "t4")))

    (println "== CONTROL TRIPLE: proving covered-manufacturer-buyer-restricted is a CONJUNCTION ==")

    (println "== sourcing/verify eo-5 (Huawei + commercial-unrestricted buyer -- sets up leg 1) ==")
    (println (exec-op actor "t5" {:op :sourcing/verify :subject "eo-5"} operator))
    (println (approve! actor "t5"))

    (println "== delivery/dispatch eo-5 (covered manufacturer ALONE, commercial-unrestricted buyer -> CLEAN, always escalates for approval, NOT held) ==")
    (let [r (exec-op actor "t6" {:op :delivery/dispatch :subject "eo-5"} operator)]
      (println r)
      (println "-- human trading supervisor approves (governor never held this) --")
      (println (approve! actor "t6")))

    (println "== sourcing/verify eo-6 (Huawei + federal-agency buyer -- sets up leg 2) ==")
    (println (exec-op actor "t7" {:op :sourcing/verify :subject "eo-6"} operator))
    (println (approve! actor "t7"))

    (println "== delivery/dispatch eo-6 (covered manufacturer AND federal-agency buyer, BOTH true -> HARD hold :covered-manufacturer-buyer-restricted) ==")
    (println (exec-op actor "t8" {:op :delivery/dispatch :subject "eo-6"} operator))

    (println "== sourcing/verify eo-7 (Nokia [non-covered] + federal-agency buyer -- sets up leg 3) ==")
    (println (exec-op actor "t9" {:op :sourcing/verify :subject "eo-7"} operator))
    (println (approve! actor "t9"))

    (println "== delivery/dispatch eo-7 (restricted buyer category ALONE, non-covered manufacturer -> CLEAN, always escalates for approval, NOT held) ==")
    (let [r (exec-op actor "t10" {:op :delivery/dispatch :subject "eo-7"} operator)]
      (println r)
      (println "-- human trading supervisor approves (governor never held this) --")
      (println (approve! actor "t10")))

    (println "== sourcing/verify eo-9 (ZTE + fcc-usf-funded-carrier buyer, GBR jurisdiction -- a SECOND restricted buyer category) ==")
    (println (exec-op actor "t11" {:op :sourcing/verify :subject "eo-9"} operator))
    (println (approve! actor "t11"))

    (println "== delivery/dispatch eo-9 (covered manufacturer AND fcc-usf-funded-carrier buyer -> HARD hold :covered-manufacturer-buyer-restricted) ==")
    (println (exec-op actor "t12" {:op :delivery/dispatch :subject "eo-9"} operator))

    (println "== other HARD-hold scenarios ==")

    (println "== sourcing/verify eo-2 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t13" {:op :sourcing/verify :subject "eo-2"} operator))

    (println "== sourcing/verify eo-3 (escalates -- sets up the credit-uncleared test) ==")
    (println (exec-op actor "t14" {:op :sourcing/verify :subject "eo-3"} operator))
    (println (approve! actor "t14"))

    (println "== delivery/dispatch eo-3 (credit not cleared -> HARD hold) ==")
    (println (exec-op actor "t15" {:op :delivery/dispatch :subject "eo-3"} operator))

    (println "== sourcing/verify eo-4 (escalates -- sets up the contract-missing test) ==")
    (println (exec-op actor "t16" {:op :sourcing/verify :subject "eo-4"} operator))
    (println (approve! actor "t16"))

    (println "== delivery/dispatch eo-4 (no contract-terms on file -> HARD hold) ==")
    (println (exec-op actor "t17" {:op :delivery/dispatch :subject "eo-4"} operator))

    (println "== sourcing/verify eo-8 (escalates -- sets up the sanctions test) ==")
    (println (exec-op actor "t18" {:op :sourcing/verify :subject "eo-8"} operator))
    (println (approve! actor "t18"))

    (println "== delivery/dispatch eo-8 (sanctions screening not passed -> HARD hold) ==")
    (println (exec-op actor "t19" {:op :delivery/dispatch :subject "eo-8"} operator))

    (println "== delivery/dispatch eo-1 AGAIN (double-dispatch -> HARD hold) ==")
    (println (exec-op actor "t20" {:op :delivery/dispatch :subject "eo-1"} operator))

    (println "== invoice/settle eo-1 AGAIN (double-invoice -> HARD hold) ==")
    (println (exec-op actor "t21" {:op :invoice/settle :subject "eo-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft equipment-dispatch records ==")
    (doseq [r (store/dispatch-history db)] (println r))

    (println "== draft invoice records ==")
    (doseq [r (store/invoice-history db)] (println r))))
