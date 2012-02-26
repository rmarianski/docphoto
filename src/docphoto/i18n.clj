(ns docphoto.i18n
  (:require [hiccup.page-helpers :as ph]))

(def ^:dynamic *language* :en)

(defmacro with-language [language & body]
  `(binding [*language* (or ~language :en)]
     ~@body))

(defmacro make-translations [& translations]
  `(merge
    ~@(for [[key english russian] (partition 3 translations)]
        {key {:en english :ru russian}})))

(def
  translations
  "mapping of keyword -> map of translation strings/functions"
  (make-translations

   ;; nav
   :home "Home" "Домой"
   :exhibits "Exhibits" "Экспонаты"
   :about "About" "О"
   :russian "Russian" "Русский"
   :login "Login" "Войти"
   :register "Register" "Регистрировать"

   ;; register / login
   :username "Username" "Имя пользователя"
   :password "Password" "пароль"
   :password-again "Password Again" "Пароль еще раз"
   :forgot-your-password-reset (fn [forgot-link]
                                 (list "Forgot your password? "
                                       (ph/link-to forgot-link "Reset") " it."))
                               (fn [forgot-link]
                                 (list "Забыли пароль? "
                                       (ph/link-to forgot-link "Сброс") " этого."))
   :first-name "First Name" "имя"
   :last-name "Last Name" "Фамилия"
   :email "Email" "Электронная почта"
   :phone "Phone" "телефон"
   :address "Address" "адрес"
   :city "City" "Сити"
   :state "State" "состояние"
   :postal-code "Postal Code" "почтовый код"
   :country "Country" "страна"
   :subscribe-to-mailinglist "Subscribe to mailing list?" "Подписаться на рассылку?"

   ;; exhibits
   :open-competitions "Open Competitions" "Открытые конкурсы"
   ))

(defn translate [translation-keyword]
  (-> translations translation-keyword *language*))
