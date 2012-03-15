(ns docphoto.core
  (:use [compojure.core :only (defroutes GET POST ANY routes routing context)]
        [compojure.handler :only (api)]
        [compojure.response :only (render)]
        [ring.middleware.multipart-params :only (wrap-multipart-params)]
        [hiccup.core :only (html)]
        [hiccup.page-helpers :only (xhtml include-css
                                          include-js javascript-tag)]
        [ring.adapter.jetty-servlet :only (run-jetty)]
        [ring.util.response :only (redirect)]
        [clojure.core.incubator :only (-?> -?>>)]
        [clojure.java.io :only (copy file input-stream output-stream)]
        [docphoto.utils :only (defn-debug-memo md5 multipart-form?
                                send-message onpost when-logged-in dbg
                                not-empty-and-ascii?)]
        [docphoto.form :only (defformpage came-from-field
                               req-textfield textfield req-password
                               english-only-textfield)])
  (:require [compojure.route :as route]
            [docphoto.salesforce :as sf]
            [docphoto.persist :as persist]
            [docphoto.image :as image]
            [docphoto.config :as cfg]
            [docphoto.session :as session]
            [docphoto.form :as form]
            [docphoto.i18n :as i18n]
            [clojure.string :as string]
            [clojure.walk]
            [docphoto.guidelines :as guidelines]
            [hiccup.page-helpers :as ph]
            [ring.middleware.stacktrace :as stacktrace]
            [decline.core :as decline])
  (:import [java.io File]))

;; global salesforce connection
(defonce conn nil)

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

(defmacro defquery
  "Generate a call to sf/query returning multiple results."
  [fn-name args query-params & [alter-query-fn]]
  (let [alter-query-fn (or (eval alter-query-fn) identity)]
   `(defn-debug-memo ~fn-name ~args
      ~(alter-query-fn `(sf/query ~'conn ~@query-params)))))

(defmacro defquery-single
  "Generate a call to sf/query returning a single element."
  [fn-name args query-params]
  `(defquery ~fn-name ~args ~query-params
     ~(fn [form] `(first ~form))))

;; the password passed in should be the hash
(defquery-single query-user-by-credentials [username password]
  (contact sf/user-fields [[username__c = username]
                           [password__c = password]]))

;; the password passed in should be the hash
(defquery-single query-user-by-credentials-with-userid [user-id password]
  (contact sf/user-fields [[id = user-id]
                           [password__c = password]]))

(defquery-single query-user-by-email [email]
  (contact [id] [[email = email]]))

(defquery-single query-user-by-id [id]
  (contact sf/user-fields [[id = id]]))

(defquery-single query-user-by-username [username]
  (contact [id] [[userName__c = username]]))

(defquery query-exhibits []
  (exhibit__c [id name slug__c application_start_date__c description__c]
              [[closed__c = false noquote]]
              :append "order by application_start_date__c asc"))

(defquery-single query-latest-exhibit []
  (exhibit__c [id name slug__c application_start_date__c description__c]
              [[closed__c = false noquote]]
              :append "order by application_start_date__c desc limit 1"))

(defquery query-applications [userid]
  (exhibit_application__c
   [id title__c exhibit__r.name exhibit__r.slug__c
    lastModifiedDate submission_Status__c referredby__c]
   [[exhibit_application__c.exhibit__r.closed__c = false noquote]
    [exhibit_application__c.contact__r.id = userid]])
  (fn [form] `(map tweak-application-result ~form)))

(defquery-single query-exhibit [exhibit-slug]
  (exhibit__c [id name description__c slug__c]
              [[slug__c = exhibit-slug]]))

(defquery query-application
  [app-id]
  (exhibit_application__c
   [id biography__c title__c website__c statementRich__c contact__c
    submission_Status__c exhibit__r.name exhibit__r.slug__c
    narrative__c multimedia_Link__c cover_Page__c
    focus_Country__c focus_Region__c
    referredby__c]
   [[id = app-id]])
  (fn [form] `(-?> ~form first tweak-application-result)))

(defquery query-image [image-id]
  (image__c
   [id caption__c filename__c mime_type__c order__c
    exhibit_application__r.id
    exhibit_application__r.exhibit__r.slug__c
    exhibit_application__r.contact__c]
   [[id = image-id]])
  (fn [form] `(-?> ~form first tweak-image-result)))

(defquery query-images [application-id]
  (image__c [id caption__c order__c]
            [[exhibit_application__c = application-id]]
            :append "order by order__c"))

;; filter passed in images to those that current user can view
(defquery query-allowed-images [user image-ids]
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

(defquery-single query-review-request [review-request-id]
  (exhibit_review_request__c
   [id exhibit_application__c reviewer__c review_stage__c]
   [[id = review-request-id]]))

(defquery query-review-requests [user-id application-id]
  (exhibit_review_request__c
   [id exhibit_application__c reviewer__c review_stage__c]
   [[reviewer__c = user-id]
    [exhibit_application__c = application-id]]))

(defquery query-review-requests-for-user [user-id]
  (exhibit_review_request__c
   [id exhibit_application__c reviewer__c review_stage__c
    exhibit_application__r.title__c]
   [[reviewer__c = user-id]])
  (fn [form] `(map tweak-review-request-result ~form)))

(defquery-single query-review [user-id application-id]
  (exhibit_application_review__c
   [id exhibit_application__c contact__c
    comments__c rating__c review_stage__c status__c]
   [[contact__c = user-id]
    [exhibit_application__c = application-id]]))

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
         :opts (cons [:option ""]
                     (for [[label# value#] field-values#]
                       [:option label# value#]))}))))

(defn- list-all-editor-css-files []
  "convenience function to list all google editor css files to include"
  (letfn [(css? [filename] (.endsWith filename ".css"))
          (files-in [path]
            (filter css?
             (map #(str "/public/css/" path "/" (.getName %))
                  (.listFiles
                   (file "./resources/public/css/" path)))))]
    (concat (files-in "google")
            (files-in "google/editor")
            ["/public/css/docphoto.css"])))

(defmacro editor-css []
  (vec (list-all-editor-css-files)))

(defmacro theme-css [editor-css?]
  (if cfg/debug
    (let [debug-css-files ["/public/css/google/common.css"
                           "/public/css/theme/style.css"
                           "/public/css/uni-form.css"
                           "/public/css/docphoto.css"]]
      `(if ~editor-css?
         ~(vec
           (concat debug-css-files
                   (editor-css)))
         ~debug-css-files))
    ["/public/css/docphoto-min.css"]))

(defmacro theme-js [include-upload-js?]
  (if cfg/debug
    (let [debug-js-file "http://localhost:9810/compile?id=docphoto"]
      `(apply
        include-js
        (if ~include-upload-js?
          ["/public/js/plupload/js/plupload.full.js" ~debug-js-file]
          [~debug-js-file])))
    `(include-js "/public/js/docphoto-min.js")))

(defmacro theme-menu [uri]
  (let [uri-sym (gensym "uri_")]
    [:div#menu
     `(let [~uri-sym ~uri]
        [:ul
         ~@(for [[link text active-link-fn]
                 [["/" :home]
                  ["/exhibit" :exhibits :starts-with]]]
             `[:li (if ~(if (= :starts-with active-link-fn)
                          `(.startsWith ~uri-sym ~link)
                          `(= ~uri-sym ~link))
                     {:class "current_page_item"})
               [:a {:href ~link} (i18n/translate ~text)]])])]))

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
     (ph/url "/" ~@(interpose "/" uri-parts))))

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

(defapplinks upload submit success update)

(deflinks
  (application-link [application-id] "application" application-id)
  (user-applications-link [username] "user" "applications" username)
  (cv-link [application-id] "cv" application-id)
  (profile-update-link [user-id] "profile" user-id)
  (image-link [image-id & [scale]] "image" image-id scale)
  (exhibits-link [] "exhibit/")
  (exhibit-link [exhibit-slug] "exhibit" exhibit-slug)
  (exhibit-apply-link [exhibit-slug] "exhibit" exhibit-slug "apply")
  (forgot-link [] "password" "forgot")
  (reset-request-link [] "password" "request-reset")
  (reset-password-link [] "password" "reset")
  (images-update-link [application-id] "application" application-id "update-images")
  (image-delete-link [image-id] "image" image-id "delete")
  (admin-password-reset-link [] "admin" "password-reset")
  (switch-language-link [lang came-from] "language" lang {:came-from came-from})
  (review-request-link [review-request-id] "review-request" review-request-id)
  (login-link [& [came-from]] "login" (when came-from {:came-from came-from}))
  (logout-link [] "logout")
  (register-link [] "register")
  (profile-reset-password-link [user-id] "profile" user-id "password"))

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
         (ph/link-to (profile-update-link (:id user)) (i18n/translate :update-profile))]
        (let [apps (query-applications (:id user))]
          (when (not-empty apps)
            [:div#applications-list
             [:h2 (i18n/translate :applications)]
             [:ul
              (for [app (reverse (sort-by :lastModifiedDate apps))]
                [:li
                 [:a {:href (application-submit-link (:id app))}
                  (:title__c app)]])]]))
        (let [review-requests (query-review-requests-for-user (:id user))]
          (when (not-empty review-requests)
            [:div#reviews-list
            [:h2 "Reviews"]
            [:ul
             (for [review-request (reverse (sort-by :createdDate review-requests))]
               [:li
                [:a {:href (review-request-link (:id review-request))}
                 (:title__c (:exhibit_Application__r review-request))]])]]))
        (when (admin? user)
          [:div#admin
           [:h2 "Admin"]
           [:ul
            [:li (ph/link-to (admin-password-reset-link) "Reset user password")]]]))))))

(defmacro when-multiple-languages [& body]
  (cond
   (= true cfg/multiple-languages) `(do ~@body)
   (nil? cfg/multiple-languages) nil
   :else `(when (= ((:headers ~'request) "x-forwarded-host") ~cfg/multiple-languages)
            ~@body)))

(defn layout [request options body]
  (xhtml
   [:head
    [:meta {:http-equiv "content-type" :content "text/html; charset=utf-8"}]
    [:meta {:charset "utf-8"}]
    [:title (if (string? options) options (:title options "Docphoto"))]
    (apply include-css (theme-css (:include-editor-css options)))]
   [:body
    [:div#wrapper
     [:div#header-wrapper
      [:div#header
       [:div#logo
        [:h1
         [:a {:href "/"} [:span "Documentary"] " Photography"]]]
       (theme-menu (:uri request))
       [:div#osf-logo (ph/image "/public/osf-logo.png"
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
                 (ph/link-to
                  (switch-language-link lang came-from) text))]))]])]]
     [:div#page
      [:div#content body]
      [:div#sidebar
       (sidebar-snippet request)]]]
    [:div#footer
     [:p "Copyright (c) 2012 Docphoto. All rights reserved. Design by "
      [:a {:href "http://www.freecsstemplates.org/"} "CSS Templates."]]]
    (theme-js (:include-upload-js options))
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
    (xhtml ~body)))

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

(defn- exhibits-html [request]
  (let [exhibits (query-exhibits)]
    (if (not-empty exhibits)
      [:ul
       (for [exhibit exhibits]
         [:li
          [:a {:href (exhibit-link (:slug__c exhibit))}
           (:name exhibit)]])])))

(defview home-view "Documentary Photography Project"
  [:div
   [:h2 (i18n/translate :open-competitions)]
   (exhibits-html request)])

(defview userinfo-view {:title "User Info View" :logged-in true}
  [:dl
   (for [[k v] (.getAttribute (:session request) "user")]
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

(defformpage login-view []
  [(req-textfield :userName__c :username)
   (req-password :password__c :password)
   {:custom came-from-field}]
  (layout
   request
   {}
   (list
    [:h2 (i18n/translate :login)]
    [:form.uniForm {:method :post :action (login-link)}
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
        (redirect (if-let [came-from (:came-from params)]
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
      [:h2 (i18n/translate :update-profile)]
      [:p ((i18n/translate :reset-password-text)
           (profile-reset-password-link user-id))]
      [:form.uniForm {:method :post :action (profile-update-link user-id)}
       (render-fields request (user-update-mailinglist-value
                               (merge user params)) errors)
       [:input {:type :submit :value (i18n/translate :update)}]]])
    (not-found-view))
  (let [user-params (user-update-mailinglist-value
                     (select-keys params sf/contact-fields))
        user-params (merge {:id user-id} user-params)]
    (sf/update-user conn user-params)
    (when (= user-id (:id (session/get-user request)))
      (session/save-user request user-params))
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
          (redirect "/exhibit"))))))

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

(defview exhibit-list-view [] "Exhibits"
  (exhibits-html request))

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

(def application-fields

  ;; common fields
  {:cv {:field [:file {} :cv {:label :cv :description :cv-description}]
        :validator {:fn filesize-not-empty :msg :required}}
   :focus-region {:custom (salesforce-picklist-field :focus_Region__c :focus-region)
                  ;; field added for application submit display logic
                  :field [nil nil :focus_Region__c {:label :focus-region}]}
   :focus-country {:custom (salesforce-picklist-field :focus_Country__c :focus-country)
                   :field [nil nil :focus_Country__c {:label :focus-country}]}

   ;; these are listed here to prevent duplication with the fields
   ;; listed for review

   ;; mw20 fields
   :mw20-project-title (req-textfield :title__c "Project Title")
   :mw20-project-summary {:field [:text-area#coverpage.editor {:style "height: 50px"} :cover_Page__c
                                  {:label "Project Summary"
                                   :description "A one sentence description of the project, including title (if applicable) and main subject/content."}]
                          :validator {:fn not-empty :msg :required}}
   :mw20-project-statement {:field [:text-area#statement.editor {:style "height: 500px"} :statementRich__c
                                    {:label "Project Statement" :description "(600 words maximum) describing the project you would like to exhibit"}]
                            :validator {:fn not-empty :msg :required}}
   :mw20-bio {:field [:text-area#biography.editor {:style "height: 250px"} :biography__c
                      {:label "Short Narrative Bio"
                       :description "(250 words maximum) summarizing your previous work and experience"}]
              :validator {:fn not-empty :msg :required}}
   :mw20-summary-of-engagement {:field [:text-area#summaryEngagement.editor {:style "height: 500px"} :narrative__c
                                        {:label "Summary of your engagement"
                                         :description "(600 words maximum) Please comment on your relationship with the issue or community you photographed. How and why did you begin the project? How long have you  been working on the project? Are there particular methods you  use while working?   What do you hope a viewer will take away from your project?"}]
                                :validator {:fn not-empty :msg :required}}
   
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
                           :validator {:fn not-empty :msg :required}}})

(defmulti exhibit-apply-fields (comp keyword :slug__c))

(defmethod exhibit-apply-fields :mw20 [exhibit]
  [(application-fields :mw20-project-title)
   (application-fields :mw20-project-summary)
   (application-fields :mw20-project-statement)
   (application-fields :mw20-bio)
   (application-fields :mw20-summary-of-engagement)
   (application-fields :cv)
   (findout-field)
   {:field [:text {} :website__c {:label "Website" :description "Personal Web Site (Optional)"}]}
   {:field [:text {} :multimedia_Link__c
            {:label "Multmedia Link"
             :description
             "Moving Walls has the capacity to exhibit multimedia in addition to (but not in place of) the print exhibition. A multimedia sample should be submitted only if it complements or enhances, rather than duplicates, the other submitted materials. The sample will be judged on its ability to present complex issues through compelling multimedia storytelling, and will not negatively impact the print submission. If you are submitting a multimedia piece for consideration, please post the piece on a free public site such as YouTube or Vimeo and include a link. If the piece is longer than five minutes, let us know what start time to begin watching at."}]}
   (application-fields :focus-region)
   (application-fields :focus-country)])

(defmethod exhibit-apply-fields :prodgrant2012 [exhibit]
  [(application-fields :pg-project-title)
   (application-fields :pg-project-summary)
   (application-fields :pg-proposal-narrative)
   (application-fields :pg-personal-statement)
   (application-fields :cv)
   (findout-field)
   (application-fields :focus-region)
   (application-fields :focus-country)])

(defmulti application-update-fields (comp keyword :slug__c :exhibit__r))

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

(defmethod application-update-fields :mw20 [application]
  (make-cv-field-optional (exhibit-apply-fields (:exhibit__r application))))

(defmethod application-update-fields :prodgrant2012 [application]
  (make-cv-field-optional (exhibit-apply-fields (:exhibit__r application))))

(defmulti application-review-fields (comp keyword :slug__c :exhibit__r))

(defmethod application-review-fields :mw20 [application]
  (map application-fields
       [:mw20-project-title :mw20-project-summary :mw20-project-statement
        :mw20-bio :mw20-summary-of-engagement]))

(defmethod application-review-fields :prodgrant2012 [application]
  (map application-fields
       [:pg-project-title :pg-proposal-narrative :pg-personal-statement]))

(defn exhibit-apply-view [request exhibit]
  (when-logged-in
   (let [params (:params request)
         fields (exhibit-apply-fields exhibit)
         render-form (fn [params errors]
                       (let [field (form/field-render-fn params errors)]
                         (layout
                          request
                          {:title (str "Apply to " (:name exhibit))
                           :include-editor-css true
                           :js-script "docphoto.editor.triggerEditors();"}
                          [:div
                           [:h2 (str (i18n/translate :apply-to) (:name exhibit))]
                           [:form.uniForm {:method :post :action (:uri request)
                                           :enctype "multipart/form-data"}
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
          (let [appid (create-application
                       (:slug__c exhibit)
                       (merge params
                              {:contact__c (:id (session/get-user request))
                               :exhibit__c (:id exhibit)}))]
            (redirect (application-upload-link appid)))))
      (render-form params {})))))

(defn application-update-view [request application]
  (when-logged-in
   (let [params (:params request)
         exhibit (:exhibit__r application)
         fields (application-update-fields application)
         render-form (fn [params errors]
                       (let [field (form/field-render-fn params errors)]
                         (layout
                          request
                          {:title (str "Update application")
                           :include-editor-css true
                           :js-script "docphoto.editor.triggerEditors();"}
                          [:div
                           [:form.uniForm {:method :post :action (:uri request)
                                           :enctype "multipart/form-data"}
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
                                   (normalize-empty-value :focus_Country__c)
                                   (normalize-empty-value :referredby__c)
                                   (normalize-empty-value :website__c)
                                   (normalize-empty-value :multimedia_Link__c))]
            (sf/update-application conn app-update-map)
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
  (redirect (application-submit-link (:id application))))

(defn render-image [request image]
  (list
   [:div.image-container.goog-inline-block
    (ph/image (image-link (:id image) "small"))]
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
                           "'images-description', "
                           "{url: \"%s\", captionRequiredText: '" (i18n/translate :caption-required) "'});")
                      (:uri request))}
  (list
   [:h2 (i18n/translate :upload-images)]
   [:p (i18n/translate :upload-image-amount )]
   [:form.uniForm {:method :post :action (:uri request)}
    [:div#plupload
     [:div#files-list (i18n/translate :upload-no-support)]
     [:a#pick-files {:href "#"} (i18n/translate :upload-select-files)]
     [:p#upload-container {:style "display: none"}
      [:a#upload {:href "#"}
       (i18n/translate :upload)]]]]
   [:div#images
    [:p (i18n/translate :upload-image-limit)]
    [:p#images-description {:style "display: none"}
     (i18n/translate :upload-image-reorder)]
    [:form {:method :post :action (images-update-link (:id application))}
     (render-images request (query-images (:id application)))
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

(defn app-upload-image [request application]
  (let [[filename content-type tempfile] ((juxt
                                           :filename :content-type :tempfile)
                                          (:file (:params request)))
        exhibit-slug (:slug__c (:exhibit__r application))
        application-id (:id application)
        order (-> (query-images application-id)
                  count inc double)
        image-id (create-image
                  {:filename__c filename
                   :mime_type__c content-type
                   :exhibit_application__c application-id
                   :order__c order})]
    (persist-all-image-scales tempfile exhibit-slug application-id image-id)
    (html (render-image request (query-image image-id)))))

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
      (redirect
       (application-submit-link (:id application))))))

(defn application-submit-view [request application]
  (let [app-id (:id application)]
    (onpost
     (and (sf/update-application-status conn app-id "Final")
          (redirect (application-success-link app-id)))
     (layout
      request
      {:title "Review submission"}
      [:div.application-submit
       [:h2 (i18n/translate :application-review)]
       [:p (i18n/translate :review-application-before-submitting)]
       [:fieldset
        [:legend (i18n/translate :contact-info)]
        (let [user (query-user-by-id (:contact__c application))]
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
                                  (when-not (empty? x)
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
            (ph/image (image-link (:id image) "small"))]
           [:div.goog-inline-block.image-caption (:caption__c image)]])]
       [:a {:href (application-upload-link app-id)} (i18n/translate :update)]]
      [:form {:method :post :action (application-submit-link app-id)}
       [:div.submit-button
        (if (= "Final" (:submission_Status__c application))
          [:p (i18n/translate :application-already-submitted)]
          (list
           [:p (i18n/translate :application-submit)]
           [:input {:type "submit" :value (i18n/translate :application-submit-button)}]))]]]))))

(defview application-success-view [application]
  {:title (i18n/translate :submission-thank-you)}
  [:div
   [:h2 (i18n/translate :submission-thank-you)]
   [:p (i18n/translate :selection-email-notification) (:email (session/get-user request))]
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

(defmacro prepare-application-routes
  "Take care of fetching the application, and checking security. Anaphora: depends on having 'app-id' in context matching and injects 'application' into scope."
  [& app-routes]
  `(fn [~'request]
     (if-let [~'application (query-application ~'app-id)]
       (when-logged-in
         (if (can-view-application? ~'user ~'application)
           (routing ~'request ~@app-routes)
           (forbidden ~'request)))
       (not-found-view ~'request))))

(defmacro prepare-exhibit-routes
  "Fetch exhibit, and inject 'exhibit' through anaphora. Expects 'exhibit-id' to exist in scope."
  [& exhibit-routes]
  `(fn [~'request]
     (if-let [~'exhibit (query-exhibit ~'exhibit-id)]
       (routing ~'request ~@exhibit-routes)
       (not-found-view ~'request))))

(defmacro prepare-image-routes
  "Fetch image, user, verify user can view application associated with image. Expects 'image-id' to be in scope. Injects 'user' and 'image'."
  [& image-routes]
  `(fn [~'request]
     (if-let [~'image (query-image ~'image-id)]
       (when-logged-in
        (let [~'application (:exhibit_application__r ~'image)]
          (if (or
               (can-view-application? ~'user ~'application)
               (can-review-application? ~'user ~'application))
            (routing ~'request ~@image-routes)
            (forbidden ~'request)))))))

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
                (for [app (sort-by :lastModifiedDate apps)]
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
  (layout request "Review"
          [:div#review
           (let [images (query-images (:id application))
                 fields (application-review-fields application)]
             [:div
              [:h2 "Application Responses"]
              (for [field fields]
                (let [{[_ _ field-key {title :label}] :field} field]
                  [:dl
                   [:dt (i18n/translate title)]
                   [:dd (application field-key)]]))
              [:h2 "Images"]
              [:ul
               (for [image images]
                 [:li
                  (ph/image (image-link (:id image) "small"))
                  [:br]
                  (:caption__c image)])]
              
              [:h2 "Review"]
              [:form.uniForm {:method :post :action (:uri request)}
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
                "Submit means your review is final."]
               [:input {:type "submit" :name :submit :value "Submit"}]]])])
  (let [updated-params (update-in params [:rating__c] #(Double/valueOf %))
        final? (= (:submit params) "Submit")
        updated-params (merge updated-params
                              {:status__c (if final? "Final" "Draft")})
        application-id (:id application)
        ]
    (if-let [review (query-review user-id application-id)]
      (sf/update-review conn (merge review updated-params))
      (sf/create-review conn (assoc updated-params
                               :exhibit_Application__c application-id
                               :contact__c user-id)))
    (layout request "Thank you for your review"
            [:div
             [:p "Thank you for your review. "
              (when-not final?
                (list "You may "
                      (ph/link-to (:uri request) "update")
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
  (when-admin
   (review-view request (:id user) application)))

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
     (POST "/upload" [] (app-upload-image request application))
     (GET "/upload" [] (app-upload request application))
     (POST "/update-images" [] (application-update-images-view request application))
     (ANY "/submit" [] (application-submit-view request application))
     (GET "/success" [] (application-success-view request application))
     (ANY "/update" [] (application-update-view request application))
     (GET "/debug" [] (app-debug-view request application))
     (ANY "/review" [] (application-review-view request application))
     (GET "/" [] (app-view request application))))

  (GET "/exhibit" [] exhibit-list-view)
  (context "/exhibit/:exhibit-id" [exhibit-id]
    (prepare-exhibit-routes
     (ANY "/apply" [] (exhibit-apply-view request exhibit))
     (GET "/" [] (exhibit-view request exhibit))))
  
  (context "/image/:image-id" [image-id]
    (prepare-image-routes
     (GET "/small" [] (image-view request image "small"))
     (GET "/large" [] (image-view request image "large"))
     (GET "/original" [] (image-view request image "original"))
     (GET "/" [] (image-view request image "original"))))

  (GET "/cv/:app-id" [app-id :as request]
    ;; re-using application macro for setup logic
    (prepare-application-routes
     (ANY "*" [] (cv-view request application))))

  (ANY "/review-request/:review-request-id" [review-request-id :as request]
       (can-view-review-request request review-request-id review-request-view))

  (GET "/user/applications" [] current-user-applications-view)
  (GET "/user/applications/:username" [username :as request]
       (user-applications-view request username))

  (ANY "/admin/password-reset" request (when-admin (admin-password-reset request)))

  (ANY "/language/:language/" [language came-from :as request]
       (language-view request language came-from))

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
                    (ph/link-to "mailto:docphoto@sorosny.org" "docphoto@sorosny.org")]))})))))

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

(def app
  (->
   main-routes
   bind-language
   wrap-stacktrace
   session/wrap-servlet-session
   wrap-multipart-convert-params
   wrap-multipart-params
   api))

(defn run-server []
  (run-jetty #'app {:port 8080 :join? false}))
