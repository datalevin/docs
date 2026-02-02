(ns datalevin.docs.middleware.rate-limit
  "Rate limiting middleware backed by Datalevin.

   Limits:
   - Login attempts: 5 per 15 minutes per IP
   - Example submissions: 10 per hour per user
   - Search: 60 per minute per IP"
  (:require [datalevin.core :as d])
  (:import [java.util Date]))

(def ^:private limits
  {:login    {:max 5   :window-ms (* 15 60 1000)}   ; 5 per 15 min
   :example  {:max 10  :window-ms (* 60 60 1000)}   ; 10 per hour
   :search   {:max 60  :window-ms (* 60 1000)}})    ; 60 per min

(defn- rate-limit-key
  "Generate rate limit key based on action and identifier."
  [action identifier]
  (str (name action) ":" identifier))

(defn- get-client-ip [request]
  (or (get-in request [:headers "x-forwarded-for"])
      (get-in request [:headers "x-real-ip"])
      (:remote-addr request)))

(defn- window-expired? [window-start window-ms]
  (> (- (System/currentTimeMillis) (.getTime ^Date window-start))
     window-ms))

(defn- get-rate-limit [db key]
  (d/entity db [:rate-limit/key key]))

(defn- reset-rate-limit! [conn key]
  (d/transact! conn [{:rate-limit/key key
                      :rate-limit/count 1
                      :rate-limit/window-start (Date.)}]))

(defn- increment-rate-limit! [conn key current-count]
  (d/transact! conn [{:rate-limit/key key
                      :rate-limit/count (inc current-count)}]))

(defn check-rate-limit!
  "Check and update rate limit. Returns {:allowed? bool :remaining int :retry-after-ms int}."
  [conn action identifier]
  (let [{:keys [max window-ms]} (get limits action)
        key (rate-limit-key action identifier)
        db (d/db conn)
        record (get-rate-limit db key)]
    (cond
      ;; No record exists - create one
      (nil? record)
      (do (reset-rate-limit! conn key)
          {:allowed? true :remaining (dec max)})

      ;; Window expired - reset
      (window-expired? (:rate-limit/window-start record) window-ms)
      (do (reset-rate-limit! conn key)
          {:allowed? true :remaining (dec max)})

      ;; Under limit - increment
      (< (:rate-limit/count record) max)
      (do (increment-rate-limit! conn key (:rate-limit/count record))
          {:allowed? true :remaining (- max (:rate-limit/count record) 1)})

      ;; Over limit
      :else
      (let [elapsed (- (System/currentTimeMillis)
                       (.getTime ^Date (:rate-limit/window-start record)))
            retry-after (- window-ms elapsed)]
        {:allowed? false
         :remaining 0
         :retry-after-ms retry-after}))))

(defn rate-limit-response
  "Generate 429 Too Many Requests response."
  [retry-after-ms]
  {:status 429
   :headers {"Retry-After" (str (quot retry-after-ms 1000))}
   :body {:error "Too many requests" :retry-after-seconds (quot retry-after-ms 1000)}})

(defn wrap-rate-limit
  "Rate limiting middleware.

   Options:
   - :action - Rate limit action type (:login, :example, :search)
   - :identifier-fn - Function to extract identifier from request (default: client IP)"
  [handler {:keys [action identifier-fn]
            :or {identifier-fn get-client-ip}}]
  (fn [{:keys [conn] :as request}]
    (let [identifier (identifier-fn request)
          {:keys [allowed? remaining retry-after-ms]} (check-rate-limit! conn action identifier)]
      (if allowed?
        (-> (handler request)
            (assoc-in [:headers "X-RateLimit-Remaining"] (str remaining)))
        (rate-limit-response retry-after-ms)))))

(defn wrap-login-rate-limit
  "Rate limit login attempts by IP."
  [handler]
  (wrap-rate-limit handler {:action :login}))

(defn wrap-example-rate-limit
  "Rate limit example submissions by user ID."
  [handler]
  (wrap-rate-limit handler {:action :example
                            :identifier-fn #(get-in % [:session :user/id])}))

(defn wrap-search-rate-limit
  "Rate limit search requests by IP."
  [handler]
  (wrap-rate-limit handler {:action :search}))
