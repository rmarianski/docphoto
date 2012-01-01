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
                                send-message onpost when-logged-in)]
        [docphoto.form :only (defformpage came-from-field
                               req-textfield textfield)])
  (:require [compojure.route :as route]
            [docphoto.salesforce :as sf]
            [docphoto.persist :as persist]
            [docphoto.image :as image]
            [docphoto.config :as cfg]
            [docphoto.session :as session]
            [clojure.string :as string]
            [clojure.walk]
            [hiccup.page-helpers :as ph])
  (:import [java.io File]))

;; global salesforce connection
(defonce conn nil)

(defn-debug-memo query-user [username password]
  (first
   (sf/query conn contact
             sf/user-fields
             [[username__c = username]
              [password__c = password]])))

(defn-debug-memo query-user-by-email [email]
  (first (sf/query conn contact [id] [[email = email]])))

(defn-debug-memo query-user-by-id [id]
  (first (sf/query conn contact sf/user-fields [[id = id]])))

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
                   (file "./resources/public/css/" path)))))]
    (concat (files-in "google")
            (files-in "google/editor")
            ["/public/css/docphoto.css"])))

(defmacro editor-css []
  (if cfg/debug
    (vec (list-all-editor-css-files))
    ["/public/css/docphoto-min.css"]))

(defmacro theme-css [editor-css?]
  (if cfg/debug
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

(defn absolute-link [request url]
  (str (subs (str (:scheme request)) 1) "://" (:server-name request)
       (if-not (= 80 (:server-port request))
         (str ":" (:server-port request)))
       url))

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
  (let [user (session/get-user request)]
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

(defn- exhibits-link [request] (str "/exhibit/"))

(defn- exhibit-link [request exhibit]
  (str (exhibits-link request) (:slug__c exhibit)))

(defn- exhibit-apply-link [request exhibit]
  (str (exhibit-link request exhibit) "/apply"))

(defn forgot-link [request] "/password/forgot")
(defn reset-request-link [request] "/password/request-reset")
(defn reset-password-link [request] "/password/reset")

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
 (session/save-user request user))

(defn logout [request]
  (session/delete request))

(defformpage login-view []
  [(req-textfield :userName__c "Username")
   {:field [:password {} :password__c {:label "Password"}]
    :validator {:fn not-empty :msg :required}}
   {:custom came-from-field}]
  (layout
   request
   {}
   (list
    [:h2 "Login"]
    [:form.uniForm {:method :post :action "/login"}
     [:fieldset
      (if-let [user (session/get-user request)]
        [:p (str "Already logged in as: " (:name user))])
      (render-fields request params errors)
      [:input {:type :submit :value "Login"}]]]
    [:div
     [:p#forgot-password.note
      "Forgot your password? "
      (ph/link-to (forgot-link request) "Reset") " it."]]))
  (if-let [user (query-user (:userName__c params) (md5 (:password__c params)))]
    (do (login request user)
        (redirect (if-let [came-from (:came-from params)]
                    (if (.endsWith came-from "/login") "/" came-from)
                    "/")))
    (render-form params {:userName__c "Invalid Credentials"})))

(defn logout-view [request] (logout request) (redirect "/login"))

(defformpage profile-view []
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
  (layout
   request
   {}
   [:form.uniForm {:method :post :action (profile-update-link request)}
    [:h2 "Update profile"]
    (render-fields request (user-update-mailinglist-value
                            (merge (session/get-user request) params)) errors)
    [:input {:type :submit :value "Update"}]])
  (let [user (session/get-user request)
        user-params (user-update-mailinglist-value
                     (select-keys params sf/contact-fields))]
    (sf/update-user conn (merge {:id (:id user)} user-params))
    (session/save-user request (merge user user-params))
    (redirect (or (:came-from params) "/"))))

(defformpage register-view []
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
  (layout
   request
   {}
   [:form.uniForm {:method :post :action "/register"}
    [:h2 "Register"]
    (render-fields request params errors)
    [:input {:type :submit :value "Register"}]])
  (let [{password1 :password__c password2 :password2
         username :userName__c} params]
    (if (not (= password1 password2))
      (render-form params {:password__c "Passwords don't match"})
      (if-let [user (first
                     (sf/query conn contact
                               [id] [[userName__c = username]]))]
        (render-form params {:userName__c "User already exists"})
        (do
          (register (-> (dissoc params :password2)
                        (update-in [:password__c] md5)
                        user-update-mailinglist-value))
          (login request (query-user username (md5 password1)))
          (redirect "/exhibit"))))))

(defn reset-password-message [reset-link]
  (str "Hi,

If you did not initiate a docphoto password reset, then please ignore this message.

To reset your password, please click on the following link:
" reset-link))

(defmacro send-email-reset [request email token]
  (let [reset-link-sym (gensym "resetlink__")]
    `(let [~reset-link-sym (absolute-link ~request
                                          (str (reset-request-link ~request)
                                               "?token=" ~token))]
       ~(if cfg/debug
          `(println "Password sent to:" ~email "with link:" ~reset-link-sym)
          `(send-message
            {:to ~email
             :subject "Password reset"
             :body (reset-password-message ~reset-link-sym)})))))

(let [generator (java.util.Random.)]
  (defn generate-reset-token [email]
    (str (.nextInt generator))))

(defformpage forgot-password-view []
  [(req-textfield :email "Email address")]
  (layout
   request
   {:title "Reset password"}
   [:form.uniForm {:method :post :action (:uri request)}
    [:h2 "Password Reset"]
    [:p
     "You will receive a link that will allow you to reset your password. You must use the same browser session in order to reset your password."]
    (render-fields request params errors)
    [:input {:type :submit :value "Reset"}]])
  (let [email (:email params)]
    (if-let [user (query-user-by-email email)]
      (let [reset-token (generate-reset-token email)]
        (session/save-token request reset-token (:id user))
        (send-email-reset request email reset-token)
        (layout request {:title "Email sent"}
                [:div
                 [:p "An email has been sent to: " (:email params)]]))
      (render-form params {:email "Email not found"}))))

(defn reset-request-view [request token]
  (letfn [(reset-failure-page [msg]
            (layout request {:title "Reset failure"}
                    [:div
                     [:h2 "Password reset failed"]
                     [:p msg]]))]
    (if-not token
      (reset-failure-page "No token found. Please double check the link in your email.")
      (if-let [session-token (session/get-token request)]
        (if (= (:token session-token) token)
          (do
            (session/allow-password-reset request (:userid session-token))
            (redirect (reset-password-link request)))
          (do (println "session" (:token session-token) "passed" token)
              (reset-failure-page "Invalid token. Please double check the link in your email.")))
        (reset-failure-page [:span "Token expired. Are you using the same browser session as when you requested a password reset? If so, you can "
                             (ph/link-to (forgot-link request) "resend")
                             " a password reset email."])))))

(defformpage reset-password-view []
  [{:field [:password {} :password1 {:label "Password"}]
    :validator {:fn not-empty :msg :required}}
   {:field [:password {} :password2 {:label "Password again"}]
    :validator {:fn not-empty :msg :required}}]
  (if-let [user (-?> (session/password-reset-userid request)
                     query-user-by-id)]
    (layout
     request
     {:title "Reset password"}
     [:form.uniForm {:method :post :action (:uri request)}
      [:h2 "Password Reset"]
      [:p "Resetting password for user " (:userName__c user)]
      (render-fields request params errors)
      [:input {:type :submit :value "Reset"}]])
    (redirect (forgot-link request)))
  (if-let [userid (session/password-reset-userid request)]
    (if (= (:password1 params) (:password2 params))
      (do
        (sf/update-user conn {:id userid
                              :password__c (md5 (:password1 params))})
        (session/remove-allow-password-reset request)
        (redirect "/login?came-from=/"))
      (render-form params {:password1 "Passwords don't match"}))))


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

(defformpage exhibit-apply-view [exhibit]
  [(req-textfield :title__c "Project Title")
   {:field [:text-area#statement.editor {} :statementRich__c {:label "Project Statement"}]
    :validator {:fn not-empty :msg :required}}
   {:field [:text-area#biography.editor {} :biography__c {:label "Short Narrative Bio"}]
    :validator {:fn not-empty :msg :required}}
   {:field [:file {} :cv {:label "Upload CV"}] :validator
    {:fn filesize-not-empty :msg :required}}
   (textfield :website__c "Website")
   (findout-field)]
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
     [:input {:type :submit :value "Apply"}]]])
  (redirect
   (str "/application/"
        (create-application
         (:slug__c exhibit)
         (merge
          params
          {:contact__c (:id (session/get-user request))
           :exhibit__c (:id exhibit)}))
        "/upload")))

(defformpage application-update-view [application]
  [(req-textfield :title__c "Project Title")
   {:field [:text-area#statement.editor {} :statementRich__c {:label "Project Statement"}]
    :validator {:fn not-empty :msg :required}}
   {:field [:text-area#biography.editor {} :biography__c {:label "Short Narrative Bio"}]
    :validator {:fn not-empty :msg :required}}
   {:field [:file {} :cv {:label "Update CV"}]}
   (textfield :website__c "Website")
   (findout-field)]
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

(defn parse-caption-maps [params]
  (keep
   (fn [[k v]]
     (if-let [m (re-find #"^caption-(.*)"(name k))]
       {:id (second m) :caption__c (or v "")}))
   params))

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
         (let [user (session/get-user request)]
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
    [:p "When we have made our selections, we will notify you at the email address you provided: " (:email (session/get-user request))]
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

;; need to figure out where to store this
;; maybe just in memory for now
(defn admin? [user] false)

(defn application-owner? [user application]
  (= (:id user) (:contact__c application)))

(defn can-view-application? [user application]
  (or (admin? user) (application-owner? user application)))

(defmacro prepare-application-routes
  "Take care of fetching the application, and checking security. Anaphora: depends on having 'app-id' in context matching and injects 'application' into scope."
  [& app-routes]
  `(fn [~'request]
     (if-let [~'application (query-application ~'app-id)]
       (when-logged-in
         (if (can-view-application? ~'user ~'application)
           (routing ~'request ~@app-routes)
           (forbidden ~'request))))))

(defmacro prepare-exhibit-routes
  "Fetch exhibit, and inject 'exhibit' through anaphora. Expects 'exhibit-id' to exist in scope."
  [& exhibit-routes]
  `(fn [~'request]
     (if-let [~'exhibit (query-exhibit ~'exhibit-id)]
       (routing ~'request ~@exhibit-routes))))

(defmacro prepare-image-routes
  "Fetch image, user, verify user can view application associated with image. Expects 'image-id' to be in scope. Injects 'user' and 'image'."
  [& image-routes]
  `(fn [~'request]
     (if-let [~'image (query-image ~'image-id)]
       (when-logged-in
        (let [~'application (:exhibit_application__r ~'image)]
          (if (can-view-application? ~'user ~'application)
            (routing ~'request ~@image-routes)))))))

(defn image-delete-view [request image]
  (if (= (:request-method request) :post)
    (let [app (:exhibit_application__r image)
          exhibit-slug (:slug__c (:exhibit__r app))]
      (delete-image exhibit-slug
                    (:id app)
                    (:id image))
      "")))

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
  (if-let [user (session/get-user request)]
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
  (if-let [user (session/get-user request)]
    (layout
     request
     {:title "My applications"}
     (let [userid (:id (session/get-user request))
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
    (if-let [user (session/get-user request)]
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
  (ANY "/profile" request (if-let [user (session/get-user request)]
                            (profile-view request)
                            (redirect (str "/login?came-from="
                                           (:uri request)))))
  (ANY "/register" [] register-view)
  (ANY "/password/forgot" [] forgot-password-view)
  (ANY "/password/request-reset" [token :as request]
       (reset-request-view request token))
  (ANY "/password/reset" [] reset-password-view)

  (context "/application/:app-id" [app-id]
    (prepare-application-routes
     (ANY "/upload" [] ((onpost app-upload-image app-upload)
                        request application))
     (POST "/caption" [] (application-save-captions-view request application))
     (ANY "/submit" [] (application-submit-view request application))
     (GET "/success" [] (application-success-view request application))
     (ANY "/update" [] (application-update-view request application))
     (GET "/" [] (app-view request application))))

  (GET "/exhibit" []
       (redirect (or (-?>> (query-latest-exhibit) :slug__c (str "/exhibit/"))
                     "/")))
  (context "/exhibit/:exhibit-id" [exhibit-id]
    (prepare-exhibit-routes
     (ANY "/apply" [] (when-logged-in (exhibit-apply-view request exhibit)))
     (GET "/" [] (exhibit-view request exhibit))))
  
  (context "/image/:image-id" [image-id]
    (prepare-image-routes
     (GET "/small" [] (image-view request image "small"))
     (GET "/large" [] (image-view request image "large"))
     (GET "/original" [] (image-view request image "original"))
     (GET "/" [] (image-view request image "original"))
     (POST "/delete" [] (image-delete-view request image))))

  (GET "/cv/:app-id" [app-id :as request] (cv-view request app-id))

  (POST "/reorder-images" [order :as request]
        (reorder-images-view request order))

  (GET "/my-applications" [] my-applications-view)

  (route/files "/public" {:root "resources/public"})

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

(def app
  (->
   main-routes
   session/wrap-servlet-session
   wrap-multipart-convert-params
   wrap-multipart-params
   api))

(defn run-server []
  (run-jetty #'app {:port 8080 :join? false}))
