(ns docphoto.i18n
  (:require [hiccup.page-helpers :as ph]
            [docphoto.guidelines :as guidelines]))

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
   :old-password "Old Password" "Старый пароль"
   :new-password "New Password" "Новый пароль"
   :update-password "Update Password" "Обновление пароля"
   :reset-password-text
   (fn [reset-link] (list (ph/link-to reset-link "Reset") " your password instead."))
   (fn [reset-link] (list (ph/link-to reset-link "Сбросить ") " пароль вместо."))

   :already-logged-in (fn [username] (str "Already logged in as: " username))
                      (fn [username] (str "Уже вошли как: " username))
   :invalid-credentials "Invalid Credentials" "Неверное Полномочия"
   :forgot-your-password-reset (fn [forgot-link]
                                 (list "Forgot your password? "
                                       (ph/link-to forgot-link "Reset it") "."))
                               (fn [forgot-link]
                                 (list "Забыли пароль? "
                                       (ph/link-to forgot-link "Сброс этого") "."))

   :no-account-register
   (fn [register-link]
     (list "Don't have an account? " (ph/link-to register-link "Register") "."))
   (fn [register-link]
     (list "Вы не имеете учетной записи? " (ph/link-to register-link "регистрировать") "."))

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
   :passwords-dont-match "Passwords don't match" "Пароли не совпадают"
   :user-already-exists "User already exists" "Пользователь уже существует"

   :update-profile "Update Profile" "Обновление профиля"

   ;; password reset
   :password-reset "Password Reset" "Сброс пароля"
   :receive-link-to-reset
   "You will receive a link that will allow you to reset your password. You must use the same browser session in order to reset your password."
   "Вы получите ссылку, которая позволит вам сбросить пароль. Вы должны использовать ту же сессию браузера для того, чтобы сбросить пароль."
   :reset "Reset" "сброс"
   :email-sent "Email sent" "Письмо, отправленное"
   :email-sent-to "An email has been sent to: " "Электронной почты было отправлено: "
   :email-not-found "Email not found" "Отправить не найдено"
   :password-reset-failure "Password reset failure" "Отказ Сброс пароля"
   :no-token-found
   "No token found. Please double check the link in your email."
   "Нет маркеров найдено. Пожалуйста, проверьте ссылку на Вашу электронную почту."
   :invalid-token
   "Invalid token. Please double check the link in your email."
   "Недопустимый маркер. Пожалуйста, проверьте ссылку на Вашу электронную почту."
   :token-expired
   (fn [link]
     [:span "Token expired. Are you using the same browser session as when you requested a password reset? If so, you can "
      (ph/link-to link "resend")
      " a password reset email."])
   (fn [link]
     [:span "Маркер истек. Используете ли вы той же сессии браузера, когда вы просили сброса пароля? Если "
      (ph/link-to link "это") " так, вы можете повторно электронной почты для сброса пароля."])

   :reset-password-for
   (fn [username] (str "Reset password for: " username))
   (fn [username] (str "Сброс пароля для: " username))

   :password-reset-email
   (fn [reset-link]
     (str "Hi,

If you did not initiate a docphoto password reset, then please ignore this message.

To reset your password, please click on the following link: 
" reset-link))
   (fn [reset-link]
     (str
      "Привет,

Если вы не начать сброс пароля, то не обращайте внимания на это сообщение.

Чтобы восстановить пароль, пожалуйста, перейдите по следующей ссылке:"
      reset-link))

   ;; form related
   :required "Required" "требуется"

   ;; applications
   :apply-to "Apply to " "Применить "
   :apply-online "Apply Online" "подать заявку онлайн"
   :cv "Curriculum Vitae" "Резюме"
   :cv-description
   "The preferred format for Curriculum Vitae is either Microsoft Word or PDF"
   "предпочтительный формат для резюме – либо документ Microsoft Word, либо PDF"
   :cv-download "Download CV" "Скачать резюме"
   :focus-region "Focus Region" "Фокус области"
   :focus-country "Focus Country" "Фокус страны"

   ;; referred by drop down
   :how-did-you-find-out "How did you find out about this competition?"  "Как Вы узнали о конкурсе?"
   :findout-website "Open Society Foundations website" "Open Society Foundations веб-сайт"
   :findout-newsletter "Doc Photo Project newsletter" "Проект бюллетеня"
   :findout-facebook "Facebook" "Facebook"
   :findout-twitter "Twitter" "Twitter"
   :findout-article "Article" "статья"
   :findout-friend "Friend" "друг"
   :findout-other "Other" "другой"

   :required-english-only "Required and English Only" "Обязательно и только на английском языке"
   :pg-project-title "Project Title" "Название проекта"
   :pg-project-summary "Project Summary" "Краткое описание проекта"
   :pg-project-summary-description "A one sentence summary of proposed body of work (English only)" "Это поле не принимает кирриллические буквы"
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
   :proceed-to-upload-images "Save Progress and Proceed to Upload Images" "Приступить к Загрузить изображения"

   :caption-required "Caption required" "Заголовок необходимые"

   :not-enough-images "Not enough images uploaded" "Не достаточно загруженных изображений"
   :not-all-captions-complete "Not all captions complete" "Не все подписи завершения"
   :fix-errors-before-can-submit
   "Before submitting your application, please correct the following:"
   "Перед подачей заявки, пожалуйста, исправьте следующие:"

   :application-review "Application Review" "Обзор приложений"
   :review-application-before-submitting
   "Review your application before submitting."
   "Обзор приложения перед отправкой."
   :contact-info "Contact Information" "Контактная информация"
   :subscribed-to-mailing-list "Subscribed to mailing list" "Подписка на рассылку"
   :not-subscribed-to-mailing-list "Not subscribed to mailing list" "Не подписаны на рассылку"

   :summary "Summary" "резюме"
   :proposal-narrative "Proposal Narrative" "Описание предложения"
   :statement "Statement" "заявление"
   :biography "Short biography" "Краткая биография"

   :images "Images" "изображения"

   :save "Save" "экономить"
   :delete "Delete" "удалять"
   :update "Update" "обновлять"
   :applications "Applications" "применения"
   :application "Application" "применение"

   :application-already-submitted
   "Your application has already been submitted. When we are finished reviewing all applications, we will get back to you."
   "Ваша заявка уже подана. Когда мы закончили рассмотрение всех приложений, мы свяжемся с вами."

   :application-submit
   "Once you have reviewed your application, please click on the submit button below."
   "После того как вы изучили приложения, нажмите на кнопку отправки ниже."
   :application-submit-button "Submit your application" "Подайте заявку"

   :submission-thank-you "Thank you for your submission" "Спасибо за Ваше представление"
   :selection-email-notification
   "When we have made our selections, we will notify you at the email address you provided: "
   "Когда мы сделали наш выбор, мы сообщим вам на адрес электронной почты, при условии: "

   :view-all-applications "You can view all your " "Вы можете просмотреть все "

   :submitted "Submitted" "представленный"

   :guidelines-prodgrant2012
   (:en (guidelines/guidelines :prodgrant2012))
   (:ru (guidelines/guidelines :prodgrant2012))

   :guidelines-mw20
   (:en (guidelines/guidelines :mw20))
   ;; only need english guidelines for mw20
   (:en (guidelines/guidelines :mw20))

   ))

(defn translate [translation-keyword]
  (if (keyword? translation-keyword)
    (-> translations translation-keyword *language*)
    translation-keyword))
