(ns telecomtrade.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  This repo previously shipped a HAND-WRITTEN `docs/samples/operator-
  console.html` (added in the initial commit, 7,512 bytes, with no
  generator anywhere in the tree). Its entity ids happened to match
  `telecomtrade.store/demo-data`, but its `Governor verdict` column was
  typed, not computed: it asserted exactly one verdict per order with no
  run behind it, so it could not show the ORDER-DEPENDENT part of the
  governor (an order dispatched before its sourcing assessment is
  committed holds on `:evidence-incomplete`, not on the rule the page
  claimed), and it stated behaviour in prose that nothing re-checked.
  This namespace replaces it with output that is DERIVED.

  Every entity id, order id, manufacturer, counterparty, buyer category,
  price, dispatch/invoice number, violation rule, violation detail
  string and ledger row on the generated page comes from a real run of
  THIS repo's own stack -- `telecomtrade.operation` (langgraph-clj
  StateGraph) -> `telecomtrade.governor` -> `telecomtrade.store` --
  against `telecomtrade.store/seed-db`. Nothing on the page is typed by
  hand.

  Two rendering disciplines follow from that:

  - The action-gate section does not DESCRIBE the phase policy, it CALLS
    it: every cell is `(telecomtrade.phase/gate phase {:op op} ..)`
    evaluated at render time, for all four phases and all four write
    ops. A sentence saying `:delivery/dispatch` can never auto-commit
    would become a lie the moment someone added it to a phase's `:auto`
    set; a table computed from `phase/gate` cannot.
  - The approval-attribution section MEASURES what the SSoT retained
    rather than assuming it. See `retained-approver` -- the answer is
    genuinely mixed in this repo, and the page reports both halves.

  Determinism: no timestamps, no random, no wall-clock, no locale-
  dependent formatting (money goes through `Locale/ROOT`), and every map
  / set iterated for output is explicitly sorted. Two consecutive runs
  are byte-identical.

  `-main` refuses to write a console from a scenario that produced no
  `:governor-hold` fact, and refuses to write one that produced no
  `:committed` fact -- a console that showed neither would misrepresent
  the governor as either toothless or total. That makes the HARD-hold
  requirement a build-time invariant instead of a convention.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [telecomtrade.facts :as facts]
            [telecomtrade.governor :as governor]
            [telecomtrade.operation :as op]
            [telecomtrade.phase :as phase]
            [telecomtrade.store :as store]))

(def ^:private operator
  "The injected actor context. `:phase 3` is `telecomtrade.phase/
  default-phase` -- the most permissive rollout phase this actor has, so
  everything the page shows as still requiring a human is required by
  structure, not by running the demo at a deliberately timid phase."
  {:actor-id "op-1" :actor-role :trading-supervisor :phase phase/default-phase})

;; ----------------------------- scenario driver -----------------------------

(defn- record!
  "Keeps ONE entry per thread-id, holding that thread's LATEST result.
  A resumed approval returns a state whose `:audit` channel already
  contains the pre-interrupt facts (the channel reducer is `into`), so
  collecting both the interrupt and the resume would double-count every
  proposal."
  [runs tid label r]
  (swap! runs
         (fn [v]
           (if-let [i (first (keep-indexed (fn [i m] (when (= tid (:tid m)) i)) v))]
             (assoc v i {:tid tid :label label :final r})
             (conj v {:tid tid :label label :final r}))))
  r)

(defn- exec! [runs actor tid label request]
  (record! runs tid label
           (g/run* actor {:request request :context operator} {:thread-id tid})))

(defn- decide! [runs actor tid label status by]
  (record! runs tid label
           (g/run* actor {:approval {:status status :by by}}
                   {:thread-id tid :resume? true})))

(defn run-demo!
  "Drives a freshly seeded store through a scenario that reaches EVERY
  disposition this actor can produce, and every HARD rule
  `telecomtrade.governor/check` can emit.

  Happy path, on the real seeded order `eo-1` (Cisco Systems, Inc. ->
  Northbridge Network Integrators LLC, USA): intake auto-commits (phase
  3, `:order/intake` is the only op in any phase's `:auto` set), then a
  sourcing verification, an equipment dispatch and an invoice settlement
  each escalate to a human and are approved.

  `eo-1` is ALSO dispatched once BEFORE its sourcing assessment exists,
  which is the case the previous hand-written page could not represent:
  the same order, same clean facts, holds on `:evidence-incomplete`
  purely because of WHEN it was attempted.

  Control quad for the domain-defining conjunction check -- `eo-5`
  (covered manufacturer, unrestricted buyer) and `eo-7` (non-covered
  manufacturer, restricted buyer) each carry exactly ONE of the two
  facts and both dispatch cleanly; `eo-6` (Huawei + `:federal-agency`)
  and `eo-9` (ZTE + `:fcc-usf-funded-carrier`, GBR) carry BOTH and both
  hold on `:covered-manufacturer-buyer-restricted`.

  Remaining HARD rules, one isolated order each: `eo-2` (`ATL`, a
  jurisdiction deliberately absent from `telecomtrade.facts/catalog`) ->
  `:no-spec-basis`; `eo-3` -> `:credit-uncleared`; `eo-4` ->
  `:contract-missing`; `eo-8` -> `:counterparty-sanctions-flag-
  unresolved`; `eo-1` re-dispatched -> `:already-dispatched`; `eo-1`
  re-settled -> `:already-invoiced`.

  Finally a human REJECTION: settling `eo-5`'s invoice is governor-clean
  and reaches the approver, who declines -- the one hold on the page
  that a human produced rather than the governor.

  Returns {:db store :runs [..]} -- every value the renderer prints is
  read back out of these, never passed in."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        runs (atom [])
        go (fn [tid label request] (exec! runs actor tid label request))
        approve (fn [tid label] (decide! runs actor tid label :approved "op-1"))
        reject (fn [tid label] (decide! runs actor tid label :rejected "op-2"))]

    ;; -- eo-1: intake auto-commits at phase 3 (no capital risk yet) --
    (go "t01" "intake eo-1"
        {:op :order/intake :subject "eo-1"
         :patch {:id "eo-1" :counterparty "Northbridge Network Integrators LLC"}})

    ;; -- eo-1 dispatched too early: clean order, HARD hold on sequencing --
    (go "t02" "dispatch eo-1 before any sourcing assessment exists"
        {:op :delivery/dispatch :subject "eo-1"})

    ;; -- eo-1 full lifecycle --
    (go "t03" "sourcing/verify eo-1" {:op :sourcing/verify :subject "eo-1"})
    (approve "t03" "sourcing/verify eo-1 approved")

    (go "t04" "delivery/dispatch eo-1" {:op :delivery/dispatch :subject "eo-1"})
    (approve "t04" "delivery/dispatch eo-1 approved")

    (go "t05" "invoice/settle eo-1" {:op :invoice/settle :subject "eo-1"})
    (approve "t05" "invoice/settle eo-1 approved")

    ;; -- double-actuation guards --
    (go "t06" "delivery/dispatch eo-1 again" {:op :delivery/dispatch :subject "eo-1"})
    (go "t07" "invoice/settle eo-1 again" {:op :invoice/settle :subject "eo-1"})

    ;; -- control quad for the conjunction check --
    (go "t08" "sourcing/verify eo-5" {:op :sourcing/verify :subject "eo-5"})
    (approve "t08" "sourcing/verify eo-5 approved")
    (go "t09" "delivery/dispatch eo-5 (covered manufacturer alone)"
        {:op :delivery/dispatch :subject "eo-5"})
    (approve "t09" "delivery/dispatch eo-5 approved")

    (go "t10" "sourcing/verify eo-6" {:op :sourcing/verify :subject "eo-6"})
    (approve "t10" "sourcing/verify eo-6 approved")
    (go "t11" "delivery/dispatch eo-6 (both facts true)"
        {:op :delivery/dispatch :subject "eo-6"})

    (go "t12" "sourcing/verify eo-7" {:op :sourcing/verify :subject "eo-7"})
    (approve "t12" "sourcing/verify eo-7 approved")
    (go "t13" "delivery/dispatch eo-7 (restricted buyer alone)"
        {:op :delivery/dispatch :subject "eo-7"})
    (approve "t13" "delivery/dispatch eo-7 approved")

    (go "t14" "sourcing/verify eo-9 (GBR)" {:op :sourcing/verify :subject "eo-9"})
    (approve "t14" "sourcing/verify eo-9 approved")
    (go "t15" "delivery/dispatch eo-9 (second restricted buyer category)"
        {:op :delivery/dispatch :subject "eo-9"})

    ;; -- one isolated order per remaining HARD rule --
    (go "t16" "sourcing/verify eo-2 (jurisdiction ATL, absent from the catalog)"
        {:op :sourcing/verify :subject "eo-2"})

    (go "t17" "sourcing/verify eo-3" {:op :sourcing/verify :subject "eo-3"})
    (approve "t17" "sourcing/verify eo-3 approved")
    (go "t18" "delivery/dispatch eo-3 (credit not cleared)"
        {:op :delivery/dispatch :subject "eo-3"})

    (go "t19" "sourcing/verify eo-4" {:op :sourcing/verify :subject "eo-4"})
    (approve "t19" "sourcing/verify eo-4 approved")
    (go "t20" "delivery/dispatch eo-4 (no contract-terms on file)"
        {:op :delivery/dispatch :subject "eo-4"})

    (go "t21" "sourcing/verify eo-8" {:op :sourcing/verify :subject "eo-8"})
    (approve "t21" "sourcing/verify eo-8 approved")
    (go "t22" "delivery/dispatch eo-8 (sanctions screening not passed)"
        {:op :delivery/dispatch :subject "eo-8"})

    ;; -- a hold the HUMAN produced, not the governor --
    (go "t23" "invoice/settle eo-5 (governor-clean)" {:op :invoice/settle :subject "eo-5"})
    (reject "t23" "invoice/settle eo-5 REJECTED by the approver")

    {:db db :runs @runs}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (if (nil? v) "" (str v))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- nm [v] (if (keyword? v) (name v) (str v)))

(defn- money
  "Locale-independent money formatting -- `clojure.core/format` would use
  the default locale and could render a different decimal separator on a
  differently-configured machine, which would break byte-identity across
  builds."
  [v]
  (if (number? v)
    (String/format java.util.Locale/ROOT "%,.2f" (into-array Object [(double v)]))
    (str v)))

(defn- yn [b] (if b "<span class=\"err\">yes</span>" "<span class=\"muted\">no</span>"))
(defn- okno [b] (if b "<span class=\"ok\">yes</span>" "<span class=\"err\">no</span>"))
(defn- tag [cls s] (str "<span class=\"" cls "\">" (esc s) "</span>"))
(defn- code [s] (str "<code>" (esc s) "</code>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" % "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n") "")
       "      </tbody>\n"
       "    </table>\n"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- section [title lede & body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (if lede (str "    <p class=\"muted\">" lede "</p>\n") "")
       (str/join body)
       "  </section>\n"))

;; ----------------------------- derived views -----------------------------

(defn- audit-trail
  "Every fact the graph emitted, in scenario order. Strictly richer than
  the persisted ledger: `:telecomtradeadvisor-proposal`,
  `:approval-requested` and `:approval-granted` are graph-channel facts
  that `telecomtrade.operation` never appends to the store."
  [runs]
  (vec (mapcat #(get-in % [:final :state :audit]) runs)))

(defn- holds-for
  "Governor holds this run actually produced for `subject`."
  [ledger subject]
  (filter #(and (= :governor-hold (:t %)) (= subject (:subject %))) ledger))

(defn- rules-hit [ledger subject]
  (set (mapcat :basis (holds-for ledger subject))))

(defn- last-fact [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact ledger subject)]
    (case (:t f)
      :committed (tag "ok" "committed")
      :governor-hold (str (tag "critical" "HARD hold")
                          " " (code (str/join ", " (map nm (:basis f)))))
      :approval-rejected (str (tag "warn" "rejected by approver")
                              " " (code (str/join ", " (map nm (:basis f)))))
      (tag "muted" "no ledger activity"))))

;; -- orders --

(defn- order-row [ledger {:keys [id order-id equipment-description equipment-type
                                 manufacturer counterparty buyer-category price
                                 contract-terms credit-cleared? sanctions-screened?
                                 dispatched? invoiced? jurisdiction
                                 dispatch-number invoice-number]}]
  (let [covered? (facts/covered-manufacturer? manufacturer)
        restricted? (contains? facts/restricted-buyer-categories buyer-category)]
    (row (code id)
         (esc order-id)
         (str (esc equipment-description) "<br>" (code (nm equipment-type)))
         (str (esc manufacturer)
              (when covered? (str " " (tag "critical" "covered list"))))
         (str (esc counterparty) "<br>" (code (nm buyer-category))
              (when restricted? (str " " (tag "critical" "restricted"))))
         (str "<span class=\"num\">" (esc (money price)) "</span>")
         (esc jurisdiction)
         (str (okno credit-cleared?) " / " (okno sanctions-screened?) " / "
              (if (str/blank? (str contract-terms))
                (tag "err" "no contract")
                (tag "ok" "contract")))
         (str (if dispatched? (str (tag "ok" "dispatched") " " (code dispatch-number))
                  (tag "muted" "not dispatched"))
              "<br>"
              (if invoiced? (str (tag "ok" "invoiced") " " (code invoice-number))
                  (tag "muted" "not invoiced")))
         (status-cell ledger id))))

;; -- conjunction truth table --

(defn- conjunction-row [ledger {:keys [id manufacturer buyer-category]}]
  (let [covered? (facts/covered-manufacturer? manufacturer)
        restricted? (contains? facts/restricted-buyer-categories buyer-category)
        held? (contains? (rules-hit ledger id) :covered-manufacturer-buyer-restricted)]
    (row (code id)
         (esc manufacturer)
         (yn covered?)
         (code (nm buyer-category))
         (yn restricted?)
         (if (and covered? restricted?) "<strong>A &and; B</strong>"
             (tag "muted" "not both"))
         (if held?
           (tag "critical" "HELD")
           (tag "ok" "not held on this rule")))))

;; -- action gate, computed by calling phase/gate --

(defn- gate-cell [ph op base]
  (let [{:keys [disposition reason]} (phase/gate ph {:op op} base)]
    (str (case disposition
           :commit (tag "ok" "auto-commit")
           :escalate (tag "warn" "human approval")
           :hold (tag "critical" "HOLD"))
         (when reason (str " " (code (nm reason)))))))

(defn- gate-row [ledger op]
  (let [observed (->> ledger (filter #(= op (:op %))) (map :t) distinct sort)]
    (apply row
           (code (str op))
           (concat
            (for [ph (sort (keys phase/phases))] (gate-cell ph op :commit))
            [(gate-cell phase/default-phase op :hold)
             (if (contains? governor/high-stakes op)
               (tag "critical" "yes")
               (tag "muted" "no"))
             (if (seq observed)
               (str/join " " (map #(code (nm %)) observed))
               (tag "muted" "not exercised"))]))))

;; -- HARD holds this run produced --

(defn- hold-rule-rows [ledger]
  (let [by-rule (group-by :rule (mapcat :violations (filter #(= :governor-hold (:t %)) ledger)))
        subjects-of (fn [rule]
                      (->> ledger
                           (filter #(and (= :governor-hold (:t %))
                                         (contains? (set (:basis %)) rule)))
                           (map :subject) distinct sort))]
    (for [rule (sort (keys by-rule))]
      (row (code (nm rule))
           (str "<span class=\"num\">" (count (get by-rule rule)) "</span>")
           (str/join " " (map code (subjects-of rule)))
           (esc (:detail (first (get by-rule rule))))))))

;; -- approval attribution, MEASURED --

(defn- approver-in
  "Any key on `m` whose name mentions an approver, with its value.
  Entries are sorted before scanning so the answer cannot depend on hash
  ordering."
  [m]
  (when (map? m)
    (->> m
         (map (fn [[k v]] [(nm k) v]))
         (sort-by first)
         (some (fn [[k v]] (when (str/includes? (str/lower-case k) "approv") [k v]))))))

(defn- artifacts-for
  "The SSoT artifacts this op actually wrote, read back out of the store
  by the store's own read API -- named so the page can say WHERE it
  looked, not merely what it concluded."
  [db op subject]
  (case op
    :sourcing/verify
    [["assessment-of" (store/assessment-of db subject)]]

    :delivery/dispatch
    (into [["telecom-order" (store/telecom-order db subject)]]
          (map (fn [r] ["dispatch-history" r])
               (filter #(= subject (get % "telecom_order_id")) (store/dispatch-history db))))

    :invoice/settle
    (into [["telecom-order" (store/telecom-order db subject)]]
          (map (fn [r] ["invoice-history" r])
               (filter #(= subject (get % "telecom_order_id")) (store/invoice-history db))))

    [["telecom-order" (store/telecom-order db subject)]]))

(defn- approval-measurement
  "For one `:approval-granted` graph fact, MEASURE whether the approver's
  identity survived into the SSoT. Not assumed either way: the answer
  differs by op inside this very repo, because
  `telecomtrade.store/commit-record!` destructures `:payload` for
  `:sourcing-assessment/set` (where `telecomtrade.operation`'s
  `:request-approval` node put `:approved-by`) but reconstructs the
  record from scratch for `:order/mark-dispatched` / `:order/mark-
  invoiced`, which never read `:payload` at all."
  [db {:keys [op subject by]}]
  (let [arts (artifacts-for db op subject)
        hit (first (keep (fn [[where m]]
                           (when-let [[k v] (approver-in m)] [where k v]))
                         arts))]
    {:op op :subject subject :by by
     :looked-in (mapv first arts)
     :found hit}))

(defn- approval-row [{:keys [op subject by looked-in found]}]
  (row (code (str op))
       (code subject)
       (esc by)
       (str/join " " (map code (distinct looked-in)))
       (if found
         (str (tag "ok" "retained") " "
              (code (str (second found) " = " (pr-str (nth found 2)))))
         (str (tag "warn" "audit fact only") " &mdash; not retained in "
              (str/join " / " (map code (distinct looked-in)))))))

;; -- registry drafts --

(defn- registry-row [r]
  (row (code (get r "record_id"))
       (code (get r "kind"))
       (code (get r "telecom_order_id"))
       (esc (get r "jurisdiction"))
       (if (get r "immutable") (tag "ok" "immutable") (tag "warn" "mutable"))))

;; -- catalogs --

(defn- jurisdiction-row [iso3]
  (let [{:keys [owner-authority legal-basis provenance required-evidence]} (facts/spec-basis iso3)]
    (row (code iso3)
         (esc owner-authority)
         (esc legal-basis)
         (str "<a href=\"" (esc provenance) "\">" (esc provenance) "</a>")
         (str/join "<br>" (map #(str "&middot; " (esc %)) required-evidence)))))

(defn- manufacturer-row [mfr]
  (let [{:keys [legal-basis owner-authority provenance category]} (facts/covered-manufacturer-basis mfr)]
    (row (esc mfr)
         (code (nm category))
         (esc legal-basis)
         (esc owner-authority)
         (str "<a href=\"" (esc provenance) "\">" (esc provenance) "</a>"))))

(defn- buyer-category-row [cat]
  (let [{:keys [restricted? legal-basis]} (get facts/buyer-category-basis cat)]
    (row (code (nm cat))
         (if (contains? facts/restricted-buyer-categories cat)
           (tag "critical" "restricted")
           (tag "ok" "not restricted"))
         (if restricted? (tag "critical" "true") (tag "muted" "false"))
         (esc legal-basis))))

;; -- ledger --

(defn- ledger-row [i {:keys [t op subject disposition basis violations confidence]}]
  (row (str "<span class=\"num\">" i "</span>")
       (case t
         :committed (tag "ok" (nm t))
         :governor-hold (tag "critical" (nm t))
         :approval-rejected (tag "warn" (nm t))
         (esc (nm t)))
       (code (str op))
       (code subject)
       (code (nm disposition))
       (str "<span class=\"num\">" (esc confidence) "</span>")
       (if (seq violations)
         (str/join "<br>" (map (fn [v] (str (code (nm (:rule v))) " " (esc (:detail v)))) violations))
         (esc (str/join " / " (map str basis))))))

;; ----------------------------- render -----------------------------

(defn render
  "Renders the whole console from a store that has already been driven
  by `run-demo!` (or any other real scenario) plus that scenario's graph
  runs. Reads only through the public store API and the real
  `telecomtrade.facts` / `telecomtrade.phase` / `telecomtrade.governor`
  vars -- there is no path by which a value on this page could have been
  typed here."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        orders (vec (store/all-telecom-orders db))
        trail (audit-trail runs)
        granted (filter #(= :approval-granted (:t %)) trail)
        requested (filter #(= :approval-requested (:t %)) trail)
        rejected (filter #(= :approval-rejected (:t %)) trail)
        measurements (mapv #(approval-measurement db %) granted)
        retained (filter :found measurements)
        dropped (remove :found measurements)
        holds (filter #(= :governor-hold (:t %)) ledger)
        hold-rules (sort (distinct (mapcat :basis holds)))
        committed (filter #(= :committed (:t %)) ledger)
        cov (facts/coverage)]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-4652 &middot; telecomtrade operator console</title><style>\n"
     (jp-go-dds.skin/dds+skin)
     "\n</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Wholesale of electronic and telecommunications equipment and parts (ISIC 4652) &mdash; Operator Console</h1>\n"
     "</header>\n"
     "<p class=\"subtitle\">Generated at build time by <code>telecomtrade.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) from one real run of "
     "<code>telecomtrade.operation</code> &rarr; <code>telecomtrade.governor</code> &rarr; "
     "<code>telecomtrade.store</code> against <code>telecomtrade.store/seed-db</code>. "
     "Every id, name, number, rule and verdict below was produced by that run. "
     "Deterministic: no timestamps, no random, byte-identical across reruns.</p>\n"
     "<p>"
     (tag "badge" (str (count orders) " seeded orders"))
     " " (tag "badge" (str (count runs) " actor threads"))
     " " (tag "badge" (str (count ledger) " ledger facts"))
     " " (tag "badge" (str (count committed) " committed"))
     " " (tag "badge" (str (count holds) " HARD governor holds"))
     " " (tag "badge" (str (count hold-rules) " distinct HARD rules"))
     " " (tag "badge" (str "phase " phase/default-phase " ("
                           (:label (get phase/phases phase/default-phase)) ")"))
     "</p>\n"
     "<main>\n"

     ;; 1
     (section "Telecom orders (SSoT after the run)"
              (str "All " (count orders) " orders from <code>telecomtrade.store/all-telecom-orders</code>, "
                   "after the scenario ran. Covered-list and restricted-buyer tags are evaluated live "
                   "against <code>telecomtrade.facts/covered-manufacturer?</code> and "
                   "<code>telecomtrade.facts/restricted-buyer-categories</code>; dispatch and invoice "
                   "numbers were assigned by <code>telecomtrade.registry</code> during the run.")
              (table ["Order" "Ref" "Equipment" "Manufacturer" "Counterparty / buyer category"
                      "Price" "Juris." "Credit / sanctions / contract" "Actuation" "Last ledger fact"]
                     (map (partial order-row ledger) orders)))

     ;; 2
     (section "Domain-defining check: covered manufacturer &and; restricted buyer category"
              (str "The governor's <code>covered-manufacturer-buyer-restricted</code> rule reads TWO "
                   "independent facts and ANDs them. Columns A and B are computed per order; the last "
                   "column reports whether this run's ledger actually contains that hold for the order. "
                   "The table is a truth table over real seed data, not a claim about the rule &mdash; "
                   "if the conjunction were ever weakened to a disjunction, rows with only one fact true "
                   "would start showing HELD here.")
              (table ["Order" "Manufacturer" "A: on covered list" "Buyer category"
                      "B: restricted category" "A &and; B" "Observed in this run"]
                     (map (partial conjunction-row ledger) orders)))

     ;; 3
     (section "Action gate"
              (str "Every cell below is <code>(telecomtrade.phase/gate phase {:op op} base)</code> "
                   "evaluated at render time &mdash; this section calls the policy rather than "
                   "describing it. The <em>governor HOLD</em> column feeds a hold in as the base "
                   "disposition to show that no phase can lift it. <em>Observed</em> lists the fact "
                   "types this run's ledger actually recorded for the op.")
              (table (concat ["Op"]
                             (for [ph (sort (keys phase/phases))]
                               (str "Phase " ph "<br><span class=\"muted\">"
                                    (esc (:label (get phase/phases ph))) "</span>"))
                             ["Governor HOLD at phase 3"
                              "In <code>governor/high-stakes</code>"
                              "Observed this run"])
                     (map (partial gate-row ledger) (sort-by str phase/write-ops))))

     ;; 4
     (section "HARD governor holds produced by this run"
              (str "Grouped from the run's own <code>:governor-hold</code> ledger facts. "
                   (count holds) " hold" (when (not= 1 (count holds)) "s") " across "
                   (count hold-rules) " distinct rules. HARD violations never reach a human approver "
                   "&mdash; <code>telecomtrade.phase/gate</code> keeps a hold a hold at every phase "
                   "(see the previous section's HOLD column). The detail string is the governor's own.")
              (table ["Rule" "Times fired" "Orders" "Governor detail (first occurrence)"]
                     (hold-rule-rows ledger)))

     ;; 5
     (section "Human approval gate &mdash; and what the SSoT retained"
              (str (count requested) " approval" (when (not= 1 (count requested)) "s")
                   " were requested by the actor pausing at <code>:request-approval</code> "
                   "(<code>interrupt-before</code>), " (count granted) " granted and "
                   (count rejected) " rejected. The last column is MEASURED, not assumed: after each "
                   "approved commit the store is read back through its own API and scanned for a "
                   "persisted approver key. "
                   (cond
                     (and (seq retained) (seq dropped))
                     (str "<strong>The answer is mixed in this repo</strong> &mdash; "
                          (count retained) " of " (count measurements) " approvals kept the approver, "
                          (count dropped) " did not, because "
                          "<code>telecomtrade.store/commit-record!</code> persists <code>:payload</code> "
                          "for <code>:sourcing-assessment/set</code> but rebuilds the record from "
                          "scratch for <code>:order/mark-dispatched</code> / "
                          "<code>:order/mark-invoiced</code>, which never read <code>:payload</code>. "
                          "Rows marked <em>audit fact only</em> mean the approval is provable from the "
                          "graph audit trail but not from the stored record.")
                     (seq retained)
                     (str "All " (count measurements) " approvals kept the approver in the stored record.")
                     :else
                     (str "None of the " (count measurements) " approvals kept the approver in the "
                          "stored record; each is provable only from the graph audit trail."))))

     "  <section class=\"card\">\n"
     "    <h2>Approvals in this run</h2>\n"
     (table ["Op" "Order" "Approver (graph audit)" "SSoT artifacts inspected" "Retained in SSoT?"]
            (map approval-row measurements))
     (if (seq rejected)
       (str "    <p class=\"muted\">Rejections (the human declining a governor-clean proposal):</p>\n"
            (table ["Op" "Order" "Basis" "Disposition"]
                   (map (fn [f] (row (code (str (:op f))) (code (:subject f))
                                     (code (str/join ", " (map nm (:basis f))))
                                     (tag "warn" "held &mdash; nothing committed")))
                        rejected)))
       "")
     "  </section>\n"

     ;; 6
     (section "Registry drafts written by this run"
              (str "Unsigned drafts built by <code>telecomtrade.registry</code>. Reference numbers are "
                   "jurisdiction-scoped sequences assigned by the store during the run &mdash; this "
                   "actor invents no external registry identifier and signs nothing.")
              (str "    <h3>Equipment dispatches</h3>\n"
                   (table ["Record" "Kind" "Order" "Jurisdiction" "Immutability"]
                          (map registry-row (store/dispatch-history db)))
                   "    <h3>Invoices</h3>\n"
                   (table ["Record" "Kind" "Order" "Jurisdiction" "Immutability"]
                          (map registry-row (store/invoice-history db)))))

     ;; 7
     (section "Jurisdiction spec-basis catalog"
              (str "<code>telecomtrade.facts/catalog</code>. A proposal that cites no official basis for "
                   "its jurisdiction is a HARD <code>:no-spec-basis</code> hold &mdash; requirements are "
                   "never invented for an unlisted jurisdiction. Coverage, reported by "
                   "<code>telecomtrade.facts/coverage</code>: " (:covered cov) " of " (:requested cov)
                   " requested. " (esc (:note cov)))
              (table ["ISO3" "Owner authority" "Legal basis" "Provenance" "Required evidence"]
                     (map jurisdiction-row (sort (keys facts/catalog)))))

     ;; 8
     (section "Covered-manufacturer named-entity list"
              (str "<code>telecomtrade.facts/covered-manufacturers</code> &mdash; a named-entity list, "
                   "not a technical classification of the equipment. Membership is exact-match on the "
                   "manufacturer recorded on the order; a manufacturer absent from this table is simply "
                   "not covered, never inferred.")
              (table ["Manufacturer" "Category" "Legal basis" "Owner authority" "Provenance"]
                     (map manufacturer-row (sort (keys facts/covered-manufacturers)))))

     ;; 9
     (section "Buyer-category scope"
              (str "Fact B of the conjunction. The governor tests membership in "
                   "<code>telecomtrade.facts/restricted-buyer-categories</code> directly; the "
                   "<code>:restricted?</code> column is the separate human-readable citation table "
                   "<code>telecomtrade.facts/buyer-category-basis</code>, shown beside it so the two "
                   "can be seen to agree.")
              (table ["Buyer category" "In restricted set (what the governor tests)"
                      "<code>:restricted?</code> in the citation table" "Legal basis"]
                     (map buyer-category-row (sort-by nm (keys facts/buyer-category-basis)))))

     ;; 10
     (section "Audit ledger (this run)"
              (str "The append-only decision log, exactly as "
                   "<code>telecomtrade.store/ledger</code> returns it &mdash; "
                   (count ledger) " facts in order. Only <code>:commit</code> and <code>:hold</code> "
                   "write here; advisor proposals and approval requests live in the graph's audit "
                   "channel and are summarised in the approvals section above.")
              (table ["#" "Fact" "Op" "Order" "Disposition" "Confidence" "Basis / violations"]
                     (map-indexed ledger-row ledger)))

     "</main>\n"
     "<footer>\n"
     "  <p class=\"muted\">cloud-itonami-isic-4652 &middot; TelecomTradeAdvisor &#8867; "
     "<code>:telecom-supply-chain-governor</code> &middot; regenerate with "
     "<code>clojure -M:dev:render-html</code>. Drafts on this page are unsigned; signature is the "
     "operator's act, not this actor's.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        holds (filter #(= :governor-hold (:t %)) ledger)
        committed (filter #(= :committed (:t %)) ledger)
        rules (distinct (mapcat :basis holds))]
    ;; Evidence floor. A console rendered from a scenario with no HARD
    ;; hold would show a governor that never says no; one with no commit
    ;; would show a governor that never says yes. Neither is this actor.
    (when (zero? (count holds))
      (throw (ex-info "no HARD governor hold in scenario - console would misrepresent the governor"
                      {:ledger-facts (count ledger)})))
    (when (zero? (count committed))
      (throw (ex-info "no committed fact in scenario - console would misrepresent the happy path"
                      {:ledger-facts (count ledger)})))
    (spit out (render result))
    (println "wrote" out
             "|" (count runs) "actor threads"
             "|" (count ledger) "ledger facts"
             "|" (count committed) "committed"
             "|" (count holds) "HARD holds over" (count rules) "rules:"
             (str/join ", " (sort (map name rules)))
             "|" (count (store/dispatch-history db)) "dispatch drafts"
             "|" (count (store/invoice-history db)) "invoice drafts")))
