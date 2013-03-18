(ns clj-span.line-of-sight-test
  (:use clojure.test
        clj-span.core
        clj-span.models.line-of-sight :reload-all
        clj-misc.utils
        clj-misc.matrix-ops))

(def value-type :numbers)

(defn register-math-syms [t]
  (with-typed-math-syms value-type [_0_ _+_ _-_ _*_ _d_ _* *_ _d _- -_ _>_ _<_ _max_ rv-fn _>]
    (t)))

(use-fixtures :once register-math-syms)

(def source-layer
  [[0.0 1.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]])

(def sink-layer
  [[0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]])

(def use-layer
  [[0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]])

(def elev-layer
 [[30.0 30.0 32.0 32.0 32.0 22.0 11.0  5.0  5.0 5.0]
  [30.0 29.0 27.0 27.0 27.0 20.0  6.0  5.0  5.0 5.0]
  [30.0 28.0 22.0 22.0 22.0 15.0  3.0  5.0  5.0 5.0]
  [30.0 27.0 17.0 17.0 17.0 11.0  2.0  2.0  5.0 5.0]
  [30.0 26.0 12.0  8.0  9.0  9.0  0.0  1.0  5.0 5.0]
  [30.0 25.0  7.0  3.0  5.0  5.0  1.0  3.0  5.0 5.0]
  [30.0 24.0  2.0  2.0  4.0  4.0  3.0  5.0  8.0 5.0]
  [30.0 23.0  1.0  3.0  3.0  3.0  8.0  9.0 11.0 5.0]
  [30.0 22.0  1.0  3.0  7.0  9.0 12.0 13.0 15.0 5.0]
  [30.0 21.0  1.0  3.0  8.0  9.0 14.0 15.0 17.0 5.0]])

(def water-layer
  [[0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
   [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]])

(def source-points (register-math-syms #(filter-matrix-for-coords (p not= _0_) source-layer)))

(def use-points (register-math-syms #(filter-matrix-for-coords (p not= _0_) use-layer)))

(def ^:dynamic cell-height 100.0)

(def ^:dynamic cell-width  100.0)

(defn to-meters [[i j]] [(* i cell-height) (* j cell-width)])

(deftest distance-filtering-small-cells
  (binding [cell-height 100.0
            cell-width  100.0]
    (is (= (map #(take 2 %) (select-in-range-views use-points source-points to-meters))
           '(([0 1] [8 5])
             ([3 7] [8 5]))))))

(deftest distance-filtering-large-cells
  (binding [cell-height 15000.0
            cell-width  15000.0]
    (is (= (map #(take 2 %) (select-in-range-views use-points source-points to-meters))
           '(([3 7] [8 5]))))))

(deftest sight-line-1
  (is (= (find-line-between (first use-points) (first source-points))
         '([8 5] [7 5] [7 4] [6 4] [5 4] [5 3] [4 3] [3 3] [3 2] [2 2] [1 2] [1 1] [0 1]))))

(deftest sight-line-2
  (is (= (find-line-between (first use-points) (second source-points))
         '([8 5] [7 5] [7 6] [6 6] [5 6] [4 6] [4 7] [3 7]))))

(deftest slope-filtering-1
  (let [source-point (first source-points)
        use-point    (first use-points)
        sight-line   (rest (find-line-between use-point source-point))
        use-elev     (get-in elev-layer use-point)
        use-loc-in-m (to-meters use-point)]
    (is (= (filter-sight-line elev-layer sight-line use-point use-elev use-loc-in-m to-meters)
           [[[7 5] 3.0 100.0 -0.06999999999999999]
            [[7 4] 3.0 141.4213562373095 -0.06]
            [[6 4] 4.0 223.60679774997897 -0.04242640687119285]
            [[5 4] 5.0 316.22776601683796 -0.022360679774997897]
            [[4 3] 8.0 447.21359549995793 -0.012649110640673516]
            [[3 3] 17.0 538.5164807134504 -0.00223606797749979]
            [[2 2] 22.0 670.820393249937 0.014855627054164149]
            [[1 2] 27.0 761.5773105863908 0.019379255804998177]
            [[1 1] 29.0 806.2257748298549 0.02363515791475006]]))))

(deftest slope-filtering-2
  (let [source-point (second source-points)
        use-point    (first use-points)
        sight-line   (rest (find-line-between use-point source-point))
        use-elev     (get-in elev-layer use-point)
        use-loc-in-m (to-meters use-point)]
    (is (= (filter-sight-line elev-layer sight-line use-point use-elev use-loc-in-m to-meters)
           [[[7 5] 3.0 100.0 -0.06999999999999999]
            [[7 6] 8.0 141.4213562373095 -0.06]]))))

(deftest sight-line-splitting-1
  (let [source-point (first source-points)
        use-point    (first use-points)
        sight-line   (rest (find-line-between use-point source-point))]
    (is (= (split-sight-line elev-layer use-point sight-line)
           '[([7 5] [7 4])
             ([6 4] [5 4] [5 3] [4 3] [3 3] [3 2] [2 2] [1 2] [1 1] [0 1])]))))

(deftest sight-line-splitting-2
  (let [source-point (second source-points)
        use-point    (first use-points)
        sight-line   (rest (find-line-between use-point source-point))]
    (is (= (split-sight-line elev-layer use-point sight-line)
           '[([7 5])
             ([7 6] [6 6] [5 6] [4 6] [4 7] [3 7])]))))

;; (run-tests)
