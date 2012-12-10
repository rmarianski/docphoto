(defproject docphoto "1.0.0-SNAPSHOT"
  :description "FIXME: write description"
  :dev-dependencies [[lein-ring "0.5.2"]]
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [compojure "1.1.3"]
                 [hiccup "1.0.0"]
                 [ring "1.1.6"]
                 [rmarianski/ring-jetty-servlet-adapter "0.0.3"]
                 [commons-codec "1.6"]
                 [commons-lang "2.6"]
                 [com.salesforce/wsc "2.2"]
                 [org.soros/prod-salesforce-docphoto "0.0.1"]
                 [clj-decline "0.0.5"]
                 [flutter "0.0.8"]
                 [org.imgscalr/imgscalr-lib "4.1"]
                 [com.draines/postal "1.7.0"]
                 [org.clojure/core.incubator "0.1.0"]
                 [clj-stacktrace "0.2.4"]
                 [clojure-csv "2.0.0-alpha2"]
                 [org.xhtmlrenderer/flying-saucer-pdf "9.0.1"]
                 [org.clojure/data.codec "0.1.0"]
                 [org.ccil.cowan.tagsoup/tagsoup "1.2.1"]]
  :ring {:handler docphoto.core/app
         :init docphoto.core/servlet-init
         :destroy docphoto.core/servlet-destroy
         :resource-scripts [docphoto/core.clj]})
