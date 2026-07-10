(ns telecomtrade.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [telecomtrade.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "USA" (:jurisdiction (store/telecom-order s "eo-1"))))
      (is (= "Northbridge Network Integrators LLC" (:counterparty (store/telecom-order s "eo-1"))))
      (is (= :switch (:equipment-type (store/telecom-order s "eo-1"))))
      (is (= "Cisco Systems, Inc." (:manufacturer (store/telecom-order s "eo-1"))))
      (is (= :commercial-unrestricted (:buyer-category (store/telecom-order s "eo-1"))))
      (is (= "ATL" (:jurisdiction (store/telecom-order s "eo-2"))))
      (is (false? (:credit-cleared? (store/telecom-order s "eo-3"))) "eo-3 credit not cleared")
      (is (nil? (:contract-terms (store/telecom-order s "eo-4"))) "eo-4 no contract-terms")
      (is (= "Huawei Technologies Company" (:manufacturer (store/telecom-order s "eo-5"))))
      (is (= :commercial-unrestricted (:buyer-category (store/telecom-order s "eo-5"))))
      (is (= "Huawei Technologies Company" (:manufacturer (store/telecom-order s "eo-6"))))
      (is (= :federal-agency (:buyer-category (store/telecom-order s "eo-6"))))
      (is (= "Nokia Corporation" (:manufacturer (store/telecom-order s "eo-7"))))
      (is (= :federal-agency (:buyer-category (store/telecom-order s "eo-7"))))
      (is (false? (:sanctions-screened? (store/telecom-order s "eo-8"))) "eo-8 sanctions not screened")
      (is (= "ZTE Corporation" (:manufacturer (store/telecom-order s "eo-9"))))
      (is (= :fcc-usf-funded-carrier (:buyer-category (store/telecom-order s "eo-9"))))
      (is (= "GBR" (:jurisdiction (store/telecom-order s "eo-9"))))
      (is (false? (:dispatched? (store/telecom-order s "eo-1"))))
      (is (false? (:invoiced? (store/telecom-order s "eo-1"))))
      (is (= ["eo-1" "eo-2" "eo-3" "eo-4" "eo-5" "eo-6" "eo-7" "eo-8" "eo-9"]
             (mapv :id (store/all-telecom-orders s))))
      (is (nil? (store/assessment-of s "eo-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/dispatch-history s)))
      (is (= [] (store/invoice-history s)))
      (is (zero? (store/next-dispatch-sequence s "USA")))
      (is (zero? (store/next-invoice-sequence s "USA")))
      (is (false? (store/telecom-order-already-dispatched? s "eo-1")))
      (is (false? (store/telecom-order-already-invoiced? s "eo-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :order/upsert
                                 :value {:id "eo-1" :counterparty "Northbridge Network Integrators LLC"}})
        (is (= "Northbridge Network Integrators LLC" (:counterparty (store/telecom-order s "eo-1"))))
        (is (= "USA" (:jurisdiction (store/telecom-order s "eo-1"))) "unrelated field preserved"))
      (testing "sourcing-assessment payloads commit and read back"
        (store/commit-record! s {:effect :sourcing-assessment/set :path ["eo-1"]
                                 :payload {:jurisdiction "USA" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "USA" :checklist ["a" "b"]} (store/assessment-of s "eo-1"))))
      (testing "equipment dispatch drafts a record and advances the dispatch sequence"
        (store/commit-record! s {:effect :order/mark-dispatched :path ["eo-1"]})
        (is (= "USA-DISPATCH-000000" (get (first (store/dispatch-history s)) "record_id")))
        (is (= "telecom-dispatch-draft" (get (first (store/dispatch-history s)) "kind")))
        (is (true? (:dispatched? (store/telecom-order s "eo-1"))))
        (is (= 1 (count (store/dispatch-history s))))
        (is (= 1 (store/next-dispatch-sequence s "USA")))
        (is (true? (store/telecom-order-already-dispatched? s "eo-1"))))
      (testing "invoice settlement drafts a record and advances the invoice sequence"
        (store/commit-record! s {:effect :order/mark-invoiced :path ["eo-1"]})
        (is (= "USA-INVOICE-000000" (get (first (store/invoice-history s)) "record_id")))
        (is (= "telecom-invoice-draft" (get (first (store/invoice-history s)) "kind")))
        (is (true? (:invoiced? (store/telecom-order s "eo-1"))))
        (is (= 1 (count (store/invoice-history s))))
        (is (= 1 (store/next-invoice-sequence s "USA")))
        (is (true? (store/telecom-order-already-invoiced? s "eo-1"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/telecom-order s "nope")))
    (is (= [] (store/all-telecom-orders s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/dispatch-history s)))
    (is (= [] (store/invoice-history s)))
    (is (zero? (store/next-dispatch-sequence s "USA")))
    (is (zero? (store/next-invoice-sequence s "USA")))
    (store/with-telecom-orders s {"x" {:id "x" :order-id "EO-X"
                                       :equipment-description "Test equipment" :equipment-type :router
                                       :manufacturer "Cisco Systems, Inc."
                                       :counterparty "c" :buyer-category :commercial-unrestricted
                                       :price 1000.0 :contract-terms "FCA warehouse, net 30 days"
                                       :credit-cleared? true :sanctions-screened? true
                                       :dispatched? false :invoiced? false
                                       :jurisdiction "USA" :status :intake
                                       :dispatch-number nil :invoice-number nil}})
    (is (= "c" (:counterparty (store/telecom-order s "x"))))
    (is (= :router (:equipment-type (store/telecom-order s "x"))) "keyword field round-trips through DatomicStore")
    (is (= :commercial-unrestricted (:buyer-category (store/telecom-order s "x"))) "buyer-category keyword round-trips through DatomicStore")))
