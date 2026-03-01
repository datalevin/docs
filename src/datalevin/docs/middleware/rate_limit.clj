(ns datalevin.docs.middleware.rate-limit
  "In-memory rate limiting middleware.

   Limits:
   - Login attempts: 5 per 15 minutes per IP
   - Registration: 3 per hour per IP
   - Example submissions: 10 per hour per user
   - Search API: 60 per minute per IP
   - Password reset: 3 per 15 minutes per IP")

(def ^:private limits
  {:login    {:max 5   :window-ms (* 15 60 1000)}
   :register {:max 3   :window-ms (* 60 60 1000)}
   :example  {:max 10  :window-ms (* 60 60 1000)}
   :search   {:max 60  :window-ms (* 60 1000)}
   :reset    {:max 3   :window-ms (* 15 60 1000)}})

;; {key {:timestamps [t1 t2 ...], :last-cleanup t}}
(defonce ^:private state (atom {}))

(def ^:private cleanup-interval-ms (* 10 60 1000)) ;; 10 minutes

(defn- get-client-ip [request]
  (or (some-> (get-in request [:headers "x-forwarded-for"])
              (clojure.string/split #",")
              first
              clojure.string/trim)
      (get-in request [:headers "x-real-ip"])
      (:remote-addr request)))

(defn- cleanup-window [timestamps now window-ms]
  (let [cutoff (- now window-ms)]
    (filterv #(> % cutoff) timestamps)))

(defn- cleanup-old-entries!
  "Removes entries with no recent timestamps from state. Called periodically."
  [s now]
  (into {} (filter (fn [[_k v]]
                     (seq (:timestamps v))))
        s))

(defn- check-rate-limit!
  "Returns {:allowed? bool :remaining int :retry-after-ms int}."
  [action identifier]
  (let [{:keys [max window-ms]} (get limits action)
        key (str (name action) ":" identifier)
        now (System/currentTimeMillis)
        result (volatile! nil)]  ;; Use volatile! instead of atom for single-threaded context
    (swap! state
           (fn [s]
             (let [entry (get s key)
                   timestamps (cleanup-window (or (:timestamps entry) []) now window-ms)
                   count (count timestamps)
                   last-cleanup (or (:last-cleanup s) 0)]
               ;; Periodically clean up old entries from the entire state (every 10 minutes)
               (let [s (if (> (- now last-cleanup) cleanup-interval-ms)
                         (cleanup-old-entries! s now)
                         s)]
                 (if (< count max)
                   (do (vreset! result {:allowed? true :remaining (- max count 1)})
                       (assoc s key {:timestamps (conj timestamps now)
                                     :last-cleanup now}))
                   (let [oldest (first timestamps)
                         retry-after (- (+ oldest window-ms) now)]
                     (vreset! result {:allowed? false :remaining 0 :retry-after-ms (clojure.core/max retry-after 0)})
                     s))))))
    @result)))

(defn- rate-limit-response [retry-after-ms]
  {:status 429
   :headers {"Content-Type" "text/html"
             "Retry-After" (str (max 1 (quot retry-after-ms 1000)))}
   :body "Too many requests. Please try again later."})

(defn wrap-rate-limit
  "Rate limiting middleware.

   Options:
   - :action - Rate limit action type (:login, :register, :example, :search, :reset)
   - :identifier-fn - Function to extract identifier from request (default: client IP)"
  [handler {:keys [action identifier-fn]
            :or {identifier-fn get-client-ip}}]
  (fn [request]
    (let [identifier (identifier-fn request)
          {:keys [allowed? remaining retry-after-ms]} (check-rate-limit! action identifier)]
      (if allowed?
        (-> (handler request)
            (assoc-in [:headers "X-RateLimit-Remaining"] (str remaining)))
        (rate-limit-response retry-after-ms)))))

(defn wrap-login-rate-limit [handler]
  (wrap-rate-limit handler {:action :login}))

(defn wrap-register-rate-limit [handler]
  (wrap-rate-limit handler {:action :register}))

(defn wrap-example-rate-limit [handler]
  (wrap-rate-limit handler {:action :example
                            :identifier-fn #(or (get-in % [:user :user/id] "anon")
                                                (get-client-ip %))}))

(defn wrap-search-rate-limit [handler]
  (wrap-rate-limit handler {:action :search}))

(defn wrap-reset-rate-limit [handler]
  (wrap-rate-limit handler {:action :reset}))
