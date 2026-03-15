(ns datalevin.docs.views.layout
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [jsonista.core :as j]
            [ring.middleware.anti-forgery :as anti-forgery]
            [datalevin.docs.util :as util]))

(defn header [& [req]]
  (let [user (:user req)
        token (when user (force anti-forgery/*anti-forgery-token*))]
    [:header {:class "sticky top-0 z-50 border-b"
              :style "background:var(--bg-header, rgba(10,10,15,0.8)); backdrop-filter:blur(16px); -webkit-backdrop-filter:blur(16px); border-color:var(--border-header, rgba(255,255,255,0.1));"
              :data-theme "dark"}
     [:div {:class "max-w-5xl mx-auto px-4 py-3 flex items-center justify-between"}
      [:a {:href "/" :class "flex items-center gap-2"}
       [:img {:src "/images/logo.png" :alt "Datalevin" :class "h-8 w-8"}]
       [:span {:class "font-bold text-lg" :style "color:var(--text-primary, #e5e7eb)"} "Datalevin"]]
      [:nav {:class "hidden md:flex items-center gap-6"
             :style "color:var(--text-secondary, #9ca3af);"}
       [:a {:href "/docs" :class "hover:text-cyan-400 transition" :style "color:var(--text-secondary, #9ca3af);"} "The Book"]
       [:a {:href "/examples" :class "hover:text-cyan-400 transition" :style "color:var(--text-secondary, #9ca3af);"} "Examples"]
       [:a {:href "/search" :class "hover:text-cyan-400 transition" :style "color:var(--text-secondary, #9ca3af);"} "Search"]
       [:a {:href "https://github.com/datalevin/datalevin" :target "_blank" :rel "noopener noreferrer"
            :class "flex items-center gap-1.5 hover:text-cyan-400 transition"
            :style "color:var(--text-secondary, #9ca3af);"}
        [:span "GitHub"]
        [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 16 16" :fill "currentColor"
               :width "16" :height "16" :class "flex-shrink-0"}
         [:path {:d "M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"}]]
       [:span {:id "gh-stars" :class "text-xs font-medium whitespace-nowrap"}]]]
      [:div {:class "flex items-center gap-4"}
       [:button {:id "theme-toggle"
                 :type "button"
                 :class "p-2 rounded-lg transition"
                 :title "Toggle light/dark theme"
                 :style "background:rgba(255,255,255,0.1); border:1px solid rgba(255,255,255,0.15); color:#9ca3af;"
                 :data-theme-toggle "true"}
        [:svg {:xmlns "http://www.w3.org/2000/svg" :width "18" :height "18" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"
               :class "dark:hidden"}
         [:path {:d "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"}]]
        [:svg {:xmlns "http://www.w3.org/2000/svg" :width "18" :height "18" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"
               :class "hidden dark:block"}
         [:circle {:cx "12" :cy "12" :r "4"}]
         [:path {:d "M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"}]]]
       (if user
         [:div {:class "flex items-center gap-3"}
          [:span {:class "text-sm" :style "color:var(--text-secondary, #9ca3af)"} (:user/username user)]
          (when (= :admin (:user/role user))
            [:a {:href "/admin/examples" :class "text-sm hover:text-cyan-300" :style "color:var(--text-link, #22d3ee);"} "Admin"])
          [:a {:href "/pdf" :class "text-sm hover:text-cyan-300" :style "color:var(--text-link, #22d3ee);"} "PDF"]
          [:a {:href "/examples/new" :class "text-sm hover:text-cyan-300" :style "color:var(--text-link, #22d3ee);"} "Add Example"]
          [:form {:method "post" :action "/auth/logout" :hx-boost "false" :class "inline"}
           [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
           [:button {:type "submit"
                     :class "text-sm hover:text-gray-300"
                     :style "color:#6b7280; background:none; border:none; padding:0; cursor:pointer;"}
            "Logout"]]]
         [:a {:href "/auth/login" :class "text-sm hover:text-white transition"
              :title "Sign in to post examples and download the book" :style "color:var(--text-secondary, #9ca3af);"} "Log in"])]]]))

(defn flash-message [flash]
  (when flash
    [:div {:class "fixed top-20 left-1/2 -translate-x-1/2 z-50"}
     (when-let [error (:error flash)]
       [:div {:class "px-4 py-3 rounded-lg shadow-lg text-sm"
              :style "background:rgba(220,38,38,0.15); border:1px solid rgba(220,38,38,0.4); color:#fca5a5; backdrop-filter:blur(12px);"
              :data-flash-auto-dismiss "true"}
        error])
     (when-let [success (:success flash)]
       [:div {:class "px-4 py-3 rounded-lg shadow-lg text-sm"
              :style "background:rgba(34,197,94,0.15); border:1px solid rgba(34,197,94,0.4); color:#86efac; backdrop-filter:blur(12px);"
              :data-flash-auto-dismiss "true"}
        success])]))

(def ^:private default-image-path
  "/images/unified.jpg")

(defn- split-page-opts
  [body]
  (if (map? (first body))
    [(first body) (rest body)]
    [{} body]))

(defn- meta-tags
  [title req {:keys [description canonical-path canonical-url robots image-path image-url og-type]}]
  (let [page-title (util/page-title title)
        description (or (some-> description str/trim not-empty)
                        util/site-description)
        robots (or robots "index,follow")
        canonical-url (or canonical-url
                          (when-let [path (or canonical-path (some-> req :uri))]
                            (util/absolute-url req path)))
        page-url (or canonical-url
                     (when-let [uri (some-> req :uri)]
                       (util/absolute-url req uri)))
        image-url (or image-url
                      (when-let [path (or image-path default-image-path)]
                        (util/absolute-url req path)))
        og-type (or og-type "website")]
    (cond-> [[:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
             [:title page-title]
             [:meta {:name "description" :content description}]
             [:meta {:name "robots" :content robots}]
             [:meta {:name "theme-color" :content "#0a0a0f"}]
             [:meta {:property "og:site_name" :content util/site-name}]
             [:meta {:property "og:type" :content og-type}]
             [:meta {:property "og:title" :content page-title}]
             [:meta {:property "og:description" :content description}]
             [:meta {:name "twitter:card" :content "summary_large_image"}]
             [:meta {:name "twitter:title" :content page-title}]
             [:meta {:name "twitter:description" :content description}]]
      canonical-url (conj [:link {:rel "canonical" :href canonical-url}])
      page-url (conj [:meta {:property "og:url" :content page-url}])
      image-url (conj [:meta {:property "og:image" :content image-url}]
                      [:meta {:name "twitter:image" :content image-url}]))))

(defn- render-page
  [title req body]
  (let [[opts body] (split-page-opts body)
        session (:session req)
        flash (:flash session)
        token (when req (force anti-forgery/*anti-forgery-token*))
        html-attrs (cond-> {:hx-boost "true" :lang "en"}
                     token (assoc :hx-headers (j/write-value-as-string {"X-CSRF-Token" token})))
        head-assets (if req
                      [[:link {:href "/css/hljs-atom-one-dark.min.css" :rel "stylesheet"}]
                       [:link {:href "/css/multi-lang.css" :rel "stylesheet"}]
                       [:script {:src "/js/highlight.min.js"}]
                       [:script {:src "/js/hljs-clojure.min.js"}]
                       [:script {:src "/js/hljs-java.min.js"}]
                       [:script {:src "/js/hljs-python.min.js"}]
                       [:script {:src "/js/hljs-javascript.min.js"}]
                       [:script {:src "/js/code-highlight.js" :defer true}]
                       [:script {:src "/js/gh-stars.js" :defer true}]]
                      [])
        head-children (into [[:meta {:charset "utf-8"}]]
                            (concat (meta-tags title req opts)
                                    [[:link {:rel "icon" :type "image/png" :href "/images/logo.png"}]
                                     [:script {:src "/js/htmx.min.js"}]
                                     [:link {:href "/css/output.css" :rel "stylesheet"}]]
                                    head-assets
                                    [[:link {:href "/css/theme-shell.css" :rel "stylesheet"}]]
                                    [[:script {:src "/js/theme.js" :defer true}]
                                     [:script {:src "/js/ui-interactions.js" :defer true}]]))
        body-children (cond-> [(if req (header req) (header))]
                        (and req flash) (conj (flash-message flash)))
        body-children (into body-children body)]
    (str (h/html {:mode :html :escape? false}
                 [:html html-attrs
                  (into [:head] head-children)
                  (into [:body {:class "dark"}] body-children)]))))

(defn base [title & body]
  (render-page title nil body))

(defn base-with-req [title req & body]
  (render-page title req body))

(defn render-example [example & [req]]
  (let [{:keys [example/id example/code example/author]} example
        username (or (some-> author :user/username) "Anonymous")
        user (:user req)
        admin? (= :admin (:user/role user))
        token (when admin? (force anti-forgery/*anti-forgery-token*))]
    [:div {:class "rounded-lg overflow-hidden"
           :style "background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1);"}
     [:div {:class "px-4 py-3"}
      [:pre {:class "bg-gray-900 text-gray-100 p-3 rounded-lg overflow-x-auto text-sm font-mono"
             :style "background:#0d0d14; border:1px solid rgba(255,255,255,0.08);"}
       [:code code]]]
     [:div {:class "px-4 py-2 flex items-center justify-between"
            :style "border-top:1px solid rgba(255,255,255,0.08); background:rgba(255,255,255,0.03);"}
      [:span {:class "text-xs text-gray-500"} "by "
       [:a {:href (str "/users/" username) :class "text-cyan-400 hover:text-cyan-300 hover:underline"} username]]
      (when admin?
        [:form {:method "post" :action (str "/admin/examples/" id "/remove") :class "inline"}
         [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
         [:button {:type "submit" :class "text-xs text-red-400 hover:text-red-300 font-medium"
                   :data-confirm-message "Remove this example?"}
          "Remove"]])]]))

(defn error-page
  "Renders a styled error page. Uses `base` (no req) so it works outside of session middleware."
  [status title message]
  (base (str status " — " title)
    {:description message
     :robots "noindex,nofollow"}
    [:div {:class "max-w-xl mx-auto py-24 px-4 text-center"}
     [:p {:class "text-6xl font-bold mb-4" :style "color:rgba(255,255,255,0.15)"} (str status)]
     [:h1 {:class "text-2xl font-bold text-white mb-3"} title]
     [:p {:class "text-gray-400 mb-8"} message]
     [:a {:href "/" :class "dl-btn-primary"} "Go home"]]))

(defn not-found-page []
  (error-page 404 "Page not found" "The page you're looking for doesn't exist or has been moved."))

(defn server-error-page []
  (error-page 500 "Something went wrong" "An unexpected error occurred. Please try again later."))

(defn forbidden-page []
  (error-page 403 "Forbidden" "You don't have permission to access this page."))

(defn chapter-nav [{:keys [nav]}]
  (let [prev (:prev nav)
        nxt  (:next nav)]
    (when (or prev nxt)
      [:nav {:class "flex items-center justify-between mt-12 pt-6"
             :style "border-top:1px solid rgba(255,255,255,0.1);"}
       (if prev
         [:a {:href (str "/docs/" (:slug prev))
              :class "group flex items-center gap-2 text-sm text-gray-400 hover:text-cyan-400 transition no-underline"}
          [:span {:class "text-lg group-hover:-translate-x-0.5 transition-transform"} "←"]
          [:span {:class "font-medium text-gray-300 group-hover:text-cyan-400"} (:title prev)]]
         [:div])
       (if nxt
         [:a {:href (str "/docs/" (:slug nxt))
              :class "group flex items-center gap-2 text-sm text-gray-400 hover:text-cyan-400 transition text-right no-underline"}
          [:span {:class "font-medium text-gray-300 group-hover:text-cyan-400"} (:title nxt)]
          [:span {:class "text-lg group-hover:translate-x-0.5 transition-transform"} "→"]]
         [:div])])))

(defn doc-page [{:keys [title chapter part html examples slug nav] :as doc} & [req]]
  (let [user (:user req)
        token (when user (force anti-forgery/*anti-forgery-token*))
        description (or (:description doc)
                        util/site-description)
        examples-list (when (seq examples)
                        (mapv first examples))
        examples-html (str (h/html {:mode :html :escape? false}
                             [:div {:id "examples" :class "not-prose mt-10 pt-8"
                                    :style "border-top:1px solid rgba(255,255,255,0.1);"}
                              [:div {:class "flex items-center justify-between mb-4"}
                               [:h2 {:class "text-xl font-bold text-white"}
                                "User Examples"
                               (when (seq examples-list)
                                  [:span {:class "text-base font-normal text-gray-500 ml-2"}
                                   (str "(" (count examples-list) ")")])]
                               (if user
                                 [:button {:type "button"
                                           :class "text-sm text-white px-3 py-1.5 rounded-lg font-medium"
                                           :style "background:linear-gradient(135deg,#06b6d4,#3b82f6);"
                                           :data-toggle-target (str "#example-form-" slug)}
                                  "Add Example"]
                                 [:a {:href (str "/auth/login?return-to=/docs/" slug "%23examples")
                                      :class "text-sm text-cyan-400 hover:text-cyan-300"}
                                  "Log in to create examples"])]
                              [:div {:id (str "example-form-" slug)
                                     :class "mt-4 hidden"
                                     :data-open-when-hash "#examples"}
                               [:form {:method "post" :action "/examples" :hx-boost "false"
                                       :class "p-4 rounded-lg"
                                       :style "background:var(--bg-card, rgba(255,255,255,0.05)); border:1px solid var(--border-card, rgba(255,255,255,0.1));"}
                                [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
                                [:input {:type "hidden" :name "doc-section" :value slug}]
                                [:textarea {:name "code" :required true :rows 8
                                            :maxlength util/max-example-code-length
                                            :placeholder "Paste your code example here\n;; Add comments to describe it"
                                            :class "w-full px-3 py-2 rounded-lg font-mono text-sm mb-3 outline-none"
                                            :style "background:var(--input-bg, rgba(255,255,255,0.05)); border:1px solid var(--input-border, rgba(255,255,255,0.1)); color:var(--text-primary, #e5e7eb);"}]
                                [:p {:class "text-xs mb-3"
                                     :style "color:var(--text-secondary, #9ca3af);"}
                                util/example-code-help-text]
                                [:div {:class "flex gap-3"}
                                 [:button {:type "submit" :class "py-2 px-4 text-white rounded-lg font-medium"
                                           :style "background:linear-gradient(135deg,#06b6d4,#3b82f6);"} "Submit"]
                                 [:button {:type "button" :class "py-2 px-4 rounded-lg text-gray-300"
                                           :style "border:1px solid rgba(255,255,255,0.15); background:rgba(255,255,255,0.05);"
                                           :data-hide-target (str "#example-form-" slug)} "Cancel"]]]]
                              (if (seq examples-list)
                               [:div {:class "space-y-4"}
                                (for [ex examples-list]
                                  (render-example ex req))]
                               [:p {:class "text-gray-500 text-sm"}
                                "No examples for this chapter yet."])]))
        nav-html (str (h/html {:mode :html :escape? false} (chapter-nav doc)))]
    (base-with-req title req
      {:description description
       :canonical-path (str "/docs/" slug)
       :og-type "article"}
      [:div {:class "max-w-5xl mx-auto px-6 py-10"}
       ;; Breadcrumb
       [:nav {:class "text-sm mb-6 text-gray-500"}
        [:a {:href "/" :class "hover:text-cyan-400 transition"} "Home"]
        [:span {:class "mx-2"} "/"]
        [:a {:href "/docs" :class "hover:text-cyan-400 transition"} "Docs"]
        [:span {:class "mx-2"} "/"]
        [:span {:class "text-gray-300"} title]]
       ;; Part label
       (when part
         [:div {:class "text-sm font-medium uppercase tracking-wide mb-2 text-cyan-400"} (str "Part " part)])
       ;; Article
       [:article {:class "prose prose-datalevin max-w-none rounded-xl px-10 py-12"
                  :style "background:rgba(255,255,255,0.06); backdrop-filter:blur(20px); -webkit-backdrop-filter:blur(20px); border:1px solid rgba(255,255,255,0.1); box-shadow:0 8px 32px rgba(0,0,0,0.3);"}
        (h/raw html)
        (h/raw examples-html)
        (h/raw nav-html)]])))
