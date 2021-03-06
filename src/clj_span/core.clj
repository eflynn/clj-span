;;; Copyright 2010-2013 Gary Johnson
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
;;; This namespace defines the run-span function, which is the main
;;; entry point into the SPAN system and may be called with a number
;;; of different options specifying the form of its results.

(ns clj-span.core
  (:use [clj-misc.utils            :only (p & with-message)]
        [clj-misc.matrix-ops       :only (map-matrix
                                          make-matrix
                                          resample-matrix
                                          matrix2seq
                                          matrix-max
                                          get-rows
                                          get-cols
                                          grids-align?
                                          is-matrix?
                                          filter-matrix-for-coords)]
        [clj-span.interface        :only [provide-results]]
        [clj-span.gui              :only [with-animation]]
        [clj-span.analyzer         :only [theoretical-source
                                          inaccessible-source
                                          possible-source
                                          blocked-source
                                          actual-source
                                          theoretical-sink
                                          inaccessible-sink
                                          actual-sink
                                          theoretical-use
                                          inaccessible-use
                                          possible-use
                                          blocked-use
                                          actual-use
                                          possible-flow
                                          blocked-flow
                                          actual-flow]]
        [clj-span.thinklab-monitor :only (monitor-info with-error-monitor with-interrupt-checking)])
  (:require (clj-misc [numbers :as nb] [varprop :as vp] [randvars :as rv]))
  (:import (org.integratedmodelling.thinklab.api.listeners IMonitor)))

(defmacro with-typed-math-syms-single-thread [value-type symbols & body]
  (let [prob-ns (gensym)]
    `(let [~prob-ns (case ~value-type
                      :numbers  'clj-misc.numbers
                      :varprop  'clj-misc.varprop
                      :randvars 'clj-misc.randvars)]
       (binding ~(vec (mapcat (fn [sym] [sym `(var-get (ns-resolve ~prob-ns '~sym))]) symbols))
         ~@body))))

(defmacro with-typed-math-syms [value-type symbols & body]
  (let [prob-ns (gensym)]
    `(let [~prob-ns (case ~value-type
                      :numbers  'clj-misc.numbers
                      :varprop  'clj-misc.varprop
                      :randvars 'clj-misc.randvars)]
       ~@(map (fn [sym] `(alter-var-root (var ~sym) (constantly (var-get (ns-resolve ~prob-ns '~sym)))))
              symbols)
       ~@body)))

(defstruct service-carrier
  :source-id      ; starting id of this flow path
  :route          ; byte array of directions from source-id to use-id or nil
  :possible-weight; amount of source-weight which reaches (and is used by) this use location disregarding sink-effects
  :actual-weight  ; amount of source-weight which reaches (and is used by) this use location including sink-effects
  :sink-effects   ; map of sink-ids to sink-effects on this flow path (decayed as necessary)
  :use-effects)   ; map of use-ids to rival use-effects on this flow path (decayed as necessary)

(defmulti distribute-flow!
  "Creates a network of interconnected locations, and starts a
   service-carrier propagating in every location whose source value is
   greater than 0.  These carriers propagate child carriers through
   the network which collect information about the routes traveled and
   the service weight transmitted along these routes.  Over the course
   of the simulation, this function will update the passed in
   cache-layer, possible-flow-layer, and actual-flow-layer.  Its
   return result is ignored."
  :flow-model)

(defmethod distribute-flow! :default
  [{:keys [flow-model]}]
  (throw (Exception. (str "distribute-flow! is undefined for flow-model " flow-model))))

;; Pull in model namespaces that use service-carrier and distribute-flow!
(require '(clj-span.models carbon
                           proximity
                           line-of-sight
                           surface-water
                           subsistence-fisheries
                           coastal-storm-protection
                           flood-water
                           sediment
                           flow-direction))

(defn generate-results-map
  "Return the simulation results as a map of layer names to closures."
  [{:keys [possible-use-layer value-type orig-rows orig-cols monitor]
    :as params}]
  (monitor-info monitor "registering SPAN simulation output analyzers")
  (with-error-monitor ^IMonitor monitor
    (let [rv-intensive-sampler (case value-type
                                 :numbers  nb/rv-intensive-sampler
                                 :varprop  vp/rv-intensive-sampler
                                 :randvars rv/rv-intensive-sampler)]
      (with-message
        "Generating result maps...\n"
        "Finished generating result maps."
        (apply array-map
               (mapcat (fn [[label val]]
                         (if val
                           (with-message (str "Adding " label " to computable outputs...") "done"
                             [label #(let [matrix (if (fn? val) (val params) val)]
                                       (if (is-matrix? matrix)
                                         (resample-matrix orig-rows orig-cols rv-intensive-sampler matrix)
                                         matrix))])))
                       (if possible-use-layer 
                         ;; our SPAN simulation pre-generated the layers -> return layers
                         (array-map
                          "Source - Theoretical"  (:theoretical-source-layer  params)
                          "Source - Inaccessible" (:inaccessible-source-layer params)
                          "Source - Possible"     (:possible-source-layer     params)
                          "Source - Blocked"      (:blocked-source-layer      params)
                          "Source - Actual"       (:actual-source-layer       params)
                          "Sink   - Theoretical"  (:theoretical-sink-layer    params)
                          "Sink   - Inaccessible" (:inaccessible-sink-layer   params)
                          "Sink   - Actual"       (:actual-sink-layer         params)
                          "Use    - Theoretical"  (:theoretical-use-layer     params)
                          "Use    - Inaccessible" (:inaccessible-use-layer    params)
                          "Use    - Possible"     (:possible-use-layer        params)
                          "Use    - Blocked"      (:blocked-use-layer         params)
                          "Use    - Actual"       (:actual-use-layer          params)
                          "Flow   - Possible"     (:possible-flow-layer       params)
                          "Flow   - Blocked"      (:blocked-flow-layer        params)
                          "Flow   - Actual"       (:actual-flow-layer         params))
                         ;; our SPAN simulation simply generated the cache-layer -> return fns
                         (array-map
                          "Source - Theoretical"  theoretical-source
                          "Source - Inaccessible" inaccessible-source
                          "Source - Possible"     possible-source
                          "Source - Blocked"      blocked-source
                          "Source - Actual"       actual-source
                          "Sink   - Theoretical"  theoretical-sink
                          "Sink   - Inaccessible" inaccessible-sink
                          "Sink   - Actual"       actual-sink
                          "Use    - Theoretical"  theoretical-use
                          "Use    - Inaccessible" inaccessible-use
                          "Use    - Possible"     possible-use
                          "Use    - Blocked"      blocked-use
                          "Use    - Actual"       actual-use
                          "Flow   - Possible"     possible-flow
                          "Flow   - Blocked"      blocked-flow
                          "Flow   - Actual"       actual-flow))))))))

(defn deref-result-layers
  [{:keys [possible-use-layer cache-layer possible-flow-layer actual-flow-layer monitor]
    :as params}]
  (monitor-info monitor "extracting SPAN simulation outputs")
  (with-error-monitor ^IMonitor monitor
    (if possible-use-layer
      ;; our SPAN simulation pre-generated the layers
      params
      ;; our SPAN simulation simply generated the cache-layer
      (assoc params
        :cache-layer         (map-matrix (& seq deref) cache-layer)
        :possible-flow-layer (map-matrix deref possible-flow-layer)
        :actual-flow-layer   (map-matrix deref actual-flow-layer)))))

(defn count-affected-users
  [{:keys [possible-use-layer cache-layer value-type]}]
  (let [_0_ (case value-type
              :numbers  nb/_0_
              :varprop  vp/_0_
              :randvars rv/_0_)]
    (if possible-use-layer
      (count (filter-matrix-for-coords (p not= _0_) possible-use-layer))
      (count (filter (& seq deref) (matrix2seq cache-layer))))))

(defn run-simulation
  [{:keys [flow-model source-layer source-points use-points animation?
           value-type possible-flow-layer actual-flow-layer monitor]
    :as params}]
  (monitor-info monitor (str "running SPAN " flow-model " flow model"))
  (with-error-monitor ^IMonitor monitor
    (with-interrupt-checking ^IMonitor monitor
      (with-message
        (str "\nRunning " flow-model " flow model...\n")
        "Simulation complete."
;;        #(str "Simulation complete.\nUsers affected: " (count-affected-users %))
        (if (and (seq source-points)
                 (seq use-points))
          (let [new-params (if animation?
                             (with-animation value-type (matrix-max source-layer) possible-flow-layer actual-flow-layer (distribute-flow! params))
                             (distribute-flow! params))]
            (or new-params params))
          (do (println "Either source or use is zero everywhere. Therefore, there can be no service flow.")
              params))))))

(defn create-simulation-inputs
  [{:keys [source-layer sink-layer use-layer rows cols value-type monitor]
    :as params}]
  (monitor-info monitor "creating SPAN simulation inputs")
  (with-error-monitor ^IMonitor monitor
    (with-message
      "\nCreating simulation inputs...\n"
      #(str "Source points: " (count (:source-points %)) "\n"
            "Sink points:   " (count (:sink-points   %)) "\n"
            "Use points:    " (count (:use-points    %)))
      (let [_0_ (case value-type
                  :numbers  nb/_0_
                  :varprop  vp/_0_
                  :randvars rv/_0_)]
        (assoc params
          :source-points       (filter-matrix-for-coords (p not= _0_) source-layer)
          :sink-points         (filter-matrix-for-coords (p not= _0_) sink-layer)
          :use-points          (filter-matrix-for-coords (p not= _0_) use-layer)
          :cache-layer         (make-matrix rows cols (fn [_] (ref ())))
          :possible-flow-layer (make-matrix rows cols (fn [_] (ref _0_)))
          :actual-flow-layer   (make-matrix rows cols (fn [_] (ref _0_))))))))

(defn zero-layer-below-threshold
  "Takes a two dimensional array of RVs and replaces all values which
   have a >50% likelihood of being below the threshold with _0_."
  [value-type threshold layer]
  (with-message
    (str "Zeroing layer below " threshold "...\n")
    #(format "  Distinct Layer Values: [Pre] %d [Post] %d"
             (count (distinct (matrix2seq layer)))
             (count (distinct (matrix2seq %))))
    (let [[_< _0_] (case value-type
                     :numbers  [nb/_< nb/_0_]
                     :varprop  [vp/_< vp/_0_]
                     :randvars [rv/_< rv/_0_])]
      (map-matrix #(if (_< % threshold) _0_ %) layer))))

(defn resample-and-zero
  [value-type scaled-rows scaled-cols layer threshold]
  (let [[rv-intensive-sampler _0_] (case value-type
                                     :numbers  [nb/rv-intensive-sampler nb/_0_]
                                     :varprop  [vp/rv-intensive-sampler vp/_0_]
                                     :randvars [rv/rv-intensive-sampler rv/_0_])]
    (cond (nil? layer)     (make-matrix scaled-rows scaled-cols (constantly _0_))
          (nil? threshold) (resample-matrix scaled-rows scaled-cols rv-intensive-sampler layer)
          :otherwise       (zero-layer-below-threshold value-type
                                                       threshold
                                                       (resample-matrix scaled-rows scaled-cols rv-intensive-sampler layer)))))

(defn preprocess-data-layers
  "Preprocess data layers (downsampling and zeroing below their thresholds)."
  [{:keys [source-layer sink-layer use-layer flow-layers
           source-threshold sink-threshold use-threshold
           cell-width cell-height downscaling-factor value-type monitor]
    :as params}]
  (monitor-info monitor "preprocessing SPAN input layers")
  (println "Preprocessing the input data layers.")
  (with-error-monitor ^IMonitor monitor
    (let [[rows cols] ((juxt get-rows get-cols) source-layer)
          scaled-rows (int (quot rows downscaling-factor))
          scaled-cols (int (quot cols downscaling-factor))
          r-and-z     (p resample-and-zero value-type scaled-rows scaled-cols)]
      (assoc params
        :orig-rows    rows
        :orig-cols    cols
        :rows         scaled-rows
        :cols         scaled-cols
        :cell-width   (* cell-width  (/ cols scaled-cols))
        :cell-height  (* cell-height (/ rows scaled-rows))
        :source-layer (r-and-z source-layer source-threshold)
        :sink-layer   (r-and-z sink-layer   sink-threshold)
        :use-layer    (r-and-z use-layer    use-threshold)
        :flow-layers  (into {} (for [[name layer] flow-layers] [name (r-and-z layer nil)]))))))

(def double>0?         #(and (float?   %) (pos? %)))
(def nil-or-double>=0? #(or  (nil?     %) (and (float? %) (>= % 0))))
(def integer>=1?       #(and (integer? %) (>= % 1)))
(def number>=1?        #(and (number?  %) (>= % 1)))
(def nil-or-matrix?    #(or  (nil?     %) (is-matrix? %)))

(defn verify-params-or-throw
  [{:keys [source-layer sink-layer use-layer flow-layers
           source-threshold sink-threshold use-threshold trans-threshold
           cell-width cell-height rv-max-states downscaling-factor
           source-type sink-type use-type benefit-type
           value-type flow-model animation? result-type monitor]
    :as params}]
  (with-error-monitor ^IMonitor monitor
    (assert (every? is-matrix? [source-layer use-layer]))
    (assert (every? nil-or-matrix? (cons sink-layer (vals flow-layers))))
    (assert (apply grids-align? (remove nil? (list* source-layer sink-layer use-layer (vals flow-layers)))))
    (assert (every? nil-or-double>=0? [source-threshold sink-threshold use-threshold]))
    (assert (every? double>0? [trans-threshold cell-width cell-height]))
    (assert (integer>=1? rv-max-states))
    (assert (number>=1? downscaling-factor))
    (assert (every? #{:finite :infinite} [source-type use-type]))
    (assert (contains? #{:finite :infinite nil} sink-type))
    (assert (contains? #{:rival :non-rival} benefit-type))
    (assert (contains? #{:randvars :varprop :numbers} value-type))
    (assert (contains? #{"LineOfSight"
                         "Proximity"
                         "CO2Removed"
                         "FloodWaterMovement"
                         "SurfaceWaterMovement"
                         "SedimentTransport"
                         "CoastalStormMovement"
                         "SubsistenceFishAccessibility"
                         "FlowDirection"}
                       flow-model))
    (assert (contains? #{:cli-menu :closure-map :java-hashmap} result-type))
    (assert (contains? #{true false nil} animation?))
    params))

(defn set-global-vars!
  [{:keys [value-type rv-max-states]}]
  (if (and (= value-type :randvars)
           (integer>=1? rv-max-states))
    (rv/reset-rv-max-states! rv-max-states)))

(defn run-span
  "Run a flow model and return the results."
  [{:keys [result-type value-type source-layer
           sink-layer use-layer flow-layers]
    :as params}]
  (set-global-vars! params)
  (let [simulation-results (some->> params
                                    verify-params-or-throw
                                    preprocess-data-layers
                                    create-simulation-inputs
                                    run-simulation
                                    deref-result-layers
                                    generate-results-map
                                    (provide-results result-type value-type source-layer sink-layer use-layer flow-layers))]
    simulation-results))
