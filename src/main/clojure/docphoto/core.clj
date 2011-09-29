(ns docphoto.core
  (:use [compojure.core :only (defroutes GET POST ANY routes routing)]
        [compojure.handler :only (api)]
        [compojure.response :only (render)]
        [ring.middleware.multipart-params :only (wrap-multipart-params)]
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
            [clojure.contrib.string :as string]
            [clojure.walk])
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
             [id username__c firstname lastname name email phone]
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

(defn layout [options body]
  (xhtml
   [:head
    [:title (:title options "Docphoto")]
    (apply include-css (:css options))
    (apply include-js (:js options))]
   [:body (list body
                (map javascript-tag (:js-script options)))]))

(defn- make-fields-render-fn [fields options]
  (let [field (gensym "field__")]
    `(fn [params# errors#]
       (let [~field (-> html4-fields
                        wrap-form-field
                        wrap-shortcuts
                        (wrap-errors errors#)
                        (wrap-params params#))]
         (list
          ~@(map (fn [fieldinfo]
                   (if-let [fieldspec (:field fieldinfo)]
                     `(~field ~@fieldspec)))
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

(defn- exhibit-link [request exhibit]
  (str "/exhibit/" (:slug__c exhibit)))

(defn- exhibits-html [request]
  (let [exhibits (query-exhibits)]
    (if (not-empty exhibits)
      [:ul
       (map #(vector :li [:a {:href (exhibit-link request %)} (:name %)])
            exhibits)])))

(defn- application-link [application-id]
  (str "/application/" application-id))

(defn- image-link [image-id] (str "/image/" image-id))

(defn home-view [request]
  (layout
   {}
   [:div
    [:p (str "user: "
             (or (-?> (session-get-user request) :name)
                 "anonymous"))]
    (exhibits-html request)]))

(defmacro validate-vals [& val-data]
  `(validations
    ~@(for [[k f error] (partition 3 val-data)]
       `(validate-val ~k ~f {~k ~error}))))

(defn userinfo-view [request]
  (layout
   {}
   [:dl
    (for [[k v] (.getAttribute (:session request) "user")]
      (list [:dt k] [:dd v]))]))

(defn register [register-map]
  (sf/create-contact conn register-map))

(defn create-application [application-map]
  (sf/create-application conn application-map))

(defn create-image [image-map]
  (sf/create-image conn image-map))

(defn login [request user]
  (session-save-user request user))

(defn logout [request]
  (session-delete request))

(defformpage login-view
  [{:field [:text {} :userName__c {:label "Username"}] :validator {:fn not-empty :msg :required}}
   {:field [:password {} :password__c {:label "Password"}] :validator {:fn not-empty :msg :required}}]
  [{:keys [render-fields request params errors]}]
  (layout
   {}
   [:form {:method :post :action "/login"}
    [:h2 "Login"]
    (when-let [user (session-get-user request)]
      [:p (str "Already logged in as: " (:name user))])
    (render-fields params errors)
    [:input {:type :submit :value "Login"}]])
  [{render-form-fn :render-form params :params request :request}]
  (if-let [user (query-user (:userName__c params) (md5 (:password__c params)))]
    (do (login request user) (redirect "/userinfo"))
    (render-form-fn params {:userName__c "Invalid Credentials"})))

(defn logout-view [request] (logout request) (redirect "/login"))

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
  [{:keys [render-fields params errors]}]
  (layout
   {}
   [:form {:method :post :action "/register"}
    [:h2 "Register"]
    (render-fields params errors)
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
  ([app-id]
     (-?>
      (sf/query conn exhibit_application__c
                [id biography__c title__c website__c statementRich__c
                 exhibit__r.name exhibit__r.slug__c]
                [[id = app-id]])
      first
      tweak-application-result))
  ([app-id request]
     (or (session-get-application request app-id)
         (if-let [application (query-application app-id)]
           (do (session-save-application request app-id application)
               application)))))

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
        [id title__c exhibit__r.name exhibit__r.slug__c]
        [[exhibit_application__c.exhibit__r.closed__c = false noquote]
         [exhibit_application__c.contact__r.id = userid]])))

(defn query-images [application-id]
  (sf/query conn image__c
            [id caption__c]
            [[exhibit_application__c = application-id]]
            :append "order by order__c"))

(defn exhibit-view [request exhibit]
  (if exhibit
    (layout {}
            [:div
            [:h1 (:name exhibit)]
            [:p (:description__c exhibit)]])))

(defformpage exhibit-apply-view
  [{:field [:text {} :statementRich__c {:label "Project Statement"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :title__c {:label "Project Title"}] :validator {:fn not-empty :msg :required}}
   {:field [:text {} :biography__c {:label "Short Narrative Bio"}] :validator {:fn not-empty :msg :required}}
   ;; cv
   {:field [:text {} :website__c {:label "Website"}]}]
  {:fn-bindings [exhibit]}
  [{:keys [render-fields request params errors]}]
  (if exhibit
    (xhtml [:div
            [:h1 (str "Apply to " (:name exhibit))]
            [:form {:method :post :action (:uri request)}
             [:fieldset
              [:legend "Apply"]
              (render-fields params errors)]
             [:input {:type :submit :value "Apply"}]]]))
  [{:keys [params request]}]
  (redirect
   (str "/application/"
        (create-application
         (merge
          params
          {:contact__c (:id (session-get-user request))
           :exhibit__c (:id exhibit)})))))

(defn app-view [request application]
  (layout
   {:title (:title__c application)}
   (list [:h2 (:title__c application)]
         [:dl
          (for [[k v] application]
            (list
             [:dt k]
             [:dd v]))])))

(defn display-images [request images]
  [:ul
   (for [image images]
     [:li
      [:img {:src (image-link (:id image))}]
      [:p (:caption__c image)]])])

(defn app-upload [request application]
  (layout
   {:title "Upload images"
    :css ["/public/docphoto.css"]
    :js ["/public/plupload/js/plupload.full.js"
         "http://localhost:9810/compile?id=docphoto"]
    :js-script
    [(format (str "new docphoto.Uploader('plupload', 'pick-files', "
                  "'upload', 'files-list', {url: \"%s\"});")
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
    (display-images request (query-images (:id application))))))

(defn app-upload-image [request application]
  (let [params (:params request)
        [filename content-type tempfile] ((juxt :filename :content-type :tempfile)
                                          (params "file"))
        exhibit-slug (:slug__c (:exhibit__r application))
        application-id (:id application)
        image-id (create-image
                  {:filename__c filename
                   :mime_type__c content-type
                   :exhibit_application__c application-id
                   :order__c (-> (query-images application-id)
                                 count inc double)})]
    (persist/persist-image-chunk tempfile exhibit-slug application-id image-id)
    {:status 200}))

(defn image-view [request image]
  (let [f (file
           (persist/image-file-path
            (-> image :exhibit_application__r :exhibit__r :slug__c)
            (:id (:exhibit_application__r image))
            (:id image)))]
    (if (.exists f)
      {:headers {"Content-Type" (:mime_type__c image)}
       :status 200
       :body f})))

(defn forbidden [request]
  {:status 403
   :headers {}
   :body "Forbidden"})

(defn parse-exhibit-slug [uri]
  (and (.startsWith uri "/exhibit/")
       (nth (string/split #"/" uri) 2 nil)))

(defn parse-application-id [uri]
  (and (.startsWith uri "/application/")
       (nth (string/split #"/" uri) 2 nil)))

(defn parse-image-id [uri]
  (and (.startsWith uri "/image/")
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
    (if-let [exhibit-slug (parse-exhibit-slug (:uri request))]
      (if-let [exhibit (query-exhibit exhibit-slug)]
        (render
         (condp = (remove-from-beginning (:uri request) "/exhibit/" exhibit-slug)
           "/apply" (exhibit-apply-view request exhibit)
           "" (exhibit-view request exhibit)
           nil)
         request)))))

(defn application-routes [request]
  (if-let [app-id (parse-application-id (:uri request))]
    (if-let [application (query-application app-id request)]
      (render
       (condp = (remove-from-beginning (:uri request) "/application/" app-id)
         "/upload" (condp = (:request-method request)
                     :post (app-upload-image request application)
                     :get (app-upload request application)
                     nil)
         "" (app-view request application)
         nil)
       request))))

(defn image-routes [request]
  (if-let [image-id (parse-image-id (:uri request))]
    (if-let [image (query-image image-id)]
      (render
       (condp = (remove-from-beginning (:uri request) "/image/" image-id)
         "" (image-view request image)
         nil)
       request))))

(defn my-applications-view [request]
  (layout
   {:title "My applications"}
   (let [userid (:id (session-get-user request))
         apps (query-applications userid)
         apps-by-exhibit (group-by (comp :name :exhibit__r) apps)]
     (list
      (for [[exhibit-name apps] apps-by-exhibit]
        [:div
         [:h2 exhibit-name]
         [:ul
          (for [app apps]
            [:li [:a {:href (application-link (:id app))} (:title__c app)]])]])))))

(defroutes main-routes
  (GET "/" request home-view)
  (GET "/userinfo" [] userinfo-view)
  (ANY "/login" [] login-view)
  (GET "/logout" [] logout-view)
  (ANY "/register" [] register-view)

  exhibit-routes
  application-routes
  image-routes

  (GET "/my-applications" [] my-applications-view)

  (route/files "/public" {:root "src/main/resources/public"})
  (route/not-found "Page not found"))

(def app
     (-> main-routes
         wrap-servlet-session
         wrap-multipart-params
         api))

(defn run-server []
  (run-jetty #'app {:port 8080 :join? false}))

(def tmprequest (atom []))
