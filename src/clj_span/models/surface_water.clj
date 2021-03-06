;;; Copyright 2010-2013 Gary Johnson & Ioannis Athanasiadis
;;;
;;; This file is part of clj-span.
;;;
;;; clj-span is free software: you can redistribute it and/or modify
;;; it under the terms of the GNU General Public License as published
;;; by the Free Software Foundation, either version 3 of the License,
;;; or (at your option) any later version.
;;;
;;; clj-span is distributed in the hope that it will be useful, but
;;; WITHOUT ANY WARRANTY; without even the implied warranty of
;;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;;; General Public License for more details.
;;;
;;; You should have received a copy of the GNU General Public License
;;; along with clj-span.  If not, see <http://www.gnu.org/licenses/>.
;;;
;;;-------------------------------------------------------------------
;;;
;;; This namespace defines the surface-water model.
;;;

(ns clj-span.models.surface-water
  (:use [clj-misc.utils      :only [seq2map mapmap iterate-while-seq with-message
                                    memoize-by-first-arg angular-distance p &
                                    with-progress-bar-cool depth-first-graph-traversal
                                    depth-first-graph-ordering depth-first-graph-paths
                                    manhattan-distance-2 dofold]]
        [clj-misc.matrix-ops :only [get-neighbors on-bounds? subtract-ids find-nearest
                                    filter-matrix-for-coords make-matrix map-matrix
                                    get-neighbors-clockwise group-by-adjacency]]
        [clj-span.thinklab-monitor :only (monitor-info with-interrupt-checking)])
  (:require [clojure.core.reducers :as r])
  (:import (org.integratedmodelling.thinklab.api.listeners IMonitor)))

(refer 'clj-span.core :only '(distribute-flow! service-carrier with-typed-math-syms))

(def ^:dynamic _0_)
(def ^:dynamic _+_)
(def ^:dynamic _-_)
(def ^:dynamic *_)
(def ^:dynamic _d)
(def ^:dynamic rv-fn)
(def ^:dynamic _min_)
(def ^:dynamic _<_)
(def ^:dynamic _>_)
(def ^:dynamic _>)
(def ^:dynamic _*_)
(def ^:dynamic _d_)

;; FIXME: Find a way to parallelize the generation of these 9 matrices.
(defn create-output-layers
  [{:keys [source-layer sink-layer use-layer
           actual-sink-layer possible-use-layer actual-use-layer
           possible-flow-layer actual-flow-layer monitor]
    :as params}]
  (monitor-info monitor "creating SPAN output layers")
  (with-interrupt-checking ^IMonitor monitor
    (with-message "Creating the SPAN output layers..." "done"
      (let [_-_ (fn [A B] (rv-fn '(fn [a b] (max (- a b) 0.0)) A B))
            actual-sink-layer   (map-matrix deref actual-sink-layer)
            possible-use-layer  (map-matrix deref possible-use-layer)
            actual-use-layer    (map-matrix deref actual-use-layer)
            possible-flow-layer (map-matrix deref possible-flow-layer)
            actual-flow-layer   (map-matrix deref actual-flow-layer)]
        (assoc params
          :theoretical-source-layer  source-layer
          :inaccessible-source-layer nil ;; FIXME: how can I compute this?
          :possible-source-layer     nil ;; FIXME: how can I compute this?
          :blocked-source-layer      nil ;; FIXME: how can I compute this?
          :actual-source-layer       nil ;; FIXME: how can I compute this?
          :theoretical-sink-layer    sink-layer
          :inaccessible-sink-layer   (map-matrix _-_ sink-layer actual-sink-layer)
          :actual-sink-layer         actual-sink-layer
          :theoretical-use-layer     use-layer
          :inaccessible-use-layer    (map-matrix _-_ use-layer possible-use-layer)
          :possible-use-layer        possible-use-layer
          :blocked-use-layer         (map-matrix _-_ possible-use-layer actual-use-layer)
          :actual-use-layer          actual-use-layer
          :possible-flow-layer       possible-flow-layer
          :blocked-flow-layer        (map-matrix _-_ possible-flow-layer actual-flow-layer)
          :actual-flow-layer         actual-flow-layer)))))

(defn distribute-water-locally!
  [{:keys [source-layer sink-layer use-layer intake-layer
           actual-sink-layer possible-use-layer actual-use-layer
           possible-flow-layer actual-flow-layer
           service-network stream-intakes _-_]}
   node]
  (dosync
   (let [theoretical-source       (get-in source-layer node)
         theoretical-sink         (get-in sink-layer node)
         theoretical-use          (get-in intake-layer node)
         possible-upstream-inflow @(get-in possible-flow-layer node) ;; inflow without rival use
         actual-upstream-inflow   @(get-in actual-flow-layer node) ;; inflow with rival use
         possible-inflow          (_+_ theoretical-source possible-upstream-inflow)
         actual-inflow            (_+_ theoretical-source actual-upstream-inflow)
         possible-stock           (_-_ possible-inflow theoretical-sink) ;; amount left after sinks
         actual-stock             (_-_ actual-inflow theoretical-sink) ;; amount left after sinks
         ;; possible-sink            (_min_ possible-inflow theoretical-sink) ;; NOTE: this is a new result!
         actual-sink              (_min_ actual-inflow theoretical-sink)
         possible-use             (_min_ possible-stock theoretical-use) ;; user capture without rival use
         actual-use               (_min_ actual-stock theoretical-use) ;; user capture with rival use
         possible-outflow         possible-stock
         actual-outflow           (_-_ actual-stock actual-use)]
     ;; at this location
     (ref-set (get-in possible-flow-layer node) possible-outflow)
     (ref-set (get-in actual-flow-layer   node) actual-outflow)
     (ref-set (get-in actual-sink-layer   node) actual-sink)
     ;; at our next downhill/downstream neighbor
     (when-let [next-step-node (get-in service-network node)]
       (commute (get-in possible-flow-layer next-step-node) _+_ possible-outflow)
       (commute (get-in actual-flow-layer   next-step-node) _+_ actual-outflow))
     ;; at the use locations that draw from this intake point
     ;; note: we also store actual-use in the actual-sink-layer since we're treating
     ;;       user capture as a sink for rival competition scenarios
     (doseq [user (stream-intakes node)]
       (let [use-percentage        (_d_ (get-in use-layer user) theoretical-use)
             relative-possible-use (_*_ possible-use use-percentage)
             relative-actual-use   (_*_ actual-use use-percentage)]
         (ref-set (get-in possible-use-layer user) relative-possible-use)
         (ref-set (get-in actual-use-layer   user) relative-actual-use))))))

(defn propagate-runoff!
  [{:keys [serviceshed-bands stream-intakes use-layer rows cols monitor]
    :as params}]
  (let [num-bands (count serviceshed-bands)]
    (monitor-info monitor (str "propagating runoff through " num-bands " serviceshed band" (if (> num-bands 1) "s")))
    (with-interrupt-checking ^IMonitor monitor
      (with-message (str "Propagating runoff through " num-bands " serviceshed band" (if (> num-bands 1) "s") "...") "done"
        (let [intake-layer  (make-matrix rows cols
                                         (fn [id] (if-let [users (stream-intakes id)]
                                                    (reduce _+_ (map #(get-in use-layer %) users))
                                                    _0_)))
              _-_           (fn [A B] (rv-fn '(fn [a b] (max (- a b) 0.0)) A B))
              work-fn       (p distribute-water-locally! (assoc params :intake-layer intake-layer :_-_ _-_))
              num-threads   (* (.availableProcessors (Runtime/getRuntime)) 2)]
          (doseq [band-number (range (apply max (keys serviceshed-bands)) -1 -1)]
            (let [nodes         (serviceshed-bands band-number)
                  workload-size (max 1 (quot (count nodes) num-threads))]
              (dofold workload-size work-fn nodes))))))
    params))

(defn upstream-parents
  [rows cols stream-network id]
  (filterv #(= id (get-in stream-network %))
           (get-neighbors rows cols id)))

(defn find-most-downstream-intakes
  [stream-intakes service-network]
  (keys stream-intakes)) ;; Just return them all, DAMNIT!
  ;; (filter (fn [intake] (let [downstream-child (get-in service-network intake)]
  ;;                        (nil? (get-in service-network downstream-child))))
  ;;         (keys stream-intakes)))

(defn order-upstream-nodes
  [{:keys [stream-intakes service-network rows cols monitor] :as params}]
  (monitor-info monitor "ordering serviceshed cells by outlet distance")
  (with-interrupt-checking ^IMonitor monitor
    (with-message "Ordering the serviceshed cells by outlet distance..." "done"
      (assoc params
        :serviceshed-bands
        (let [upstream-parents (partial upstream-parents rows cols service-network)
              intake-distances (apply merge-with max
                                      (for [intake-point (find-most-downstream-intakes stream-intakes service-network)]
                                        (depth-first-graph-ordering intake-point upstream-parents)))]
          (group-by intake-distances (keys intake-distances)))))))

(defn filter-upstream-nodes
  [{:keys [stream-intakes stream-network rows cols monitor] :as params}]
  (monitor-info monitor "filtering out the serviceshed")
  (with-interrupt-checking ^IMonitor monitor
    (with-message "Filtering out the serviceshed..." "done"
      (assoc params
        :service-network
        (let [upstream-parents (partial upstream-parents rows cols stream-network)
              upstream-nodes   (reduce (fn [upstream-node-list intake-point]
                                         (depth-first-graph-traversal intake-point upstream-parents upstream-node-list))
                                       #{}
                                       (keys stream-intakes))]
          (make-matrix rows cols (fn [id] (if (contains? upstream-nodes id)
                                            (get-in stream-network id)))))))))

(defn find-lowest
  [elev-layer ids]
  (reduce (fn [lowest-id id]
            (if (_<_ (get-in elev-layer id)
                     (get-in elev-layer lowest-id))
              id
              lowest-id))
          ids))

(defn find-least-slope
  [elev-layer ids initial-id]
  (let [initial-elev (get-in elev-layer initial-id)]
    (reduce (fn [least-slope-id id]
              (let [least-slope (let [s1 (_-_ (get-in elev-layer least-slope-id) initial-elev)] (if (_<_ s1 _0_) (*_ -1.0 s1) s1))
                    new-slope   (let [s2 (_-_ (get-in elev-layer id)             initial-elev)] (if (_<_ s2 _0_) (*_ -1.0 s2) s2))]
                (if (_<_ new-slope least-slope)
                  id
                  least-slope-id)))
            ids)))

(defn find-closest
  [neighbor-ids initial-id]
  (reduce (fn [closest-id id]
            (if (< (manhattan-distance-2 initial-id id)
                   (manhattan-distance-2 initial-id closest-id))
              id
              closest-id))
          neighbor-ids))

(defn lowest-neighbors-overland
  [id in-stream? elev-layer rows cols]
  (if-not (on-bounds? rows cols id)
    (let [neighbors (get-neighbors rows cols id)]
      (if-let [water-neighbors (seq (filterv in-stream? neighbors))]
        (find-lowest elev-layer water-neighbors)
        (find-lowest elev-layer neighbors)))))
        ;; (find-lowest elev-layer (cons id neighbors))))))

(defn select-stream-path-dirs
  [elev-layer stream-points-in-path]
  (let [num-stream-points-in-path (count stream-points-in-path)
        [left-path right-path]    (split-at (quot num-stream-points-in-path 2) stream-points-in-path)
        left-weight               (reduce _+_ (r/map #(get-in elev-layer %) left-path))
        right-weight              (reduce _+_ (r/map #(get-in elev-layer %)
                                                     (if (odd? num-stream-points-in-path) (rest right-path) right-path)))]
    (persistent!
     (reduce (fn [acc [curr next]] (assoc! acc curr next))
             (transient {})
             (partition 2 1 (if (_>_ left-weight right-weight)
                              stream-points-in-path
                              (rseq stream-points-in-path)))))))

(defn stream-segment-type
  [water-neighbors]
  (let [neighbor-groups (group-by-adjacency water-neighbors)]
    (case (count neighbor-groups)
      0 :lake
      1 :edge
      2 :link
      :junction)))

(defn find-next-in-stream-step
  [rows cols elev-layer in-stream? [id unexplored-points]]
  (if-not (on-bounds? rows cols id)
    (let [water-neighbors (seq (filter in-stream? (get-neighbors-clockwise rows cols id)))]
      (if (contains? #{:link :edge} (stream-segment-type water-neighbors))
        (if-let [unexplored-neighbors (seq (filter unexplored-points water-neighbors))]
          ;; (let [next-step (find-lowest elev-layer unexplored-neighbors)]
          ;; (let [next-step (find-least-slope elev-layer unexplored-neighbors id)]
          (let [next-step (find-closest unexplored-neighbors id)]
            [next-step (disj unexplored-points next-step)]))))))

(defn find-bounded-stream-segment
  [id stream-points explore-stream]
  (let [left-results  (take-while (& not nil?) (rest (iterate explore-stream [id (disj stream-points id)])))
        right-results (take-while (& not nil?) (rest (iterate explore-stream [id (second (last left-results))])))]
    (vec (concat (mapv first (reverse left-results)) [id] (mapv first right-results)))))

(defn assoc-longest!
  [transient-map & keyvals]
  (reduce (fn [tm [k v]] (assoc! tm k (if-let [curr-val (tm k)] (max-key count curr-val v) v)))
          transient-map
          (partition 2 keyvals)))

(defn assoc-conj!
  [transient-map & keyvals]
  (reduce (fn [tm [k v]] (assoc! tm k (if-let [curr-val (tm k)] (conj curr-val v) [v])))
          transient-map
          (partition 2 keyvals)))

(defn collect-stream-segment-info
  [in-stream? elev-layer rows cols]
  (let [explore-stream (p find-next-in-stream-step rows cols elev-layer in-stream?)
        stream-links   (set (filter #(contains? #{:link :edge}
                                                (stream-segment-type
                                                 (filter in-stream? (get-neighbors-clockwise rows cols %))))
                                    in-stream?))]
    (loop [unexplored-links stream-links
           ends-to-segments (transient {})
           ends-to-ends     (transient {})]
      (if (empty? unexplored-links)
        [(persistent! ends-to-segments)
         (persistent! ends-to-ends)]
        (let [id             (first unexplored-links)
              stream-segment (find-bounded-stream-segment id in-stream? explore-stream)
              segment-start  (first stream-segment)
              segment-end    (peek stream-segment)]
          (if (= 1 (count stream-segment))
            ;; picked a link or edge on the map bounds, disregard and continue
            (recur (disj unexplored-links id)
                   ends-to-segments
                   ends-to-ends)
            ;; detected a proper stream segment, terminated by edges, junctions, and/or links on the map bounds
            (recur (reduce disj unexplored-links stream-segment)
                   (assoc-longest! ends-to-segments
                                   [segment-start segment-end] stream-segment
                                   [segment-end segment-start] (rseq stream-segment))
                   (assoc-conj! ends-to-ends
                                segment-start segment-end
                                segment-end segment-start))))))))

(defn expand-stream-path
  [ends-to-segments stream-path]
  (into [(first stream-path)]
        (mapcat (& rest ends-to-segments vec)
                (partition 2 1 stream-path))))

(defn filter-unique-paths
  [paths]
  (vals
   (persistent!
    (reduce (fn [unique-paths next-path]
              (let [path-ids (set next-path)]
                (if (unique-paths path-ids)
                  unique-paths
                  (assoc! unique-paths path-ids next-path))))
            (transient {})
            paths))))

(defn find-all-unique-stream-paths
  [ends-to-segments ends-to-ends]
  (let [unexplored-ends  (keys ends-to-ends) ;; more samples is apparently better
  ;; (let [unexplored-ends  (filter #(= 1 (count (ends-to-ends %))) (keys ends-to-ends))
        all-stream-paths (mapcat #(depth-first-graph-paths % ends-to-ends) unexplored-ends)
        unique-paths     (filter-unique-paths all-stream-paths)]
    (map #(expand-stream-path ends-to-segments %) unique-paths)))

(defn determine-river-flow-directions
  [in-stream? elev-layer rows cols]
  (let [[ends-to-segments ends-to-ends] (collect-stream-segment-info in-stream? elev-layer rows cols)
        all-stream-paths                (find-all-unique-stream-paths ends-to-segments ends-to-ends)
        stream-paths-by-length          (sort-by count > all-stream-paths)]
    (apply merge (map #(select-stream-path-dirs elev-layer %) stream-paths-by-length))))

(defn build-stream-network
  [{:keys [in-stream? elev-layer rows cols monitor] :as params}]
  (monitor-info monitor "building hydrologic network")
  (with-interrupt-checking ^IMonitor monitor
    (with-message "Building hydrologic network..." "done"
      (let [in-stream-dirs (determine-river-flow-directions in-stream? elev-layer rows cols)]
        (assoc params
          :stream-network
          (make-matrix rows cols
                       (fn [id]
                         (if (in-stream? id)
                           (in-stream-dirs id)
                           (lowest-neighbors-overland id in-stream? elev-layer rows cols)))))))))

(defn make-buckets
  "Stores maps from {ids -> mm3-ref} for sink-caps, possible-use-caps, and actual-use-caps in params."
  [{:keys [rows cols] :as params}]
  (assoc params
    :actual-sink-layer  (make-matrix rows cols (fn [_] (ref _0_)))
    :possible-use-layer (make-matrix rows cols (fn [_] (ref _0_)))
    :actual-use-layer   (make-matrix rows cols (fn [_] (ref _0_)))))

(defn find-nearest-stream-points
  [safe-stream-points rows cols use-points]
  (with-message
    "Finding nearest stream points to all users...\n"
    #(str "\nDone. Found " (count %) " intake points.")
    (let [in-stream-users (filter safe-stream-points use-points)
          claimed-intakes (zipmap in-stream-users (map vector in-stream-users))]
      (println "Detected" (count in-stream-users) "in-stream users.\nContinuing with out-of-stream users...")
      (apply merge-with #(reduce conj %1 %2)
             claimed-intakes
             (with-progress-bar-cool
               :keep
               (- (count use-points) (count in-stream-users))
               (pmap #(if-let [stream-id (find-nearest safe-stream-points rows cols %)] {stream-id [%]})
                     (remove safe-stream-points use-points)))))))

(defn link-streams-to-users
  "Stores a map of {stream-ids -> nearest-use-ids} under (params :stream-intakes)."
  [{:keys [in-stream? rows cols use-points monitor] :as params}]
  (monitor-info monitor "associating users with stream points")
  (with-interrupt-checking ^IMonitor monitor
    (let [safe-stream-points (set (remove (p on-bounds? rows cols) in-stream?))]
      (assoc params
        :stream-intakes (find-nearest-stream-points safe-stream-points rows cols use-points)))))

(defn create-in-stream-test
  "Stores a set of all in-stream ids under (params :in-stream?)."
  [{:keys [flow-layers] :as params}]
  (assoc params
    :in-stream? (set (filter-matrix-for-coords #(not= _0_ %) (flow-layers "River")))))

(defmethod distribute-flow! "SurfaceWaterMovement"
  [{:keys [flow-layers value-type monitor] :as params}]
  (let [results (with-typed-math-syms value-type [_0_ _+_ _-_ *_ _d rv-fn _min_ _<_ _>_ _> _*_ _d_]
                  (-> (assoc params :elev-layer (flow-layers "Altitude"))
                      create-in-stream-test
                      link-streams-to-users
                      make-buckets
                      build-stream-network
                      filter-upstream-nodes
                      order-upstream-nodes
                      propagate-runoff!
                      create-output-layers))]
    (monitor-info monitor (str "completed SurfaceWaterMovement simulation successfully"))
    results))
