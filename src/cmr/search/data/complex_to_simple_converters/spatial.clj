(ns cmr.search.data.complex-to-simple-converters.spatial
  "Contains converters for spatial condition into the simpler executable conditions"
  (:require [cmr.search.data.complex-to-simple :as c2s]
            [cmr.search.data.elastic-search-index :as idx]
            [cmr.search.models.query :as qm]
            [cmr.spatial.mbr :as mbr]
            [cmr.spatial.serialize :as srl]
            [cmr.spatial.derived :as d]
            [cmr.spatial.relations :as sr]
            [clojure.string :as str])
  (:import gov.nasa.echo_orbits.EchoOrbitsRubyBootstrap))

(def orbits
  "Java wrapper for echo-orbits ruby library."
  (EchoOrbitsRubyBootstrap/bootstrapEchoOrbits))

(defn- shape->script-cond
  [shape]
  (let [ords-info-map (-> (srl/shapes->ords-info-map [shape])
                          (update-in [:ords-info] #(str/join "," %))
                          (update-in [:ords] #(str/join "," %)))]
    (qm/map->ScriptCondition {:name "spatial"
                              :params ords-info-map})))

(defn- br->cond
  [prefix {:keys [west north east south] :as br}]
  (letfn [(add-prefix [field]
                      (->> field name (str prefix "-") keyword))
          (range-cond [field from to]
                      (qm/numeric-range-condition (add-prefix field) from to))
          (bool-cond [field value]
                     (qm/->BooleanCondition (add-prefix field) value))]
    (if (mbr/crosses-antimeridian? br)
      (let [c (range-cond :west -180 west)
            d (range-cond :east -180 east)
            e (range-cond :east east 180)
            f (range-cond :west west 180)
            am-conds (qm/and-conds [(qm/or-conds [c f])
                                    (qm/or-conds [d e])
                                    (bool-cond :crosses-antimeridian true)])
            lon-cond (qm/or-conds [(range-cond :west -180 east)
                                   (range-cond :east west 180)
                                   am-conds])]
        (qm/and-conds [lon-cond
                       (range-cond :north south 90)
                       (range-cond :south -90 north)]))

      (let [north-cond (range-cond :north south 90.0)
            south-cond (range-cond :south -90.0 north)
            west-cond (range-cond :west -180 east)
            east-cond (range-cond :east west 180)
            am-conds (qm/and-conds [(bool-cond :crosses-antimeridian true)
                                    (qm/or-conds [west-cond east-cond])])
            non-am-conds (qm/and-conds [west-cond east-cond])]
        (qm/and-conds [north-cond
                       south-cond
                       (qm/or-conds [am-conds non-am-conds])])))))


(def orbit-param-fields
  ["concept-id"
   "swath-width"
   "period"
   "inclination-angle"
   "number-of-orbits"
   "start-circular-latitude"])

(defn- collection-ids-present?
  [orbit-collection-ids]
  (and (not= :all orbit-collection-ids)
       (seq orbit-collection-ids)))

(defn- fix-vals
  "Helper function to convert a map with sequences for values into a map with single values."
  [map-value]
  (reduce (fn [m key]
            (update-in m [key] #(first %)))
          map-value
          (keys map-value)))

(defn- orbits-for-context
  "Get the orbit parameters for all the relevant collections."
  [context]
  ;; Construct a query for concept-ids and orbit parameters of all collections that have them
  ;; Execute the query to get the data needed to do orbitial back tracking
  (let [{:keys [orbit-collection-ids]} context
        orbit-params-cond (qm/->ExistCondition :swath-width)
        orbit-params-cond (if (collection-ids-present? orbit-collection-ids)
                            (qm/and-conds orbit-params-cond
                                          (qm/or-conds (map (fn [concept-id]
                                                              (qm/string-condition
                                                                :concept-id
                                                                concept-id
                                                                true
                                                                false))
                                                            orbit-collection-ids)))
                            orbit-params-cond)
        orbit-params-query (qm/query {:concept-type :collection
                                      :condition orbit-params-cond
                                      :skip-acls? true
                                      :page-size :unlimited
                                      :result-format :query-specified
                                      :fields orbit-param-fields})
        results (get-in (idx/execute-query context orbit-params-query) [:hits :hits])]
    (map #(fix-vals (:fields %)) results)))


(defn orbit-crossings
  "Compute the orbit crossing ranges (max and min longitude) used to create the crossing
  conditions for orbital crossing searches. Returns a vector of vectors"
  [stored-ords orbit-params]
  ;; Use the orbit parameters to perform oribtial back tracking to longitude ranges to be used
  ;; in the search

  ;; Construct an OR group with the ranges, looking for overlaps with the :orbit-start-clat
  ;; and :orbit-end-clat range
  (let [
        type (name (get-in stored-ords [0 :type]))
        coords (double-array (map srl/stored->ordinate
                                  (get-in stored-ords [0 :ords])))
        crossings (let [{:keys [swath-width
                                period
                                inclination-angle
                                number-of-orbits
                                start-circular-latitude]} orbit-params
                        asc-crossing (.areaCrossingRange
                                       orbits
                                       type
                                       coords
                                       true
                                       inclination-angle
                                       period
                                       swath-width
                                       start-circular-latitude
                                       number-of-orbits)
                        desc-crossing (.areaCrossingRange
                                        orbits
                                        type
                                        coords
                                        false
                                        inclination-angle
                                        period
                                        swath-width
                                        start-circular-latitude
                                        number-of-orbits)]
                    (into (into [] asc-crossing) desc-crossing))]

    crossings))

(defn orbital-condition
  "Create a condition that will use orbit parameters and orbital back tracking to find matches
  to a spatial search."
  [shape context]
  (let [mbr (sr/mbr shape)
        lat-ranges (.denormalizeLatitudeRange orbits (:south mbr) (:north mbr))
        lat-conds (qm/or-conds
                    (map (fn [range]
                           (qm/numeric-overlap-condition
                             :orbit-start-clat
                             :orbit-end-clat
                             (first range)
                             (last range)))
                         lat-ranges))
        orbit-params (orbits-for-context context)
        stored-ords (srl/shape->stored-ords shape)]
    (qm/or-conds
      (map (fn [params]
             (qm/and-conds
               [(qm/string-condition :collection-concept-id (:concept-id params))
                (let [crossings (orbit-crossings stored-ords params)]
                  (qm/or-conds
                    [lat-conds
                     (qm/or-conds
                       (map (fn [crossing-range]
                              (qm/numeric-range-condition
                                :orbit-asc-crossing-lon
                                (first crossing-range)
                                (last crossing-range)))
                            crossings))]))]))

           orbit-params))))

(extend-protocol c2s/ComplexQueryToSimple
  cmr.search.models.query.SpatialCondition
  (c2s/reduce-query
    [{:keys [shape]} context]
    (let [shape (d/calculate-derived shape)
          orbital-cond (orbital-condition shape context)
          _ (println "ORBITAL CONDS.......")
          _ (println orbital-cond)
          mbr-cond (br->cond "mbr" (srl/shape->mbr shape))
          lr-cond (br->cond "lr" (srl/shape->lr shape))
          spatial-script (shape->script-cond shape)]
      ;(qm/and-conds [mbr-cond (qm/or-conds [lr-cond spatial-script])]))))
      (qm/or-conds [(qm/and-conds [mbr-cond (qm/or-conds [lr-cond spatial-script])])
                    orbital-cond]))))