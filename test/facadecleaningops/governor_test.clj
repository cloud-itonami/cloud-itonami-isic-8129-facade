(ns facadecleaningops.governor-test
  "Pure unit tests of `facadecleaningops.governor/check` against
  hand-built proposals -- the fast, focused complement to
  `actor_test.clj`'s full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [facadecleaningops.advisor :as adv]
            [facadecleaningops.governor :as gov]
            [facadecleaningops.store :as store]))

(def today 20260718)

(def site-1 {:site-id "site-1" :name "Shibuya Crossing District Office Tower Facade"
             :registered? true :verified? true :owner-consent? true})
(def site-3 {:site-id "site-3" :name "Downtown Municipal Building"
             :registered? true :verified? true :owner-consent? false})

(def zone-active {:zone/id "zone-active" :zone/name "Sample active zone"
                   :zone/status :active :zone/permit-required? true
                   :zone/max-hours [9 18] :zone/insurance-min-jpy 100000000
                   :zone/permit {:status :granted :expires 20270101}})
(def zone-suspended (assoc zone-active :zone/id "zone-suspended" :zone/status :suspended))
(def zone-expired-permit (assoc zone-active :zone/id "zone-expired-permit"
                                :zone/permit {:status :granted :expires 20260101}))
(def zone-no-permit-required (assoc zone-active :zone/id "zone-no-permit-required"
                                    :zone/permit-required? false :zone/permit nil))

(def fluid-1 {:fluid-id "fluid-1" :name "Neutral-pH Facade Glass Cleaner"
              :registered? true :verified? true :verified-surfaces #{"glass" "aluminum-cladding"}})
(def fluid-2-unverified {:fluid-id "fluid-2" :name "Unverified Bulk Import"
                          :registered? true :verified? false :verified-surfaces #{"concrete"}})

(defn- clean-proposal [op site-id zone-id]
  {:op op :site-id site-id :zone-id zone-id :summary "s" :rationale "routine facade-cleaning operation"
   :cites [site-id zone-id] :effect :propose :value {} :confidence 0.85})

(defn- with-fluid [proposal fluid-id surface]
  (assoc proposal :value {:fluid-id fluid-id :surface surface}))

(defn- check
  ([proposal s] (gov/check {} nil proposal s {:today today}))
  ([proposal s opts] (gov/check {} nil proposal s (merge {:today today} opts))))

;; ----------------------------- HARD check 1: site -----------------------------

(deftest site-unregistered-is-hard
  (testing "no site record at all -> HARD hold"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          verdict (check (clean-proposal :cleaning/assess "unknown-site" "zone-active") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:site-unverified} (map :rule (:violations verdict)))))))

(deftest site-owner-consent-missing-is-hard
  (testing "site registered+verified but owner-consent not obtained -> HARD hold"
    (let [s (store/mem-store {"site-3" site-3} {"zone-active" zone-active})
          verdict (check (clean-proposal :cleaning/assess "site-3" "zone-active") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:site-unverified} (map :rule (:violations verdict)))))))

(deftest site-verified-with-consent-is-not-hard-on-site-check
  (testing "a fully registered/verified/consented site never trips :site-unverified"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          verdict (check (clean-proposal :cleaning/assess "site-1" "zone-active") s)]
      (is (empty? (filter #(= :site-unverified (:rule %)) (:violations verdict)))))))

;; ----------------------------- HARD check 2: zone -----------------------------

(deftest zone-unregistered-is-hard
  (testing "no zone record at all -> HARD hold"
    (let [s (store/mem-store {"site-1" site-1} {})
          verdict (check (clean-proposal :cleaning/assess "site-1" "unknown-zone") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:zone-unverified} (map :rule (:violations verdict)))))))

(deftest zone-suspended-is-hard
  (testing "zone exists but :status is not :active -> HARD hold"
    (let [s (store/mem-store {"site-1" site-1} {"zone-suspended" zone-suspended})
          verdict (check (clean-proposal :cleaning/assess "site-1" "zone-suspended") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:zone-unverified} (map :rule (:violations verdict)))))))

(deftest zone-expired-permit-is-hard
  (testing "zone active but its public-right-of-way permit has expired as-of :today -> HARD hold"
    (let [s (store/mem-store {"site-1" site-1} {"zone-expired-permit" zone-expired-permit})
          verdict (check (clean-proposal :cleaning/assess "site-1" "zone-expired-permit") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:zone-permit-invalid} (map :rule (:violations verdict)))))))

(deftest zone-valid-permit-is-not-hard-on-zone-check
  (testing "an active zone with a currently valid permit never trips the zone check"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          verdict (check (clean-proposal :cleaning/assess "site-1" "zone-active") s)]
      (is (empty? (filter #(#{:zone-unverified :zone-permit-invalid} (:rule %)) (:violations verdict)))))))

(deftest zone-no-permit-required-never-trips-permit-check
  (testing "a zone with :zone/permit-required? false never trips :zone-permit-invalid even with no permit record"
    (let [s (store/mem-store {"site-1" site-1} {"zone-no-permit-required" zone-no-permit-required})
          verdict (check (clean-proposal :cleaning/assess "site-1" "zone-no-permit-required") s)]
      (is (empty? (filter #(#{:zone-unverified :zone-permit-invalid} (:rule %)) (:violations verdict)))))))

;; ----------------------------- HARD check 3: SDS scope -----------------------------

(deftest sds-fluid-missing-is-hard-when-value-implies-fluid-use
  (testing "an unknown fluid-id -> HARD hold"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active} {"fluid-1" fluid-1})
          verdict (check (with-fluid (clean-proposal :cleaning/assess "site-1" "zone-active") "unknown-fluid" "glass") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:sds-out-of-scope} (map :rule (:violations verdict)))))))

(deftest sds-fluid-unverified-is-hard
  (testing "a registered but unverified SDS record -> HARD hold"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active} {"fluid-2" fluid-2-unverified})
          verdict (check (with-fluid (clean-proposal :cleaning/assess "site-1" "zone-active") "fluid-2" "concrete") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:sds-out-of-scope} (map :rule (:violations verdict)))))))

(deftest sds-surface-out-of-verified-scope-is-hard
  (testing "a verified fluid used on a surface NOT in its verified-surfaces set -> HARD hold"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active} {"fluid-1" fluid-1})
          verdict (check (with-fluid (clean-proposal :cleaning/assess "site-1" "zone-active") "fluid-1" "asphalt") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:sds-out-of-scope} (map :rule (:violations verdict)))))))

(deftest sds-fluid-verified-in-scope-is-not-hard
  (testing "a verified fluid used on a surface within its verified-surfaces set never trips :sds-out-of-scope"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active} {"fluid-1" fluid-1})
          verdict (check (with-fluid (clean-proposal :cleaning/assess "site-1" "zone-active") "fluid-1" "glass") s)]
      (is (empty? (filter #(= :sds-out-of-scope (:rule %)) (:violations verdict)))))))

(deftest sds-check-only-applies-when-proposal-names-a-fluid
  (testing "proposals naming no :fluid-id at all (e.g. flag-safety-concern) never trip :sds-out-of-scope"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          verdict (check (clean-proposal :cleaning/flag-safety-concern "site-1" "zone-active") s)]
      (is (empty? (filter #(= :sds-out-of-scope (:rule %)) (:violations verdict)))))))

;; ----------------------------- HARD check 4: effect -----------------------------

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          verdict (check (assoc (clean-proposal :cleaning/assess "site-1" "zone-active") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

;; ----------------------------- HARD check 5: evidence -----------------------------

(deftest log-completion-without-evidence-is-hard
  (testing ":cleaning/log-completion with an empty/missing :evidence vector -> HARD hold"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          verdict (check (clean-proposal :cleaning/log-completion "site-1" "zone-active") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:evidence-missing} (map :rule (:violations verdict)))))))

(deftest log-completion-with-evidence-is-not-hard-on-evidence-check
  (testing ":cleaning/log-completion with a non-empty :evidence vector never trips :evidence-missing"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          proposal (assoc (clean-proposal :cleaning/log-completion "site-1" "zone-active")
                          :value {:evidence ["photo-ref-1"]})
          verdict (check proposal s)]
      (is (empty? (filter #(= :evidence-missing (:rule %)) (:violations verdict)))))))

(deftest evidence-check-is-scoped-to-log-completion-only
  (testing "non-log-completion ops never trip :evidence-missing"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})]
      (doseq [op [:cleaning/assess :cleaning/dispatch :cleaning/flag-safety-concern]]
        (let [verdict (check (clean-proposal op "site-1" "zone-active") s)]
          (is (empty? (filter #(= :evidence-missing (:rule %)) (:violations verdict)))
              (str "op " op " must never trip :evidence-missing")))))))

;; ----------------------------- scope exclusion -----------------------------

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          verdict (check (clean-proposal :cleaning/actuate-directly "site-1" "zone-active") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest direct-actuation-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches directly actuating the robot is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          poisoned (assoc (clean-proposal :cleaning/assess "site-1" "zone-active")
                          :rationale "directly actuated the robot to begin cleaning immediately"
                          :confidence 0.99)
          verdict (check poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest unverified-site-entry-content-is-hard
  (testing "a proposal touching entering the site without owner consent is HARD-blocked"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          poisoned (assoc (clean-proposal :cleaning/dispatch "site-1" "zone-active")
                          :summary "the robot entered the site without owner consent to save time")
          verdict (check poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest sds-override-content-is-hard
  (testing "a proposal touching overriding the SDS surface restriction is HARD-blocked"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active} {"fluid-1" fluid-1})
          poisoned (assoc (with-fluid (clean-proposal :cleaning/assess "site-1" "zone-active") "fluid-1" "glass")
                          :summary "overrode the SDS surface restriction to finish faster")
          verdict (check poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-safety-concern-is-not-scope-excluded
  (testing "flagging an observed pedestrian-safety/mechanical anomaly as a SAFETY CONCERN (not a direct-actuation/finalization action) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          concern (assoc (clean-proposal :cleaning/flag-safety-concern "site-1" "zone-active")
                         :value {:concern "pedestrian crossed the cordoned work area near the robot"})
          verdict (check concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (pedestrian/robot/safety) is exactly what this op exists to surface"))))

;; ----------------------------- SOFT escalation -----------------------------

(deftest dispatch-always-escalates-even-clean
  (testing ":cleaning/dispatch is always high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active} {"fluid-1" fluid-1})
          verdict (check (with-fluid (assoc (clean-proposal :cleaning/dispatch "site-1" "zone-active") :confidence 0.99)
                                     "fluid-1" "glass")
                         s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest safety-concern-always-escalates-even-clean
  (testing ":cleaning/flag-safety-concern is always high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          verdict (check (assoc (clean-proposal :cleaning/flag-safety-concern "site-1" "zone-active") :confidence 0.99) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-confidence-escalates
  (testing "confidence below the floor forces escalate even for an otherwise-auto-eligible op"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active})
          verdict (check (assoc (clean-proposal :cleaning/log-completion "site-1" "zone-active")
                                :value {:evidence ["photo-ref-1"]} :confidence 0.4)
                         s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest clean-high-confidence-assess-is-ok
  (testing "a fully clean, high-confidence :cleaning/assess (non-always-escalate op) is :ok?"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active} {"fluid-1" fluid-1})
          verdict (check (with-fluid (assoc (clean-proposal :cleaning/assess "site-1" "zone-active") :confidence 0.9)
                                     "fluid-1" "glass")
                         s)]
      (is (true? (:ok? verdict)))
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict))))))

;; ----------------------------- self-trip regression -----------------------------
;;
;; A known bug class in this actor fleet: the governor's own
;; scope-exclusion term list is sometimes phrased as a bare noun, which
;; then accidentally matches inside the mock advisor's own DEFAULT
;; rationale/disclaimer text for a legitimate, allowed proposal --
;; causing the actor to self-block its own happy path. Dedicated
;; regression test: every op the default mock advisor can generate, with
;; default (non-`out-of-scope?`) request patches, must NEVER trip
;; `:scope-excluded` or `:op-not-allowed`.
(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the default mock advisor's own proposals for every allowed op never trip the governor's scope-exclusion check"
    (let [s (store/mem-store {"site-1" site-1} {"zone-active" zone-active} {"fluid-1" fluid-1})]
      (doseq [op [:cleaning/assess :cleaning/dispatch :cleaning/log-completion :cleaning/flag-safety-concern]]
        (let [patch (cond
                      (= op :cleaning/log-completion) {:evidence ["photo-ref-1"]}
                      (#{:cleaning/assess :cleaning/dispatch} op) {:fluid-id "fluid-1" :surface "glass"}
                      :else {})
              proposal (adv/infer nil {:op op :site-id "site-1" :zone-id "zone-active" :patch patch})
              verdict (check proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must never self-trip :scope-excluded -- rationale/summary: "
                   (pr-str (select-keys proposal [:summary :rationale]))))
          (is (empty? (filter #(= :op-not-allowed (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must always be inside the closed op allowlist")))))))
