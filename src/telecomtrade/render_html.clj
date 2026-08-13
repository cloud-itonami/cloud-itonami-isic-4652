(ns telecomtrade.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo. Before this namespace
  existed, `docs/samples/operator-console.html` was a HAND-WRITTEN page
  committed in the initial commit -- no generator had ever produced it.
  It looked plausible (right ids, right vocabulary) but it was not
  evidence of anything: it even rendered `<button>Dispatch…</button>`
  action controls for a static file, and its `escalate`/`HOLD` verdicts
  were typed by a human rather than emitted by
  `telecomtrade.governor`. This namespace replaces it with a page whose
  every row is derived from a REAL run of the repo's own actor stack:

    telecomtrade.operation (langgraph StateGraph)
      -> telecomtrade.telecomtradeadvisor (contained advisor)
      -> telecomtrade.governor            (independent censor)
      -> telecomtrade.phase               (rollout gate)
      -> telecomtrade.store               (SSoT + append-only ledger)

  Nothing on the page is typed in by hand: the telecom-orders come from
  `telecomtrade.store/demo-data`, the verdicts and hold reasons from
  `telecomtrade.governor`, the dispatch/invoice reference numbers from
  `telecomtrade.registry`, the jurisdiction citations and the covered-
  manufacturer named-entity list from `telecomtrade.facts`, and the
  phase gate from `telecomtrade.phase/phases`. There are no timestamps
  in the output, so two consecutive runs are byte-identical.

  BUILD-TIME INVARIANT (not a comment -- see `-main`): the run MUST
  produce at least one `:governor-hold` fact, AND the set of HARD rules
  the run actually exercised MUST equal `scenario-hard-rules` below.
  A console that shows no HARD hold is not evidence that a governor
  exists, so `-main` throws rather than writing such a page.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.set :as set]
            [clojure.string :as str]
            [telecomtrade.facts :as facts]
            [telecomtrade.governor :as governor]
            [telecomtrade.phase :as phase]
            [telecomtrade.store :as store]
            [telecomtrade.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- scenario -----------------------------

(def ^:private supervisor
  "The requesting operator context at the default rollout phase."
  {:actor-id "op-1" :actor-role :trading-supervisor :phase phase/default-phase})

(def ^:private phase-1-context
  "The SAME operator, but running under the phase-1 (`assisted-intake`)
  rollout gate -- used once below so the `:order/upsert` commit effect
  is reached through a real human approval rather than a phase-3
  auto-commit. This is what makes the approver-retention probe able to
  ask its question about all four commit effects instead of three."
  (assoc supervisor :phase 1))

(def ^:private approver
  "The human who resumes the interrupted graph. Deliberately a DIFFERENT
  identity from `supervisor`'s `:actor-id`, so that the approver-
  retention probe below can distinguish 'the store kept the approver'
  from 'the store kept the requesting actor, which happens to share the
  same id'."
  "sup-1")

(def scenario-hard-rules
  "rule -> [subject, one-line reason] for every HARD governor rule this
  scenario is BUILT to fire. `-main` asserts the run's OBSERVED rule set
  equals exactly this key set -- so a governor check that stops firing
  (or a new one that starts) fails the build instead of silently
  changing the page. Every one of `telecomtrade.governor`'s eight HARD
  checks is represented."
  {:no-spec-basis
   ["eo-2" "ATL jurisdiction has no entry in telecomtrade.facts/catalog"]
   :evidence-incomplete
   ["eo-2" "dispatch attempted with no committed sourcing assessment on file"]
   :credit-uncleared
   ["eo-3" "counterparty credit-clearance not on file (:credit-cleared? false)"]
   :contract-missing
   ["eo-4" "no contract-terms recorded on the order (:contract-terms nil)"]
   :covered-manufacturer-buyer-restricted
   ["eo-6 / eo-9" "covered-list manufacturer AND restricted buyer category, both true at once"]
   :counterparty-sanctions-flag-unresolved
   ["eo-8" "OFAC/equivalent sanctions screening not passed (:sanctions-screened? false)"]
   :already-dispatched
   ["eo-1" "same telecom-order dispatched twice (:dispatched? already true)"]
   :already-invoiced
   ["eo-1" "same telecom-order invoiced twice (:invoiced? already true)"]})

(def ^:private conjunction-orders
  "The CONTROL QUAD that proves `:covered-manufacturer-buyer-restricted`
  is a genuine conjunction of two INDEPENDENT facts. Only the ids are
  named here -- every fact shown for them on the page is read back from
  the seeded store and from the run's own ledger."
  ["eo-5" "eo-6" "eo-7" "eo-9"])

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve!
  "Resume an interrupted run with a human approval, and return the
  `:approval-granted` audit fact enriched with the commit `:effect` the
  approved proposal carried -- the two pieces the approver-retention
  probe needs. Returns nil if this thread did not actually pause for
  approval (so the probe can never claim an approval that never
  happened)."
  [actor tid]
  (let [res (g/run* actor {:approval {:status :approved :by approver}}
                    {:thread-id tid :resume? true})
        granted (->> (get-in res [:state :audit])
                     (filter #(= :approval-granted (:t %)))
                     last)]
    (when granted
      (assoc granted :effect (get-in res [:state :proposal :effect])))))

(defn run-demo!
  "Runs a fresh seeded store through a scenario that exercises every
  disposition this actor can reach.

  Clean lifecycle: `eo-1` walks intake (auto-commits at phase 3 -- the
  only op in any phase's `:auto` set) -> sourcing verification
  (escalates, approved) -> equipment dispatch (ALWAYS escalates,
  approved) -> invoice settlement (ALWAYS escalates, approved).

  Control quad for the domain-defining check: `eo-5` (covered
  manufacturer, commercial-unrestricted buyer) and `eo-7` (non-covered
  manufacturer, federal-agency buyer) each hold exactly ONE of the two
  facts and BOTH dispatch cleanly; `eo-6` (covered manufacturer +
  federal-agency buyer) and `eo-9` (a different covered manufacturer +
  fcc-usf-funded-carrier buyer, GBR jurisdiction) hold BOTH facts at
  once and BOTH HARD-hold on the same rule.

  Remaining HARD holds: `eo-2` verification held for no spec-basis, then
  `eo-2` dispatch held for incomplete evidence (its verification never
  committed, so no assessment is on file); `eo-3` credit uncleared;
  `eo-4` no contract on file; `eo-8` sanctions screening not passed;
  `eo-1` dispatched and invoiced a second time.

  Returns {:db store :approvals [..]} -- `:approvals` are the real
  `:approval-granted` audit facts the graph emitted, which the
  approver-retention probe reads the store back against."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        approvals (atom [])
        approve! (fn [tid] (when-let [a (approve! actor tid)] (swap! approvals conj a)))
        verify! (fn [tid subject]
                  (exec! actor tid {:op :sourcing/verify :subject subject} supervisor)
                  (approve! tid))
        dispatch! (fn [tid subject]
                    (exec! actor tid {:op :delivery/dispatch :subject subject} supervisor))]

    ;; --- clean end-to-end lifecycle on eo-1 -------------------------------
    (exec! actor "eo1-intake"
           {:op :order/intake :subject "eo-1"
            :patch {:id "eo-1" :counterparty "Northbridge Network Integrators LLC"}}
           supervisor)
    (verify! "eo1-verify" "eo-1")
    (dispatch! "eo1-dispatch" "eo-1")
    (approve! "eo1-dispatch")
    (exec! actor "eo1-settle" {:op :invoice/settle :subject "eo-1"} supervisor)
    (approve! "eo1-settle")

    ;; --- control quad: the conjunction, proved in both directions ---------
    (verify! "eo5-verify" "eo-5")
    (dispatch! "eo5-dispatch" "eo-5")
    (approve! "eo5-dispatch")

    (verify! "eo6-verify" "eo-6")
    (dispatch! "eo6-dispatch" "eo-6")          ; HARD hold (both facts true)

    (verify! "eo7-verify" "eo-7")
    (dispatch! "eo7-dispatch" "eo-7")
    (approve! "eo7-dispatch")

    (verify! "eo9-verify" "eo-9")
    (dispatch! "eo9-dispatch" "eo-9")          ; HARD hold (both facts true)

    ;; --- eo-2: intake under the PHASE-1 gate, then two HARD holds ---------
    ;; Phase 1 enables `:order/intake` writes but auto-commits nothing, so
    ;; this run escalates on `:phase-approval` and commits `:order/upsert`
    ;; through a real approval -- the fourth commit effect the probe needs.
    (exec! actor "eo2-intake"
           {:op :order/intake :subject "eo-2"
            :patch {:id "eo-2" :counterparty "Atlantis Communications Ltd"}}
           phase-1-context)
    (approve! "eo2-intake")
    (exec! actor "eo2-verify" {:op :sourcing/verify :subject "eo-2"} supervisor)
    (dispatch! "eo2-dispatch" "eo-2")          ; HARD hold (no assessment on file)

    ;; --- one order per remaining failure mode -----------------------------
    (verify! "eo3-verify" "eo-3")
    (dispatch! "eo3-dispatch" "eo-3")          ; HARD hold (credit uncleared)

    (verify! "eo4-verify" "eo-4")
    (dispatch! "eo4-dispatch" "eo-4")          ; HARD hold (contract missing)

    (verify! "eo8-verify" "eo-8")
    (dispatch! "eo8-dispatch" "eo-8")          ; HARD hold (sanctions unresolved)

    ;; --- double-actuation guards -----------------------------------------
    (dispatch! "eo1-redispatch" "eo-1")        ; HARD hold (already dispatched)
    (exec! actor "eo1-resettle" {:op :invoice/settle :subject "eo-1"} supervisor)

    {:db db :approvals @approvals}))

;; ----------------------------- derived probes -----------------------------

(defn approver-retention
  "MEASURED AT RENDER TIME, never asserted: for every approval the graph
  actually granted in this run, read the SSoT back through the `Store`
  protocol and ask whether the approver identity survived the commit.

  This is deliberately a probe rather than a hardcoded note. The four
  commit effects take different paths through
  `telecomtrade.store/commit-record!` (some read the record's `:value`,
  some its `:payload`, some rebuild from `telecomtrade.registry` and
  read neither), so whether the approver is retrievable differs BY
  EFFECT -- and if the store is later changed, this page follows the
  change instead of continuing to publish a stale claim."
  [db approvals]
  (mapv (fn [{:keys [op subject by effect]}]
          (let [record (case effect
                         :order/upsert            (store/telecom-order db subject)
                         :sourcing-assessment/set (store/assessment-of db subject)
                         :order/mark-dispatched   (->> (store/dispatch-history db)
                                                       (filter #(= subject (get % "telecom_order_id")))
                                                       last)
                         :order/mark-invoiced     (->> (store/invoice-history db)
                                                       (filter #(= subject (get % "telecom_order_id")))
                                                       last)
                         nil)
                stored (when (map? record)
                         (or (get record :approved-by) (get record "approved-by")))]
            {:op op :subject subject :effect effect
             :audit-approver by
             :stored-approver stored
             :retained? (and (some? stored) (= stored by))}))
        approvals))

(defn ledger-retains-approver?
  "Does the append-only ledger itself carry an approver identity on any
  fact? Derived, not assumed."
  [db]
  (boolean (some #(or (:approved-by %) (:by %)) (store/ledger db))))

(defn- last-fact-for
  ([ledger subject] (last (filter #(= subject (:subject %)) ledger)))
  ([ledger subject op] (last (filter #(and (= subject (:subject %)) (= op (:op %))) ledger))))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- row [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" (str/join "\n" rows) "\n      </tbody>\n"
       "    </table>\n"))

(defn- section [title note body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when note (str "    <p class=\"muted\">" note "</p>\n"))
       body
       "  </section>\n"))

(defn- yes-no [b yes no]
  (if b (str "<span class=\"critical\">" yes "</span>")
        (str "<span class=\"ok\">" no "</span>")))

(defn- verdict-cell
  "The ledger's own last word on one (subject, op) pair. Never typed in."
  [fact]
  (cond
    (nil? fact) "<span class=\"muted\">not attempted</span>"
    (= :governor-hold (:t fact))
    (str "<span class=\"critical\">HARD hold</span> <code>"
         (esc (str/join ", " (map kw (:basis fact)))) "</code>")
    (= :committed (:t fact)) "<span class=\"ok\">committed</span>"
    :else (str "<span class=\"muted\">" (esc (kw (:t fact))) "</span>")))

;; ----------------------------- sections -----------------------------

(defn- conjunction-section [db ledger]
  (section
   "Domain-defining check &middot; <code>:covered-manufacturer-buyer-restricted</code>"
   (str "The rule ANDs two facts that come from two unrelated sources: whether the order's own "
        "<code>:manufacturer</code> is a named entity on <code>telecomtrade.facts/covered-manufacturers</code>, "
        "and whether the order's own <code>:buyer-category</code> is in "
        "<code>telecomtrade.facts/restricted-buyer-categories</code>. Neither fact alone blocks anything. "
        "The four rows below are a control quad from the real run: two orders hold exactly one of the "
        "two facts and dispatch cleanly, two hold both and are HARD-held on the same rule. "
        "Every cell is read back from the seeded store and this run's own ledger.")
   (table ["Order" "Manufacturer" "Covered list?" "Buyer category" "Restricted category?"
           "Both true?" "Dispatch outcome (from the ledger)"]
          (for [id conjunction-orders]
            (let [eo (store/telecom-order db id)
                  covered? (facts/covered-manufacturer? (:manufacturer eo))
                  restricted? (contains? facts/restricted-buyer-categories (:buyer-category eo))]
              (row (str "<code>" (esc id) "</code>")
                   (esc (:manufacturer eo))
                   (yes-no covered? "on covered list" "not listed")
                   (str "<code>" (esc (kw (:buyer-category eo))) "</code>")
                   (yes-no restricted? "restricted" "unrestricted")
                   (if (and covered? restricted?)
                     "<span class=\"critical\">BOTH</span>"
                     "<span class=\"ok\">one only</span>")
                   (verdict-cell (last-fact-for ledger id :delivery/dispatch))))))))

(defn- orders-section [db ledger]
  (section
   "Telecom-orders"
   (str "The complete seeded directory from <code>telecomtrade.store/demo-data</code>, read back "
        "through the <code>Store</code> protocol after the run. Dispatch and invoice reference "
        "numbers are the ones <code>telecomtrade.registry</code> actually minted; a blank means "
        "the actuation never committed.")
   (table ["Order" "Reference" "Equipment" "Type" "Manufacturer" "Counterparty" "Buyer category"
           "Jurisdiction" "Price" "Dispatched" "Invoiced" "Last governor word"]
          (for [eo (store/all-telecom-orders db)]
            (row (str "<code>" (esc (:id eo)) "</code>")
                 (esc (:order-id eo))
                 (esc (:equipment-description eo))
                 (str "<code>" (esc (kw (:equipment-type eo))) "</code>")
                 (str (esc (:manufacturer eo))
                      (when (facts/covered-manufacturer? (:manufacturer eo))
                        " <span class=\"tag\">covered list</span>"))
                 (esc (:counterparty eo))
                 (str "<code>" (esc (kw (:buyer-category eo))) "</code>"
                      (when (contains? facts/restricted-buyer-categories (:buyer-category eo))
                        " <span class=\"tag\">restricted</span>"))
                 (esc (:jurisdiction eo))
                 (str "<span class=\"num\">" (esc (:price eo)) "</span>")
                 (if (:dispatched? eo)
                   (str "<span class=\"ok\">" (esc (:dispatch-number eo)) "</span>")
                   "<span class=\"muted\">&mdash;</span>")
                 (if (:invoiced? eo)
                   (str "<span class=\"ok\">" (esc (:invoice-number eo)) "</span>")
                   "<span class=\"muted\">&mdash;</span>")
                 (verdict-cell (last-fact-for ledger (:id eo))))))))

(defn- holds-section [holds]
  (section
   "HARD governor holds in this run"
   (str "Every <code>:governor-hold</code> fact the run produced, with the governor's own violation "
        "text verbatim. A HARD hold is un-overridable: it never reaches the human approval node at "
        "all, so no operator sign-off can release it.")
   (table ["#" "Op" "Order" "Rule" "Governor's reason" "Advisor confidence"]
          (map-indexed
           (fn [i f]
             (row (str (inc i))
                  (str "<code>" (esc (kw (:op f))) "</code>")
                  (str "<code>" (esc (:subject f)) "</code>")
                  (str "<span class=\"critical\">" (esc (str/join ", " (map kw (:basis f)))) "</span>")
                  (esc (str/join " / " (map :detail (:violations f))))
                  (str "<span class=\"num\">" (esc (:confidence f)) "</span>")))
           holds))))

(defn- rule-coverage-section [holds]
  (let [observed (into #{} (mapcat :basis holds))]
    (section
     "HARD rule coverage"
     (str "The scenario is built to fire every one of <code>telecomtrade.governor</code>'s HARD checks. "
          "<code>-main</code> refuses to write this page unless the observed set equals the intended "
          "set exactly, so a check that silently stops firing fails the build instead of quietly "
          "disappearing from the console.")
     (table ["HARD rule" "Order that triggers it" "Why" "Observed in this run"]
            (for [[rule [subject why]] (sort-by (comp name key) scenario-hard-rules)]
              (row (str "<code>" (esc (kw rule)) "</code>")
                   (str "<code>" (esc subject) "</code>")
                   (esc why)
                   (if (contains? observed rule)
                     "<span class=\"ok\">fired</span>"
                     "<span class=\"critical\">NOT fired</span>")))))))

(defn- phase-gate-section []
  (section
   "Action gate"
   (str "Read directly out of <code>telecomtrade.phase/phases</code> and "
        "<code>telecomtrade.governor/high-stakes</code> &mdash; not a description of them. "
        "Two independent layers agree that a real equipment dispatch and a real invoice settlement "
        "are always a human trading supervisor's call: neither op is in ANY phase's <code>:auto</code> "
        "set, and the governor independently flags both as high-stakes.")
   (str
    (table ["Phase" "Label" "Writable ops" "May auto-commit when governor-clean"]
           (for [[n {:keys [label writes auto]}] (sort-by key phase/phases)]
             (row (str (if (= n phase/default-phase)
                         (str "<strong>" n "</strong> <span class=\"tag\">default</span>")
                         n))
                  (esc label)
                  (if (seq writes)
                    (str/join " " (map #(str "<code>" (esc (kw %)) "</code>") (sort-by kw writes)))
                    "<span class=\"muted\">none</span>")
                  (if (seq auto)
                    (str/join " " (map #(str "<code>" (esc (kw %)) "</code>") (sort-by kw auto)))
                    "<span class=\"muted\">none</span>"))))
    (table ["Op" "Auto-commit at the default phase?" "Governor high-stakes?" "Effective gate"]
           (let [{:keys [auto]} (get phase/phases phase/default-phase)]
             (for [o (sort-by kw phase/write-ops)]
               (let [auto? (contains? auto o)
                     stakes? (contains? governor/high-stakes o)]
                 (row (str "<code>" (esc (kw o)) "</code>")
                      (if auto? "<span class=\"ok\">yes</span>" "<span class=\"warn\">no</span>")
                      (if stakes? "<span class=\"critical\">yes</span>" "<span class=\"muted\">no</span>")
                      (cond
                        stakes? "<span class=\"critical\">always human approval &middot; never auto at any phase</span>"
                        auto? "<span class=\"ok\">auto-commit when governor-clean and confident</span>"
                        :else "<span class=\"warn\">human approval (not auto-eligible at this phase)</span>")))))))))

(defn- approver-section [db approvals]
  (let [probe (approver-retention db approvals)
        ledger? (ledger-retains-approver? db)
        retained (filter :retained? probe)
        dropped (remove :retained? probe)
        by-effect (fn [rows]
                    (str/join ", " (sort (distinct (map #(str "<code>" (esc (kw (:effect %))) "</code>") rows)))))]
    (section
     "Approver attribution (measured, not claimed)"
     (str "Each row below was produced by reading the SSoT back after the run and asking whether the "
          "approver identity is still there. <strong>This is derived at render time</strong>, so if "
          "<code>telecomtrade.store/commit-record!</code> is changed the page changes with it &mdash; "
          "a hardcoded verdict would become a lie the moment someone fixed the store. "
          "&ldquo;audit only&rdquo; means the graph really did record an approval "
          "(<code>:approval-granted</code>) but the committed record does not carry it, so a reader of "
          "the SSoT alone cannot tell &ldquo;nobody approved&rdquo; from &ldquo;the approver was not "
          "retained&rdquo;.")
     (str
      (table ["Op" "Order" "Commit effect" "Approver in the audit fact" "Approver read back from the store" "Attribution"]
             (for [{:keys [op subject effect audit-approver stored-approver retained?]} probe]
               (row (str "<code>" (esc (kw op)) "</code>")
                    (str "<code>" (esc subject) "</code>")
                    (str "<code>" (esc (kw effect)) "</code>")
                    (str "<code>" (esc audit-approver) "</code>")
                    (if stored-approver
                      (str "<code>" (esc stored-approver) "</code>")
                      "<span class=\"muted\">absent</span>")
                    (if retained?
                      "<span class=\"ok\">retained in record</span>"
                      "<span class=\"warn\">audit only &mdash; not retained in record</span>"))))
      "    <p class=\"muted\">"
      (format (str "Measured over %d approval%s in this run: %d retained (%s), %d audit-only (%s). "
                   "The append-only ledger itself %s an approver identity on any fact &mdash; only "
                   "<code>:committed</code> and <code>:governor-hold</code> facts are appended by "
                   "<code>telecomtrade.operation</code>, so <code>:approval-granted</code> lives in the "
                   "run's audit channel and is not persisted.")
              (count probe) (if (= 1 (count probe)) "" "s")
              (count retained) (if (seq retained) (by-effect retained) "none")
              (count dropped) (if (seq dropped) (by-effect dropped) "none")
              (if ledger? "carries" "does NOT carry"))
      "</p>\n"))))

(defn- ledger-section [ledger]
  (section
   "Audit ledger (this run)"
   (str "The complete append-only decision log from <code>telecomtrade.store/ledger</code>, in order. "
        "Every commit and every hold leaves exactly one fact.")
   (table ["#" "Fact" "Op" "Order" "Actor" "Basis / summary"]
          (map-indexed
           (fn [i {:keys [t op subject actor basis summary violations]}]
             (row (str (inc i))
                  (if (= :governor-hold t)
                    "<span class=\"critical\">governor-hold</span>"
                    "<span class=\"ok\">committed</span>")
                  (str "<code>" (esc (kw op)) "</code>")
                  (str "<code>" (esc subject) "</code>")
                  (str "<code>" (esc actor) "</code>")
                  (esc (if (= :governor-hold t)
                         (str/join " / " (map :detail violations))
                         (or summary (str/join ", " (map kw basis)))))))
           ledger))))

(defn- registry-section [db]
  (section
   "Registry drafts minted by this run"
   (str "Unsigned, jurisdiction-scoped draft records from "
        "<code>telecomtrade.registry</code>. The actor builds the record an operator would keep; "
        "signature and filing remain the operator's own act.")
   (str
    (table ["#" "Dispatch reference" "Order" "Jurisdiction" "Kind" "Immutable"]
           (map-indexed (fn [i r]
                          (row (str (inc i))
                               (str "<code>" (esc (get r "record_id")) "</code>")
                               (str "<code>" (esc (get r "telecom_order_id")) "</code>")
                               (esc (get r "jurisdiction"))
                               (esc (get r "kind"))
                               (if (get r "immutable") "<span class=\"ok\">yes</span>" "no")))
                        (store/dispatch-history db)))
    (table ["#" "Invoice reference" "Order" "Jurisdiction" "Kind" "Immutable"]
           (map-indexed (fn [i r]
                          (row (str (inc i))
                               (str "<code>" (esc (get r "record_id")) "</code>")
                               (str "<code>" (esc (get r "telecom_order_id")) "</code>")
                               (esc (get r "jurisdiction"))
                               (esc (get r "kind"))
                               (if (get r "immutable") "<span class=\"ok\">yes</span>" "no")))
                        (store/invoice-history db))))))

(defn- jurisdiction-section [db]
  (let [used (sort (distinct (map :jurisdiction (store/all-telecom-orders db))))
        cov (facts/coverage used)]
    (section
     "Jurisdiction spec-basis"
     (str "From <code>telecomtrade.facts/catalog</code>, with an honest coverage report over the "
          "jurisdictions this demo data actually uses. A jurisdiction with no entry has NO spec-basis "
          "&mdash; the advisor must not invent one and the governor holds if it tries "
          "(that is exactly what happens to <code>eo-2</code> above).")
     (str
      (table ["Jurisdiction" "In catalog?" "Owner authority" "Legal basis" "Required evidence"]
             (for [j used]
               (let [sb (facts/spec-basis j)]
                 (row (str "<code>" (esc j) "</code>")
                      (if sb "<span class=\"ok\">yes</span>" "<span class=\"critical\">no spec-basis</span>")
                      (esc (or (:owner-authority sb) "—"))
                      (esc (or (:legal-basis sb) "—"))
                      (if sb
                        (str/join "; " (map esc (:required-evidence sb)))
                        "<span class=\"muted\">&mdash;</span>")))))
      "    <p class=\"muted\">"
      (format "Coverage over the jurisdictions in play: %d of %d have an official spec-basis; missing: %s."
              (:covered cov) (:requested cov)
              (if (seq (:missing-jurisdictions cov))
                (str/join ", " (map #(str "<code>" (esc %) "</code>") (:missing-jurisdictions cov)))
                "none"))
      "</p>\n"))))

(defn- covered-list-section []
  (section
   "Covered-manufacturer named-entity list"
   (str "From <code>telecomtrade.facts/covered-manufacturers</code>. This is a NAMED-ENTITY list "
        "keyed by manufacturer, not a classification of the item &mdash; membership is exact-match, "
        "never guessed from a name.")
   (str
    (table ["Manufacturer" "Category" "Legal basis" "Provenance"]
           (for [[m {:keys [category legal-basis provenance]}] (sort-by key facts/covered-manufacturers)]
             (row (esc m)
                  (str "<code>" (esc (kw category)) "</code>")
                  (esc legal-basis)
                  (str "<a href=\"" (esc provenance) "\">" (esc provenance) "</a>"))))
    (table ["Buyer category" "Restricted?" "Legal basis"]
           (for [[c {:keys [restricted? legal-basis]}] (sort-by (comp name key) facts/buyer-category-basis)]
             (row (str "<code>" (esc (kw c)) "</code>")
                  (if restricted?
                    "<span class=\"critical\">yes &mdash; within reach of the restriction</span>"
                    "<span class=\"ok\">no</span>")
                  (esc legal-basis)))))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator console from a store `db` that has already
  run `run-demo!`, plus the approvals that run granted."
  [db approvals]
  (let [ledger (vec (store/ledger db))
        holds (filterv #(= :governor-hold (:t %)) ledger)
        commits (filterv #(= :committed (:t %)) ledger)]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-4652 &middot; telecomtrade &middot; Operator Console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Wholesale of electronic &amp; telecommunications equipment and parts (ISIC 4652) &mdash; Operator Console</h1>\n"
     "</header>\n"
     "<p class=\"subtitle\">Read-only sample. Generated at build time by <code>telecomtrade.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) from a real run of "
     "<code>telecomtrade.operation</code> &rarr; <code>telecomtrade.governor</code> &rarr; "
     "<code>telecomtrade.store</code>. No hand-written rows, no timestamps &mdash; reruns against the "
     "same seed are byte-identical.</p>\n"
     "<p><span class=\"badge\">" (count (store/all-telecom-orders db)) " telecom-orders</span> "
     "<span class=\"badge\">" (count ledger) " ledger facts</span> "
     "<span class=\"badge\">" (count commits) " commits</span> "
     "<span class=\"badge\">" (count holds) " HARD holds</span> "
     "<span class=\"badge\">" (count (into #{} (mapcat :basis holds))) " distinct HARD rules</span> "
     "<span class=\"badge\">" (count approvals) " human approvals</span></p>\n"
     "<main>\n"
     (conjunction-section db ledger)
     (orders-section db ledger)
     (holds-section holds)
     (rule-coverage-section holds)
     (phase-gate-section)
     (approver-section db approvals)
     (ledger-section ledger)
     (registry-section db)
     (jurisdiction-section db)
     (covered-list-section)
     "</main>\n"
     "<footer><p class=\"muted\">cloud-itonami-isic-4652 &middot; TelecomTradeAdvisor &#8891; "
     ":telecom-supply-chain-governor &middot; langgraph-clj StateGraph. "
     "Regenerate with <code>clojure -M:dev:render-html</code>.</p></footer>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db approvals]} (run-demo!)
        ledger (vec (store/ledger db))
        holds (filterv #(= :governor-hold (:t %)) ledger)
        observed (into #{} (mapcat :basis holds))
        intended (set (keys scenario-hard-rules))]

    ;; BUILD-TIME INVARIANT 1 -- a console with no HARD hold is not
    ;; evidence that a governor exists. Refuse to write one.
    (when (empty? holds)
      (throw (ex-info (str "render-html: the real governor produced ZERO :governor-hold facts. "
                           "Refusing to write a console that would show a governed actor with "
                           "nothing governed.")
                      {:ledger-facts (count ledger) :holds 0})))

    ;; BUILD-TIME INVARIANT 2 -- the scenario claims to exercise a
    ;; specific set of HARD rules. If reality disagrees in either
    ;; direction, fail rather than publish a page that quietly no longer
    ;; demonstrates what it says it demonstrates.
    (when-not (= observed intended)
      (throw (ex-info "render-html: observed HARD rule set does not match scenario-hard-rules."
                      {:observed (vec (sort observed))
                       :intended (vec (sort intended))
                       :missing (vec (sort (set/difference intended observed)))
                       :unexpected (vec (sort (set/difference observed intended)))})))

    ;; BUILD-TIME INVARIANT 3 -- at least one approval must have been
    ;; granted, otherwise the approver-attribution probe below would be
    ;; reporting on an empty set and read as "nothing to disclose".
    (when (empty? approvals)
      (throw (ex-info "render-html: the run granted ZERO approvals -- the approver-attribution probe would be vacuous."
                      {:approvals 0})))

    (spit out (render db approvals))
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count holds) " HARD holds over " (count observed) " distinct rules, "
                  (count approvals) " approvals, "
                  (count (store/dispatch-history db)) " dispatch drafts, "
                  (count (store/invoice-history db)) " invoice drafts)"))))
