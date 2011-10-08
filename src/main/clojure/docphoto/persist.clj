;;;; handle files that we want to persist separately from salesforce

(ns docphoto.persist
  (:use [clojure.java.io :only (file input-stream output-stream copy)])
  (:import [java.io File]
           [org.apache.commons.io FileUtils]))

;; this is the root where data will be persisted
(def ^{:dynamic true} *base-storage-path*
  (file (System/getProperty "user.dir") "store"))

(defn- safe-filename [filename]
  "make the filename trustable for filesystem use"
  (.getName (file filename)))

(defn image-file-path [exhibit-slug app-id image-id & [scale-type]]
  (apply
   file *base-storage-path* exhibit-slug app-id "images" image-id
   (if scale-type [scale-type] [])))

(defn cv-file-path [exhibit-slug app-id filename]
  (file *base-storage-path* exhibit-slug app-id "cv" filename))

(defn- ensure-dir-exists [& paths] (.mkdirs (apply file paths)))

(defn ensure-image-path [exhibit-slug app-id image-id]
  (ensure-dir-exists *base-storage-path* exhibit-slug app-id "images" image-id))

(defn- ensure-cv-path [exhibit-slug app-id]
  (ensure-dir-exists *base-storage-path* exhibit-slug app-id "cv"))

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
