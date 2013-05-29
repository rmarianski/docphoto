(ns docphoto.pdf
  (:require [hiccup.core :as hic]
            [clojure.java.io :as io]
            [clojure.data.codec.base64 :as b64]
            [docphoto.config :as cfg]
            [docphoto.persist :as persist])
  (:import [org.xhtmlrenderer.pdf ITextRenderer]
           [javax.xml.parsers DocumentBuilder DocumentBuilderFactory]
           [javax.xml.transform TransformerFactory]
           [javax.xml.transform.sax SAXSource]
           [javax.xml.transform.dom DOMResult]
           [org.w3c.dom Document]
           [org.xml.sax InputSource]
           [java.io ByteArrayInputStream ByteArrayOutputStream StringReader]
           [org.ccil.cowan.tagsoup Parser]
           ))

(defn base64 [fileobj]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [in (io/input-stream fileobj)]
      (b64/encoding-transfer in baos))
    (.toString baos "UTF-8")))

(defn render-pdf [out doc]
  (doto (ITextRenderer.)
    (.setDocument doc nil)
    (.layout)
    (.createPDF out)))

(defn generate-hiccup-markup [application]
  (hic/html
   [:html
    [:head
     [:title (:title__c application)]
     [:style {:type "text/css"}
      ".page-break { page-break-after: always; }\n"
      "h1 { text-align: center; }\n"
      "h2 { margin-top: 2em; }\n"
      ".image-row { clear: both; min-height: 120px;}\n"
      ".image-container { float: left; width: 130px; }\n"
      ]]
    [:body
     [:h1 (:title__c application)]
     (for [[field-id heading] [[:cover_Page__c "Cover Page"]
                               [:statementRich__c "Statement"]
                               [:biography__c "Biography"]
                               [:narrative__c "Narrative"]
                               [:website__c "Website"]]]
       (when-let [value (application field-id)]
         [:div
          [:h2 heading]
          [:div value]]))
     [:div.page-break]
     (let [exhibit-slug (:slug__c (:exhibit__r application))
           app-id (:id application)]
       (for [image (:images application)]
         [:div.image-row
          [:div.image-container
           [:img
            {:src (str cfg/pdf-generation-base-url "/image/" (:id image) "/small/" (:filename__c image))
             ;; not base64 encoding due to jvm custom url handler issues
             ;; #_(str
             ;;    "data:" (:mime_type__c image) ";base64,"
             ;;    (base64 (persist/image-file-path exhibit-slug app-id (:id image) "small")))
             }]]
          [:p (:caption__c image)]]))]])
  )

;; without tagsoup
;; (defn make-w3c-document [html-as-string]
;;   (-> (DocumentBuilderFactory/newInstance)
;;       (.newDocumentBuilder)
;;       (.parse (ByteArrayInputStream. (.getBytes html-as-string "UTF-8")))))

(defn make-tagsoup-w3c-document [html-as-string]
  (let [dom-result (DOMResult.)]
    (-> (TransformerFactory/newInstance)
        (.newTransformer)
        (.transform (SAXSource. (Parser.)
                                (-> (StringReader. html-as-string)
                                    (InputSource.)))
                    dom-result))
    (.getNode dom-result)))

(defn generate-doc [application]
  (make-tagsoup-w3c-document (generate-hiccup-markup application)))

;; driver function to be called from view
(defn render-application-as-pdf [application out]
  (render-pdf out (generate-doc application)))
