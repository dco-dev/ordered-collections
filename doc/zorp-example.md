# Zorp's Sneaker Emporium: A Practical Guide

*A tale of data structures, dark-side commerce, and surprisingly fresh kicks*

---

## Prologue

Zorp runs the only sneaker store on the dark side of Pluto. Business is good—the perpetual darkness means nobody can see your shoes, which paradoxically makes everyone *obsessed* with having the freshest ones. "It's about knowing," Zorp explains to confused off-world visitors. "Knowing you're dripping."

This is the story of how Zorp uses the `ordered-collections` library to manage his interplanetary sneaker empire.

---

## Chapter 1: The Inventory Problem

Zorp's inventory is chaos. Shipments arrive from Earth (8-month delay), Mars (3 weeks), and the Jovian moons (2 days, but they only make sandals). He needs to track thousands of SKUs, look them up fast, and always know what's in stock.

```clojure
(require '[com.dean.ordered-collections.core :as oc])

;; Zorp's inventory: SKU -> {:name, :size, :quantity, :price}
(def inventory
  (oc/ordered-map
    {"PLT-001" {:name "Shadow Walker 9000" :size 10 :quantity 45 :price 299.99}
     "PLT-002" {:name "Dark Side Dunks"    :size 11 :quantity 12 :price 450.00}
     "PLT-003" {:name "Void Runner"        :size 9  :quantity 0  :price 175.50}
     "JUP-017" {:name "Europa Ice Grip"    :size 10 :quantity 88 :price 225.00}
     "MRS-042" {:name "Olympus Max"        :size 12 :quantity 33 :price 380.00}}))

;; Fast lookup when a customer asks for a specific SKU
(inventory "PLT-002")
;; => {:name "Dark Side Dunks", :size 11, :quantity 12, :price 450.00}

;; Zorp wants to see all Plutonian models (SKUs starting with PLT)
;; The ordered-map keeps keys sorted, so he can grab a range efficiently
(subseq inventory >= "PLT" < "PLU")
;; => (["PLT-001" {...}] ["PLT-002" {...}] ["PLT-003" {...}])

;; New shipment arrives! Immutable update, Zorp's accountant loves the audit trail
(def inventory'
  (assoc inventory "PLT-003"
    (update (inventory "PLT-003") :quantity + 50)))

(get-in inventory' ["PLT-003" :quantity])
;; => 50
```

"The sorted keys," Zorp muses, stroking his antenna, "they let me slice the catalog by manufacturer prefix. Very satisfying."

---

## Chapter 2: The VIP Customer Rankings

Zorp's loyalty program tracks customer spending. He needs to answer questions like "Who are my top 10 spenders?" and "What percentile is this customer in?" without re-sorting everything constantly.

```clojure
;; RankedSet: sorted set with O(log n) positional access
;; We'll store [total-spent customer-id] pairs so they sort by spending

(def customer-spending
  (oc/ranked-set
    [[15420.00 "CUST-0042"]   ; Krix, the methane baron
     [8730.50  "CUST-0117"]   ; Anonymous (pays in nitrogen credits)
     [45200.00 "CUST-0001"]   ; The Mayor's office
     [3200.00  "CUST-0233"]   ; First-time buyer
     [12800.00 "CUST-0089"]   ; Repeat customer
     [52100.00 "CUST-0007"]   ; "Big Toe" Tony
     [9999.99  "CUST-0404"]])) ; Suspicious round number

;; Who's the biggest spender?
(oc/nth-element customer-spending (dec (count customer-spending)))
;; => [52100.0 "CUST-0007"]  -- Big Toe Tony, of course

;; Top 3 spenders (highest indices in ascending-sorted set)
(let [n (count customer-spending)]
  (map #(oc/nth-element customer-spending %)
       (range (- n 3) n)))
;; => ([15420.0 "CUST-0042"] [45200.0 "CUST-0001"] [52100.0 "CUST-0007"])

;; What's the median spending level?
(oc/median customer-spending)
;; => [12800.0 "CUST-0089"]

;; A new customer wants to know: "Am I in the top 25%?"
(let [spending [8730.50 "CUST-0117"]
      rank     (oc/rank customer-spending spending)
      percentile (* 100 (/ rank (count customer-spending)))]
  (println "You're at the" (int percentile) "percentile!")
  (> percentile 75))
;; You're at the 14 percentile!
;; => false
```

"Big Toe Tony," Zorp sighs. "He bought every color of the Void Runner. Every. Color. The man has 47 feet."

---

## Chapter 3: The Shift Schedule

Zorp's store is open during "business hours"—but on the dark side of Pluto, time is meaningless. So he defines shifts by arbitrary time units (PTU: Pluto Time Units). He needs to quickly answer: "Who's working at PTU 4500?"

```clojure
;; IntervalMap: map from intervals to values
;; Keys are [start end] intervals, values are employee names

(def shift-schedule
  (oc/interval-map
    {[0 2000]     "Glorm (morning shift)"
     [2000 4000]  "Blixxa (afternoon shift)"
     [4000 6000]  "Zorp (evening shift, owner's hours)"
     [6000 8000]  "Night Bot 3000 (graveyard shift)"
     [1800 2200]  "Krix Jr. (overlap coverage)"}))

;; Customer calls at PTU 4500. Who picks up?
(shift-schedule 4500)
;; => ("Zorp (evening shift, owner's hours)")

;; During shift change at PTU 2000, who's available?
(shift-schedule 2000)
;; => ("Glorm (morning shift)"
;;     "Blixxa (afternoon shift)"
;;     "Krix Jr. (overlap coverage)")

;; Krix Jr. works a weird split shift for overlap coverage
(shift-schedule 1900)
;; => ("Glorm (morning shift)" "Krix Jr. (overlap coverage)")
```

"The interval map," Zorp explains to his new hire, "handles the overlaps automatically. Krix Jr. wanted 'creative scheduling.' Now I can just query any moment and know who's supposed to be here."

---

## Chapter 4: The Discount Tiers

Zorp's discount system is based on purchase amount. Different ranges get different discounts, and ranges can't overlap (unlike the interval map)—each credit amount maps to exactly one discount tier.

```clojure
;; RangeMap: non-overlapping ranges, each point maps to one value
;; When you insert a range, it automatically carves out space

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

;; Big spender alert!
(discount-tiers 12000)
;; => :platinum-20-percent

;; Edge case: exactly 1000 credits
(discount-tiers 1000)
;; => :gold-15-percent  (ranges are [lo, hi) -- 1000 is in gold tier)

;; Zorp runs a flash sale: 20% off for purchases 200-400 credits
;; This automatically splits the bronze tier!
(def flash-sale-tiers
  (assoc discount-tiers [200 400] :flash-sale-20-percent))

(oc/ranges flash-sale-tiers)
;; => ([[0 100] :no-discount]
;;     [[100 200] :bronze-5-percent]      ; auto-trimmed!
;;     [[200 400] :flash-sale-20-percent] ; inserted
;;     [[400 500] :bronze-5-percent]      ; auto-trimmed!
;;     [[500 1000] :silver-10-percent]
;;     ...)
```

"Before the range-map," Zorp recalls darkly, "I had seventeen overlapping discount codes and a customer who got 95% off a limited edition. Never again."

---

## Chapter 5: The Sales Analytics

Zorp wants to analyze daily sales. Specifically, he needs to answer range queries like "What were total sales from day 50 to day 75?" and update individual days as sales come in—all in logarithmic time.

```clojure
;; SegmentTree: range aggregate queries with O(log n) updates and queries
;; Perfect for "sum of values in range [a,b]" questions

;; Daily sales for the first quarter (90 days)
;; Start with some historical data
(def daily-sales
  (oc/segment-tree + 0  ; operation: +, identity: 0
    (into {} (for [day (range 1 91)]
               [day (+ 1000 (rand-int 500))]))))  ; 1000-1500 credits/day

;; Total sales for days 1-30 (first month)
(oc/query daily-sales 1 30)
;; => ~37500 (varies with random data)

;; Total sales for days 31-60 (second month)
(oc/query daily-sales 31 60)
;; => ~38200

;; Big sale day! Update day 45 with actual figure
(def daily-sales'
  (oc/update-val daily-sales 45 8500))

;; Requery - the tree updates in O(log n)
(oc/query daily-sales' 40 50)
;; => includes the 8500 spike

;; What's the total for the whole quarter?
(oc/aggregate daily-sales')
;; => sum of all 90 days, O(1) time!

;; Zorp also tracks minimum daily sales to identify slow days
(def min-daily-sales
  (oc/min-tree
    (into {} (for [day (range 1 91)]
               [day (+ 1000 (rand-int 500))]))))

;; Worst day in the second month?
(oc/query min-daily-sales 31 60)
;; => something around 1000-1050
```

"The segment tree," Zorp tells his accountant (a sentient calculator from Neptune), "gives me range sums instantly. Quarterly reports used to take hours. Now? Logarithmic time. The auditors are suspicious it's *too* fast."

---

## Chapter 6: The Sneaker Reservation System

Zorp's hottest releases require a reservation system. Customers select time slots to pick up their shoes. Each slot can only be used once, and Zorp needs fast set operations to manage availability.

```clojure
;; OrderedSet for managing available and reserved slots

(def all-slots
  (oc/ordered-set (range 100 200)))  ; slots 100-199 available today

(def reserved-slots
  (oc/ordered-set [105 110 115 120 125 142 143 144 150 175 188]))

;; Available slots = all-slots - reserved-slots
(def available
  (oc/difference all-slots reserved-slots))

(count available)
;; => 89 slots still open

;; Customer wants the earliest available slot at or after 140
(first (subseq available >= 140))
;; => 140 (it's available!)

;; Customer wants specifically AFTER 140
(first (subseq available > 140))
;; => 141 (since 142-144 are taken)

;; Another customer takes 141
(def available' (disj available 141))

;; VIP customer Krix wants to know: are ANY slots between 170-180 open?
(seq (subseq available' >= 170 < 180))
;; => (170 171 172 173 174 176 177 178 179)  -- plenty! (175 was reserved)
```

---

## Chapter 7: The Priority Repair Queue

Shoes break. It happens. Zorp offers repair services, but some repairs are more urgent than others. A customer's only pair? Rush job. Seventh pair of limited editions? They can wait.

```clojure
;; Priority queue based on urgency score (lower = more urgent)
;; Use priority-queue-by with [priority job] pairs

(def repair-queue
  (oc/priority-queue-by <
    [[1 {:customer "CUST-0042" :issue "Sole detachment, only pair"}]
     [5 {:customer "CUST-0007" :issue "Scuff marks, has 46 other pairs"}]
     [2 {:customer "CUST-0117" :issue "Lace replacement, formal event tomorrow"}]
     [3 {:customer "CUST-0233" :issue "Squeaky heel"}]
     [1 {:customer "CUST-0089" :issue "Zipper stuck, only winter boots"}]]))

;; Who's first? (peek returns just the job, not the priority)
(peek repair-queue)
;; => {:customer "CUST-0042" :issue "Sole detachment, only pair"}

;; Process both priority-1 jobs, then see who's next
(-> repair-queue pop pop peek)
;; => {:customer "CUST-0117" :issue "Lace replacement, formal event tomorrow"}

;; How many repairs pending?
(count repair-queue)
;; => 5
```

"Big Toe Tony's scuff marks," Zorp mutters, "can wait until the heat death of the universe."

---

## Epilogue: The Integration

It's the end of a long Pluto day (about 6 Earth days, but who's counting). Zorp reviews his systems:

```clojure
(defn daily-report []
  (println "=== ZORP'S SNEAKER EMPORIUM - DAILY REPORT ===")
  (println)
  (println "Inventory SKUs:" (count inventory))
  (println "Top customer:" (last (seq customer-spending)))
  (println "Current shift:" (first (shift-schedule 4500)))
  (println "Available pickup slots:" (count available))
  (println "Repairs pending:" (count repair-queue))
  (println "Q1 sales to date:" (oc/aggregate daily-sales))
  (println)
  (println "All systems nominal. Stay frosty. Literally."))

(daily-report)
;; === ZORP'S SNEAKER EMPORIUM - DAILY REPORT ===
;;
;; Inventory SKUs: 5
;; Top customer: [52100.0 "CUST-0007"]
;; Current shift: Zorp (evening shift, owner's hours)
;; Available pickup slots: 89
;; Repairs pending: 5
;; Q1 sales to date: 115847.50
;;
;; All systems nominal. Stay frosty. Literally.
```

Zorp dims the store lights (not that it makes a difference on the dark side) and heads home. Tomorrow, a shipment of the new "Event Horizon XI" arrives from Earth. He'll need to update the inventory, adjust the discount tiers for the launch, schedule extra shifts, and prepare the segment tree for what he hopes will be record-breaking sales.

But that's tomorrow. Tonight, Zorp puts on his personal pair of Shadow Walker 9000s—the ones he'll never sell—and walks out into the eternal darkness, fresh kicks glowing faintly with bioluminescent laces.

*It's about knowing.*

---

## Quick Reference

| Data Structure | Use Case | Key Operations |
|---------------|----------|----------------|
| `ordered-map` | Sorted key-value store | `get`, `assoc`, `subseq` |
| `ordered-set` | Sorted unique elements | `conj`, `disj`, `subseq`, set operations |
| `ranked-set` | Positional access to sorted set | `nth-element`, `rank`, `median`, `percentile` |
| `interval-map` | Overlapping interval queries | `get` (returns all overlapping values) |
| `interval-set` | Set of potentially overlapping intervals | `get` (returns all overlapping intervals) |
| `range-map` | Non-overlapping range mapping | `get`, `assoc` (auto-splits existing ranges) |
| `segment-tree` | Range aggregate queries | `query`, `update-val`, `aggregate` |
| `priority-queue` | Priority-ordered queue | `conj`, `peek`, `pop` |

---

*Zorp's Sneaker Emporium is a registered trademark of Zorp Enterprises, LLC (Pluto Division). No actual Plutonians were harmed in the making of this documentation. Big Toe Tony is a real customer and has given written consent for his likeness to be used in educational materials.*
