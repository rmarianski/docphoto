(ns docphoto.salesforce
  (:use [clojure.core.incubator :only (-?>)])
  (:require [clojure.string :as string])
  (:import [com.sforce.ws ConnectorConfig]
           [com.sforce.soap.enterprise Connector]
           [com.sforce.soap.enterprise.sobject
            Contact SObject Exhibit_Application__c Image__c]
           [com.sforce.soap.enterprise.fault UnexpectedErrorFault]
           [org.apache.commons.codec.digest DigestUtils]))

;; no longer in contrib
(defn find-first
  "Returns the first item of coll for which (pred item) returns logical true.
  Consumes sequences up to the first match, will consume the entire sequence
  and return nil if no match is found."
  [pred coll]
  (first (filter pred coll)))

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
      sobject->map
      (query-records
       ~conn
       (str
        ~(str
          "SELECT "
          (string/join ", " (map name
                                 (if (vector? select-fields)
                                   select-fields
                                   ;; select fields must be known at
                                   ;; compile time and expand to
                                   ;; something sequential
                                   (eval select-fields))))
          " FROM " (string/capitalize (name table)))

        ;; if we wanted to generic dynamic queries from a map at runtime
        ;; we can check if we have a symbol here,
        ;; and assume an AND for all operations
        ~(if (not-empty select-filters)
           `(str
             " WHERE "
             (string/join
              ~(str " " (name (:compounder options "AND")) " ")
              [~@(let [column (gensym) operator (gensym) criteria (gensym)]
                   (for [[column operator criteria noquote] select-filters]
                     `(str ~(name column)
                           " " ~(name operator) " "
                           ~(if noquote
                              criteria
                              `(str "'" ~criteria "'")))))])))
        ~(if-let [to-append (:append options)]
           (str " " to-append)))))))

(defsfcommand update-records check-results [conn sobjects]
  (.update conn sobjects))

(defmacro update [conn class update-maps]
  `(update-records
    ~conn
    (sobject-array
     (map #(create-sf-object (new ~class) %) ~update-maps))))

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
            (string/capitalize (first (name k)))
            (rest (name k)))
     (to-array [v])))
  obj)

(def contact-fields
  [:firstName :lastName :email :phone
   :userName__c :password__c
   :mailingStreet :mailingCity :mailingState :mailingPostalCode
   :mailingCountry :docPhoto_Mail_List__c])

(def user-fields (conj contact-fields :id :name))

(defn create-contact [conn contact-data-map]
  (create
   conn
   (sobject-array
    [(create-sf-object
      (Contact.)
      (select-keys
       contact-data-map
       contact-fields
       ))])))

(defn create-application [conn application-map]
  (-?> (create
        conn
        (sobject-array
         [(create-sf-object
           (Exhibit_Application__c.)
           (select-keys
            application-map
            [:statementRich__c :title__c :biography__c :website__c
             :contact__c :exhibit__c :submission_Status__c :referredby__c]))]))
       first
       .getId))

(defn create-image [conn image-map]
  (-?> (create
        conn
        (sobject-array
         [(create-sf-object
           (Image__c.)
           (select-keys
            image-map
            [:caption__c :exhibit_application__c :filename__c
             :mime_type__c :order__c :url__c]))]))
       first
       .getId))

(defn delete-ids [conn ids]
  "delete passed in ids"
  (let [delete-results (.delete conn (into-array String ids))]
    (if (not-every? #(.isSuccess %) delete-results)
      (throw (IllegalStateException.
              (str (find-first #(not (.isSuccess %)) delete-results))))
      delete-results)))

(defn delete-images-for-application [conn application-id]
  "delete all images associated with an application"
  (delete-ids
   conn
   (map
    :id
    (query conn image__c [id] [[exhibit_application__c = application-id]]))))

(defn delete-image [conn image-id]
  (delete-ids conn [image-id]))

(defn update-application-captions [conn caption-maps]
  (update conn Image__c (map #(select-keys % [:id :caption__c]) caption-maps)))

(defn update-image-order [conn image-maps]
  (update conn Image__c (map #(select-keys % [:id :order__c]) image-maps)))

(defn update-application-status [conn application-id status]
  (update conn Exhibit_Application__c [{:id application-id
                                        :submission_Status__c status}]))

(defn update-user [conn user-map]
  (update conn Contact
          [(select-keys user-map (conj contact-fields :id))]))

(defn update-application [conn application-map]
  (update conn Exhibit_Application__c [application-map]))
