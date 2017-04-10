(ns cmr.system-int-test.search.collection-two-d-coord-name-search-test
  "Integration test for collection two d coordinate name search"
  (:require [clojure.test :refer :all]
            [cmr.system-int-test.utils.ingest-util :as ingest]
            [cmr.system-int-test.utils.search-util :as search]
            [cmr.system-int-test.utils.index-util :as index]
            [cmr.system-int-test.data2.collection :as dc]
            [cmr.system-int-test.data2.core :as d]))

(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1"}))

(deftest search-by-two-d-coord-name
  (let [two-d1 (dc/two-d "one CALIPSO")
        two-d2 (dc/two-d "two CALIPSO")
        two-d3 (dc/two-d "three CALIPSO")
        two-d4 (dc/two-d "three Bravo")
        two-d5 (dc/two-d "four Bravo")
        coll1 (d/ingest "PROV1" (dc/collection {:two-d-coordinate-systems [two-d1]}))
        coll2 (d/ingest "PROV1" (dc/collection {:two-d-coordinate-systems [two-d2]}))
        coll3 (d/ingest "PROV1" (dc/collection {:two-d-coordinate-systems [two-d3]}))
        coll4 (d/ingest "PROV1" (dc/collection {:two-d-coordinate-systems [two-d4]}))
        coll5 (d/ingest "PROV1" (dc/collection {:two-d-coordinate-systems [two-d2 two-d5]}))
        coll6 (d/ingest "PROV1" (dc/collection {}))]
    (index/wait-until-indexed)

    (testing "two d coordinate search by two_d_coordinate_system_name parameter"
      (are [items two-ds options]
           (let [params (merge {"two_d_coordinate_system_name[]" two-ds}
                               (when options
                                 {"options[two_d_coordinate_system_name]" options}))]
             (d/refs-match? items (search/find-refs :collection params)))

           ;; search by by two d coordinate system name - single value
           [coll1] "one CALIPSO" {}

           ;; search by two d coordinate system name - multiple values
           [coll1 coll4] ["one CALIPSO" "three Bravo"] {}

           ;; search by two d coordinate system name - wildcards
           [coll3 coll4] "three *" {:pattern true}
           [] "three *" {:pattern false}
           [] "three *" {}

           ;; search by two d coordinate system name - no match
           [] "NO MATCH" {}

           ;; search by two d coordinate system name - multiple in collection
           [coll2 coll5] "two CALIPSO" {}))

    (testing "two d coordinate search by two_d_coordinate_system[name] parameter"
      (are [items two-ds]
           (let [params {"two_d_coordinate_system[name]" two-ds}]
             (d/refs-match? items (search/find-refs :collection params)))

           ;; search by by two d coordinate system name - single value
           [coll1] "one CALIPSO"

           ;; search by two d coordinate system name - multiple values
           [coll1 coll4] ["one CALIPSO" "three Bravo"]

           ;; search by two d coordinate system name - no match
           [] "NO MATCH"

           ;; search by two d coordinate system name - multiple in collection
           [coll2 coll5] "two CALIPSO")

      (is (= {:status 400,
              :errors ["two_d_coordinate_system[name] can not be empty"]}
             (search/find-refs :collection {"two_d_coordinate_system[name]" ""})))

      (is (= {:status 400,
              :errors ["two_d_coordinate_system[coordinates] is not supported for collection search."]}
             (search/find-refs :collection {"two_d_coordinate_system[name]" "grid"
                                            "two_d_coordinate_system[coordinates]" "dummy"}))))

    (testing "two d coordinate search by aql"
      (are [items two-ds options]
           (let [condition (merge {:TwoDCoordinateSystemName two-ds} options)]
             (d/refs-match? items (search/find-refs-with-aql :collection [condition])))

           ;; search by by two d coordinate system name - single value
           [coll1] "one CALIPSO" {}

           ;; search by two d coordinate system name - multiple values
           [coll1 coll4] ["one CALIPSO" "three Bravo"] {}

           ;; search by two d coordinate system name - wildcards
           [coll3 coll4] "three %" {:pattern true}

           ;; search by two d coordinate system name - no match
           [] "NO MATCH" {}

           ;; search by two d coordinate system name - multiple in collection
           [coll2 coll5] "two CALIPSO" {}))

    (testing "two d coordinate search using JSON Query"
      (are [items search]
           (d/refs-match? items (search/find-refs-with-json-query :collection {} search))

           [coll1] {:two_d_coordinate_system_name "one CALIPSO"}
           [coll1 coll4] {:or [{:two_d_coordinate_system_name "one CALIPSO"}
                               {:two_d_coordinate_system_name "three Bravo"}]}
           [] {:two_d_coordinate_system_name "NO MATCH"}
           [coll2 coll5] {:two_d_coordinate_system_name "two CALIPSO"}
           [coll3 coll4] {:two_d_coordinate_system_name {:value "three *"
                                                         :pattern true
                                                         :ignore_case false}}))))