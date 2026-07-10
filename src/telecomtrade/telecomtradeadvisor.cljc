(ns telecomtrade.telecomtradeadvisor
  "TelecomTradeAdvisor client -- the *contained intelligence node* for
  the telecom/electronics-equipment-wholesale (ISIC 4652) actor.

  It normalizes telecom-order intake, drafts a per-jurisdiction GENERIC
  counterparty-diligence evidence checklist, drafts the equipment-
  dispatch action, and drafts the invoice-settlement action. CRITICAL:
  it is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record or a real
  dispatch/settlement. Every output is censored downstream by
  `telecomtrade.governor` before anything touches the SSoT, and
  `:delivery/dispatch`/`:invoice/settle` proposals NEVER auto-commit at
  any phase -- see README `Actuation`.

  The advisor MAY summarize what it believes an order's covered-
  manufacturer/buyer-category posture is (informationally, for a human
  reviewer's benefit, in `:rationale`) but this is NEVER what the
  governor actually checks: `telecomtrade.governor`'s `covered-
  manufacturer-buyer-restricted-violations` independently re-reads the
  order's own `:manufacturer`/`:buyer-category` ground truth directly
  against `telecomtrade.facts`' two catalogs, so a compromised or
  mistaken advisor citation can never substitute for the real facts --
  the SAME discipline the dual-use/export-classification sibling's
  advisor establishes for its own ECCN/license citation.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :delivery/dispatch | :invoice/settle | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [telecomtrade.facts :as facts]
            [telecomtrade.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the order-id, counterparty, manufacturer, buyer-
  category or any physical/commercial value. High confidence, low
  stakes."
  [_db {:keys [patch]}]
  {:summary    (str "電気通信機器卸売オーダー記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :order/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- verify-sourcing
  "Per-jurisdiction GENERIC counterparty-diligence evidence checklist
  draft (credit-clearance record, contract/PO, sanctions-screening
  record). `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `telecomtrade.facts` -- the Telecom Supply-Chain Governor must
  reject this (never invent a jurisdiction's requirements). The advisor
  ALSO summarizes the order's own reported covered-manufacturer/buyer-
  category posture informationally (for the human reviewer), but this
  is never what the governor's domain-defining check actually reads --
  see namespace docstring."
  [db {:keys [subject no-spec?]}]
  (let [eo (store/telecom-order db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction eo))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "telecomtrade.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :sourcing-assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案"
                        (when eo (str " / メーカー=" (:manufacturer eo)
                                      " (covered-list該当="
                                      (boolean (facts/covered-manufacturer? (:manufacturer eo))) ")")))
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :sourcing-assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- propose-dispatch
  "Draft the actual EQUIPMENT-DISPATCH action -- shipping real telecom/
  networking equipment to a counterparty. ALWAYS `:stake :delivery/
  dispatch` -- this is a REAL-WORLD act, never a draft the actor may
  auto-run. See README `Actuation`: no phase ever adds this op to a
  phase's `:auto` set (`telecomtrade.phase`); the governor also always
  escalates on `:delivery/dispatch`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [eo (store/telecom-order db subject)
        credit-ok? (and eo (true? (:credit-cleared? eo)))
        contract-ok? (and eo (some? (:contract-terms eo))
                          (not= "" (:contract-terms eo)))
        covered? (and eo (facts/covered-manufacturer? (:manufacturer eo)))
        restricted-buyer? (and eo (contains? facts/restricted-buyer-categories (:buyer-category eo)))
        sourcing-ok? (not (and covered? restricted-buyer?))
        sanctions-ok? (and eo (true? (:sanctions-screened? eo)))]
    {:summary    (str subject " 向け出荷提案"
                      (when eo (str " (counterparty=" (:counterparty eo)
                                    ", メーカー=" (:manufacturer eo)
                                    ", 買主区分=" (name (:buyer-category eo)) ")")))
     :rationale  (if eo
                   (str "credit-cleared?=" credit-ok?
                        " contract-on-file?=" contract-ok?
                        " covered-manufacturer?=" covered?
                        " restricted-buyer-category?=" restricted-buyer?
                        " sanctions-screened?=" sanctions-ok?)
                   "telecom-orderが見つかりません")
     :cites      (if eo [subject] [])
     :effect     :order/mark-dispatched
     :value      {:telecom-order-id subject}
     :stake      :delivery/dispatch
     :confidence (if (and credit-ok? contract-ok? sourcing-ok? sanctions-ok?) 0.9 0.3)}))

(defn- propose-invoice
  "Draft the actual INVOICE-SETTLEMENT action -- settling a real
  telecom/electronics-equipment-wholesale invoice (the money side of the
  trade, custody/financial transfer). ALWAYS `:stake :invoice/settle` --
  this is a REAL-WORLD act (real money moves between counterparty and
  wholesaler), never a draft the actor may auto-run. See README
  `Actuation`: no phase ever adds this op to a phase's `:auto` set
  (`telecomtrade.phase`); the governor also always escalates on
  `:invoice/settle`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [eo (store/telecom-order db subject)
        dispatched? (and eo (:dispatched? eo))
        sanctions-ok? (and eo (true? (:sanctions-screened? eo)))]
    {:summary    (str subject " 向け請求提案"
                      (when eo (str " (counterparty=" (:counterparty eo) ")")))
     :rationale  (if eo
                   (str "dispatched?=" dispatched?
                        " sanctions-screened?=" sanctions-ok?)
                   "telecom-orderが見つかりません")
     :cites      (if eo [subject] [])
     :effect     :order/mark-invoiced
     :value      {:telecom-order-id subject}
     :stake      :invoice/settle
     :confidence (if (and dispatched? sanctions-ok?) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :order/intake      (normalize-intake db request)
    :sourcing/verify   (verify-sourcing db request)
    :delivery/dispatch (propose-dispatch db request)
    :invoice/settle    (propose-invoice db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは電気通信機器・電子部品卸売事業者の出荷・請求エージェント"
       "の助言者です。与えられた事実のみに基づき、提案を1つだけEDNマップ"
       "で返します。説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:order/upsert|:sourcing-assessment/set|"
       ":order/mark-dispatched|:order/mark-invoiced) "
       ":stake(:delivery/dispatch か :invoice/settle か nil) "
       ":confidence(0..1)。\n"
       "重要: 登録されていない法域の電気通信機器サプライチェーン調達"
       "要件を絶対に創作してはいけません。spec-basisが無い場合は "
       ":cites を空にし confidence を上げないこと。"
       "取引先信用審査・契約有無・メーカーのcovered-list該当有無・"
       "買主区分・制裁スクリーニングの状態を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :sourcing/verify   {:telecom-order (store/telecom-order st subject)}
    :delivery/dispatch {:telecom-order (store/telecom-order st subject)}
    :invoice/settle    {:telecom-order (store/telecom-order st subject)}
    {:telecom-order (store/telecom-order st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Telecom Supply-Chain Governor
  escalates/holds -- an LLM hiccup can never auto-dispatch equipment or
  auto-settle an invoice."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :telecomtradeadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
