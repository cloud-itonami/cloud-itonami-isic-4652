(ns telecomtrade.facts
  "Per-jurisdiction telecom-equipment supply-chain / national-security-
  sourcing regulatory catalog -- the G2-style spec-basis table the
  Telecom Supply-Chain Governor checks every `:sourcing/verify` proposal
  against ('did the advisor cite an OFFICIAL public source for THIS
  jurisdiction's telecom-supply-chain-sourcing regime, or did it invent
  one?') -- PLUS a second, independent catalog: `covered-manufacturers`,
  the named-entity list this vertical's domain-defining check actually
  reads.

  CRITICAL STRUCTURAL DIFFERENCE from every prior wholesale-trading
  sibling's own `facts` catalog (the dual-use/encryption-classification
  sibling `techtrade.facts` included): every prior sibling's catalog
  answers 'what does THIS ITEM technically classify as, and does that
  classification require a license for this destination' -- a
  technology-CLASSIFICATION question. This vertical's catalog answers a
  DIFFERENT question in kind: 'is this item's MANUFACTURER a NAMED
  ENTITY on a real government list, and if so, does the BUYER'S OWN
  CATEGORY put them within reach of the restriction that listing
  triggers' -- a supply-chain-trust / national-security-SOURCING
  question that has nothing to do with what the item technically is
  (an EAR99 router and a 5A002 encryption router are equally 'covered'
  if Huawei made either of them; conversely, an identical router made by
  Cisco or Nokia is never 'covered' no matter how it is classified).
  `covered-manufacturers` below is therefore NOT a classification list
  keyed by ECCN/HS-code -- it is a NAMED-ENTITY list keyed by
  manufacturer, structurally closer to a denied-party/entity list than a
  control list.

  Two REAL, current, well-documented US regimes anchor `covered-
  manufacturers`:

  - Section 889(f)(3) of the John S. McCain National Defense
    Authorization Act for Fiscal Year 2019 (Pub. L. 115-232) names FIVE
    entities (and their subsidiaries/affiliates) as 'covered
    telecommunications equipment or services' producers: Huawei
    Technologies Company, ZTE Corporation, Hytera Communications
    Corporation, Hangzhou Hikvision Digital Technology Company, and
    Dahua Technology Company. Section 889(a) (effective 13 Aug 2019)
    bars FEDERAL AGENCIES from procuring or obtaining covered equipment/
    services; Section 889(b) (effective 13 Aug 2020) separately bars
    FEDERAL CONTRACTORS and grant/loan recipients from using covered
    equipment/services as a substantial or essential component, or as
    critical technology, of any system -- implemented via FAR clause
    52.204-25.
  - The FCC's 'Covered List', maintained under the Secure and Trusted
    Communications Networks Act of 2019 (Pub. L. 116-124) and first
    published March 2021, separately designates communications
    equipment/services that pose an unacceptable national-security risk.
    The FCC's own November 2019/2020 Universal Service Fund (USF) order
    prohibits USF support from being used to purchase, obtain, maintain
    or support Covered List equipment/services -- reaching CARRIERS that
    receive federal USF subsidies (the same carriers the FCC's 'rip-and-
    replace' Secure and Trusted Communications Networks Reimbursement
    Program later funded the removal of already-installed covered gear
    from). A LATER, BROADER FCC rule (November 2022, adopted in the
    'Protecting Against National Security Threats to the Communications
    Networks or the Information and Communications Technology Supply
    Chain through the Equipment Authorization Program' proceeding)
    separately prohibits the FCC from AUTHORIZING any NEW Covered List
    equipment for import or sale in the United States AT ALL --
    regardless of buyer or funding source. This 2022 rule is real and
    cited here for context, but it is DELIBERATELY NOT modeled as this
    R0's buyer-category-gated HARD check (see `telecomtrade.governor`
    namespace docstring and `docs/adr/0001-architecture.md` Decision 4
    for why: it is a uniform, buyer-blind equipment-authorization/
    certification bar on the item ever lawfully entering the US market
    at all, not a per-order procurement restriction gated on WHO is
    buying -- a genuinely different regulatory lever, correctly scoped
    OUT of this R0 as a follow-up, the same 'extending coverage is
    additive' discipline every sibling's own out-of-scope items follow).

  `covered-manufacturers` and `restricted-buyer-categories` are TWO
  INDEPENDENT facts, deliberately not folded into one table cell: WHICH
  named entities are listed is a fact about the MANUFACTURER; WHICH
  buyer categories the restriction actually reaches is a fact about the
  REGULATION'S OWN SCOPE. `telecomtrade.governor`'s domain-defining
  check reads both independently and ANDs them -- see that namespace's
  docstring for the full reasoning on why this is a genuinely different
  check SHAPE from every prior sibling's own domain-defining check.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries. Likewise, a
  manufacturer not in `covered-manufacturers` is simply not covered --
  never guessed, never inferred from a name that merely sounds
  Chinese-owned or unfamiliar.")

;; ----------------------------- per-jurisdiction spec-basis -----------------------------

(def catalog
  "iso3 -> requirement map. `:required-evidence` is the GENERIC
  counterparty-diligence evidence set (credit-clearance record,
  contract/PO, sanctions-screening record) -- deliberately does NOT
  include a 'covered-manufacturer screening record' item: unlike those
  three (genuinely per-jurisdiction procedural requirements, the same
  shape regardless of what is being traded), covered-manufacturer/buyer-
  category status is its OWN dedicated governor check
  (`covered-manufacturer-buyer-restricted-violations`), not a checklist
  item -- see `telecomtrade.governor` namespace docstring.
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any `:sourcing/verify` proposal
  can commit."
  {"USA" {:name "USA"
          :owner-authority "Federal Communications Commission (FCC) Public Safety and Homeland Security Bureau; Federal Acquisition Regulatory (FAR) Council"
          :legal-basis "Secure and Trusted Communications Networks Act of 2019 (Pub. L. 116-124) -- FCC Covered List; Section 889 of the John S. McCain National Defense Authorization Act for Fiscal Year 2019 (Pub. L. 115-232), Parts A and B; FAR clause 52.204-25"
          :provenance "https://www.fcc.gov/supplychain/coveredlist"
          :required-evidence ["credit-clearance record"
                              "contract/PO"
                              "sanctions-screening (OFAC/equivalent) record"]}
   "GBR" {:name "GBR"
          :owner-authority "Department for Science, Innovation and Technology (DSIT); Office of Communications (Ofcom)"
          :legal-basis "Telecommunications (Security) Act 2021; designated vendor directions restricting high-risk-vendor equipment in UK public telecoms networks"
          :provenance "https://www.gov.uk/government/collections/telecoms-supply-chain-review"
          :required-evidence ["credit-clearance record"
                              "contract/PO"
                              "sanctions-screening (OFAC/equivalent) record"]}
   "DEU" {:name "DEU"
          :owner-authority "Bundesnetzagentur (Federal Network Agency); Bundesamt für Sicherheit in der Informationstechnik (BSI, Federal Office for Information Security)"
          :legal-basis "Telekommunikationsgesetz (TKG) § 167 -- Katalog von Sicherheitsanforderungen (catalogue of security requirements) and the Liste der kritischen Funktionen für öffentliche Telekommunikationsnetze und -dienste mit erhöhtem Gefährdungspotenzial (list of critical functions for public telecom networks/services with elevated risk potential), issued by the Bundesnetzagentur in agreement with BSI and the Bundesbeauftragte für den Datenschutz und die Informationsfreiheit; BSI-Gesetz (BSIG) § 2 Nr. 23 (definition of 'kritische Komponenten') and § 56 Abs. 6 (Rechtsverordnung empowerment for the Bundesministerium des Innern to designate critical components used in critical facilities/public telecom networks by statutory ordinance)"
          :provenance "https://www.bundesnetzagentur.de/DE/Fachthemen/Telekommunikation/OeffentlicheSicherheit/KatalogSicherheitsanforderungen/start.html"
          :required-evidence ["credit-clearance record"
                              "contract/PO"
                              "sanctions-screening (OFAC/equivalent) record"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to dispatch telecom
  equipment or settle an invoice on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions
  actually have a spec-basis entry. Never report a missing jurisdiction
  as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-4652 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `telecomtrade.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements. This "
                 "regime is unusually US-centric among currently well-"
                 "documented, named-manufacturer telecom-sourcing "
                 "restrictions -- see docs/business-model.md "
                 "'Jurisdiction coverage (honest)'.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

;; ----------------------------- covered-manufacturer named-entity list -----------------------------

(def covered-manufacturers
  "manufacturer name (as recorded on the telecom-order, exact match) ->
  covered-list citation map. THIS IS NOT A CLASSIFICATION OF THE ITEM --
  it is a NAMED-ENTITY list. The five entries below are the entities
  named at Section 889(f)(3) of the NDAA FY2019 (Pub. L. 115-232) --
  every one of them is also independently on the FCC's Covered List
  (Secure and Trusted Communications Networks Act of 2019). Three
  (Huawei, ZTE, Hytera) are core telecom/radio EQUIPMENT manufacturers
  squarely within this vertical's scope (routers, switches, base
  stations, two-way radios); Hikvision and Dahua are video-surveillance-
  equipment manufacturers, included here because they are named at the
  SAME statutory provision and a telecom/electronics wholesaler may
  carry network-attached surveillance gear as part of a mixed order --
  `:category` distinguishes them for an operator's own reporting, but
  the governor's check does not gate on `:category`, only on list
  membership. A manufacturer NOT in this table is simply not covered --
  never guessed, never inferred from a name that merely sounds
  unfamiliar or foreign-owned."
  {"Huawei Technologies Company"
   {:legal-basis "Section 889(f)(3)(A), NDAA FY2019 (Pub. L. 115-232); FCC Covered List"
    :owner-authority "U.S. Congress (NDAA FY2019 §889(f)(3)); FCC Public Safety and Homeland Security Bureau"
    :provenance "https://www.fcc.gov/supplychain/coveredlist"
    :category :telecom-equipment}
   "ZTE Corporation"
   {:legal-basis "Section 889(f)(3)(B), NDAA FY2019 (Pub. L. 115-232); FCC Covered List"
    :owner-authority "U.S. Congress (NDAA FY2019 §889(f)(3)); FCC Public Safety and Homeland Security Bureau"
    :provenance "https://www.fcc.gov/supplychain/coveredlist"
    :category :telecom-equipment}
   "Hytera Communications Corporation"
   {:legal-basis "Section 889(f)(3)(C), NDAA FY2019 (Pub. L. 115-232); FCC Covered List"
    :owner-authority "U.S. Congress (NDAA FY2019 §889(f)(3)); FCC Public Safety and Homeland Security Bureau"
    :provenance "https://www.fcc.gov/supplychain/coveredlist"
    :category :radio-equipment}
   "Hangzhou Hikvision Digital Technology Company"
   {:legal-basis "Section 889(f)(3)(D), NDAA FY2019 (Pub. L. 115-232); FCC Covered List"
    :owner-authority "U.S. Congress (NDAA FY2019 §889(f)(3)); FCC Public Safety and Homeland Security Bureau"
    :provenance "https://www.fcc.gov/supplychain/coveredlist"
    :category :video-surveillance-equipment}
   "Dahua Technology Company"
   {:legal-basis "Section 889(f)(3)(E), NDAA FY2019 (Pub. L. 115-232); FCC Covered List"
    :owner-authority "U.S. Congress (NDAA FY2019 §889(f)(3)); FCC Public Safety and Homeland Security Bureau"
    :provenance "https://www.fcc.gov/supplychain/coveredlist"
    :category :video-surveillance-equipment}})

(defn covered-manufacturer?
  "Is `manufacturer` a named entity on the covered list? A blank/nil
  manufacturer is never covered (it is a data-quality problem, not a
  restriction -- `telecomtrade.governor` has no dedicated check for a
  missing manufacturer name in this R0)."
  [manufacturer]
  (contains? covered-manufacturers manufacturer))

(defn covered-manufacturer-basis
  "The covered-list citation for `manufacturer`, or nil."
  [manufacturer]
  (get covered-manufacturers manufacturer))

;; ----------------------------- buyer-category scope -----------------------------

(def restricted-buyer-categories
  "Buyer categories the covered-manufacturer restriction actually
  reaches -- the SECOND independent fact this vertical's domain-defining
  check ANDs against `covered-manufacturer?`. See
  `telecomtrade.governor/covered-manufacturer-buyer-restricted-
  violations` for the full reasoning. `:commercial-unrestricted` is
  DELIBERATELY excluded: Section 889 is a federal-procurement/federal-
  funding-NEXUS statute, not a general trade ban -- a purely private
  commercial sale with no federal agency, federal contract/grant, or
  FCC-USF-funding nexus is outside every regime cited in
  `covered-manufacturers`' entries."
  #{:federal-agency :federal-contractor :fcc-usf-funded-carrier})

(def buyer-category-basis
  "buyer-category -> {:restricted? bool :legal-basis str}, for the
  advisor's own rationale/citation text and for an operator's reporting.
  The governor's actual HARD check reads `restricted-buyer-categories`
  directly (a set membership test) -- this map exists for
  human-readable citation, not as a second source of truth the check
  itself depends on."
  {:federal-agency
    {:restricted? true
     :legal-basis "Section 889(a), NDAA FY2019 (Pub. L. 115-232) -- federal executive agencies barred from procuring or obtaining covered telecommunications equipment/services, effective 13 Aug 2019"}
   :federal-contractor
    {:restricted? true
     :legal-basis "Section 889(b), NDAA FY2019 (Pub. L. 115-232) -- federal contractors and grant/loan recipients barred from using covered equipment/services as a substantial or essential component, or as critical technology, of any system, effective 13 Aug 2020; implemented via FAR clause 52.204-25"}
   :fcc-usf-funded-carrier
    {:restricted? true
     :legal-basis "FCC Report and Order, In re Protecting Against National Security Threats to the Communications Supply Chain (Nov. 2019) -- Universal Service Fund (USF) support may not be used to purchase, obtain, maintain or support covered equipment/services; Secure and Trusted Communications Networks Reimbursement Program ('rip-and-replace')"}
   :commercial-unrestricted
    {:restricted? false
     :legal-basis "No federal-agency, federal-contract/grant, or FCC-USF-funding nexus -- Section 889 and the FCC's USF rule do not reach a purely private commercial transaction"}})
