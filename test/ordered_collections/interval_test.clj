(ns ordered-collections.interval-test
  (:require [clojure.test :refer :all]
            [ordered-collections.kernel.interval :as interval :refer :all])
  (:import  [clojure.lang MapEntry]))


(deftest pair-check
  (is (ordered-pair? (MapEntry. 0 1)))
  (is (ordered-pair? (vector 0 1)))
  (is (ordered-pair? (ordered-pair 0 1)))
  (is (ordered-pair? (ordered-pair [0 1])))
  (is (thrown? java.lang.AssertionError (ordered-pair 1 0)))
  (is (thrown? java.lang.AssertionError (ordered-pair [1 0])))
  (is (not (ordered-pair? (MapEntry. 1 0))))
  (is (not (ordered-pair? [1 0])))
  (is (not (ordered-pair? :foo))))

(deftest intersection-check
  (is (overlaps? [1 3] [2 4]))
  (is (overlaps? [2 4] [1 3]))
  (is (overlaps? [1 3] [3 4]))
  (is (not (overlaps? [1 2] [3 4])))
  (is (not (includes? [0 1] [0 2])))
  (is (not (includes? [0 1] [1 2])))
  (is (includes? [0 2] [0 1]))
  (is (intersects? [1 3] [2 4]))
  (is (intersects? [2 4] [1 3]))
  (is (intersects? [1 3] [3 4]))
  (is (not (intersects? [1 2] [3 4])))
  (is (intersects? [0 1] [0 2]))
  (is (intersects? [0 1] [1 2]))
  (is (intersects? [0 2] [0 1])))
