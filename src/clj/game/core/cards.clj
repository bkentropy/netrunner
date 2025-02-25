(in-ns 'game.core)

(declare active? all-installed all-active-installed cards card-init deactivate
         card-flag? gain lose get-card-hosted handle-end-run hardware? ice? is-type?
         program? register-events remove-from-host remove-icon make-card
         resource? rezzed? toast toast-check-mu trash trigger-event
         update-breaker-strength update-hosted! update-ice-strength unregister-events
         use-mu)

;;; Functions for loading card information.
(defn card-def
  "Retrieves a card's abilities definition map."
  [card]
  (if-let [title (:title card)]
    (get cards title)
    (.println *err* (with-out-str
                      (clojure.stacktrace/print-stack-trace
                        (Exception. (str "Tried to select card def for non-existent card: " card))
                        25)))))

(defn find-cid
  "Return a card with specific :cid from given sequence"
  [cid from]
  (some #(when (= (:cid %) cid) %) from))

(defn find-latest
  "Returns the newest version of a card where-ever it may be"
  [state card]
  (let [side (-> card :side to-keyword)]
    (find-cid (:cid card) (concat (all-installed state side)
                                  (-> (map #(-> @state side %) [:hand :discard :deck :rfg :scored]) concat flatten)))))

(defn get-scoring-owner
  "Returns the owner of the scoring area the card is in"
  [state {:keys [cid] :as card}]
  (cond
    (find-cid cid (get-in @state [:corp :scored]))
    :corp
    (find-cid cid (get-in @state [:runner :scored]))
    :runner))

(defn get-card
  "Returns the most recent copy of the card from the current state, as identified
  by the argument's :zone and :cid."
  [state {:keys [cid zone side host type] :as card}]
  (when card
    (if (= type "Identity")
      (get-in @state [(to-keyword side) :identity])
      (if zone
        (if host
          (get-card-hosted state card)
          (some #(when (= cid (:cid %)) %)
                (let [zones (map to-keyword zone)]
                  (if (= (first zones) :scored)
                    (into (get-in @state [:corp :scored]) (get-in @state [:runner :scored]))
                    (get-in @state (cons (to-keyword side) zones))))))
        card))))

;;; Functions for updating cards
(defn update!
  "Updates the state so that its copy of the given card matches the argument given."
  [state side {:keys [type zone cid host] :as card}]
  (cond
    (= type "Identity")
    (when (= side (to-keyword (:side card)))
      (swap! state assoc-in [side :identity] card))

    host
    (update-hosted! state side card)

    :else
    (let [z (cons (to-keyword (or (get-scoring-owner state card) (:side card))) zone)
              [head tail] (split-with #(not= (:cid %) cid) (get-in @state z))]
          (when (not-empty tail)
            (swap! state assoc-in z (vec (concat head [card] (rest tail))))))))

;; Helpers for move
(defn- remove-old-card
  "Removes the old pre-move card from the game state, for use in move"
  [state side {:keys [zone host] :as card}]
  (doseq [s [:runner :corp]]
    (if host
      (remove-from-host state side card)
      (swap! state update-in (cons s (vec zone)) (fn [coll] (remove-once #(same-card? card %) coll))))))

(defn- get-moved-card
  "Get the moved cards with correct abilities and keys hooked up / removed etc."
  [state side {:keys [zone host installed] :as card} to]
  (let [zone (if host (map to-keyword (:zone host)) zone)
        src-zone (first zone)
        target-zone (if (vector? to) (first to) to)
        same-zone? (= src-zone target-zone)
        dest (if (sequential? to) (vec to) [to])
        to-facedown (= dest [:rig :facedown])
        to-installed (#{:servers :rig} (first dest))
        trash-hosted (fn [h]
                       (trash state side
                              (update-in h [:zone] #(map to-keyword %))
                              {:unpreventable true
                               :suppress-event true
                               ;; this handles executives getting trashed before World's Plaza #2949
                               :host-trashed true})
                       ())
        update-hosted (fn [h]
                        (let [newz (flatten (list dest))
                              newh (-> h
                                       (assoc-in [:zone] '(:onhost))
                                       (assoc-in [:host :zone] newz))]
                          (update! state side newh)
                          (unregister-events state side h)
                          (register-events state side (:events (card-def newh)) newh)
                          newh))
        hosted (seq (flatten (map (if same-zone? update-hosted trash-hosted) (:hosted card))))
        ;; Set :seen correctly
        c (if (= :corp side)
            (cond
              ;; Moving rezzed card to discard, explicitly mark as seen
              (and (= :discard (first dest))
                   (rezzed? card))
              (assoc card :seen true)

              ;; Moving card to HQ or R&D, explicitly mark as not seen
              (#{:hand :deck} (first dest))
              (dissoc card :seen)

              ;; Else return card
              :else
              card)
            card)
        c (if (and (or installed
                       host
                       (#{:servers :scored :current} src-zone))
                   (or (#{:hand :deck :discard :rfg} target-zone)
                       to-facedown)
                   (not (:facedown c)))
            (deactivate state side c to-facedown)
            c)
        c (if to-installed
            (assoc c :installed :this-turn)
            (dissoc c :installed))
        c (if to-facedown
            (assoc c :facedown true)
            (dissoc c :facedown))
        moved-card (assoc c :zone dest
                            :host nil
                            :hosted hosted
                            :previous-zone (:zone c))
        ;; Set up abilities for stolen agendas
        moved-card (if (and (= :scored (first dest))
                            (card-flag? moved-card :has-abilities-when-stolen true))
                     (merge moved-card {:abilities (:abilities (card-def moved-card))})
                     moved-card)]
    moved-card))

(defn reset-card
  "Resets a card back to its original state - retaining any data in the :persistent key"
  ([state side {:keys [title cid persistent]}]
   (swap! state update-in [:per-turn] dissoc cid)
   (update! state side (assoc (make-card (server-card title) cid) :persistent persistent))))

(defn move
  "Moves the given card to the given new zone."
  ([state side card to] (move state side card to nil))
  ([state side {:keys [zone host] :as card} to {:keys [front index keep-server-alive force]}]
   (let [zone (if host (map to-keyword (:zone host)) zone)
         src-zone (first zone)
         target-zone (if (vector? to) (first to) to)]
     (if (is-type? card "Fake-Identity")
       ;; Make Fake-Identity cards "disappear"
       (do (deactivate state side card false)
           (remove-old-card state side card))
       (when (and card
                  (or host
                      (some #(same-card? card %) (get-in @state (cons :runner (vec zone))))
                      (some #(same-card? card %) (get-in @state (cons :corp (vec zone)))))
                  (or force
                      (empty? (get-in @state [side :locked (-> card :zone first)]))))
         (trigger-event state side :pre-card-moved card src-zone target-zone)
         (let [dest (if (sequential? to) (vec to) [to])
               moved-card (get-moved-card state side card to)]
           (remove-old-card state side card)
           (let [pos-to-move-to (cond index index
                                      front 0
                                      :else (count (get-in @state (cons side dest))))]
             (swap! state update-in (cons side dest) #(vec (concat (take pos-to-move-to %) [moved-card] (drop pos-to-move-to %)))))
           (let [z (vec (cons :corp (butlast zone)))]
             (when (and (not keep-server-alive)
                        (is-remote? z)
                        (empty? (get-in @state (conj z :content)))
                        (empty? (get-in @state (conj z :ices))))
               (when-let [run (:run @state)]
                 (when (= (last (:server run)) (last z))
                   (handle-end-run state side)))
               (swap! state dissoc-in z)))
           (when-let [move-zone-fn (:move-zone (card-def moved-card))]
             (move-zone-fn state side (make-eid state) moved-card card))
           (trigger-event state side :card-moved card (assoc moved-card :move-to-side side))
           ;; Default a card when moved to inactive zones (except :persistent key)
           (when (#{:discard :hand :deck :rfg :scored} to)
             (reset-card state side moved-card)
             (when-let [icon-card (get-in moved-card [:icon :card])]
               ;; Remove icon and icon-card keys
               (remove-icon state side icon-card moved-card)))
           moved-card))))))

(defn move-zone
  "Moves all cards from one zone to another, as in Chronos Project."
  [state side server to]
  (when-not (seq (get-in @state [side :locked server]))
    (let [from-zone (cons side (if (sequential? server) server [server]))
          to-zone (cons side (if (sequential? to) to [to]))]
      (swap! state assoc-in to-zone (concat (get-in @state to-zone)
                                            (zone to (get-in @state from-zone))))
      (swap! state assoc-in from-zone []))))

(defn add-prop
  "Adds the given value n to the existing value associated with the key in the card.
  Example: (add-prop ... card :counter 1) adds one power/virus counter. Triggers events."
  ([state side card key n] (add-prop state side card key n nil))
  ([state side card key n {:keys [placed] :as args}]
   (let [updated-card (if (has-subtype? card "Virus")
                        (assoc card :added-virus-counter true)
                        card)]
     (update! state side (update-in updated-card [key] #(+ (or % 0) n)))
     (if (= key :advance-counter)
       (do (when (and (ice? updated-card) (rezzed? updated-card)) (update-ice-strength state side updated-card))
           (if-not placed
             (trigger-event state side :advance (get-card state updated-card))
             (trigger-event state side :advancement-placed (get-card state updated-card))))
       (trigger-event state side :counter-added (get-card state updated-card))))))

(defn set-prop
  "Like add-prop, but sets multiple keys to corresponding values without triggering events.
  Example: (set-prop ... card :counter 4 :current-strength 0)"
  [state side card & args]
  (update! state side (apply assoc (cons card args))))

(defn add-counter
  "Adds n counters of the specified type to a card"
  ([state side card type n] (add-counter state side card type n nil))
  ([state side card type n {:keys [placed] :as args}]
   (let [updated-card (if (= type :virus)
                        (assoc card :added-virus-counter true)
                        card)]
     (update! state side (update-in updated-card [:counter type] #(+ (or % 0) n)))
     (if (= type :advancement)
       ;; if advancement counter use existing system
       (add-prop state side card :advance-counter n args)
       (trigger-event state side :counter-added (get-card state updated-card))))))

;;; Deck-related functions
(defn shuffle!
  "Shuffles the vector in @state [side kw]."
  [state side kw]
  (wait-for (trigger-event-sync state side (if (= :corp side) :corp-shuffle-deck :runner-shuffle-deck) nil)
            (swap! state update-in [side kw] shuffle)))

(defn shuffle-into-deck
  [state side & args]
  (let [player (side @state)
        zones (filter #(not (seq (get-in @state [side :locked %]))) args)
        deck (shuffle (reduce concat (:deck player) (for [p zones] (zone :deck (p player)))))]
    (swap! state assoc-in [side :deck] deck)
    (doseq [p zones]
      (swap! state assoc-in [side p] []))))

;;; Misc card functions
(defn is-virus-program?
  [card]
  (and (program? card)
       (has-subtype? card "Virus")))

(defn get-virus-counters
  "Calculate the number of virus counters on the given card, taking Hivemind into account."
  [state card]
  (let [hiveminds (when (is-virus-program? card)
                    (filter #(= (:title %) "Hivemind") (all-active-installed state :runner)))]
    (reduce + (map #(get-counters % :virus) (cons card hiveminds)))))

(defn count-virus-programs
  "Calculate the number of virus programs in play"
  [state]
  (count (filter is-virus-program? (all-active-installed state :runner))))

(defn card->server
  "Returns the server map that this card is installed in or protecting."
  [state card]
  (let [z (:zone card)]
    (get-in @state [:corp :servers (second z)])))

(defn- actual-disable-identity
  "Actually disables the side's identity"
  [state side]
  (let [id (assoc (get-in @state [side :identity]) :disabled true)]
    (update! state side id)
    (unregister-events state side id)
    (when-let [leave-play (:leave-play (card-def id))]
      (leave-play state side (make-eid state) id nil))))

(defn disable-identity
  "Disables the side's identity"
  [state side]
  (let [disable-count (get-in @state [side :identity :num-disables])
        id (assoc (get-in @state [side :identity])
                  :num-disables ((fnil inc 0) disable-count))]
    (update! state side id)
    (when (= 1 (:num-disables id))
      (actual-disable-identity state side))))

(defn disable-card
  "Disables a card"
  [state side card]
  (deactivate state side card)
  (let [c (assoc card :disabled true)]
    (update! state side c))
  (when-let [disable-effect (:disable (card-def card))]
    (resolve-ability state side disable-effect (get-card state card) nil)))

(defn- actual-enable-identity
  "Actually enables the side's identity"
  [state side]
  (let [id (assoc (get-in @state [side :identity]) :disabled false)
        {:keys [events effect]} (card-def id)]
    (update! state side id)
    (when effect
      (effect state side (make-eid state) id nil))
    (when events
      (register-events state side events id))))

(defn enable-identity
  "Enables the side's identity"
  [state side]
  (let [disable-count (get-in @state [side :identity :num-disables])
        id (assoc (get-in @state [side :identity])
                  :num-disables ((fnil dec 1) disable-count))]
    (update! state side id)
    (when (= 0 (:num-disables id))
      (actual-enable-identity state side))))

(defn enable-card
  "Enables a disabled card"
  [state side {:keys [disabled] :as card}]
  (when disabled
    (let [c (dissoc card :disabled)]
      (update! state side c)
      (when (active? card)
        (card-init state side c {:resolve-effect false})))))

(defn flip-facedown
  "Flips a runner card facedown, either manually (if it's hosted) or by calling move to facedown"
  [state side {:keys [host] :as card}]
  (if host
    (let [card (deactivate state side card true)
          card (assoc-in card [:facedown] true)]
      (update! state side card))
    (move state side card [:rig :facedown])))

(defn flip-faceup
  "Flips a runner card facedown, either manually (if it's hosted) or by calling move to correct area.
  Wires events without calling effect/init-data"
  [state side {:keys [host] :as card}]
  (let [card (if host
               (dissoc card :facedown)
               (move state side card (type->rig-zone (:type card))))]
   (card-init state side card {:resolve-effect false :init-data false})
   (when-let [mu (:memoryunits card)]
     (use-mu state mu)
     (toast-check-mu state))
   (when (has-subtype? card "Icebreaker")
     (update-breaker-strength state side card))))
