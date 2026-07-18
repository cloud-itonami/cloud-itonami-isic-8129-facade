(ns facadecleaningops.operation
  "OperationActor -- one facade/street-cleaning-robot request = one
  supervised actor run, expressed as a langgraph-clj StateGraph. The
  advisor (FacadeCleaningAdvisor) is sealed into a single node
  (:advise); its proposal is ALWAYS routed through the
  FacadeCleaningGovernor (:govern) and the rollout phase gate (:decide)
  before anything commits to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (MemStore today)              - `store` arg
    - the Advisor  (mock | real LLM)              - :advisor opt
    - the Phase    (0->3 rollout)                 - :phase in ctx

  One graph run = one facade/street-cleaning-robot request (intake ->
  advise -> govern -> decide -> commit | hold | approval). No unbounded
  inner loop -- each operation is auditable and checkpointed.

  Human-in-the-loop = real approval workflow: `interrupt-before
  #{:request-approval}` pauses the actor and hands the decision to a
  human facade-cleaning-operations coordinator/deployment-zone
  compliance officer. The approver resumes with `{:approval {:status
  :approved}}` (or :rejected).

  ------------------------------------------------------------------
  SAFETY-CRITICAL FIX -- :request-approval re-verifies against the LIVE
  store at resume time, never trusts the pre-interrupt verdict.
  ------------------------------------------------------------------

  Every sibling actor read while researching this repo (including this
  satellite's own parent, `industrialcleaningops.operation`) computes
  `verdict` once, in the `:govern` node, BEFORE the interrupt, and then
  the `:request-approval` node -- which runs AFTER a human has had an
  arbitrary amount of time to review -- only looks at the human's
  `:status`, never re-checking whether the world has changed in the
  meantime. That is a real bypass path here specifically: a deployment-
  zone's public-right-of-way permit can expire, a site's owner-consent
  can be revoked, or new pedestrian-safety telemetry can arrive during
  exactly the review window a HARD governor block is supposed to be
  protecting against. `:request-approval` below therefore re-runs
  `governor/check` against the LIVE `store` closed over by this graph
  (not a snapshot captured at intake time) BEFORE even looking at the
  human's approval decision -- if a fresh HARD violation now exists, the
  proposal is held regardless of what the human clicked. See
  `facadecleaningops.governor`'s docstring and `actor_test.clj`'s
  `stale-verdict-does-not-bypass-fresh-hard-violation-at-resume`."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [facadecleaningops.advisor :as advisor]
            [facadecleaningops.governor :as governor]
            [facadecleaningops.phase :as phase]
            [facadecleaningops.store :as store]))

(defn- commit-fact [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :site-id    (:site-id request)
   :zone-id    (:zone-id request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)})

(defn- commit-record [request _context proposal]
  {:op      (:op proposal)
   :site-id (:site-id request)
   :zone-id (:zone-id request)
   :value   (or (:value proposal) {})
   :payload (:value proposal)})

(defn build
  "Compiles an OperationActor graph bound to `store` (any
  `facadecleaningops.store/Store`).
  opts:
    :advisor      -- a `facadecleaningops.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id/role/phase/today
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; FacadeCleaningAdvisor inference (the contained intelligence
      ;; node) -- proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; FacadeCleaningGovernor -- independent censor (separate system
      ;; than the advisor).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store
                                    {:today (:today context)})}))

      ;; Decide: governor disposition, then the rollout-phase gate
      ;; (which can only add caution). HARD governor violations -> HOLD
      ;; (no override).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :site-id (:site-id request) :zone-id (:zone-id request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :always-escalate
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)
               :audit [(commit-fact request context proposal)]}))))

      ;; Approval handoff -- paused by interrupt-before. A human
      ;; operator resumes with :approval. Before honoring that
      ;; decision, RE-RUN the governor against the LIVE store -- a
      ;; disqualifying fact recorded during the human-review window
      ;; (zone permit expiry, revoked site consent, a fresh safety
      ;; signal) must not be silently bypassed by the stale
      ;; pre-interrupt `verdict`. This is the actual safety-critical
      ;; behavior of this node; see this namespace's docstring.
      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval]}]
          (let [fresh-verdict (governor/check request context proposal store
                                              {:today (:today context)})]
            (cond
              ;; A NEW hard violation appeared since :govern ran --
              ;; permanently hold, regardless of the human's decision.
              (:hard? fresh-verdict)
              {:disposition :hold
               :audit [(assoc (governor/hold-fact request context fresh-verdict)
                              :t :approval-superseded-by-fresh-hold)]}

              (not= :approved (:status approval))
              {:disposition :hold
               :audit [(merge (governor/hold-fact request context
                                                   (assoc fresh-verdict :violations
                                                          [{:rule :approver-rejected}]))
                              {:t :approval-rejected})]}

              :else
              {:disposition :commit
               :record (assoc (commit-record request context proposal)
                              :payload (assoc (:value proposal)
                                              :approved-by (:by approval)))
               :audit [{:t :approval-granted :op (:op request)
                        :site-id (:site-id request) :zone-id (:zone-id request) :by (:by approval)}]}))))

      ;; Commit -- the ONLY node that writes the SSoT + audit ledger.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      ;; Hold -- write the rejection to the ledger; no SSoT mutation.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected
                                          :approval-superseded-by-fresh-hold} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one facade-cleaning request to completion or interrupt.
  `thread-id` scopes checkpointing for resume after human approval.
  Returns the full run result: `{:state .. :events .. :status
  :done|:interrupted :frontier ..}`."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn resume!
  "Human-in-the-loop resume: hands an approval decision `{:status
  :approved|:rejected :by str}` to the interrupted :request-approval
  node. See this namespace's docstring for the live-store
  re-verification this triggers before the decision is honored."
  [graph thread-id approval]
  (g/run* graph {:approval approval} {:thread-id thread-id :resume? true}))
