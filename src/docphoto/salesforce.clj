(ns docphoto.salesforce
  (:use [clojure.core.incubator :only (-?>)]
        [docphoto.utils :only (find-first)])
  (:require [clojure.string :as string]
            [docphoto.config :as cfg])
  (:import [com.sforce.ws ConnectorConfig]
           [com.sforce.soap.enterprise
            Connector EnterpriseConnection
            SaveResult DeleteResult
            Field PicklistEntry]
           [com.sforce.soap.enterprise.sobject
            Contact SObject Exhibit_Application__c Image__c
            Exhibit_Application_Review__c]
           [com.sforce.soap.enterprise.fault UnexpectedErrorFault]
           [org.apache.commons.codec.digest DigestUtils]))

(defn connector-config [username password]
  (doto (ConnectorConfig.)
    (.setUsername username)
    (.setPassword password)))

(defn ^EnterpriseConnection connector
  ([config] (Connector/newConnection config))
  ([username password] (connector (connector-config username password))))

(defn disconnect [^EnterpriseConnection conn] (.logout conn))

(defn non-nills [m]
  (select-keys m
               (for [[k v] m :when (not (nil? v))] k)))

(defn ^"[Lcom.sforce.soap.enterprise.sobject.SObject;" sobject-array [coll]
  (into-array SObject coll))

(defn sobject->map [sobj]
  (when-let [result-map (-?> sobj
                             bean
                             (dissoc :fieldsToNull)
                             non-nills)]
    (with-meta result-map {:sobject sobj})))

(defn ^UnexpectedErrorFault unexpected-error-fault-cause
  "Given an exception, find the UnexpectedErrorFault or nil"
  [^Exception e]
  (and e (if (instance? UnexpectedErrorFault e)
           e
           (recur (.getCause e)))))

(defn error-code
  "Given a salesforce error fault, return the error code as a string"
  [^UnexpectedErrorFault e]
  (.getLocalPart (.getFaultCode e)))

(defn rate-limit-exceeded?
  "Given an exception, return whether it is a rate limit exception"
  [e]
  (when-let [sf-fault (unexpected-error-fault-cause e)]
    (= (error-code sf-fault) "REQUEST_LIMIT_EXCEEDED")))

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
                  (if (not-every? #(.isSuccess ^SaveResult %) results#)
                    (throw (IllegalStateException.
                            (str (find-first #(not (.isSuccess ^SaveResult %)) results#))))
                    results#))
               body)
            (catch Exception e#
              (if-let [unexpected-error-fault# (unexpected-error-fault-cause e#)]
                (if (= "INVALID_SESSION_ID" (error-code unexpected-error-fault#))
                  (let [cfg# (.getConfig conn#)
                        login-result# (.login conn#
                                              (.getUsername cfg#)
                                              (.getPassword cfg#))]
                    (.setSessionHeader conn# (.getSessionId login-result#))
                    ;; retry function
                    (apply ~fname ~bindings))
                  (throw e#))
                (throw e#))))))))

(defsfcommand query-records [^EnterpriseConnection conn ^String query]
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

(defsfcommand update-records check-results [^EnterpriseConnection conn sobjects]
  (.update conn sobjects))

(defmacro update [conn class update-maps]
  `(update-records
    ~conn
    (sobject-array
     (map #(create-sf-object (new ~class) %) ~update-maps))))

(defsfcommand create check-results [^EnterpriseConnection conn sobjects]
  (.create conn sobjects))

(defn picklist-field-metadata
  "Given the name of an object and field, return a seq of [label value] from the picklist"
  [^EnterpriseConnection conn object-name field-name]
  (when-let [object-description (.describeSObject conn (name object-name))]
    (let [fields (.getFields object-description)
          field-name (string/lower-case (name field-name))]
      (when-let [field-entries (seq (filter #(= (string/lower-case (.getName ^Field %)) field-name)
                                            fields))]
        (let [^Field field (first field-entries)]
          (when-let [picklist-entries (.getPicklistValues field)]
            (for [^PicklistEntry picklist-entry picklist-entries]
              [(.getLabel picklist-entry) (.getValue picklist-entry)])))))))

(defn create-sf-object [obj data-map]
  (letfn [(field-name [k]
            (apply str
                   (string/capitalize (first (name k)))
                   (rest (name k))))]
    (doseq [[k v] data-map]
      (clojure.lang.Reflector/invokeInstanceMethod
       obj
       (str "set" (field-name k))
       (to-array [v])))
    (let [fields-to-null (keep (fn [[k v]]
                                 (when (nil? v) (field-name k))) data-map)]
      (when (seq fields-to-null)
        (.setFieldsToNull ^SObject obj (into-array String fields-to-null))))
    obj))

(def contact-fields
  [:firstName :lastName :email :phone
   :userName__c :password__c
   :mailingStreet :mailingCity :mailingState :mailingPostalCode
   :mailingCountry :docPhoto_Mail_List__c])

(def user-fields (conj contact-fields :id :name))

(def review-fields [:comments__c :rating__c :review_Stage__c :status__c])

(defmacro defcreate
  "Helper to create salesforce object creation functions. Some functions need to automatically add an owner, others don't. the add-owner? symbol distinguishes the 2."
  [fn-name add-owner? bindings class-instance-form key-form]
  (assert (symbol? add-owner?))
  `(defn ~fn-name [conn# ~@bindings]
     (when-let [result# (-?>
                         (create
                          conn#
                          (sobject-array
                           [(create-sf-object
                             ~class-instance-form
                             ~(if (and (= add-owner? 'add-owner)
                                       cfg/owner-id)
                                `(merge {:ownerId ~cfg/owner-id}
                                        ~key-form)
                                key-form))]))
                         first)]
       (.getId ^SaveResult result#))))

(defn limit-string [^String s n]
  (when s
    (if (< (count s) n)
      s
      (.substring s 0 n))))

(defn limit-map-field [m k n]
  (if (contains? m k)
    (update-in m [k] limit-string n)
    m))

(defn prepare-contact-fields [contact-data-map]
  (-> contact-data-map
      (limit-map-field :mailingState 20)
      (limit-map-field :mailingPostalCode 20)))

(defn prepare-application-fields [app]
  (-> app
      (limit-map-field :multimedia_Link__c 255)
      (limit-map-field :additional_language_proficiency__c 255)))

(defcreate create-contact add-owner [contact-data-map]
  (Contact.)
  (prepare-contact-fields
   (select-keys
    contact-data-map
    contact-fields)))

(defcreate create-application add-owner [application-map]
  (Exhibit_Application__c.)
  (prepare-application-fields
   (select-keys
    application-map
    [:statementRich__c :title__c :biography__c :website__c :narrative__c
     :contact__c :exhibit__c :submission_Status__c :referredby__c
     :multimedia_Link__c :cover_Page__c
     :focus_Region__c :focus_Country_Single_Select__c
     :english_language_proficiency__c :russian_language_proficiency__c
     :additional_language_proficiency__c
     :process_Description__c])))

(defcreate create-image no-owner [image-map]
  (Image__c.)
  (select-keys
   image-map
   [:caption__c :exhibit_application__c :filename__c
    :mime_type__c :order__c :url__c]))

(defcreate create-review no-owner [review-map]
  (Exhibit_Application_Review__c.)
  (select-keys review-map (conj review-fields :contact__c :exhibit_Application__c)))

(defn delete-ids [^EnterpriseConnection conn ids]
  "delete passed in ids"
  (let [delete-results (.delete conn (into-array String ids))]
    (if (not-every? #(.isSuccess ^DeleteResult %) delete-results)
      (throw (IllegalStateException.
              (str (find-first #(not (.isSuccess ^DeleteResult %)) delete-results))))
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

(defn update-application-status [conn application-id status]
  (update conn Exhibit_Application__c [{:id application-id
                                        :submission_Status__c status}]))

(defn update-user [conn user-map]
  (update conn Contact
          [(prepare-contact-fields
            (select-keys user-map (conj contact-fields :id)))]))

(defn update-application [conn application-map]
  (update conn Exhibit_Application__c
          [(prepare-application-fields application-map)]))

(defn normalize-image-map
  "update image map to allow for salesforce update, ie ensure length of captions is valid"
  [image-map]
  (limit-map-field image-map :caption__c 1500))

(defn update-images [conn image-maps]
  (update conn Image__c
          (map #(select-keys % [:id :order__c :caption__c])
               (map normalize-image-map image-maps))))

(defn update-review [conn review-map]
  (update conn Exhibit_Application_Review__c
          [(select-keys review-map (conj review-fields :id))]))
