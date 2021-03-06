(ns dogfort.http
  (:require-macros [cljs.node-macros :as n])
  (:require [cljs.node :as node]
            [redlobster.events :as e]
            [redlobster.stream :as s]
            [redlobster.promise :as p]
            [dogfort.util.response :as response]))

(n/require "http" http)
(n/require "url" url)
(n/require "stream" Stream)

(defprotocol IHTTPResponseWriter
  (-write-response [data res] "Write data to a http.ServerResponse"))

(defn- send-result [res ring-result]
  (let [{:keys [status headers body]} ring-result]
    (set! (.-statusCode res) status)
    (doseq [[header value] headers]
      (.setHeader res (clj->js header) (clj->js value)))
    (when (-write-response body res)
      (.end res))))

(defn- send-error-page [res status err]
  (response/default-response 500))

(extend-protocol IHTTPResponseWriter
  string
  (-write-response [data res]
    (.write res data)
    true)

  PersistentVector
  (-write-response [data res]
    (doseq [i data] (-write-response i res))
    true)

  List
  (-write-response [data res]
    (doseq [i data] (-write-response i res))
    true)

  LazySeq
  (-write-response [data res]
    (doseq [i data] (-write-response i res))
    true)

  js/Buffer
  (-write-response [data res]
    (.write res data)
    true)

  Stream
  (-write-response [data res]
    (e/on data :error #(send-error-page res 500 %))
    (.pipe data res)
    false))

(defn- build-listener [handler options]
  (fn [req res]
    (let [{uri "pathname" query "search"} (js->clj (.parse url (.-url req)))
          headers (js->clj (.-headers req))
          conn (.-connection req)
          address (js->clj (.address conn))
          peer-cert-fn (.-getPeerCertificate conn)
          ring-req
          {:server-port (address "port")
           :server-name (address "address")
           :remote-addr (.-remoteAddress conn)
           :uri uri
           :query-string query
           :scheme "http"
           :request-method (keyword (.toLowerCase (.-method req)))
           :content-type (headers "content-type")
           :content-length (headers "content-length")
           :character-encoding nil
           :ssl-client-cert (when peer-cert-fn (peer-cert-fn))
           :headers headers
           :body req}
          result (handler ring-req)]
      (p/on-realised result
                     #(send-result res %)
                     #(send-error-page res 500 %)))))

(defn run-http [handler options]
  (let [server (.createServer http (build-listener handler options))]
    (.listen server (:port options))))
