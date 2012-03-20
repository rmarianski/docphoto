(ns docphoto.utils
  (:use [ring.util.response :only (redirect)])
  (:require [docphoto.config :as cfg]
            [ring.middleware.multipart-params :as multipart]
            [postal.core :as postal])
  (:import [org.apache.commons.codec.digest DigestUtils]
           [org.apache.commons.lang CharUtils]))

;; no longer in contrib
(defn find-first
  "Returns the first item of coll for which (pred item) returns logical true.
  Consumes sequences up to the first match, will consume the entire sequence
  and return nil if no match is found."
  [pred coll]
  (first (filter pred coll)))

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

(defn md5 [^String s] (DigestUtils/md5Hex s))

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

(defmacro with-gensyms [symbols & body]
  `(let
       ~(vec
         (apply
          concat
          (for [s symbols]
            [s `(gensym ~(str (name s) "__"))])))
     ~@body))

(defmacro onpost
  "Anaphoric macro to handle two actions based on post or not. Assumes the symbol 'request' is bound to a ring request map. posthandler and gethandler must be single forms."
  [posthandler gethandler]
  `(if (= (:request-method ~'request) :post)
     ~posthandler
     ~gethandler))

(defmacro dbg [x]
  `(let [result# ~x]
     (println '~x "->" result#)
     result#))

(defmacro when-logged-in
  "Checks if user exists in session (from request through anaphora). If not, redirect to login page with came-from to current uri. Injects 'user' into scope."
  [body]
  `(if-let [~'user (session/get-user ~'request)]
     ~body
     (redirect (str "/login?came-from=" (:uri ~'request)))))

(defn ascii? [^Character c] (CharUtils/isAscii c))

(defn all-ascii? [s] (every? ascii? s))

(defn not-empty-and-ascii? [s]
  (and (not-empty s) (all-ascii? s)))

