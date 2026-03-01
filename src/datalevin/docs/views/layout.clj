(ns datalevin.docs.views.layout
  (:require [hiccup2.core :as h]
            [jsonista.core :as j]
            [ring.middleware.anti-forgery :as anti-forgery]))

(def ^:private dark-body-style
  "html { scroll-behavior: smooth; }
body { background-color: #0a0a0f; background-image: radial-gradient(ellipse 80% 50% at 50% 0%, rgba(6,182,212,0.25), transparent), radial-gradient(ellipse 60% 50% at 100% 50%, rgba(59,130,246,0.2), transparent), radial-gradient(ellipse 60% 50% at 0% 100%, rgba(139,92,246,0.2), transparent), radial-gradient(rgba(255,255,255,0.04) 1px, transparent 1px); background-size: 100%, 100%, 100%, 24px 24px; background-attachment: fixed; min-height: 100vh; color: #e5e7eb; font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; --text-primary: #e5e7eb; --text-secondary: #9ca3af; --text-link: #22d3ee; --bg-card: rgba(255,255,255,0.05); --border-card: rgba(255,255,255,0.1); --bg-header: rgba(10,10,15,0.8); --border-header: rgba(255,255,255,0.1); }
.dl-card { display:block; padding:1.5rem; border-radius:0.75rem; transition: all 0.2s; text-decoration:none; color:inherit; cursor:pointer; background: rgba(255,255,255,0.05); backdrop-filter: blur(16px); -webkit-backdrop-filter: blur(16px); border: 1px solid rgba(255,255,255,0.1); box-shadow: 0 4px 24px rgba(0,0,0,0.2); }
.dl-card:hover { transform: translateY(-2px); border-color: rgba(6,182,212,0.4); box-shadow: 0 0 24px rgba(6,182,212,0.15), 0 4px 24px rgba(0,0,0,0.3); }
.dl-btn { display:inline-block; padding:0.625rem 1.25rem; border-radius:0.5rem; font-weight:500; font-size:0.875rem; text-decoration:none; transition: all 0.15s; cursor:pointer; color:rgba(255,255,255,0.7); background:rgba(255,255,255,0.06); border:1px solid rgba(255,255,255,0.15); backdrop-filter:blur(12px); -webkit-backdrop-filter:blur(12px); }
.dl-btn:hover { background:rgba(255,255,255,0.12); border-color:rgba(255,255,255,0.3); color:#fff; box-shadow: 0 2px 12px rgba(0,0,0,0.3); }
.dl-btn-primary { display:inline-block; color:white; padding:0.75rem 1.5rem; border-radius:0.5rem; font-weight:500; text-decoration:none; transition: all 0.15s; cursor:pointer; background:linear-gradient(135deg, #06b6d4, #3b82f6); border:none; }
.dl-btn-primary:hover { box-shadow: 0 0 24px rgba(6,182,212,0.4), 0 4px 12px rgba(59,130,246,0.3); }")

(def ^:private light-body-style
  "html { scroll-behavior: smooth; }
body.light { background-color: #f8fafc; background-image: radial-gradient(ellipse 80% 50% at 50% 0%, rgba(6,182,212,0.15), transparent), radial-gradient(ellipse 60% 50% at 100% 50%, rgba(59,130,246,0.1), transparent), radial-gradient(ellipse 60% 50% at 0% 100%, rgba(139,92,246,0.1), transparent), radial-gradient(rgba(0,0,0,0.05) 1px, transparent 1px); background-size: 100%, 100%, 100%, 24px 24px; background-attachment: fixed; min-height: 100vh; color: #1f2937; font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; --text-primary: #1f2937; --text-secondary: #475569; --text-link: #0891b2; --bg-card: rgba(255,255,255,0.8); --border-card: rgba(0,0,0,0.1); --bg-header: rgba(248,250,252,0.9); --border-header: rgba(0,0,0,0.1); }
body.light .dl-card { background: rgba(255,255,255,0.8); border: 1px solid rgba(0,0,0,0.1); box-shadow: 0 4px 24px rgba(0,0,0,0.08); color: #1f2937; }
body.light .dl-card h2, body.light .dl-card h3, body.light .dl-card h4 { color: #1f2937 !important; }
body.light .dl-card:hover { border-color: rgba(6,182,212,0.5); box-shadow: 0 0 24px rgba(6,182,212,0.15), 0 4px 24px rgba(0,0,0,0.12); }
body.light .dl-btn { color:rgba(0,0,0,0.7); background:rgba(0,0,0,0.06); border:1px solid rgba(0,0,0,0.15); }
body.light .dl-btn:hover { background:rgba(0,0,0,0.1); border-color:rgba(0,0,0,0.3); color:#000; box-shadow: 0 2px 12px rgba(0,0,0,0.1); }
body.light header { background:var(--bg-header, rgba(248,250,252,0.9)) !important; border-color:var(--border-header, rgba(0,0,0,0.1)) !important; }
body.light header a, body.light header span { color: var(--text-secondary, #475569) !important; }
body.light header .text-white { color: var(--text-primary, #1f2937) !important; }
body.light .prose h1, body.light .prose h2, body.light .prose h3, body.light .prose h4 { color: #1f2937 !important; }
body.light .prose p, body.light .prose li, body.light .prose td { color: #374151 !important; }
body.light .prose strong { color: #1f2937 !important; }
body.light .prose blockquote { color: #374151 !important; border-left-color: #0891b2 !important; background-color: rgba(0,0,0,0.03) !important; }
body.light .prose code { color: #1f2937 !important; background-color: rgba(0,0,0,0.08) !important; }
body.light .prose pre { background-color: #1f2937 !important; }
body.light .prose pre code { color: #e5e7eb !important; background-color: transparent !important; }
body.light h2.section-title { color: #1f2937 !important; }")

(defn header [& [req]]
  (let [user (:user req)]
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
                 :class "p-2 rounded-lg transition"
                 :title "Toggle light/dark theme"
                 :style "background:rgba(255,255,255,0.1); border:1px solid rgba(255,255,255,0.15); color:#9ca3af;"
                 :onclick "toggleTheme()"}
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
          [:a {:href "/auth/logout" :class "text-sm hover:text-gray-300" :style "color:#6b7280;"} "Logout"]]
         [:a {:href "/auth/login" :class "text-sm hover:text-white transition"
              :title "Sign in to post examples and download the book" :style "color:var(--text-secondary, #9ca3af);"} "Log in"])]]]))

(defn flash-message [flash]
  (when flash
    [:div {:class "fixed top-20 left-1/2 -translate-x-1/2 z-50"}
     (when-let [error (:error flash)]
       [:div {:class "px-4 py-3 rounded-lg shadow-lg text-sm"
              :style "background:rgba(220,38,38,0.15); border:1px solid rgba(220,38,38,0.4); color:#fca5a5; backdrop-filter:blur(12px);"
              :hx-get "/_flash" :hx-vals {:json "null"} :hx-trigger "load delay:3s"}
        error])
     (when-let [success (:success flash)]
       [:div {:class "px-4 py-3 rounded-lg shadow-lg text-sm"
              :style "background:rgba(34,197,94,0.15); border:1px solid rgba(34,197,94,0.4); color:#86efac; backdrop-filter:blur(12px);"
              :hx-get "/_flash" :hx-vals {:json "null"} :hx-trigger "load delay:3s"}
        success])]))

(defn base [title & body]
  (str (h/html {:mode :html :escape? false}
    [:html {:hx-boost "true" :lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:content "width=device-width, initial-scale=1" :name "viewport"}]
      [:title title]
      [:link {:rel "icon" :type "image/png" :href "/images/logo.png"}]
      [:script {:src "/js/htmx.min.js"}]
      [:link {:href "/css/output.css" :rel "stylesheet"}]
      [:script {:src "/js/theme.js" :defer true}]
      [:style dark-body-style]
      [:style light-body-style]]
     [:body {:class "dark"}
      (header)
      body]])))

(defn base-with-req [title req & body]
  (let [session (:session req)
        flash (:flash session)
        token (force anti-forgery/*anti-forgery-token*)]
    (str (h/html {:mode :html :escape? false}
      [:html {:hx-boost "true" :hx-headers (j/write-value-as-string {"X-CSRF-Token" token}) :lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:content "width=device-width, initial-scale=1" :name "viewport"}]
        [:title title]
        [:link {:rel "icon" :type "image/png" :href "/images/logo.png"}]
        [:script {:src "/js/htmx.min.js"}]
        [:link {:href "/css/output.css" :rel "stylesheet"}]
        [:link {:href "/css/hljs-atom-one-dark.min.css" :rel "stylesheet"}]
        [:script {:src "/js/highlight.min.js"}]
        [:script {:src "/js/hljs-clojure.min.js"}]
        [:script {:src "/js/code-highlight.js" :defer true}]
        [:script {:src "/js/gh-stars.js" :defer true}]
        [:script {:src "/js/theme.js" :defer true}]
        [:style dark-body-style]
        [:style light-body-style]]
       [:body {:class "dark"}
        (header req)
        (flash-message flash)
        body]]))))

(defn render-example [example & [req]]
  (let [{:keys [example/id example/code author]} example
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
                   :onclick "return confirm('Remove this example?')"}
          "Remove"]])]]))

(defn error-page
  "Renders a styled error page. Uses `base` (no req) so it works outside of session middleware."
  [status title message]
  (base (str status " — " title)
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
                                           :onclick (str "document.getElementById('example-form-" slug "').classList.toggle('hidden')")}
                                  "Add Example"]
                                 [:a {:href (str "/auth/login?return-to=/docs/" slug "%23examples")
                                      :class "text-sm text-cyan-400 hover:text-cyan-300"}
                                  "Log in to create examples"])]
                              [:div {:id (str "example-form-" slug) :class "mt-4 hidden"}
                               [:form {:method "post" :action "/examples" :hx-boost "false"
                                       :class "p-4 rounded-lg"
                                       :style "background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1);"}
                                [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
                                [:input {:type "hidden" :name "doc-section" :value slug}]
                                [:textarea {:name "code" :required true :rows 8
                                            :placeholder "Paste your code example here\n;; Add comments to describe it"
                                            :class "w-full px-3 py-2 rounded-lg font-mono text-sm mb-3 text-white outline-none"
                                            :style "background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1);"}]
                                [:div {:class "flex gap-3"}
                                 [:button {:type "submit" :class "py-2 px-4 text-white rounded-lg font-medium"
                                           :style "background:linear-gradient(135deg,#06b6d4,#3b82f6);"} "Submit"]
                                 [:button {:type "button" :class "py-2 px-4 rounded-lg text-gray-300"
                                           :style "border:1px solid rgba(255,255,255,0.15); background:rgba(255,255,255,0.05);"
                                           :onclick (str "document.getElementById('example-form-" slug "').classList.add('hidden')")} "Cancel"]]]]
                              (if (seq examples-list)
                               [:div {:class "space-y-4"}
                                (for [ex examples-list]
                                  (render-example ex req))]
                               [:p {:class "text-gray-500 text-sm"}
                                "No examples for this chapter yet."])
                              (when user
                                [:script (h/raw (str "if(location.hash==='#examples'){document.getElementById('example-form-" slug "').classList.remove('hidden')}"))])]))
        nav-html (str (h/html {:mode :html :escape? false} (chapter-nav doc)))]
    (base-with-req title req
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
