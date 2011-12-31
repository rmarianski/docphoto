(ns docphoto.utils
  (:require [docphoto.config :as cfg]
            [ring.middleware.multipart-params :as multipart]
            [postal.core :as postal])
  (:import [org.apache.commons.codec.digest DigestUtils]))

;; copied from old clojure.contrib
;; defn-memo by Chouser:
(defmacro defn-memo
  "Just like defn, but memoizes the function using clojure.core/memoize"
  [fn-name & defn-stuff]
  `(do
     (defn ~fn-name ~@defn-stuff)
     (alter-var-root (var ~fn-name) memoize)
     (var ~fn-name)))

(defmacro defn-debug-memo
  "when in debug mode, memoize function"
  [& args]
  (if cfg/debug
    `(defn-memo ~@args)
    `(defn ~@args)))

(defn md5 [s] (DigestUtils/md5Hex s))

(defn lorem-ipsum []
  "Lorem ipsum dolor sit amet, consectetur adipisicing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.")

(defn multipart-form? [request]
  (@#'multipart/multipart-form? request))

(defn send-message
  "Sends out an email. Options are the arguments to postal/send-message:
:to :subject :body"
  [mail-options]
  (postal/send-message
   (merge cfg/mail-config
          mail-options)))
