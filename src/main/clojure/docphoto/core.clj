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
        [clojure.string :only (capitalize)]
        [clojure.contrib.string :only (as-str)])
  (:require [compojure.route :as route]
            [docphoto.salesforce :as sf]
            [docphoto.persist :as persist]
            [docphoto.image :as image]
            [docphoto.config :as cfg]
            [clojure.contrib.string :as string]
            [clojure.walk]
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

(defn session-get-user [request]
  (.getAttribute (:session request) "user"))

(defn session-save-user [request user]
  (.setAttribute (:session request) "user" user))

(defn session-delete [request]
  (.invalidate (:session request)))

(defn session-get-application [request application-id]
  (get (.getAttribute (:session request) "applications") application-id))

(defn session-save-application [request application-id application]
  (let [session (:session request)]
    (.setAttribute session
                   "applications"
                   (assoc (or (.getAttribute session "applications") {})
                     application-id application))
    application-id))

(defn- query-user [username password]
  (first
   (sf/query conn contact
             sf/user-fields
             [[username__c = username]
              [password__c = password]])))

(defn- query-exhibits []
  (sf/query conn exhibit__c
            [id name slug__c application_start_date__c description__c]
            [[closed__c = false noquote]]))

(defn- query-latest-exhibit []
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

(defn- query-applications [userid]
  (map tweak-application-result
       (sf/query
        conn
        exhibit_application__c
        [id title__c exhibit__r.name exhibit__r.slug__c
         lastModifiedDate submission_Status__c]
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
       (theme-menu (:uri request))]]
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
       (or (:label opts) (capitalize (as-str name)))]
      (when-let [desc (:description opts)]
        [:p.formHint desc])
      (f type attrs name (:opts opts) value)
      (when-let [error (:error opts)]
        [:div.error error]))]))

(defn wrap-textinput-classes [f]
  "add a textInput class to text inputs"
  (fn [type attrs name opts value]
    (if (#{:text :password} type)
      (f type (if (:class attrs)
                (str (:class attrs) " textInput")
                (assoc attrs :class "textInput"))
         name opts value)
      (f type attrs name opts value))))

(defn- make-fields-render-fn [fields options]
  (let [field (gensym "field__")
        params (gensym "params__")
        request (gensym "request__")
        errors (gensym "errors__")]
    `(fn [~request ~params ~errors]
       (let [~field (-> html4-fields
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
           request (gensym "request__")]
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

(defn- exhibits-link [request] (str "/exhibit/"))

(defn- exhibit-link [request exhibit]
  (str (exhibits-link request) (:slug__c exhibit)))

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
    [:h2 "Welcome"]
    [:p "Here is some introductory text, briefly describing docphoto"]
    [:h3 {:style "margin-top: 2em"} "Competitions open for application"]
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
  [{:field [:text {} :userName__c {:label "Username"}] :validator {:fn not-empty :msg :required}}
   {:field [:password {} :password__c {:label "Password"}] :validator {:fn not-empty :msg :required}}
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
      [:input {:type :submit :value "Login"}]]]))
  [{render-form-fn :render-form params :params request :request}]
  (if-let [user (query-user (:userName__c params) (md5 (:password__c params)))]
    (do (login request user)
        (redirect (if-let [came-from (:came-from params)]
                    (if (.endsWith came-from "/login") "/" came-from)
                    "/")))
    (render-form-fn params {:userName__c "Invalid Credentials"})))

(defn logout-view [request] (logout request) (redirect "/login"))

(defformpage profile-view
  [{:field [:text {} :firstName {:label "First Name"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :lastName {:label "Last Name"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :email {:label "Email"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :phone {:label "Phone"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :mailingStreet {:label "Address"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :mailingCity {:label "City"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :mailingState {:label "State"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :mailingPostalCode {:label "Postal Code"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :mailingCountry {:label "Country"}] :validator {:fn not-empty :msg :required}}
   {:custom came-from-field}]
  [{:keys [render-fields request params errors]}]
  (layout
   request
   {}
   [:form.uniForm {:method :post :action (profile-update-link request)}
    [:h2 "Update profile"]
    (render-fields request (merge (session-get-user request) params) errors)
    [:input {:type :submit :value "Update"}]])
  [{render-form-fn :render-form params :params request :request}]
  (let [user (session-get-user request)
        user-params (select-keys params sf/contact-fields)]
    (sf/update-user conn (merge {:id (:id user)} user-params))
    (session-save-user request (merge user user-params))
    (redirect (or (:came-from params) "/"))))

(defformpage register-view
  [{:field [:text {} :userName__c {:label "Username"}] :validator {:fn not-empty :msg :required}}
   {:field [:password {} :password__c {:label "Password"}] :validator {:fn not-empty :msg :required}}
   {:field [:password {} :password2 {:label "Password"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :firstName {:label "First Name"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :lastName {:label "Last Name"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :email {:label "Email"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :phone {:label "Phone"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :mailingStreet {:label "Address"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :mailingCity {:label "City"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :mailingState {:label "State"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :mailingPostalCode {:label "Postal Code"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :mailingCountry {:label "Country"}] :validator {:fn not-empty :msg :required}}]
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
          (register (update-in (dissoc params :password2)
                               [:password__c] md5))
          (login request (query-user username (md5 password1)))
          (redirect "/exhibit"))))))

(defn- query-exhibit [exhibit-slug]
  (first
   (sf/query conn exhibit__c
             [id name description__c slug__c]
             [[slug__c = exhibit-slug]])))

(defn- query-application
  [app-id]
  (-?>
   (sf/query conn exhibit_application__c
             [id biography__c title__c website__c statementRich__c
              submission_Status__c exhibit__r.name exhibit__r.slug__c]
             [[id = app-id]])
   first
   tweak-application-result))

(defn- query-image [image-id]
  (-?>
   (sf/query conn image__c
             [id caption__c filename__c mime_type__c order__c
              exhibit_application__r.id
              exhibit_application__r.exhibit__r.slug__c]
             [[id = image-id]])
   first
   tweak-image-result))

(defn query-images [application-id]
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

(defformpage exhibit-apply-view
  [{:field [:text {} :title__c {:label "Project Title"}] :validator {:fn not-empty :msg :required}}
   {:field [:text-area#statement.editor {} :statementRich__c {:label "Project Statement"}] :validator {:fn not-empty :msg :required}}
   {:field [:text-area#biography.editor {} :biography__c {:label "Short Narrative Bio"}] :validator {:fn not-empty :msg :required}}
   {:field [:file {} :cv {:label "Upload CV"}] :validator {:fn filesize-not-empty :msg :required}}
   {:field [:text {} :website__c {:label "Website"}]}]
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
  [{:field [:text {} :title__c {:label "Project Title"}] :validator {:fn not-empty :msg :required}}
   {:field [:text-area#statement.editor {} :statementRich__c {:label "Project Statement"}] :validator {:fn not-empty :msg :required}}
   {:field [:text-area#biography.editor {} :biography__c {:label "Short Narrative Bio"}] :validator {:fn not-empty :msg :required}}
   {:field [:file {} :cv {:label "Update CV"}]}
   {:field [:text {} :website__c {:label "Website"}]}]
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
    [:img {:src (image-link (:id image) "small")}]]
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
                            "{url: \"%s\"});")
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
          [:dd [:a {:href (cv-link app-id)} "Download CV"]]]
         [:a {:href (application-update-link app-id request)} "Update"]]
        [:fieldset
         [:legend "Images"]
         [:ol
          (for [image (query-images app-id)]
            [:li
             [:div.image-container.goog-inline-block
              [:img {:src (image-link (:id image) "small")}]]
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
   :headers {}
   :body "Forbidden"})

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
           "/apply" (exhibit-apply-view request exhibit)
           "" (exhibit-view request exhibit)
           nil)
         request)))))

(defn application-routes [request]
  (if-let [app-id (parse-mounted-route
                   (:uri request) "/application/")]
    (if-let [application (query-application app-id)]
      (render
       (condp = (remove-from-beginning (:uri request) "/application/" app-id)
         "/upload" (condp = (:request-method request)
                     :post (app-upload-image request application)
                     :get (app-upload request application)
                     nil)
         "/caption" (application-save-captions-view request application)
         "/submit" (application-submit-view request application)
         "/success" (application-success-view request application)
         "/update" (application-update-view request application)
         "" (app-view request application)
         nil)
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
       (let [rest-of-uri (remove-from-beginning
                          (:uri request) "/image/" image-id)]
         (if (#{"" "/" "/original"} rest-of-uri)
           (image-view request image "original")
           (condp = rest-of-uri
             "/small" (image-view request image "small")
             "/large" (image-view request image "large")
             "/delete" (image-delete-view request image)
             nil)))
       request))))

(defn reorder-images-view [order-string]
  (and
   order-string
   (let [image-ids (.split order-string ",")]
     (sf/update-image-order
      conn
      (map-indexed
       (fn [n image-id]
         {:id image-id
          :order__c (double (inc n))})
       image-ids))
     "")))

(defn my-applications-view [request]
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
                 " - (submitted)")])]]))))))

(defn about-view [request]
  (layout
   request
   {:title "About the Documentary Photography Project"}
   [:div
    [:h2
     "About Documentary Photography"]
    [:p (lorem-ipsum)]]))

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
    (let [exhibit-slug (:slug__c (:exhibit__r application))]
      (if-let [cv (persist/cv-file-path exhibit-slug app-id)]
        (if (.exists cv)
          {:status 200
           :headers {"Content-Disposition" (str "attachment; filename=\""
                                                (.getName cv)
                                                "\"")}
           :body cv})))))

(defroutes main-routes
  (GET "/" request home-view)
  (GET "/about" [] about-view)
  (GET "/userinfo" [] userinfo-view)
  (ANY "/login" [] login-view)
  (GET "/logout" [] logout-view)
  (ANY "/profile" [] profile-view)
  (ANY "/register" [] register-view)

  exhibit-routes
  application-routes
  image-routes
  (GET "/cv/:app-id" [app-id :as request] (cv-view request app-id))

  (POST "/reorder-images" [order] (reorder-images-view order))

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
     (-> main-routes
         wrap-servlet-session
         wrap-multipart-convert-params
         wrap-multipart-params
         api))

(defn run-server []
  (run-jetty #'app {:port 8080 :join? false}))
