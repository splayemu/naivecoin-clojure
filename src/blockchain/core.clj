(ns blockchain.core
  (:require
   [digest]
   [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
   [ring.middleware.params :as params]
   [clj-time.core :as t]
   [environ.core :as e]
   [gniazdo.core :as ws]
   [clojure.edn :as edn]
   [clojure.core.async
    :as a
    :refer [>! <! >!! <!! go chan buffer close! thread
            alts! alts!! timeout]])
  (:use
   [compojure.route :only [files not-found]]
   [compojure.handler :only [site]] ; form, query params decode; cookie; session, etc
   [compojure.core :only [defroutes GET POST DELETE ANY context]]
   org.httpkit.server)
  (:gen-class))

(defn env-peers [peer-str]
  (->> (clojure.string/split peer-str #",")
       (map #(clojure.string/split % #":"))))

(defn env-port [k d]
  (if-let [p (e/env k)]
    (java.lang.Integer/parseInt p)
    d))

(def http-port (env-port :http-port 3001))
(def p2p-port (env-port :p2p-port 6001))

(def initial-peers
  (if-let [peers (e/env :peers)]
    (env-peers peers)
    []))

(defonce http-server (atom nil))
(defonce p2p-server (atom nil))

(def sockets (atom {}))
(def channels (atom []))

(def query-chain-length-msg {:type :message-type/QUERY-LATEST})
(def query-all-msg {:type :message-type/QUERY-ALL})

(defn genesis-block []
  {:index 0
   :previous-hash "0"
   :timestamp 0
   :data ""
   :hash ""
   })

(defn write-client [ws message]
  (println (str "write-client: " message))
  (ws/send-msg ws message))

(defn write-channel [ch message]
  (println (str "write-channel: " message))
  (send! ch message))

(defn write-local-channel [ch message]
  (println (str "write-local-channel: " message))
  (>!! ch message))

(defn write [type socket message]
  (cond
    (= type :socket-type/client)
    (write-client socket message)

    (= type :socket-type/server)
    (write-channel socket message)

    (= type :socket-type/local)
    (write-local-channel socket message)))

(defn broadcast [message]
  (doseq [ws (vals @sockets)]
    (write-client ws message))
  (doseq [ch @channels]
    (write-channel ch message)))

(def blockchain (atom [(genesis-block)]))

(defn get-latest-block []
  (last @blockchain))

(defn response-chain-msg []
  {:type :message-type/RESPONSE-BLOCKCHAIN
   :data @blockchain})

(defn response-latest-msg []
  {:type :message-type/RESPONSE-BLOCKCHAIN
   :data [(get-latest-block)]})

(defn calculate-hash [& args]
  (digest/sha-256 (apply str args)))

(defn calculate-hash-for-block [block]
  (calculate-hash (:index block)
                  (:previous-hash block)
                  (:timestamp block)
                  (:data block)))

(defn generate-next-block [block-data]
  (let [previous-block (get-latest-block)
        next-index     (+ (:index previous-block) 1)
        next-timestamp (str (t/now))]
    {:index         next-index
     :previous-hash (:hash previous-block)
     :timestamp     next-timestamp
     :data          block-data
     :hash          (calculate-hash next-index
                                    (:hash previous-block)
                                    next-timestamp
                                    block-data)}))

(defn is-valid-new-block [new-block, previous-block]
  (cond
    (not= (+ (:index previous-block) 1) (:index new-block))
    (do (print "invalid index") false)

    (not= (:hash previous-block) (:previous-hash new-block))
    (do (print "invalid previous-hash") false)

    (not= (calculate-hash-for-block new-block) (:hash new-block))
    (do (print (str "invalid hash: "
                    (calculate-hash-for-block new-block) " "
                    (:hash new-block))))
    :else true))

(defn add-block [new-block]
  (when (is-valid-new-block new-block (get-latest-block))
    (swap! blockchain conj new-block)))

(defn is-valid-chain [blockchain-to-validate]
  (and (= (first blockchain-to-validate) (genesis-block))
       (reduce (fn [b [previous-block curr-block]]
                 (and b (is-valid-new-block curr-block previous-block)))
               true
               (partition 2 1 blockchain-to-validate))))

(defn replace-chain [new-chain]
  (if (and (is-valid-chain new-chain) (> (count new-chain) (count @blockchain)))
    (do
      (print "Received blockchain is valid. Replacing current blockchain with received blockchain")
      (reset! blockchain new-chain)
      (broadcast (prn-str (response-latest-msg))))
    (print "Received blockchain invalid")))

;; server

(defn stop-server [server]
  (when-not (nil? @server)
    ;; graceful shutdown: wait 100ms for existing requests to be finished
    ;; :timeout is optional, when no timeout, stop immediately
    (@server :timeout 100)
    (reset! server nil)))

(defn handle-blockchain-response [data]
  (let [received-blocks (sort-by :index (:data data))
        latest-block-received (last received-blocks)
        latest-block-held (get-latest-block)]
    (println latest-block-received)
    (println latest-block-held)
    (cond
      (<= (:index latest-block-received) (:index latest-block-held))
      (do
        (println "received blockchain is shorter than local blockchain. Do nothing")
        :do-nothing)

      (= (:hash latest-block-held) (:previous-hash latest-block-received))
      (do
        (println "We can append the recieved block to our chain")
        (swap! blockchain conj latest-block-received)
        (broadcast (prn-str (response-latest-msg)))
        :appended-chain)

      (= (count received-blocks) 1)
      (do
        (println "We have to query the chain from our peer")
        (broadcast (prn-str query-all-msg))
        :queried-chain)

      :else
      (do
        (println "Received blockchain is longer than current blockchain. Update chain")
        (replace-chain received-blocks)
        :updated-chain))))

(defn ws-message-handler
  ([socket-type socket]
   (println (str "initializing ws-message-handler " socket-type " " socket))
   (partial ws-message-handler socket-type socket))
  ([socket-type socket data]
   (println (str "Received message: " data) " " (type data))
   (let [data (edn/read-string data)
         socket (if (= socket-type :socket-type/client)
                  (get @sockets socket)
                  socket)
         response (case (:type data)
                    :message-type/QUERY-LATEST
                    (write socket-type socket (prn-str (response-latest-msg)))

                    :message-type/QUERY-ALL
                    (write socket-type socket (prn-str (response-chain-msg)))

                    :message-type/RESPONSE-BLOCKCHAIN
                    (handle-blockchain-response data)

                    :error)]
     (println (str "ws-message-handler: " response)))))


;; I want to take this handler, with an input chan and output chan
;; and
(comment
  (let [c1 (chan)
        c2 (chan)
        handle (ws-message-handler :socket-type/local c2)]
    (handle (prn-str {:type :message-type/QUERY-LATEST})))

  (let [name "Lauren"]
    (str name " Mackey"))

  )


(defn handler [request]
  (with-channel request channel
    (swap! channels conj channel)
    (write-channel channel (prn-str query-chain-length-msg))
    (on-close channel (fn [status] (println "channel closed: " status)))
    (on-receive channel (ws-message-handler :socket-type/server channel))))

(defn init-p2p-server
  ([] (init-p2p-server p2p-port))
  ([port] (reset! p2p-server (run-server handler {:port port}))))

(defn connect-to-peer [[ip port]]
  (let [connection-failed (str "connection failed to peer: "
                               ip ":" port " ")
        close-connection  (fn [[status reason]]
                            (println (str connection-failed
                                          status " " reason))
                            (swap! sockets dissoc [ip port]))
        error-connection  (fn [error]
                            (println (str connection-failed error))
                            (swap! sockets dissoc [ip port]))

        s (ws/connect (str "ws://" ip ":" port)
            :on-receive (ws-message-handler :socket-type/client [ip port])
            :on-error error-connection
            :on-close close-connection)]
    (swap! sockets assoc [ip port] s)
    (ws/send-msg s (prn-str {:type :message-type/QUERY-LATEST}))))

(defn connect-to-peers [new-peers]
  (reset! sockets {})
  (doseq [p new-peers]
    (connect-to-peer p)))

(defroutes all-routes
  (GET "/" [] (wrap-json-response (fn [req] {:body @blockchain}) {:pretty true}))
  (GET "/blocks" [] (wrap-json-response (fn [req] {:body @blockchain}) {:pretty true}))
  (POST "/mineBlock" []
        (wrap-json-response
         (wrap-json-body
          (fn [req]
            (let [new-block (generate-next-block (-> req :body :data))]
              (add-block new-block)
              (broadcast (prn-str (response-latest-msg)))
              (println (str "block added: " new-block))
              {:body new-block}))
          {:keywords? true})
         {:pretty true}))
  ;; need to add the channels as well
  (GET "/peers" []
       (wrap-json-response
        (fn [req] {:body (or (keys @sockets) [])})
        {:pretty true}))
  (POST "/addPeer" []
        (wrap-json-response
         (wrap-json-body
          (fn [req]
            (let [p (-> req :body :peer)]
              (connect-to-peer p)
              {:body p}))
          {:keywords? true})
         {:pretty true}))
  (not-found "<p>Page not found.</p>"))

(defn init-http-server [& args]
  (reset! http-server (run-server (site #'all-routes) {:port http-port})))

(comment
  (init-http-server)

  (stop-server http-server)

  (init-p2p-server 6001)

  (init-p2p-server 6002)

  (stop-server p2p-server)

  (connect-to-peers [["localhost" 6002]])

  )

(defn -main [& args]
  (connect-to-peers initial-peers)
  (init-http-server)
  (init-p2p-server))
