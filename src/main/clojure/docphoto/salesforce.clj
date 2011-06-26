(ns docphoto.salesforce
  (:use [clojure.string :only (capitalize)]
        [clojure.contrib.core :only (-?>)])
  (:require [clojure.contrib.string :as string])
  (:import [com.sforce.ws ConnectorConfig]
           [com.sforce.soap.enterprise Connector]
           [com.sforce.soap.enterprise.sobject Contact SObject]
           [com.sforce.soap.enterprise.fault UnexpectedErrorFault]
           [org.apache.commons.codec.digest DigestUtils]))

(defn connector-config [username password]
  (doto (ConnectorConfig.)
    (.setUsername username)
    (.setPassword password)))

(defn connector
  ([config] (Connector/newConnection config))
  ([username password] (connector (connector-config username password))))

(defn non-nills [m]
  (select-keys m
               (for [[k v] m :when (not (nil? v))] k)))

(defn sobject-array [coll]
  (into-array SObject coll))

(defn sobject->map [sobj]
  (-?> sobj
       bean
       (dissoc :fieldsToNull)
       non-nills))

;; automatically reconnect on session invalidation
;; salesforce connection must be first binding
(defmacro defsfcommand [fname bindings & body]
  `(defn ~fname ~bindings
     (let [conn# ~(first bindings)]
       (try
         (do ~@body)
         (catch UnexpectedErrorFault e#
           (if (= "INVALID_SESSION_ID"
                  (.getLocalPart (.getFaultCode e#)))
             (let [cfg# (.getConfig conn#)
                   login-result#
                   (.login conn#
                           (.getUsername cfg#) (.getPassword cfg#))]
               (.setSessionHeader conn# (.getSessionId login-result#))
               ;; retry function
               (apply ~fname ~bindings))
             (throw e#)))))))


(defsfcommand query-records [conn query]
  (->
   (.query conn query)
   .getRecords))

(defmacro query [conn table select-fields select-filters & options]
  (let [options (apply hash-map options)]
    `(map
      sf/sobject->map
      (query-records
       ~conn
       (str
        ~(str
          "SELECT "
          (string/join ", " (map string/as-str select-fields))
          " FROM " (capitalize (string/as-str table)))

        ;; if we wanted to generic dynamic queries from a map at runtime
        ;; we can check if we have a symbol here,
        ;; and assume an AND for all operations
        ~(if (not-empty select-filters)
           `(str
             " WHERE "
             (string/join
              ~(str " " (string/as-str (:compounder options "AND")) " ")
              [~@(let [column (gensym) operator (gensym) criteria (gensym)]
                   (for [[column operator criteria] select-filters]
                     `(str ~(string/as-str column)
                           " " ~(string/as-str operator) " "
                           "'" ~criteria "'")))]))))))))

(defsfcommand update [conn sobjects]
  (if (.isArray (.getClass sobjects))
    (.update conn sobjects)
    (update conn (sobject-array sobjects))))

(defsfcommand create [conn sobjects]
  (.create conn sobjects))

(defsfcommand describe-sobject [conn object-name]
  (when-let [description (.describeSObject conn object-name)]
    {:name (.getName description)
     :activate? (.isActivateable description)
     :fields
     (for [field (.getFields description)]
       {:name (.getName field)
        :nillable? (.isNillable field)
        :unique? (.isUnique field)
        :type (.getType field)
        :label (.getLabel field)})}))

(defn describe-sobject-names [conn object-name]
  (->> object-name (describe-sobject conn) :fields (map :name)))

(defn create-sf-object [obj data-map]
  (doseq [[k v] data-map]
    (clojure.lang.Reflector/invokeInstanceMethod
     obj
     (apply str "set"
            (capitalize (first (name k)))
            (rest (name k)))
     (to-array [v]))))

(defn create-contact [conn contact-data-map]
  (create
   conn
   (sobject-array [(create-sf-object (Contact.) contact-data-map)])))
