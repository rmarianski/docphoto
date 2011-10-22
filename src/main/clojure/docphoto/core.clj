(ns docphoto.core
  (:use [compojure.core :only (defroutes GET POST ANY routes routing)]
        [compojure.handler :only (api)]
        [compojure.response :only (render)]
        [ring.middleware.multipart-params :only (wrap-multipart-params)]
        [hiccup.core :only (html)]
        [hiccup.page-helpers :only (xhtml include-css
                                          include-js javascript-tag)]
        [ring.adapter.jetty-servlet :only (run-jetty)]
        [flutter.html4 :only (html4-fields)]
        [flutter.shortcuts :only (wrap-shortcuts)]
        [flutter.params :only (wrap-params)]
        [ring.util.response :only (redirect)]
        [decline.core :only
         (validations validation validate-val validate-some)]
        [clojure.contrib.core :only (-?> -?>>)]
        [clojure.contrib.trace :only (deftrace)]
        [clojure.java.io :only (copy file input-stream output-stream)]
        [clojure.contrib.string :only (as-str)]
        [clojure.contrib.def :only (defn-memo)])
  (:require [compojure.route :as route]
            [docphoto.salesforce :as sf]
            [docphoto.persist :as persist]
            [docphoto.image :as image]
            [docphoto.config :as cfg]
            [clojure.contrib.string :as string]
            [clojure.walk]
            [hiccup.page-helpers :as ph]
            [ring.middleware.multipart-params :as multipart])
  (:import [org.apache.commons.codec.digest DigestUtils]))

;; global salesforce connection
(defonce conn nil)

(defn md5 [s] (DigestUtils/md5Hex s))

(defn wrap-servlet-session [handler]
  (fn [request]
    (handler
     (if-let [servlet-request (:servlet-request request)]
       (assoc request :session (.getSession servlet-request true))
       request))))

(defmacro defn-debug-memo
  "when in debug mode, memoize function"
  [& args]
  (if cfg/*debug*
    `(defn-memo ~@args)
    `(defn ~@args)))

(defn session-get-user [request]
  (.getAttribute (:session request) "user"))

(defn session-save-user [request user]
  (.setAttribute (:session request) "user" user))

(defn session-delete [request]
  (.invalidate (:session request)))

(defn session-get-token [request]
  (.getAttribute (:session request) "reset-token"))

(defn session-save-token [request reset-token userid]
  (.setAttribute (:session request) "reset-token" {:userid userid
                                                   :token reset-token}))

(defn session-allow-password-reset [request userid]
  (.removeAttribute (:session request) "reset-token")
  (.setAttribute (:session request) "allow-password-reset" userid))

(defn session-password-reset-userid [request]
  (.getAttribute (:session request) "allow-password-reset"))

(defn session-remove-allow-password-reset [request]
  (.removeAttribute (:session request) "allow-password-reset"))

(defn session-get-application [request application-id]
  (get (.getAttribute (:session request) "applications") application-id))

(defn session-save-application [request application-id application]
  (let [session (:session request)]
    (.setAttribute session
                   "applications"
                   (assoc (or (.getAttribute session "applications") {})
                     application-id application))
    application-id))

(defn-debug-memo query-user [username password]
  (first
   (sf/query conn contact
             sf/user-fields
             [[username__c = username]
              [password__c = password]])))

(defn-debug-memo query-user-by-email [email]
  (first (sf/query conn contact [id] [[email = email]])))

(defn-debug-memo query-exhibits []
  (sf/query conn exhibit__c
            [id name slug__c application_start_date__c description__c]
            [[closed__c = false noquote]]))

(defn-debug-memo query-latest-exhibit []
  (first
   (sf/query conn exhibit__c
             [id name slug__c application_start_date__c description__c]
             [[closed__c = false noquote]]
             :append "order by application_start_date__c desc limit 1")))

(defn- list-all-editor-css-files []
  "convenience function to list all google editor css files to include"
  (letfn [(css? [filename] (.endsWith filename ".css"))
          (files-in [path]
            (filter css?
             (map #(str "/public/css/" path "/" (.getName %))
                  (.listFiles
                   (file "./src/main/resources/public/css/" path)))))]
    (concat (files-in "google")
            (files-in "google/editor")
            ["/public/css/docphoto.css"])))

(defmacro editor-css []
  (if cfg/*debug*
    (vec (list-all-editor-css-files))
    ["/public/css/docphoto-min.css"]))

(defn lorem-ipsum []
  "Lorem ipsum dolor sit amet, consectetur adipisicing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.")

(defmacro theme-css [editor-css?]
  (if cfg/*debug*
    (let [debug-css-files ["/public/css/theme/style.css"
                           "/public/css/uni-form.css"
                           "/public/css/docphoto.css"]]
      `(if ~editor-css?
         ~(vec
           (concat debug-css-files
                   (editor-css)))
         ~debug-css-files))
    ["/public/css/docphoto-min.css"]))

(defmacro theme-js [include-upload-js?]
  (if cfg/*debug*
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
                 [["/" "Home"]
                  ["/exhibit" "Exhibits" :starts-with]
                  ["/about" "About"]]]
             `[:li (if ~(if (= :starts-with active-link-fn)
                          `(.startsWith ~uri-sym ~link)
                          `(= ~uri-sym ~link))
                     {:class "current_page_item"})
               [:a {:href ~link} ~text]])])]))

(defn- tweak-application-result [application]
  (update-in application [:exhibit__r] sf/sobject->map))

(defn- tweak-image-result [image]
  (->
   (update-in image [:exhibit_application__r] sf/sobject->map)
   (update-in [:exhibit_application__r :exhibit__r] sf/sobject->map)))

(defn-debug-memo query-applications [userid]
  (map tweak-application-result
       (sf/query
        conn
        exhibit_application__c
        [id title__c exhibit__r.name exhibit__r.slug__c
         lastModifiedDate submission_Status__c referredby__c]
        [[exhibit_application__c.exhibit__r.closed__c = false noquote]
         [exhibit_application__c.contact__r.id = userid]])))

(defn- application-link [application-id]
  (str "/application/" application-id))

(defn- application-upload-link [application-id]
  (str (application-link application-id) "/upload"))

(defn- application-submit-link [application-id]
  (str (application-link application-id) "/submit"))

(defn application-success-link [application-id]
  (str (application-link application-id) "/success"))

(defn my-applications-link [request]
  "/my-applications")

(defn cv-link [application-id]
  (str "/cv/" application-id))

(defn came-from-link-snippet [came-from]
  (if came-from
    (str "?came-from=" came-from)))

(defn profile-update-link [request & [came-from]]
  (str "/profile"
       (came-from-link-snippet came-from)))

(defn application-update-link [application-id request & [came-from]]
  (str (application-link application-id) "/update"
       (came-from-link-snippet came-from)))

(defn- image-link [image-id scale] (str "/image/" image-id "/" scale))

(defn- user-update-mailinglist-value
  "ensure a :docPhoto_Mail_List__c key exists and is set to a boolean value"
  [m]
  (update-in m [:docPhoto_Mail_List__c] boolean))

(defn login-logout-snippet [request]
  (let [user (session-get-user request)]
    (list
     [:div.login-logout
      (if user
        [:a {:href "/logout" :title (str "Logout " (:userName__c user))}
         "Logout"]
        (list
         [:a {:href "/login"} "Login"]
         " | "
         [:a {:href "/register"} "Register"]))]
     (if user
       [:div.applications
        [:h2 "applications"]
        (let [apps (query-applications (:id user))]
          [:ul
           (for [app (reverse (sort-by :lastModifiedDate apps))]
             [:li
              [:a {:href (application-submit-link (:id app))}
               (:title__c app)]])])]))))

(defn layout [request options body]
  (xhtml
   [:head
    [:meta {:http-equiv "content-type" :content "text/html; charset=utf-8"}]
    [:meta {:charset "utf-8"}]
    [:title (:title options "Docphoto")]
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
                                "Open Society Foundations")]]]
     [:div#page
      [:div#content body]]
     [:div#sidebar
      (login-logout-snippet request)]]
    [:div#footer
     [:p "Copyright (c) 2011 Docphoto. All rights reserved. Design by "
      [:a {:href "http://www.freecsstemplates.org/"} "CSS Templates."]]]
    (theme-js (:include-upload-js options))
    (if-let [js (:js-script options)]
      (javascript-tag js))]))

(defn wrap-errors
  "add in errors for form fields puts the error in opts if it's a map"
  [f errors]
  (fn [type attrs name opts value]
    (if (nil? opts)
      (recur type attrs name {} value)
      (f type attrs name
         (if (map? opts)
           (merge {:error (errors name)} opts)
           opts)
         value))))

(defn wrap-form-field
  "wraps a particular field with a label, description, error which is
  fetched from opts which must be a map. options for the field itself
  go under the :opts key"
  [f]
  (fn [type attrs name opts value]
    [:div.ctrlHolder
     (list
      [:label {:required (:required opts)}
       (or (:label opts) (string/capitalize (as-str name)))]
      (when-let [desc (:description opts)]
        [:p.formHint desc])
      (f type attrs name (:opts opts) value)
      (when-let [error (:error opts)]
        [:div.error error]))]))

(defn wrap-textinput-classes
  "add a textInput class to text inputs"
  [f]
  (fn [type attrs name opts value]
    (if (#{:text :password} type)
      (f type (if (:class attrs)
                (str (:class attrs) " textInput")
                (assoc attrs :class "textInput"))
         name opts value)
      (f type attrs name opts value))))

(defn wrap-checkbox-opts-normalize
  "normalize the options to have the checked behavior work properly"
  [f]
  (fn [type attrs name opts value]
    (if (= type :checkbox)
      (f type attrs name "on" (if value "on"))
      (f type attrs name opts value))))

(defn- make-fields-render-fn [fields options]
  (let [field (gensym "field__")
        params (gensym "params__")
        request (gensym "request__")
        errors (gensym "errors__")]
    `(fn [~request ~params ~errors]
       (let [~field (-> html4-fields
                        wrap-checkbox-opts-normalize
                        wrap-form-field
                        wrap-textinput-classes
                        wrap-shortcuts
                        (wrap-errors ~errors)
                        (wrap-params ~params))]
         (list
          ~@(map (fn [fieldinfo]
                   (if-let [customfn (:custom fieldinfo)]
                     `(~customfn ~request ~params ~errors)
                     (if-let [fieldspec (:field fieldinfo)]
                       `(~field ~@fieldspec))))
                 fields))))))

(defn- make-validator-stanza [fieldinfo]
  (let [{:keys [field validator]} fieldinfo
        {:keys [fn msg]} validator
        [_ _ name] field]
    (if (some nil? [fn msg name])
      `nil
      `(validate-val ~name ~fn {~name ~msg}))))

(defn make-form-validator-fn [fields]
  `(validations
    ~@(keep make-validator-stanza fields)))

(defn make-form-render-fn [request binding fields-render-fn form-body options]
  `(fn [params# errors#]
     (let [~@binding {:render-fields ~fields-render-fn
                      :params params#
                      :request ~request
                      :errors errors#}]
       ~form-body)))

(defmacro defformpage
  ([fn-name fields field-render-bindings form-render-body
    success-bindings success-body]
     `(defformpage ~fn-name ~fields {}
        ~field-render-bindings ~form-render-body
        ~success-bindings ~success-body))
  ([fn-name fields options field-render-bindings form-render-body
    success-bindings success-body]
     (let [fields-render-fn (gensym "fields-render-fn__")
           form-render-fn (gensym "form-render-fn__")
           validator-fn (gensym "validator-fn__")
           params (gensym "params__")
           request (gensym "request__")
           fields (map macroexpand fields)]
       `(let [~fields-render-fn ~(make-fields-render-fn fields options)
              ~validator-fn ~(make-form-validator-fn fields)]
          (defn ~fn-name [~request ~@(:fn-bindings options)]
            (let [~params (:params ~request)
                  ~form-render-fn ~(make-form-render-fn
                                    request
                                    field-render-bindings fields-render-fn
                                    form-render-body options)]
              (if (= (:request-method ~request) :post)
                (if-let [errors# (~validator-fn ~params)]
                  (~form-render-fn ~params errors#)
                  (let [~@success-bindings {:render-form ~form-render-fn
                                            :params ~params
                                            :request ~request}]
                    ~success-body))
                (~form-render-fn ~params {}))))))))

(defmacro textfield [fieldname label]
  {:field [:text {} fieldname {:label label}]})

(defmacro req-textfield [fieldname label]
  (assoc (textfield fieldname label)
    :validator {:fn not-empty :msg :required}))

(defn- exhibits-link [request] (str "/exhibit/"))

(defn- exhibit-link [request exhibit]
  (str (exhibits-link request) (:slug__c exhibit)))

(defn- exhibit-apply-link [request exhibit]
  (str (exhibit-link request exhibit) "/apply"))

(defn forgot-link [request] "/forgot-password")

(defn reset-request-link [request] "/reset-request")
(defn reset-password-link [request] "/reset-password")

(defn- exhibits-html [request]
  (let [exhibits (query-exhibits)]
    (if (not-empty exhibits)
      [:ul
       (map #(vector :li [:a {:href (exhibit-link request %)}
                          (:name %)])
            exhibits)])))

(defn home-view [request]
  (layout
   request
   {:title "Documentary Photography Project"}
   [:div
    [:h2 "Open competitions"]
    (exhibits-html request)]))

(defmacro validate-vals [& val-data]
  `(validations
    ~@(for [[k f error] (partition 3 val-data)]
       `(validate-val ~k ~f {~k ~error}))))

(defn userinfo-view [request]
  (layout
   request
   {}
   [:dl
    (for [[k v] (.getAttribute (:session request) "user")]
      (list [:dt k] [:dd v]))]))

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
 (session-save-user request user))

(defn logout [request]
  (session-delete request))

(defn came-from-field [request params errors]
  "a hidden input that passes came from information"
  (let [came-from (or (:came-from params) ((:headers request) "referer"))]
    (if (not-empty came-from)
      [:input {:type :hidden
               :name "came-from"
               :value came-from}])))

(defformpage login-view
  [(req-textfield :userName__c "Username")
   {:field [:password {} :password__c {:label "Password"}]
    :validator {:fn not-empty :msg :required}}
   {:custom came-from-field}]
  [{:keys [render-fields request params errors]}]
  (layout
   request
   {}
   (list
    [:h2 "Login"]
    [:form.uniForm {:method :post :action "/login"}
     [:fieldset
      (if-let [user (session-get-user request)]
        [:p (str "Already logged in as: " (:name user))])
      (render-fields request params errors)
      [:input {:type :submit :value "Login"}]]]
    [:div
     [:p#forgot-password.note
      "Forgot your password? "
      (ph/link-to (forgot-link request) "Reset") " it."]]))
  [{render-form-fn :render-form params :params request :request}]
  (if-let [user (query-user (:userName__c params) (md5 (:password__c params)))]
    (do (login request user)
        (redirect (if-let [came-from (:came-from params)]
                    (if (.endsWith came-from "/login") "/" came-from)
                    "/")))
    (render-form-fn params {:userName__c "Invalid Credentials"})))

(defn logout-view [request] (logout request) (redirect "/login"))

(defformpage profile-view
  [(req-textfield :firstName "First Name")
   (req-textfield :lastName "Last Name")
   (req-textfield :email "Email")
   (req-textfield :phone "Phone")
   (req-textfield :mailingStreet "Address")
   (req-textfield :mailingCity "City")
   (req-textfield :mailingState "State")
   (req-textfield :mailingPostalCode "Postal Code")
   (req-textfield :mailingCountry "Country")
   {:field [:checkbox {} :docPhoto_Mail_List__c
            {:label "Subscribe to mailing list?"}]}
   {:custom came-from-field}]
  [{:keys [render-fields request params errors]}]
  (layout
   request
   {}
   [:form.uniForm {:method :post :action (profile-update-link request)}
    [:h2 "Update profile"]
    (render-fields request (user-update-mailinglist-value
                            (merge (session-get-user request) params)) errors)
    [:input {:type :submit :value "Update"}]])
  [{render-form-fn :render-form params :params request :request}]
  (let [user (session-get-user request)
        user-params (user-update-mailinglist-value
                     (select-keys params sf/contact-fields))]
    (sf/update-user conn (merge {:id (:id user)} user-params))
    (session-save-user request (merge user user-params))
    (redirect (or (:came-from params) "/"))))

(defformpage register-view
  [(req-textfield :userName__c "Username")
   {:field [:password {} :password__c {:label "Password"}]
    :validator {:fn not-empty :msg :required}}
   {:field [:password {} :password2 {:label "Password"}]
    :validator {:fn not-empty :msg :required}}
   (req-textfield :firstName "First Name")
   (req-textfield :lastName "Last Name")
   (req-textfield :email "Email")
   (req-textfield :phone "Phone")
   (req-textfield :mailingStreet "Address")
   (req-textfield :mailingCity "City")
   (req-textfield :mailingState "State")
   (req-textfield :mailingPostalCode "Postal Code")
   (req-textfield :mailingCountry "Country")
   {:field [:checkbox {} :docPhoto_Mail_List__c
            {:label "Subscribe to mailing list?"}]}]
  [{:keys [render-fields request params errors]}]
  (layout
   request
   {}
   [:form.uniForm {:method :post :action "/register"}
    [:h2 "Register"]
    (render-fields request params errors)
    [:input {:type :submit :value "Register"}]])
  [{render-form-fn :render-form params :params request :request}]
  (let [{password1 :password__c password2 :password2
         username :userName__c} params]
    (if (not (= password1 password2))
      (render-form-fn params {:password__c "Passwords don't match"})
      (if-let [user (first
                     (sf/query conn contact
                               [id] [[userName__c = username]]))]
        (render-form-fn params {:userName__c "User already exists"})
        (do
          (register (-> (dissoc params :password2)
                        (update-in [:password__c] md5)
                        user-update-mailinglist-value))
          (login request (query-user username (md5 password1)))
          (redirect "/exhibit"))))))

(defn send-email-reset [request email token]
  (let [reset-link (str (reset-request-link request) "?token=" token)]
    (println "Reset password:" reset-link)))

(let [generator (java.util.Random.)]
  (defn generate-reset-token [email]
    (str (.nextInt generator))))

(defformpage forgot-password-view
  [(req-textfield :email "Email address")]
  [{:keys [render-fields request params errors]}]
  (layout
   request
   {:title "Reset password"}
   [:form.uniForm {:method :post :action (:uri request)}
    [:h2 "Password Reset"]
    [:p
     "You will receive a link that will allow you to reset your password. You must use the same browser session in order to reset your password."]
    (render-fields request params errors)
    [:input {:type :submit :value "Reset"}]])
  [{render-form-fn :render-form params :params request :request}]
  (let [email (:email params)]
    (if-let [user (query-user-by-email email)]
      (let [reset-token (generate-reset-token email)]
        (session-save-token request reset-token (:id user))
        (send-email-reset request email reset-token)
        (layout request {:title "Email sent"}
                [:div
                 [:p "An email has been sent to: " (:email params)]]))
      (render-form-fn params {:email "Email not found"}))))

(defn reset-request-view [request token]
  (letfn [(reset-failure-page [msg]
            (layout request {:title "Reset failure"}
                    [:div
                     [:h2 "Password reset failed"]
                     [:p msg]]))]
    (if-not token
      (reset-failure-page "No token found. Please double check the link in your email.")
      (if-let [session-token (session-get-token request)]
        (if (= (:token session-token) token)
          (do
            (session-allow-password-reset request (:userid session-token))
            (redirect (reset-password-link request)))
          (do (println "session" (:token session-token) "passed" token)
            (reset-failure-page "Invalid token. Please double check the link in your email.")))
        (reset-failure-page [:span "Token expired. Please "
                             (ph/link-to (forgot-link request) "resend")
                             " a password reset email."])))))

(defformpage reset-password-view
  [{:field [:password {} :password1 {:label "Password"}]
    :validator {:fn not-empty :msg :required}}
   {:field [:password {} :password2 {:label "Password again"}]
    :validator {:fn not-empty :msg :required}}]
  [{:keys [render-fields request params errors]}]
  (if-let [userid (session-password-reset-userid request)]
    (layout
     request
     {:title "Reset password"}
     [:form.uniForm {:method :post :action (:uri request)}
      [:h2 "Password Reset"]
      (render-fields request params errors)
      [:input {:type :submit :value "Reset"}]])
    (redirect (reset-request-link request)))
  [{render-form-fn :render-form params :params request :request}]
  (if-let [userid (session-password-reset-userid request)]
    (if (= (:password1 params) (:password2 params))
      (do
        (sf/update-user conn {:id userid
                              :password__c (md5 (:password1 params))})
        (session-remove-allow-password-reset request)
        (redirect "/login?came-from=/"))
      (render-form-fn params {:password1 "Passwords don't match"}))))


(defn-debug-memo query-exhibit [exhibit-slug]
  (first
   (sf/query conn exhibit__c
             [id name description__c slug__c]
             [[slug__c = exhibit-slug]])))

(defn-debug-memo query-application
  [app-id]
  (-?>
   (sf/query conn exhibit_application__c
             [id biography__c title__c website__c statementRich__c contact__c
              submission_Status__c exhibit__r.name exhibit__r.slug__c
              referredby__c]
             [[id = app-id]])
   first
   tweak-application-result))

(defn-debug-memo query-image [image-id]
  (-?>
   (sf/query conn image__c
             [id caption__c filename__c mime_type__c order__c
              exhibit_application__r.id
              exhibit_application__r.exhibit__r.slug__c
              exhibit_application__r.contact__c]
             [[id = image-id]])
   first
   tweak-image-result))

(defn-debug-memo query-images [application-id]
  (sf/query conn image__c
            [id caption__c]
            [[exhibit_application__c = application-id]]
            :append "order by order__c"))

(defn delete-images [exhibit-slug application-id]
  (sf/delete-images-for-application conn application-id)
  (persist/delete-images-for-application exhibit-slug application-id))

(defn delete-image [exhibit-slug application-id image-id]
  (sf/delete-image conn image-id)
  (persist/delete-image exhibit-slug application-id image-id))

(defn exhibit-view [request exhibit]
  (if exhibit
    (layout
     request
     {:title (:name exhibit)
      :js-script "docphoto.removeAnchorTargets();"}
     [:div
      [:h2 (:name exhibit)]
      [:p (:description__c exhibit)]])))

(defn filesize-not-empty [fileobject]
  (pos? (:size fileobject 0)))

(defmacro findout-field []
  {:field [:select {} :referredby__c
           {:label "How did you find out about Moving Walls 20?"
            :opts [[:option ""]
                   [:option "Friend" "Friend"]
                   [:option "Website" "Website"]
                   [:option "Other" "Other"]]}]
   :validator {:fn not-empty :msg :required}})

(defformpage exhibit-apply-view
  [(req-textfield :title__c "Project Title")
   {:field [:text-area#statement.editor {} :statementRich__c {:label "Project Statement"}]
    :validator {:fn not-empty :msg :required}}
   {:field [:text-area#biography.editor {} :biography__c {:label "Short Narrative Bio"}]
    :validator {:fn not-empty :msg :required}}
   {:field [:file {} :cv {:label "Upload CV"}] :validator
    {:fn filesize-not-empty :msg :required}}
   (textfield :website__c "Website")
   (findout-field)]
  {:fn-bindings [exhibit]}
  [{:keys [render-fields request params errors]}]
  (if exhibit
    (layout
     request
     {:title (str "Apply to " (:name exhibit))
      :include-editor-css true
      :js-script "docphoto.editor.triggerEditors();"}
     [:div
      [:h2 (str "Apply to " (:name exhibit))]
      [:form.uniForm {:method :post :action (:uri request)
              :enctype "multipart/form-data"}
       [:fieldset
        [:legend "Apply"]
        (render-fields request params errors)]
       [:input {:type :submit :value "Apply"}]]]))
  [{:keys [params request]}]
  (redirect
   (str "/application/"
        (create-application
         (:slug__c exhibit)
         (merge
          params
          {:contact__c (:id (session-get-user request))
           :exhibit__c (:id exhibit)}))
        "/upload")))

(defformpage application-update-view
  [(req-textfield :title__c "Project Title")
   {:field [:text-area#statement.editor {} :statementRich__c {:label "Project Statement"}]
    :validator {:fn not-empty :msg :required}}
   {:field [:text-area#biography.editor {} :biography__c {:label "Short Narrative Bio"}]
    :validator {:fn not-empty :msg :required}}
   {:field [:file {} :cv {:label "Update CV"}]}
   (textfield :website__c "Website")
   (findout-field)]
  {:fn-bindings [application]}
  [{:keys [render-fields request params errors]}]
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
      (render-fields request (merge application params) errors)]
     [:input {:type :submit :value "Update"}]]])
  [{:keys [params request]}]
  (let [app-id (:id application)
        app-update-map (merge (dissoc params :cv)
                              {:id app-id})]
    (sf/update-application conn app-update-map)
    (let [cv (:cv params)
          tempfile (:tempfile cv)
          filename (persist/safe-filename (:filename cv))
          size (:size cv)
          exhibit-slug (:slug__c (:exhibit__r application))]
      (if (and :cv (not-empty filename) (pos? size))
        (do
          (persist/remove-existing-cvs exhibit-slug app-id)
          (persist/persist-cv tempfile exhibit-slug app-id filename))))
    (redirect
     (or (:came-from params)
         (application-submit-link app-id)))))

(defn app-view [request application]
  (layout
   request
   {:title (:title__c application)}
   (list [:h2 (:title__c application)]
         [:dl
          (for [[k v] application]
            (list
             [:dt k]
             [:dd v]))])))

(defn caption-save-link [application-id]
  (str "/application/" application-id "/caption"))

(defn image-delete-link [image-id]
  (str "/image/" image-id "/delete"))

(defn render-image [request image]
  (list
   [:div.image-container.goog-inline-block
    (ph/image (image-link (:id image) "small"))]
   [:textarea {:name (str "caption-" (:id image))}
    (or (:caption__c image) "")]
   [:a {:href (image-delete-link (:id image))
        :class "image-delete"} "Delete"]))

(defn render-images [request images]
  [:ul#images-list
   (for [image images]
     [:li (render-image request image)])])

(defn app-upload [request application]
  (layout
   request
   {:title "Upload images"
    :include-upload-js true
    :js-script (format (str "new docphoto.Uploader('plupload', 'pick-files', "
                            "'upload', 'files-list', 'images-list', "
                            "'images-description', {url: \"%s\"});")
                       (:uri request))}
   (list
    [:h2 "Upload images"]
    [:form.uniForm {:method :post :action (:uri request)}
     [:div#plupload
      [:div#files-list
       (str "Your browser doesn't have Flash, Silverlight, Gears, BrowserPlus "
            "or HTML5 support.")]
      [:a#pick-files {:href "#"} "Select files"]
      [:span " | "]
      [:a#upload {:href "#"} "Upload"]]]
    [:div#images
     [:p#images-description {:style "display: none"}
      (str "The order of your images is an important consideration. "
           "Drag them to re-order.")]
     [:form {:method :post :action (caption-save-link (:id application))}
      (render-images request (query-images (:id application)))
      [:input {:type "submit" :value "Save"}]]])))

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
        image-id (create-image
                  {:filename__c filename
                   :mime_type__c content-type
                   :exhibit_application__c application-id
                   :order__c (-> (query-images application-id)
                                 count inc double)})]
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

(let [n (count "caption-")]
  (defn parse-caption-maps [params]
    (keep
     identity
     (map (fn [[k v]]
            (let [caption (name k)]
              (if (.startsWith caption "caption-")
                {:id (subs caption n)
                 :caption__c (or v "")})))
          params))))

(defn application-save-captions-view [request application]
  (if (= :post (:request-method request))
    (let [caption-maps (parse-caption-maps (:params request))]
      (if (not-empty caption-maps)
        (redirect
         (and (sf/update-application-captions conn caption-maps)
              (application-submit-link (:id application))))))))

(defn application-submit-view [request application]
  (let [app-id (:id application)]
    (if (= :post (:request-method request))
      (and (sf/update-application-status conn app-id "Final")
           (redirect (application-success-link app-id)))
      (layout
       request
       {:title "Review submission"}
       [:div.application-submit
        [:h2 "Application review"]
        [:p "Review your application before submitting."]
        [:fieldset
         [:legend "Contact info"]
         (let [user (session-get-user request)]
           (list
            [:h2 (:name user)]
            [:p (:email user)]
            [:p (:phone user)]
            [:p
             (:mailingStreet user) [:br]
             (:mailingCity user) ", " (:mailingState user) " "
             (:mailingPostalCode user) [:br]
             (:mailingCountry user)]
            [:p (str (if-not (:docPhoto_Mail_List__c user)
                       "Not subscribed "
                       "Subscribed ")
                     "to mailing list")]
            [:a {:href (profile-update-link request
                                            (:uri request))} "Update"]))]
        [:fieldset
         [:legend "Application"]
         [:h2 (:title__c application)]
         [:dl
          [:dt "Project Statement"]
          [:dd (:statementRich__c application)]
          [:dt "Short biography"]
          [:dd (:biography__c application)]
          [:dt "Website"]
          [:dd (:website__c application "No website")]
          [:dt "CV"]
          [:dd [:a {:href (cv-link app-id)} "Download CV"]]
          [:dt "Found out from"]
          [:dd (:referredby__c application)]]
         [:a {:href (application-update-link app-id request)} "Update"]]
        [:fieldset
         [:legend "Images"]
         [:ol
          (for [image (query-images app-id)]
            [:li
             [:div.image-container.goog-inline-block
              (ph/image (image-link (:id image) "small"))]
             [:span (:caption__c image)]])]
         [:a {:href (application-upload-link app-id)} "Update"]]
        [:form {:method :post :action (application-submit-link app-id)}
         [:div.submit-button
          (if (= "Final" (:submission_Status__c application))
            [:p "Your application has already been submitted. When we are finished reviewing all applications, we will get back to you."]
            (list
             [:p "Once you have reviewed your application, please click on the submit button below."]
             [:input {:type "submit" :value "Submit your application"}]))]]]))))

(defn application-success-view [request application]
  (layout
   request
   {:title "Thank you for your submission"}
   [:div
    [:h2 "Thank you for your submission"]
    [:p "When we have made our selections, we will notify you at the email address you provided: " (:email (session-get-user request))]
    [:p "You can view all your "
     [:a {:href (my-applications-link request)} "applications"] "."]]))

(defn forbidden [request]
  {:status 403
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout
          request
          {:title "Forbidden"}
          (list
           [:h2 "Forbidden"]
           [:p "You don't have access to view this page"]))})

(defn- parse-mounted-route [uri uri-start]
  "returns 2nd element of route assuming mount on a prefix"
  (and (.startsWith uri uri-start)
       (nth (string/split #"/" uri) 2 nil)))

(defn remove-from-beginning [uri & parts]
  (subs uri
        (reduce + (map count parts))))

(defn exhibit-routes [request]
  (if (#{"/exhibit" "/exhibit/"} (:uri request))
    (redirect (or (-?>> (query-latest-exhibit)
                        :slug__c
                        (str "/exhibit/"))
                  "/"))
    (if-let [exhibit-slug (parse-mounted-route
                           (:uri request) "/exhibit/")]
      (if-let [exhibit (query-exhibit exhibit-slug)]
        (render
         (condp = (remove-from-beginning (:uri request)
                                         "/exhibit/" exhibit-slug)
           "/apply" (if-let [user (session-get-user request)]
                      (exhibit-apply-view request exhibit)
                      (redirect (str "/login?came-from="
                                     (exhibit-apply-link request exhibit))))
           "" (exhibit-view request exhibit)
           nil)
         request)))))

(defn- wrap-secure-clauses [request conditional & clauses]
  (if-not (even? (count clauses))
    (throw (IllegalArgumentException. "Odd number of clauses"))
    (if (seq clauses)
      (let [[pred body & remaining-clauses] clauses]
        (concat
         [pred `(if-not (session-get-user ~request)
                  (redirect (str "/login?came-from=" (:uri ~request)))
                  (if ~conditional
                    ~body
                    (forbidden ~request)))]
         (apply wrap-secure-clauses request conditional remaining-clauses))))))

(defmacro secure-condp
  "wrap the actions in a condp with security checks"
  [f f-param request conditional & clauses]
  `(condp ~f ~f-param
     ~@(if (even? (count clauses))
         (apply wrap-secure-clauses request conditional clauses)
         (concat
          (apply wrap-secure-clauses request conditional (butlast clauses))
          [(last clauses)]))))

;; need to figure out where to store this
;; maybe just in memory for now
(defn admin? [user] false)

(defn application-owner? [user application]
  (= (:id user) (:contact__c application)))

(defn can-view-application? [user application]
  (or (admin? user) (application-owner? user application)))

(defn application-routes [request]
  (if-let [app-id (parse-mounted-route
                   (:uri request) "/application/")]
    (if-let [application (query-application app-id)]
      (render
       (let [user (session-get-user request)]
         (secure-condp
          = (remove-from-beginning (:uri request) "/application/" app-id)
          request
          (can-view-application? user application)
          "/upload" (condp = (:request-method request)
                      :post (app-upload-image request application)
                      :get (app-upload request application)
                      nil)
          "/caption" (application-save-captions-view request application)
          "/submit" (application-submit-view request application)
          "/success" (application-success-view request application)
          "/update" (application-update-view request application)
          "" (app-view request application)
          nil))
       request))))

(defn image-details [image]
  (let [app (:exhibit_application__r image)]
    (conj ((juxt (comp :slug__c :exhibit__r) :id) app) (:id image))))

(defn image-delete-view [request image]
  (if (= (:request-method request) :post)
    (let [[exhibit-slug application-id image-id]
          (image-details image)]
      (delete-image exhibit-slug application-id image-id)
      "")))

(defn image-routes [request]
  (if-let [image-id (parse-mounted-route
                     (:uri request) "/image/")]
    (if-let [image (query-image image-id)]
      (render
       (let [user (session-get-user request)
             application (:exhibit_application__r image)
             rest-of-uri (remove-from-beginning
                          (:uri request) "/image/" image-id)]
         (if (#{"" "/" "/original"} rest-of-uri)
           (image-view request image "original")
           (secure-condp
            = rest-of-uri
            request
            (can-view-application? user application)
             "/small" (image-view request image "small")
             "/large" (image-view request image "large")
             "/delete" (image-delete-view request image)
             nil)))
       request))))

(defn-debug-memo query-allowed-images
  "filter passed in images to those that current user can view"
  [user image-ids]
  (map
   :id
   (filter
    #(or (admin? user)
         ( = (:id user)
             (:contact__c (:exhibit_application__r
                           (tweak-image-result %)))))
    (sf/query conn image__c [id exhibit_application__r.contact__c]
              [[id IN (str "("
                           (string/join ", "
                                        (map #(str "'" % "'") image-ids))
                           ")") noquote]]))))

(defn reorder-images-view [request order-string]
  (if-let [user (session-get-user request)]
    (if order-string
      (let [image-ids (.split order-string ",")
            allowed-image-ids (query-allowed-images user image-ids)
            image-ids-to-update (filter (set allowed-image-ids)
                                        image-ids)]
        (if (not-empty image-ids-to-update)
          (do
            (sf/update-image-order
             conn
             (map-indexed
              (fn [n image-id]
                {:id image-id
                 :order__c (double (inc n))})
              image-ids-to-update))
            ""))))))

(defn my-applications-view [request]
  (if-let [user (session-get-user request)]
    (layout
     request
     {:title "My applications"}
     (let [userid (:id (session-get-user request))
           apps (query-applications userid)
           apps-by-exhibit (group-by (comp :name :exhibit__r) apps)]
       (if (empty? apps)
         [:p "You have no applications. Perhaps you would like to "
          [:a {:href (exhibits-link request)} "apply"]]
         (list
          (for [[exhibit-name apps] apps-by-exhibit]
            [:div
             [:h2 exhibit-name]
             [:ul
              (for [app (sort-by :lastModifiedDate apps)]
                [:li
                 [:a {:href (application-submit-link (:id app))} (:title__c app)]
                 (if (= (:submission_Status__c app) "Final")
                   " - (submitted)")])]])))))
    (redirect (str "/login?came-from=" (:uri request)))))

(defn about-view [request]
  (layout
   request
   {:title "About the Documentary Photography Project"}
   [:div
    [:h2 "About Documentary Photography"]
    [:p "The Documentary Photography Project uses exhibits, workshops, grantmaking, and public programs to explore how photography can shape public perception and effect social change. The project supports photographers whose work addresses social justice and human rights issues that coincide with OSI's mission of promoting and expanding open society."]
    [:p
     "The project's longest-running activity is "
     [:a {:href "http://www.soros.org/initiatives/photography/focus_areas/mw"} "Moving Walls"]
     ", a group photography exhibition series that features in-depth and nuanced explorations of human rights and social issues.  Moving Walls is shown at OSI offices in New York City and Washington, D.C."]
    [:p
     "The project also sponsors individual photographers through the annual "
     [:a {:href "http://www.soros.org/initiatives/photography/focus_areas/engagement"} "Audience Engagement Grant"]
     " (formerly called the Distribution Grant) and "
     [:a {:href "http://www.soros.org/initiatives/photography/focus_areas/production-individual"} "Production Grants"]
     ". Audience Engagement Grants support photographers who work with an NGO or advocacy organization to use photography as a tool for advocacy, education, or civic engagement. Recent distribution grants supported a traveling exhibit and digital archive addressing statelessness of Nubians in Kenya; a public billboard campaign and website on energy production and consumption in America; an educational campaign on HIV-positive senior citizens; and a publication and series of workshops addressing the socioeconomic realities of young women in Troy, NY."]
    [:p "Production grants help photographers from Central Asia, the Caucasus, Afghanistan, Mongolia, and Pakistan produce work on a social justice or human rights issue in their home country. Production grants are combined with mentorship and training by internationally recognized photographers."]
    [:p
     "The project also provides "
     [:a {:href "http://www.soros.org/initiatives/photography/focus_areas/production"} "grants to organizations"]
     " and projects that: have a broad impact in the photographic community; support photographers from regions that lack advanced-level training or professional opportunities; and respond to changes in the media environment by proposing new models for producing work."]
    [:p "In addition to organizing Moving Walls, the project's recent activities and grantmaking have included an "
     [:a {:href "http://www.soros.org/initiatives/photography/movingwalls/international"} "international tour"]
     " of past Moving Walls photographers in the Middle East and North Africa that included exhibits and trainings for local photographers and young people."]
    [:p.note
     "Note: The Documentary Photography Project does not support film. For information on grants for documentary filmmaking, please contact the "
     [:a {:href "http://www.sundance.org/"} "Sundance Institute"]
     ", an OSI grantee in Los Angeles, California."]]))

(defn not-found-view [request]
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout
          request
          {:title "404 Page not found"}
          [:div
           [:h2 "Page not found"]
           [:p "We could not find the page you are looking for."]])})

(defn cv-view [request app-id]
  (if-let [application (query-application app-id)]
    (if-let [user (session-get-user request)]
      (if (can-view-application? user application)
        (let [exhibit-slug (:slug__c (:exhibit__r application))]
          (if-let [cv (persist/cv-file-path exhibit-slug app-id)]
            (if (.exists cv)
              {:status 200
               :headers {"Content-Disposition" (str "attachment; filename=\""
                                                    (.getName cv)
                                                    "\"")}
               :body cv})))
        (forbidden request))
      (redirect (str "/login?came-from=" (:uri request))))))

(defroutes main-routes
  (GET "/" request home-view)
  (GET "/about" [] about-view)
  (GET "/userinfo" [] userinfo-view)
  (ANY "/login" [] login-view)
  (GET "/logout" [] logout-view)
  (ANY "/profile" request (if-let [user (session-get-user request)]
                            (profile-view request)
                            (redirect (str "/login?came-from="
                                           (:uri request)))))
  (ANY "/register" [] register-view)
  (ANY "/forgot-password" [] forgot-password-view)
  (ANY "/reset-request" [token :as request] (reset-request-view request token))
  (ANY "/reset-password" [] reset-password-view)

  exhibit-routes
  application-routes
  image-routes

  (GET "/cv/:app-id" [app-id :as request] (cv-view request app-id))

  (POST "/reorder-images" [order :as request]
        (reorder-images-view request order))

  (GET "/my-applications" [] my-applications-view)

  (route/files "/public" {:root "src/main/resources/public"})

  not-found-view)

(defn multipart-form? [request]
  (@#'multipart/multipart-form? request))

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

(def app
  (->
   main-routes
   wrap-servlet-session
   wrap-multipart-convert-params
   wrap-multipart-params
   api))

(defn run-server []
  (run-jetty #'app {:port 8080 :join? false}))
