(ns com.dean.ordered-collections.zorp-test
  "Tests for examples in doc/zorp-example.md

   Zorp's Sneaker Emporium: Advanced Patterns
   Testing the 0.2.0 API features."
  (:refer-clojure :exclude [split-at])
  (:require [clojure.test :refer [deftest testing is are]]
            [com.dean.ordered-collections.core :as oc]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 1: The Fuzzy Warehouse (FuzzySet)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def catalog-prices
  (oc/fuzzy-set
    [99.99 149.50 175.00 225.00 299.99 375.00 450.00 599.00 899.00]
    :distance (fn [a b] (Math/abs (- a b)))))

(deftest chapter-1-fuzzy-warehouse-test
  (testing "Fuzzy lookup finds closest match"
    (is (= 175.0 (catalog-prices 180)))
    (is (= 299.99 (catalog-prices 300)))
    (is (= 99.99 (catalog-prices 100))))

  (testing "fuzzy-nearest returns value and distance"
    (let [[value distance] (oc/fuzzy-nearest catalog-prices 180)]
      (is (= 175.0 value))
      (is (= 5.0 distance)))
    (let [[value distance] (oc/fuzzy-nearest catalog-prices 550)]
      (is (= 599.0 value))
      (is (= 49.0 distance))))

  (testing "Tiebreak preference"
    (let [size-catalog-down (oc/fuzzy-set
                              [6.0 6.5 7.0 7.5 8.0 8.5 9.0 9.5 10.0]
                              :distance (fn [a b] (Math/abs (- a b)))
                              :tiebreak :<)]
      ;; 9.25 is equidistant from 9.0 and 9.5, tiebreak :< prefers smaller
      (is (= 9.0 (size-catalog-down 9.25))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 2: The Fuzzy Customer Database (FuzzyMap)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn levenshtein [^String s1 ^String s2]
  (let [n (count s1) m (count s2)]
    (cond
      (zero? n) m
      (zero? m) n
      :else
      (let [d (make-array Long/TYPE (inc n) (inc m))]
        (doseq [i (range (inc n))] (aset d i 0 (long i)))
        (doseq [j (range (inc m))] (aset d 0 j (long j)))
        (doseq [i (range 1 (inc n))
                j (range 1 (inc m))]
          (aset d i j
            (long (min (inc (aget d (dec i) j))
                       (inc (aget d i (dec j)))
                       (+ (aget d (dec i) (dec j))
                          (if (= (.charAt s1 (dec i))
                                 (.charAt s2 (dec j))) 0 1))))))
        (aget d n m)))))

(def customers
  (oc/fuzzy-map
    [["Krix" {:id "CUST-0042" :tier :gold}]
     ["Big Toe Tony" {:id "CUST-0007" :tier :diamond}]
     ["Mayor Glorbix" {:id "CUST-0001" :tier :platinum}]
     ["Blixxa" {:id "CUST-0117" :tier :silver}]
     ["Night Bot 3000" {:id "CUST-0099" :tier :bronze}]]
    :distance levenshtein))

(deftest chapter-2-fuzzy-customer-database-test
  (testing "Typo tolerance"
    (is (= {:id "CUST-0042" :tier :gold} (customers "Kricks")))
    (is (= {:id "CUST-0042" :tier :gold} (customers "Krix"))))

  (testing "Partial name matching"
    ;; Note: Levenshtein distance doesn't do substring matching.
    ;; "Tony" has edit distance 4 to "Krix" (all substitutions),
    ;; but distance 8 to "Big Toe Tony" (8 insertions).
    ;; Use a typo-like query instead:
    (is (= {:id "CUST-0007" :tier :diamond} (customers "Big Tow Tony"))))

  (testing "Mangled names"
    (is (= {:id "CUST-0001" :tier :platinum} (customers "Mayor Glorbox"))))

  (testing "Distance indicates confidence"
    ;; fuzzy-nearest on fuzzy-map returns [key value distance]
    (let [[_ _ distance] (oc/fuzzy-nearest customers "Krix")]
      (is (zero? distance)))  ; exact match
    (let [[_ _ distance] (oc/fuzzy-nearest customers "Zorp himself")]
      (is (> distance 5)))))  ; poor match

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 3: The Split Decision (split-key, split-at)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def yearly-transactions
  (oc/ordered-set
    [150 320 450 890 1200 1850 2400 3100 4500
     5200 6800 7500 8900 12000 15000 18500 22000]))

(deftest chapter-3-split-decision-test
  (testing "split-key partitions at threshold"
    (let [[small-biz mid-biz large-biz] (oc/split-key yearly-transactions 5000)]
      (is (= [150 320 450 890 1200 1850 2400 3100 4500] (vec small-biz)))
      (is (nil? mid-biz))  ; no transaction exactly at 5000
      (is (= [5200 6800 7500 8900 12000 15000 18500 22000] (vec large-biz)))))

  (testing "split-key with existing element"
    (let [[below entry above] (oc/split-key yearly-transactions 1200)]
      (is (= [150 320 450 890] (vec below)))
      (is (= 1200 entry))
      (is (= [1850 2400 3100 4500 5200 6800 7500 8900 12000 15000 18500 22000]
             (vec above)))))

  (testing "split-at partitions at index"
    (let [n (count yearly-transactions)
          q1 (quot n 4)
          [left right] (oc/split-at yearly-transactions q1)]
      (is (= q1 (count left)))
      (is (= (- n q1) (count right)))))

  (testing "split-at edge cases"
    (let [[left right] (oc/split-at yearly-transactions 0)]
      (is (empty? left))
      (is (= yearly-transactions right)))
    (let [[left right] (oc/split-at yearly-transactions (count yearly-transactions))]
      (is (= yearly-transactions left))
      (is (empty? right)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 4: The Subrange Inventory (subrange)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def inventory-by-size
  (oc/ordered-map
    [[6.0  ["Comet Cruiser" "Starlight Slip-on"]]
     [7.0  ["Void Runner" "Shadow Walker"]]
     [8.0  ["Void Runner" "Europa Ice" "Olympus Max"]]
     [9.0  ["Event Horizon" "Gravity Well"]]
     [10.0 ["Dark Side Dunk" "Void Runner" "Shadow Walker"]]
     [11.0 ["Olympus Max" "Event Horizon"]]
     [12.0 ["Void Runner" "Dark Side Dunk"]]
     [13.0 ["Shadow Walker"]]
     [14.0 ["Gravity Well" "Olympus Max"]]
     [15.0 ["Event Horizon XI"]]]))

(deftest chapter-4-subrange-inventory-test
  (testing "subrange with >= and <="
    (let [big-sizes (oc/subrange inventory-by-size >= 11.0 <= 15.0)]
      (is (= 5 (count big-sizes)))
      (is (contains? big-sizes 11.0))
      (is (contains? big-sizes 15.0))))

  (testing "subrange with >= and <"
    (let [mid-sizes (oc/subrange inventory-by-size >= 7.0 < 11.0)]
      (is (= 4 (count mid-sizes)))
      (is (contains? mid-sizes 7.0))
      (is (contains? mid-sizes 10.0))
      (is (not (contains? mid-sizes 11.0)))))

  (testing "subrange single-bound"
    (let [large (oc/subrange inventory-by-size > 10.0)]
      (is (= 5 (count large)))
      (is (not (contains? large 10.0))))
    (let [small (oc/subrange inventory-by-size < 8.0)]
      (is (= 2 (count small)))
      (is (contains? small 6.0))
      (is (contains? small 7.0)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 5: The Nearest Competitor (nearest)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def our-prices
  (oc/ordered-set
    [99.99 149.50 175.00 225.00 275.00 299.99
     350.00 399.00 450.00 525.00 599.00 750.00 899.00]))

(deftest chapter-5-nearest-competitor-test
  (testing "nearest <="
    (is (= 275.0 (oc/nearest our-prices <= 280)))
    (is (= 399.0 (oc/nearest our-prices <= 400)))
    (is (= 899.0 (oc/nearest our-prices <= 1000))))

  (testing "nearest >="
    (is (= 299.99 (oc/nearest our-prices >= 280)))
    (is (= 450.0 (oc/nearest our-prices >= 400)))
    (is (= 525.0 (oc/nearest our-prices >= 500))))

  (testing "nearest < (strict)"
    (is (= 275.0 (oc/nearest our-prices < 280)))
    (is (= 399.0 (oc/nearest our-prices < 400)))
    (is (= 350.0 (oc/nearest our-prices < 399))))

  (testing "nearest > (strict)"
    (is (= 299.99 (oc/nearest our-prices > 280)))
    (is (= 450.0 (oc/nearest our-prices > 400)))
    (is (= 450.0 (oc/nearest our-prices > 399))))

  (testing "nearest at boundaries"
    (is (nil? (oc/nearest our-prices < 99.99)))
    (is (nil? (oc/nearest our-prices > 899.0)))
    (is (= 99.99 (oc/nearest our-prices <= 99.99)))
    (is (= 899.0 (oc/nearest our-prices >= 899.0))))

  (testing "nearest on ordered-map"
    (let [price-map (oc/ordered-map
                      [[100 :budget]
                       [250 :mid]
                       [500 :premium]])]
      (is (= [250 :mid] (oc/nearest price-map <= 300)))
      (is (= [500 :premium] (oc/nearest price-map >= 400))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 6: Combining Structures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def tony-purchases
  (oc/ordered-map
    [[1000 2500]  [1500 3200]  [2000 4100]  [2500 1800]
     [3000 5500]  [3500 2900]  [4000 7200]  [4500 4400]
     [5000 8100]  [5500 3300]  [6000 6600]]))

(deftest chapter-6-combining-structures-test
  (testing "Segment tree for range sums"
    (let [tony-spending (oc/sum-tree (into {} tony-purchases))]
      ;; Q1: timestamps 1000-3000
      (is (= (+ 2500 3200 4100 1800 5500)
             (oc/query tony-spending 1000 3000)))
      ;; Q2: timestamps 3500-6000
      (is (= (+ 2900 7200 4400 8100 3300 6600)
             (oc/query tony-spending 3500 6000)))))

  (testing "Split purchases by amount"
    (let [amounts (oc/ordered-set (vals tony-purchases))
          [small _ medium-up] (oc/split-key amounts 3000)
          [medium _ large] (oc/split-key medium-up 5000)]
      (is (= #{1800 2500 2900} (set small)))
      (is (= #{3200 3300 4100 4400} (set medium)))
      (is (= #{5500 6600 7200 8100} (set large))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 7: The Time-Slice Analysis
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def inventory-events
  [[1000 "VR" +100]  [1100 "SW" +50]   [1200 "VR" -20]
   [1300 "EH" +75]   [1400 "SW" -15]   [1500 "VR" -30]
   [1600 "DD" +40]   [1700 "EH" -25]   [1800 "VR" +50]
   [1900 "SW" -10]   [2000 "DD" -5]    [2100 "VR" -40]])

(defn inventory-at [events timestamp]
  (let [relevant (filter #(<= (first %) timestamp) events)]
    (->> relevant
         (reduce (fn [inv [_ sku delta]]
                   (update inv sku (fnil + 0) delta))
                 (oc/ordered-map)))))

(deftest chapter-7-time-slice-analysis-test
  (testing "Inventory state at various times"
    (is (= {"SW" 50 "VR" 80}
           (into {} (inventory-at inventory-events 1200))))
    (is (= {"DD" 40 "EH" 50 "SW" 35 "VR" 50}
           (into {} (inventory-at inventory-events 1700))))
    (is (= {"DD" 35 "EH" 50 "SW" 25 "VR" 60}
           (into {} (inventory-at inventory-events 2100)))))

  (testing "Inventory is sorted by SKU"
    (let [inv (inventory-at inventory-events 2100)]
      (is (= ["DD" "EH" "SW" "VR"] (vec (keys inv)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Epilogue: Integration Test
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest epilogue-integration-test
  (testing "All new 0.2.0 features work together"
    ;; Fuzzy lookup
    (is (= {:id "CUST-0007" :tier :diamond} (customers "Big Tow Tony")))

    ;; Split at threshold
    (let [[small _ large] (oc/split-key yearly-transactions 5000)]
      (is (= 9 (count small)))
      (is (= 8 (count large))))

    ;; Subrange for filtering
    (let [mid-tier (oc/subrange our-prices >= 200 < 500)]
      (is (= 6 (count mid-tier))))  ; 225, 275, 299.99, 350, 399, 450

    ;; Nearest for competitive analysis
    (is (= 275.0 (oc/nearest our-prices <= 280)))))
