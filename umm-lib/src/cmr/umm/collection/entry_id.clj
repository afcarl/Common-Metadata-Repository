(ns cmr.umm.collection.entry-id
  "Functions to create and retrieve entry-id for collections."
  (:require [clojure.string :as str]))

(def DEFAULT_VERSION "Not provided")

(defn entry-id
  "Returns the entry-id for the given short-name and version-id."
  [short-name version-id]
  (if (not= DEFAULT_VERSION version-id)
     (str short-name "_V:" version-id)
     short-name))

(defn umm->entry-id
  "Returns an entry-id for the given umm record."
  [umm]
  (let [{:keys [short-name version-id]} (:product umm)]
  	(entry-id short-name version-id)))