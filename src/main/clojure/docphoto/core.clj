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
        [formidable.core :only (wrap-form-field wrap-errors)]
        [ring.util.response :only (redirect)]
        [decline.core :only
         (validations validation validate-val validate-some)]
        [clojure.contrib.core :only (-?> -?>>)]
        [clojure.contrib.trace :only (deftrace)]
        [clojure.java.io :only (copy file input-stream output-stream)])
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

(defn layout [request options body]
  (xhtml
   [:head
    [:title (:title options "Docphoto")]
    (apply include-css (:css options))
    (apply include-js (:js options))]
   [:body (list (login-logout-snippet request)
                body
                (map javascript-tag (:js-script options)))]))

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
    ["/public/docphoto.css"]))

(defn- make-fields-render-fn [fields options]
  (let [field (gensym "field__")
        params (gensym "params__")
        request (gensym "request__")
        errors (gensym "errors__")]
    `(fn [~request ~params ~errors]
       (let [~field (-> html4-fields
                        wrap-form-field
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
  (str (application-link application-id) "/cv"))

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
  [:div.login-logout
   (if-let [user (session-get-user request)]
     [:a {:href "/logout"} (str "Logout " (:userName__c user))]
     [:a {:href "/login"} "Login"])])

(defn home-view [request]
  (layout
   request
   {:title "Documentary Photography Project"}
   [:div (exhibits-html request)]))

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
   [:form {:method :post :action "/login"}
    [:h2 "Login"]
    (if-let [user (session-get-user request)]
      [:p (str "Already logged in as: " (:name user))])
    (render-fields request params errors)
    [:input {:type :submit :value "Login"}]])
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
   [:form {:method :post :action (profile-update-link request)}
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
   [:form {:method :post :action "/register"}
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

(defn- tweak-application-result [application]
  (update-in application [:exhibit__r] sf/sobject->map))

(defn- tweak-image-result [image]
  (->
   (update-in image [:exhibit_application__r] sf/sobject->map)
   (update-in [:exhibit_application__r :exhibit__r] sf/sobject->map)))

(defn- query-application
  [app-id]
  (-?>
   (sf/query conn exhibit_application__c
             [id biography__c title__c website__c statementRich__c
              exhibit__r.name exhibit__r.slug__c]
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

(defn- query-applications [userid]
  (map tweak-application-result
       (sf/query
        conn
        exhibit_application__c
        [id title__c exhibit__r.name exhibit__r.slug__c
         lastModifiedDate submission_Status__c]
        [[exhibit_application__c.exhibit__r.closed__c = false noquote]
         [exhibit_application__c.contact__r.id = userid]])))

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
      :js ["http://localhost:9810/compile?id=docphoto"]
      :js-script ["docphoto.removeAnchorTargets();"]}
     [:div
      [:h1 (:name exhibit)]
      [:p (:description__c exhibit)]])))

(defformpage exhibit-apply-view
  [{:field [:text {} :title__c {:label "Project Title"}] :validator {:fn not-empty :msg :required}}
   {:field [:text-area#statement.editor {} :statementRich__c {:label "Project Statement"}] :validator {:fn not-empty :msg :required}}
   {:field [:text-area#biography.editor {} :biography__c {:label "Short Narrative Bio"}] :validator {:fn not-empty :msg :required}}
   {:field [:file {} :cv {:label "Upload CV"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :website__c {:label "Website"}]}]
  {:fn-bindings [exhibit]}
  [{:keys [render-fields request params errors]}]
  (if exhibit
    (layout
     request
     {:title (str "Apply to " (:name exhibit))
      :css (editor-css)
      :js ["http://localhost:9810/compile?id=docphoto"]
      :js-script
      ["docphoto.editor.triggerEditors();"]}
     [:div
      [:h1 (str "Apply to " (:name exhibit))]
      [:form {:method :post :action (:uri request)
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
   {:field [:file {} :cv {:label "Upload CV"}]}
   {:field [:text {} :website__c {:label "Website"}]}]
  {:fn-bindings [application]}
  [{:keys [render-fields request params errors]}]
  (layout
   request
   {:title (str "Update application")
    :css (editor-css)
    :js ["http://localhost:9810/compile?id=docphoto"]
    :js-script
    ["docphoto.editor.triggerEditors();"]}
   [:div
    [:form {:method :post :action (:uri request)
            :enctype "multipart/form-data"}
     [:fieldset
      [:legend "Update application"]
      (render-fields request (merge application params) errors)]
     [:input {:type :submit :value "Update"}]]])
  [{:keys [params request]}]
  (do
    (let [app-id (:id application)
          app-update-map (merge (dissoc params :cv)
                                {:id app-id})]
      ;; XXX need to update the cv also if it's there
      (sf/update-application conn app-update-map)
      (session-save-application request app-id app-update-map)
      (redirect
       (or (:came-from params)
           (application-submit-link app-id))))))

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
  [:div
   [:img {:src (image-link (:id image) "small")}]
   [:textarea {:name (str "caption-" (:id image))}
    (or (:caption__c image) "")]
   [:a {:href (image-delete-link (:id image))
        :class "image-delete"} "Delete"]])

(defn render-images [request images]
  [:ul
   (for [image images]
     [:li (render-image request image)])])

(defn app-upload [request application]
  (layout
   request
   {:title "Upload images"
    :css ["/public/docphoto.css"]
    :js ["/public/js/plupload/js/plupload.full.js"
         "http://localhost:9810/compile?id=docphoto"]
    :js-script
    [(format (str "new docphoto.Uploader('plupload', 'pick-files', "
                  "'upload', 'files-list', 'images', {url: \"%s\"});")
             (:uri request))]}
   (list
    [:h2 "Upload images"]
    [:form {:method :post :action (:uri request)}
     [:div#plupload
      [:div#files-list
       (str "Your browser doesn't have Flash, Silverlight, Gears, BrowserPlus "
            "or HTML5 support.")]
      [:a#pick-files {:href "#"} "Select files"]
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
       [:div
        [:fieldset
         [:legend "Contact info"]
         (let [user (session-get-user request)]
           (list
            [:h2 (:name user)]
            [:p (:email user)]
            [:p (:phone user)]
            [:div
             (:mailingStreet user) [:br]
             (:mailingCity user) ", " (:mailingState user) " "
             (:mailingPostalCode user) [:br]
             (:mailingCountry user)]
            [:a {:href (profile-update-link request
                                            (:uri request))} "Update"]))]
        [:fieldset
         [:legend "Application"]
         [:h2 (:title__c application)]
         [:div (:statementRich__c application)]
         [:div (:biography__c application)]
         [:a {:href (cv-link app-id)} "CV"]
         [:p (:website__c application "No website")]
         [:a {:href (application-update-link app-id request)} "Update"]]
        [:fieldset
         [:legend "Images"]
         [:ol
          (for [image (query-images app-id)]
            [:li
             [:img {:src (image-link (:id image) "small")}]
             [:span (:caption__c image)]])]
         [:a {:href (application-upload-link app-id)} "Update"]]
        [:form {:method :post :action (application-submit-link app-id)}
         [:input {:type "submit" :value "Submit"}]]]))))

(defn application-success-view [request application]
  (layout
   request
   {:title "Thank you for your submission"}
   [:div
    [:h1 "Thank you for your submission"]
    [:p "You can view all your "
     [:a {:href (my-applications-link request)} "applications"]]]))

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

(defroutes main-routes
  (GET "/" request home-view)
  (GET "/userinfo" [] userinfo-view)
  (ANY "/login" [] login-view)
  (GET "/logout" [] logout-view)
  (ANY "/profile" [] profile-view)
  (ANY "/register" [] register-view)

  exhibit-routes
  application-routes
  image-routes

  (POST "/reorder-images" [order] (reorder-images-view order))

  (GET "/my-applications" [] my-applications-view)

  (route/files "/public" {:root "src/main/resources/public"})
  (route/not-found "Page not found"))

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
