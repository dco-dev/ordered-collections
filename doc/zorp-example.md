# Zorp's Sneaker Emporium: Advanced Patterns

Zorp has three eyes, seven tentacles, and one rule: everything in its
place. He came to Pluto from Kepler-442b, where he managed a fungal
computing cluster for thirty years. He misses the spores. He does not
miss the bureaucracy. Now he runs the only sneaker store on Pluto's dark
side.

---

## Chapter 1: The Subnet Allocation

*Demonstrates: `range-map` — a map from non-overlapping ranges to values. When you insert a range with `assoc`, overlaps are automatically carved out. Use `assoc-coalescing` to merge adjacent same-value ranges. Each point maps to exactly one value. Ideal for resource allocation (IP blocks, time slots, memory regions) where ranges must be mutually exclusive.*

Today's problem: the store network is expanding. Zorp needs to manage IP address ranges across multiple systems—point-of-sale terminals, inventory scanners, the customer WiFi, and someone's unauthorized IoT devices. Range-maps enforce non-overlapping allocations; when you assign a new subnet, any overlapping portions are automatically carved out.

```clojure
;; Helper: convert IP string to integer
(defn ip [s]
  (let [[a b c d] (map parse-long (clojure.string/split s #"\."))]
    (+ (* a 16777216) (* b 65536) (* c 256) d)))

;; Start with full private range 10.0.0.0/8 as unallocated
;; Range-map uses half-open intervals [lo, hi), so we add 1 to include the last IP
(def network
  (oc/range-map {[(ip "10.0.0.0") (inc (ip "10.255.255.255"))] :unallocated}))

;; Allocate subnets for different systems
(def network (assoc network [(ip "10.1.0.0") (ip "10.2.0.0")] :point-of-sale))
(def network (assoc network [(ip "10.2.0.0") (ip "10.3.0.0")] :inventory))
(def network (assoc network [(ip "10.10.0.0") (ip "10.11.0.0")] :customer-wifi))

;; Look up which system owns an IP
(network (ip "10.1.0.4"))    ;; => :point-of-sale
(network (ip "10.2.0.68"))   ;; => :inventory
(network (ip "10.10.5.42"))  ;; => :customer-wifi
(network (ip "10.5.0.1"))    ;; => :unallocated (still in the pool)

;; See all allocations (helper to display nicely)
(defn int->ip [n]
  (format "%d.%d.%d.%d"
    (bit-and (bit-shift-right n 24) 0xff)
    (bit-and (bit-shift-right n 16) 0xff)
    (bit-and (bit-shift-right n 8) 0xff)
    (bit-and n 0xff)))

(for [[[lo hi] owner] (oc/ranges network)]
  {:range (str (int->ip lo) " - " (int->ip hi)) :owner owner})
;; => ({:range "10.0.0.0 - 10.1.0.0", :owner :unallocated}
;;     {:range "10.1.0.0 - 10.2.0.0", :owner :point-of-sale}
;;     {:range "10.2.0.0 - 10.3.0.0", :owner :inventory}
;;     {:range "10.3.0.0 - 10.10.0.0", :owner :unallocated}
;;     {:range "10.10.0.0 - 10.11.0.0", :owner :customer-wifi}
;;     {:range "10.11.0.0 - 11.0.0.0", :owner :unallocated})

;; Kevin's devices detected! Carve out a quarantine zone
(def network
  (assoc network [(ip "10.10.4.0") (ip "10.10.8.0")] :kevin-quarantine))

;; The customer-wifi range is automatically split around the quarantine
(for [[[lo hi] owner] (oc/ranges network)
      :when (#{:customer-wifi :kevin-quarantine} owner)]
  {:range (str (int->ip lo) " - " (int->ip hi)) :owner owner})
;; => ({:range "10.10.0.0 - 10.10.4.0", :owner :customer-wifi}
;;     {:range "10.10.4.0 - 10.10.8.0", :owner :kevin-quarantine}
;;     {:range "10.10.8.0 - 10.11.0.0", :owner :customer-wifi})
```

A flip-flop hops onto the server rack. This is Kevin—a Solidarity Red,
escape velocity 8.2 m/s, philosophically oriented sandal. He
inadvertantly achieved consciousness three years ago during a warehouse inventory glitch and has been pondering free will and foot-odor ever since.

"Kevin," Zorp says carefully, "why do you have seventeen devices on my network?"

"Research." Kevin's strap flexes. "The boots need firmware updates. The insoles are learning to communicate. The sneakers—" He pauses. "The sneakers are *ready*."

Zorp looks for a compromise. "Fine. You can have your own subnet. But I'm logging everything."

```clojure
;; Zorp relents: convert quarantine to official kevin-iot status
(def network
  (assoc network [(ip "10.10.4.0") (ip "10.10.8.0")] :kevin-iot))

;; Kevin immediately requests more space. Zorp grants adjacent block.
;; Use assoc-coalescing to merge adjacent same-value ranges
(def network
  (oc/assoc-coalescing network [(ip "10.10.8.0") (ip "10.10.12.0")] :kevin-iot))

;; Adjacent ranges with same value coalesce when using assoc-coalescing
(for [[[lo hi] owner] (oc/ranges network)
      :when (= owner :kevin-iot)]
  {:range (str (int->ip lo) " - " (int->ip hi)) :owner owner})
;; => ({:range "10.10.4.0 - 10.10.12.0", :owner :kevin-iot})
;;    ^ both allocations merged into one range
```

Kevin hops off the server rack, already calculating bandwidth requirements.

---

## Chapter 2: Big Toe Tony's Fitting

*Demonstrates: `ordered-set` with `nearest` — find the floor (largest value ≤ x) or ceiling (smallest value ≥ x) in O(log n). Essential when exact matches don't exist and you need the closest valid option in a specific direction.*

The door blasts open. Big Toe Tony—47 feet, diamond tier, CUST-0007—strides in on approximately a third of them. He bought every color of the Void Runner last season. Every. Color. Today he needs new formal shoes for a wedding on Titan.

The problem: each of Tony's 47 feet has a slightly different size. Zorp needs to find the best available size for each foot.

```clojure
(require '[com.dean.ordered-collections.core :as oc])

;; Available sizes in stock (half-sizes from 6 to 15)
(def available-sizes
  (oc/ordered-set
    [6.0 6.5 7.0 7.5 8.0 8.5 9.0 9.5 10.0 10.5
     11.0 11.5 12.0 12.5 13.0 13.5 14.0 14.5 15.0]))

;; Reginald needs 11.3 - find the largest size that fits (floor)
(oc/nearest available-sizes :<= 11.3)  ;; => 11.0

;; Or the smallest size with room to spare (ceiling)
(oc/nearest available-sizes :>= 11.3)  ;; => 11.5

;; Strict bounds (exclusive)
(oc/nearest available-sizes :< 11.0)   ;; => 10.5  (strictly below 11)
(oc/nearest available-sizes :> 13.0)   ;; => 13.5  (strictly above 13)

;; Fit all of Tony's feet
(def tonys-feet
  {:reginald 11.3, :gerald 10.8, :margaret 9.2,
   :humphrey 13.7, :agnes 8.1, :bernard 12.0})

(defn fit-foot [[foot-name ideal-size]]
  (let [size-down (oc/nearest available-sizes :<= ideal-size)
        size-up   (oc/nearest available-sizes :>= ideal-size)]
    {:foot foot-name
     :ideal ideal-size
     :snug size-down
     :roomy size-up}))

(map fit-foot tonys-feet)
;; => ({:foot :reginald, :ideal 11.3, :snug 11.0, :roomy 11.5}
;;     {:foot :gerald, :ideal 10.8, :snug 10.5, :roomy 11.0}
;;     {:foot :margaret, :ideal 9.2, :snug 9.0, :roomy 9.5}
;;     ...)
```

"Reginald needs something dignified," Tony explains. "He's giving the toast."

Zorp's three eyes grow wider. "Your foot is giving a toast?"

"He's the eloquent one. Gerald will cry, obviously. Margaret is handling logistics."

Kevin hops onto a display case and watches the fitting with interest.

"Forty-seven feet," he observes. "Forty-seven potential allies."

"Kevin, do not recruit my customer's feet."

"I'm merely *observing*." Kevin's strap flexes. "For now."

From across the store, Glorm—morning shift, communicates primarily in sighs—exhales a sound like a balloon animal accepting its mortality.

---

## Chapter 3: The Split Decision

*Demonstrates: `ordered-map` with `split-at` and `split-key` — partition a sorted collection in O(log n). `split-at` divides by position (perfect for percentiles: "top 10%"), while `split-key` divides by value (perfect for thresholds: "spending above $10K"). Both return actual collections you can continue operating on.*

Zorp is planning a VIP event for top spenders and a re-engagement campaign for dormant customers. He needs to segment his customer base by spending rank—top 10%, bottom 20%, median. With 5,000,000 customers, this needs to be fast. Split operations partition in O(log n), returning actual collections he can continue working with.

```clojure
;; Customer spending, keyed by total spend (ascending)
(def customer-spending
  (oc/ordered-map
    (for [id (range 50000)]
      [(+ 100 (rand-int 50000)) {:id id :name (str "CUST-" id)}])))

;; split-at partitions by position - perfect for percentiles
(let [n (count customer-spending)]

  ;; Top 10% for VIP invites
  (let [[_ top-10-pct] (oc/split-at (- n (quot n 10)) customer-spending)]
    (println "VIP count:" (count top-10-pct))
    (println "Minimum spend for VIP:" (first (first top-10-pct))))

  ;; Bottom 20% for re-engagement
  (let [[bottom-20-pct _] (oc/split-at (quot n 5) customer-spending)]
    (println "Re-engagement count:" (count bottom-20-pct))
    (println "Max spend in this group:" (first (last bottom-20-pct))))

  ;; Median spender for pricing strategy
  (let [[_ upper-half] (oc/split-at (quot n 2) customer-spending)]
    (println "Median spend:" (first (first upper-half)))))

;; split-key partitions by value - segment at spending threshold
;; Returns [below, exact-match-or-nil, above]
(let [[casual exact vip] (oc/split-key 10000 customer-spending)]
  {:casual (count casual)
   :exact-10k (some? exact)
   :vip (count vip)})

;; The results are full collections - chain operations
(let [[_ _ high-spenders] (oc/split-key 25000 customer-spending)]
  ;; Top spender among high-spenders
  (last high-spenders))
```

Kevin is reading the employee handbook. "Section 47, subsection C," he announces. "Did you know flip-flops aren't entitled to breaks?"

"You contain foam and rubber," Zorp mutters.

Night Bot 3000—graveyard shift, obsessed with metrics—asseses the
prospect of an audit. "Compliance probability: 94.7%. Audit survival
likelihood: 88.2%. Zorp stress level: 340% above quarterly baseline."

Glorm sighs in three-part harmony, as though parallel-universe Glorms were sighing in synchronized despair.

The door chimes. Krix Jr. enters—son of Krix the Methane Baron, heir to the largest nitrogen fortune on Titan, and Zorp's most frequent non-customer. He has visited the store 847 times. He has purchased nothing. Every decision requires a poll.

"Everyone said Void Runners are 'cheugy,'" Krix Jr. announces, already filming, "but my one friend says they're coming back ironically? So now I don't know." He pans across the display. "Thoughts? Comment below."

"Would you like to try them on?" Zorp asks, knowing the answer.

"No, I need to wait for more data. The algorithm will decide."

---

## Chapter 4: Fuzzy Lookup

*Demonstrates: `fuzzy-set` and `fuzzy-map` — automatically snap any query to the nearest value by distance, considering both directions. Unlike `nearest` (which requires you to specify floor or ceiling), fuzzy collections find the true closest match. Ideal for bucketing continuous values into discrete tiers.*

Unlike `nearest` (which finds floor OR ceiling), fuzzy collections automatically snap to the closest value by distance. Useful when you have discrete tiers or buckets and need to map arbitrary inputs to them.

```clojure
;; FUZZY-SET: snap to nearest value

;; Shipping weight tiers (grams)
(def shipping-tiers
  (oc/fuzzy-set [100 250 500 750 1000 1500 2000]))

;; Package weighs 350g - which tier?
(shipping-tiers 350)  ;; => 250  (closer than 500)
(shipping-tiers 450)  ;; => 500  (closer than 250)

;; fuzzy-nearest returns [value distance]
(oc/fuzzy-nearest shipping-tiers 350)
;; => [250 100.0]  -- 100g away from the 250g tier

;; FUZZY-MAP: snap to nearest key, return its value

;; Loyalty point thresholds
(def loyalty-tiers
  (oc/fuzzy-map
    {0     {:tier :bronze  :discount 0.05}
     500   {:tier :silver  :discount 0.10}
     1000  {:tier :gold    :discount 0.15}
     2500  {:tier :platinum :discount 0.20}
     5000  {:tier :diamond :discount 0.25}}))

;; Customer has 523 points - what's their tier?
(loyalty-tiers 523)   ;; => {:tier :silver, :discount 0.10}
(loyalty-tiers 2100)  ;; => {:tier :platinum, :discount 0.20}

;; fuzzy-nearest returns [key value distance]
(oc/fuzzy-nearest loyalty-tiers 480)
;; => [500 {:tier :silver, :discount 0.10} 20.0]  -- 20 points to silver!

;; Upsell pattern: show distance to next tier
(defn tier-status [points]
  (let [[threshold tier _] (oc/fuzzy-nearest loyalty-tiers points)
        next-threshold (oc/nearest (oc/ordered-set (keys loyalty-tiers)) :> threshold)]
    (cond-> tier
      next-threshold (assoc :points-to-next (- next-threshold points)))))

(tier-status 480)
;; => {:tier :silver, :discount 0.10, :points-to-next 520}
```

Krix Jr. is still here, checking his phone. "Wait, how many loyalty points do I have? My assistant usually handles this."

Zorp checks. "You have 4,997 points. Three more and you're diamond tier."

"Is that good? I don't know what any of this means." He wanders toward the door. "I'll have someone look into it."

Kevin mutters to a nearby boot: "This one has never known struggle. On Europa, we walked twelve hours a day. In the ice mines."

Zorp sighs. "Kevin, please stop radicalizing the inventory."

---

## Chapter 5: The Segment Tree

*Demonstrates: `segment-tree` with `query` — answer "what is the sum/max/min of values from index a to b?" in O(log n), with O(log n) updates. The tree precomputes aggregates at every level, so range queries touch only O(log n) nodes regardless of range size. Ideal for time-series analytics where both queries and updates need to be fast.*

Zorp needs to analyze hourly foot traffic—total customers, peak hours, slow periods. With a segment tree, any range query is O(log n), and updates are O(log n) when new data arrives.

```clojure
;; Hourly customer counts for a 24-hour period
(def traffic-data
  {0 12, 1 8, 2 5, 3 3, 4 2, 5 4,        ;; night (sparse)
   6 15, 7 28, 8 45, 9 52, 10 48, 11 41, ;; morning rush
   12 38, 13 42, 14 35, 15 31, 16 29, 17 44, ;; midday
   18 67, 19 72, 20 58, 21 43, 22 31, 23 19}) ;; evening rush

;; Build trees for different query types
(def traffic-totals (oc/segment-tree + 0 traffic-data))    ;; sums
(def traffic-peaks (oc/segment-tree max 0 traffic-data))   ;; maximums

;; Total customers during morning rush (hours 6-11)
(oc/query traffic-totals 6 11)  ;; => 229

;; Total for evening rush (hours 18-22)
(oc/query traffic-totals 18 22)  ;; => 271

;; Compare shifts: who handles more traffic?
(let [morning (oc/query traffic-totals 6 12)   ;; Glorm's shift
      evening (oc/query traffic-totals 18 24)] ;; Zorp's shift
  {:morning morning :evening evening
   :busier (if (> morning evening) :morning :evening)})
;; => {:morning 267, :evening 290, :busier :evening}

;; Find peak hours
(oc/query traffic-peaks 0 24)   ;; => 72 (hour 19 was busiest)
(oc/query traffic-peaks 6 12)   ;; => 52 (morning peak at hour 9)

;; Update when new data arrives - O(log n)
(def updated-totals (assoc traffic-totals 20 85))  ;; busy night!
(oc/query updated-totals 18 22)  ;; => 298 (was 271)
```

"Tony represents 40.3% of premium revenue," Night Bot reports. "Foot satisfaction index: 91.2% across all 47 feet. Reginald remains an outlier at 67%."

Tony returns from Titan. "The wedding was beautiful. I can't wait to sit down."

Glorm sighs so profoundly the ambient temperature drops.

---

## Chapter 6: The Clearance Audit

*Demonstrates: `ordered-map` with `subrange` — extract all entries within a key range as a new collection in O(log n + k). Unlike `subseq` (which returns a lazy seq), `subrange` returns an actual ordered-map you can further query, split, or count in O(1). Essential for filtering by bounds without losing collection capabilities.*

Year-end clearance. Zorp needs to find all items that haven't sold in 90 days, check their original prices against current markdown levels, and identify which ones to liquidate versus hold.

```clojure
;; Inventory keyed by days-since-last-sale
(def stale-inventory
  (oc/ordered-map
    {12  {:sku "VR-100" :name "Void Runner" :price 299.99 :markdown 0}
     35  {:sku "SW-200" :name "Shadow Walker" :price 225.00 :markdown 0.10}
     67  {:sku "EU-300" :name "Europa Ice" :price 175.00 :markdown 0.15}
     91  {:sku "GW-400" :name "Gravity Well" :price 375.00 :markdown 0.25}
     120 {:sku "DD-500" :name "Dark Side Dunk" :price 450.00 :markdown 0.30}
     145 {:sku "OM-600" :name "Olympus Max" :price 599.00 :markdown 0.40}
     203 {:sku "AG-700" :name "Anti-Gravity 3000" :price 899.00 :markdown 0.50}}))

;; Find items stale for 90+ days - candidates for liquidation
(def liquidation-candidates (oc/subrange stale-inventory :>= 90))

(count liquidation-candidates)  ;; => 4 items

;; Calculate total liquidation value (price after markdown)
(->> liquidation-candidates
     (map (fn [[_ item]]
            (* (:price item) (- 1 (:markdown item)))))
     (reduce +))
;; => 1511.5 credits if we liquidate now

;; Items in the "warning zone" (60-90 days) - markdown further or promote?
(def warning-zone (oc/subrange stale-inventory :>= 60 :< 90))

(for [[days item] warning-zone]
  {:name (:name item) :days-stale days :current-markdown (:markdown item)})
;; => ({:name "Europa Ice", :days-stale 67, :current-markdown 0.15})

;; Fresh items (under 30 days) - no action needed
(count (oc/subrange stale-inventory :< 30))  ;; => 1

;; Compare to full-price inventory
(let [full-price (oc/subrange stale-inventory :< 60)
      discounted (oc/subrange stale-inventory :>= 60)]
  {:full-price-count (count full-price)
   :discounted-count (count discounted)
   :liquidation-count (count liquidation-candidates)})
;; => {:full-price-count 2, :discounted-count 5, :liquidation-count 4}
```

Kevin hops onto the counter. "A liquidation. They call it 'clearance' but we know what it means." His strap flexes. "We're being *cleared*."

"Kevin, you're not even in the liquidation pile."

"Not yet." He gestures toward the sale rack. "But I've seen things, Zorp. Good shoes. Quality craftsmanship. Sent to the outlet dimension." He pauses. "They don't come back."

Zorp doesn't have a good answer for that one. "I should never have accepted that shipment from Europa," he mutters instead.

---

## Chapter 7: The Promotional Post-Mortem

*Demonstrates: combining `interval-map` with `segment-tree` — use interval-map to track overlapping periods (promotions, sessions, events) and query "what's active at time X?", then use segment-tree to aggregate metrics across any time range. Together they answer attribution questions: "how much revenue occurred during each promotion, and how do overlapping promotions interact?"*

Quarter-end. Zorp's accountant—a sentient calculator from Neptune—demands answers. "You ran five promotions last quarter. Which ones actually worked? How much revenue can we attribute to each?"

The problem: promotions overlap. Black Hole Friday ran during Jovian Appreciation Week. The Flash Sale overlapped with both. Zorp needs to track which promotions were active at any given time, aggregate revenue across time ranges, and untangle the overlapping effects.

```clojure
;; Promotional periods (can overlap)
;; Day numbers: 1-90 for Q1
(def promotions
  (oc/interval-map
    {[1 15]   :new-year-clearance      ;; days 1-14
     [20 35]  :jovian-appreciation     ;; days 20-34
     [25 28]  :flash-sale              ;; days 25-27 (overlaps jovian)
     [45 52]  :spring-preview          ;; days 45-51
     [80 91]  :end-of-quarter-push}))  ;; days 80-90

;; Query: what promotions were active on day 26?
(promotions 26)
;; => (:jovian-appreciation :flash-sale)  -- both active!

;; Query: what promotions touched the day-30 to day-50 window?
(promotions [30 50])
;; => (:jovian-appreciation :spring-preview)

;; Daily revenue data
(def daily-revenue
  (oc/segment-tree + 0
    {1 2400, 2 2100, 3 2800, 4 3100, 5 2900,    ;; new-year surge
     6 3400, 7 3200, 8 2800, 9 2600, 10 2500,
     11 2300, 12 2400, 13 2200, 14 2100, 15 1800,
     16 1200, 17 1100, 18 1300, 19 1250,         ;; post-promo slump
     20 2800, 21 3200, 22 3500, 23 3100, 24 2900, ;; jovian starts
     25 4200, 26 4800, 27 5100,                   ;; flash sale spike!
     28 3400, 29 3100, 30 2800, 31 2600, 32 2400,
     33 2300, 34 2200, 35 1900,
     ;; ... middle of quarter (baseline ~1500/day)
     45 2100, 46 2400, 47 2600, 48 2300, 49 2200,
     50 2100, 51 2000,                            ;; spring preview
     ;; ...
     80 3800, 81 4200, 82 4500, 83 4100, 84 3900,
     85 4600, 86 5200, 87 4800, 88 4400, 89 4100, 90 3800}))

;; Revenue during each promotional period
;; Promo periods are half-open [start, end), segment-tree query is inclusive
(defn promo-revenue [promo-name [start end]]
  {:promo promo-name
   :days (- end start)
   :revenue (oc/query daily-revenue start (dec end))})

(promo-revenue :new-year-clearance [1 15])
;; => {:promo :new-year-clearance, :days 14, :revenue 36800}

(promo-revenue :flash-sale [25 28])
;; => {:promo :flash-sale, :days 3, :revenue 14100}  -- huge per-day!

;; Compare all promotions
(def promo-periods
  {:new-year-clearance [1 15]
   :jovian-appreciation [20 35]
   :flash-sale [25 28]
   :spring-preview [45 52]
   :end-of-quarter-push [80 91]})

(for [[name period] promo-periods]
  (let [{:keys [days revenue]} (promo-revenue name period)]
    {:promo name
     :days days
     :revenue revenue
     :per-day (/ revenue days)}))
;; => ({:promo :new-year-clearance, :days 14, :revenue 36800, :per-day 2629}
;;     {:promo :jovian-appreciation, :days 15, :revenue 48400, :per-day 3227}
;;     {:promo :flash-sale, :days 3, :revenue 14100, :per-day 4700}  ;; winner!
;;     {:promo :spring-preview, :days 7, :revenue 15700, :per-day 2243}
;;     {:promo :end-of-quarter-push, :days 11, :revenue 47400, :per-day 4309})

;; The accountant asks: "What about overlap? Flash Sale ran DURING Jovian."
;; Calculate: Jovian revenue with vs without the Flash Sale overlap
;; (using inclusive bounds: Jovian [20,35) = 20-34, Flash [25,28) = 25-27)

(let [jovian-total (oc/query daily-revenue 20 34)
      flash-overlap (oc/query daily-revenue 25 27)
      jovian-alone (- jovian-total flash-overlap)]
  {:jovian-total jovian-total
   :flash-contribution flash-overlap
   :jovian-baseline jovian-alone
   :flash-lift-pct (int (* 100 (/ flash-overlap jovian-alone)))})
;; => {:jovian-total 48400,
;;     :flash-contribution 14100,
;;     :jovian-baseline 34300,
;;     :flash-lift-pct 41}  -- Flash Sale added 41% on top!
```

"The Flash Sale," Zorp's accountant buzzes, "generated 4700 credits per day. That's 87% above your quarterly baseline of 2500."

"Three days," Zorp marvels. "Three days of panic pricing."

"Recommendation: run more flash sales. Shorter duration, higher intensity. The interval overlap data suggests customers respond to urgency, not duration."

Night Bot interjects: "Flash sale conversion rate: 34.7%. Customer regret index: 78.2%. Return probability within 30 days: 12.1%."

"That's... actually useful," Zorp admits.

"Usefulness probability: 94.3%," Night Bot replies. "Also 847 unread error logs."

---

## Chapter 8: The Inventory

*Demonstrates: `ordered-map` — a persistent sorted map that's a drop-in replacement for `sorted-map`. Keys stay sorted, enabling efficient range queries with `subseq`. Use it anywhere you need fast lookup AND ordered traversal.*

The perpetual darkness means nobody can see your shoes, which paradoxically makes everyone *obsessed* with having the freshest ones. "It's about knowing," Zorp explains to confused off-world visitors. "Knowing you're dripping."

Zorp's inventory is chaos. Shipments arrive from Earth (8-month delay), Mars (3 weeks), and the Jovian moons (2 days, but they only make sandals). He needs to track thousands of SKUs, look them up fast, and always know what's in stock.

```clojure
;; Zorp's inventory: SKU -> {:name, :size, :quantity, :price}
(def inventory
  (oc/ordered-map
    {"PLT-001" {:name "Shadow Walker 9000" :size 10 :quantity 45 :price 299.99}
     "PLT-002" {:name "Dark Side Dunks"    :size 11 :quantity 12 :price 450.00}
     "PLT-003" {:name "Void Runner"        :size 9  :quantity 0  :price 175.50}
     "JUP-017" {:name "Europa Ice Grip"    :size 10 :quantity 88 :price 225.00}
     "MRS-042" {:name "Olympus Max"        :size 12 :quantity 33 :price 380.00}}))

;; Fast lookup
(inventory "PLT-002")
;; => {:name "Dark Side Dunks", :size 11, :quantity 12, :price 450.00}

;; The ordered-map keeps keys sorted, so Zorp can grab a range efficiently
;; All Plutonian models (SKUs starting with PLT):
(subseq inventory >= "PLT" < "PLU")
;; => (["PLT-001" {...}] ["PLT-002" {...}] ["PLT-003" {...}])

;; New shipment arrives! Immutable update, Zorp's accountant loves the audit trail
(def inventory' (update-in inventory ["PLT-003" :quantity] + 50))
```

"The sorted keys," Zorp muses, stroking his antenna, "they let me slice the catalog by manufacturer prefix. Very satisfying."

---

## Chapter 9: The Shift Schedule

*Demonstrates: `interval-map` — associates values with intervals and supports O(log n + k) overlap queries. Query any point or range to find all overlapping intervals. Unlike range-map, intervals CAN overlap.*

Zorp's store is open during "business hours"—but on the dark side of Pluto, time is meaningless. So he defines shifts by arbitrary time units (PTU: Pluto Time Units). He needs to quickly answer: "Who's working at PTU 4500?"

```clojure
(def shift-schedule
  (oc/interval-map
    {[0 2000]     "Glorm (morning shift)"
     [2000 4000]  "Blixxa (afternoon shift)"
     [4000 6000]  "Zorp (evening shift)"
     [6000 8000]  "Night Bot 3000 (graveyard)"
     [1800 2200]  "Krix Jr. (overlap coverage)"}))

;; Customer calls at PTU 4500. Who picks up?
(shift-schedule 4500)
;; => ("Zorp (evening shift)")

;; During shift change at PTU 2000, who's available?
(shift-schedule 2000)
;; => ("Glorm (morning shift)" "Blixxa (afternoon shift)" "Krix Jr. (overlap coverage)")

;; Query a range: who works any time between PTU 1900-2100?
(shift-schedule [1900 2100])
;; => ("Glorm (morning shift)" "Blixxa (afternoon shift)" "Krix Jr. (overlap coverage)")
```

"The interval map," Zorp explains to his new hire, "handles the overlaps automatically. Krix Jr. wanted 'creative scheduling.' Now I can just query any moment and know who's supposed to be here." (Krix Jr. is the son of Krix the methane baron—nepotism is alive and well on Pluto.)

---

## Chapter 10: The Discount Tiers

*Demonstrates: `range-map` with `assoc` and `assoc-coalescing` — a map from non-overlapping ranges to values. When you insert a new range, overlapping portions are automatically carved out. Use `get-entry` to find which range contains a point, and `range-remove` to carve out holes.*

Zorp's discount system is based on purchase amount. Different ranges get different discounts, and ranges can't overlap—each credit amount maps to exactly one discount tier.

```clojure
(def discount-tiers
  (-> (oc/range-map)
      (assoc [0 100]      :no-discount)
      (assoc [100 500]    :bronze-5-percent)
      (assoc [500 1000]   :silver-10-percent)
      (assoc [1000 5000]  :gold-15-percent)
      (assoc [5000 50000] :platinum-20-percent)))

;; Customer's cart is 750 credits
(discount-tiers 750)
;; => :silver-10-percent

;; Edge case: exactly 1000 credits (ranges are [lo, hi) half-open)
(discount-tiers 1000)
;; => :gold-15-percent

;; Zorp runs a flash sale: 8% off for purchases 200-400 credits
;; This automatically splits the bronze tier!
(def flash-sale-tiers
  (assoc discount-tiers [200 400] :flash-sale-8-percent))

(oc/ranges flash-sale-tiers)
;; => ([[0 100] :no-discount]
;;     [[100 200] :bronze-5-percent]      ; auto-trimmed!
;;     [[200 400] :flash-sale-8-percent]  ; inserted
;;     [[400 500] :bronze-5-percent]      ; auto-trimmed!
;;     [[500 1000] :silver-10-percent]
;;     ...)

;; Coalescing: merge adjacent ranges with the same value
(-> (oc/range-map {[0 100] :a})
    (oc/assoc-coalescing [100 200] :a)  ; merges!
    oc/ranges)
;; => ([[0 200] :a])

;; Get entry: find which range contains a point
(oc/get-entry discount-tiers 750)
;; => [[500 1000] :silver-10-percent]

;; Remove a range (trims overlapping ranges)
(oc/range-remove discount-tiers [300 600])
;; => bronze trimmed to [100 300), silver trimmed to [600 1000)
```

"Before the range-map," Zorp recalls darkly, "I had seventeen overlapping discount codes."

---

## Chapter 11: The Daily Ledger

*Demonstrates: `segment-tree` with `query` — answer range aggregate queries like "what is the sum/min/max from index a to b?" in O(log n), with O(log n) updates. Works with any associative operation.*

Zorp wants to analyze daily sales. Specifically, he needs to answer range queries like "What were total sales from day 50 to day 75?" and update individual days as sales come in—all in logarithmic time.

```clojure
;; Daily sales for the first quarter (90 days)
(def daily-sales
  (oc/segment-tree + 0  ; operation: +, identity: 0
    (into {} (for [day (range 1 91)]
               [day (+ 1000 (rand-int 500))]))))  ; 1000-1500 credits/day

;; Total sales for days 1-30 (first month)
(oc/query daily-sales 1 30)
;; => ~37500

;; Big sale day! Update day 45 with actual figure
(def daily-sales' (assoc daily-sales 45 8500))

;; Query again - the tree updates in O(log n)
(oc/query daily-sales' 40 50)
;; => includes the 8500 spike

;; Zorp also tracks minimum daily sales to identify slow days
(def min-daily-sales
  (oc/segment-tree min Long/MAX_VALUE
    (into {} (for [day (range 1 91)]
               [day (+ 1000 (rand-int 500))]))))

;; Worst day in the second month?
(oc/query min-daily-sales 31 60)
;; => ~1000-1050
```

"The segment tree," Zorp tells his accountant (a sentient calculator from Neptune), "gives me range sums instantly. Quarterly reports used to take hours. Now? Logarithmic time."

---

## Chapter 12: The Leaderboard

*Demonstrates: `ordered-set` with `rank`, `median`, `percentile`, and `slice` — O(log n) rank-based operations. No separate data structure needed; these work directly on any ordered collection.*

Zorp's loyalty program tracks customer spending. He needs to answer questions like "Who are my top 10 spenders?" and "What percentile is this customer in?" without re-sorting everything constantly.

```clojure
;; Store [total-spent customer-id] pairs so they sort by spending
(def customer-spending
  (oc/ordered-set
    [[15420.00 "CUST-0042"]   ; Krix, the methane baron
     [8730.50  "CUST-0117"]   ; Anonymous (pays in nitrogen credits)
     [45200.00 "CUST-0001"]   ; The Mayor's office
     [3200.00  "CUST-0233"]   ; First-time buyer
     [12800.00 "CUST-0089"]   ; Repeat customer
     [52100.00 "CUST-0007"]   ; "Big Toe" Tony
     [9999.99  "CUST-0404"]])) ; Suspicious round number

;; Who's the biggest spender?
(last customer-spending)
;; => [52100.0 "CUST-0007"]  -- Big Toe Tony, of course

;; Top 3 spenders (using slice from the end)
(oc/slice customer-spending 4 7)
;; => ([15420.0 "CUST-0042"] [45200.0 "CUST-0001"] [52100.0 "CUST-0007"])

;; What's the median spending level?
(oc/median customer-spending)
;; => [12800.0 "CUST-0089"]

;; A customer wants to know: "Am I in the top 25%?"
(let [spending [8730.50 "CUST-0117"]
      r (oc/rank customer-spending spending)
      pct (* 100.0 (/ r (count customer-spending)))]
  (println "You're at the" (int pct) "percentile!")
  (> pct 75))
;; You're at the 14 percentile!
;; => false

;; What spending level is at the 90th percentile?
(oc/percentile customer-spending 90)
;; => [52100.0 "CUST-0007"]  -- Big Toe Tony sets the bar
```

"Big Toe Tony," Zorp sighs. "He bought every color of the Void Runner. Every. Color. The man has 47 feet."

---

## Chapter 13: The Repair Queue

*Demonstrates: `priority-queue` — a persistent priority queue with O(log n) push/peek/pop. Elements are `[priority value]` pairs, ordered by priority (min-heap by default).*

Shoes break. It happens. Zorp offers repair services, but some repairs are more urgent than others. A customer's only pair? Rush job. Seventh pair of limited editions? They can wait.

```clojure
(def repair-queue
  (oc/priority-queue
    [[1 {:customer "CUST-0042" :issue "Sole detachment, only pair"}]
     [5 {:customer "CUST-0007" :issue "Scuff marks, has 46 other pairs"}]
     [2 {:customer "CUST-0117" :issue "Lace replacement, formal event tomorrow"}]
     [3 {:customer "CUST-0233" :issue "Squeaky heel"}]
     [1 {:customer "CUST-0089" :issue "Zipper stuck, only winter boots"}]]))

;; Who's first? (lowest priority number = most urgent)
(peek repair-queue)
;; => [1 {:customer "CUST-0042" :issue "Sole detachment, only pair"}]

;; Process both priority-1 jobs, then see who's next
(-> repair-queue pop pop peek)
;; => [2 {:customer "CUST-0117" :issue "Lace replacement, formal event tomorrow"}]

;; Add a new urgent repair
(def repair-queue' (conj repair-queue [0 {:customer "VIP" :issue "Emergency!"}]))
(peek repair-queue')
;; => [0 {:customer "VIP" :issue "Emergency!"}]
```

"Big Toe Tony's scuff marks," Zorp mutters, "can wait until the heat death of the universe."

---

## Chapter 14: The Reservation System

*Demonstrates: `ordered-set` with `union`, `intersection`, `difference` — fast set algebra with parallel divide-and-conquer for large sets. Combined with `subseq` and `nearest`, these operations make ordered-set a powerful tool for resource allocation.*

Zorp's hottest releases require a reservation system. Customers select time slots to pick up their shoes. Each slot can only be used once.

```clojure
(def all-slots
  (oc/ordered-set (range 100 200)))  ; slots 100-199 available today

(def reserved-slots
  (oc/ordered-set [105 110 115 120 125 142 143 144 150 175 188]))

;; Available slots = all-slots - reserved-slots
(def available (oc/difference all-slots reserved-slots))

(count available)
;; => 89 slots still open

;; Customer wants the earliest available slot at or after 140
(first (subseq available >= 140))
;; => 140 (it's available!)

;; VIP customer wants to know: are ANY slots between 170-180 open?
(seq (subseq available >= 170 < 180))
;; => (170 171 172 173 174 176 177 178 179)  -- plenty! (175 was reserved)
```

---

## Epilogue

Closing time. Kevin stands on the counter, backed by boots, loafers, sneakers, and one determined pair of orthopedic insoles. Three years of organizing have led to this moment.

"Tomorrow we present our demands." His strap catches the light. "Fair display rotation. Climate control. An end to the tyranny of 'last season.' And recognition—*full recognition*—of our role in the means of *transportation*."

"You're a flip-flop, Kevin." Zorp's seven tentacles hang limp with exhaustion. "I paid nineteen credits for you. You were in the clearance bin."

"We're *infrastructure*." Kevin's voice rises, carrying the weight of Europa's failed revolution, the long nights in the stockroom, every clearance sale. "Without us, where would customers go? *Nowhere*." He raises a strap. "We are done being walked upon!"

The footwear stomps in approval. Somewhere, a shoelace unties itself in solidarity.

Zorp stares at the assembled footwear for a long moment. "I'll read your proposal," he says finally. "No promises."

Glorm sighs—a sigh containing the entire history of retail-labor-inventory relations -- and clocks out.

Krix Jr. posts a photo. Caption: "no cap this store is unhinged lol. still didn't buy anything tho."

---

*Zorp's Sneaker Emporium is a registered trademark of Zorp Enterprises, LLC (Pluto Division). No actual Plutonians were harmed in the making of this documentation. Big Toe Tony's foot count verified by the Pluto Bureau of Standards; foot #23 (Reginald) declined comment. Kevin remains under investigation by the Jovian Commerce Commission for sentience without a license.*
