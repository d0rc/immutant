;; Copyright 2014 Red Hat, Inc, and individual contributors.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;; http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns immutant.web.javax
  "A means of creating Servlets and JSR-356 Endpoints from Ring handlers and callback functions"
  (:require [ring.util.servlet :as ring]
            [immutant.web.javax.session :refer (wrap-servlet-session)]
            [immutant.websocket])
  (:import [org.projectodd.wunderboss.websocket Util]
           [javax.servlet Servlet ServletConfig ServletContext]
           [javax.servlet.http HttpServlet HttpServletRequest HttpSession]
           [javax.websocket Endpoint MessageHandler$Whole]
           [javax.websocket.server ServerContainer ServerEndpointConfig$Builder ServerEndpointConfig$Configurator]))

(extend-type javax.websocket.Session
  immutant.websocket/Channel
  (send! [ch message] (.sendObject (.getAsyncRemote ch) message))
  (open? [ch] (.isOpen ch))
  (close [ch] (.close ch)))

(defn create-servlet
  "Encapsulate a ring handler within a servlet's service method,
  storing :session data in the associated HttpSession"
  ([]
     (create-servlet (fn [_] {:status 200, :body "OK"})))
  ([handler]
     (-> handler
       wrap-servlet-session
       ring/servlet)))

(defn ^Endpoint create-endpoint
  "Create a JSR-356 endpoint from one or more callback functions.

  The following callbacks are supported, where `channel` is an
  instance of `javax.websocket.Session`, extended to the
  {{immutant.websocket/Channel}} protocol:

    * :on-message `(fn [channel message])`
    * :on-open    `(fn [channel])`
    * :on-close   `(fn [channel {:keys [code reason]}])`
    * :on-error   `(fn [channel throwable])`"
  ([key value & key-values]
     (create-endpoint (apply hash-map key value key-values)))
  ([{:keys [on-message on-open on-close on-error]}]
     (proxy [Endpoint] []
       (onOpen [session config]
         (when on-open (on-open session))
         (when on-message
           (let [handler (reify MessageHandler$Whole
                           (onMessage [_ message] (on-message session message)))]
             (.addMessageHandler session (Util/createTextHandler handler))
             (.addMessageHandler session (Util/createBinaryHandler handler)))))
       (onClose [session reason]
         (when on-close (on-close session
                          {:code (.. reason getCloseCode getCode)
                           :reason (.getReasonPhrase reason)})))
       (onError [session error]
         (when on-error (on-error session error))))))

(defn add-endpoint
  "Adds an endpoint to a container obtained from the servlet-context,
  typically called from {{attach-endpoint}}"
  ([^Endpoint endpoint ^ServletContext servlet-context]
     (add-endpoint endpoint servlet-context {}))
  ([^Endpoint endpoint ^ServletContext servlet-context {:keys [path handshake] :or {path "/"}}]
     (let [^ServerContainer container (.getAttribute servlet-context "javax.websocket.server.ServerContainer")
           config (.. ServerEndpointConfig$Builder
                    (create (class endpoint) path)
                    (configurator (proxy [ServerEndpointConfig$Configurator] []
                                    (getEndpointInstance [c] endpoint)
                                    (modifyHandshake [config request response]
                                      (if handshake
                                        (handshake config request response)
                                        (-> config
                                          .getUserProperties
                                          (.put "HandshakeRequest" request))))))
                    build)]
       (.addEndpoint container config))))

(defn attach-endpoint
  "Attach a JSR-356 endpoint to a servlet. If the servlet is already
  running, the endpoint will be deployed immediately. Otherwise, it'll
  be deployed when the servlet is initialized. Either the servlet or a
  proxy for it will be returned.

  A :path option may be specified. It will be resolved relative to the
  path on which the returned servlet is mounted.

  If a :handshake callback is passed, it will be used as the
  `modifyHandshake` method on a `ServerEndpointConfig$Configurator`
  instance. Because this is often used as a means to obtain the
  handshake's request headers or the servlet's session, we store the
  `HandshakeRequest` parameter passed to `modifyHandshake` in the map
  returned by `Session.getUserProperties` by default, i.e. when
  :handshake is *not* passed"
  ([^Servlet servlet ^Endpoint endpoint]
     (attach-endpoint servlet endpoint {}))
  ([^Servlet servlet ^Endpoint endpoint {:keys [path handshake] :as options}]
     (if-let [config (.getServletConfig servlet)]
       (do
         (add-endpoint endpoint (.getServletContext config) options)
         servlet)
       (proxy [javax.servlet.Servlet] []
         (init [^ServletConfig config]
           (.init servlet config)
           (add-endpoint endpoint (.getServletContext config) options))
         (service [request response]
           (.service servlet request response))
         (destroy []
           (.destroy servlet))
         (getServletConfig []
           (.getServletConfig servlet))
         (getServletInfo []
           (.getServletInfo servlet))))))

(defn ^HttpSession http-session
  "Returns the servlet's HttpSession from the ring request"
  [request]
  (if-let [^HttpServletRequest hsr (:servlet-request request)]
    (.getSession hsr)))

(defn ^String context-path
  "Returns the servlet context path from the ring request"
  [request]
  (if-let [^HttpServletRequest hsr (:servlet-request request)]
    (str (.getContextPath hsr) (.getServletPath hsr))))

(defn ^String path-info
  "Returns the servlet path info from the ring request"
  [request]
  (if-let [^HttpServletRequest hsr (:servlet-request request)]
    (let [result (.substring (.getRequestURI hsr) (.length (context-path hsr)))]
      (if (.isEmpty result)
        "/"
        result))))