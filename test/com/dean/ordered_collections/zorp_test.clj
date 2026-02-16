(ns com.dean.ordered-collections.zorp-test
  "Tests for examples in doc/zorp-example.md

   Zorp's Sneaker Emporium: Advanced Patterns
   Testing the 0.2.0 API features."
  (:refer-clojure :exclude [split-at])
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.string :as str]
            [com.dean.ordered-collections.core :as oc]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 1: The Subnet Allocation (range-map)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ip
  "Convert IP string to integer."
  [s]
  (let [[a b c d] (map parse-long (str/split s #"\."))]
    (+ (* a 16777216) (* b 65536) (* c 256) d)))

(defn int->ip
  "Convert integer to IP string."
  [n]
  (format "%d.%d.%d.%d"
    (bit-and (bit-shift-right n 24) 0xff)
    (bit-and (bit-shift-right n 16) 0xff)
    (bit-and (bit-shift-right n 8) 0xff)
    (bit-and n 0xff)))

(deftest chapter-1-subnet-allocation-test
  (testing "IP helper functions"
    (is (= 167772160 (ip "10.0.0.0")))
    (is (= 167772161 (ip "10.0.0.1")))
    (is (= "10.0.0.0" (int->ip 167772160)))
    (is (= "10.1.0.4" (int->ip (ip "10.1.0.4")))))

  (testing "Range-map creation and basic lookup"
    (let [network (oc/range-map {[(ip "10.0.0.0") (inc (ip "10.255.255.255"))] :unallocated})]
      (is (= :unallocated (network (ip "10.5.0.1"))))
      (is (= :unallocated (network (ip "10.128.0.0"))))))

  (testing "Subnet allocation carves out ranges"
    (let [network (-> (oc/range-map {[(ip "10.0.0.0") (inc (ip "10.255.255.255"))] :unallocated})
                      (assoc [(ip "10.1.0.0") (ip "10.2.0.0")] :point-of-sale)
                      (assoc [(ip "10.2.0.0") (ip "10.3.0.0")] :inventory)
                      (assoc [(ip "10.10.0.0") (ip "10.11.0.0")] :customer-wifi))]
      ;; Look up which system owns an IP
      (is (= :point-of-sale (network (ip "10.1.0.4"))))
      (is (= :inventory (network (ip "10.2.0.68"))))
      (is (= :customer-wifi (network (ip "10.10.5.42"))))
      (is (= :unallocated (network (ip "10.5.0.1"))))))

  (testing "Quarantine zone splits existing range"
    (let [network (-> (oc/range-map {[(ip "10.10.0.0") (ip "10.11.0.0")] :customer-wifi})
                      (assoc [(ip "10.10.4.0") (ip "10.10.8.0")] :kevin-quarantine))
          ranges (for [[[lo hi] owner] (oc/ranges network)]
                   {:lo (int->ip lo) :hi (int->ip hi) :owner owner})]
      ;; customer-wifi should be split around quarantine
      (is (= 3 (count ranges)))
      (is (some #(= {:lo "10.10.0.0" :hi "10.10.4.0" :owner :customer-wifi} %) ranges))
      (is (some #(= {:lo "10.10.4.0" :hi "10.10.8.0" :owner :kevin-quarantine} %) ranges))
      (is (some #(= {:lo "10.10.8.0" :hi "10.11.0.0" :owner :customer-wifi} %) ranges))))

  (testing "Adjacent ranges with same value coalesce (using assoc-coalescing)"
    (let [network (-> (oc/range-map {[(ip "10.10.4.0") (ip "10.10.8.0")] :kevin-iot})
                      (oc/assoc-coalescing [(ip "10.10.8.0") (ip "10.10.12.0")] :kevin-iot))
          kevin-ranges (for [[[lo hi] owner] (oc/ranges network)
                             :when (= owner :kevin-iot)]
                         {:lo (int->ip lo) :hi (int->ip hi)})]
      ;; Should coalesce into single range
      (is (= 1 (count kevin-ranges)))
      (is (= {:lo "10.10.4.0" :hi "10.10.12.0"} (first kevin-ranges))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 2: Big Toe Tony's Fitting (nearest)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def available-sizes
  (oc/ordered-set
    [6.0 6.5 7.0 7.5 8.0 8.5 9.0 9.5 10.0 10.5
     11.0 11.5 12.0 12.5 13.0 13.5 14.0 14.5 15.0]))

(deftest chapter-2-big-toe-tonys-fitting-test
  (testing "nearest :<= finds floor (largest size that fits)"
    (is (= 11.0 (oc/nearest available-sizes :<= 11.3)))
    (is (= 10.5 (oc/nearest available-sizes :<= 10.8)))
    (is (= 9.0 (oc/nearest available-sizes :<= 9.2))))

  (testing "nearest :>= finds ceiling (smallest size with room)"
    (is (= 11.5 (oc/nearest available-sizes :>= 11.3)))
    (is (= 11.0 (oc/nearest available-sizes :>= 10.8)))
    (is (= 9.5 (oc/nearest available-sizes :>= 9.2))))

  (testing "nearest with strict bounds"
    (is (= 10.5 (oc/nearest available-sizes :< 11.0)))
    (is (= 13.5 (oc/nearest available-sizes :> 13.0))))

  (testing "fit-foot function finds snug and roomy options"
    (let [tonys-feet {:reginald 11.3 :gerald 10.8 :margaret 9.2
                      :humphrey 13.7 :agnes 8.1 :bernard 12.0}
          fit-foot (fn [[foot-name ideal-size]]
                     {:foot foot-name
                      :ideal ideal-size
                      :snug (oc/nearest available-sizes :<= ideal-size)
                      :roomy (oc/nearest available-sizes :>= ideal-size)})
          fits (into {} (map (fn [f] [(:foot f) f]) (map fit-foot tonys-feet)))]
      (is (= {:foot :reginald :ideal 11.3 :snug 11.0 :roomy 11.5}
             (:reginald fits)))
      (is (= {:foot :gerald :ideal 10.8 :snug 10.5 :roomy 11.0}
             (:gerald fits)))
      (is (= {:foot :margaret :ideal 9.2 :snug 9.0 :roomy 9.5}
             (:margaret fits)))))

  (testing "nearest at boundaries"
    (is (nil? (oc/nearest available-sizes :< 6.0)))
    (is (nil? (oc/nearest available-sizes :> 15.0)))
    (is (= 6.0 (oc/nearest available-sizes :<= 6.0)))
    (is (= 15.0 (oc/nearest available-sizes :>= 15.0)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 3: The Split Decision (split-at, split-key)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest chapter-3-split-decision-test
  (testing "split-at partitions by position for percentiles"
    (let [customer-spending (oc/ordered-map
                              (for [id (range 1000)]
                                [(+ 100 (* id 50)) {:id id}]))
          n (count customer-spending)]
      ;; Top 10%
      (let [[_ top-10-pct] (oc/split-at (- n (quot n 10)) customer-spending)]
        (is (= 100 (count top-10-pct))))
      ;; Bottom 20%
      (let [[bottom-20-pct _] (oc/split-at (quot n 5) customer-spending)]
        (is (= 200 (count bottom-20-pct))))
      ;; Median
      (let [[lower upper] (oc/split-at (quot n 2) customer-spending)]
        (is (= 500 (count lower)))
        (is (= 500 (count upper))))))

  (testing "split-key partitions by value at threshold"
    (let [customer-spending (oc/ordered-map
                              [[100 {:id 0}] [500 {:id 1}] [1000 {:id 2}]
                               [5000 {:id 3}] [10000 {:id 4}] [25000 {:id 5}]])
          [casual exact vip] (oc/split-key 10000 customer-spending)]
      (is (= 4 (count casual)))       ; 100, 500, 1000, 5000
      (is (some? exact))              ; exact match at 10000
      (is (= 1 (count vip)))))        ; 25000

  (testing "split-key with no exact match"
    (let [spending (oc/ordered-map [[100 :a] [500 :b] [1000 :c]])
          [below exact above] (oc/split-key 750 spending)]
      (is (= 2 (count below)))        ; 100, 500
      (is (nil? exact))               ; no 750
      (is (= 1 (count above)))))      ; 1000

  (testing "Results are full collections - can chain operations"
    (let [customer-spending (oc/ordered-map
                              [[100 :a] [500 :b] [1000 :c] [5000 :d]
                               [10000 :e] [25000 :f] [50000 :g]])
          [_ _ high-spenders] (oc/split-key 25000 customer-spending)]
      ;; Can get last element of result
      (is (= [50000 :g] (last high-spenders))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 4: Fuzzy Lookup (fuzzy-set, fuzzy-map)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def shipping-tiers
  (oc/fuzzy-set [100 250 500 750 1000 1500 2000]))

(def loyalty-tiers
  (oc/fuzzy-map
    {0     {:tier :bronze  :discount 0.05}
     500   {:tier :silver  :discount 0.10}
     1000  {:tier :gold    :discount 0.15}
     2500  {:tier :platinum :discount 0.20}
     5000  {:tier :diamond :discount 0.25}}))

(deftest chapter-4-fuzzy-lookup-test
  (testing "fuzzy-set snaps to nearest value"
    (is (= 250 (shipping-tiers 350)))   ; closer to 250 than 500
    (is (= 500 (shipping-tiers 450)))   ; closer to 500 than 250
    (is (= 100 (shipping-tiers 50)))    ; below range, snaps to 100
    (is (= 2000 (shipping-tiers 3000))) ; above range, snaps to 2000
    (is (= 750 (shipping-tiers 750))))  ; exact match

  (testing "fuzzy-nearest returns [value distance]"
    (let [[value distance] (oc/fuzzy-nearest shipping-tiers 350)]
      (is (= 250 value))
      (is (= 100.0 distance)))
    (let [[value distance] (oc/fuzzy-nearest shipping-tiers 750)]
      (is (= 750 value))
      (is (= 0.0 distance))))

  (testing "fuzzy-map snaps to nearest key, returns value"
    (is (= {:tier :silver :discount 0.10} (loyalty-tiers 523)))
    (is (= {:tier :platinum :discount 0.20} (loyalty-tiers 2100)))
    (is (= {:tier :bronze :discount 0.05} (loyalty-tiers 0)))
    (is (= {:tier :diamond :discount 0.25} (loyalty-tiers 5000))))

  (testing "fuzzy-nearest on fuzzy-map returns [key value distance]"
    (let [[threshold tier distance] (oc/fuzzy-nearest loyalty-tiers 480)]
      (is (= 500 threshold))
      (is (= {:tier :silver :discount 0.10} tier))
      (is (= 20.0 distance))))

  (testing "Upsell pattern - points to next tier"
    (let [tier-thresholds (oc/ordered-set [0 500 1000 2500 5000])
          tier-status (fn [points]
                        (let [[threshold tier _] (oc/fuzzy-nearest loyalty-tiers points)
                              next-threshold (oc/nearest tier-thresholds :> threshold)]
                          (cond-> tier
                            next-threshold (assoc :points-to-next (- next-threshold points)))))
          status (tier-status 480)]
      (is (= :silver (:tier status)))
      (is (= 520 (:points-to-next status))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 5: The Segment Tree (segment-tree, query)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def traffic-data
  {0 12, 1 8, 2 5, 3 3, 4 2, 5 4,        ;; night (sparse)
   6 15, 7 28, 8 45, 9 52, 10 48, 11 41, ;; morning rush
   12 38, 13 42, 14 35, 15 31, 16 29, 17 44, ;; midday
   18 67, 19 72, 20 58, 21 43, 22 31, 23 19}) ;; evening rush

(def traffic-totals (oc/segment-tree + 0 traffic-data))
(def traffic-peaks (oc/segment-tree max 0 traffic-data))

(deftest chapter-5-segment-tree-test
  (testing "Total customers during morning rush (hours 6-11)"
    (is (= (+ 15 28 45 52 48 41) (oc/query traffic-totals 6 11))))

  (testing "Total for evening rush (hours 18-22)"
    (is (= (+ 67 72 58 43 31) (oc/query traffic-totals 18 22))))

  (testing "Compare shifts"
    (let [morning (oc/query traffic-totals 6 12)   ;; Glorm's shift
          evening (oc/query traffic-totals 18 24)] ;; Zorp's shift
      (is (= (+ 15 28 45 52 48 41 38) morning))
      (is (= (+ 67 72 58 43 31 19) evening))
      (is (> evening morning))))

  (testing "Find peak hours"
    (is (= 72 (oc/query traffic-peaks 0 24)))   ;; hour 19 was busiest
    (is (= 52 (oc/query traffic-peaks 6 12))))  ;; morning peak at hour 9

  (testing "Update when new data arrives - O(log n)"
    (let [updated-totals (assoc traffic-totals 20 85)]  ;; busy night!
      ;; Original was 58, now 85 - difference of 27
      (is (= (+ 67 72 85 43 31) (oc/query updated-totals 18 22)))))

  (testing "Sum tree for range aggregation"
    (let [sum-tree (oc/sum-tree {0 10, 1 20, 2 30, 3 40, 4 50})]
      (is (= 60 (oc/query sum-tree 0 2)))     ;; 10+20+30
      (is (= 150 (oc/query sum-tree 0 4)))))) ;; all

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 6: The Clearance Audit (subrange)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def stale-inventory
  (oc/ordered-map
    {12  {:sku "VR-100" :name "Void Runner" :price 299.99 :markdown 0}
     35  {:sku "SW-200" :name "Shadow Walker" :price 225.00 :markdown 0.10}
     67  {:sku "EU-300" :name "Europa Ice" :price 175.00 :markdown 0.15}
     91  {:sku "GW-400" :name "Gravity Well" :price 375.00 :markdown 0.25}
     120 {:sku "DD-500" :name "Dark Side Dunk" :price 450.00 :markdown 0.30}
     145 {:sku "OM-600" :name "Olympus Max" :price 599.00 :markdown 0.40}
     203 {:sku "AG-700" :name "Anti-Gravity 3000" :price 899.00 :markdown 0.50}}))

(deftest chapter-6-clearance-audit-test
  (testing "Find items stale 90+ days - liquidation candidates"
    (let [liquidation-candidates (oc/subrange stale-inventory :>= 90)]
      (is (= 4 (count liquidation-candidates)))
      (is (contains? liquidation-candidates 91))
      (is (contains? liquidation-candidates 120))
      (is (contains? liquidation-candidates 145))
      (is (contains? liquidation-candidates 203))))

  (testing "Calculate liquidation value"
    (let [liquidation-candidates (oc/subrange stale-inventory :>= 90)
          value (->> liquidation-candidates
                     (map (fn [[_ item]]
                            (* (:price item) (- 1 (:markdown item)))))
                     (reduce +))]
      ;; 375*0.75 + 450*0.70 + 599*0.60 + 899*0.50
      ;; = 281.25 + 315 + 359.4 + 449.5 = 1405.15
      (is (< (Math/abs (- 1405.15 value)) 0.01))))

  (testing "Warning zone (60-90 days)"
    (let [warning-zone (oc/subrange stale-inventory :>= 60 :< 90)]
      (is (= 1 (count warning-zone)))
      (let [[days item] (first warning-zone)]
        (is (= 67 days))
        (is (= "Europa Ice" (:name item))))))

  (testing "Fresh items (under 30 days)"
    (is (= 1 (count (oc/subrange stale-inventory :< 30)))))

  (testing "Compare full-price vs discounted inventory"
    (let [full-price (oc/subrange stale-inventory :< 60)
          discounted (oc/subrange stale-inventory :>= 60)
          liquidation (oc/subrange stale-inventory :>= 90)]
      (is (= 2 (count full-price)))
      (is (= 5 (count discounted)))
      (is (= 4 (count liquidation))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 7: The Promotional Post-Mortem (interval-map + segment-tree)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def promotions
  (oc/interval-map
    {[1 15]   :new-year-clearance      ;; days 1-14
     [20 35]  :jovian-appreciation     ;; days 20-34
     [25 28]  :flash-sale              ;; days 25-27 (overlaps jovian)
     [45 52]  :spring-preview          ;; days 45-51
     [80 91]  :end-of-quarter-push}))  ;; days 80-90

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
     ;; middle of quarter omitted (baseline)
     45 2100, 46 2400, 47 2600, 48 2300, 49 2200,
     50 2100, 51 2000,                            ;; spring preview
     80 3800, 81 4200, 82 4500, 83 4100, 84 3900,
     85 4600, 86 5200, 87 4800, 88 4400, 89 4100, 90 3800}))

(deftest chapter-7-promotional-post-mortem-test
  (testing "Query promotions active on a given day"
    (let [active-day-26 (promotions 26)]
      ;; Both jovian-appreciation and flash-sale active
      (is (some #{:jovian-appreciation} active-day-26))
      (is (some #{:flash-sale} active-day-26)))
    ;; Single promotion day
    (let [active-day-10 (promotions 10)]
      (is (some #{:new-year-clearance} active-day-10))
      (is (not (some #{:flash-sale} active-day-10)))))

  (testing "Query promotions touching a range"
    (let [active-30-50 (promotions [30 50])]
      (is (some #{:jovian-appreciation} active-30-50))
      (is (some #{:spring-preview} active-30-50))
      (is (not (some #{:flash-sale} active-30-50)))))

  (testing "Revenue during promotional periods"
    ;; Promo periods are half-open [start, end), but segment-tree query is inclusive
    ;; So we query [start, end-1] to get the correct range
    (let [promo-revenue (fn [[start end]]
                          (oc/query daily-revenue start (dec end)))]
      ;; New year clearance: days 1-14 (half-open [1,15))
      (let [revenue (promo-revenue [1 15])]
        (is (= (+ 2400 2100 2800 3100 2900 3400 3200 2800 2600 2500 2300 2400 2200 2100)
               revenue)))
      ;; Flash sale: days 25-27 (half-open [25,28))
      (let [revenue (promo-revenue [25 28])]
        (is (= (+ 4200 4800 5100) revenue))
        (is (= 14100 revenue)))))

  (testing "Per-day revenue analysis"
    (let [promo-periods {:new-year-clearance [1 15]
                         :jovian-appreciation [20 35]
                         :flash-sale [25 28]
                         :spring-preview [45 52]
                         :end-of-quarter-push [80 91]}
          analyze (fn [[name [start end]]]
                    (let [days (- end start)
                          ;; Query with (dec end) since segment-tree is inclusive
                          revenue (oc/query daily-revenue start (dec end))]
                      {:promo name
                       :days days
                       :revenue revenue
                       :per-day (/ revenue days)}))
          analysis (into {} (map (fn [r] [(:promo r) r]) (map analyze promo-periods)))]
      ;; Flash sale has highest per-day
      (is (= 4700 (:per-day (:flash-sale analysis))))
      ;; End of quarter also strong
      (is (> (:per-day (:end-of-quarter-push analysis)) 4000))))

  (testing "Overlap analysis - Jovian with/without Flash Sale"
    ;; Promo periods are half-open, segment-tree query is inclusive
    ;; Jovian: [20,35) = days 20-34, Flash: [25,28) = days 25-27
    (let [jovian-total (oc/query daily-revenue 20 34)
          flash-overlap (oc/query daily-revenue 25 27)
          jovian-alone (- jovian-total flash-overlap)]
      ;; Jovian total includes flash sale days (days 20-34)
      (is (= (+ 2800 3200 3500 3100 2900 4200 4800 5100 3400 3100 2800 2600 2400 2300 2200)
             jovian-total))
      ;; Flash contribution (days 25-27)
      (is (= 14100 flash-overlap))
      ;; Jovian baseline without flash
      (is (= (- jovian-total 14100) jovian-alone))
      ;; Flash lift percentage
      (let [lift-pct (int (* 100 (/ flash-overlap jovian-alone)))]
        (is (> lift-pct 40))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Epilogue: Integration Test
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest epilogue-integration-test
  (testing "All chapter features work together"
    ;; Chapter 1: range-map subnet allocation
    (let [network (-> (oc/range-map {[(ip "10.0.0.0") (ip "10.1.0.0")] :available})
                      (assoc [(ip "10.0.0.0") (ip "10.0.128.0")] :allocated))]
      (is (= :allocated (network (ip "10.0.64.0"))))
      (is (= :available (network (ip "10.0.200.0")))))

    ;; Chapter 2: nearest for size fitting
    (is (= 11.0 (oc/nearest available-sizes :<= 11.3)))

    ;; Chapter 3: split-key for segmentation
    (let [[small _ large] (oc/split-key 1000 (oc/ordered-set [100 500 1000 5000 10000]))]
      (is (= [100 500] (vec small)))
      (is (= [5000 10000] (vec large))))

    ;; Chapter 4: fuzzy lookup for tier mapping
    (is (= {:tier :silver :discount 0.10} (loyalty-tiers 600)))

    ;; Chapter 5: segment-tree for range queries
    (is (= (+ 67 72 58 43 31 19) (oc/query traffic-totals 18 24)))

    ;; Chapter 6: subrange for filtering
    (is (= 4 (count (oc/subrange stale-inventory :>= 90))))

    ;; Chapter 7: interval-map + segment-tree for attribution
    (is (some #{:flash-sale} (promotions 26)))
    (is (= 14100 (oc/query daily-revenue 25 27)))))  ; days 25-27 inclusive
