(ns facadecleaningops.phase
  "Phase 0->3 staged rollout for the ISIC-8129 facade/street-cleaning
  robot-dispatch satellite actor.

    Phase 0  read-only              -- no writes, still governor-gated.
    Phase 1  assisted                -- completion-logging allowed,
                                        every write needs human approval.
    Phase 2  assisted-dispatch      -- adds readiness/route/chemical-
                                        scope assessment proposals,
                                        still approval-gated.
    Phase 3  supervised-auto        -- governor-clean, high-confidence
                                        `:cleaning/log-completion`/
                                        `:cleaning/assess` may
                                        auto-commit. `:cleaning/dispatch`
                                        and `:cleaning/flag-safety-concern`
                                        NEVER auto-commit, at any phase
                                        -- the actual physical dispatch
                                        of a robot into public space
                                        always requires human sign-off
                                        in this design (the governor's
                                        own `always-escalate-ops` keeps
                                        both out of `:commit`
                                        independently -- two layers, not
                                        one, agree on this).

  `:cleaning/dispatch` and `:cleaning/flag-safety-concern` are
  deliberately ABSENT from every phase's `:auto` set, including phase 3
  -- a permanent structural fact, not a rollout milestone still to come.

  ------------------------------------------------------------------
  CRITICAL FIX -- missing/unset :phase must default to the MOST
  CONSERVATIVE tier, never the most permissive.
  ------------------------------------------------------------------

  This is a previously-fixed bug class in this fleet. The bug's origin
  and rationale are documented verbatim in
  `orgs/gftdcojp/cloud-itonami/src/itonami/phase.cljc`'s own
  `default-phase` docstring:

    \"This is directly reachable by any ordinary caller that simply
    omits :phase -- not just malformed/malicious input -- so it must be
    the MOST CONSERVATIVE phase, never the most permissive: 'can only
    add caution' (this namespace's own docstring) has to hold for a
    MISSING phase too, not only an explicitly-set low one.
    kg/default-phase was 3 (supervised, the most permissive tier ...)
    until a live check confirmed a caller who forgets :phase silently
    got maximum autonomy instead of the safe default -- the same
    accidental-fail-open shape already found and fixed ... in the
    shared talent.phase template ..., plus siblings newscaster.phase,
    wami.phase, kyoninka.phase, and sng.phase, which all inherited the
    same bug. 1 (assisted) matches those fixes.\"

  `default-phase` here is therefore 1 (assisted, still requires human
  approval for every write), NOT 3. Any caller in this repo that omits
  `:phase` from `context` (e.g.
  `(:phase context phase/default-phase)` in `facadecleaningops.operation`)
  gets the conservative default, and `gate`'s own fallback for an
  unrecognized phase NUMBER also resolves to this same conservative
  default -- both paths, not just one, must honor 'can only add
  caution' for a MISSING phase."
  (:require [facadecleaningops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:cleaning/dispatch` and
;; `:cleaning/flag-safety-concern` are members of `write-ops`
;; (governor-gated like any write) but are NEVER members of any phase's
;; `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"          :writes #{}                                                             :auto #{}}
   1 {:label "assisted"           :writes #{:cleaning/log-completion}                                     :auto #{}}
   2 {:label "assisted-dispatch"  :writes #{:cleaning/log-completion :cleaning/assess}                     :auto #{}}
   3 {:label "supervised-auto"    :writes write-ops
      :auto #{:cleaning/log-completion :cleaning/assess}}})

(def default-phase
  "The conservative default for a MISSING :phase -- 1 (assisted), NOT 3.
  See this namespace's own docstring for the fixed-bug provenance
  (itonami.phase's default-phase docstring)."
  1)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean.
  - an UNRECOGNIZED phase number also falls back to `default-phase`
    (1, conservative) -- 'can only add caution' has to hold for a
    missing/unrecognized phase, not only an explicitly-set low one.
  - `:cleaning/dispatch`/`:cleaning/flag-safety-concern` are never
    auto-eligible at any phase, so they always escalate once the
    governor clears them (or hold if the governor doesn't) -- but that
    escalation is actually already forced upstream by the governor's
    own `always-escalate-ops`/`high-stakes?`, so this function never
    even sees a `:commit` disposition for them in the first place."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a FacadeCleaningGovernor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
