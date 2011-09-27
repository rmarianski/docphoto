(ns docphoto.image
  (:import [java.awt.image BufferedImageOp]
           [java.io File]
           [javax.imageio ImageIO]
           [com.thebuzzmedia.imgscalr Scalr]))

(defn extension [^File f]
  (let [path (.getPath f)
        dot-index (.lastIndexOf path ".")]
    (if (> dot-index -1)
      (subs path (inc dot-index)))))

(let [format-names (into #{} (ImageIO/getWriterFormatNames))]
  (defn image-format [^File f]
    (or (format-names (extension f))
        "png")))  ; default to png

(let [empty-array (into-array BufferedImageOp [])]
  (defn scale [^File src ^File dest width height]
    (if-let [bsrc (ImageIO/read src)]
      (let [bdest (Scalr/resize bsrc width height empty-array)]
        (ImageIO/write bdest (image-format src) dest)))))
