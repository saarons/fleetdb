(ns fleetdb.embedded
  (:use [fleetdb.util :only (def- ? spawn rassert raise)]
        [clojure.contrib.seq-utils :only (partition-all)])
  (:require (fleetdb [types :as types] [lint :as lint] [core :as core]
                     [fair-lock :as fair-lock] [file :as file] [json :as json]))
  (:import  (java.util ArrayList)
            (java.io FileReader BufferedReader FileWriter BufferedWriter)))

(defn- dba? [dba]
  (? (:write-lock (meta dba))))

(defn- persistent? [dba]
  (? (:writer (meta dba))))

(defn- ephemeral? [dba]
  (not (persistent? dba)))

(defn- compacting? [dba]
  (? (:write-buf (meta dba))))

(defn- replay-query [db q]
  (first (core/query* db q)))

(defn- read-db [read-path]
  (let [reader  (BufferedReader. (FileReader. #^String read-path))
        queries (json/parse-lines reader)
        empty   (core/init)]
    (reduce replay-query empty queries)))

(defn- new-writer [write-path]
  (let [appending (file/exist? write-path)]
    (BufferedWriter. (FileWriter. #^String write-path appending))))

(defn- close-writer [#^BufferedWriter writer]
  (.close writer))

(defn- write-query [#^BufferedWriter writer query]
  (.write writer #^String (json/generate-string query))
  (.flush writer))

(defn- write-queries [#^BufferedWriter writer queries]
  (doseq [q queries]
    (.write writer #^String (json/generate-string q))
    (.flush writer)))

(defn- write-db [write-path db]
  (let [writer (new-writer write-path)]
    (doseq [coll (core/query* db ["list-collections"])]
      (write-queries writer
        (for [chunk (partition-all 100 (core/query* db ["select" coll]))]
          ["insert" coll chunk]))
      (write-queries writer
        (for [ispec (core/query* db ["list-indexes" coll])]
          ["create-index" coll ispec])))
    (close-writer writer)))

(defn- init* [db & [other-meta]]
  (atom db :meta (assoc other-meta :write-lock (fair-lock/init))))

(defn init-ephemeral []
  (init* (core/init)))

(defn load-ephemeral [read-path]
  (init* (read-db read-path)))

(defn init-persistent [write-path]
  (init* (core/init)
         {:writer (new-writer write-path)
          :write-path write-path}))

(defn load-persistent [read-write-path]
  (init* (read-db read-write-path)
         {:writer (new-writer read-write-path)
          :write-path read-write-path}))

(defn close [dba]
  (assert (fair-lock/join (:write-lock (meta dba)) 60))
  (if-let [writer (:writer (meta dba))]
    (close-writer writer))
  (assert (compare-and-set! dba @dba nil))
  true)

(defn fork [dba]
  (rassert (ephemeral? dba) "cannot fork persistent databases")
  (init* @dba))

(defn snapshot [dba snapshot-path]
  (rassert (ephemeral? dba) "cannot snapshot persistent databases")
  (let [tmp-path  (file/tmp-path "/tmp" "snapshot")]
    (write-db tmp-path @dba)
    (file/mv tmp-path snapshot-path)
    true))

(defn compact [dba]
  (rassert (persistent? dba) "cannot compact ephemeral database")
  (rassert (not (compacting? dba)) "already compacting database")
  (fair-lock/fair-locking (:write-lock (meta dba))
    (let [tmp-path    (file/tmp-path "/tmp" "compact")
          db-at-start @dba]
      (alter-meta! dba assoc :write-buf (ArrayList.))
      (spawn
        (write-db tmp-path db-at-start)
        (fair-lock/fair-locking (:write-lock (meta dba))
          (let [writer (new-writer tmp-path)]
            (write-queries writer (:write-buf (meta dba)))
            (file/mv tmp-path (:write-path (meta dba)))
            (close-writer (:writer (meta dba)))
            (alter-meta! dba dissoc :write-buf)
            (alter-meta! dba assoc  :writer writer))))
      true)))

(defn query* [dba q]
  (if (types/write-queries (first q))
    (fair-lock/fair-locking (:write-lock (meta dba))
      (let [old-db          @dba
            [new-db result] (core/query* old-db q)]
        (when-let [writer (:writer (meta dba))]
          (write-query writer q)
          (when-let [write-buf (:write-buf (meta dba))]
            (.add #^ArrayList write-buf q)))
        (assert (compare-and-set! dba old-db new-db))
        result))
    (core/query* @dba q)))

(defn query [dba q]
  (rassert (dba? dba) "dba not recognized as a database")
  (lint/lint-query q)
  (query* dba q))
