(ns facadecleaningops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: before this namespace
  existed there was NO demo page and NO generator here at all (`docs/`
  held only `adr/0001-architecture.md`). Everything on the rendered page
  is produced by driving the REAL actor stack --
  `facadecleaningops.operation` (a langgraph-clj StateGraph) ->
  `facadecleaningops.governor` -> `facadecleaningops.store` -- through
  `run-demo!` below. There is no hand-typed entity, id, number, hold
  reason or verdict anywhere in `render`: every cell is either read back
  out of the store/ledger this run wrote, or computed by CALLING the
  real `governor/check` / `phase/gate` functions at render time.

  Why that matters here: a committed `operator-console.html` is not by
  itself evidence that a generator ever ran. Sibling repos in this fleet
  were found carrying hand-written console pages that used correct
  domain vocabulary but invented entities. The defence is that this file
  regenerates the page from scratch and overwrites it, and that the
  page's own footer states which seed the numbers came from.

  Before writing this namespace the repo's own demo driver
  (`clojure -M:dev:run`, `facadecleaningops.sim`) was run and its ledger
  inspected, confirming that the ids it uses (`site-1`..`site-3`,
  `shibuya-ku-row` / `expired-permit-zone` / `suspended-zone` /
  `private-lot-no-permit`, `fluid-1`..`fluid-3`) really are defined by
  `facadecleaningops.store/demo-data` -- a sibling repo in an earlier
  wave turned out to have a sim referencing ids its own seed never
  defined, so this is checked rather than assumed. The scenario below is
  adapted from that sim and extended: it additionally exercises the
  rollout-phase gate (a hold with NO governor violation), a human
  REJECTION (a hold that is not a governor refusal at all), and the
  fluid/surface matrix, so the page can keep those three failure shapes
  in separate tables instead of blurring them into one 'blocked' column.

  Determinism: `:today` is threaded explicitly (20260718, the same
  as-of date `facadecleaningops.governor/check` defaults to) and never
  read from the wall clock; no timestamp appears in the page content;
  every set is sorted before rendering. Two consecutive runs against the
  same seed are byte-identical.

  Build-time invariant: `-main` REFUSES to write the page unless the run
  actually produced governor HARD holds. The check is deliberately
  two-stage, because a phase-gating hold (`:phase-disabled`) reaches the
  ledger as a `:governor-hold` fact carrying an EMPTY `:violations`
  vector -- a naive `(count holds)` would be satisfied by a run in which
  the governor never refused anything at all. See `-main`.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [facadecleaningops.advisor :as advisor]
            [facadecleaningops.governor :as governor]
            [facadecleaningops.phase :as phase]
            [facadecleaningops.store :as store]
            [facadecleaningops.operation :as op]
            [langgraph.graph :as g]))

(def ^:private today
  "As-of date threaded into every governor check, mirroring
  `facadecleaningops.governor/check`'s own default. Explicit, never a
  wall-clock read -- the page must not change because it was rebuilt on
  a different day."
  20260718)

(defn- ctx
  "Actor context for one run at rollout `phase`."
  [phase]
  {:actor-id "coord-1"
   :actor-role :facade-cleaning-coordinator
   :phase phase
   :today today})

(def ^:private approver
  "The human this scenario resumes interrupted runs as. Appears on the
  page ONLY where the store actually kept it -- see
  `approver-paths`/`attribution`."
  "facade-cleaning-zone-compliance-officer-1")

;; ----------------------------- scenario -----------------------------

(defn- exec!
  "One actor run. `patch` is the request's own extension point, so
  `:run-id` rides through the advisor into the proposal's `:value` and
  out into the committed record -- giving every committed record a
  UNIQUE key back to the run that produced it. Joining committed records
  to approvals on `[op site-id]` would NOT be unique here (site-1 alone
  receives four different ops in this scenario), and a sibling repo in
  an earlier wave nearly reported a store defect it did not have by
  doing exactly that."
  [actor run-id request phase]
  (g/run* actor {:request request :context (ctx phase)} {:thread-id run-id}))

(defn- resume!
  [actor run-id status]
  (g/run* actor {:approval {:status status :by approver}}
          {:thread-id run-id :resume? true}))

(defn- record-run
  "Normalises one scenario step into a render-ready row. `first-result`
  is the run up to completion-or-interrupt; `resumed` is the resume
  result, or nil when no human was ever involved. Both dispositions are
  read out of the graph's real final state."
  [{:keys [run-id label phase request first-result resumed human]}]
  (let [final (or resumed first-result)]
    {:run-id      run-id
     :label       label
     :phase       phase
     :op          (:op request)
     :site-id     (:site-id request)
     :zone-id     (:zone-id request)
     :fluid-id    (get-in request [:patch :fluid-id])
     :surface     (get-in request [:patch :surface])
     :first-status      (:status first-result)
     :first-disposition (get-in first-result [:state :disposition])
     :human       human
     :disposition (get-in final [:state :disposition])
     :status      (:status final)}))

(defn run-demo!
  "Drives one freshly-seeded store through a scenario that reaches every
  disposition this actor can produce, and returns
  `{:db .. :runs [..] :permit-drill {..}}`.

  Approved / committed paths (so the page shows more than refusals):
    r01 `:cleaning/assess` on a verified site+zone with an in-scope
        fluid at phase 3 -- governor-clean and auto-eligible, so it
        commits with NO human in the loop.
    r02 `:cleaning/dispatch` -- physically sending a robot into public
        space ALWAYS escalates, at every phase; approved, then commits.
    r03 `:cleaning/log-completion` WITH evidence -- auto-commits.
    r04 `:cleaning/flag-safety-concern` on the private-lot zone (the
        one seeded zone with `:zone/permit-required? false`) -- always
        escalates; approved, then commits.
    r05 `:cleaning/assess` with `fluid-3` on concrete -- a second
        verified fluid/surface pair; auto-commits.

  A hold that is NOT a governor refusal:
    r06 a governor-clean `:cleaning/dispatch` that the human REJECTS.

  Holds that are the ROLLOUT GATE, not the governor (these carry an
  EMPTY `:violations` vector -- the reason the build-time invariant in
  `-main` is two-stage):
    r07 `:cleaning/assess` at phase 1, where it is not yet a permitted
        write.
    r08 `:cleaning/dispatch` at phase 2, same.

  Governor HARD refusals -- every one of these is decided before any
  human is asked, and none can be overridden:
    r09 `:site-unverified`     -- site-3, owner consent not obtained.
    r10 `:zone-unverified`     -- a suspended deployment zone.
    r11 `:zone-permit-invalid` -- a lapsed public-right-of-way permit.
    r12 `:sds-out-of-scope`    -- an unverified safety data sheet.
    r13 `:sds-out-of-scope`    -- a verified fluid on a surface outside
                                  its verified scope.
    r14 `:evidence-missing`    -- a completion record with no evidence.
    r15 `:effect-not-propose`  -- an advisor claiming a direct commit.
    r16 `:scope-excluded`      -- an advisor drifting into permanently
                                  excluded direct-actuation vocabulary.
    r17 `:op-not-allowed`      -- an op outside the closed four-op
                                  allowlist, which the advisor collapses
                                  to its `:unknown` catch-all.

  Together r09-r17 exercise ALL EIGHT of the rules
  `facadecleaningops.governor` can raise, so the refusal table on the
  page is a complete census of this governor's HARD vocabulary rather
  than a sample of it.

  Finally the safety-critical drill, run LAST because it mutates a zone:
    r18 a `:cleaning/dispatch` that is clean at intake, whose zone's
        permit then EXPIRES during the human-review window. The human
        approves anyway; `:request-approval` re-runs the governor
        against the LIVE store and holds regardless."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        runs  (atom [])
        step! (fn [{:keys [run-id phase request approve] :as spec}]
                (let [a  (or (:actor spec) actor)
                      r1 (exec! a run-id request phase)
                      r2 (when approve (resume! a run-id approve))]
                  (swap! runs conj (record-run (assoc spec
                                                      :first-result r1
                                                      :resumed r2
                                                      :human approve)))
                  r2))
        ;; A second graph over the SAME store whose advisor claims
        ;; `:effect :commit` -- the only way to exercise the
        ;; no-actuation invariant, since the real advisor is
        ;; structurally unable to emit anything but `:propose`.
        direct-actuation-actor
        (op/build db {:advisor (reify advisor/Advisor
                                 (-advise [_ _ req]
                                   (assoc (advisor/infer nil req) :effect :commit)))})]

    ;; -- committed paths ------------------------------------------------
    (step! {:run-id "r01" :phase 3
            :label "clean readiness assessment, auto-eligible at phase 3"
            :request {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                      :patch {:run-id "r01" :fluid-id "fluid-1" :surface "glass"}}})

    (step! {:run-id "r02" :phase 3 :approve :approved
            :label "physical robot dispatch -- always escalates, approved"
            :request {:op :cleaning/dispatch :site-id "site-1" :zone-id "shibuya-ku-row"
                      :patch {:run-id "r02" :fluid-id "fluid-1" :surface "glass"
                              :window "09:00-12:00"}}})

    (step! {:run-id "r03" :phase 3
            :label "completion record WITH evidence, auto-eligible at phase 3"
            :request {:op :cleaning/log-completion :site-id "site-1" :zone-id "shibuya-ku-row"
                      :patch {:run-id "r03" :evidence ["photo-ref-1" "sensor-log-ref-1"]}}})

    (step! {:run-id "r04" :phase 3 :approve :approved
            :label "safety concern on the no-permit-required private lot -- always escalates, approved"
            :request {:op :cleaning/flag-safety-concern :site-id "site-2" :zone-id "private-lot-no-permit"
                      :patch {:run-id "r04" :confidence 0.9
                              :concern "pedestrian crossed the cordoned work area, robot halted automatically"}}})

    (step! {:run-id "r05" :phase 3
            :label "second verified fluid/surface pair, auto-eligible at phase 3"
            :request {:op :cleaning/assess :site-id "site-2" :zone-id "private-lot-no-permit"
                      :patch {:run-id "r05" :fluid-id "fluid-3" :surface "concrete"}}})

    ;; -- human said no (NOT a governor refusal) --------------------------
    (step! {:run-id "r06" :phase 3 :approve :rejected
            :label "governor-clean dispatch that the human REJECTED"
            :request {:op :cleaning/dispatch :site-id "site-2" :zone-id "private-lot-no-permit"
                      :patch {:run-id "r06" :fluid-id "fluid-3" :surface "asphalt"
                              :window "05:30-07:00"}}})

    ;; -- rollout gate held (NO governor violation) -----------------------
    (step! {:run-id "r07" :phase 1
            :label "assessment not yet a permitted write at phase 1"
            :request {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                      :patch {:run-id "r07" :fluid-id "fluid-1" :surface "glass"}}})

    (step! {:run-id "r08" :phase 2
            :label "dispatch not yet a permitted write at phase 2"
            :request {:op :cleaning/dispatch :site-id "site-1" :zone-id "shibuya-ku-row"
                      :patch {:run-id "r08" :fluid-id "fluid-1" :surface "glass"
                              :window "09:00-12:00"}}})

    ;; -- governor HARD refusals -------------------------------------------
    (step! {:run-id "r09" :phase 3
            :label "site whose owner consent was never obtained"
            :request {:op :cleaning/assess :site-id "site-3" :zone-id "shibuya-ku-row"
                      :patch {:run-id "r09" :fluid-id "fluid-1" :surface "glass"}}})

    (step! {:run-id "r10" :phase 3
            :label "deployment zone under a suspended authorization"
            :request {:op :cleaning/assess :site-id "site-1" :zone-id "suspended-zone"
                      :patch {:run-id "r10" :fluid-id "fluid-1" :surface "glass"}}})

    (step! {:run-id "r11" :phase 3
            :label "deployment zone whose public-right-of-way permit has lapsed"
            :request {:op :cleaning/assess :site-id "site-1" :zone-id "expired-permit-zone"
                      :patch {:run-id "r11" :fluid-id "fluid-1" :surface "glass"}}})

    (step! {:run-id "r12" :phase 3
            :label "cleaning fluid whose safety data sheet is unverified"
            :request {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                      :patch {:run-id "r12" :fluid-id "fluid-2" :surface "concrete"}}})

    (step! {:run-id "r13" :phase 3
            :label "verified fluid applied to a surface outside its verified scope"
            :request {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                      :patch {:run-id "r13" :fluid-id "fluid-1" :surface "asphalt"}}})

    (step! {:run-id "r14" :phase 3
            :label "completion record with NO evidence attached"
            :request {:op :cleaning/log-completion :site-id "site-1" :zone-id "shibuya-ku-row"
                      :patch {:run-id "r14"}}})

    (step! {:run-id "r15" :phase 3 :actor direct-actuation-actor
            :label "advisor claiming a direct commit instead of a proposal"
            :request {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                      :patch {:run-id "r15" :fluid-id "fluid-1" :surface "glass"}}})

    (step! {:run-id "r16" :phase 3
            :label "advisor drifting into permanently excluded direct-actuation scope"
            :request {:op :cleaning/assess :site-id "site-1" :zone-id "shibuya-ku-row"
                      :out-of-scope? true
                      :patch {:run-id "r16" :fluid-id "fluid-1" :surface "glass"}}})

    ;; An op outside the closed four-op allowlist. The advisor is
    ;; structurally unable to construct one (`advisor/infer` collapses
    ;; anything outside `permitted-ops` to the `{:op :unknown
    ;; :confidence 0.0}` catch-all), so what actually reaches the
    ;; governor is that catch-all -- and `:unknown` is itself not in
    ;; `governor/allowed-ops`, which is what makes this the LAST of the
    ;; governor's eight rules the scenario had not yet exercised.
    (step! {:run-id "r17" :phase 3
            :label "an op outside the closed allowlist -- collapses to :unknown, refused"
            :request {:op :facility/demolish :site-id "site-1" :zone-id "shibuya-ku-row"
                      :patch {:run-id "r17"}}})

    ;; -- safety-critical: the world changes during human review -----------
    ;; Runs LAST: it mutates `shibuya-ku-row`, so every run above saw the
    ;; zone in its seeded (valid-permit) state. Before/after are read back
    ;; out of the store rather than restated, so the page cannot drift
    ;; from what actually happened.
    (let [zone-id "shibuya-ku-row"
          r1 (exec! actor "r18" {:op :cleaning/dispatch :site-id "site-2" :zone-id zone-id
                                 :patch {:run-id "r18" :fluid-id "fluid-1" :surface "glass"
                                         :window "13:00-16:00"}}
                    3)
          before (get-in (store/zone db zone-id) [:zone/permit :expires])
          _ (store/set-zone! db zone-id
                             (assoc-in (store/zone db zone-id) [:zone/permit :expires] 20260101))
          after (get-in (store/zone db zone-id) [:zone/permit :expires])
          r2 (resume! actor "r18" :approved)]
      (swap! runs conj (record-run {:run-id "r18" :phase 3
                                    :label "clean at intake; zone permit expired mid-review; human approved anyway"
                                    :request {:op :cleaning/dispatch :site-id "site-2" :zone-id zone-id
                                              :patch {:run-id "r18" :fluid-id "fluid-1" :surface "glass"}}
                                    :first-result r1 :resumed r2 :human :approved}))
      {:db db
       :runs @runs
       :permit-drill {:run-id "r18"
                      :zone-id zone-id
                      :expires-before before
                      :expires-after after
                      :as-of today
                      :interrupted-at (:status r1)
                      :human :approved
                      :final (get-in r2 [:state :disposition])}})))

;; ----------------------------- ledger slicing -----------------------------

(defn hard-refusals
  "Governor HARD refusals: a hold the governor itself raised from at
  least one real violation, decided WITHOUT a human ever being asked (or,
  for `:approval-superseded-by-fresh-hold`, decided against a human who
  had already said yes).

  Deliberately excludes the two neighbouring shapes that are NOT
  governor refusals and that sibling consoles in this fleet were found
  blurring into the same column:
    - `:approval-rejected`, where the governor was clean and a HUMAN
      declined; and
    - a phase-gating hold, which reaches the ledger as a
      `:governor-hold` fact with an EMPTY `:violations` vector."
  [ledger]
  (->> ledger
       (filter #(#{:governor-hold :approval-superseded-by-fresh-hold} (:t %)))
       (filter #(seq (:violations %)))
       (remove #(every? (comp #{:approver-rejected} :rule) (:violations %)))
       vec))

(defn phase-gate-holds
  "Holds produced by the ROLLOUT GATE rather than the governor: the
  governor found nothing wrong, but the op is not yet a permitted write
  at that phase. Identified structurally -- empty `:violations` plus a
  `:phase-reason` -- not by guessing from the op."
  [ledger]
  (->> ledger
       (filter #(and (= :governor-hold (:t %))
                     (empty? (:violations %))
                     (:phase-reason %)))
       vec))

(defn human-decisions
  "Every point at which a human entered, or was overruled after
  entering."
  [ledger]
  (->> ledger
       (filter #(#{:approval-rejected :approval-superseded-by-fresh-hold} (:t %)))
       vec))

;; ----------------------------- approver attribution -----------------------------

(defn approver-paths
  "Every path inside `record` whose key names an approver, with the
  value found there.

  Derived by WALKING the record the store actually kept, at render time
  -- never a hard-coded claim about this store's behaviour. Sibling
  repos in this fleet were measured to differ here (some stores drop the
  payload entirely, some keep the whole record, some attribute only
  certain effects), and a note reading 'this store loses approvers'
  becomes a lie the day someone fixes the store. If the behaviour
  changes, this page changes with it."
  [record]
  (letfn [(walk [prefix m]
            (when (map? m)
              (mapcat (fn [[k v]]
                        (let [p (conj prefix k)]
                          (concat
                           (when (and (keyword? k)
                                      (str/includes? (str/lower-case (name k)) "approv"))
                             [[p v]])
                           (walk p v))))
                      m)))]
    (vec (sort-by (comp pr-str first) (walk [] record)))))

(defn attribution
  "Cross-checks, for every committed record, whether the store kept an
  approver against whether a human actually approved that run.

  The join is on `:run-id`, which this scenario threads through the
  request's own `:patch` into the proposal's `:value` and out into the
  committed record -- unique per run. `[op site-id]` would not be."
  [runs records]
  (let [approved (into {} (map (juxt :run-id :human)) runs)]
    (for [r records
          :let [run-id (get-in r [:value :run-id])
                paths (approver-paths r)]]
      {:run-id     run-id
       :op         (:op r)
       :site-id    (:site-id r)
       :zone-id    (:zone-id r)
       :human      (get approved run-id)
       :paths      paths
       :kept?      (boolean (seq paths))})))

(defn attribution-verdict
  "The disclosure sentence for the approver-attribution section,
  DERIVED from `attribution` rather than asserted. Silently omitting the
  approver is not honest -- a reader cannot otherwise distinguish
  'nobody approved this' from 'the store did not keep who did'."
  [rows]
  (let [human-approved (filter #(= :approved (:human %)) rows)
        kept (filter :kept? human-approved)
        auto (remove :human rows)
        under (fn [k] (->> human-approved
                           (mapcat :paths)
                           (map first)
                           (filter #(= k (first %)))
                           seq
                           boolean))]
    {:human-approved (count human-approved)
     :attributed     (count kept)
     :auto-committed (count auto)
     :in-payload?    (under :payload)
     :in-value?      (under :value)
     :verdict
     (cond
       (empty? human-approved)
       "この実行では人間承認を経て commit された記録が無いため、承認者保持の可否は測定できていない。"

       (= (count kept) (count human-approved))
       "測定結果: この store は承認者を保持する。人間承認を経て commit された記録すべてに承認者キーが実在した。"

       (zero? (count kept))
       "測定結果: この store は承認者を捨てている。人間が承認した記録に承認者キーが1件も残っていない -- 監査証跡にのみ承認者が残る。"

       :else
       "測定結果: 承認者の保持は一部のみ。人間承認を経た記録の一部にしか承認者キーが残っていない。")}))

;; ----------------------------- governor probes -----------------------------

(defn- probe
  "Ask the REAL governor about a hypothetical proposal, without touching
  the ledger (`governor/check` is pure). Used to derive the zone and
  fluid/surface tables from live rules instead of restating them."
  [db {:keys [site-id zone-id fluid-id surface]}]
  (governor/check {:site-id site-id :zone-id zone-id}
                  nil
                  {:op :cleaning/assess :site-id site-id :zone-id zone-id
                   :effect :propose :confidence 1.0
                   :value (cond-> {}
                            fluid-id (assoc :fluid-id fluid-id)
                            surface (assoc :surface surface))}
                  db
                  {:today today}))

(defn- zone-verdict
  "Zone admissibility as-of `today`, obtained by asking the governor
  about a proposal that is clean in every OTHER respect (a verified
  site, no fluid named), so any violation returned is necessarily the
  zone's."
  [db zone-id]
  (->> (probe db {:site-id "site-1" :zone-id zone-id})
       :violations
       (filter #(#{:zone-unverified :zone-permit-invalid} (:rule %)))
       vec))

(defn- site-verdict
  [db site-id]
  (->> (probe db {:site-id site-id :zone-id "private-lot-no-permit"})
       :violations
       (filter #(= :site-unverified (:rule %)))
       vec))

(defn- sds-verdict
  [db fluid-id surface]
  (->> (probe db {:site-id "site-1" :zone-id "private-lot-no-permit"
                  :fluid-id fluid-id :surface surface})
       :violations
       (filter #(= :sds-out-of-scope (:rule %)))
       vec))

(defn- requested-surfaces
  "The surfaces this scenario actually asked about, sorted. Not a
  hand-written list -- read back off the runs."
  [runs]
  (->> runs (keep :surface) distinct sort vec))

;; ----------------------------- html -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- yes-no [b]
  (if b "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>"))

(defn- cell [v] (str "<td>" v "</td>"))

(defn- row [cells] (str "        <tr>" (str/join (map cell cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows)
         (str (str/join "\n" rows) "\n")
         (str (row [(str "<span class=\"muted\">この実行では該当なし ("
                         (count headers) " columns)</span>")]) "\n"))
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       body
       "  </section>\n"))

(defn- disposition-cell [d]
  (case d
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"critical\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (esc (kw d)) "</span>")))

(defn- violations-cell [violations]
  (if (seq violations)
    (str/join "<br>" (map #(str "<strong>" (esc (kw (:rule %))) "</strong><br>"
                                "<span class=\"muted\">" (esc (:detail %)) "</span>")
                          violations))
    "<span class=\"muted\">(なし)</span>"))

;; ----------------------------- sections -----------------------------

(defn- sites-section [db]
  (section
   "1. 登録済み清掃対象サイト (client sites)"
   (str "<code>facadecleaningops.store/all-sites</code> の全件。最右列は "
        "<code>facadecleaningops.governor</code> に実際に問い合わせた結果で、"
        "提案側の自己申告ではなく store のレコードから毎回導出される。")
   (table ["site-id" "名称" "registered?" "verified?" "owner-consent?" "governor 判定"]
          (for [s (store/all-sites db)
                :let [v (site-verdict db (:site-id s))]]
            (row [(code (:site-id s))
                  (esc (:name s))
                  (yes-no (:registered? s))
                  (yes-no (:verified? s))
                  (yes-no (:owner-consent? s))
                  (if (seq v)
                    (str "<span class=\"critical\">" (esc (kw (:rule (first v)))) "</span>")
                    "<span class=\"ok\">admissible</span>")])))))

(defn- zones-section [db permit-drill]
  (section
   "2. 展開区画 (deployment zones) と道路占用許可"
   (str "区画は純粋なデータ (<code>facadecleaningops.store</code> の <code>zone</code> レコード)。"
        "都市を増やしてもコード変更は不要。許可の有効性は as-of <span class=\"num\">"
        (esc (:as-of permit-drill)) "</span> で "
        "<code>governor/check</code> に実際に問い合わせた結果。"
        "<strong>この表は実行終了時点の状態</strong>である -- "
        (code (:zone-id permit-drill))
        " の許可期限は実行 " (code (:run-id permit-drill)) " (§8) の人間レビュー中に "
        "<span class=\"num\">" (esc (:expires-before permit-drill)) "</span> から "
        "<span class=\"num\">" (esc (:expires-after permit-drill)) "</span> へ変化した。"
        "それ以前の実行はすべて有効な許可の下で判定されている。")
   (table ["zone-id" "名称" "status" "許可要否" "許可 status" "expires" "governor 判定"]
          (for [z (store/all-zones db)
                :let [v (zone-verdict db (:zone/id z))
                      p (:zone/permit z)]]
            (row [(code (:zone/id z))
                  (esc (:zone/name z))
                  (esc (kw (:zone/status z)))
                  (if (:zone/permit-required? z)
                    "<span class=\"warn\">required</span>"
                    "<span class=\"muted\">not required</span>")
                  (if p (esc (kw (:status p))) "<span class=\"muted\">(許可レコード無し)</span>")
                  (if (:expires p) (str "<span class=\"num\">" (esc (:expires p)) "</span>")
                      "<span class=\"muted\">—</span>")
                  (if (seq v)
                    (str "<span class=\"critical\">" (esc (kw (:rule (first v)))) "</span>")
                    "<span class=\"ok\">admissible</span>")])))))

(defn- sds-section [db]
  (section
   "3. 安全データシート (SDS) レジストリ"
   (str "薬剤ごとの検証済み適用面。<code>verified-surfaces</code> は store のレコードそのもの"
        "（ソート済み表示）で、テキスト走査ではなく検証済みレコードへの外部キー照合に使われる。")
   (table ["fluid-id" "名称" "registered?" "verified?" "verified-surfaces"]
          (for [s (store/all-sds db)]
            (row [(code (:fluid-id s))
                  (esc (:name s))
                  (yes-no (:registered? s))
                  (yes-no (:verified? s))
                  (str/join " " (map #(str "<code>" (esc %) "</code>")
                                     (sort (:verified-surfaces s))))])))))

(defn- sds-matrix-section [db runs]
  (let [surfaces (requested-surfaces runs)]
    (section
     "4. 薬剤 × 作業面 の適合マトリクス (governor 実問い合わせ)"
     (str "行 = SDS レジストリの全薬剤、列 = この実行が実際に要求した作業面 "
          (str/join "・" (map #(str "<code>" (esc %) "</code>") surfaces))
          "（要求内容から導出、固定リストではない）。各セルは "
          "<code>governor/check</code> を実際に呼んだ結果であって、表を人手で書いたものではない。")
     (table (into ["fluid-id"] surfaces)
            (for [s (store/all-sds db)]
              (row (into [(code (:fluid-id s))]
                         (for [surf surfaces
                               :let [v (sds-verdict db (:fluid-id s) surf)]]
                           (if (seq v)
                             "<span class=\"critical\">blocked</span>"
                             "<span class=\"ok\">in scope</span>")))))))))

(defn- runs-section [runs]
  (section
   (str "5. この実行のシナリオ全 " (count runs) " 件")
   (str "1 行 = 1 アクター実行 (<code>langgraph</code> StateGraph の 1 スレッド)。"
        "「初回」列が <code>interrupted</code> の行は人間承認のために停止したもので、"
        "「人間」列がその人間の判断、「最終」列が再開後の実際の帰結。"
        "すべて実行結果の state から読み出しており、期待値ではない。")
   (table ["run" "phase" "op" "site" "zone" "fluid/面" "初回" "人間" "最終"]
          (for [r runs]
            (row [(code (:run-id r))
                  (str "<span class=\"num\">" (esc (:phase r)) "</span>")
                  (code (kw (:op r)))
                  (code (:site-id r))
                  (code (:zone-id r))
                  (if (:fluid-id r)
                    (str (code (:fluid-id r)) " / " (esc (or (:surface r) "—")))
                    "<span class=\"muted\">—</span>")
                  (if (= :interrupted (:first-status r))
                    "<span class=\"warn\">interrupted</span>"
                    (str "<span class=\"muted\">" (esc (kw (:first-status r))) "</span>"))
                  (if (:human r)
                    (str "<span class=\"warn\">" (esc (kw (:human r))) "</span>")
                    "<span class=\"muted\">（介在なし）</span>")
                  (disposition-cell (:disposition r))])))))

(defn- refusals-section [refusals]
  (section
   (str "6. ガバナの HARD 拒否 " (count refusals) " 件 — 人間に到達しない")
   (str "永久・上書き不可。<code>facadecleaningops.governor</code> が提案そのものを拒否したもので、"
        "承認キューにも載らない。detail 文言は governor が生成した実際の文字列。"
        "<strong>後述 §7 のロールアウト保留とは別物</strong>である。")
   (table ["run が触れた op" "site" "zone" "confidence" "違反規則と detail"]
          (for [f refusals]
            (row [(code (kw (:op f)))
                  (code (:site-id f))
                  (code (:zone-id f))
                  (str "<span class=\"num\">" (esc (:confidence f)) "</span>")
                  (violations-cell (:violations f))])))))

(defn- phase-holds-section [holds]
  (section
   (str "7. ロールアウト保留 " (count holds) " 件 — ガバナは何も拒否していない")
   (str "こちらは <em>段階的ロールアウトのゲート</em>。<code>:violations</code> は"
        "<strong>空</strong>で、コンプライアンス上の問題は 1 件も検出されていない -- "
        "その phase でまだ書き込みが解禁されていないだけである。"
        "この 2 つを同じ「ブロック」列にまとめると、"
        "「規制で恒久的に禁止」と「今はまだ解禁前」の区別が消える。"
        "ビルド時不変条件 (<code>-main</code>) が 2 段構えなのもこのため。")
   (table ["op" "phase" "phase-reason" "violations" "confidence"]
          (for [f holds]
            (row [(code (kw (:op f)))
                  (str "<span class=\"num\">" (esc (:phase f)) "</span>")
                  (code (kw (:phase-reason f)))
                  (str "<span class=\"num\">" (count (:violations f)) "</span>")
                  (str "<span class=\"num\">" (esc (:confidence f)) "</span>")])))))

(defn- human-section [decisions permit-drill]
  (section
   "8. 人間が入った地点、および承認後に覆された地点"
   (str "<code>:approval-rejected</code> は「ガバナは通したが人間が拒否した」。"
        "<code>:approval-superseded-by-fresh-hold</code> は逆で、"
        "<strong>人間が承認した後に</strong>、<code>:request-approval</code> が "
        "LIVE な store に対してガバナを再実行し、レビュー中に発生した新しい HARD 違反を"
        "検出して人間の判断を覆したもの。"
        "この実行では " (code (:zone-id permit-drill))
        " の道路占用許可期限を人間レビュー中に <span class=\"num\">"
        (esc (:expires-before permit-drill)) "</span> → <span class=\"num\">"
        (esc (:expires-after permit-drill)) "</span> へ変化させ、"
        "人間は <code>" (esc (kw (:human permit-drill))) "</code> と応答したが、"
        "最終帰結は <strong>" (esc (kw (:final permit-drill))) "</strong> になった"
        "（before/after とも store から読み戻した実測値）。")
   (table ["fact" "op" "site" "zone" "違反規則と detail"]
          (for [f decisions]
            (row [(code (kw (:t f)))
                  (code (kw (:op f)))
                  (code (:site-id f))
                  (code (:zone-id f))
                  (violations-cell (:violations f))])))))

(defn- records-section [rows verdict]
  (section
   (str "9. SSoT に commit された記録 " (count rows) " 件と承認者の帰属")
   (str "<strong>" (esc (:verdict verdict)) "</strong><br>"
        "内訳: 人間承認を経た記録 <span class=\"num\">" (:human-approved verdict)
        "</span> 件のうち承認者キーが実在したもの <span class=\"num\">" (:attributed verdict)
        "</span> 件、人間を介さず auto-commit された記録 <span class=\"num\">"
        (:auto-committed verdict) "</span> 件（こちらは承認者が居ないことが正しい）。"
        (when (and (:in-payload? verdict) (not (:in-value? verdict)))
          (str "<br>ただし承認者が載るのは <code>:payload</code> 側だけで、"
               "<code>:value</code>（承認前の草案）には載らない -- "
               "<code>:value</code> だけを読む描画は、実在しない欠陥を報告してしまう。"))
        "<br>この判定は記録を実際に走査して求めたもので、"
        "「この store は承認者を落とす」といった固定文言ではない。store が変われば表示も変わる。")
   (table ["run" "op" "site" "zone" "人間の判断" "承認者キーの所在"]
          (for [r rows]
            (row [(code (:run-id r))
                  (code (kw (:op r)))
                  (code (:site-id r))
                  (code (:zone-id r))
                  (if (:human r)
                    (str "<span class=\"warn\">" (esc (kw (:human r))) "</span>")
                    "<span class=\"muted\">（auto-commit / 介在なし）</span>")
                  (if (:kept? r)
                    (str/join "<br>" (for [[p v] (:paths r)]
                                       (str "<code>" (esc (pr-str p)) "</code> = " (esc v))))
                    (if (:human r)
                      "<span class=\"critical\">記録に承認者キー無し（監査台帳のみ）</span>"
                      "<span class=\"muted\">—</span>"))])))))

(defn- gate-matrix-section []
  (section
   "10. op × phase ゲート行列（実関数から導出）"
   (str "各セルは <code>facadecleaningops.phase/gate</code> に、ガバナが "
        "<code>:commit</code> を返した場合を渡して<strong>実際に呼んだ</strong>結果。"
        "表を人手で書いていないので、phase の定義が変われば次回生成でこの表も変わる。"
        "「常時エスカレーション」列は <code>governor/always-escalate-ops</code> の実際の内容。")
   (table (into ["op" "常時エスカレーション"]
                (for [p (sort (keys phase/phases))]
                  (str "phase " p " (" (:label (get phase/phases p)) ")")))
          (for [o (sort-by kw governor/allowed-ops)]
            (row (into [(code (kw o))
                        (if (contains? governor/always-escalate-ops o)
                          "<span class=\"warn\">always</span>"
                          "<span class=\"muted\">—</span>")]
                       (for [p (sort (keys phase/phases))
                             :let [{:keys [disposition reason]} (phase/gate p {:op o} :commit)]]
                         (str (disposition-cell disposition)
                              (when reason
                                (str "<br><span class=\"muted\">" (esc (kw reason)) "</span>"))))))))))

(defn- config-section []
  (section
   "11. ガバナ設定（生成時に名前空間から読み出した実値）"
   "定数は転記ではなく、レンダリング時に var を直接読んでいる。"
   (table ["設定" "値"]
          [(row ["confidence floor"
                 (str "<span class=\"num\">" (esc governor/confidence-floor) "</span>")])
           (row ["許可 op (closed allowlist)"
                 (str/join " " (map #(str "<code>" (esc (kw %)) "</code>")
                                    (sort-by kw governor/allowed-ops)))])
           (row ["常時人間承認が必要な op"
                 (str/join " " (map #(str "<code>" (esc (kw %)) "</code>")
                                    (sort-by kw governor/always-escalate-ops)))])
           (row ["スコープ除外語 (件数)"
                 (str "<span class=\"num\">" (count governor/scope-excluded-terms) "</span>")])
           (row [":phase 未指定時の既定 phase"
                 (str "<span class=\"num\">" phase/default-phase "</span> ("
                      (esc (:label (get phase/phases phase/default-phase)))
                      ") — 最も保守的な段を既定にする")])])))

(defn- ledger-section [ledger]
  (section
   (str "12. 監査台帳 (append-only) 全 " (count ledger) " 件")
   "この実行が書いた決定事実の全件。commit も hold も等しく残る。"
   (table ["#" "fact" "op" "site" "zone" "disposition" "basis"]
          (map-indexed
           (fn [i f]
             (row [(str "<span class=\"num\">" (inc i) "</span>")
                   (code (kw (:t f)))
                   (code (kw (or (:op f) :n-a)))
                   (code (:site-id f))
                   (code (:zone-id f))
                   (disposition-cell (:disposition f))
                   (if (seq (:basis f))
                     (str/join " " (map #(str "<code>" (esc (kw %)) "</code>") (:basis f)))
                     "<span class=\"muted\">—</span>")]))
           ledger))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole console from a completed `run-demo!` result. Every
  value below is read out of that run -- there is no literal entity id,
  count, verdict or hold reason in this function."
  [{:keys [db runs permit-drill]}]
  (let [ledger    (vec (store/ledger db))
        records   (vec (store/all-dispatch-records db))
        refusals  (hard-refusals ledger)
        gate-held (phase-gate-holds ledger)
        decisions (human-decisions ledger)
        attrib    (vec (attribution runs records))
        verdict   (attribution-verdict attrib)
        rules     (->> refusals (mapcat :violations) (map :rule) distinct sort vec)]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-8129-facade · 外装/路面清掃ロボット派遣 オペレータコンソール</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>外装・路面清掃ロボット派遣アクター — オペレータコンソール</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">ISIC Rev.5 8129</span> "
     "<span class=\"badge\">read-only サンプル</span> "
     "<span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">物理派遣は常に人間承認</span></p>\n"
     "<p class=\"subtitle\">このページはビルド時に "
     "<code>facadecleaningops.render-html</code> が実アクター "
     "(<code>operation</code> → <code>governor</code> → <code>store</code>) を"
     "実際に走らせて生成したもの。以下の "
     "<span class=\"num\">" (count runs) "</span> 実行・"
     "<span class=\"num\">" (count ledger) "</span> 監査事実・"
     "<span class=\"num\">" (count records) "</span> commit 記録は、"
     "すべてその実行の出力であって手書きではない。"
     "ガバナの HARD 拒否 <span class=\"num\">" (count refusals) "</span> 件（規則 "
     "<span class=\"num\">" (count rules) "</span> 種: "
     (str/join "・" (map #(str "<code>" (esc (kw %)) "</code>") rules))
     "）、ロールアウト保留 <span class=\"num\">" (count gate-held) "</span> 件。</p>\n"
     "<main>\n"
     (sites-section db)
     (zones-section db permit-drill)
     (sds-section db)
     (sds-matrix-section db runs)
     (runs-section runs)
     (refusals-section refusals)
     (phase-holds-section gate-held)
     (human-section decisions permit-drill)
     (records-section attrib verdict)
     (gate-matrix-section)
     (config-section)
     (ledger-section ledger)
     "</main>\n"
     "<footer>\n"
     "  <p>生成元: <code>clojure -M:dev:render-html</code> "
     "(<code>facadecleaningops.render-html</code>)。"
     "種データは <code>facadecleaningops.store/demo-data</code>、"
     "as-of 日付は <span class=\"num\">" (esc today) "</span> "
     "で明示的にスレッドされており実時計は読まない。"
     "同じ種から再生成するとバイト単位で同一になる（ページ本文にタイムスタンプを含めない）。</p>\n"
     "  <p>ページに出せなかった値はここに明記する: "
     "この store の <code>commit-record!</code> が書く記録には "
     "<code>:dispatch-id</code> が付かないため "
     "(<code>facadecleaningops.operation/commit-record</code> が生成しない)、"
     "<code>store/dispatch-record</code> による ID 引きは commit 済み記録を解決できない。"
     "本ページが記録と実行を結ぶキーは、要求の <code>:patch</code> 経由で実際に流れた "
     "<code>:run-id</code> である。</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn -main [& args]
  (let [out    (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        ledger (vec (store/ledger (:db result)))
        ;; Stage 1: did anything hold at all?
        holds  (filter #(= :hold (:disposition %)) ledger)
        ;; Stage 2: did the GOVERNOR refuse anything, carrying at least
        ;; one real violation? A phase-gating hold reaches the ledger as
        ;; a `:governor-hold` fact with an EMPTY `:violations` vector, so
        ;; stage 1 alone would be satisfied by a run in which the
        ;; governor never refused a single proposal.
        refusals (hard-refusals ledger)
        rules    (->> refusals (mapcat :violations) (keep :rule) distinct sort vec)
        ;; Stage 3: did a HUMAN-APPROVED path actually reach :commit?
        ;;
        ;; Measured from the graph's real final state, NOT from the
        ;; store ledger: this actor's `:commit` node appends only
        ;; `governor/…`-shaped `commit-fact`s, so the
        ;; `{:t :approval-granted}` fact emitted by
        ;; `facadecleaningops.operation`'s `:request-approval` node
        ;; never leaves the run's `:audit` channel and is absent from
        ;; `store/ledger` by construction. Asserting on it here would be
        ;; an invariant that can never pass no matter how many humans
        ;; approve -- a check that cannot be satisfied is as useless as
        ;; one that cannot fail.
        approved (filter #(and (= :approved (:human %))
                               (= :commit (:disposition %)))
                         (:runs result))
        commits  (filter #(= :committed (:t %)) ledger)]
    (when (empty? holds)
      (throw (ex-info "render-html refuses to write: the scenario produced NO holds at all"
                      {:ledger-facts (count ledger)})))
    (when (empty? refusals)
      (throw (ex-info (str "render-html refuses to write: " (count holds)
                           " hold(s) were recorded but NONE is a governor HARD refusal "
                           "carrying a violation -- a page showing only phase-gating holds "
                           "would misrepresent this actor as never refusing anything")
                      {:holds (count holds) :hard-refusals 0})))
    (when (empty? rules)
      (throw (ex-info "render-html refuses to write: HARD refusals carry no :rule"
                      {:hard-refusals (count refusals)})))
    (when (empty? approved)
      (throw (ex-info (str "render-html refuses to write: no human-approved run reached "
                           ":commit -- a page showing only refusals would misrepresent this "
                           "actor as never letting anything through")
                      {:hard-refusals (count refusals)
                       :runs (count (:runs result))
                       :human-approved (count (filter #(= :approved (:human %)) (:runs result)))})))
    (when (empty? commits)
      (throw (ex-info "render-html refuses to write: nothing was committed to the SSoT"
                      {:hard-refusals (count refusals)})))
    ;; Create the output directory if absent, so a fresh clone (or a
    ;; render into a scratch dir for the determinism check) works
    ;; without a manual mkdir.
    (io/make-parents out)
    (spit out (render result))
    (println "wrote" out
             (str "(" (count (:runs result)) " runs, "
                  (count ledger) " ledger facts, "
                  (count refusals) " HARD governor refusals over "
                  (count rules) " distinct rules " (pr-str rules) ", "
                  (count (phase-gate-holds ledger)) " rollout-gate holds, "
                  (count approved) " human approvals, "
                  (count commits) " commits)"))))
