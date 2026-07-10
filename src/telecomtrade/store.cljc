(ns telecomtrade.store
  "SSoT for the telecom/electronics-equipment-wholesale actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite -- the same
  seam every prior `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/telecomtrade/store_contract_test.clj), which is the whole point:
  the actor, the Telecom Supply-Chain Governor and the audit ledger
  never know which SSoT they run on.

  Like the fuel-wholesale sibling's own `fuel-order` entity, this
  vertical's `dispatch` and `settle` actuation events apply SEQUENTIALLY
  to the SAME `telecom-order` -- a physical equipment dispatch happens
  first (routers/switches/base-station/radio equipment leaves the
  wholesaler's control), invoice settlement happens later, on the same
  order record. A two-member actuation shape
  (`#{:delivery/dispatch :invoice/settle}`), matching every principal-
  trading sibling except the dual-use/export-classification sibling's
  own three-member shape (that sibling's THIRD op exists because
  software/technology release is sometimes not a physical shipment at
  all -- see `cloud-itonami-isic-4651`; this vertical trades PHYSICAL
  telecom/networking hardware exclusively, so it has no analogous
  non-physical release path). Dedicated double-actuation-guard booleans
  (`:dispatched?`/`:invoiced?`, never a `:status` value) prevent
  re-running either op against the same order.

  The ledger stays append-only on every backend: which telecom-order was
  verified for a jurisdiction with no official spec-basis, which order
  had an uncleared counterparty credit / no contract-terms / an
  unresolved sanctions-screening flag, which order was BLOCKED because
  its manufacturer is on the covered list AND its buyer category is one
  the restriction reaches, which order was dispatched, which invoice was
  settled, on what jurisdictional and sourcing basis, approved by whom
  -- always a query over an immutable log."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [telecomtrade.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (telecom-order [s id])
  (all-telecom-orders [s])
  (assessment-of [s telecom-order-id] "committed sourcing assessment, or nil")
  (ledger [s])
  (dispatch-history [s] "the append-only equipment-dispatch history (telecomtrade.registry drafts)")
  (invoice-history [s] "the append-only invoice history (telecomtrade.registry drafts)")
  (next-dispatch-sequence [s jurisdiction] "next dispatch-number sequence for a jurisdiction")
  (next-invoice-sequence [s jurisdiction] "next invoice-number sequence for a jurisdiction")
  (telecom-order-already-dispatched? [s telecom-order-id] "has this order's equipment already been dispatched?")
  (telecom-order-already-invoiced? [s telecom-order-id] "has this order already been invoiced?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-telecom-orders [s telecom-orders] "replace/seed the telecom-order directory (map id->telecom-order)"))

;; ----------------------------- demo data -----------------------------

(defn- base-order
  "The neutral, clean telecom-order shape (every field in its safe
  state), so each demo order below isolates exactly ONE failure mode by
  overriding a single field. `:manufacturer` defaults to a NON-covered
  vendor (Cisco Systems, Inc.) and `:buyer-category` defaults to
  `:commercial-unrestricted` -- the baseline this vertical's domain-
  defining check must never fire against on its own."
  [overrides]
  (merge {:id "eo-1" :order-id "EO-2026-0001"
          :equipment-description "24-port managed core network switches, rack-mount, 12-unit lot"
          :equipment-type :switch
          :manufacturer "Cisco Systems, Inc."
          :counterparty "Northbridge Network Integrators LLC"
          :buyer-category :commercial-unrestricted
          :price 186000.00 :contract-terms "FCA warehouse, net 30 days"
          :credit-cleared? true :sanctions-screened? true
          :dispatched? false :invoiced? false
          :jurisdiction "USA" :status :intake
          :dispatch-number nil :invoice-number nil}
         overrides))

(defn demo-data
  "A small, self-contained telecom-order set covering both actuation
  lifecycles (dispatch, invoice settlement) plus the Telecom Supply-
  Chain Governor's own checks, so the actor + tests run offline. Each
  violation order isolates exactly ONE failure mode (the rest stay
  clean) following the 'exercise the failure mode directly, never only
  via a happy-path actuation' discipline every sibling governor's demo
  data establishes. `eo-5`/`eo-6`/`eo-7` are the CONTROL TRIPLE that
  proves the domain-defining check is a genuine CONJUNCTION, not either
  fact alone: `eo-5` (covered manufacturer + commercial-unrestricted
  buyer) and `eo-7` (non-covered manufacturer + federal-agency buyer)
  BOTH dispatch cleanly through this check; only `eo-6` (covered
  manufacturer + federal-agency buyer, BOTH facts true at once) holds.
  `eo-9` proves the restriction reaches a SECOND restricted buyer
  category (`:fcc-usf-funded-carrier`), not just `:federal-agency`, and
  exercises the GBR jurisdiction spec-basis."
  []
  {:telecom-orders
   (into {}
         (for [o [(base-order {:id "eo-1" :order-id "EO-2026-0001"})

                  (base-order {:id "eo-2" :order-id "EO-2026-0002"
                               :equipment-description "Two-way radio handset lot, 200 units"
                               :equipment-type :radio
                               :manufacturer "Motorola Solutions, Inc."
                               :counterparty "Atlantis Communications Ltd"
                               :jurisdiction "ATL"})

                  (base-order {:id "eo-3" :order-id "EO-2026-0003"
                               :equipment-description "Enterprise edge routers, 40-unit lot"
                               :equipment-type :router
                               :counterparty "Cedar Point Networks Inc"
                               :credit-cleared? false})

                  (base-order {:id "eo-4" :order-id "EO-2026-0004"
                               :equipment-description "24-port managed switches, 30-unit lot"
                               :equipment-type :switch
                               :counterparty "Delta Fiber Distribution BV"
                               :contract-terms nil})

                  (base-order {:id "eo-5" :order-id "EO-2026-0005"
                               :equipment-description "5G macro base-station radio units, 6-unit lot"
                               :equipment-type :base-station
                               :manufacturer "Huawei Technologies Company"
                               :counterparty "Eastgate Wireless Retail Co"
                               :buyer-category :commercial-unrestricted})

                  (base-order {:id "eo-6" :order-id "EO-2026-0006"
                               :equipment-description "5G macro base-station radio units, 4-unit lot"
                               :equipment-type :base-station
                               :manufacturer "Huawei Technologies Company"
                               :counterparty "Federal Networks Modernization Office"
                               :buyer-category :federal-agency})

                  (base-order {:id "eo-7" :order-id "EO-2026-0007"
                               :equipment-description "Enterprise core routers, 10-unit lot"
                               :equipment-type :router
                               :manufacturer "Nokia Corporation"
                               :counterparty "Granite Federal Systems Integrators"
                               :buyer-category :federal-agency})

                  (base-order {:id "eo-8" :order-id "EO-2026-0008"
                               :equipment-description "Network appliance/firewall lot, 15-unit"
                               :equipment-type :network-appliance
                               :counterparty "Harborview Telecom Supply Co"
                               :sanctions-screened? false})

                  (base-order {:id "eo-9" :order-id "EO-2026-0009"
                               :equipment-description "4G/5G rural base-station equipment, 8-unit lot"
                               :equipment-type :base-station
                               :manufacturer "ZTE Corporation"
                               :counterparty "Highland Rural Wireless Cooperative"
                               :buyer-category :fcc-usf-funded-carrier
                               :jurisdiction "GBR"})]]
           [(:id o) o]))})

;; ----------------------------- shared commit logic -----------------------------

(defn- dispatch-order!
  "Backend-agnostic `:order/mark-dispatched` -- looks up the telecom-
  order via the protocol and drafts the equipment-dispatch record, and
  returns {:result .. :telecom-order-patch ..} for the caller to
  persist."
  [s telecom-order-id]
  (let [eo (telecom-order s telecom-order-id)
        seq-n (next-dispatch-sequence s (:jurisdiction eo))
        result (registry/register-dispatch-record telecom-order-id (:jurisdiction eo) seq-n)]
    {:result result
     :telecom-order-patch {:dispatched? true
                           :dispatch-number (get result "dispatch_number")}}))

(defn- invoice-order!
  "Backend-agnostic `:order/mark-invoiced` -- looks up the telecom-order
  via the protocol and drafts the invoice record, and returns
  {:result .. :telecom-order-patch ..} for the caller to persist."
  [s telecom-order-id]
  (let [eo (telecom-order s telecom-order-id)
        seq-n (next-invoice-sequence s (:jurisdiction eo))
        result (registry/register-invoice-record telecom-order-id (:jurisdiction eo) seq-n)]
    {:result result
     :telecom-order-patch {:invoiced? true
                           :invoice-number (get result "invoice_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (telecom-order [_ id] (get-in @a [:telecom-orders id]))
  (all-telecom-orders [_] (sort-by :id (vals (:telecom-orders @a))))
  (assessment-of [_ telecom-order-id] (get-in @a [:assessments telecom-order-id]))
  (ledger [_] (:ledger @a))
  (dispatch-history [_] (:dispatches @a))
  (invoice-history [_] (:invoices @a))
  (next-dispatch-sequence [_ jurisdiction] (get-in @a [:dispatch-sequences jurisdiction] 0))
  (next-invoice-sequence [_ jurisdiction] (get-in @a [:invoice-sequences jurisdiction] 0))
  (telecom-order-already-dispatched? [_ telecom-order-id] (boolean (get-in @a [:telecom-orders telecom-order-id :dispatched?])))
  (telecom-order-already-invoiced? [_ telecom-order-id] (boolean (get-in @a [:telecom-orders telecom-order-id :invoiced?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :order/upsert
      (swap! a update-in [:telecom-orders (:id value)] merge value)

      :sourcing-assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :order/mark-dispatched
      (let [telecom-order-id (first path)
            {:keys [result telecom-order-patch]} (dispatch-order! s telecom-order-id)
            jurisdiction (:jurisdiction (telecom-order s telecom-order-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:dispatch-sequences jurisdiction] (fnil inc 0))
                       (update-in [:telecom-orders telecom-order-id] merge telecom-order-patch)
                       (update :dispatches registry/append result))))
        result)

      :order/mark-invoiced
      (let [telecom-order-id (first path)
            {:keys [result telecom-order-patch]} (invoice-order! s telecom-order-id)
            jurisdiction (:jurisdiction (telecom-order s telecom-order-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:invoice-sequences jurisdiction] (fnil inc 0))
                       (update-in [:telecom-orders telecom-order-id] merge telecom-order-patch)
                       (update :invoices registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-telecom-orders [s telecom-orders] (when (seq telecom-orders) (swap! a assoc :telecom-orders telecom-orders)) s))

(defn seed-db
  "A MemStore seeded with the demo telecom-order set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {}
                           :ledger [] :dispatch-sequences {} :dispatches []
                           :invoice-sequences {} :invoices []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment payloads, ledger facts, dispatch/
  invoice records) are stored as EDN strings so `langchain.db` doesn't
  expand them into sub-entities -- the same convention every sibling
  actor's store uses."
  {:telecom-order/id                     {:db/unique :db.unique/identity}
   :assessment/telecom-order-id          {:db/unique :db.unique/identity}
   :ledger/seq                           {:db/unique :db.unique/identity}
   :dispatch/seq                         {:db/unique :db.unique/identity}
   :invoice/seq                          {:db/unique :db.unique/identity}
   :dispatch-sequence/jurisdiction       {:db/unique :db.unique/identity}
   :invoice-sequence/jurisdiction        {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

;; Every telecom-order field is stored as its own Datomic attr so a
;; governor pull reads the exact ground truth (no blob decode). Boolean
;; fields are coerced on read so a missing attr reads back as false
;; (parity with MemStore). Keyword-valued fields round-trip via
;; `enc`/`dec*` (stored as an EDN string) so `:equipment-type`/
;; `:buyer-category`/`:status` survive the pull as keywords, not bare
;; strings -- necessary here (unlike the fuel-wholesale sibling's own
;; DatomicStore, which stores `:status` as a bare value) because
;; `:buyer-category` is structurally load-bearing: it is one of the TWO
;; independent facts `covered-manufacturer-buyer-restricted-violations`
;; reads directly via `contains?` against a set of keywords, so losing
;; the keyword-ness on a Datomic round-trip would be a real parity bug,
;; the same discipline `cloud-itonami-isic-4651`'s `:item-type`/
;; `:delivery-mode` encoding establishes for its own load-bearing
;; keyword fields. [field-key tx-attr kind] kind ∈ #{:plain :bool :kw}
(def ^:private telecom-order-fields
  [[:id :telecom-order/id :plain]
   [:order-id :telecom-order/order-id :plain]
   [:equipment-description :telecom-order/equipment-description :plain]
   [:equipment-type :telecom-order/equipment-type :kw]
   [:manufacturer :telecom-order/manufacturer :plain]
   [:counterparty :telecom-order/counterparty :plain]
   [:buyer-category :telecom-order/buyer-category :kw]
   [:price :telecom-order/price :plain]
   [:contract-terms :telecom-order/contract-terms :plain]
   [:credit-cleared? :telecom-order/credit-cleared? :bool]
   [:sanctions-screened? :telecom-order/sanctions-screened? :bool]
   [:dispatched? :telecom-order/dispatched? :bool]
   [:invoiced? :telecom-order/invoiced? :bool]
   [:jurisdiction :telecom-order/jurisdiction :plain]
   [:status :telecom-order/status :kw]
   [:dispatch-number :telecom-order/dispatch-number :plain]
   [:invoice-number :telecom-order/invoice-number :plain]])

(defn- telecom-order->tx [eo]
  (reduce (fn [tx [k attr kind]]
            (let [v (get eo k)]
              (cond-> tx
                (some? v) (assoc attr (if (= kind :kw) (enc v) v)))))
          {:telecom-order/id (:id eo)}
          telecom-order-fields))

(def ^:private telecom-order-pull (mapv second telecom-order-fields))

(defn- pull->telecom-order [m]
  (when (:telecom-order/id m)
    (reduce (fn [eo [k attr kind]]
              (let [v (get m attr)]
                (cond
                  (= kind :bool)  (assoc eo k (boolean v))
                  (= kind :kw)    (cond-> eo (some? v) (assoc k (dec* v)))
                  (some? v)       (assoc eo k v)
                  :else           eo)))
            {:id (:telecom-order/id m)}
            telecom-order-fields)))

(defrecord DatomicStore [conn]
  Store
  (telecom-order [_ id]
    (pull->telecom-order (d/pull (d/db conn) telecom-order-pull [:telecom-order/id id])))
  (all-telecom-orders [_]
    (->> (d/q '[:find [?id ...] :where [?e :telecom-order/id ?id]] (d/db conn))
         (map #(pull->telecom-order (d/pull (d/db conn) telecom-order-pull [:telecom-order/id %])))
         (sort-by :id)))
  (assessment-of [_ telecom-order-id]
    (dec* (d/q '[:find ?p . :in $ ?eoid
                :where [?a :assessment/telecom-order-id ?eoid] [?a :assessment/payload ?p]]
              (d/db conn) telecom-order-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (dispatch-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :dispatch/seq ?s] [?e :dispatch/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (invoice-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :invoice/seq ?s] [?e :invoice/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-dispatch-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :dispatch-sequence/jurisdiction ?j] [?e :dispatch-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-invoice-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :invoice-sequence/jurisdiction ?j] [?e :invoice-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (telecom-order-already-dispatched? [s telecom-order-id]
    (boolean (:dispatched? (telecom-order s telecom-order-id))))
  (telecom-order-already-invoiced? [s telecom-order-id]
    (boolean (:invoiced? (telecom-order s telecom-order-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :order/upsert
      (d/transact! conn [(telecom-order->tx value)])

      :sourcing-assessment/set
      (d/transact! conn [{:assessment/telecom-order-id (first path) :assessment/payload (enc payload)}])

      :order/mark-dispatched
      (let [telecom-order-id (first path)
            {:keys [result telecom-order-patch]} (dispatch-order! s telecom-order-id)
            jurisdiction (:jurisdiction (telecom-order s telecom-order-id))
            next-n (inc (next-dispatch-sequence s jurisdiction))]
        (d/transact! conn
                     [(telecom-order->tx (assoc telecom-order-patch :id telecom-order-id))
                      {:dispatch-sequence/jurisdiction jurisdiction :dispatch-sequence/next next-n}
                      {:dispatch/seq (count (dispatch-history s)) :dispatch/record (enc (get result "record"))}])
        result)

      :order/mark-invoiced
      (let [telecom-order-id (first path)
            {:keys [result telecom-order-patch]} (invoice-order! s telecom-order-id)
            jurisdiction (:jurisdiction (telecom-order s telecom-order-id))
            next-n (inc (next-invoice-sequence s jurisdiction))]
        (d/transact! conn
                     [(telecom-order->tx (assoc telecom-order-patch :id telecom-order-id))
                      {:invoice-sequence/jurisdiction jurisdiction :invoice-sequence/next next-n}
                      {:invoice/seq (count (invoice-history s)) :invoice/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-telecom-orders [s telecom-orders]
    (when (seq telecom-orders) (d/transact! conn (mapv telecom-order->tx (vals telecom-orders)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:telecom-orders ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [telecom-orders]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-telecom-orders s telecom-orders))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo telecom-order set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
