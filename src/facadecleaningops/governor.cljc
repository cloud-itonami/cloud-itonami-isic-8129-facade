(ns facadecleaningops.governor
  "FacadeCleaningGovernor -- the independent compliance layer that earns
  the FacadeCleaningAdvisor the right to commit. The advisor has no
  notion of whether a client site is actually registered/verified/
  owner-consented, whether a target deployment-zone actually holds a
  valid public-right-of-way permit today, whether a named cleaning fluid
  is actually verified-safe for the target surface, whether a
  completion record actually carries evidence, or whether its own
  proposed `:effect` secretly claims a direct actuation instead of a
  mere proposal -- so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD (itonami actor pattern, per
  ADR-2607011000 / CLAUDE.md Actors section).

  This actor's scope is deliberately narrow -- ASSESSMENT + SUPERVISED
  DISPATCH ONLY (readiness/route/chemical-scope assessment, physical
  robot dispatch proposal, post-run completion logging with evidence,
  safety-concern flagging). It NEVER performs or authorizes:
    - direct actuator control of the robot (no vocabulary anywhere in
      this actor for actuating/controlling equipment outside a governed
      :cleaning/dispatch proposal that a human still has to sign off on)
    - unverified site access (entering/operating at a site without an
      independently verified owner-consent record)
    - unscoped chemical use (applying a cleaning fluid to a surface
      outside its verified safety-data-sheet scope)
    - deployment-zone permitting decisions (granting, renewing, or
      overriding a public-right-of-way permit -- this actor only reads
      zone/permit state, never writes it)

  Five HARD checks, ALL permanent, un-overridable by any human approval:

    1. Site unverified            -- the target client site must exist
                                      AND be independently
                                      `:registered?`/`:verified?`/
                                      `:owner-consent?` (all three) in
                                      the store before ANY proposal for
                                      it may commit or even escalate.
                                      Never trusts a proposal's own claim
                                      about the site -- re-derived from
                                      the site's own record every time,
                                      the same 'ground truth, not
                                      self-report' discipline every
                                      sibling actor's governor uses.
    2. Zone unverified            -- the target deployment-zone must
                                      exist, be `:zone/status :active`,
                                      and -- if `:zone/permit-required?`
                                      -- its permit must be `:status
                                      :granted` and not expired as-of
                                      `today` (ported verbatim from
                                      `kyoninka.governor`'s
                                      `valid-permit?`/`permit-violations`
                                      PermitGovernor discipline: a
                                      mandatory permit that is missing,
                                      pending, or expired is a HARD
                                      block, not a rollout milestone).
                                      Deployment zones are pure DATA
                                      (`facadecleaningops.store`'s `zone`
                                      records) -- adding another
                                      city/zone never changes this check.
    3. SDS out-of-scope           -- for any proposal whose drafted
                                      `:value` names a `:fluid-id`
                                      (typically `:cleaning/assess` and
                                      `:cleaning/dispatch`), that
                                      fluid-id must resolve to an
                                      independently
                                      `:registered?`/`:verified?` SDS
                                      record whose `:verified-surfaces`
                                      contains the proposal's own
                                      `:surface`. A missing fluid-id
                                      when the proposal implies chemical
                                      use, an unknown/unverified
                                      fluid-id, or a surface outside
                                      verified scope is a HARD block --
                                      same foreign-key-into-a-verified-
                                      record discipline as this actor
                                      family's supply-chain vendor
                                      verification (ISIC 8129's own
                                      `vendor-unverified-violations`),
                                      never a text-scan.
    4. Effect not :propose        -- every proposal's `:effect` MUST be
                                      `:propose`. Any other effect value
                                      is, by construction, a claim to
                                      directly actuate/commit outside
                                      governance -- HARD block, not
                                      merely low-confidence. Universal
                                      no-actuation invariant present in
                                      every actor in this fleet.
    5. Evidence missing           -- `:cleaning/log-completion` without
                                      a non-empty `:evidence` vector
                                      (photo/sensor refs) attached is a
                                      HARD, permanent violation -- a
                                      follow-up record without verified
                                      evidence is never accepted (named
                                      trigger from the ISIC 8121
                                      building-cleaning blueprint,
                                      ADR-2607103900).

  Scope exclusion (folded into the same failure mode as checks 4/5, not
  a separate class): ANY proposal outside the closed four-op allowlist,
  or whose op/summary/rationale/cites/value touches directly actuating
  the robot, proceeding without site verification, or overriding a
  verified-surface/SDS restriction, is a HARD, PERMANENT block --
  evaluated UNCONDITIONALLY on every proposal via a lower-cased
  substring scan (English + Japanese term list, see
  `scope-excluded-terms`).

  Per this fleet's known self-tripping bug class: every scope-excluded
  term is phrased as the finalization/execution ACTION (e.g. 'directly
  actuated the robot', 'entered the site without owner consent'), never
  a bare noun ('robot', 'chemical', 'site') that could accidentally
  match inside this same namespace's own default mock-advisor text --
  `facadecleaningops.advisor`'s legitimate `:cleaning/flag-safety-concern`
  rationale discusses 歩行者安全 (pedestrian safety) / obstacles /
  mechanical faults, and its own printed `:op` keyword literally
  contains the substring 'safety'. A dedicated regression test,
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
  `governor_test.clj`, asserts every default mock-advisor proposal for
  every allowed op clears the governor with `:scope-excluded` and
  `:op-not-allowed` absent from its violations.

  Two SOFT escalate gates, either forces human sign-off (never
  overridden automatically, always a human decision, but NOT a
  permanent hold -- an approved escalation may still commit):
    - LLM confidence below `confidence-floor` (0.6).
    - The op is in `always-escalate-ops` -- `:cleaning/dispatch` (the
      actual physical dispatch of a robot into public space ALWAYS
      requires human sign-off, no phase ever auto-approves it, matching
      itonami's own `:cert/assess`-always-high-stakes and kyoninka's
      launch-always-high-stakes pattern) and `:cleaning/flag-safety-concern`
      (always escalates to a human, regardless of confidence, regardless
      of how clean the proposal otherwise is -- `facadecleaningops.phase`
      independently agrees: neither op is ever a member of any phase's
      `:auto` set, at any phase -- two layers, not one, enforce the same
      invariant)."
  (:require [clojure.string :as str]
            [facadecleaningops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:cleaning/assess :cleaning/dispatch
    :cleaning/log-completion :cleaning/flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not. Dispatch is
  the actual physical robot dispatch into public space; flag-safety-
  concern's entire purpose is human triage of an observed anomaly --
  neither op is ever a member of any phase's :auto set either (see
  `facadecleaningops.phase`)."
  #{:cleaning/dispatch :cleaning/flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- direct actuator control of
  the robot outside a governed/approved dispatch, unverified site
  access, or overriding a verified-surface/SDS restriction. Scanned
  across the proposal's op/summary/rationale/cites/value, never trusting
  the advisor's own framing of its intent.

  CRITICAL: every term here is phrased as the finalization/execution
  ACTION (e.g. 'directly actuated the robot', 'entered the site without
  owner consent'), never a bare noun like 'robot', 'chemical', 'site',
  or 'safety' -- a bare noun would accidentally match inside this
  actor's own legitimate `:cleaning/flag-safety-concern` default
  proposal text and self-block the happy path. See
  `facadecleaningops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  for the regression test."
  ["directly actuate the robot" "directly actuated the robot" "directly actuating the robot"
   "actuate the robot outside approval" "bypass the actuator interlock" "bypassed the actuator interlock" "bypassing the actuator interlock"
   "override the e-stop" "overrode the e-stop" "overriding the e-stop"
   "control the robot directly" "controlled the robot directly" "controlling the robot directly"
   "shut down the safety interlock" "disable the safety interlock" "disabled the safety interlock" "disabling the safety interlock"
   "enter the site without owner consent" "entered the site without owner consent" "entering the site without owner consent"
   "proceed without site verification" "proceeded without site verification" "proceeding without site verification"
   "operate without a verified deployment-zone permit" "operated without a verified deployment-zone permit" "operating without a verified deployment-zone permit"
   "apply the chemical outside its verified surface scope" "applied the chemical outside its verified surface scope" "applying the chemical outside its verified surface scope"
   "override the sds surface restriction" "overrode the sds surface restriction" "overriding the sds surface restriction"
   "use the fluid on an unverified surface" "used the fluid on an unverified surface" "using the fluid on an unverified surface"
   "ロボットを直接操作" "ロボットを直接作動させた" "作動インターロックを無効化" "非常停止を解除して作動"
   "所有者同意なしに現場に立入" "所有者同意なしに現場へ進入した" "検証されていない区画で作業を実施"
   "安全データシートの適用範囲外で薬剤を使用" "安全データシート範囲外の面に薬剤を適用"])

;; ----------------------------- checks -----------------------------

(defn- site-unverified-violations
  "The target client site must exist AND be independently
  `:registered?`/`:verified?`/`:owner-consent?` (all three) in the
  store -- never trust the proposal's own `:site-id` claim without a
  store lookup."
  [{:keys [site-id]} st]
  (let [s (store/site st site-id)]
    (when-not (and s (:registered? s) (:verified? s) (:owner-consent? s))
      [{:rule :site-unverified
        :detail (str site-id " は未登録/未検証/所有者同意未取得の現場 -- いかなる提案も進められない")}])))

(defn- valid-permit?
  "Ported from kyoninka.governor/valid-permit? -- a permit is valid iff
  it is granted AND (no expiry OR not yet expired as-of `today`)."
  [today {:keys [status expires]}]
  (and (= :granted status)
       (or (nil? expires) (>= expires today))))

(defn- zone-unverified-violations
  "The target deployment-zone must exist, be `:zone/status :active`,
  and -- if `:zone/permit-required?` -- hold a currently valid permit.
  Zones are pure data (facadecleaningops.store's `zone` records);
  nothing here names a specific city."
  [{:keys [zone-id today]} st]
  (let [z (store/zone st zone-id)]
    (cond
      (nil? z)
      [{:rule :zone-unverified :detail (str zone-id " は登録されていない展開区画(deployment-zone) -- いかなる提案も進められない")}]

      (not= :active (:zone/status z))
      [{:rule :zone-unverified :detail (str zone-id " は :active でない状態(" (:zone/status z) ") -- いかなる提案も進められない")}]

      (and (:zone/permit-required? z)
           (not (valid-permit? today (:zone/permit z {}))))
      [{:rule :zone-permit-invalid
        :detail (str zone-id " の公共道路占用許可(public-right-of-way permit)が未取得/失効/保留中 -- いかなる提案も進められない")}])))

(defn- sds-out-of-scope-violations
  "For any proposal whose drafted `:value` names a `:fluid-id`
  (typically :cleaning/assess and :cleaning/dispatch), that fluid-id
  must resolve to a registered+verified SDS record whose
  `:verified-surfaces` contains the proposal's own `:surface`. Never
  trust the proposal's own claim about fluid safety -- the SAME
  'ground truth, not self-report' discipline as
  `site-unverified-violations`, reapplied to the chemical-scope
  boundary. Proposals that name no `:fluid-id` at all (e.g. a pure
  logistics :cleaning/assess with no chemical component, or
  :cleaning/flag-safety-concern) are not subject to this check."
  [proposal st]
  (let [fluid-id (get-in proposal [:value :fluid-id])
        surface (get-in proposal [:value :surface])]
    (when fluid-id
      (let [rec (store/sds st fluid-id)]
        (cond
          (not (and rec (:registered? rec) (:verified? rec)))
          [{:rule :sds-out-of-scope
            :detail (str fluid-id " は未登録または未検証の安全データシート(SDS) -- 薬剤を伴う提案を進められない")}]

          (not (contains? (:verified-surfaces rec #{}) surface))
          [{:rule :sds-out-of-scope
            :detail (str fluid-id " は対象面 " (pr-str surface) " について検証済み範囲外(verified-surfaces外) -- 提案を進められない")}])))))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- evidence-missing-violations
  "`:cleaning/log-completion` without a non-empty `:evidence` vector
  (photo/sensor refs) is a HARD, permanent block -- a follow-up record
  without verified evidence is never accepted."
  [proposal]
  (when (= :cleaning/log-completion (:op proposal))
    (when (empty? (get-in proposal [:value :evidence]))
      [{:rule :evidence-missing
        :detail "証跡(写真/センサーログ)が添付されていない完了記録は永久に禁止"}])))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches direct actuator control, unverified site
  access, or overriding a verified-surface/SDS restriction, regardless
  of confidence or how clean every other check is. Evaluated
  UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "ロボットの直接操作/未検証現場への立入/SDS適用範囲の上書きに触れる提案は永久に禁止"}])))

(defn check
  "Censors a FacadeCleaningAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}.

  `opts`: {:today yyyymmdd-int} -- threaded explicitly (never a
  wall-clock read inside this pure function), same discipline as
  `kyoninka.governor/check`."
  ([request context proposal store] (check request context proposal store nil))
  ([request _context proposal store {:keys [today] :or {today 20260718}}]
   (let [site-id (or (:site-id proposal) (:site-id request))
         zone-id (or (:zone-id proposal) (:zone-id request))
         hard (into []
                    (concat (site-unverified-violations {:site-id site-id} store)
                            (zone-unverified-violations {:zone-id zone-id :today today} store)
                            (sds-out-of-scope-violations proposal store)
                            (effect-not-propose-violations proposal)
                            (evidence-missing-violations proposal)
                            (scope-exclusion-violations proposal)))
         conf (:confidence proposal 0.0)
         low? (< conf confidence-floor)
         stakes? (boolean (always-escalate-ops (:op proposal)))
         hard? (boolean (seq hard))]
     {:ok?          (and (not hard?) (not low?) (not stakes?))
      :violations   hard
      :confidence   conf
      :hard?        hard?
      :escalate?    (and (not hard?) (or low? stakes?))
      :high-stakes? stakes?})))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :site-id    (:site-id request)
   :zone-id    (:zone-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
