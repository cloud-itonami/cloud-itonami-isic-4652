(ns telecomtrade.registry
  "Pure-function equipment-dispatch + invoice record construction -- an
  append-only telecom/electronics-equipment-wholesale book-of-record
  draft.

  Like every principal-trading sibling's own registry, this vertical's
  Telecom Supply-Chain Governor needs NO registry range-check functions
  at all: its domain checks (credit-uncleared, contract-missing,
  covered-manufacturer-buyer-restricted, counterparty-sanctions-flag-
  unresolved) are direct entity/catalog reads in `telecomtrade.
  governor`, off dedicated facts on the `telecom-order` record and
  `telecomtrade.facts`' two catalogs. So this namespace is RECORD
  CONSTRUCTION ONLY -- no pure range checks to host here.

  Like every sibling actor's registry, there is no single international
  reference-number standard for an equipment-dispatch or invoice record
  -- every operator/jurisdiction assigns its own reference format. This
  namespace does NOT invent one beyond a jurisdiction-scoped sequence
  number; it validates the record's required fields, the same honest,
  non-fabricating discipline `telecomtrade.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real warehouse-management/ERP/billing system. It builds
  the RECORD an operator would keep, not the act of dispatching real
  telecom/networking equipment or settling a real invoice itself (that
  is `telecomtrade.operation`'s `:delivery/dispatch`/`:invoice/settle`,
  always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the operator's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

;; ----------------------------- record construction -----------------------------

(defn register-dispatch-record
  "Validate + construct the EQUIPMENT-DISPATCH registration DRAFT -- the
  operator's own legal act of dispatching real telecom/networking
  equipment to a counterparty. Pure function -- does not touch any real
  warehouse-management or ERP system; it builds the RECORD an operator
  would keep. `telecomtrade.governor` independently re-verifies the
  counterparty's credit-clearance, contract-on-file, covered-
  manufacturer/buyer-category, sanctions-screening and evidence-
  completeness ground truth, and blocks a double-dispatch of the same
  telecom-order, before this is ever allowed to commit."
  [telecom-order-id jurisdiction sequence]
  (when-not (and telecom-order-id (not= telecom-order-id ""))
    (throw (ex-info "telecom-dispatch: telecom_order_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "telecom-dispatch: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "telecom-dispatch: sequence must be >= 0" {})))
  (let [dispatch-number (str (str/upper-case jurisdiction) "-DISPATCH-" (zero-pad sequence 6))
        record {"record_id" dispatch-number
                "kind" "telecom-dispatch-draft"
                "telecom_order_id" telecom-order-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "dispatch_number" dispatch-number
     "certificate" (unsigned-certificate "TelecomDispatch" dispatch-number dispatch-number)}))

(defn register-invoice-record
  "Validate + construct the INVOICE registration DRAFT -- the operator's
  own legal act of settling a real telecom/electronics-equipment-
  wholesale invoice (the money side of the trade, custody/financial
  transfer). Pure function -- does not touch any real billing or
  accounts-receivable system; it builds the RECORD an operator would
  keep. `telecomtrade.governor` independently re-verifies the sanctions-
  screening and evidence-completeness ground truth, and blocks a
  double-invoice of the same telecom-order, before this is ever allowed
  to commit."
  [telecom-order-id jurisdiction sequence]
  (when-not (and telecom-order-id (not= telecom-order-id ""))
    (throw (ex-info "telecom-invoice: telecom_order_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "telecom-invoice: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "telecom-invoice: sequence must be >= 0" {})))
  (let [invoice-number (str (str/upper-case jurisdiction) "-INVOICE-" (zero-pad sequence 6))
        record {"record_id" invoice-number
                "kind" "telecom-invoice-draft"
                "telecom_order_id" telecom-order-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "invoice_number" invoice-number
     "certificate" (unsigned-certificate "TelecomInvoice" invoice-number invoice-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
