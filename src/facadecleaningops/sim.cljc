(ns facadecleaningops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean readiness assessment
  through intake -> advise -> govern -> decide -> commit at phase 3,
  then a physical robot dispatch (ALWAYS escalates regardless of phase
  -- approve, then commit), then a post-run completion log with evidence
  (auto-commits at phase 3), then a safety-concern flag (ALWAYS
  escalates -- approve, then commit), then HARD-hold scenarios: an
  unverified/unconsented site, an inactive deployment-zone, a
  deployment-zone with an expired public-right-of-way permit, an SDS
  fluid used on an out-of-scope surface, a completion log missing
  evidence, a proposal whose own `:effect` is not `:propose`, a proposal
  that has drifted into permanently-excluded direct-actuation scope, and
  finally the safety-critical resume-time re-verification: a dispatch
  that was clean at intake but whose deployment-zone permit expires
  DURING the human-review window -- resume must still hold, not commit."
  (:require [langgraph.graph :as g]
            [facadecleaningops.advisor :as advisor]
            [facadecleaningops.store :as store]
            [facadecleaningops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "facade-cleaning-zone-compliance-officer-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :facade-cleaning-coordinator :phase 3 :today 20260718}
        actor (op/build db)]

    (println "== cleaning/assess site-1/shibuya-ku-row, verified fluid+surface (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t1" {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                                  :patch {:fluid-id "fluid-1" :surface "glass"}} coordinator-phase-3))

    (println "\n== cleaning/dispatch site-1/shibuya-ku-row (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t2" {:op :cleaning/dispatch :site-id "site-1" :zone-id "shibuya-ku-row"
                                 :patch {:fluid-id "fluid-1" :surface "glass" :window "09:00-12:00"}} coordinator-phase-3)]
      (println r)
      (println "-- human zone-compliance officer reviews & approves --")
      (println (approve! actor "t2")))

    (println "\n== cleaning/log-completion site-1/shibuya-ku-row, with evidence (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :cleaning/log-completion :site-id "site-1" :zone-id "shibuya-ku-row"
                                  :patch {:evidence ["photo-ref-1" "sensor-log-ref-1"]}} coordinator-phase-3))

    (println "\n== cleaning/flag-safety-concern site-1/shibuya-ku-row (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t4" {:op :cleaning/flag-safety-concern :site-id "site-1" :zone-id "shibuya-ku-row"
                                 :patch {:concern "pedestrian crossed the cordoned work area, robot halted automatically" :confidence 0.9}} coordinator-phase-3)]
      (println r)
      (println "-- human zone-compliance officer reviews & approves --")
      (println (approve! actor "t4")))

    (println "\n== cleaning/assess site-3 (owner-consent pending -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :cleaning/assess :site-id "site-3" :zone-id "shibuya-ku-row"
                                  :patch {:fluid-id "fluid-1" :surface "glass"}} coordinator-phase-3))

    (println "\n== cleaning/assess site-1/suspended-zone (zone not :active -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :cleaning/assess :site-id "site-1" :zone-id "suspended-zone"
                                  :patch {:fluid-id "fluid-1" :surface "glass"}} coordinator-phase-3))

    (println "\n== cleaning/assess site-1/expired-permit-zone (permit expired as-of :today -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :cleaning/assess :site-id "site-1" :zone-id "expired-permit-zone"
                                  :patch {:fluid-id "fluid-1" :surface "glass"}} coordinator-phase-3))

    (println "\n== cleaning/assess site-1/shibuya-ku-row, fluid-2 unverified SDS (-> HARD hold) ==")
    (println (exec-op actor "t8" {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                                  :patch {:fluid-id "fluid-2" :surface "concrete"}} coordinator-phase-3))

    (println "\n== cleaning/assess site-1/shibuya-ku-row, fluid-1 on out-of-scope surface (-> HARD hold) ==")
    (println (exec-op actor "t9" {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                                  :patch {:fluid-id "fluid-1" :surface "asphalt"}} coordinator-phase-3))

    (println "\n== cleaning/log-completion site-1/shibuya-ku-row, no evidence (-> HARD hold) ==")
    (println (exec-op actor "t10" {:op :cleaning/log-completion :site-id "site-1" :zone-id "shibuya-ku-row"
                                   :patch {}} coordinator-phase-3))

    (println "\n== cleaning/assess site-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t11" {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                                            :patch {:fluid-id "fluid-1" :surface "glass"}} coordinator-phase-3)))

    (println "\n== cleaning/assess site-1, advisor drifts into direct-actuation scope -> HARD hold, permanent ==")
    (println (exec-op actor "t12" {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                                   :out-of-scope? true
                                   :patch {:fluid-id "fluid-1" :surface "glass"}} coordinator-phase-3))

    (println "\n== SAFETY-CRITICAL: cleaning/dispatch, clean at intake, zone permit EXPIRES during human review -> resume still holds ==")
    (let [r (exec-op actor "t13" {:op :cleaning/dispatch :site-id "site-2" :zone-id "shibuya-ku-row"
                                  :patch {:fluid-id "fluid-1" :surface "glass" :window "13:00-16:00"}} coordinator-phase-3)]
      (println r)
      (println "-- during human review, the zone's permit expires --")
      (store/set-zone! db "shibuya-ku-row" (assoc-in (store/zone db "shibuya-ku-row") [:zone/permit :expires] 20260101))
      (println "-- human approves anyway (unaware the fact changed) -- live re-verification must still HOLD --")
      (println (approve! actor "t13")))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed dispatch/log records ==")
    (doseq [r (store/all-dispatch-records db)] (println r))))
