(ns facadecleaningops.store
  "SSoT for the ISIC Rev.5 8129 facade/street-cleaning ROBOT-DISPATCH
  satellite actor -- a role-suffix satellite of the already-`:implemented`
  `cloud-itonami-isic-8129` (Other building and industrial cleaning
  activities) coordination-only actor, per ADR-2731008129. Where the
  parent actor coordinates back-office paperwork only, this satellite
  actually dispatches an autonomous physical robot into public space, so
  it carries two structural additions the parent never needed: a
  data-driven `deployment-zone` registry (public-right-of-way permits,
  never hardcoded to one city) and a safety-data-sheet (SDS) registry
  scoping which surfaces/materials a given cleaning fluid is verified
  safe for. Store is a protocol injected into the
  `facadecleaningops.operation` StateGraph -- `MemStore` is the default,
  deterministic, zero-dep backend; a Datomic/kotoba-server-backed
  implementation can be swapped in without touching the actor or governor
  (itonami actor pattern, per ADR-2607011000 / CLAUDE.md's Actors
  section).

  Domain:

    site      -- a registered facade-cleaning client site/building.
                 {:site-id :name :registered? :verified? :owner-consent?}.
                 All three booleans must be true before ANY proposal
                 targeting the site may commit or even escalate --
                 `facadecleaningops.governor` re-derives this from the
                 site's own store record every time, never from a
                 proposal's own `:site-id` claim (ground-truth-not-
                 self-report discipline, same as every sibling actor in
                 this fleet).

    zone      -- a deployment-zone: a public-right-of-way permitting
                 record, modeled as PURE DATA so adding another city/zone
                 never requires a code change (mirrors
                 `kyoninka.store`'s jurisdiction-as-data pattern exactly).
                 {:zone/id :zone/name :zone/status (:active|:suspended|
                 :pending) :zone/permit-required? :zone/max-hours
                 :zone/insurance-min-jpy :zone/permit
                 ({:status (:granted|:pending|:expired) :expires
                 yyyymmdd-int-or-nil} or nil)}. The first seeded zone is
                 a Shibuya-ku public right-of-way permit (the motivating
                 use case for this actor), but nothing about the schema
                 or the governor's zone check names Shibuya specifically
                 -- any other city/zone is added purely as data.

    sds       -- a safety-data-sheet record for a cleaning fluid,
                 scoping which surfaces/materials it is verified safe
                 for. {:fluid-id :name :registered? :verified?
                 :verified-surfaces #{...}}. A proposal that names a
                 `:fluid-id` must resolve to a registered+verified SDS
                 record whose `:verified-surfaces` contains the
                 proposal's own `:surface` -- see
                 `facadecleaningops.governor`'s `sds-out-of-scope-violations`,
                 a HARD, permanent, un-overridable block modeled on this
                 actor's own `vendor-unverified-violations`-style
                 foreign-key-into-a-verified-record discipline (never a
                 term-scan).

    dispatch  -- a committed cleaning run record, written ONLY via
                 commit-record!, never mutated in place. {:dispatch-id
                 :site-id :zone-id :fluid-id :surface :op :evidence
                 [...]}. `:cleaning/log-completion` without a non-empty
                 `:evidence` vector (photo/sensor refs) is a HARD,
                 permanent governor block -- see
                 `facadecleaningops.governor`'s `evidence-missing-violations`.

    ledger    -- an append-only audit trail of every proposal/verdict/
                 disposition, regardless of outcome (commit or hold).

  This actor has NO code dependency on `kotoba-lang/giemon` (the hardware
  fixture repo, owned by a sibling phase of this same multi-repo
  project) -- the advisor/governor/store operate purely on request/store
  maps; any real hardware telemetry arrives as ordinary request/context
  data handed to the advisor, never as a required import.")

(defprotocol Store
  (site [s site-id] "Registered facade-cleaning client site/building
    record, or nil. {:site-id .. :name .. :registered? bool :verified?
    bool :owner-consent? bool}.")
  (all-sites [s])
  (zone [s zone-id] "Deployment-zone permitting record, or nil. Pure
    data -- see the `zone` docstring above.")
  (all-zones [s])
  (sds [s fluid-id] "Safety-data-sheet record for a cleaning fluid, or
    nil. {:fluid-id .. :name .. :registered? bool :verified? bool
    :verified-surfaces #{..}}.")
  (all-sds [s])
  (dispatch-record [s dispatch-id])
  (all-dispatch-records [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-sites [s sites] "replace/seed the site directory (map site-id->site)")
  (with-zones [s zones] "replace/seed the zone directory (map zone-id->zone)")
  (with-sds [s sds-records] "replace/seed the SDS directory (map fluid-id->sds)")
  (set-zone! [s zone-id zone-record] "mutate a single zone record in place
    -- used by tests to simulate a fact changing during a human-review
    window (e.g. a permit expiring between :request-approval interrupt
    and resume). Production callers should prefer re-seeding via
    with-zones for bulk registration; set-zone! exists specifically to
    exercise facadecleaningops.operation's live-store re-verification
    at resume.")
  (set-site! [s site-id site-record] "mutate a single site record in
    place -- same purpose as set-zone!, for site-side live facts (e.g.
    owner consent revoked mid-review)."))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained site/zone/sds directory covering both the
  happy path and every governor hard check, so the actor + tests run
  offline. The first zone entry is a real-shaped Shibuya-ku public
  right-of-way permit -- the motivating use case -- but the schema and
  every governor check are city-agnostic: adding another zone (any city,
  any country) is a pure data addition, never a code change."
  []
  {:sites
   {"site-1" {:site-id "site-1" :name "Shibuya Crossing District Office Tower Facade"
              :registered? true :verified? true :owner-consent? true}
    "site-2" {:site-id "site-2" :name "Riverside Retail Block Street Frontage"
              :registered? true :verified? true :owner-consent? true}
    "site-3" {:site-id "site-3" :name "Downtown Municipal Building (consent pending)"
              :registered? true :verified? true :owner-consent? false}}
   :zones
   {"shibuya-ku-row" {:zone/id "shibuya-ku-row"
                       :zone/name "Shibuya-ku public right-of-way"
                       :zone/status :active
                       :zone/permit-required? true
                       :zone/max-hours [9 18]
                       :zone/insurance-min-jpy 100000000
                       :zone/permit {:status :granted :expires 20270101}}
    "expired-permit-zone" {:zone/id "expired-permit-zone"
                            :zone/name "Sample zone with a lapsed public-right-of-way permit"
                            :zone/status :active
                            :zone/permit-required? true
                            :zone/max-hours [9 18]
                            :zone/insurance-min-jpy 100000000
                            :zone/permit {:status :granted :expires 20260101}}
    "suspended-zone" {:zone/id "suspended-zone"
                       :zone/name "Sample zone under a suspended deployment authorization"
                       :zone/status :suspended
                       :zone/permit-required? true
                       :zone/max-hours [9 18]
                       :zone/insurance-min-jpy 100000000
                       :zone/permit {:status :granted :expires 20270101}}
    "private-lot-no-permit" {:zone/id "private-lot-no-permit"
                              :zone/name "Sample private-lot zone -- no public-right-of-way permit required"
                              :zone/status :active
                              :zone/permit-required? false
                              :zone/max-hours [6 22]
                              :zone/insurance-min-jpy 50000000
                              :zone/permit nil}}
   :sds
   {"fluid-1" {:fluid-id "fluid-1" :name "Neutral-pH Facade Glass & Cladding Cleaner"
               :registered? true :verified? true
               :verified-surfaces #{"glass" "aluminum-cladding" "anodized-metal"}}
    "fluid-2" {:fluid-id "fluid-2" :name "Unverified Bulk Degreaser Import"
               :registered? true :verified? false
               :verified-surfaces #{"concrete"}}
    "fluid-3" {:fluid-id "fluid-3" :name "Sidewalk Sealant Stripper (concrete/asphalt only)"
               :registered? true :verified? true
               :verified-surfaces #{"concrete" "asphalt"}}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (site [_ site-id] (get-in @a [:sites site-id]))
  (all-sites [_] (sort-by :site-id (vals (:sites @a))))
  (zone [_ zone-id] (get-in @a [:zones zone-id]))
  (all-zones [_] (sort-by :zone/id (vals (:zones @a))))
  (sds [_ fluid-id] (get-in @a [:sds fluid-id]))
  (all-sds [_] (sort-by :fluid-id (vals (:sds @a))))
  (dispatch-record [_ dispatch-id] (some #(when (= dispatch-id (:dispatch-id %)) %)
                                          (:dispatch-log @a)))
  (all-dispatch-records [_] (:dispatch-log @a))
  (ledger [_] (:ledger @a))
  (commit-record! [_ record]
    (swap! a update :dispatch-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-sites [s sites] (when (seq sites) (swap! a assoc :sites sites)) s)
  (with-zones [s zones] (when (seq zones) (swap! a assoc :zones zones)) s)
  (with-sds [s sds-records] (when (seq sds-records) (swap! a assoc :sds sds-records)) s)
  (set-zone! [s zone-id zone-record]
    (swap! a assoc-in [:zones zone-id] zone-record) s)
  (set-site! [s site-id site-record]
    (swap! a assoc-in [:sites site-id] site-record) s))

(defn seed-db
  "A MemStore seeded with the demo site/zone/sds directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :dispatch-log []))))

(defn mem-store
  "A MemStore seeded with explicit `sites`/`zones`/`sds` maps (id string
  -> record map) -- the primary test/dev entry point. Any may be empty
  (an unregistered-everywhere fixture)."
  ([sites] (mem-store sites {} {}))
  ([sites zones] (mem-store sites zones {}))
  ([sites zones sds-records]
   (->MemStore (atom {:sites (or sites {}) :zones (or zones {}) :sds (or sds-records {})
                       :ledger [] :dispatch-log []}))))
