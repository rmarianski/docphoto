;;;; handle files that we want to persist separately from salesforce

(ns docphoto.persist
  (:use [clojure.java.io :only (file input-stream output-stream copy)]
        [clojure.core.incubator :only (-?>)])
  (:import [java.io File]
           [org.apache.commons.io FileUtils]))

;; this is the root where data will be persisted
(def ^{:dynamic true} *base-storage-path*
  (file (System/getProperty "user.dir") "store"))

(defn safe-filename [filename]
  "make the filename trustable for filesystem use"
  (if filename
    (.getName (file filename))))

(defn exhibit-file-path [exhibit-slug]
  (file *base-storage-path* exhibit-slug))

(defn images-file-path [exhibit-slug app-id]
  (file *base-storage-path* exhibit-slug app-id "images"))

(defn image-file-path [exhibit-slug app-id image-id & [scale-type]]
  (apply
   file *base-storage-path* exhibit-slug app-id "images" image-id
   (if scale-type [scale-type] [])))

(defn cv-file-path
  ([exhibit-slug app-id]
     (if-let [cv-name (-?> (file *base-storage-path* exhibit-slug app-id "cv")
                           (.list) first)]
       (file *base-storage-path* exhibit-slug app-id "cv" cv-name)))
  ([exhibit-slug app-id filename]
     (file *base-storage-path* exhibit-slug app-id "cv" filename)))

(defn- ensure-dir-exists [& paths] (.mkdirs (apply file paths)))

(defn ensure-image-path [exhibit-slug app-id image-id]
  (ensure-dir-exists *base-storage-path* exhibit-slug app-id "images" image-id))

(defn- ensure-cv-path [exhibit-slug app-id]
  (ensure-dir-exists *base-storage-path* exhibit-slug app-id "cv"))

(defn delete-existing-cvs [exhibit-slug app-id]
  (FileUtils/deleteDirectory
   (file *base-storage-path* exhibit-slug app-id "cv")))

(defn delete-application [exhibit-slug app-id]
  (FileUtils/deleteDirectory
   (file *base-storage-path* exhibit-slug app-id)))

(defn persist-image-chunk
  [^File chunk exhibit-slug application-id image-id scale-type]
  "save a particular uploaded image chunk.

   the image id is the salesforce id. this assumes that an image object has already been created in salesforce prior to calling this.

   we may not need all the parameters, but this lets us change
  persistence strategies down the road more easily"
  (ensure-image-path exhibit-slug application-id image-id)
  (let [image-path (file (image-file-path exhibit-slug application-id image-id ) scale-type)]
    (with-open [rdr (input-stream chunk)
                wtr (output-stream image-path)]
      (copy rdr wtr))))

(defn persist-cv
  [^File cv exhibit-slug application-id filename]
  "dump the cv in the proper location"
  (ensure-cv-path exhibit-slug application-id)
  (let [cv-path (cv-file-path exhibit-slug application-id filename)]
    (with-open [rdr (input-stream cv)
                wtr (output-stream cv-path)]
      (copy rdr wtr))))

(defn delete-images-for-application [exhibit-slug application-id]
  (FileUtils/deleteDirectory
   (file *base-storage-path* exhibit-slug application-id "images")))

(defn delete-image [exhibit-slug application-id image-id]
  (FileUtils/deleteDirectory
   (file *base-storage-path* exhibit-slug application-id "images" image-id)))

(defn existing-images-scale
  "return the file object representing the appropriate scale if it exists"
  [images-path scale]
  (fn [image-dir-string]
    (let [f (file images-path image-dir-string scale)]
      (when (.exists f)
        f))))

(defn application-image-files
  "return a seq of file objects"
  [exhibit-slug application-id]
  (let [images-path (images-file-path exhibit-slug application-id)]
    (when (.exists images-path)
      (keep (existing-images-scale images-path "original")
            (.list images-path)))))

(defn list-applications
  "given an exhibit-slug, list all applications"
  [exhibit-slug]
  (-> (exhibit-file-path exhibit-slug) (.list)))
