(ns cmr.search.results-handlers.metadata-results-handler
  "Handles search results with metadata including ECHO10 and DIF formats."
  (:require [cmr.search.data.elastic-results-to-query-results :as elastic-results]
            [cmr.search.data.elastic-search-index :as elastic-search-index]
            [cmr.search.services.query-service :as qs]
            [cmr.search.services.query-execution.granule-counts-results-feature :as gcrf]
            [cmr.search.services.query-execution.facets-results-feature :as frf]
            [cmr.search.models.results :as results]
            [cmr.search.services.transformer :as t]
            [clojure.data.xml :as x]
            [cmr.common.xml :as cx]
            [clojure.string :as str]
            [cmr.umm.dif.collection :as dif-c]
            [cmr.umm.iso-mends.collection :as iso-mends-c]
            [cmr.common.util :as u]
            [cmr.common.log :refer (debug info warn error)]))

(defmethod elastic-search-index/concept-type+result-format->fields [:granule :echo10]
  [concept-type query]
  [])

(defmethod elastic-search-index/concept-type+result-format->fields [:collection :echo10]
  [concept-type query]
  [])

(defmethod elastic-search-index/concept-type+result-format->fields [:collection :dif]
  [concept-type query]
  [])

(defmethod elastic-search-index/concept-type+result-format->fields [:collection :iso-mends]
  [concept-type query]
  [])

(def concept-type->name-key
  "A map of the concept type to the key to use to extract the reference name field."
  {:collection :entry-title
   :granule :granule-ur})

(defn- elastic-results->query-metadata-results
  "A helper for converting elastic results into metadata results."
  [context query elastic-results]
  (let [hits (get-in elastic-results [:hits :total])
        tuples (map #(vector (:_id %) (:_version %)) (get-in elastic-results [:hits :hits]))
        [req-time tresults] (u/time-execution
                              (t/get-formatted-concept-revisions context tuples (:result-format query) false))
        items (map #(select-keys % [:concept-id :revision-id :collection-concept-id :metadata]) tresults)]
    (debug "Transformer metadata request time was" req-time "ms.")
    (results/map->Results {:hits hits :items items :result-format (:result-format query)})))

(defmethod elastic-results/elastic-results->query-results :echo10
  [context query elastic-results]
  (elastic-results->query-metadata-results context query elastic-results))

(defmethod elastic-results/elastic-results->query-results :dif
  [context query elastic-results]
  (elastic-results->query-metadata-results context query elastic-results))

(defmethod elastic-results/elastic-results->query-results :iso-mends
  [context query elastic-results]
  (elastic-results->query-metadata-results context query elastic-results))

(defmethod gcrf/query-results->concept-ids :echo10
  [results]
  (->> results
       :items
       (map :concept-id)))

(defmethod gcrf/query-results->concept-ids :dif
  [results]
  (->> results
       :items
       (map :concept-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Search results handling

(defmulti metadata-item->result-string
  "Converts a search result + metadata into a string containing a single result for the metadata format."
  (fn [concept-type results metadata-item]
    concept-type))

(defmethod metadata-item->result-string :granule
  [concept-type results metadata-item]
  (let [{:keys [concept-id collection-concept-id revision-id metadata]} metadata-item]
    ["<result concept-id=\""
     concept-id
     "\" collection-concept-id=\""
     collection-concept-id
     "\" revision-id=\""
     revision-id
     "\">"
     (cx/remove-xml-processing-instructions metadata)
     "</result>"]))

(defmethod metadata-item->result-string :collection
  [concept-type results metadata-item]
  (let [{:keys [has-granules-map granule-counts-map]} results
        {:keys [concept-id revision-id metadata]} metadata-item
        attribs (concat [[:concept-id concept-id]
                         [:revision-id revision-id]]
                        (when has-granules-map
                          [[:has-granules (get has-granules-map concept-id false)]])
                        (when granule-counts-map
                          [[:granule-count (get granule-counts-map concept-id 0)]]))
        attrib-strs (for [[k v] attribs]
                      (str " " (name k) "=\"" v "\""))]
    (concat ["<result"]
            attrib-strs
            [">" (cx/remove-xml-processing-instructions metadata) "</result>"])))

(defn- facets->xml-string
  "Converts facets into an XML string."
  [facets]
  (if facets
    (cx/remove-xml-processing-instructions
      (x/emit-str (frf/facets->xml-element facets)))
    ""))

(defn search-results->metadata-response
  [context query results]
  (let [{:keys [hits took items facets]} results
        {:keys [result-format pretty? concept-type]} query
        result-strings (apply concat (map (partial metadata-item->result-string concept-type results)
                                          items))
        headers ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                 "<results><hits>"
                 hits
                 "</hits><took>"
                 took
                 "</took>"]
        facets-strs [(facets->xml-string facets)]
        footers ["</results>"]
        response (apply str (concat headers result-strings facets-strs footers))]
    ;; Since clojure.data.xml does not handle namespaces fully from parse-str to emit-str,
    ;; we don't support pretty print for ISO result which has namespace prefixes on element names.
    (if (and pretty? (not (= :iso-mends result-format)))
      (let [parsed (x/parse-str response)
            ;; Fix for DIF emitting XML
            parsed (if (= :dif result-format)
                     (cx/update-elements-at-path
                       parsed [:result :DIF]
                       assoc :attrs dif-c/dif-header-attributes)
                     parsed)]
        (x/indent-str parsed))

      response)))

(defmethod qs/search-results->response :echo10
  [context query results]
  (search-results->metadata-response context query results))

(defmethod qs/search-results->response :dif
  [context query results]
  (search-results->metadata-response context query results))

(defmethod qs/search-results->response :iso-mends
  [context query results]
  (search-results->metadata-response context query results))
