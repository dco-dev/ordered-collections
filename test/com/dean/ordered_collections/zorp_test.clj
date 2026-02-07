(ns com.dean.ordered-collections.zorp-test
  "Tests for all examples in doc/zorp-example.md

   Zorp's Sneaker Emporium: ensuring the dark side of Pluto
   has reliable data structures since PTU 0."
  (:require [clojure.test :refer [deftest testing is are]]
            [com.dean.ordered-collections.core :as oc]
            [com.dean.ordered-collections.tree.protocol :as proto]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 1: The Inventory Problem (OrderedMap)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def inventory
  (oc/ordered-map
    {"PLT-001" {:name "Shadow Walker 9000" :size 10 :quantity 45 :price 299.99}
     "PLT-002" {:name "Dark Side Dunks"    :size 11 :quantity 12 :price 450.00}
     "PLT-003" {:name "Void Runner"        :size 9  :quantity 0  :price 175.50}
     "JUP-017" {:name "Europa Ice Grip"    :size 10 :quantity 88 :price 225.00}
     "MRS-042" {:name "Olympus Max"        :size 12 :quantity 33 :price 380.00}}))

(deftest chapter-1-inventory-test
  (testing "Fast lookup by SKU"
    (is (= {:name "Dark Side Dunks" :size 11 :quantity 12 :price 450.00}
           (inventory "PLT-002")))
    (is (nil? (inventory "NONEXISTENT"))))

  (testing "Range query by SKU prefix"
    (let [plt-skus (subseq inventory >= "PLT" < "PLU")]
      (is (= 3 (count plt-skus)))
      (is (= ["PLT-001" "PLT-002" "PLT-003"]
             (map first plt-skus)))))

  (testing "Immutable update preserves original"
    (let [inventory' (assoc inventory "PLT-003"
                       (update (inventory "PLT-003") :quantity + 50))]
      (is (= 0 (get-in inventory ["PLT-003" :quantity])))
      (is (= 50 (get-in inventory' ["PLT-003" :quantity])))))

  (testing "Keys are sorted"
    (is (= ["JUP-017" "MRS-042" "PLT-001" "PLT-002" "PLT-003"]
           (map first (seq inventory))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 2: The VIP Customer Rankings (RankedSet)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def customer-spending
  (oc/ranked-set
    [[15420.00 "CUST-0042"]   ; Krix, the methane baron
     [8730.50  "CUST-0117"]   ; Anonymous
     [45200.00 "CUST-0001"]   ; The Mayor's office
     [3200.00  "CUST-0233"]   ; First-time buyer
     [12800.00 "CUST-0089"]   ; Repeat customer
     [52100.00 "CUST-0007"]   ; "Big Toe" Tony
     [9999.99  "CUST-0404"]])); Suspicious round number

(deftest chapter-2-customer-rankings-test
  (testing "Biggest spender (last element)"
    (is (= [52100.00 "CUST-0007"]
           (oc/nth-element customer-spending (dec (count customer-spending))))))

  (testing "Top 3 spenders"
    (let [n (count customer-spending)
          top-3 (map #(oc/nth-element customer-spending %) (range (- n 3) n))]
      (is (= [[15420.0 "CUST-0042"]
              [45200.0 "CUST-0001"]
              [52100.0 "CUST-0007"]]
             top-3))))

  (testing "Median spending"
    ;; 7 elements sorted: [3200, 8730.5, 9999.99, 12800, 15420, 45200, 52100]
    ;; Median index = (quot 6 2) = 3 -> [12800.0 "CUST-0089"]
    (is (= [12800.0 "CUST-0089"]
           (oc/median customer-spending))))

  (testing "Rank lookup"
    ;; Sorted: 0=[3200], 1=[8730.5], 2=[9999.99], 3=[12800], 4=[15420], 5=[45200], 6=[52100]
    (is (= 1 (oc/rank customer-spending [8730.50 "CUST-0117"])))
    (is (= 0 (oc/rank customer-spending [3200.00 "CUST-0233"])))
    (is (= 6 (oc/rank customer-spending [52100.00 "CUST-0007"]))))

  (testing "Percentile calculation"
    (let [spending [8730.50 "CUST-0117"]
          rank (oc/rank customer-spending spending)
          percentile (* 100 (/ rank (count customer-spending)))]
      (is (< percentile 75) "Customer should not be in top 25%"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 3: The Shift Schedule (IntervalMap)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def shift-schedule
  (oc/interval-map
    {[0 2000]     "Glorm (morning shift)"
     [2000 4000]  "Blixxa (afternoon shift)"
     [4000 6000]  "Zorp (evening shift, owner's hours)"
     [6000 8000]  "Night Bot 3000 (graveyard shift)"
     [1800 2200]  "Krix Jr. (overlap coverage)"}))

(deftest chapter-3-shift-schedule-test
  (testing "Single shift query"
    (is (= ["Zorp (evening shift, owner's hours)"]
           (shift-schedule 4500)))
    (is (= ["Night Bot 3000 (graveyard shift)"]
           (shift-schedule 7000))))

  (testing "Overlapping shifts at shift change"
    (let [workers (set (shift-schedule 2000))]
      (is (contains? workers "Glorm (morning shift)"))
      (is (contains? workers "Blixxa (afternoon shift)"))
      (is (contains? workers "Krix Jr. (overlap coverage)"))))

  (testing "Krix Jr. overlap coverage"
    (let [workers-1900 (set (shift-schedule 1900))
          workers-2100 (set (shift-schedule 2100))]
      (is (contains? workers-1900 "Glorm (morning shift)"))
      (is (contains? workers-1900 "Krix Jr. (overlap coverage)"))
      (is (contains? workers-2100 "Blixxa (afternoon shift)"))
      (is (contains? workers-2100 "Krix Jr. (overlap coverage)"))))

  (testing "No coverage outside defined shifts"
    (is (nil? (shift-schedule 9000)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 4: The Discount Tiers (RangeMap)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def discount-tiers
  (-> (oc/range-map)
      (assoc [0 100]      :no-discount)
      (assoc [100 500]    :bronze-5-percent)
      (assoc [500 1000]   :silver-10-percent)
      (assoc [1000 5000]  :gold-15-percent)
      (assoc [5000 50000] :platinum-20-percent)))

(deftest chapter-4-discount-tiers-test
  (testing "Basic tier lookups"
    (is (= :no-discount (discount-tiers 50)))
    (is (= :bronze-5-percent (discount-tiers 250)))
    (is (= :silver-10-percent (discount-tiers 750)))
    (is (= :gold-15-percent (discount-tiers 2500)))
    (is (= :platinum-20-percent (discount-tiers 12000))))

  (testing "Edge cases at tier boundaries (half-open intervals)"
    (is (= :no-discount (discount-tiers 0)))
    (is (= :no-discount (discount-tiers 99)))
    (is (= :bronze-5-percent (discount-tiers 100)))
    (is (= :silver-10-percent (discount-tiers 500)))
    (is (= :gold-15-percent (discount-tiers 1000))))

  (testing "Flash sale splits existing tier"
    (let [flash-sale-tiers (assoc discount-tiers [200 400] :flash-sale-20-percent)
          ranges (oc/ranges flash-sale-tiers)]
      ;; Bronze tier should be split into [100,200) and [400,500)
      (is (= :bronze-5-percent (flash-sale-tiers 150)))
      (is (= :flash-sale-20-percent (flash-sale-tiers 300)))
      (is (= :bronze-5-percent (flash-sale-tiers 450)))
      ;; Verify the split happened
      (is (some #(= [[100 200] :bronze-5-percent] %) ranges))
      (is (some #(= [[200 400] :flash-sale-20-percent] %) ranges))
      (is (some #(= [[400 500] :bronze-5-percent] %) ranges))))

  (testing "Outside all ranges returns nil"
    (is (nil? (discount-tiers 100000)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 5: The Sales Analytics (SegmentTree)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def daily-sales
  (oc/segment-tree + 0
    (into {} (for [day (range 1 91)]
               [day (* 100 day)]))))  ; Predictable: day 1 = 100, day 2 = 200, etc.

(deftest chapter-5-sales-analytics-test
  (testing "Range sum query"
    ;; Sum of days 1-10: 100 + 200 + ... + 1000 = 100 * (1+2+...+10) = 100 * 55 = 5500
    (is (= 5500 (oc/query daily-sales 1 10)))
    ;; Sum of days 1-30: 100 * (1+2+...+30) = 100 * 465 = 46500
    (is (= 46500 (oc/query daily-sales 1 30))))

  (testing "Single day query"
    (is (= 4500 (oc/query daily-sales 45 45))))

  (testing "Update value and requery"
    (let [daily-sales' (oc/update-val daily-sales 45 10000)]
      ;; Day 45 was 4500, now 10000
      (is (= 10000 (oc/query daily-sales' 45 45)))
      ;; Range 40-50 should reflect the change
      ;; Original: 100*(40+41+...+50) = 100*495 = 49500
      ;; New: 49500 - 4500 + 10000 = 55000
      (is (= 55000 (oc/query daily-sales' 40 50)))
      ;; Original unchanged
      (is (= 4500 (oc/query daily-sales 45 45)))))

  (testing "Aggregate of entire tree"
    ;; Sum of 1-90: 100 * (1+2+...+90) = 100 * 4095 = 409500
    (is (= 409500 (oc/aggregate daily-sales))))

  (testing "Min segment tree"
    (let [min-sales (oc/min-tree
                      (into {} (for [day (range 1 91)]
                                 [day (if (= day 45) 50 1000)])))]
      ;; Day 45 has the minimum
      (is (= 50 (oc/query min-sales 40 50)))
      (is (= 1000 (oc/query min-sales 1 10)))))

  (testing "Max segment tree"
    (let [max-sales (oc/max-tree
                      (into {} (for [day (range 1 91)]
                                 [day (if (= day 45) 9999 100)])))]
      (is (= 9999 (oc/query max-sales 40 50)))
      (is (= 100 (oc/query max-sales 1 10))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 6: The Sneaker Reservation System (OrderedSet)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def all-slots
  (oc/ordered-set (range 100 200)))

(def reserved-slots
  (oc/ordered-set [105 110 115 120 125 142 143 144 150 175 188]))

(deftest chapter-6-reservation-system-test
  (testing "Set difference for available slots"
    (let [available (oc/difference all-slots reserved-slots)]
      (is (= 89 (count available)))
      (is (not (contains? available 105)))
      (is (not (contains? available 142)))
      (is (contains? available 106))
      (is (contains? available 141))))

  (testing "Find earliest slot after a time"
    (let [available (oc/difference all-slots reserved-slots)]
      ;; 140 is available, so >= 140 returns 140
      (is (= 140 (first (subseq available >= 140))))
      ;; First available > 140 is 141
      (is (= 141 (first (subseq available > 140))))
      ;; First available after 105 should be 106
      (is (= 106 (first (subseq available > 105))))))

  (testing "Check availability in range"
    (let [available (oc/difference all-slots reserved-slots)
          slots-170-180 (seq (subseq available >= 170 < 180))]
      ;; 175 is reserved, so we should have 170-174 and 176-179
      (is (= [170 171 172 173 174 176 177 178 179] (vec slots-170-180)))))

  (testing "Disjoining a slot"
    (let [available (oc/difference all-slots reserved-slots)
          available' (disj available 141)]
      (is (contains? available 141))
      (is (not (contains? available' 141)))
      (is (= 88 (count available')))))

  (testing "Set union for all reserved"
    (let [more-reserved (oc/ordered-set [106 107 108])
          all-reserved (oc/union reserved-slots more-reserved)]
      (is (= 14 (count all-reserved)))
      (is (contains? all-reserved 106)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 7: The Priority Repair Queue (PriorityQueue)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def repair-queue
  (oc/priority-queue-by <
    [[1 {:customer "CUST-0042" :issue "Sole detachment, only pair"}]
     [5 {:customer "CUST-0007" :issue "Scuff marks, has 46 other pairs"}]
     [2 {:customer "CUST-0117" :issue "Lace replacement, formal event tomorrow"}]
     [3 {:customer "CUST-0233" :issue "Squeaky heel"}]
     [1 {:customer "CUST-0089" :issue "Zipper stuck, only winter boots"}]]))

(deftest chapter-7-repair-queue-test
  ;; priority-queue-by returns just the value on peek, not [priority value]
  (testing "Peek returns highest priority job (lowest number)"
    (let [job (peek repair-queue)]
      ;; Either CUST-0042 or CUST-0089 (both priority 1)
      (is (contains? #{"CUST-0042" "CUST-0089"} (:customer job)))))

  (testing "Pop removes highest priority"
    (let [queue' (pop repair-queue)
          job (peek queue')]
      (is (= 4 (count queue')))
      ;; Next job should be from priority 1 or 2
      (is (contains? #{"CUST-0042" "CUST-0089" "CUST-0117"} (:customer job)))))

  (testing "Processing drains priority-1 jobs first"
    ;; Pop until we get a non-priority-1 job
    (let [queue-after-priority-1 (-> repair-queue pop pop)]
      ;; After popping 2 priority-1 jobs, next should be priority 2
      (is (= {:customer "CUST-0117" :issue "Lace replacement, formal event tomorrow"}
             (peek queue-after-priority-1)))))

  (testing "Queue has correct count"
    (is (= 5 (count repair-queue))))

  (testing "Queue empties correctly"
    (let [final-queue (-> repair-queue pop pop pop pop pop)]
      (is (empty? final-queue)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Epilogue: Integration Test
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest epilogue-integration-test
  (testing "All data structures work together"
    (let [inv-count (count inventory)
          top-customer (last (seq customer-spending))
          current-shift (first (shift-schedule 4500))
          available-slots (count (oc/difference all-slots reserved-slots))
          repairs-pending (count repair-queue)
          q1-sales (oc/aggregate daily-sales)]
      (is (= 5 inv-count))
      (is (= [52100.0 "CUST-0007"] top-customer))
      (is (= "Zorp (evening shift, owner's hours)" current-shift))
      (is (= 89 available-slots))
      (is (= 5 repairs-pending))
      (is (= 409500 q1-sales)))))
