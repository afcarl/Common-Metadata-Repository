(ns cmr.system-int-test.search.collection-spatial-search-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [cmr.system-int-test.utils.ingest-util :as ingest]
            [cmr.system-int-test.utils.search-util :as search]
            [cmr.system-int-test.utils.index-util :as index]
            [cmr.system-int-test.data2.collection :as dc]
            [cmr.system-int-test.data2.core :as d]
            [cmr.spatial.polygon :as poly]
            [cmr.spatial.point :as p]
            [cmr.spatial.line-string :as l]
            [cmr.spatial.mbr :as m]
            [cmr.spatial.ring-relations :as rr]
            [cmr.spatial.derived :as derived]
            [cmr.spatial.codec :as codec]
            [clojure.string :as str]
            [cmr.spatial.dev.viz-helper :as viz-helper]
            [cmr.spatial.serialize :as srl]
            [cmr.common.dev.util :as dev-util]
            [cmr.spatial.lr-binary-search :as lbs]
            [cmr.umm.spatial :as umm-s]
            [cmr.umm.collection :as c]
            [cmr.umm.echo10.collection :as ec]))

(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1"}))

(defn polygon
  "Creates a single ring polygon with the given ordinates. Points must be in counter clockwise order.
  The polygon will be closed automatically."
  [& ords]
  (poly/polygon [(apply umm-s/ords->ring ords)]))

(defn search-poly
  "Returns a url encoded polygon for searching"
  [& ords]
  (codec/url-encode (umm-s/set-coordinate-system :geodetic (apply polygon ords))))

(deftest spatial-search-test
  (let [make-coll (fn [coord-sys orbit-params et & shapes]
                    (d/ingest "PROV1"
                              (dc/collection
                                {:entry-title et
                                 :spatial-coverage (apply dc/spatial
                                                          coord-sys
                                                          orbit-params
                                                          coord-sys
                                                          shapes)})))

        ;; orbit parameters
        orbit-params {:swath-width 2
                      :period 96
                      :inclination-angle 45
                      :number-of-orbits 1
                      :start-circular-latitude 0}
        orbit-coll (d/ingest "PROV1"
                             (dc/collection
                               {:entry-title "orbit-params"
                                :spatial-coverage (dc/spatial :geodetic orbit-params)}))


        ;; Lines
        normal-line (make-coll :geodetic nil "normal-line"
                               (l/ords->line-string :geodetic 22.681 -8.839, 18.309 -11.426, 22.705 -6.557))
        normal-line-cart (make-coll :cartesian nil "normal-line-cart"
                                    (l/ords->line-string :cartesian 16.439 -13.463,  31.904 -13.607, 31.958 -10.401))

        ;; Bounding rectangles
        whole-world (make-coll :geodetic nil "whole-world" (m/mbr -180 90 180 -90))
        touches-np (make-coll :geodetic nil "touches-np" (m/mbr 45 90 55 70))
        touches-sp (make-coll :geodetic nil "touches-sp" (m/mbr -160 -70 -150 -90))
        across-am-br (make-coll :geodetic nil "across-am-br" (m/mbr 170 10 -170 -10))
        normal-brs (make-coll :geodetic nil "normal-brs"
                              (m/mbr 10 10 20 0)
                              (m/mbr -20 0 -10 -10))

        ;; Polygons
        wide-north (make-coll :geodetic nil "wide-north" (polygon -70 20, 70 20, 70 30, -70 30, -70 20))
        wide-south (make-coll :geodetic nil "wide-south" (polygon -70 -30, 70 -30, 70 -20, -70 -20, -70 -30))
        across-am-poly (make-coll :geodetic nil "across-am-poly" (polygon 170 35, -175 35, -170 45, 175 45, 170 35))
        on-np (make-coll :geodetic nil "on-np" (polygon 45 85, 135 85, -135 85, -45 85, 45 85))
        on-sp (make-coll :geodetic nil "on-sp" (polygon -45 -85, -135 -85, 135 -85, 45 -85, -45 -85))
        normal-poly (make-coll :geodetic nil "normal-poly" (polygon -20 -10, -10 -10, -10 10, -20 10, -20 -10))

        ;; CMR-724 special case
        whole-world-poly (make-coll :geodetic nil "whole-world-poly"
                                    (polygon -179.9999 0.0, -179.9999 -89.9999, 0.0 -89.9999,
                                             0.0 0.0, 0.0 89.9999, -179.9999 89.9999, -179.9999 0.0))

        ;; polygon with holes
        outer (umm-s/ords->ring -5.26,-2.59, 11.56,-2.77, 10.47,8.71, -5.86,8.63, -5.26,-2.59)
        hole1 (umm-s/ords->ring 6.95,2.05, 2.98,2.06, 3.92,-0.08, 6.95,2.05)
        hole2 (umm-s/ords->ring 5.18,6.92, -1.79,7.01, -2.65,5, 4.29,5.05, 5.18,6.92)
        polygon-with-holes  (make-coll :geodetic nil "polygon-with-holes" (poly/polygon [outer hole1 hole2]))

        ;; Cartesian Polygons
        wide-north-cart (make-coll :cartesian nil "wide-north-cart" (polygon -70 20, 70 20, 70 30, -70 30, -70 20))
        wide-south-cart (make-coll :cartesian nil "wide-south-cart" (polygon -70 -30, 70 -30, 70 -20, -70 -20, -70 -30))
        very-wide-cart (make-coll :cartesian nil "very-wide-cart" (polygon -180 40, -180 35, 180 35, 180 40, -180 40))
        very-tall-cart (make-coll :cartesian nil "very-tall-cart" (polygon -160 90, -160 -90, -150 -90, -150 90, -160 90))
        normal-poly-cart (make-coll :cartesian nil "normal-poly-cart" (polygon 1.534 -16.52, 6.735 -14.102, 3.745 -9.735, -1.454 -11.802, 1.534 -16.52))

        outer-cart (umm-s/ords->ring -5.26 -22.59 11.56 -22.77 10.47 -11.29 -5.86 -11.37 -5.26 -22.59)
        hole1-cart (umm-s/ords->ring 6.95 -17.95 2.98 -17.94 3.92 -20.08 6.95 -17.95)
        hole2-cart (umm-s/ords->ring 5.18 -13.08 -1.79 -12.99 -2.65 -15 4.29 -14.95 5.18 -13.08)
        polygon-with-holes-cart (make-coll :cartesian nil "polygon-with-holes-cart" (poly/polygon [outer-cart hole1-cart hole2-cart]))

        ;; Points
        north-pole (make-coll :geodetic nil "north-pole" (p/point 0 90))
        south-pole (make-coll :geodetic nil "south-pole" (p/point 0 -90))
        normal-point (make-coll :geodetic nil "normal-point" (p/point 10 22))
        am-point (make-coll :geodetic nil "am-point" (p/point 180 22))]
    (index/refresh-elastic-index)

    (testing "orbit parameters retrieval"
      (let [found (search/find-concepts-in-format
                    "application/echo10+xml"
                    :collection
                    {:entry-title "orbit-params"})
            xml (:body found)
            echo10 (re-find #"<Collection>.*Collection>" xml)
            umm (ec/parse-collection echo10)
            umm-op (:orbit-parameters (:spatial-coverage umm))
            expected (c/map->OrbitParameters orbit-params)]
        (= expected umm-op)))


    (testing "line searches"
      (are [ords items]
           (let [found (search/find-refs
                         :collection
                         {:line (codec/url-encode (apply l/ords->line-string :geodetic ords))
                          :page-size 50})
                 matches? (d/refs-match? items found)]
             (when-not matches?
               (println "Expected:" (->> items (map :entry-title) sort pr-str))
               (println "Actual:" (->> found :refs (map :name) sort pr-str)))
             matches?)

           ;; normal two points
           [-24.28,-12.76,10,10] [whole-world whole-world-poly polygon-with-holes normal-poly normal-brs]

           ;; normal multiple points
           [-0.37,-14.07,4.75,1.27,25.13,-15.51] [whole-world whole-world-poly polygon-with-holes
                                                  polygon-with-holes-cart normal-line-cart
                                                  normal-line normal-poly-cart]
           ;; across antimeridian
           [-167.85,-9.08,171.69,43.24] [whole-world whole-world-poly across-am-br across-am-poly very-wide-cart]

           ;; across north pole
           [0 85, 180 85] [whole-world whole-world-poly north-pole on-np touches-np]

           ;; across north pole where cartesian polygon touches it
           [-155 85, 25 85] [whole-world whole-world-poly north-pole on-np very-tall-cart]

           ;; across south pole
           [0 -85, 180 -85] [whole-world whole-world-poly south-pole on-sp]

           ;; across north pole where cartesian polygon touches it
           [-155 -85, 25 -85] [whole-world whole-world-poly south-pole on-sp touches-sp very-tall-cart]))

    (testing "point searches"
      (are [lon_lat items]
           (let [found (search/find-refs :collection {:point (codec/url-encode (apply p/point lon_lat))
                                                      :page-size 50})
                 matches? (d/refs-match? items found)]
             (when-not matches?
               (println "Expected:" (->> items (map :entry-title) sort pr-str))
               (println "Actual:" (->> found :refs (map :name) sort pr-str)))
             matches?)

           ;; north pole
           [0 90] [whole-world whole-world-poly north-pole on-np touches-np]

           ;; south pole
           [0 -90] [whole-world whole-world-poly south-pole on-sp touches-sp]

           ;; in hole of polygon with a hole
           [4.83 1.06] [whole-world whole-world-poly]
           ;; in hole of polygon with a hole
           [1.67 5.43] [whole-world whole-world-poly]
           ;; and not in hole
           [1.95 3.36] [whole-world whole-world-poly polygon-with-holes]

           ;; in mbr
           [17.73 2.21] [whole-world whole-world-poly normal-brs]

           ;;matches exact point on polygon
           [-5.26 -2.59] [whole-world whole-world-poly polygon-with-holes]

           ;; Matches a granule point
           [10 22] [whole-world whole-world-poly normal-point wide-north-cart]

           [-154.821 37.84] [whole-world whole-world-poly very-wide-cart very-tall-cart]

           ;; Near but not inside the cartesian normal polygon
           ;; and also insid the polygon with holes (outside the holes)
           [-2.212,-12.44] [whole-world whole-world-poly polygon-with-holes-cart]
           [0.103,-15.911] [whole-world whole-world-poly polygon-with-holes-cart]
           ;; inside the cartesian normal polygon
           [2.185,-11.161] [whole-world whole-world-poly normal-poly-cart]

           ;; inside a hole in the cartesian polygon
           [4.496,-18.521] [whole-world whole-world-poly]

           ;; point on geodetic line
           [20.0 -10.437310310746927] [whole-world whole-world-poly normal-line]
           ;; point on cartesian line
           [20.0 -13.496157710960231] [whole-world whole-world-poly normal-line-cart]))

    (testing "bounding rectangle searches"
      (are [wnes items]
           (let [found (search/find-refs :collection {:bounding-box (codec/url-encode (apply m/mbr wnes))
                                                      :page-size 50})
                 matches? (d/refs-match? items found)]
             (when-not matches?
               (println "Expected:" (->> items (map :entry-title) sort pr-str))
               (println "Actual:" (->> found :refs (map :name) sort pr-str)))
             matches?)

           [-23.43 5 25.54 -6.31] [whole-world whole-world-poly polygon-with-holes normal-poly normal-brs]

           ;; inside hole in geodetic
           [4.03,1.51,4.62,0.92] [whole-world whole-world-poly]
           ;; corner points inside different holes
           [4.03,5.94,4.35,0.92] [whole-world whole-world-poly polygon-with-holes]

           ;; inside hole in cartesian polygon
           [-0.54,-13.7,3.37,-14.45] [whole-world whole-world-poly normal-poly-cart]
           ;; inside different holes in cartesian polygon
           [3.57,-14.38,3.84,-18.63] [whole-world whole-world-poly normal-poly-cart polygon-with-holes-cart]

           ;; just under wide north polygon
           [-1.82,46.56,5.25,44.04] [whole-world whole-world-poly]
           [-1.74,46.98,5.25,44.04] [whole-world whole-world-poly wide-north]
           [-1.74 47.05 5.27 44.04] [whole-world whole-world-poly wide-north]

           ;; vertical slice of earth
           [-10 90 10 -90] [whole-world whole-world-poly on-np on-sp wide-north wide-south polygon-with-holes
                            normal-poly normal-brs north-pole south-pole normal-point
                            very-wide-cart wide-north-cart wide-south-cart normal-poly-cart
                            polygon-with-holes-cart]

           ;; crosses am
           [166.11,53.04,-166.52,-19.14] [whole-world whole-world-poly across-am-poly across-am-br am-point very-wide-cart]

           ;; Matches geodetic line
           [17.67,-4,25.56,-6.94] [whole-world whole-world-poly normal-line]

           ;; Matches cartesian line
           [23.59,-4,25.56,-15.47] [whole-world whole-world-poly normal-line-cart]

           ;; whole world
           [-180 90 180 -90] [whole-world whole-world-poly touches-np touches-sp across-am-br normal-brs
                              wide-north wide-south across-am-poly on-sp on-np normal-poly
                              polygon-with-holes north-pole south-pole normal-point am-point
                              very-wide-cart very-tall-cart wide-north-cart wide-south-cart
                              normal-poly-cart polygon-with-holes-cart normal-line normal-line-cart]))

    (testing "polygon searches"
      (are [ords items]
           (let [found (search/find-refs :collection {:polygon (apply search-poly ords) })
                 matches? (d/refs-match? items found)]
             (when-not matches?
               (println "Expected:" (->> items (map :entry-title) sort pr-str))
               (println "Actual:" (->> found :refs (map :name) sort pr-str)))
             matches?)

           [20.16,-13.7,21.64,12.43,12.47,11.84,-22.57,7.06,20.16,-13.7]
           [whole-world whole-world-poly normal-poly normal-brs polygon-with-holes normal-line normal-line-cart]

           ;; Intersects 2nd of normal-brs
           [-16.79,-12.71,-6.32,-10.95,-5.74,-6.11,-15.18,-7.63,-16.79,-12.71]
           [whole-world whole-world-poly normal-poly normal-brs]

           [0.53,39.23,21.57,59.8,-112.21,84.48,-13.37,40.91,0.53,39.23]
           [whole-world whole-world-poly on-np wide-north very-wide-cart]

           ;; around north pole
           [58.41,76.95,163.98,80.56,-122.99,81.94,-26.18,82.82,58.41,76.95]
           [whole-world whole-world-poly on-np touches-np north-pole very-tall-cart]

           ;; around south pole
           [-161.53,-69.93,25.43,-51.08,13.89,-39.94,-2.02,-40.67,-161.53,-69.93]
           [whole-world whole-world-poly on-sp wide-south touches-sp south-pole very-tall-cart]

           ;; Across antimeridian
           [-163.9,49.6,171.51,53.82,166.96,-11.32,-168.36,-14.86,-163.9,49.6]
           [whole-world whole-world-poly across-am-poly across-am-br am-point very-wide-cart]

           [-2.212 -12.44, 0.103 -15.911, 2.185 -11.161 -2.212 -12.44]
           [whole-world whole-world-poly normal-poly-cart polygon-with-holes-cart]

           ;; Interactions with lines
           ;; Covers both lines
           [15.42,-15.13,36.13,-14.29,25.98,-0.75,13.19,0.05,15.42,-15.13]
           [whole-world whole-world-poly normal-line normal-line-cart normal-brs]

           ;; Intersects both lines
           [23.33,-14.96,24.02,-14.69,19.73,-6.81,18.55,-6.73,23.33,-14.96]
           [whole-world whole-world-poly normal-line normal-line-cart]

           ;; Related to the geodetic polygon with the holes
           ;; Inside holes
           [4.1,0.64,4.95,0.97,6.06,1.76,3.8,1.5,4.1,0.64] [whole-world whole-world-poly]
           [1.41,5.12,3.49,5.52,2.66,6.11,0.13,6.23,1.41,5.12] [whole-world whole-world-poly]
           ;; Partially inside a hole
           [3.58,-1.34,4.95,0.97,6.06,1.76,3.8,1.5,3.58,-1.34] [whole-world whole-world-poly polygon-with-holes]
           ;; Covers a hole
           [3.58,-1.34,5.6,0.05,7.6,2.33,2.41,2.92,3.58,-1.34] [whole-world whole-world-poly polygon-with-holes]
           ;; points inside both holes
           [4.44,0.66,5.4,1.35,2.66,6.11,0.13,6.23,4.44,0.66] [whole-world whole-world-poly polygon-with-holes]
           ;; completely covers the polygon with holes
           [-6.45,-3.74,12.34,-4.18,12,9.45,-6.69,9.2,-6.45,-3.74] [whole-world whole-world-poly polygon-with-holes normal-brs]

           ;; Related to the cartesian polygon with the holes
           ;; Inside holes
           [-1.39,-14.32,2.08,-14.38,1.39,-13.43,-1.68,-13.8,-1.39,-14.32]
           [whole-world whole-world-poly normal-poly-cart]
           ;; Partially inside a hole
           [-1.39,-14.32,2.08,-14.38,1.64,-12.45,-1.68,-13.8,-1.39,-14.32]
           [whole-world whole-world-poly polygon-with-holes-cart normal-poly-cart]
           ;; Covers a hole
           [-3.24,-15.58,5.22,-15.16,6.05,-12.37,-1.98,-12.46,-3.24,-15.58]
           [whole-world whole-world-poly polygon-with-holes-cart normal-poly-cart]
           ;; points inside both holes
           [3.98,-18.64,5.08,-18.53,3.7,-13.78,-0.74,-13.84,3.98,-18.64]
           [whole-world whole-world-poly polygon-with-holes-cart normal-poly-cart]
           ;; completely covers the polygon with holes
           [-5.95,-23.41,12.75,-23.69,11.11,-10.38,-6.62,-10.89,-5.95,-23.41]
           [whole-world whole-world-poly polygon-with-holes-cart wide-south-cart normal-poly-cart]))

    (testing "AQL spatial search"
      (are [type ords items]
           (let [refs (search/find-refs-with-aql :collection [{type ords}])
                 result (d/refs-match? items refs)]
             (when-not result
               (println "Expected:" (pr-str (map :entry-title items)))
               (println "Actual:" (pr-str (map :name (:refs refs)))))
             result)
           :polygon [20.16,-13.7,21.64,12.43,12.47,11.84,-22.57,7.06,20.16,-13.7]
           [whole-world whole-world-poly normal-poly normal-brs polygon-with-holes normal-line normal-line-cart]

           :box [23.59,-4,25.56,-15.47] [whole-world whole-world-poly normal-line-cart]
           :point [17.73 2.21] [whole-world whole-world-poly normal-brs]
           :line [-0.37,-14.07,4.75,1.27,25.13,-15.51]
           [whole-world whole-world-poly polygon-with-holes polygon-with-holes-cart normal-line-cart normal-line
            normal-poly-cart]))))


