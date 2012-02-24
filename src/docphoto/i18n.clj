(ns docphoto.i18n)

(def ^:dynamic *language* :en)

(defmacro with-language [language & body]
  `(binding [*language* ~language]
     ~@body))

(def
  translations
  "mapping of keyword -> map of translation strings/functions"
  {:home {:en "Home"
          :ru "Домой"}
   :exhibits {:en "Exhibits"
              :ru "Экспонаты"}
   :about {:en "About"
           :ru "О"}
   :russian {:en "Russian"
             :ru "Русский"}
   })

(defn translate-text [translation-keyword]
  (-> translations translation-keyword *language*))
