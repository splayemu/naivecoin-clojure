(defproject blockchain "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.3.465"]
                 [digest "1.4.6"]
                 [clj-time "0.14.2"]
                 [javax.servlet/servlet-api "2.5"]
                 [ring/ring-json "0.4.0"]
                 [ring/ring-core "1.6.3"]
                 [http-kit "2.2.0"]
                 [compojure "1.6.0"]
                 [environ "1.1.0"]
                 [stylefruits/gniazdo "1.0.1"]]
  :aot  [blockchain.core]
  :main blockchain.core)
