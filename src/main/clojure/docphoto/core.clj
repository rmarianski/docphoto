(ns docphoto.core
  (:use [compojure.core :only (defroutes GET ANY routes routing)]
        [compojure.handler :only (api)]
        [ring.middleware.multipart-params :only (wrap-multipart-params)]
        [hiccup.page-helpers :only (xhtml)]
        [ring.adapter.jetty-servlet :only (run-jetty)]
        [flutter.html4 :only (html4-fields)]
        [flutter.shortcuts :only (wrap-shortcuts)]
        [flutter.params :only (wrap-params)]
        [formidable.core :only (wrap-form-field wrap-errors)]
        [ring.util.response :only (redirect)]
        [decline.core :only
         (validations validation validate-val validate-some)]
        [clojure.contrib.core :only (-?> -?>>)])
  (:require [compojure.route :as route]
            [docphoto.salesforce :as sf]
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
    [:title (:title options "Docphoto")]]
   [:body
    body]))

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
          (redirect "/userinfo"))))))

(defn- query-exhibit [exhibit-slug]
  (first
   (sf/query conn exhibit__c
             [id name description__c slug__c]
             [[slug__c = exhibit-slug]])))

(defn- query-application [app-id]
  (-?>
   (sf/query conn exhibit_application__c
             [id biography__c title__c website__c statementRich__c
              exhibit__r.name exhibit__r.slug__c]
             [[id = app-id]])
   first
   (update-in [:exhibit__r] sf/sobject->map)))

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
  (redirect (str "/application/"
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

;; stubbed out for now
(defn has-permission [user permission] true)

(defn- compojure-route? [form]
  (and (symbol? form)
       (#{'ANY 'GET 'POST 'PUT 'DELETE} form)))

(defn- replace-route [f form]
  (let [[method route bindings body] form]
    `(~method ~route ~bindings ~(f body))))

(defn- wrap-body [f form]
  (if (compojure-route? (first form))
    (replace-route f form)
    (letfn [(walker [form]
                    (if (seq form)
                      (let [[head & tail] form]
                        (if (compojure-route? head)
                          (replace-route f form)
                          (cons
                           (if (list? head)
                             (walker head)
                             head)
                           (walker tail))))))]
      (walker form))))

(defn wrap-route-body
  "higher order utility function for modifying compojure route bodies"
  [f & handlers]
  `(routes
    ~@(map (partial wrap-body f) handlers)))

(defmacro let-route [bindings & handlers]
  (apply wrap-route-body
         (fn [body] `(if-let ~bindings ~body))
         handlers))

(defn forbidden [request]
  {:status 403
   :headers {}
   :body "Forbidden"})

(defmacro protect [request permission & handlers]
  (apply wrap-route-body
         (fn [body]
           `(if-let [user# (session-get-user ~request)]
              (if (has-permission user# ~permission)
                ~body
                (forbidden ~request))
              ;; XXX came from?
              (redirect "/login")))
         handlers))

(defroutes main-routes
  (GET "/" request home-view)
  (GET "/userinfo" [] userinfo-view)
  (ANY "/login" [] login-view)
  (GET "/logout" [] logout-view)
  (ANY "/register" [] register-view)
  (let-route [exhibit (query-exhibit exhibit-slug)]
    (ANY "/exhibit/:exhibit-slug/apply"
         [exhibit-slug :as request] (exhibit-apply-view request exhibit))
    (GET "/exhibit/:exhibit-slug" [exhibit-slug :as request]
         (exhibit-view request exhibit)))
  (GET "/exhibit" [] (redirect (or (-?>> (query-latest-exhibit)
                                         :slug__c
                                         (str "/exhibit/"))
                                   "/")))
  (let-route [application (query-application app-id)]
             (GET "/application/:app-id" [app-id :as request]
                  (app-view request application)))
  (route/files "/public")
  (route/not-found "Page not found"))

(def app
     (-> main-routes
         wrap-servlet-session
         wrap-multipart-params
         api))

(defn run-server []
  (run-jetty #'app {:port 8080 :join? false}))
