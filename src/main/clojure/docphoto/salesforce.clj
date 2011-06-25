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
     (let [ctr# ~(first bindings)]
       (try
         (do ~@body)
         (catch UnexpectedErrorFault e#
           (if (= "INVALID_SESSION_ID"
                  (.getLocalPart (.getFaultCode e#)))
             (let [cfg# (.getConfig ctr#)
                   login-result#
                   (.login ctr#
                           (.getUsername cfg#) (.getPassword cfg#))]
               (.setSessionHeader ctr# (.getSessionId login-result#)))
             ;; retry function
             (apply ~fname ~bindings)))))))


(defsfcommand query-records [ctr query]
  (->
   (.query ctr query)
   .getRecords))

;; (defn query-single-record [ctr query]
;;   (-> (query-records ctr query) first))

;; (defn default-fields-contact []
;;   [:Id :FirstName :LastName :Email])

;; (defmacro defquery-single [fname ctr field table column default-fields-symbol]
;;   `(defn ~fname
;;      ([~ctr ~field] (apply ~fname ~ctr ~field (~default-fields-symbol)))
;;      ([~ctr ~field & fields#]
;;         (sobject->map
;;          (query-single-record
;;           ~ctr
;;           (str "SELECT "
;;                (string/join ", " (map string/as-str fields#))
;;                " FROM " (name '~table) " WHERE "
;;                (name '~column) "='" ~field "'"))))))

;; ;; XXX should investigate filtering by multiple fields
;; (defquery-single query-single-contact-by-username ctr username
;;   Contact UserName__c default-fields-contact)
;; (defquery-single query-single-contact-by-id ctr id
;;   Contact Id default-fields-contact)
;; (defquery-single query-single-contact-by-email ctr email
;;   Contact Email default-fields-contact)

(defmacro query [ctr table select-fields select-filters & options]
  (let [options (apply hash-map options)]
    `(map
      sf/sobject->map
      (query-records
       ~ctr
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

(defsfcommand update [ctr sobjects]
  (.update ctr sobjects))

(defsfcommand create [ctr sobjects]
  (.create ctr sobjects))

(defsfcommand describe-sobject [ctr object-name]
  (when-let [description (.describeSObject ctr object-name)]
    {:name (.getName description)
     :activate? (.isActivateable description)
     :fields
     (for [field (.getFields description)]
       {:name (.getName field)
        :nillable? (.isNillable field)
        :unique? (.isUnique field)
        :type (.getType field)
        :label (.getLabel field)})}))

(defn describe-sobject-names [ctr object-name]
  (->> object-name (describe-sobject ctr) :fields (map :name)))

(defn create-sf-object [obj data-map]
  (doseq [[k v] data-map]
    (clojure.lang.Reflector/invokeInstanceMethod
     obj
     (apply str "set"
            (capitalize (first (name k)))
            (rest (name k)))
     (to-array [v]))))

(defn create-contact [ctr contact-data-map]
  (create
   ctr
   (sobject-array [(create-sf-object (Contact.) contact-data-map)])))

(defn test-sample-update [ctr id firstname lastname]
  ;; get back a save result object like this
  ;; need error checking
  ;; [#<SaveResult [SaveResult  errors='{[0]}'
  ;;  id='003A000000k2GyeIAE'
  ;;  success='true'
  ;; ]
  ;; >]
  (update
   ctr
   (sobject-array
    [(doto (Contact.)
       (.setId id)
       (.setFirstName firstname)
       (.setLastName lastname))])))

(comment

  (def result-coll (into [] (test-sample-update ctr "003A000000k2GyeIAE" "Robert" "Marianski")))

  (query-single-contact-by-id ctr "003A000000k2GyeIAE")
)
