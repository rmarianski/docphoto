(ns docphoto.core
  (:use [compojure.core :only (defroutes GET POST ANY routes routing context)]
        [compojure.handler :only (api)]
        [compojure.response :only (render)]
        [ring.middleware.multipart-params :only (wrap-multipart-params)]
        [ring.middleware.file-info :only (wrap-file-info)]
        [hiccup.util :only (escape-html)]
        [hiccup.core :only (html)]
        [hiccup.page :only (xhtml include-css include-js)]
        [hiccup.element :only (javascript-tag)]
        [ring.adapter.jetty-servlet :only (run-jetty)]
        [ring.util.response :only (redirect resource-response)]
        [clojure.core.incubator :only (-?> -?>> dissoc-in)]
        [clojure.java.io :only (copy file input-stream output-stream resource)]
        [docphoto.utils :only (defn-debug-memo md5 multipart-form?
                                send-message onpost when-logged-in dbg
                                not-empty-and-ascii?)]
        [docphoto.form :only (defformpage came-from-field
                               req-textfield textfield req-password
                               english-only-textfield make-field-label-mapping)])
  (:require [compojure.route :as route]
            [docphoto.salesforce :as sf]
            [docphoto.persist :as persist]
            [docphoto.image :as image]
            [docphoto.config :as cfg]
            [docphoto.session :as session]
            [docphoto.form :as form]
            [docphoto.i18n :as i18n]
            [docphoto.pdf :as pdf]
            [clojure.string :as string]
            [clojure.set]
            [docphoto.guidelines :as guidelines]
            [hiccup.page :as ph]
            [hiccup.util :as hu]
            [hiccup.element :as he]
            [ring.middleware.stacktrace :as stacktrace]
            [ring.util.codec :as ring-codec]
            [decline.core :as decline]
            [clojure-csv.core :as csv]
            ;[swank.swank]
            )
  (:import [java.io File PipedInputStream PipedOutputStream OutputStream]
           [java.util.zip ZipOutputStream ZipEntry]
           java.util.Calendar
           [javax.servlet.http HttpSession]))

;; global salesforce connection
(defonce conn nil)

(def ^:dynamic *request* nil)

(defn connect->salesforce
  "connect global connection object to salesforce"
  [username password token]
  (def conn (sf/connector username (str password token))))

(defn connect-cfgmap-salesforce
  "take a connection config and use it to connect to salesforce"
  [cfgmap]
  (let [{:keys [user pass token]} cfgmap]
    (if (and user pass token)
      (connect->salesforce user pass token)
      (throw (IllegalArgumentException. "invalid cfgmap")))))

(defn connect-to-dev []
  (connect-cfgmap-salesforce cfg/conn-dev))

(defn connect-to-prod []
  (connect-cfgmap-salesforce cfg/conn-prod))

(defmacro admin? [user]
  (if cfg/debug true `(cfg/admins (:userName__c ~user))))

(defn- tweak-application-result
  "convert application query responses to have the exhibit value be a map"
  [application]
  (update-in application [:exhibit__r] sf/sobject->map))

(defn- tweak-image-result
  "convert image query responses to have application/exhibit values to be maps"
  [image]
  (->
   (update-in image [:exhibit_application__r] sf/sobject->map)
   (update-in [:exhibit_application__r :exhibit__r] sf/sobject->map)))

(defn- tweak-review-request-result
  "convert review requests responses to have an application map"
  [review-request]
  (update-in review-request [:exhibit_Application__r] sf/sobject->map))

(def no-cache-get (constantly nil))
(defn no-cache-set [cache args query-results] query-results)

(defmacro cachinate [cache-key-fn & body]
  `(let [cache-key-fn# ~cache-key-fn
         ~'cache-get (fn [cache# args#]
                       (get-in cache# (cache-key-fn# args#)))
         ~'cache-set (fn [cache# args# result#]
                       (session/set-cache
                        *request*
                        (assoc-in cache# (cache-key-fn# args#) result#))
                       result#)]
     ~@body))

(defn cache-under [cache-key] (fn [args] [cache-key args]))

(defn find-cache []
  (when *request*
    (or (session/get-cache *request*) {})))

(defn cache-clear [& cache-keys]
  (when-let [cache (find-cache)]
    (session/set-cache *request* (dissoc-in cache cache-keys))))

(defmacro defquery
  "Generate a call to sf/query returning multiple results."
  [fn-name args cache-get cache-set query-params & [alter-query-fn]]
  (let [alter-query-fn (eval (or alter-query-fn identity))
        sfquery (alter-query-fn `(sf/query ~'conn ~@query-params))]
    `(defn ~fn-name ~args
       ;; if we have a *request* bound we can access cache
       ;; functionality
       (if-let [cache# (find-cache)]
         (or (~cache-get cache# ~args)
             (~cache-set cache# ~args ~sfquery))
         ~sfquery))))

(defmacro defquery-single
  "Generate a call to sf/query returning a single element."
  [fn-name args cache-get cache-set query-params]
  `(defquery ~fn-name ~args
     ~cache-get ~cache-set
     ~query-params
     ~(fn [form] `(first ~form))))

;; the password passed in should be the hash
(defquery-single query-user-by-credentials [username password]
  no-cache-get no-cache-set
  (contact sf/user-fields [[username__c = username]
                           [password__c = password]]))

;; the password passed in should be the hash
(defquery-single query-user-by-credentials-with-userid [user-id password]
  no-cache-get no-cache-set
  (contact sf/user-fields [[id = user-id]
                           [password__c = password]]))

(defquery-single query-user-by-email [email]
  no-cache-get no-cache-set
  (contact [id] [[email = email]]))

(defquery-single query-user-by-id [id]
  no-cache-get no-cache-set
  (contact sf/user-fields [[id = id]]))

(defquery-single query-user-by-username [username]
  no-cache-get no-cache-set
  (contact sf/user-fields [[userName__c = username]]))

(defquery query-exhibits []
  no-cache-get no-cache-set
  (exhibit__c [id name slug__c application_start_date__c description__c]
              [[closed__c = false noquote]]
              :append "order by application_start_date__c asc"))

(defquery-single query-latest-exhibit []
  no-cache-get no-cache-set
  (exhibit__c [id name slug__c application_start_date__c description__c]
              [[closed__c = false noquote]]
              :append "order by application_start_date__c desc limit 1"))

(def mw21-or-prodgrant2013
  (comp #{:mw21 :prodgrant2013} keyword :slug__c :exhibit__r))

(cachinate (cache-under :applications)
           (defquery query-applications [userid]
             cache-get cache-set
             (exhibit_application__c
              [id title__c exhibit__r.name exhibit__r.slug__c
               createdDate lastModifiedDate
               submission_Status__c referredby__c]
              [[exhibit_application__c.contact__r.id = userid]]
              :append "order by lastModifiedDate desc")
             (fn [form] `(filter
                         mw21-or-prodgrant2013
                         (map tweak-application-result ~form)))))

;; used for cleaning up local disk, so only app ids are returned
(defquery query-applications-for-exhibit [exhibit-slug]
  no-cache-get no-cache-set
  (exhibit_application__c
   [id]
   [[exhibit__r.slug__c = exhibit-slug]]))

(cachinate (cache-under :exhibit)
           (defquery-single query-exhibit [exhibit-slug]
             cache-get cache-set
             (exhibit__c [id name description__c slug__c closed__c]
                         [[slug__c = exhibit-slug]])))

(cachinate (cache-under :application)
           (defquery query-application
             [app-id]
             cache-get cache-set
             (exhibit_application__c
              [id biography__c title__c website__c statementRich__c contact__c
               submission_Status__c narrative__c multimedia_Link__c cover_Page__c
               exhibit__r.name exhibit__r.slug__c exhibit__r.closed__c
               focus_Country_Single_Select__c focus_Region__c referredby__c
               english_language_proficiency__c russian_language_proficiency__c
               additional_language_proficiency__c]
              [[id = app-id]])
             (fn [form] `(-?> ~form first tweak-application-result))))

(cachinate (cache-under :image)
           (defquery query-image [image-id]
             cache-get cache-set
             (image__c
              [id caption__c filename__c mime_type__c order__c
               exhibit_application__r.id
               exhibit_application__r.exhibit__r.slug__c
               exhibit_application__r.contact__c]
              [[id = image-id]])
             (fn [form] `(-?> ~form first tweak-image-result))))

(cachinate (cache-under :images)
           (defquery query-images [application-id]
             cache-get cache-set
             (image__c [id caption__c order__c filename__c mime_type__c]
                       [[exhibit_application__c = application-id]]
                       :append "order by order__c")))

;; filter passed in images to those that current user can view
(defquery query-allowed-images [user image-ids]
  no-cache-get no-cache-set
  (image__c [id exhibit_application__r.contact__c]
            [[id IN (str "("
                         (string/join ", "
                                      (map #(str "'" % "'") image-ids))
                         ")") noquote]])
  (fn [form]
    `(map :id
          (filter
           #(or (admin? ~'user)
                (= (:id ~'user)
                   (:contact__c (:exhibit_application__r
                                 (tweak-image-result %)))))
           ~form))))

(cachinate (cache-under :review-request)
           (defquery-single query-review-request [review-request-id]
             cache-get cache-set
             (exhibit_review_request__c
              [id exhibit_application__c reviewer__c review_stage__c]
              [[id = review-request-id]])))

(cachinate (cache-under :review-requests)
           (defquery query-review-requests [user-id application-id]
             cache-get cache-set
             (exhibit_review_request__c
              [id exhibit_application__c reviewer__c review_stage__c]
              [[reviewer__c = user-id]
               [exhibit_application__c = application-id]])))

(cachinate (cache-under :review-request-for-user)
           (defquery query-review-requests-for-user [user-id]
             cache-get cache-set
             (exhibit_review_request__c
              [id exhibit_application__c reviewer__c review_stage__c
               exhibit_application__r.title__c]
              [[reviewer__c = user-id]]
              :append "order by createdDate")
             (fn [form] `(map tweak-review-request-result ~form))))

(cachinate (cache-under :reviews-final)
           (defquery query-reviews-for-user-that-are-final [user-id]
             cache-get cache-set
             (exhibit_application_review__c
              [id exhibit_application__c contact__c
               comments__c rating__c review_stage__c status__c]
              [[contact__c = user-id]
               [status__c = "Final"]])))

(cachinate (cache-under :review)
           (defquery-single query-review [user-id application-id]
             cache-get cache-set
             (exhibit_application_review__c
              [id exhibit_application__c contact__c
               comments__c rating__c review_stage__c status__c]
              [[contact__c = user-id]
               [exhibit_application__c = application-id]])))

;; picklist values for application
(defn-debug-memo picklist-application-field-metadata [field-name]
  (sf/picklist-field-metadata conn :exhibit_application__c field-name))

;; select field whose source is an application picklist
(defmacro salesforce-picklist-field [field-name field-label]
  `(fn [request# field# params# errors#]
     (let [field-values# (picklist-application-field-metadata ~field-name)]
       (field#
        :select {} ~field-name
        {:label (i18n/translate ~field-label)
         :opts (cons [""]
                     (for [[label# value#] field-values#]
                       [label# value#]))}))))

(defn- list-all-editor-css-files []
  "convenience function to list all google editor css files to include"
  (letfn [(css? [^String filename] (.endsWith filename ".css"))
          (files-in [path]
            (filter css?
             (map #(str "/public/css/" path "/" (.getName ^File %))
                  (.listFiles
                   (file "./resources/public/css/" path)))))]
    (concat (files-in "google")
            (files-in "google/editor")
            ["/public/css/docphoto.css"])))

(defmacro editor-css []
  (vec (list-all-editor-css-files)))

(defn resource-version-string []
  (let [c (Calendar/getInstance)]
    (apply str (map #(.get c %)
                    [Calendar/YEAR Calendar/DAY_OF_YEAR]))))

(defmacro theme-css [editor-css?]
  (if cfg/debug-css
    (let [debug-css-files ["/public/css/google/common.css"
                           "/public/css/uni-form.css"
                           "/public/css/docphoto.css"]]
      `(if ~editor-css?
         ~(vec
           (concat debug-css-files
                   (editor-css)))
         ~debug-css-files))
    [(str "/public/css/docphoto-min-" (resource-version-string) ".css")]))

(defmacro theme-js [include-upload-js?]
  (if cfg/debug-js
    (let [debug-js-file "http://localhost:9810/compile?id=docphoto"]
      `(apply
        include-js
        (if ~include-upload-js?
          ["/public/js/plupload/js/plupload.full.js" ~debug-js-file]
          [~debug-js-file])))
    `(include-js (str "/public/js/docphoto-min-" (resource-version-string) ".js"))))

(defn host-header
  "parse the host header differently based on whether we are proxied to or not"
  [request]
  (if cfg/proxied?
    ((:headers request) "x-forwarded-host")
    (:server-name request)))

(defn absolute-link [request url]
  (let [[host port] (if cfg/proxied?
                      [((:headers request) "x-forwarded-host") 80]
                      [(:server-name request) (:server-port request)])]

    (str (subs (str (:scheme request)) 1) "://" host
         (when-not (= 80 port) (str ":" port))
         url)))

(defmacro deflink
  "Generate a string link from the parts. They are joined together with str"
  [fn-name args & uri-parts]
  `(defn ~fn-name ~args
     (str "/" ~@(interpose "/" uri-parts))))

(defmacro deflinks [& deflink-specs]
  `(do ~@(for [spec deflink-specs]
           `(deflink ~@spec))))

(defmacro defapplink
  "Create an application link function using conventions."
  [application-slug]
  (let [slug (name application-slug)
        fn-name (str "application-" slug "-link")]
    `(deflink ~(symbol fn-name) [application-id#]
       "application" application-id# ~slug)))

(defmacro defapplinks [& application-slugs]
  `(do ~@(for [slug application-slugs]
           `(defapplink ~slug))))

(defapplinks upload submit success update review)

(deflinks
  (application-link [application-id] "application" application-id)
  (user-applications-link [username] "user" "applications" (ring-codec/url-encode username))
  (cv-link [application-id] "cv" application-id)
  (profile-update-link [user-id] "profile" user-id)
  (image-link [image-id scale filename] "image" image-id scale (ring-codec/url-encode filename))
  (exhibits-link [] "exhibit/")
  (exhibit-link [exhibit-slug] "exhibit" exhibit-slug)
  (exhibit-apply-link [exhibit-slug] "exhibit" exhibit-slug "apply")
  (forgot-link [] "password" "forgot")
  (reset-request-link [] "password" "request-reset")
  (reset-password-link [] "password" "reset")
  (images-update-link [application-id] "application" application-id "update-images")
  (image-delete-link [image-id] "image" image-id "delete")
  (admin-password-reset-link [] "admin" "password-reset")
  (admin-download-link [] "admin" "download")
  (admin-login-as-link [] "admin" "login-as")
  (admin-export-captions-link [] "admin" "export" "captions")
  (admin-export-pdf-link [] "admin" "export" "pdf")
  (admin-create-vetter-link [] "admin" "create-vetter-account")
  (review-request-link [review-request-id] "review-request" review-request-id)
  (logout-link [] "logout")
  (register-link [] "register")
  (profile-reset-password-link [user-id] "profile" user-id "password"))

(defn switch-language-link [lang came-from]
  (str "/language/" lang "/?came-from=" (ring-codec/url-encode came-from)))

(defn login-link [& [came-from]]
  (str "/login"
       (when came-from (str "?came-from=" (ring-codec/url-encode came-from)))))

(defn find-remaining-review-requests [review-requests final-reviews]
  (let [s (set (map :exhibit_Application__c final-reviews))]
    (remove #(s (:exhibit_Application__c %)) review-requests)))

(defn sidebar-snippet [request]
  (let [user (session/get-user request)]
    (list
     [:div#login-logout
      (if user
        [:a {:href (logout-link) :title (str (i18n/translate :logout) " " (:userName__c user))}
         (i18n/translate :logout)]
        (list
         [:a {:href (login-link)} (i18n/translate :login)]
         " | "
         [:a {:href (register-link)} (i18n/translate :register)]))]
     (when user
       (list
        [:div#update-profile
         (he/link-to (profile-update-link (:id user)) (i18n/translate :update-profile))]
        (let [apps (query-applications (:id user))]
          (when (not-empty apps)
            [:div#applications-list
             [:h2 (i18n/translate :applications)]
             [:ul
              (for [app apps]
                [:li
                 [:a {:href (application-submit-link (:id app))}
                  (:title__c app)]])]]))
        (let [review-requests (query-review-requests-for-user (:id user))
              final-reviews (query-reviews-for-user-that-are-final (:id user))
              remaining-review-requests (find-remaining-review-requests
                                         review-requests final-reviews)]
          (when (not-empty remaining-review-requests)
            [:div#reviews-list
            [:h2 "Reviews"]
            [:ul
             (for [review-request remaining-review-requests]
               [:li
                [:a {:href (review-request-link (:id review-request))}
                 (:title__c (:exhibit_Application__r review-request))]])]]))
        (when (admin? user)
          [:div#admin
           [:h2 "Admin"]
           [:ul
            [:li (he/link-to (admin-password-reset-link) "Reset User Password")]
            [:li (he/link-to (admin-create-vetter-link) "Create Vetter Account")]
            [:li (he/link-to (admin-login-as-link) "Login As Other User")]
            [:li (he/link-to (admin-download-link) "Download Application Images")]
            [:li (he/link-to (admin-export-captions-link) "Export Image Captions")]
            [:li (he/link-to (admin-export-pdf-link) "Export Application Pdf")]]]))))))

(defmacro when-multiple-languages [& body]
  (cond
   (= true cfg/multiple-languages) `(do ~@body)
   (nil? cfg/multiple-languages) nil
   :else `(when (= ((:headers ~'request) "x-forwarded-host") ~cfg/multiple-languages)
            ~@body)))

(defn heading-apply-text
  "Display the appropriate heading text based on the domain. This is hard coded here."
  [request]
  (let [host (host-header request)]
    (if (= host "docphoto.soros.org")
      "Production Grant 2013"
      "Moving Walls 21")))

(defn layout [request options body]
  (xhtml
   [:head
    [:meta {:http-equiv "content-type" :content "text/html; charset=utf-8"}]
    [:meta {:charset "utf-8"}]
    [:title (if (string? options) options (:title options "Docphoto"))]
    (apply include-css (theme-css (:include-editor-css options)))]
   [:body
    [:div#wrapper
     [:div#header
      [:div#osf-logo (he/image "/public/osf-logo.png"
                               "Open Society Foundations")]
      (when-multiple-languages
       [:div#language
        [:ul
         (let [came-from (:uri request)
               current-language (or (session/get-language request) :en)]
           (for [[text lang] [["English" "en"] ["Русский" "ru"]]]
             [:li
              (if (= (keyword lang) current-language)
                text
                (he/link-to
                 (switch-language-link lang came-from) text))]))]])
      [:div#heading-section
                                        ;[:div#contact (he/link-to "mailto:docphoto@sorosny.org" "Contact Us")]
       [:h1#heading (he/link-to
                     "/"
                     (str (heading-apply-text request) ": " (i18n/translate :apply-online)))]]]
     [:div#page
      [:div#content body]
      (when (:display-sidebar options true)
        [:div#sidebar
         (sidebar-snippet request)])]
     [:div#footer
      [:p "&copy; 2013 Open Society Foundations. All rights reserved."]]]
    (theme-js (:include-upload-js options))
    (when (:review-js options)
      (include-js
       "http://ajax.googleapis.com/ajax/libs/jquery/1.7/jquery.min.js"
       "/public/js/galleria-1.2.7.min.js"))
    (if-let [js (:js-script options)]
      (javascript-tag js))]))

(defn- layout-form
  "helper for defview macro to generate layout form"
  [options body]
  `(layout
    ~'request
    ~(cond (string? options) {:title options}
           (map? options) options
           :else (throw (IllegalArgumentException.
                         (str "Unknown layout options: " options))))
    (html ~body)))

;; if this gets moved to utils, will need to separate layout stuff too
;; due to cyclical imports
(defmacro defview
  "Convenience macro to generate a GET view that is laid out normally. Injects 'request' anaphora."
  ([fn-name options body] `(defview ~fn-name [] ~options ~body))
  ([fn-name args options body]
     `(defn ~fn-name [~'request ~@args]
        ~(if (and (map? options) (:logged-in options))
           `(when-logged-in ~(layout-form options body))
           (layout-form options body)))))

(defn home-view
  "Redirect to the appropriate competition landing page. Host logic hard coded here too."
  [request]
  (redirect
   (str "/"
        (if (= (host-header request) "docphoto.soros.org")
          "2013"
          "21"))))

(defview userinfo-view {:title "User Info View" :logged-in true}
  [:dl
   (for [[k v] (.getAttribute ^HttpSession (:session request) "user")]
     (list [:dt k] [:dd v]))])

(defn register [register-map]
  (sf/create-contact conn register-map))

(defn create-application [exhibit-slug application-map]
  (let [[filename tempfile] ((juxt (comp persist/safe-filename :filename)
                                   :tempfile) (:cv application-map))
        application-id (sf/create-application
                        conn (dissoc (assoc application-map
                                       :submission_Status__c "Draft")
                                     :cv))]
    (persist/persist-cv
     tempfile exhibit-slug application-id filename)
    application-id))

(defn create-image [image-map]
  (sf/create-image conn image-map))

(defn login [request user]
 (session/save-user request user))

(defn logout [request]
  (session/delete request))

;; move to form maybe?
(defn error-map->errors-list [errors-map field-name->field-label]
  (for [[field-name error-msg] errors-map
        :let [field-label (field-name->field-label field-name)]
        :when field-label]
    {:label field-label
     :msg error-msg}))

(defn display-error-summary [errors-map field->label]
  (when (seq errors-map)
    (when-let [errors-list (error-map->errors-list errors-map field->label)]
      [:div.form-errors
       [:p (i18n/translate :there-were-errors)]
       [:ul
        (for [{:keys [label msg]} errors-list]
          [:li
           (str (i18n/translate label) ": ")
           [:span (i18n/translate msg)]])]])))

(defformpage login-view []
  [(req-textfield :userName__c :username)
   (req-password :password__c :password)
   {:custom came-from-field}]
  (layout
   request
   {}
   (list
    [:form.uniForm {:method :post :action (login-link)}
     [:h2 (i18n/translate :login)]
     (display-error-summary errors field->label)
     [:fieldset
      (when-let [user (session/get-user request)]
        [:p ((i18n/translate :already-logged-in) (:name user))])
      (render-fields request params errors)
      [:input {:type :submit :value (i18n/translate :login)}]]]
    [:div#login-notes
     [:p.note
      ((i18n/translate :no-account-register) (register-link))]
     [:p.note
      ((i18n/translate :forgot-your-password-reset) (forgot-link))]]))
  (if-let [user (query-user-by-credentials (:userName__c params) (md5 (:password__c params)))]
    (do (login request user)
        (redirect (if-let [^String came-from (:came-from params)]
                    (if (or (.endsWith came-from "/login")
                            (.endsWith came-from "/login/")) "/" came-from)
                    "/")))
    (render-form params {:userName__c (i18n/translate :invalid-credentials)})))

(defn logout-view [request] (logout request) (redirect (login-link)))

(defn not-found-view [request]
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout
          request
          {:title "404 Page not found"}
          [:div
           [:h2 "Page not found"]
           [:p "We could not find the page you are looking for."]])})

(defn- user-update-mailinglist-value
  "ensure a :docPhoto_Mail_List__c key exists and is set to a boolean value"
  [m]
  (update-in m [:docPhoto_Mail_List__c] boolean))

(defmacro when-profile-update-access [user-id & body]
  `(when-logged-in
    (when (or (admin? ~'user)
              (= (:id ~'user) ~user-id))
      ~@body)))

(defformpage profile-view [user-id]
  [(req-textfield :firstName :first-name)
   (req-textfield :lastName :last-name)
   (req-textfield :email :email)
   (req-textfield :phone :phone)
   (req-textfield :mailingStreet :address)
   (req-textfield :mailingCity :city)
   (req-textfield :mailingState :state)
   (req-textfield :mailingPostalCode :postal-code)
   (req-textfield :mailingCountry :country)
   {:field [:checkbox {} :docPhoto_Mail_List__c
            {:label :subscribe-to-mailinglist}]}
   {:custom came-from-field}]
  (if-let [user (query-user-by-id user-id)]
    (layout
     request
     {}
     [:div
      [:form.uniForm {:method :post :action (profile-update-link user-id)}
       [:h2 (i18n/translate :update-profile)]
       [:p ((i18n/translate :reset-password-text)
            (profile-reset-password-link user-id))]
       (render-fields request (user-update-mailinglist-value
                               (merge user params)) errors)
       [:input {:type :submit :value (i18n/translate :update)}]]])
    (not-found-view))
  (let [user-params (user-update-mailinglist-value
                     (select-keys params sf/contact-fields))
        user-params (merge {:id user-id} user-params)]
    (sf/update-user conn user-params)
    (when (= user-id (:id (session/get-user request)))
      (session/save-user request (query-user-by-id user-id)))
    (redirect (or (:came-from params) "/"))))

(defformpage profile-password-view [user-id]
  [(req-password :old-password :old-password)
   (req-password :new-password :new-password)
   (req-password :new-password2 :new-password)]
  (layout
   request (i18n/translate :update-password)
   [:form.uniForm {:method :post :action (:uri request)}
    [:h2 (i18n/translate :update-password)]
    (render-fields request params errors)
    [:input {:type :submit :value (i18n/translate :update-password)}]])
  (let [{:keys [old-password new-password new-password2]} params]
    (if (not= new-password new-password2)
      (render-form
       params
       {:new-password (i18n/translate :passwords-dont-match)})
      (if (query-user-by-credentials-with-userid user-id (md5 old-password))
        (do
          (sf/update-user conn {:id user-id :password__c (md5 new-password)})
          (redirect "/"))
        (render-form params {:old-password (i18n/translate :invalid-credentials)})))))

(defformpage register-view []
  [(english-only-textfield :userName__c :username)
   (req-password :password__c :password)
   (req-password :password2 :password-again)
   (english-only-textfield :firstName :first-name)
   (english-only-textfield :lastName :last-name)
   (english-only-textfield :email :email)
   (english-only-textfield :phone :phone)
   (english-only-textfield :mailingStreet :address)
   (english-only-textfield :mailingCity :city)
   (english-only-textfield :mailingState :state)
   (english-only-textfield :mailingPostalCode :postal-code)
   (english-only-textfield :mailingCountry :country)
   {:field [:checkbox {} :docPhoto_Mail_List__c
            {:label :subscribe-to-mailinglist}]}]
  (layout
   request
   {}
   [:form.uniForm {:method :post :action (register-link)}
    [:h2 (i18n/translate :register)]
    (display-error-summary errors field->label)
    (render-fields request params errors)
    [:input {:type :submit :value (i18n/translate :register)}]])
  (let [{password1 :password__c password2 :password2
         username :userName__c} params]
    (if (not (= password1 password2))
      (render-form params {:password__c (i18n/translate :passwords-dont-match)})
      (if-let [user (query-user-by-username username)]
        (render-form params {:userName__c (i18n/translate :user-already-exists)})
        (do
          (register (-> (dissoc params :password2)
                        (update-in [:password__c] md5)
                        user-update-mailinglist-value))
          (login request (query-user-by-credentials username (md5 password1)))
          (redirect "/"))))))

(defmacro send-email-reset [request email token]
  (let [reset-link-sym (gensym "resetlink__")]
    `(let [~reset-link-sym (absolute-link ~request
                                          (str (reset-request-link)
                                               "?token=" ~token))]
       ~(if cfg/debug
          `(println "Password sent to:" ~email "with link:" ~reset-link-sym)
          `(send-message
            {:to ~email
             :subject (i18n/translate :password-reset)
             :body ((i18n/translate :password-reset-email) ~reset-link-sym)})))))

(let [generator (java.util.Random.)]
  (defn generate-reset-token [email]
    (str (.nextInt generator))))

(defformpage forgot-password-view []
  [(req-textfield :email :email)]
  (layout
   request
   {:title (i18n/translate :password-reset)}
   [:form.uniForm {:method :post :action (:uri request)}
    [:h2 (i18n/translate :password-reset)]
    [:p (i18n/translate :receive-link-to-reset)]
    (render-fields request params errors)
    [:input {:type :submit :value (i18n/translate :reset)}]])
  (let [email (:email params)]
    (if-let [user (query-user-by-email email)]
      (let [reset-token (generate-reset-token email)]
        (session/save-token request reset-token (:id user))
        (send-email-reset request email reset-token)
        (layout request {:title (i18n/translate :email-sent)}
                [:div
                 [:p (i18n/translate :email-sent-to) (:email params)]]))
      (render-form params {:email (i18n/translate :email-not-found)}))))

(defn reset-request-view [request token]
  (letfn [(reset-failure-page [msg]
            (layout request {:title (i18n/translate :password-reset-failure)}
                    [:div
                     [:h2 (i18n/translate :password-reset-failure)]
                     [:p msg]]))]
    (if-not token
      (reset-failure-page (i18n/translate :no-token-found))
      (if-let [session-token (session/get-token request)]
        (if (= (:token session-token) token)
          (do
            (session/allow-password-reset request (:userid session-token))
            (redirect (reset-password-link)))
          (reset-failure-page (i18n/translate :invalid-token)))
        (reset-failure-page ((i18n/translate :token-expired) (forgot-link)))))))

(defformpage reset-password-view []
  [(req-password :password1 :password)
   (req-password :password2 :password-again)]
  (if-let [user (-?> (session/password-reset-userid request)
                     query-user-by-id)]
    (layout
     request
     {:title (i18n/translate :password-reset)}
     [:form.uniForm {:method :post :action (:uri request)}
      [:h2 (i18n/translate :password-reset)]
      [:p ((i18n/translate :reset-password-for) (:userName__c user))]
      (render-fields request params errors)
      [:input {:type :submit :value (i18n/translate :reset)}]])
    (redirect (forgot-link)))
  (if-let [userid (session/password-reset-userid request)]
    (if (= (:password1 params) (:password2 params))
      (do
        (sf/update-user conn {:id userid
                              :password__c (md5 (:password1 params))})
        (session/remove-allow-password-reset request)
        (redirect (login-link "/")))
      (render-form params {:password1 (i18n/translate :passwords-dont-match)}))))


(defn delete-images-for-application [exhibit-slug application-id]
  (sf/delete-images-for-application conn application-id)
  (persist/delete-images-for-application exhibit-slug application-id))

(defn delete-image [exhibit-slug application-id image-id]
  (sf/delete-image conn image-id)
  (persist/delete-image exhibit-slug application-id image-id))

(defn delete-images [exhibit-slug application-id imageids]
  (sf/delete-ids conn imageids)
  (doseq [imageid imageids]
    (persist/delete-image exhibit-slug application-id imageid)))

(defn delete-application
  ([application] (delete-application (:slug__c (:exhibit__r application))
                                     (:id application)))
  ([exhibit-slug application-id]
     (sf/delete-ids conn [application-id])
     (persist/delete-application exhibit-slug application-id)))

(defn guidelines-keyword [exhibit-slug]
  (keyword (str "guidelines-" exhibit-slug)))

(defn exhibit-guidelines [request exhibit]
  (or (i18n/translate (guidelines-keyword (:slug__c exhibit)))
      (:description__c exhibit)))

(defview exhibit-view [exhibit]
  {:title (:name exhibit)
   :js-script "docphoto.removeAnchorTargets();"}
  [:div#guidelines
   (exhibit-guidelines request exhibit)])

(defn filesize-not-empty [fileobject]
  (pos? (:size fileobject 0)))

(defmacro findout-field []
  {:field [:select {} :referredby__c
           {:label :how-did-you-find-out
            :opts [[""]
                   [:findout-website "Open Society Foundations website"]
                   [:findout-newsletter "Doc Photo Project newsletter"]
                   [:findout-facebook "Facebook"]
                   [:findout-twitter "Twitter"]
                   [:findout-article "Article"]
                   [:findout-friend "Friend"]
                   [:findout-other "Other"]]}]})

(def language-fluency-options
  (map #(vector % (string/capitalize (name %))) [:beginner :intermediate :advanced :fluent]))

(def application-fields

  ;; common fields
  {:cv {:field [:file {} :cv {:label :cv :description :cv-description}]
        :validator {:fn filesize-not-empty :msg :required}}
   :focus-region {:field [:select {} :focus_Region__c
                          {:label :focus-region
                           :opts (map
                                  vector
                                  ["" "Africa, Sub-Saharan" "Asia" "Europe"
                                   "Former Soviet Union / Central Eurasia"
                                   "Latin America" "Middle East / North Africa"
                                   "North America" "Oceana" "Other"])}]}
   :focus-country {:custom (salesforce-picklist-field :focus_Country_Single_Select__c :focus-country)
                   :field [nil nil :focus_Country_Single_Select__c {:label :focus-country}]}

   ;; these are listed here to prevent duplication with the fields
   ;; listed for review

   ;; mw21 fields
   :mw21-project-title (req-textfield :title__c "Project Title")
   :mw21-project-summary {:field [:text-area#coverpage.editor {:style "height: 50px"} :cover_Page__c
                                  {:label "Project Summary"
                                   :description "A one sentence description of the project, including title (if applicable) and main subject/content."}]
                          :validator {:fn not-empty :msg :required}}
   :mw21-project-statement {:field [:text-area#statement {:style "height: 500px" :class "editor max-600"} :statementRich__c
                                    {:label "Project Statement" :description "(600 words maximum) describing the project you would like to exhibit"}]
                            :validator {:fn not-empty :msg :required}}
   :mw21-bio {:field [:text-area#biography {:style "height: 250px" :class "editor max-250"} :biography__c
                      {:label "Short Narrative Bio"
                       :description "(250 words maximum) summarizing your previous work and experience"}]
              :validator {:fn not-empty :msg :required}}
   :mw21-summary-of-engagement {:field [:text-area#summaryEngagement {:style "height: 500px" :class "editor max-600"} :narrative__c
                                        {:label "Summary of your engagement"
                                         :description "(600 words maximum) Please comment on your relationship with the issue or community you photographed. How and why did you begin the project? How long have you  been working on the project? Are there particular methods you  use while working?   What do you hope a viewer will take away from your project?"}]
                                :validator {:fn not-empty :msg :required}}
   :website {:field [:text {} :website__c {:label "Website" :description "Personal Web Site (Optional)"}]}
   :multimedia {:field [:text {} :multimedia_Link__c
                        {:label "Multimedia Link"
                         :description
                         "Moving Walls has the capacity to exhibit multimedia in addition to (but not in place of) the print exhibition. A multimedia sample should be submitted only if it complements or enhances, rather than duplicates, the other submitted materials. The sample will be judged on its ability to present complex issues through compelling multimedia storytelling, and will not negatively impact the print submission. If you are submitting a multimedia piece for consideration, please post the piece on a free public site such as YouTube or Vimeo and include a link. If the piece is longer than five minutes, let us know what start time to begin watching at."}]}

   ;; prodgrant fields
   :pg-project-title {:field [:text {} :title__c {:label :pg-project-title}]
                      :validator {:fn not-empty-and-ascii? :msg :required-english-only}}
   :pg-project-summary {:field [:text-area#coverpage.editor {:style "height: 50px"} :cover_Page__c
                                {:label :pg-project-summary :description :pg-project-summary-description}]
                        :validator {:fn not-empty-and-ascii? :msg :required-english-only}}
   :pg-proposal-narrative {:field [:text-area#narrative.editor {:style "height: 500px"} :narrative__c
                                   {:label :pg-proposal-narrative :description :pg-proposal-narrative-description}]
                           :validator {:fn not-empty :msg :required}}
   :pg-personal-statement {:field [:text-area#personal-statement.editor {:style "height: 500px"} :biography__c
                                   {:label :pg-personal-statement :description :pg-personal-statement-description}]
                           :validator {:fn not-empty :msg :required}}
   :pg-english-language-proficiency {:field [:radio-single-line {:class :inline-radio} :english_language_proficiency__c
                                             {:label :pg-english-language-proficiency :opts language-fluency-options}]
                                     :validator {:fn not-empty :msg :required}}
   :pg-russian-language-proficiency {:field [:radio-single-line {:class :inline-radio} :russian_language_proficiency__c
                                             {:label :pg-russian-language-proficiency :opts language-fluency-options}]
                                     :validator {:fn not-empty :msg :required}}
   :pg-additional-language-proficiency {:field [:text-area {:rows 4} :additional_language_proficiency__c
                                                {:label :pg-additional-language-proficiency
                                                 :description :pg-additional-language-proficiency-description}]}})

(defmulti exhibit-apply-fields (comp keyword :slug__c))

(def mw-fields
  [(application-fields :mw21-project-title)
   (application-fields :mw21-project-summary)
   (application-fields :mw21-project-statement)
   (application-fields :mw21-bio)
   (application-fields :mw21-summary-of-engagement)
   (application-fields :cv)
   (findout-field)
   (application-fields :website)
   (application-fields :multimedia)
   (application-fields :focus-region)
   (application-fields :focus-country)])

(defmethod exhibit-apply-fields :mw20 [exhibit] mw-fields)
(defmethod exhibit-apply-fields :mw21 [exhibit] mw-fields)

(def pg-fields
  [(application-fields :pg-project-title)
   (application-fields :pg-project-summary)
   (application-fields :pg-proposal-narrative)
   (application-fields :pg-personal-statement)
   (application-fields :cv)
   (findout-field)
   (application-fields :focus-region)
   (application-fields :focus-country)])

(defmethod exhibit-apply-fields :prodgrant2012 [exhibit] pg-fields)
(defmethod exhibit-apply-fields :prodgrant2013 [exhibit]
  (into pg-fields [(application-fields :pg-english-language-proficiency)
                   (application-fields :pg-russian-language-proficiency)
                   (application-fields :pg-additional-language-proficiency)]))

(def exhibit-slug-from-application (comp keyword :slug__c :exhibit__r))

(defmulti application-update-fields exhibit-slug-from-application)

(defn make-cv-field-optional
  "The cv field is optional when updating the application"
  [fields]
  (map (fn [field]
         (or
          (when-let [fieldspec (:field field)]
            (when-let [field-name (nth fieldspec 2 nil)]
              (when (= field-name :cv)
                (dissoc field :validator))))
          field))
       fields))

(defn mw-update-fields [application]
  (make-cv-field-optional (exhibit-apply-fields (:exhibit__r application))))

(defmethod application-update-fields :mw20 [application]
  (mw-update-fields application))
(defmethod application-update-fields :mw21 [application]
  (mw-update-fields application))

(defn pg-update-fields [application]
  (make-cv-field-optional (exhibit-apply-fields (:exhibit__r application))))

(defmethod application-update-fields :prodgrant2012 [application]
  (pg-update-fields application))
(defmethod application-update-fields :prodgrant2013 [application]
  (pg-update-fields application))

(defmulti application-review-fields (comp keyword :slug__c :exhibit__r))

(def mw-review-fields
  (map application-fields
       [:mw21-project-title :mw21-project-summary :mw21-project-statement
        :mw21-bio :mw21-summary-of-engagement
        :website :multimedia]))

(defmethod application-review-fields :mw20 [application] mw-fields)
(defmethod application-review-fields :mw21 [application] mw-fields)

(def pg-review-fields
  (map application-fields
       [:pg-project-title :pg-project-summary
        :pg-proposal-narrative :pg-personal-statement]))

(defmethod application-review-fields :prodgrant2012 [application] pg-review-fields)
(defmethod application-review-fields :prodgrant2013 [application]
  (into pg-review-fields [(application-fields :english_language_proficiency__c)
                          (application-fields :russian_language_proficiency__c)
                          (application-fields :additional_language_proficiency__c)]))

(defn exhibit-closed? [exhibit] (:closed__c exhibit))

(defview exhibit-closed-view [exhibit]
  {:title (str (:name exhibit) " Closed")}
  [:div
   [:p "We are no longer accepting applications for " (:name exhibit) "."]
   [:p "If you attempted to submit your application before or on the May 2nd deadline and had trouble with the online application system, please contact Felix Endara at " (he/link-to "mailto:docphoto@sorosny.org" "docphoto@sorosny.org") "."]])

(defn exhibit-apply-view [request exhibit]
  (when-logged-in
   (if (exhibit-closed? exhibit)
     (exhibit-closed-view request exhibit)
     (let [params (:params request)
           fields (exhibit-apply-fields exhibit)
           field->label (make-field-label-mapping fields)
           render-form (fn [params errors]
                         (let [field (form/field-render-fn params errors)]
                           (layout
                            request
                            {:title (str "Apply to " (:name exhibit))
                             :include-editor-css true
                             :js-script (str "docphoto.editor.triggerEditors('"
                                             (i18n/translate :too-many-words)
                                             "');")}
                            [:div
                             [:form.uniForm {:method :post :action (:uri request)
                                             :enctype "multipart/form-data"}
                              [:h2 (str (i18n/translate :apply-to) (:name exhibit))]
                              (display-error-summary errors field->label)
                              [:fieldset
                               [:legend "Apply"]
                               (letfn [(render-field [field-stanza]
                                         (if-let [customfn (:custom field-stanza)]
                                           (customfn request field params errors)
                                           (if-let [fieldspec (:field field-stanza)]
                                             (apply field fieldspec))))]
                                 (map render-field fields))]
                              [:input {:type :submit :value (i18n/translate :proceed-to-upload-images)}]]])))]
       (onpost
        (let [validate (apply
                        decline/validations
                        (keep (fn [fieldspec]
                                (let [{:keys [field validator]} fieldspec
                                      {:keys [fn msg]} validator
                                      [_ _ name] field]
                                  (when-not (some nil? [fn msg name])
                                    (decline/validate-val name fn {name msg}))))
                              fields))]
          (if-let [errors (validate params)]
            (render-form params errors)
            (let [user (session/get-user request)
                  appid (create-application
                         (:slug__c exhibit)
                         (merge params
                                {:contact__c (:id user)
                                 :exhibit__c (:id exhibit)}))]
              (cache-clear :applications)
              (redirect (application-upload-link appid)))))
        (render-form params {}))))))

(defn application-update-view [request application]
  (when-logged-in
   (let [params (:params request)
         exhibit (:exhibit__r application)
         fields (application-update-fields application)
         field->label (make-field-label-mapping fields)
         render-form (fn [params errors]
                       (let [field (form/field-render-fn params errors)]
                         (layout
                          request
                          {:title (str "Update application")
                           :include-editor-css true
                           :js-script (str "docphoto.editor.triggerEditors('"
                                           (i18n/translate :too-many-words)
                                           "');")}
                          [:div
                           [:form.uniForm {:method :post :action (:uri request)
                                           :enctype "multipart/form-data"}
                            (display-error-summary errors field->label)
                            [:fieldset
                             [:legend "Update application"]
                             (letfn [(render-field [field-stanza]
                                       (if-let [customfn (:custom field-stanza)]
                                         (customfn request field params errors)
                                         (if-let [fieldspec (:field field-stanza)]
                                           (apply field fieldspec))))]
                               (map render-field fields))]
                            [:input {:type :submit :value "Update"}]]])))]
     (onpost
      (let [validate (apply
                      decline/validations
                      (keep (fn [fieldspec]
                              (let [{:keys [field validator]} fieldspec
                                    {:keys [fn msg]} validator
                                    [_ _ name] field]
                                (when-not (some nil? [fn msg name])
                                  (decline/validate-val name fn {name msg}))))
                            fields))]
        (if-let [errors (validate params)]
          (render-form (merge application params) errors)
          (let [app-id (:id application)
                normalize-empty-value (fn [m k]
                                        (update-in m [k]
                                                   #(if (empty? %) nil %)))
                app-update-map (-> params
                                   (dissoc :cv :app-id)
                                   (merge {:id app-id})
                                   (normalize-empty-value :focus_Region__c)
                                   (normalize-empty-value :focus_Country_Single_Select__c)
                                   (normalize-empty-value :referredby__c)
                                   (normalize-empty-value :website__c)
                                   (normalize-empty-value :multimedia_Link__c)
                                   (normalize-empty-value :additional_language_proficiency__c))]
            (sf/update-application conn app-update-map)
            (cache-clear :application [app-id])
            ;; title could have changed too so it's safer to clear
            ;; applications list also
            (cache-clear :applications)
            (when-let [cv (:cv params)]
              (let [tempfile (:tempfile cv)
                    filename (persist/safe-filename (:filename cv))
                    size (:size cv)
                    exhibit-slug (:slug__c exhibit)]
                (when (and :cv (not-empty filename) (pos? size))
                  (do
                    (persist/delete-existing-cvs exhibit-slug app-id)
                    (persist/persist-cv tempfile exhibit-slug app-id filename)))))
            (redirect
             (or (:came-from params)
                 (application-submit-link app-id))))))
      (render-form (merge application params) {})))))

(defview app-debug-view [application]
  {:title (:title__c application)}
  (list [:h2 (:title__c application)]
        [:dl
         (for [[k v] (dissoc application :class)]
           (list
            [:dt k]
            [:dd v]))]))

(defn app-view [request application]
  (let [app-id (:id application)
        user (session/get-user request)]
    (redirect
     (if (admin? user)
       (application-review-link app-id)
       (application-submit-link app-id)))))

(defn render-image [request image]
  (list
   [:div.image-container.goog-inline-block
    (he/image (image-link (:id image) "small" (:filename__c image)))]
   [:input {:type :hidden :name :imageids :value (:id image)}]
   [:textarea {:name "captions" :class "image-caption"}
    (or (:caption__c image) "")]
   [:a {:href "#"
        :class "image-delete"} (i18n/translate :delete)]))

(defn render-images [request images]
  [:ul#images-list
   (for [image images]
     [:li (render-image request image)])])

(defview app-upload [application]
  {:title "Upload images"
   :include-upload-js true
   :js-script (format (str "new docphoto.Uploader('plupload', 'pick-files', "
                           "'upload', 'files-list', 'images-list', "
                           "'images-description', 'num-images-error', "
                           "{url: \"%s\", captionRequiredText: '" (i18n/translate :caption-required) "'});")
                      (:uri request))}
  (list
   [:form.uniForm {:method :post :action (:uri request)}
    [:h2 (i18n/translate :upload-images)]
    [:p (i18n/translate :upload-image-amount)]
    [:div#plupload
     [:div#files-list (i18n/translate :upload-no-support)]
     [:a#pick-files {:href "#"} (i18n/translate :upload-select-files)]
     [:p#upload-container {:style "display: none"}
      [:a#upload {:href "#"}
       (i18n/translate :upload)]]]]
   [:div#images
    [:p (i18n/translate :upload-image-limit)]
    [:p (i18n/translate :captions-limited)]
    [:p#images-description {:style "display: none"}
     (i18n/translate :upload-image-reorder)]
    [:form {:method :post :action (images-update-link (:id application))}
     (render-images request (query-images (:id application)))
     [:p#num-images-error.error {:style "display: none"} (i18n/translate :num-images-error)]
     [:input {:type "submit" :value (i18n/translate :save)}]]]))

(defn persist-all-image-scales [^File chunk exhibit-slug application-id image-id]
  "save all necessary image scales for a particular image"
  (persist/ensure-image-path exhibit-slug application-id image-id)
  (let [base-image-path (persist/image-file-path exhibit-slug
                                                 application-id image-id)
        orig-file (file base-image-path "original")]
    (persist/persist-image-chunk chunk exhibit-slug application-id
                                 image-id "original")
    (dorun
     (for [[scale-type [width height]] [["small" [100 100]]
                                        ["large" [600 600]]]]
       (image/scale
        orig-file
        (file base-image-path scale-type)
        width height)))))

(defn normalize-image-filename [^String filename]
  (apply str (filter #(or (Character/isLetterOrDigit ^Character %)
                          (#{\- \_ \.} %))
                     filename)))

(defn app-upload-image [request application]
  ;; cache needs to be cleared before/after because of order fetching
  (cache-clear :images [(:id application)])
  (let [{:keys [filename content-type tempfile]} (:file (:params request))]
    (if (some empty? [filename content-type])
      {:status 400
       :headers {}
       :body "Invalid file"}
      (let [exhibit-slug (:slug__c (:exhibit__r application))
            application-id (:id application)
            order (-> (query-images application-id)
                      count inc double)
            image-id (create-image
                      {:filename__c (normalize-image-filename filename)
                       :mime_type__c content-type
                       :exhibit_application__c application-id
                       :order__c order})]
        (persist-all-image-scales tempfile exhibit-slug application-id image-id)
        (cache-clear :images [application-id])
        (html (render-image request (query-image image-id)))))))

(defn image-view [request image scale-type]
  (let [f (persist/image-file-path
           (-> image :exhibit_application__r :exhibit__r :slug__c)
           (:id (:exhibit_application__r image))
           (:id image)
           scale-type)]
    (if (.exists f)
      {:headers {"Content-Type" (:mime_type__c image)}
       :status 200
       :body f})))

(defn- find-imageids-to-delete
  "convenience function to intersect the image maps to update with all images queried from application"
  [update-maps existing-images]
  (clojure.set/difference
   (set (map :id existing-images))
   (set (map :id update-maps))))

(defn create-image-update-maps [imageids captions]
  (map
   (fn [[imageid caption n]]
     {:id imageid
      :caption__c caption
      :order__c n })
   (map vector imageids captions
        (iterate (comp double inc) 1.0))))

(defn normalize-form-param
  "normalize the form param to a vector"
  [value]
  (cond
   (nil? value) []
   (string? value) [value]
   (vector? value) value
   :else value))

(defn application-update-images-view [request application]
  (if (= :post (:request-method request))
    ;; the :form-params data preserves ordering with multiple keys
    (do
      ;; clear the images cache just to be safe because we are
      ;; potentially updating/deleting images based on ordering
      (cache-clear :images [(:id application)])
      (let [ordered-form-params (:form-params request)
           ;; need to check if these are always lists
           imageids (normalize-form-param (ordered-form-params "imageids"))
           captions (normalize-form-param (ordered-form-params "captions"))
           image-update-maps (create-image-update-maps imageids captions)
           existing-images (query-images (:id application))
           ;; only allow updates for images that user is allowed to see
           ;; since it's tied to the application, we just need to make
           ;; sure that the images are in the existing list
           existing-imageids-set (set (map :id existing-images))
           image-update-maps (filter #(existing-imageids-set (:id %))
                                     image-update-maps)]
       (if (not-empty image-update-maps)
         (sf/update-images conn image-update-maps))
       (let [imageids-to-delete (find-imageids-to-delete image-update-maps existing-images)]
         (if (not-empty imageids-to-delete)
           (delete-images (-> application :exhibit__r :slug__c)
                          (:id application)
                          imageids-to-delete)))
       (cache-clear :images [(:id application)])
       ;; might have to clear :image too ... but we don't actually
       ;; read any data from there save the mimetype which we don't
       ;; ever update
       ;; the image cache is expensive to clear, as it will result in
       ;; an additional query for each image again
       (redirect
        (application-submit-link (:id application)))))))

(defn application-submit-view [request application after-application-submitted]
  (let [app-id (:id application)
        images (query-images app-id)
        errors (seq (concat
                     (when (< (count images) 15)
                       [:not-enough-images])
                     (and (some empty? (map :caption__c images))
                          [:not-all-captions-complete])))]
    (onpost
     (if errors
       (redirect (application-submit-link app-id))
       (and (sf/update-application-status conn app-id "Final")
            (do
              (cache-clear :application [app-id])
              ;; in case they view a page that displays the list
              ;; with final/draft status
              (cache-clear :applications)
              (let [curuser (session/get-user request)]
                (when (= (:id curuser) (:contact__c application))
                  (after-application-submitted (:exhibit__r application)
                                               (:email curuser))))
              (redirect (application-success-link app-id)))))
     (layout
      request
      {:title "Review submission"}
      [:div.application-submit
       [:h2 (i18n/translate :application-review)]
       [:p (i18n/translate :review-application-before-submitting)]
       [:fieldset
        [:legend (i18n/translate :contact-info)]
        (let [curuser (session/get-user request)
              user (if (= (:id curuser) (:contact__c application))
                     curuser
                     (query-user-by-id (:contact__c application)))]
          (list
           [:h2 (:name user)]
           [:p (:email user)]
           [:p (:phone user)]
           [:p
            (:mailingStreet user) [:br]
            (:mailingCity user) ", " (:mailingState user) " "
            (:mailingPostalCode user) [:br]
            (:mailingCountry user)]
           [:p (i18n/translate
                (if (:docPhoto_Mail_List__c user)
                  :subscribed-to-mailing-list
                  :not-subscribed-to-mailing-list))]
           [:a {:href (profile-update-link (:id user))} (i18n/translate :update)]))]
       [:fieldset
        [:legend (i18n/translate :application)]
        [:h2 (:title__c application)]
        [:dl
         (let [display-if-set (fn [k title]
                                (let [x (k application)]
                                  (when (or (not-empty x) (= k :cv))
                                    (list
                                     [:dt (i18n/translate title)]
                                     [:dd
                                      (if (= k :cv)
                                        [:a {:href (cv-link app-id)}
                                         (i18n/translate :cv-download)]
                                        x)]))))
               fields (application-update-fields application)]
           (for [{[_ _ k {title :label}] :field} fields]
             (display-if-set k title)))]
        [:a {:href (application-update-link app-id)} (i18n/translate :update)]]
      [:fieldset
       [:legend (i18n/translate :images)]
       [:ol
        (for [image (query-images app-id)]
          [:li
           [:div.image-container.goog-inline-block
            (he/image (image-link (:id image) "small" (:filename__c image)))]
           [:div.goog-inline-block.image-caption (:caption__c image)]])]
       [:a {:href (application-upload-link app-id)} (i18n/translate :update)]]
       (if errors
         [:div
          [:p (i18n/translate :fix-errors-before-can-submit)]
          [:ul
           (for [error errors]
             [:li (i18n/translate error)])]]
         [:form {:method :post :action (application-submit-link app-id)}
          [:div.submit-button
           (if (= "Final" (:submission_Status__c application))
             [:p (i18n/translate :application-already-submitted)]
             (list
              [:p (i18n/translate :application-submit)]
              [:input {:type "submit"
                       :value (i18n/translate :application-submit-button)}]))]])]))))

(defview application-success-view [application]
  {:title (i18n/translate :submission-thank-you)}
  [:div
   [:h2 (i18n/translate :submission-thank-you)]
   [:p ((i18n/translate (if (= :prodgrant2013 (exhibit-slug-from-application application))
                          :pg-selection-email-notification :mw-selection-email-notification))
        (:email (session/get-user request)))]
   [:p (i18n/translate :view-all-applications)
    [:a {:href (user-applications-link (:userName__c (session/get-user request)))}
     (i18n/translate :applications)] "."]])

(defn forbidden [request]
  {:status 403
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout
          request
          {:title "Forbidden"}
          (list
           [:h2 "Forbidden"]
           [:p "You don't have access to view this page"]))})

(defn application-owner? [user application]
  (= (:id user) (:contact__c application)))

(defn can-view-application? [user application]
  (or (admin? user) (application-owner? user application)))

(defn can-review-request? [user review-request]
  (or (admin? user)
      (and
       (= (:id user) (:reviewer__c review-request))
       (if-let [review (query-review
                        (:id user) (:exhibit_Application__c review-request))]
         (not= (:status__c review) "Final")
         true))))

(defn can-review-application? [user application]
  (or (admin? user)
      (not-empty (query-review-requests (:id user)
                                        (:id application)))))

(defmacro when-admin
  "Checks that a user is an admin before proceeding. Returns appropriate error codes if not. Uses anaphora. Injects 'user' and depends on 'request' in scope."
  [body]
  `(when-logged-in
    (if (admin? ~'user)
      ~body
      (forbidden ~'request))))

(defmacro when-application
  "If an application exists, proceed. Otherwise return not found. Anaphora for 'application'. Expects 'request' symbol in scope"
  [application-id body]
  `(if-let [~'application (query-application ~application-id)]
     ~body
     (not-found-view ~'request)))

(defmacro prepare-application-routes
  "Take care of fetching the application, and checking security. Anaphora: depends on having 'app-id' in context matching and injects 'application' into scope. Expects 'request' symbol in scope."
  [can-view? & app-routes]
  `(fn [~'request]
     (when-application
      ~'app-id
      (when-logged-in
       (if (~can-view? (:uri ~'request) ~'user ~'application)
         (routing ~'request ~@app-routes)
         (forbidden ~'request))))))

(defn lookup-exhibit-slug [exhibit-spec]
  (case exhibit-spec
    "21" "mw21"
    "2013" "prodgrant2013"
    nil))

(defmacro prepare-exhibit-routes
  "Fetch exhibit, and inject 'exhibit' through anaphora. Expects 'exhibit-id' to exist in scope."
  [& exhibit-routes]
  `(fn [~'request]
     (when-let [exhibit-slug# (lookup-exhibit-slug ~'exhibit-id)]
      (when-let [~'exhibit (query-exhibit exhibit-slug#)]
        (routing ~'request ~@exhibit-routes)))))

(defn local-request? [request]
  (= "127.0.0.1" (:remote-addr request)))

(defmacro prepare-image-routes
  "Fetch image, user, verify user can view application associated with image. Expects 'image-id' to be in scope. Injects 'user' and 'image'."
  [& image-routes]
  `(wrap-file-info
    (fn [~'request]
      (if-let [~'image (query-image ~'image-id)]
        (let [~'application (:exhibit_application__r ~'image)]
          (if (local-request? ~'request)
            (routing ~'request ~@image-routes)
            (when-logged-in
             (if (or
                  (can-view-application? ~'user ~'application)
                  (can-review-application? ~'user ~'application))
               (routing ~'request ~@image-routes)
               (forbidden ~'request)))))))))

(defn user-applications-view [request username]
  (when-logged-in
   (if-not (or (admin? user) (= username (:userName__c user)) )
     (forbidden request)
     (if-let [for-user (query-user-by-username username)]
      (layout
       request
       {:title (str "Applications for " username)}
       (let [apps (query-applications (:id for-user))
             apps-by-exhibit (group-by (comp :name :exhibit__r) apps)]
         (if (empty? apps)
           [:p "You have no applications. Perhaps you would like to "
            [:a {:href (exhibits-link)} "apply"]]
           (list
            (for [[exhibit-name apps] apps-by-exhibit]
              [:div
               [:h2 exhibit-name]
               [:ul
                (for [app apps]
                  [:li
                   [:a {:href (application-submit-link (:id app))} (:title__c app)]
                   (if (= (:submission_Status__c app) "Final")
                     (str " - " (i18n/translate :submitted)))])]])))))))))

(defn current-user-applications-view [request]
  (when-logged-in (redirect (user-applications-link (:userName__c user)))))

(defn cv-view [request application]
  (let [exhibit-slug (:slug__c (:exhibit__r application))]
    (if-let [cv (persist/cv-file-path exhibit-slug (:id application))]
      (if (.exists cv)
        {:status 200
         :headers {"Content-Disposition" (str "attachment; filename=\""
                                              (.getName cv)
                                              "\"")}
         :body cv}))))

(defformpage admin-password-reset []
  [(req-textfield :username "Username")
   (req-password :password1 "Password")
   (req-password :password2 "Password Again")]
  (layout request "Reset Password"
          [:form.uniForm {:method :post :action (:uri request)}
           [:h2 "Reset User Password"]
           (render-fields request params errors)
           [:input {:type :submit :value "Save"}]])
  (let [[pass1 pass2] ((juxt :password1 :password2) params)]
    (if (= pass1 pass2)
      (if-let [user (query-user-by-username (:username params))]
        (do
          (sf/update-user conn {:id (:id user)
                                :password__c (md5 pass1)})
          (layout request "Password changed"
                  [:h1 (str "Password changed for: " (:username params))]))
        (render-form params {:username "User does not exist"}))
      (render-form params {:password1 "Passwords don't match"}))))

(defn language-view [request language came-from]
  (when (#{"en" "ru"} language)
    (session/save-language request (keyword language)))
  (redirect (or came-from "/")))

(defn translate-double-rating-to-string [d]
  (condp = d
    1.0 "1"
    2.0 "2"
    3.0 "3"
    4.0 "4"
    5.0 "5"
    (str d)))

(defn normalize-review [review]
  (and review
       (update-in review [:rating__c]
                  (fnil translate-double-rating-to-string 1.0))))

(defformpage review-view [user-id application & [review-stage]]
  [{:field [:radio {} :rating__c {:label "Rating"
                                  :description "From 1-5 (1 being lowest, 5 being highest)"
                                  :opts (map #(vector % %) (map str (range 1 6)))}]
    :validator {:fn not-empty :msg :required}}
   {:field [:text-area {:style "height: 300px; width: 650px"} :comments__c
            {:label "Comments"
             :description "Your comments are important, and we pay particular attention to your feedback. In some instances, your comments are more valuable than the average rating."}]
    :validator {:fn not-empty :msg :required}}
   {:field [:hidden {} :review_Stage__c {}]}]
  (layout
   request
   {:title "Review"
    :review-js true
    :js-script
    (str
     "Galleria.loadTheme('/public/galleria/galleria.classic.min.js'); "
     "Galleria.run('#review-images'); "
     "var caption = $('#featured-caption');"
     "Galleria.on('image', function(e) { caption.text(e.galleriaData.title); });")}
   [:div#review
    (let [images (query-images (:id application))
          fields (application-review-fields application)]
      [:div
       [:h2 "Review Application"]
       (display-error-summary errors field->label)
       [:dl
        [:dt "Applicant"]
        [:dd (:name (query-user-by-id (:contact__c application)))]
        (for [field fields]
          (let [{[_ _ field-key {title :label}] :field} field
                field-value (application field-key)]
            (when-not (empty? field-value)
              (list
              [:dt (i18n/translate title)]
              [:dd (application field-key)]))))
        [:dt "CV"]
        [:dd [:a {:href (cv-link (:id application))}
              (i18n/translate :cv-download)]]]
       [:h2 "Images"]
       [:div#review-images
        (for [image images]
          [:a {:href (image-link (:id image) "large" (:filename__c image))}
           [:img
            {:src (image-link (:id image) "small" (:filename__c image))
             :data-title (:caption__c image)}]])]
       [:p#featured-caption]
       [:form.uniForm {:method :post :action (:uri request)}
        [:h2 "Review"]
        (render-fields
         request
         (merge
          {:review_Stage__c (or review-stage "Internal Review")}
          (normalize-review (query-review user-id (:id application)))
          params)
         errors)
        [:p.note
         "Save will allow you to save your comments and come back to them later."]
        [:input {:type "submit" :name :submit :value "Save"}]
        [:p.note
         "Submit means your review is final. You will have to contact: "
         (he/link-to "mailto:docphoto@sorosny.org" "docphoto")
         " if you need to update your review."]
        [:input {:type "submit" :name :submit :value "Submit"}]]])])
  (let [updated-params (update-in params [:rating__c] #(Double/valueOf ^String %))
        final? (= (:submit params) "Submit")
        updated-params (merge updated-params
                              {:status__c (if final? "Final" "Draft")})
        application-id (:id application)]
    (if-let [review (query-review user-id application-id)]
      (do
        (sf/update-review conn (merge review updated-params))
        (cache-clear :review [user-id application-id]))
      (sf/create-review conn (assoc updated-params
                               :exhibit_Application__c application-id
                               :contact__c user-id)))
    ; clear all these caches just to be safe
    (cache-clear :review-requests)
    (cache-clear :review-request-for-user)
    (cache-clear :reviews-final)
    (layout request "Thank you for your review"
            [:div
             [:p "Thank you for your review. "
              (when-not final?
                (list "You may "
                      (he/link-to (:uri request) "update")
                      " it at any time."))]])))

(defn can-view-review-request [request review-request-id f]
  (if-let [review-request (query-review-request review-request-id)]
    (when-logged-in
     (if (can-review-request? user review-request)
       (f request review-request-id review-request)
       (forbidden request)))
    (not-found-view request)))

(defn review-request-view [request review-request-id review-request]
  (let [{application-id :exhibit_Application__c
         user-id :reviewer__c review-stage :review_Stage__c} review-request
         application (query-application application-id)]
    (review-view request user-id application review-stage)))

(defn application-review-view [request application]
  (when-logged-in
   (review-view request (:id user) application)))

(defmacro admin-routes
  "common checks for all admin routes"
  [& routes]
  `(fn [~'request]
     (when-admin (routing ~'request ~@routes))))

(defn create-images-zipper [images-with-files]
  (fn [^OutputStream outputstream]
    (when (seq images-with-files)
      (with-open [out (ZipOutputStream. outputstream)]
        (doseq [{:keys [filename image-file]} images-with-files]
          (.putNextEntry out (ZipEntry. ^String filename))
          (copy image-file out)
          (.closeEntry out))
        (.finish out)))
    (.close outputstream)))

(defn create-input-stream-from-output
  "Generic function that uses piped input/output streams to generate an input stream from an output stream. Passed in function should take an output stream."
  [f]
  (let [in (PipedInputStream.)
        out (PipedOutputStream. in)]
    (future (f out))
    in))

(defn weave-images-with-files [image-objects image-files]
  (let [parse-app-id (fn [^File f] (-> (.getParent f)
                                      file
                                      .getName))
        file-id-image-map (into {} (map
                                    (fn [image-file] [(parse-app-id image-file) image-file])
                                    image-files))]
    (keep (fn [image]
            (let [image-id (:id image)]
              (when-let [[_ image-file] (find file-id-image-map image-id)]
                {:filename (:filename__c image)
                 :image-file image-file})))
          image-objects)))

(defn download-images-response [application]
  (let [exhibit-slug (:slug__c (:exhibit__r application))
        application-id (:id application)
        image-files (persist/application-image-files exhibit-slug
                                                     application-id)
        image-objects (query-images application-id)
        images-with-files (weave-images-with-files image-objects image-files)]
    {:headers
     {"Content-Type" "application/zip"
      "Content-Disposition" (str
                             "attachment; filename=" application-id ".zip")}
     :status 200
     :body (create-input-stream-from-output (create-images-zipper images-with-files))}))

(defn admin-download-view [request]
  (if-let [application-id (:application-id (:params request))]
    (when-application
     application-id
     (download-images-response application))
    (layout
     request "Download Images for Application"
     (list
      [:h2 "Download Images"]
      [:p "Select the application id (found from the salesforce url)"]
      [:form {:method :get :action (admin-download-link)}
       [:input {:type :text :name :application-id}]
       [:br]
       [:input {:type :submit :value "Download"}]]))))

;; if time permits, can try to eliminate the duplication between this
;; and the register view
(defformpage admin-create-vetter-view []
  [(english-only-textfield :userName__c :username)
   (req-password :password__c :password)
   (req-password :password2 :password-again)
   (english-only-textfield :firstName :first-name)
   (english-only-textfield :lastName :last-name)
   (english-only-textfield :email :email)]
  (layout
   request "Create Vetter Account"
   (list
    [:form.uniForm {:method :post :action (:uri request)}
     [:h1 "Create Vetter Account"]
     [:p "Use this form to create a new vetter account. These are simply trimmed down user accounts (contacts in salesforce) that can be given to vetters that don't already exist."]
     (render-fields request params errors)
     [:input {:type :submit :value "Create"}]]))
  (let [{password1 :password__c password2 :password2
         username :userName__c} params]
    (if (not (= password1 password2))
      (render-form params {:password__c (i18n/translate :passwords-dont-match)})
      (if-let [user (query-user-by-username username)]
        (render-form params {:userName__c (i18n/translate :user-already-exists)})
        (do
          (register (-> (dissoc params :password2)
                        (update-in [:password__c] md5)))
          (layout request "User created"
                  (list
                   [:h1 "User '" username "' successfully created"])))))))

(defformpage admin-login-as-view []
  [(req-textfield :username "Username to log in as")
   {:custom came-from-field}]
  (layout
   request "Admin Login As"
   [:div
    [:form.uniForm {:method :post :action (:uri request)}
     (render-fields request params errors)
     [:input {:type :submit :value "Login"}]]])
  (let [username (:username params)]
    (if-let [user (query-user-by-username username)]
      (do
        (session/clear request)
        (login request user)
        (redirect (or (:came-from params) "/")))
      (render-form params {:username "Username not found in salesforce"}))))

(defn app-email-text [exhibit]
  (let [i18nkeyword (case (keyword (:slug__c exhibit))
                      :mw20 :mw-app-submitted-email
                      :mw21 :mw-app-submitted-email
                      :prodgrant2012 :pg-app-submitted-email
                      :prodgrant2013 :pg-app-submitted-email
                      )]
    (i18n/translate i18nkeyword)))

(defn appsubmit-println-processor [exhibit email-address]
  (println "sending email for" exhibit "to:" email-address)
  (println (app-email-text exhibit)))

(defn appsubmit-email-processor [exhibit email-address]
  (send-message
   {:to email-address
    :subject (str "[" (:name exhibit) "] Application Received")
    :body (app-email-text exhibit)}))

(defmacro application-submitted-function-from-config []
  (if cfg/debug
    `appsubmit-println-processor
    `appsubmit-email-processor))

(defn csv-image-captions [image-objects]
  (csv/write-csv
   (into
    [["Salesforce Id" "Filename" "Caption"]]
    (for [image image-objects]
      [(:id image) (:filename__c image) (:caption__c image)]))))

(defformpage admin-export-captions []
  [(req-textfield :application-id "Application id (from url)")]
  (layout
   request "Admin Export Image Captions"
   [:div
    [:form.uniForm {:method :post :action (:uri request)}
     (render-fields request params errors)
     [:input {:type :submit :value "Export Image Captions"}]]])
  (let [application-id (:application-id params)]
    (if-let [application (query-application application-id)]
      {:headers
       {"Content-Type" "text/csv"
        "Content-Disposition" (str
                               "attachment; filename=" application-id ".csv")}
       :status 200
       :body (csv-image-captions (query-images application-id))}
      (render-form params {:username "Username not found in salesforce"}))))

(defformpage admin-export-pdf []
  [(req-textfield :application-id "Application id (from url)")]
  (layout
   request "Admin Export Pdf"
   [:div
    [:form.uniForm {:method :post :action (:uri request)}
     (render-fields request params errors)
     [:input {:type :submit :value "Export Application Pdf"}]]])
  (let [application-id (:application-id params)]
    (if-let [application (query-application application-id)]
      (let [app-model (assoc application :images (query-images application-id))]
        {:headers
         {"Content-Type" "application/pdf"
          "Content-Disposition" (str "attachment; filename=" application-id ".pdf")}
         :status 200
         :body (create-input-stream-from-output
                (partial pdf/render-application-as-pdf app-model))})
      (render-form params {:username "Username not found in salesforce"}))))

(defroutes main-routes
  (GET "/" request home-view)
  (GET "/userinfo" [] userinfo-view)
  (ANY "/login" [] login-view)
  (ANY "/login/" [] login-view)
  (GET "/logout" [] logout-view)
  (ANY "/profile/:user-id" [user-id :as request]
       (when-profile-update-access user-id
        (profile-view request user-id)))
  (ANY "/profile/:user-id/password" [user-id :as request]
       (when-profile-update-access user-id
        (profile-password-view request user-id)))
  (ANY "/register" [] register-view)
  (ANY "/password/forgot" [] forgot-password-view)
  (ANY "/password/request-reset" [token :as request]
       (reset-request-view request token))
  (ANY "/password/reset" [] reset-password-view)

  (context "/application/:app-id" [app-id]
    (prepare-application-routes
     (fn [uri user application]
       (if (.endsWith ^String uri "/review")
         (can-review-application? user application)
         (can-view-application? user application)))
     (POST "/upload" [] (app-upload-image request application))
     (GET "/upload" [] (app-upload request application))
     (POST "/update-images" [] (application-update-images-view request application))
     (ANY "/submit" [] (application-submit-view request application
                                                (application-submitted-function-from-config)))
     (GET "/success" [] (application-success-view request application))
     (ANY "/update" [] (application-update-view request application))
     (GET "/debug" [] (app-debug-view request application))
     (ANY "/review" [] (application-review-view request application))
     (GET "/" [] (app-view request application))))

  (context "/image/:image-id" [image-id]
    (prepare-image-routes
     (GET "/small/*" [] (image-view request image "small"))
     (GET "/large/*" [] (image-view request image "large"))
     (GET "/original/*" [] (image-view request image "original"))
     (GET "/*" [] (image-view request image "original"))))

  (GET "/cv/:app-id" [app-id :as request]
    ;; re-using application macro for setup logic
    (prepare-application-routes
     (fn [uri user application]
       (or
        (can-view-application? user application)
        (can-review-application? user application)))
     (ANY "*" [] (cv-view request application))))

  (ANY "/review-request/:review-request-id" [review-request-id :as request]
       (can-view-review-request request review-request-id review-request-view))

  (GET "/user/applications" [] current-user-applications-view)
  (GET "/user/applications/:username" [username :as request]
       (user-applications-view request (ring-codec/url-decode username)))

  (context
   "/admin" []
   (admin-routes
    (ANY "/download" [] admin-download-view)
    (ANY "/password-reset" [] admin-password-reset)
    (ANY "/create-vetter-account" [] admin-create-vetter-view)
    (ANY "/login-as" [] admin-login-as-view)
    (ANY "/export/captions" [] admin-export-captions)
    (ANY "/export/pdf" [] admin-export-pdf)))

  (ANY "/language/:language/" [language came-from :as request]
       (language-view request language came-from))

  (context "/:exhibit-id" [exhibit-id]
    (prepare-exhibit-routes
     (ANY "/apply" [] (exhibit-apply-view request exhibit))
     (GET "/" [] (exhibit-view request exhibit))))

  (fn [request]
    (let [^String uri (:uri request)]
      (cond
       (.startsWith uri "/public/js/docphoto-min-")
       (resource-response "/public/js/docphoto-min.js")

       (.startsWith uri "/public/css/docphoto-min-")
       (resource-response "/public/css/docphoto-min.css"))))

  (route/resources "/" {:root nil})

  not-found-view)

(defn convert-params-to-keywords [params]
  (into {}
        (map (fn [[k v]] [(if (instance? String k) (keyword k) k) v])
             params)))

(defn wrap-multipart-convert-params [handler]
  "when multipart params are used, ring doesn't stick keywords into
  the params map. this middleware works around that"
  (fn [request]
    (handler (if (multipart-form? request)
               (update-in request [:params] convert-params-to-keywords)
               request))))

(defn wrap-docphoto-stacktrace
  "production ready 500 error page"
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception ex
        (do
          (.printStackTrace ex)
          {:status 500
          :headers {"Content-Type" "text/html; charset=utf-8"}
          :body
          (layout request "Error"
                  (list
                   [:h1 "Error"]
                   [:p
                    "We're sorry. We ran into an error. If the problem continues, "
                    "please contact "
                    (he/link-to "mailto:docphoto@sorosny.org" "docphoto@sorosny.org")]))})))))

(defn wrap-stacktrace
  "wrap the appropriate stack trace handler based on if debugging"
  [handler]
  ((if cfg/debug
     stacktrace/wrap-stacktrace
     wrap-docphoto-stacktrace)
   handler))

(defn bind-language [handler]
  (fn [request]
    (i18n/with-language (session/get-language request)
      (handler request))))

(defn bind-request [handler]
  (fn [request]
    ;; bind request so that query functions have access to session
    (binding [*request* request]
      (handler request))))

(defview rate-limit-exceeded-view []
  {:title "Rate limit exceeded" :display-sidebar false}
  [:div
   [:p "We are currently experiencing heavy traffic. Please wait a few minutes, and check back later. Note that the deadline has been extended to May 2."]])

(defn wrap-rate-limit-exceeded [handler]
  (fn [request]
    (try (handler request)
         (catch Exception e
           (if (sf/rate-limit-exceeded? e)
             (render (rate-limit-exceeded-view request) request)
             (throw e))))))

(def app
  (->
   main-routes
   bind-language
   bind-request
   wrap-rate-limit-exceeded
   wrap-stacktrace
   session/wrap-servlet-session
   wrap-multipart-convert-params
   wrap-multipart-params
   api))

(defn find-applications-on-disk-not-in-salesforce
  "When applications are removed from salesforce through the ui, the images/cv remain on disk. This lists the extraneous applications."
  [exhibit-slug]
  (let [applications-on-disk (persist/list-applications exhibit-slug)
        applications-in-salesforce (query-applications-for-exhibit exhibit-slug)
        s-disk-apps (set applications-on-disk)
        s-sf-apps (set (map :id applications-in-salesforce))]
    (clojure.set/difference s-disk-apps s-sf-apps)))

(defn delete-applications-on-disk-not-in-salesforce
  "Remove local applications on disk that are not in salesforce"
  [exhibit-slug]
  (doseq [application-id (find-applications-on-disk-not-in-salesforce exhibit-slug)]
    (persist/delete-application exhibit-slug application-id)))

(defn run-server []
  (run-jetty #'app {:port 8080 :join? false}))

(defn run!
  "convenience function to get started"
  []
  (connect-to-prod)
  (def server (run-server)))

;; (defn start-swank-server
;;   "start a swank server"
;;   [& server-options]
;;   (apply swank.swank/start-server server-options))

;; (defn stop-swank-server [] (swank.swank/stop-server))

;; used for war file init/destroy
(defn servlet-init []
  (println "connecting to salesforce ...")
  (connect-to-prod)
  (println "connected")
  ;; (when cfg/swank-server?
  ;;   (println "starting swank server ...")
  ;;   (start-swank-server :host "localhost" :port 4005 :dont-close true)
  ;;   (println "swank server ready"))
  )

(defn servlet-destroy []
  (println "disconnecting from sf")
  (sf/disconnect conn)
  (println "disconnected!")
  ;; (when cfg/swank-server?
  ;;   (println "stopping swank server")
  ;;   (stop-swank-server)
  ;;   (println "swank server stopped"))
  )
