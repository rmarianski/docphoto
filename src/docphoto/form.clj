(ns docphoto.form
  (:use [docphoto.utils :only (with-gensyms onpost not-empty-and-ascii?)]
        [flutter.html4 :only (html4-fields)]
        [flutter.shortcuts :only (wrap-shortcuts)]
        [flutter.params :only (wrap-params)]
        [decline.core :only (validations validate-val)])
  (:require [clojure.string :as string]
            [docphoto.i18n :as i18n]))

(defn wrap-errors
  "add in errors for form fields puts the error in opts if it's a map"
  [f errors]
  (fn [type attrs name opts value]
    (if (nil? opts)
      (recur type attrs name {} value)
      (f type attrs name
         (if (map? opts)
           (merge {:error (errors name)} opts)
           opts)
         value))))

(defn wrap-form-field
  "wraps a particular field with a label, description, error which is
  fetched from opts which must be a map. options for the field itself
  go under the :opts key"
  [f]
  (fn [type attrs field-name opts value]
    (if (= type :hidden)
      (f type attrs field-name opts value)
      [:div.ctrlHolder
      (list
       [:label {:required (:required opts)}
        (or (i18n/translate (:label opts)) (string/capitalize (name field-name)))]
       (when-let [desc (:description opts)]
         [:p.formHint (i18n/translate desc)])
       (f type attrs field-name (:opts opts) value)
       (when-let [error (:error opts)]
         [:div.error (i18n/translate error)]))])))

(defn wrap-textinput-classes
  "add a textInput class to text inputs"
  [f]
  (fn [type attrs name opts value]
    (if (#{:text :password} type)
      (f type (if (:class attrs)
                (str (:class attrs) " textInput")
                (assoc attrs :class "textInput"))
         name opts value)
      (f type attrs name opts value))))

(defn wrap-checkbox-opts-normalize
  "normalize the options to have the checked behavior work properly"
  [f]
  (fn [type attrs name opts value]
    (if (= type :checkbox)
      (f type attrs name "on" (if value "on"))
      (f type attrs name opts value))))

(defn wrap-radio-group
  "expand out the radio button options to be able to specify multiple options under one group"
  [f]
  (fn [type attrs name opts value]
    (if (and (= type :radio)
             (not-empty opts))
      (for [[label opt-value] opts]
        [:div label (f type attrs name opt-value value) [:br]])
      (f type attrs name opts value))))

(def base-field-render-fn
  (-> html4-fields
      wrap-radio-group
      wrap-checkbox-opts-normalize
      wrap-form-field
      wrap-textinput-classes
      wrap-shortcuts
      ))

(defn field-render-fn
  "create the function used to render all fields"
  [params errors]
  (-> base-field-render-fn
      (wrap-errors errors)
      (wrap-params params)))

(defn make-render-stanza
  "parse a fieldspec and create a form to render the field. Returns nil if the parse fails."
  [field params request errors fieldspec]
  (if-let [customfn (:custom fieldspec)]
    `(~customfn ~request ~field ~params ~errors)
    (if-let [fieldspec (:field fieldspec)]
      `(~field ~@fieldspec))))

(defn make-fields-render-fn
  "Used by the defformpage macro to generate the fields renderer function. It determines at compile time what functions need to be called to render each field. If this needs to be a decision at runtime, a :custom function should be used and handled separately. A single form to generate a function is returned that returns a list of each field element rendered. This function is then injected through anaphora as 'render-fields' to the form view and can be called to render all fields."
  [fieldspecs]
  (with-gensyms [field params request errors]
    `(fn [~request ~params ~errors]
       (let [~field (field-render-fn ~params ~errors)]
         (list ~@(keep
                  (partial make-render-stanza field params request errors)
                  fieldspecs))))))

(defn make-validator-stanza
  "Parse a fieldspec and create a validator form out of it. Returns nil if no field name, validator function, or error message is found."
  [fieldspec]
  (let [{:keys [field validator]} fieldspec
        {:keys [fn msg]} validator
        [_ _ name] field]
    (if-not (some nil? [fn msg name])
      `(validate-val ~name ~fn {~name ~msg}))))

(defn make-validator-fn
  "Create the form to validate all fields."
  [fieldspecs]
  `(validations ~@(keep make-validator-stanza fieldspecs)))

(defn make-form-render-fn
  "Create the lisp form to render the html form. This is necessary in case the params and errors need to be changed dynamically for rendering purposes."
  [form-render-body]
  `(fn [~'params ~'errors]
     ~form-render-body))

(defmacro defformpage
  "Should write a lot of documentation here. Especially on anaphora. And what's expected. Fieldspecs need to be documented too. The fact that the body parameters need to be single forms too."
  [fn-name args fieldspecs form-render-body success-body]
  (let [fieldspecs (map macroexpand (macroexpand fieldspecs))]
    `(let [~'render-fields ~(make-fields-render-fn fieldspecs)
           ~'validate-fields ~(make-validator-fn fieldspecs)]
       (defn ~fn-name [~'request ~@args]
         (let [~'params (:params ~'request)
               ~'render-form ~(make-form-render-fn form-render-body)]
           (onpost
            (if-let [~'errors (~'validate-fields ~'params)]
              (~'render-form ~'params ~'errors)
              ~success-body)
            (~'render-form ~'params {})))))))

(defmacro textfield
  "helper to simplify generating optional text fields"
  [fieldname label & [description]]
  {:field [:text {} fieldname
           (merge (when description {:description description})
                  {:label label})]})

(defmacro req-textfield
  "helper to simplify generating required text fields"
  [fieldname label & description]
  (assoc (textfield fieldname label description)
    :validator {:fn not-empty :msg :required}))

(defmacro english-only-textfield
  "helper to generate english only required text fields"
  [fieldname label & description]
  (assoc (textfield fieldname label description)
    :validator {:fn not-empty-and-ascii? :msg :required-english-only}))

(defmacro req-password
  "helper to simplify generating password fields"
  [fieldname label]
  {:field [:password {} fieldname {:label label}]})

(defn came-from-field
  "a hidden input that passes came from information"
  [request field params errors]
  (let [came-from (or (:came-from params) ((:headers request) "referer"))]
    (if (not-empty came-from)
      [:input {:type :hidden
               :name "came-from"
               :value came-from}])))
