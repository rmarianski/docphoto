(ns docphoto.core
  (:use [compojure.core :only (defroutes GET ANY)]
        [compojure.handler :only (api)]
        [ring.middleware.multipart-params :only (wrap-multipart-params)]
        [hiccup.page-helpers :only (xhtml)]
        [ring.adapter.jetty-servlet :only (run-jetty)]
        [formidable.core :only (render)]
        [ring.util.response :only (redirect)]
        [decline.core :only
         (validations validation validate-val validate-some)])
  (:require [compojure.route :as route]
            [docphoto.salesforce :as sf]
            [clojure.contrib.string :as string])
  (:import [org.apache.commons.codec.digest DigestUtils]))

;; global salesforce connection
(defn conn)

(defn md5 [s] (DigestUtils/md5Hex s))

(defn wrap-servlet-session [handler]
  (fn [request]
    (handler
     (if-let [servlet-request (:servlet-request request)]
       (assoc request :session (.getSession servlet-request))
       request))))

(defn user-from-session [request]
  (.getAttribute (:session request) "userid"))

(defn home-view [request]
  (xhtml [:div [:h1 "hi world"]
          [:p (str "user: "
                   (or (user-from-session request)
                       "anonymous"))]]))

(defmethod render :password
  [field value]
  [:input {:type :password
           :name (:name field)
           :value (or value "")}])

(defmacro validate-vals [& val-data]
  `(validations
    ~@(for [[k f error] (partition 3 val-data)]
       `(validate-val ~k ~f {~k ~error}))))

(defn login-validator []
  (validate-vals :username not-empty :required
                 :password not-empty :required))

;; XXX explore strategy of matching salesforce names exactly
;; that way we don't have to adapt keys, just select them
;; although if we're selecting them, might as well just rename them

(defn query-login-info [conn username password]
  (sf/query conn Contact
            [Id UserName__c FirstName LastName Email Phone]
            [[FirstName = username]
             [Password = password]]))

(defn render-login-form
  ([params] (render-login-form params {}))
  ([params errors]
     (xhtml
      [:div
       [:h1 "Login"]
       (render {:type :uniform
                :fields
                [{:type :text :name :username :error (:username errors)}
                 {:type :password :name :password :error (:password errors)}]
                :buttons [{:type :submit :value :login}]}
               params)])))

(defn login [request user]
  (doto (:session request)
    (.setAttribute "userid" (:id user))
    (.setAttribute "user" user)))

(defn login-view [request]
  (let [params (:params request)]
    (if (= (:request-method request) :post)
      (if-let [errors ((login-validator) params)]
        (render-login-form params errors)
        (let [{:keys [username password]} params]
          (if-let [user (query-login-info conn username password)]
            (do
              (login request (sf/sobject->map user))
              (redirect "/"))
            (render-login-form params {:username "Invalid Credentials"}))))
      (render-login-form params))))

(defn userinfo-view [request]
  (xhtml
   [:dl
    (for [[k v] (.getAttribute (:session request) "user")]
      (list [:dt k] [:dd v]))]))

(defn render-register-form
  ([params] (render-register-form params {}))
  ([params errors]
     (xhtml
      [:div
       [:h1 "Register"]
       (render {:type :uniform
                :fields
                [{:type :text :name :username :error (:username errors)}
                 {:type :password :name :password :error (:password errors)}
                 {:type :password :name :password2
                  :label "Password" :error (:password2 errors)}
                 {:type :text :name :first-name
                  :label "First name" :error (:first-name errors)}
                 {:type :text :name :last-name
                  :label "Last name" :error (:last-name errors)}
                 {:type :text :name :email :error (:email errors)}
                 {:type :text :name :phone :error (:phone errors)}]
                :buttons [{:type :submit :value "Register"}]}
               params)])))

(defn register-validator []
  (validations
   (validate-val :Username not-empty {:Username :required})
   (validate-val :Password not-empty {:Password :required})
   (validate-val :Email not-empty {:Email :required})
   (validate-val :Phone not-empty {:Phone :required})
   (validate-val :FirstName not-empty {:FirstName :required})
   (validate-val :LastName not-empty {:LastName :required})))

(defn register-view [request]
  (let [params (:params request)]
    (if (= (:request-method request) :post)
      (if-let [errors ((register-validator) params)]
        (render-register-form params errors)
        (let [{username :Username password :Password
               first-name :FirstName last-name :LastName
               email :Email phone :Phone}
              params]
          (sf/create-contact
           conn
           {:FirstName first-name
            :LastName last-name
            :Email email
            :Phone phone
            :UserName__c username
            :Password__c (md5 password)})
          (redirect "/userinfo")))
      (render-register-form params))))

(defn- make-field-stanza
  ([fieldinfo errors-symbol]
     (if (keyword? fieldinfo)
       (make-field-stanza
        fieldinfo (string/capitalize (name fieldinfo)) errors-symbol)
       (make-field-stanza
        (first fieldinfo) (second fieldinfo) errors-symbol)))
  ([field label errors-symbol]
     `{:type :text :name ~field :label ~label :error (~field ~errors-symbol)}))

(defn- make-form-render-fn [fields options]
  (let [errors (gensym "errors__")]
    `(fn [params# ~errors]
       (xhtml
        (render {:type :uniform
                 :fields
                 [~@(map #(make-field-stanza % errors) fields)]
                 :buttons [{:type :submit
                            :value ~(:submit-label options "Submit")}]}
                params#)))))

(defn- make-validator-stanza [fieldinfo]
  (if (keyword? fieldinfo)
    `(validate-val ~fieldinfo not-empty {~fieldinfo :required})
    (let [[fieldname label & options] fieldinfo
          opts (apply hash-map options)]
      (if (:required opts true)
        `(validate-val ~fieldname not-empty {~fieldname :required})))))

(defn make-form-validator-fn [fields]
  `(validations
    ~@(keep make-validator-stanza fields)))

(defmacro defformpage
  ([fn-name fields bindings body]
     `(defformpage ~fn-name ~fields {} ~bindings ~body))
  ([fn-name fields options bindings body]
     (let [render-fn (gensym "render-fn__")
           validator-fn (gensym "validator-fn__")
           params (gensym "params__")
           request (gensym "request__")]
       `(let [~render-fn ~(make-form-render-fn fields options)
              ~validator-fn ~(make-form-validator-fn fields)]
          (defn ~fn-name [~request]
            (let [~params (:params ~request)]
              (if (= (:request-method ~request) :post)
                (if-let [errors# (~validator-fn ~params)]
                  (~render-fn ~params errors#)
                  (let [~@bindings {:render ~render-fn
                                    :params ~params
                                    :request ~request}]
                    ~body))
                (~render-fn ~params {}))))))))

(defn register [register-map]
  (println "registered:" register-map))

(defformpage register-page
  [[:userName__c "Username"]
   [:password__c "Password"]
   [:password2 "Password"]
   [:firstName "First Name"]
   [:lastName "Last Name"]
   :email
   :phone]
  [{render-form-fn :render params :params}]
  (let [{password1 :password__c password2 :password2} params]
    (if (not (= password1 password2))
      (render-form-fn params {:password__c "Passwords don't match"})
      (do
        (register (dissoc params :password2))
        (redirect "/userinfo")))))

(defroutes main-routes
  (GET "/" [] home-view)
  (ANY "/login" [] login-view)
  (ANY "/userinfo" [] userinfo-view)
  (ANY "/register" [] register-view)
  (ANY "/macro-register" [] register-page)
  (route/files "/public")
  (route/not-found "Page not found"))

(def app
     (-> main-routes
         wrap-servlet-session
         wrap-multipart-params
         api))

(defn run-server []
  (run-jetty #'app {:port 8080 :join? false}))
