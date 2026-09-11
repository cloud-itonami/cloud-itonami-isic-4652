(ns telecomtrade.facts-test
  (:require [clojure.test :refer [deftest is]]
            [telecomtrade.facts :as facts]))

(deftest usa-has-a-spec-basis
  (is (some? (facts/spec-basis "USA")))
  (is (string? (:provenance (facts/spec-basis "USA"))))
  (is (string? (:legal-basis (facts/spec-basis "USA")))))

(deftest both-seeded-jurisdictions-have-required-evidence
  (doseq [iso3 ["USA" "GBR"]]
    (is (seq (facts/evidence-checklist iso3)) (str iso3 " required-evidence"))
    (is (string? (:legal-basis (facts/spec-basis iso3))) (str iso3 " legal-basis"))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["USA" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "USA"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "USA")]
    (is (facts/required-evidence-satisfied? "USA" all))
    (is (not (facts/required-evidence-satisfied? "USA" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))

(deftest evidence-checklist-deliberately-excludes-covered-manufacturer-status
  ;; unlike a hypothetical checklist that folds manufacturer/buyer-category
  ;; status into the generic per-jurisdiction evidence set, this vertical's
  ;; covered-manufacturer/buyer-category determination is its OWN dedicated
  ;; governor check, not a checklist item -- see telecomtrade.governor.
  (doseq [iso3 ["USA" "GBR"]]
    (is (not-any? #(re-find #"(?i)covered|manufacturer" %) (facts/evidence-checklist iso3))
        (str iso3 " checklist should not mention covered-manufacturer status"))))

;; ----------------------------- covered-manufacturers -----------------------------

(deftest named-covered-manufacturers-are-covered
  (doseq [m ["Huawei Technologies Company"
             "ZTE Corporation"
             "Hytera Communications Corporation"
             "Hangzhou Hikvision Digital Technology Company"
             "Dahua Technology Company"]]
    (is (true? (facts/covered-manufacturer? m)) (str m " should be covered"))
    (is (some? (facts/covered-manufacturer-basis m)) (str m " should have a citation"))))

(deftest ordinary-manufacturers-are-not-covered
  (doseq [m ["Cisco Systems, Inc." "Nokia Corporation" "Motorola Solutions, Inc." "Ericsson AB"]]
    (is (false? (facts/covered-manufacturer? m)) (str m " should NOT be covered"))
    (is (nil? (facts/covered-manufacturer-basis m)))))

(deftest blank-or-nil-manufacturer-is-not-covered
  (is (false? (facts/covered-manufacturer? nil)))
  (is (false? (facts/covered-manufacturer? ""))))

;; ----------------------------- restricted-buyer-categories -----------------------------

(deftest restricted-buyer-categories-are-exactly-the-federal-and-usf-nexus-categories
  (is (= #{:federal-agency :federal-contractor :fcc-usf-funded-carrier}
         facts/restricted-buyer-categories)))

(deftest commercial-unrestricted-is-not-a-restricted-buyer-category
  (is (not (contains? facts/restricted-buyer-categories :commercial-unrestricted))))

(deftest buyer-category-basis-covers-every-category-with-a-restricted-flag
  (doseq [cat [:federal-agency :federal-contractor :fcc-usf-funded-carrier :commercial-unrestricted]]
    (let [entry (get facts/buyer-category-basis cat)]
      (is (some? entry) (str cat " should have a basis entry"))
      (is (boolean? (:restricted? entry)))
      (is (string? (:legal-basis entry)))))
  (is (true? (:restricted? (get facts/buyer-category-basis :federal-agency))))
  (is (true? (:restricted? (get facts/buyer-category-basis :federal-contractor))))
  (is (true? (:restricted? (get facts/buyer-category-basis :fcc-usf-funded-carrier))))
  (is (false? (:restricted? (get facts/buyer-category-basis :commercial-unrestricted)))))
