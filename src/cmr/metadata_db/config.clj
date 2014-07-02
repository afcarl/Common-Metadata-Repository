(ns cmr.metadata-db.config
  "Contains functions to retrieve metadata db specific configuration"
  (:require [cmr.common.config :as cfg]
            [cmr.oracle.config :as oracle-config]
            [cmr.oracle.connection :as conn]))

(def app-port (cfg/config-value-fn :metadata-db-port 3001 #(Long. %)))

(def db-username
  (cfg/config-value-fn :metadata-db-username "METADATA_DB"))

(def db-password
  (cfg/config-value-fn :metadata-db-password "METADATA_DB"))

(def catalog-rest-db-username
  (cfg/config-value-fn :catalog-rest-db-username "DEV_52_CATALOG_REST"))

(defn db-spec
  "Returns a db spec populated with config information that can be used to connect to oracle"
  [connection-pool-name]
   (conn/db-spec
     connection-pool-name
     (oracle-config/db-url)
     (oracle-config/db-fcf-enabled)
     (oracle-config/db-ons-config)
     (db-username)
     (db-password)))

(defn parallel-n
  "Get the number of concepts that should be processed in each thread of get-concepts."
  []
  (Integer/parseInt ((cfg/config-value-fn :parallel-n "200"))))

(defn fetch-size
  "Get the setting for query fetch-size (number of rows to fetch at once)"
  []
  (Integer/parseInt ((cfg/config-value-fn :fetch-size "200"))))
