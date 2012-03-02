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
   :about "About" "О нас"
   :russian "Russian" "Русский"
   :login "Login" "Войти"
   :logout "Logout" "Выход"
   :register "Register" "Регистрировать"

   ;; register / login
   :username "Username" "Имя пользователя"
   :password "Password" "пароль"
   :password-again "Password Again" "Пароль еще раз"
   :already-logged-in (fn [username] (str "Already logged in as: " username))
                      (fn [username] (str "Уже вошли как: " username))
   :invalid-credentials "Invalid Credentials" "Неверное Полномочия"
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

   ;; form related
   :required "Required" "требуется"

   ;; applications
   :cv "Curriculum Vitae" "Резюме"
   :cv-description
   "The preferred format for Curriculum Vitae is either Microsoft Word or PDF"
   "предпочтительный формат для резюме – либо документ Microsoft Word, либо PDF"

   :pg-project-title "Project Title" "Название проекта"
   :pg-project-title-description "A one sentence summary of proposed body of work (English only)" "Это поле не принимает кирриллические буквы"
   :pg-proposal-narrative "Proposal Narrative" "Описание предложения"
   :pg-proposal-narrative-description
   "Please provide a three-page description of the proposed project, a summary of the issue and its importance, a description of the plan for producing the work, a description of sources and contacts for the project, and thoughts on how the finished product might be distributed."
   "Пожалуйста, представьте трёхстраничное описание предложенного проекта, краткое изложение темы и её важность, описание плана выполнения работы, описание источников и контактов по проекту, соображения относительно того, каким образом окончательный продукт мог бы распространяться."
   :pg-personal-statement "Personal Statement" "Персональное заявление"
   :pg-personal-statement-description
   "Please provide a one-page summary of your experience as a photographer, the training you have received, and why you feel this grant program would be useful for you now."
   "Пожалуйста, представьте одну страницу с кратким изложением вашего опыта как фотографа, полученной подготовки, а также того, почему вы чувствуете, что эта грантовая программа была бы полезной вам в данное время."

   ;; image upload
   :upload-images "Upload images" "Загрузка изображений"
   :upload-image-amount "Please upload 15-20 images." "Пожалуйста, загрузите 15-20 изображений"
   :upload-no-support
   "Your browser doesn't have Flash, Silverlight, Gears, BrowserPlus or HTML5 support."
   "Ваш браузер не установлен Flash, Silverlight, Gears, BrowserPlus или поддержки HTML5."

   :upload-select-files "Select files" "Выбор файлов"
   :upload "Upload" "загружать"

   :upload-image-limit "There is a 5 megabyte limit on images." "Существует 5 мегабайт ограничения на изображениях."
   :upload-image-reorder
   "The order of your images is an important consideration. Drag them to re-order."
   "Порядок изображений является важным фактором. Перетащите их на повторный заказ."

   :save "Save" "экономить"
   :delete "Delete" "удалять"
   :applications "Applications" "применения"

   ))

(defn translate [translation-keyword]
  (if (keyword? translation-keyword)
    (-> translations translation-keyword *language*)
    translation-keyword))
