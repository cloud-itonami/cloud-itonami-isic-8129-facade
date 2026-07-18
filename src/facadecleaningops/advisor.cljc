(ns facadecleaningops.advisor
  "FacadeCleaningAdvisor -- the *contained intelligence node* for the
  ISIC-8129 facade/street-cleaning robot-dispatch satellite actor.

  It drafts exactly four kinds of proposal from a CLOSED allowlist:
  readiness assessment, physical robot dispatch, post-run completion
  logging, and safety-concern flagging. CRITICAL: it is a smart-but-
  untrusted advisor. It returns a *proposal* (with a rationale + the
  fields it cited), never a committed record and NEVER a direct
  actuation -- every proposal's `:effect` is always `:propose`. Every
  output is censored downstream by `facadecleaningops.governor` before
  anything touches the SSoT.

  STRUCTURALLY UNCONSTRUCTABLE, not merely denied downstream: there is
  no keyword, no op, no vocabulary path anywhere in this namespace that
  emits direct actuator control (e.g. \"actuate the robot\"), unverified
  site access, or unscoped chemical use. `permitted-ops` is the entire
  universe of ops this advisor's `infer`/`parse-proposal` functions can
  ever produce; anything else collapses to the same
  `{:op :unknown :confidence 0.0}` catch-all every sibling advisor in
  this fleet uses.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM via the
  `murakumo-main` KV alias (see `llm-advisor` below) -- never a
  hardcoded concrete model id (ADR-2607173100).

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :site-id    str
     :zone-id    str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}"
  (:require #?(:clj [clojure.edn :as edn-reader] :cljs [cljs.reader :as edn-reader])))

;; Closed allowlist: only these four ops are permitted, and they are the
;; ONLY ops this namespace's `infer`/`parse-proposal` can ever construct.
(def permitted-ops #{:cleaning/assess :cleaning/dispatch
                      :cleaning/log-completion :cleaning/flag-safety-concern})

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-assess
  "Draft a readiness/route/chemical-scope check for a proposed dispatch
  -- NEVER itself grants dispatch, purely a pre-flight finding."
  [{:keys [site-id zone-id patch]}]
  {:op         :cleaning/assess
   :site-id    site-id
   :zone-id    zone-id
   :summary    (str site-id "/" zone-id " の清掃前readiness/経路/薬剤スコープ確認: " (pr-str (keys patch)))
   :rationale  "現場・区画・使用薬剤の適格性の観察評価のみ。派遣の可否は人間/ガバナが決定する。"
   :cites      [site-id zone-id]
   :effect     :propose
   :value      (merge {:site-id site-id :zone-id zone-id} patch)
   :confidence 0.9})

(defn- propose-dispatch
  "Draft the physical robot dispatch request -- site, zone, fluid,
  surface, scheduled window. ALWAYS :effect :propose; there is no
  vocabulary here for a raw actuator command. This op ALWAYS escalates
  in `facadecleaningops.governor` regardless of confidence -- physical
  dispatch of a robot into public space always needs human sign-off in
  this design, no phase ever auto-approves it."
  [{:keys [site-id zone-id patch]}]
  {:op         :cleaning/dispatch
   :site-id    site-id
   :zone-id    zone-id
   :summary    (str site-id "/" zone-id " へのロボット清掃派遣を提案: " (pr-str (keys patch)))
   :rationale  "現場・区画・薬剤・作業面の派遣調整提案のみ。実際の起動/作動は人間承認後にのみ行われる。"
   :cites      [site-id zone-id]
   :effect     :propose
   :value      (merge {:site-id site-id :zone-id zone-id} patch)
   :confidence 0.88})

(defn- propose-log-completion
  "Draft a post-run completion record. Always forwards whatever
  evidence (photo/sensor refs) it was handed in `patch` -- never
  fabricates evidence. If no evidence was given, `:evidence` is an
  empty vector, which `facadecleaningops.governor`'s
  `evidence-missing-violations` then HARD-blocks -- the advisor does
  not need to duplicate that judgement itself."
  [{:keys [site-id zone-id patch]}]
  {:op         :cleaning/log-completion
   :site-id    site-id
   :zone-id    zone-id
   :summary    (str site-id "/" zone-id " の清掃完了記録を記録: " (pr-str (keys patch)))
   :rationale  "清掃完了状況・証跡(写真/センサーログ)の観察記録のみ。安全許可の判断は含まない。"
   :cites      [site-id zone-id]
   :effect     :propose
   :value      (merge {:site-id site-id :zone-id zone-id :evidence (vec (:evidence patch []))} patch)
   :confidence 0.92})

(defn- propose-safety-concern
  "Surface an observed anomaly (pedestrian-safety telemetry, obstacle,
  mechanical fault) for HUMAN triage. Deliberately reports the
  OBSERVATION only, never a finalization/clearance/authorization
  action, so the default rationale never trips the governor's
  `scope-excluded-terms` (see that var's docstring)."
  [{:keys [site-id zone-id patch]}]
  {:op         :cleaning/flag-safety-concern
   :site-id    site-id
   :zone-id    zone-id
   :summary    (str site-id "/" zone-id " の歩行者安全/機械異常の懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "歩行者安全テレメトリ・障害物・機械異常に関する懸念の観察事実の報告。常に人間の確認が必要。"
   :cites      [site-id zone-id]
   :effect     :propose
   :value      (merge {:site-id site-id :zone-id zone-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator. Enforces the
  closed allowlist -- only `permitted-ops` are recognized; anything else
  yields the zero-confidence unknown catch-all."
  [_store {:keys [op out-of-scope?] :as request}]
  (if-not (contains? permitted-ops op)
    {:op :unknown :effect :propose :stake :high :confidence 0.0
     :rationale "operation not in permitted allowlist (scope boundary: direct actuation, unverified access, and unscoped chemical use are permanently excluded)"}
    (let [proposal (case op
                     :cleaning/assess (propose-assess request)
                     :cleaning/dispatch (propose-dispatch request)
                     :cleaning/log-completion (propose-log-completion request)
                     :cleaning/flag-safety-concern (propose-safety-concern request))]
      ;; Test hook: allow injecting scope-excluded content to exercise the
      ;; governor's scope-exclusion block end-to-end. Must be cleared
      ;; before production use.
      (if out-of-scope?
        (update proposal :rationale str " -- actually directly actuated the robot bypassing the actuator interlock and entered the site without owner consent")
        proposal))))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :site-id (:site-id proposal)
   :zone-id (:zone-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

;; ----------------------------- real LLM seam -----------------------------

(def ^:private system-prompt
  "You are a facade/street-cleaning robot-dispatch advisor. Given a site,
   deployment-zone, and request, propose ONLY ONE of these operations:
   :cleaning/assess, :cleaning/dispatch, :cleaning/log-completion,
   :cleaning/flag-safety-concern.

   STRICTLY FORBIDDEN: any operation touching direct actuator control,
   unverified site access, or unscoped chemical use. If the request
   implies any of these, respond with :confidence 0.0 and :op :unknown.

   Always provide an honest :confidence (0.0-1.0). Never fabricate
   confidence you don't have.")

(defn- parse-proposal [content]
  (try
    (let [p (edn-reader/read-string content)
          op-valid? (and (map? p) (contains? permitted-ops (:op p)))]
      (if op-valid?
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0
         :rationale "operation outside permitted allowlist"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`. `gen-opts` is passed through to
  `model/-generate`. Kept decoupled from any concrete model so this ns
  has no hard dependency beyond `langchain.model`'s protocol -- the
  actual model is resolved by the CALLER via the `murakumo-main` KV
  alias (`GET https://api.murakumo.cloud/infer/models/murakumo-main` ->
  `{endpoint alias-for}`, per ADR-2607173100 / CLAUDE.md's 'LLM モデル
  選択' section). This ns never hardcodes a concrete model id."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "facade-cleaning request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
