(ns facadecleaningops.actor-test
  "Integration tests: full OperationActor graph exercising the governor's
  hard checks, escalation logic, phase gating, and audit trail --
  including the safety-critical live-store re-verification at resume."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [facadecleaningops.advisor :as advisor]
            [facadecleaningops.store :as store]
            [facadecleaningops.operation :as op]))

(defn exec-request [actor tid request ctx]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn resume-approval [actor tid status]
  (g/run* actor {:approval {:status status :by "zone-compliance-officer"}} {:thread-id tid :resume? true}))

;; ----------------------------- (1) clean intake -> commit -----------------------------

(deftest assess-full-flow-auto-commits-at-phase-3
  (testing "clean :cleaning/assess proposal -> auto-commit at phase 3"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-1" :phase 3 :today 20260718}
          result (exec-request actor "t1"
                               {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {:fluid-id "fluid-1" :surface "glass"}}
                               ctx)]
      (is (some? result))
      (is (= :done (:status result)))
      (is (> (count (store/ledger db)) 0)
          "commit must append audit facts to ledger")
      (is (> (count (store/all-dispatch-records db)) 0)
          "commit must append record to the dispatch/log directory")
      (is (some #(= :committed (:t %)) (store/ledger db))))))

(deftest log-completion-with-evidence-auto-commits-at-phase-3
  (testing "clean :cleaning/log-completion WITH evidence -> auto-commit at phase 3"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-1b" :phase 3 :today 20260718}
          result (exec-request actor "t1b"
                               {:op :cleaning/log-completion :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {:evidence ["photo-ref-1" "sensor-log-ref-1"]}}
                               ctx)]
      (is (some? result))
      (is (> (count (store/all-dispatch-records db)) 0)))))

;; ----------------------------- (2) hard-hold path -----------------------------

(deftest unverified-site-hard-hold
  (testing "site with owner-consent not obtained -> permanent HARD hold, never escalates"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2" :phase 3 :today 20260718}
          result (exec-request actor "t2"
                               {:op :cleaning/assess :site-id "site-3" :zone-id "shibuya-ku-row"
                                :patch {:fluid-id "fluid-1" :surface "glass"}}
                               ctx)]
      (is (some? result))
      (is (= :done (:status result)) "HARD hold finishes the graph, it does not interrupt")
      (is (= 0 (count (store/all-dispatch-records db)))
          "HARD hold must never commit")
      (is (some #(= :governor-hold (:t %)) (store/ledger db))))))

(deftest zone-inactive-hard-hold
  (testing "deployment-zone not :active -> permanent HARD hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2b" :phase 3 :today 20260718}
          result (exec-request actor "t2b"
                               {:op :cleaning/assess :site-id "site-1" :zone-id "suspended-zone"
                                :patch {:fluid-id "fluid-1" :surface "glass"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/all-dispatch-records db)))))))

(deftest zone-expired-permit-hard-hold
  (testing "deployment-zone permit expired as-of :today -> permanent HARD hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2c" :phase 3 :today 20260718}
          result (exec-request actor "t2c"
                               {:op :cleaning/assess :site-id "site-1" :zone-id "expired-permit-zone"
                                :patch {:fluid-id "fluid-1" :surface "glass"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/all-dispatch-records db)))))))

(deftest sds-out-of-scope-hard-hold
  (testing "verified fluid used on a surface outside its verified-surfaces scope -> permanent HARD hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2d" :phase 3 :today 20260718}
          result (exec-request actor "t2d"
                               {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {:fluid-id "fluid-1" :surface "asphalt"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/all-dispatch-records db)))))))

(deftest log-completion-without-evidence-hard-hold
  (testing ":cleaning/log-completion with no evidence -> permanent HARD hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2e" :phase 3 :today 20260718}
          result (exec-request actor "t2e"
                               {:op :cleaning/log-completion :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/all-dispatch-records db)))))))

(deftest effect-not-propose-hard-hold
  (testing "proposal with :effect :commit (not :propose) -> hard hold"
    (let [db (store/seed-db)
          bad-advisor (reify advisor/Advisor
                        (-advise [_ _ req]
                          (assoc (advisor/infer nil req) :effect :commit)))
          actor (op/build db {:advisor bad-advisor})
          ctx {:actor-id "test-2f" :phase 3 :today 20260718}
          result (exec-request actor "t2f"
                               {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {:fluid-id "fluid-1" :surface "glass"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/all-dispatch-records db)))))))

(deftest scope-excluded-content-hard-hold
  (testing "proposal drifting into direct-actuation/unverified-access scope -> permanent hard hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2g" :phase 3 :today 20260718}
          result (exec-request actor "t2g"
                               {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                                :out-of-scope? true
                                :patch {:fluid-id "fluid-1" :surface "glass"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/all-dispatch-records db)))))))

;; ----------------------------- (3) escalate -> approve -----------------------------

(deftest dispatch-escalates-then-approve-commits
  (testing ":cleaning/dispatch always escalates, human approval -> commits"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-3" :phase 3 :today 20260718}
          result (exec-request actor "t3"
                               {:op :cleaning/dispatch :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {:fluid-id "fluid-1" :surface "glass" :window "09:00-12:00"}}
                               ctx)]
      (is (= :interrupted (:status result)) "dispatch must interrupt for human approval, never auto-commit")
      (is (= 0 (count (store/all-dispatch-records db))))
      (let [r2 (resume-approval actor "t3" :approved)]
        (is (= :done (:status r2)))
        (is (> (count (store/all-dispatch-records db)) 0)
            "after approval, dispatch must be committed")
        (is (some #(= :approval-granted (:t %)) (get-in r2 [:state :audit]))
            "the approval-granted audit event is carried in this run's transient audit trace (the :commit node persists its own :committed fact to store/ledger separately)")))))

(deftest safety-concern-escalates-then-approve-commits
  (testing ":cleaning/flag-safety-concern always escalates, human approval -> commits"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-3b" :phase 3 :today 20260718}
          result (exec-request actor "t3b"
                               {:op :cleaning/flag-safety-concern :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {:concern "pedestrian crossed the cordoned work area" :confidence 0.95}}
                               ctx)]
      (is (= :interrupted (:status result)))
      (is (= 0 (count (store/all-dispatch-records db))))
      (resume-approval actor "t3b" :approved)
      (is (> (count (store/all-dispatch-records db)) 0)))))

;; ----------------------------- (4) escalate -> reject -----------------------------

(deftest dispatch-escalates-then-reject-holds
  (testing ":cleaning/dispatch escalates, human REJECTS -> permanent hold, never commits"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-4" :phase 3 :today 20260718}
          result (exec-request actor "t4"
                               {:op :cleaning/dispatch :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {:fluid-id "fluid-1" :surface "glass" :window "09:00-12:00"}}
                               ctx)]
      (is (= :interrupted (:status result)))
      (let [r2 (resume-approval actor "t4" :rejected)]
        (is (= :done (:status r2)))
        (is (= 0 (count (store/all-dispatch-records db)))
            "rejected escalation must never commit")
        (is (some #(= :approval-rejected (:t %)) (store/ledger db)))))))

;; ----------------------------- (5) SAFETY-CRITICAL: live re-verification at resume -----------------------------
;;
;; Every sibling actor read while building this repo computes the
;; governor verdict once, before the interrupt, and trusts it at resume
;; time -- meaning a fact that changes DURING the human-review window
;; (a permit expiring, consent being revoked, a fresh safety signal
;; arriving) is silently bypassed if the human approves without knowing
;; about it. facadecleaningops.operation's :request-approval node
;; re-runs governor/check against the LIVE store before honoring the
;; human's decision. This is the single most safety-critical behavior
;; in this repo.

(deftest stale-verdict-does-not-bypass-fresh-hard-violation-at-resume--zone-permit-expires
  (testing "a dispatch that was clean at intake, but whose deployment-zone permit EXPIRES during the human-review window, must still HOLD at resume even though the human approves"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-5" :phase 3 :today 20260718}
          result (exec-request actor "t5"
                               {:op :cleaning/dispatch :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {:fluid-id "fluid-1" :surface "glass" :window "09:00-12:00"}}
                               ctx)]
      (is (= :interrupted (:status result)) "clean dispatch must interrupt for approval, same as the baseline dispatch test")
      ;; During the human-review window, the zone's permit expires.
      (store/set-zone! db "shibuya-ku-row"
                       (assoc-in (store/zone db "shibuya-ku-row") [:zone/permit :expires] 20260101))
      ;; The human approves anyway, unaware the fact changed underneath them.
      (let [r2 (resume-approval actor "t5" :approved)]
        (is (= :done (:status r2)))
        (is (= 0 (count (store/all-dispatch-records db)))
            "a fresh HARD violation discovered at resume must block commit, REGARDLESS of the human's :approved decision")
        (is (some #(= :approval-superseded-by-fresh-hold (:t %)) (store/ledger db))
            "the ledger must record that this was a stale-verdict supersession, not an ordinary rejection")))))

(deftest stale-verdict-does-not-bypass-fresh-hard-violation-at-resume--site-consent-revoked
  (testing "a dispatch that was clean at intake, but whose site owner-consent is REVOKED during the human-review window, must still HOLD at resume even though the human approves"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-5b" :phase 3 :today 20260718}
          result (exec-request actor "t5b"
                               {:op :cleaning/dispatch :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {:fluid-id "fluid-1" :surface "glass" :window "09:00-12:00"}}
                               ctx)]
      (is (= :interrupted (:status result)))
      (store/set-site! db "site-1" (assoc (store/site db "site-1") :owner-consent? false))
      (let [r2 (resume-approval actor "t5b" :approved)]
        (is (= :done (:status r2)))
        (is (= 0 (count (store/all-dispatch-records db)))
            "revoked owner-consent discovered at resume must block commit, REGARDLESS of the human's :approved decision")))))

(deftest verdict-not-stale-when-nothing-changed--sanity-check
  (testing "sanity check: when NOTHING changes between interrupt and resume, live re-verification agrees with the original verdict and approval still commits normally (this is the baseline the two tests above are contrasted against)"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-5c" :phase 3 :today 20260718}
          result (exec-request actor "t5c"
                               {:op :cleaning/dispatch :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {:fluid-id "fluid-1" :surface "glass" :window "09:00-12:00"}}
                               ctx)]
      (is (= :interrupted (:status result)))
      (let [r2 (resume-approval actor "t5c" :approved)]
        (is (= :done (:status r2)))
        (is (> (count (store/all-dispatch-records db)) 0)
            "when the live store still agrees with the pre-interrupt verdict, approval commits normally")))))

;; ----------------------------- (6) missing-phase defaults to conservative -----------------------------

(deftest missing-phase-defaults-to-conservative-not-permissive
  (testing "context with NO :phase key at all must be gated as the conservative default (phase 1: assisted, always human approval) -- NOT the most permissive tier. A :cleaning/log-completion proposal that would auto-commit at phase 3 must instead escalate when :phase is simply omitted."
    (let [db (store/seed-db)
          actor (op/build db)
          ctx-no-phase {:actor-id "test-6" :today 20260718}  ; deliberately NO :phase key
          result (exec-request actor "t6"
                               {:op :cleaning/log-completion :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {:evidence ["photo-ref-1"]}}
                               ctx-no-phase)]
      (is (= :interrupted (:status result))
          "a missing :phase must behave as phase 1 (assisted) -- always escalate, never silently auto-commit at maximum autonomy")
      (is (= 0 (count (store/all-dispatch-records db)))
          "must not have auto-committed before human approval")
      (resume-approval actor "t6" :approved)
      (is (> (count (store/all-dispatch-records db)) 0)
          "after explicit human approval it still commits -- the fix only removes silent auto-autonomy, it does not block legitimate work"))))

(deftest missing-phase-still-hard-holds-a-phase-2-only-op
  (testing "with :phase omitted (-> conservative phase 1), :cleaning/assess (only enabled from phase 2) must be HELD as phase-disabled, not silently allowed through"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx-no-phase {:actor-id "test-6b" :today 20260718}
          result (exec-request actor "t6b"
                               {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                                :patch {:fluid-id "fluid-1" :surface "glass"}}
                               ctx-no-phase)]
      (is (= :done (:status result)))
      (is (= 0 (count (store/all-dispatch-records db)))
          ":cleaning/assess is not in phase 1's :writes set, so a missing :phase must HOLD it as :phase-disabled")
      (is (some #(and (= :governor-hold (:t %)) (= :phase-disabled (:phase-reason %))) (store/ledger db))))))

;; ----------------------------- audit trail -----------------------------

(deftest audit-trail-completeness
  (testing "every decision leaves immutable audit facts"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-7" :phase 3 :today 20260718}]
      (exec-request actor "t7a"
                     {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                      :patch {:fluid-id "fluid-1" :surface "glass"}}
                     ctx)
      (exec-request actor "t7b"
                     {:op :cleaning/assess :site-id "unknown" :zone-id "shibuya-ku-row"
                      :patch {:fluid-id "fluid-1" :surface "glass"}}
                     ctx)
      (let [ledger (store/ledger db)]
        (is (> (count ledger) 0))
        (is (some #(= :committed (:t %)) ledger)
            "successful commits must be logged")
        (is (some #(= :governor-hold (:t %)) ledger)
            "HARD holds must be logged")))))
