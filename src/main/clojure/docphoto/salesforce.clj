(ns docphoto.salesforce
  (:use [clojure.string :only (capitalize)]
        [clojure.contrib.core :only (-?>)]
        [clojure.contrib.seq :only (find-first)])
  (:require [clojure.contrib.string :as string])
  (:import [com.sforce.ws ConnectorConfig]
           [com.sforce.soap.enterprise Connector]
           [com.sforce.soap.enterprise.sobject
            Contact SObject Exhibit_Application__c]
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
(defmacro defsfcommand
  ([fname bindings body] `(defsfcommand ~fname no-check ~bindings ~body))
  ([fname check-results bindings body]
     `(defn ~fname ~bindings
        (let [conn# ~(first bindings)]
          (try
            ~(if (= check-results 'check-results)
               `(let [results# ~body]
                  (if (not-every? #(.isSuccess %) results#)
                    (throw (IllegalStateException.
                            (str (find-first #(not (.isSuccess %)) results#))))
                    results#))
               body)
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
                (throw e#))))))))

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
                   (for [[column operator criteria noquote] select-filters]
                     `(str ~(string/as-str column)
                           " " ~(string/as-str operator) " "
                           ~(if noquote
                              criteria
                              `(str "'" ~criteria "'")))))])))
        ~(if-let [to-append (:append options)]
           (str " " to-append)))))))

(defsfcommand update-records check-results [conn sobjects]
  (.update conn sobjects))

(defmacro update [conn class & update-maps]
  `(update-records
    ~conn
    (sobject-array
     (map (partial create-sf-object (new ~class))
          (vector ~@update-maps)))))

(defsfcommand create check-results [conn sobjects]
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
     (to-array [v])))
  obj)

(defn create-contact [conn contact-data-map]
  (create
   conn
   (sobject-array
    [(create-sf-object
      (Contact.)
      (select-keys
       contact-data-map
       [:firstName :lastName :email :phone :userName__c :password__c
        :mailingStreet :mailingCity :mailingState :mailingPostalCode
        :mailingCountry]))])))

(defn create-application [conn application-map]
  (-?> (create
        conn
        (sobject-array
         [(create-sf-object
           (Exhibit_Application__c.)
           (select-keys
            application-map
            [:statementRich__c :title__c :biography__c :website__c
             :contact__c :exhibit__c]))]))
       first
       .getId))
