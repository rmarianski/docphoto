(ns docphoto.session
  (:import [javax.servlet.http
            HttpServletRequest HttpSession]))

(defn wrap-servlet-session
  "takes the servlet container provided session,
   and sticks it in the request map under the :session key"
  [handler]
  (fn [request]
    (handler
     (if-let [^HttpServletRequest servlet-request (:servlet-request request)]
       (assoc request :session (.getSession servlet-request true))
       request))))

(defn set-session-attribute! [^HttpSession session attr-key attr-val]
  (.setAttribute session attr-key attr-val)
  attr-val)

(defn get-session-attribute [^HttpSession session attr-key]
  (.getAttribute session attr-key))

(defmacro defsession
  "generate an anaphoric function that pulls the session out of the request"
  [fn-name args body]
  (let [^HttpSession session 'session]
    `(defn ~fn-name [~'request ~@args]
       (if-let [~(with-meta session {:tag 'HttpSession}) (:session ~'request)]
         ~body))))

(defmacro defsession-getter
  "generate a session fn getter"
  [fn-name attribute-name]
  `(defsession ~fn-name []
     (get-session-attribute ~'session ~attribute-name)))

(defmacro defsession-setter
  "generate a simple session fn setter"
  [fn-name attribute-name]
  `(defsession ~fn-name [attribute-value#]
     (set-session-attribute! ~'session ~attribute-name attribute-value#)))

(defsession-getter get-user "user")
(defsession-setter save-user "user")

(defsession delete [] (.invalidate session))
(defsession clear []
  (doseq [argname (enumeration-seq (.getAttributeNames session))]
    (.removeAttribute session argname)))

(defsession-getter get-token "reset-token")
(defsession save-token [reset-token userid]
  (.setAttribute session "reset-token" {:userid userid
                                        :token reset-token}))

(defsession allow-password-reset [userid]
  (do (.removeAttribute session "reset-token")
      (set-session-attribute! session "allow-password-reset" userid)))

(defsession-getter password-reset-userid "allow-password-reset")
(defsession remove-allow-password-reset []
  (.removeAttribute session "allow-password-reset"))

(defsession-getter get-language "language")
(defsession-setter save-language "language")

(defsession-getter get-cache "cache")
(defsession-setter set-cache "cache")
