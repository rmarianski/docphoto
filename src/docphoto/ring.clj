(ns docphoto.ring
  (:require [compojure.response :as cr]
            [clojure.string :as string]
            [ring.adapter.jetty-servlet]))

(defmacro one-ring [& ringspecs]
  `(fn [~'request]
     (let [~'params (:params ~'request)
           ~'uri (:uri ~'request)
           ~'method (:request-method ~'request)]
       (cr/render (or ~@ringspecs) ~'request))))

(defmacro on-post [post-body get-body]
  `(if (= ~'method :post) ~post-body ~get-body))

(defn keyword->symbol [keyword]
  (if-not (keyword? keyword)
    (throw (IllegalArgumentException. (str keyword " is not a keyword")))
    (symbol (apply str (next (str keyword))))))

(defn rest-of-uri [parts idx]
  (string/join "/" (cons "" (subvec parts idx))))

(defmacro it-begins [s & beginspecs]
  (let [uriparts (gensym "uriparts__")]
    `(let [~uriparts (vec (.split ~s "/"))]
       (or
        ~@(for [[specparts body] beginspecs]
            (letfn [(begin [remaining-spec-parts uri-parts-idx body]
                      (if-not (seq remaining-spec-parts)
                        `(let [~'uri-rest (rest-of-uri ~uriparts
                                                       ~uri-parts-idx)]
                           ~body)
                        (let [spec-part (first remaining-spec-parts)]
                          (if (keyword? spec-part)
                            `(if-let [~(keyword->symbol spec-part)
                                      (get ~uriparts ~uri-parts-idx)]
                               ~(begin (next remaining-spec-parts)
                                   (inc uri-parts-idx)
                                   body))
                            `(if (= ~(name (first remaining-spec-parts))
                                    (get ~uriparts ~uri-parts-idx))
                               ~(begin (next remaining-spec-parts)
                                       (inc uri-parts-idx)
                                       body))))))]
              (begin specparts 1 body)))))))

(defmacro uri-begins [& beginspecs]
  `(it-begins ~'uri ~@beginspecs))

(defmacro subroutes* [uri-rest f & subroutespecs]
  (if (odd? (count subroutespecs)) (throw (IllegalArgumentException.
                                           "bad subroute specs")))
  `(case ~uri-rest
     ~@(letfn [(make-case [specs]
                 (if (seq specs)
                   (cons (first specs)
                         (cons `(~f ~(second specs))
                               (make-case (nthnext specs 2))))))]
         (make-case subroutespecs))
     nil))

(defmacro subroutes-wrapped [f & subroutespecs]
  `(subroutes* ~'uri-rest ~f ~@subroutespecs))

(defmacro subroutes [& subroutespecs]
  `(subroutes* ~'uri-rest identity ~@subroutespecs))

;; (one-ring
;;  (it-begins uri
;;             ([application :id] )))

   ;; (dr/one-ring
   ;;  (dr/uri-begins
   ;;   ([foo] (case uri-rest
   ;;            "/bar" (str "got /foo/bar")
   ;;            nil)))
   ;;  "not found i'm afraid")

(def main-routes
  (one-ring
   (uri-begins
    ([foo] (subroutes-wrapped (fn [x] (if (.startsWith x "b")
                                       (str "got a b: " x)
                                       x))
             "/bar" "bar"
             "/baz" "baz"
             "/quux" "foo quux")))
   "404 page"))

(defn ring-server []
  (ring.adapter.jetty-servlet/run-jetty #'main-routes
                                        {:port 8080 :join? false}))
