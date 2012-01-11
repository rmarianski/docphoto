(ns docphoto.session)

(defn wrap-servlet-session
  "takes the servlet container provided session,
   and sticks it in the request map under the :session key"
  [handler]
  (fn [request]
    (handler
     (if-let [servlet-request (:servlet-request request)]
       (assoc request :session (.getSession servlet-request true))
       request))))

(defmacro defsession
  "generate an anaphoric function that pulls the session out of the request"
  [fn-name args body]
  `(defn ~fn-name [~'request ~@args]
     (if-let [~'session (:session ~'request)]
       ~body)))

(defmacro defsession-getter
  "generate a session fn getter"
  [fn-name attribute-name]
  `(defsession ~fn-name []
     (.getAttribute ~'session ~attribute-name)))

(defmacro defsession-setter
  "generate a simple session fn setter"
  [fn-name attribute-name]
  `(defsession ~fn-name [attribute-value#]
     (.setAttribute ~'session ~attribute-name attribute-value#)))

(defsession-getter get-user "user")
(defsession-setter save-user "user")

(defsession delete [] (.invalidate session))

(defsession-getter get-token "reset-token")
(defsession save-token [reset-token userid]
  (.setAttribute session "reset-token" {:userid userid
                                        :token reset-token}))

(defsession allow-password-reset [userid]
  (do (.removeAttribute session "reset-token")
      (.setAttribute session "allow-password-reset" userid)))

(defsession-getter password-reset-userid "allow-password-reset")
(defsession remove-allow-password-reset []
  (.removeAttribute session "allow-password-reset"))
