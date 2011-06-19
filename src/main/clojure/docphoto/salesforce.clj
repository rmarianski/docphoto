(ns docphoto.salesforce
  (:use [clojure.string :only (capitalize)])
  (:require [clojure.contrib.string :as string])
  (:import [com.sforce.ws ConnectorConfig]
           [com.sforce.soap.enterprise Connector]
           [com.sforce.soap.enterprise.sobject Contact SObject]
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
  (and sobj
       (->
        (bean sobj)
        (dissoc :fieldsToNull)
        non-nills)))

(defn query-records [ctr query]
  (->
   (.query ctr query)
   .getRecords))

(defn query-single-record [ctr query]
  (-> (query-records ctr query) first))

(defn default-fields-contact []
  [:Id :FirstName :LastName :Email])

(defmacro defquery-single [fname ctr field table column default-fields-symbol]
  `(defn ~fname
     ([~ctr ~field] (apply ~fname ~ctr ~field (~default-fields-symbol)))
     ([~ctr ~field & fields#]
        (sobject->map
         (query-single-record
          ~ctr
          (str "SELECT "
               (string/join ", " (map string/as-str fields#))
               " FROM " (name '~table) " WHERE "
               (name '~column) "='" ~field "'"))))))

;; XXX should investigate filtering by multiple fields
(defquery-single query-single-contact-by-username ctr username
  Contact UserName__c default-fields-contact)
(defquery-single query-single-contact-by-id ctr id
  Contact Id default-fields-contact)
(defquery-single query-single-contact-by-email ctr email
  Contact Email default-fields-contact)

(defn update [ctr sobjects]
  (.update ctr sobjects))

(defn create [ctr sobjects]
  (.create ctr sobjects))

(defn describe-sobject [ctr object-name]
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
