(ns cmr.metadata-db.runner
  (:require [cmr.metadata-db.system :as system]
            [clojure.tools.cli :refer [cli]]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [taoensso.timbre :refer (debug info warn error)]
            [cmr.metadata-db.data.memory :as memory]
            [cmr.metadata-db.api.web-server :as web-server])
  (:gen-class))

(defn parse-endpoint
  "Parses an endpoint in the format host:port"
  [s]
  (let [[host port] (string/split s #":" 2)]
    {:host host :port (Integer. port)}))

(def arg-description
  [["-h" "--help" "Show help" :default false :flag true]
   ["-d" "--db" "Specifies the database type - memory or oracle" :default "memory"]
   ["-p" "--port" "The HTTP Port to listen on for requests." :default 4242 :parse-fn #(Integer. %)]])


(defn parse-args [args]
  (let [[options extra-args banner] (apply cli args arg-description)
        error-with-banner #((println "Error: " % "\n" banner) (System/exit 1))
        exit-with-banner #((println % "\n" banner) (System/exit 0))]
    (when (:help options)
      (exit-with-banner "Help:\n"))
    options))

(defn- select-db
  "return an initialization function for database based on user input parameter"
  [{:keys [db]} ]
  (case db
    "memory" (memory/create-db)
    "default" (memory/create-db)))
  

(defn -main
  "Starts the App."
  [& args]
  (let [{:keys [port db]} (parse-args args)
        db-params {:db db}
        db (select-db db-params)
        web-params {:port port}
        web (web-server/create-web-server web-params)
        system (system/start (system/create-system db web))]
    (info "Running...")))

(comment 
  
((str ({:db "memory"} :db) "/" "create-db")))