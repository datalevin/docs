(ns datalevin.docs.views.layout
  (:require [hiccup2.core :as h]
            [jsonista.core :as j]
            [ring.middleware.anti-forgery :as anti-forgery]))

(defn header [& [req]]
  (let [user (:user req)]
    [:header {:class "sticky top-0 z-50 bg-white border-b shadow-sm"}
     [:div {:class "max-w-5xl mx-auto px-4 py-3 flex items-center justify-between"}
[:a {:href "/" :class "flex items-center gap-2"}
        [:img {:src "/images/logo.png" :alt "Datalevin" :class "h-8 w-8"}]
        [:span {:class "font-bold text-lg"} "Datalevin"]]
      [:nav {:class "hidden md:flex items-center gap-6"}
       [:a {:href "/docs" :class "text-gray-700 hover:text-blue-600"} "The Book"]
       [:a {:href "/examples" :class "text-gray-700 hover:text-blue-600"} "Examples"]
       [:a {:href "/search" :class "text-gray-700 hover:text-blue-600"} "Search"]
       [:a {:href "https://github.com/datalevin/datalevin" :target "_blank" :rel "noopener noreferrer"
            :class "flex items-center gap-1.5 text-gray-700 hover:text-blue-600"}
        [:span "GitHub"]
        [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 16 16" :fill "currentColor"
               :width "16" :height "16" :class "flex-shrink-0"}
         [:path {:d "M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"}]]
        [:span {:id "gh-stars" :class "text-xs font-medium whitespace-nowrap"}]]]
      [:div {:class "flex items-center gap-4"}
       (if user
         [:div {:class "flex items-center gap-3"}
          [:span {:class "text-sm text-gray-600"} (:user/username user)]
          (when (= :admin (:user/role user))
            [:a {:href "/admin/examples" :class "text-sm text-blue-600 hover:underline"} "Admin"])
          [:a {:href "/pdf" :class "text-sm text-blue-600 hover:underline"} "PDF"]
          [:a {:href "/examples/new" :class "text-sm text-blue-600 hover:underline"} "Add Example"]
          [:a {:href "/auth/logout" :class "text-sm text-gray-500 hover:text-gray-700"} "Logout"]]
         [:a {:href "/auth/login" :class "text-sm text-gray-600 hover:text-gray-900"
              :title "Sign in to post examples and download the book"} "Log in"])]]]))

(defn flash-message [flash]
  (when flash
    [:div {:class "fixed top-20 left-1/2 -translate-x-1/2 z-50"}
     (when-let [error (:error flash)]
       [:div {:class "bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded shadow-lg"
              :hx-get "/_flash" :hx-vals {:json "null"} :hx-trigger "load delay:3s"}
        error])
     (when-let [success (:success flash)]
       [:div {:class "bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded shadow-lg"
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
      [:style "html { scroll-behavior: smooth; }
body { background: linear-gradient(135deg, #eef2ff 0%, #f9fafb 40%, #f0fdf4 70%, #fefce8 100%); background-attachment: fixed; min-height: 100vh; }"]]
     [:body
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
        [:style "html { scroll-behavior: smooth; }
body { background: linear-gradient(135deg, #eef2ff 0%, #f9fafb 40%, #f0fdf4 70%, #fefce8 100%); background-attachment: fixed; min-height: 100vh; }
.dl-card { display:block; background:white; padding:1.5rem; border-radius:0.5rem; border:1px solid #e5e7eb; transition: transform 0.15s, box-shadow 0.15s; text-decoration:none; color:inherit; cursor:pointer; }
.dl-card:hover { transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.08); border-color: #c7d2fe; }
.dl-btn { display:inline-block; border:1px solid #d1d5db; background:white; color:#374151; padding:0.625rem 1.25rem; border-radius:0.5rem; font-weight:500; font-size:0.875rem; text-decoration:none; transition: all 0.15s; cursor:pointer; }
.dl-btn:hover { background:#f3f4f6; border-color:#9ca3af; color:#111827; box-shadow: 0 2px 6px rgba(0,0,0,0.06); }
.dl-btn-primary { display:inline-block; background:#2563eb; color:white; padding:0.75rem 1.5rem; border-radius:0.5rem; font-weight:500; text-decoration:none; transition: all 0.15s; cursor:pointer; }
.dl-btn-primary:hover { background:#1d4ed8; box-shadow: 0 4px 12px rgba(37,99,235,0.3); }"]]
       [:body
        (header req)
        (flash-message flash)
        body]]))))

(defn render-example [example & [req]]
  (let [{:keys [example/id example/title example/code example/output example/description author]} example
        username (or (some-> author :user/username) "Anonymous")
        user (:user req)
        admin? (= :admin (:user/role user))
        token (when admin? (force anti-forgery/*anti-forgery-token*))]
    [:div {:class "border border-gray-200 rounded-lg overflow-hidden bg-white shadow-sm"}
     [:div {:class "bg-gray-50 px-4 py-2 border-b border-gray-200 flex items-center justify-between"}
      [:h4 {:class "text-base font-semibold text-gray-900"} title]
      (when admin?
        [:form {:method "post" :action (str "/admin/examples/" id "/remove") :class "inline"}
         [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
         [:button {:type "submit" :class "text-xs text-red-600 hover:text-red-800 font-medium"
                   :onclick "return confirm('Remove this example?')"}
          "Remove"]])]
     (when description
       [:div {:class "px-4 py-2 text-sm text-gray-600 border-b border-gray-100"} description])
     [:div {:class "px-4 py-3"}
      [:pre {:class "bg-gray-900 text-gray-100 p-3 rounded-lg overflow-x-auto text-sm font-mono"}
       [:code code]]]
     (when output
       [:div {:class "px-4 py-2 bg-blue-50 border-t border-blue-100"}
        [:span {:class "text-xs font-medium text-blue-700"} "Output: "]
        [:code {:class "text-sm text-blue-800"} output]])
     [:div {:class "px-4 py-2 border-t border-gray-100 bg-gray-50"}
      [:span {:class "text-xs text-gray-500"} "by "
       [:a {:href (str "/users/" username) :class "text-blue-600 hover:underline"} username]]]]))

(defn error-page
  "Renders a styled error page. Uses `base` (no req) so it works outside of session middleware."
  [status title message]
  (base (str status " — " title)
    [:div {:class "max-w-xl mx-auto py-24 px-4 text-center"}
     [:p {:class "text-6xl font-bold text-gray-300 mb-4"} (str status)]
     [:h1 {:class "text-2xl font-bold text-gray-900 mb-3"} title]
     [:p {:class "text-gray-500 mb-8"} message]
     [:a {:href "/" :class "inline-block bg-blue-600 text-white px-5 py-2.5 rounded-lg font-medium hover:bg-blue-700"} "Go home"]]))

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
      [:nav {:class "flex items-center justify-between mt-12 pt-6 border-t border-gray-200"}
       (if prev
         [:a {:href (str "/docs/" (:slug prev))
              :class "group flex items-center gap-2 text-sm text-gray-500 hover:text-blue-600 transition no-underline"}
          [:span {:class "text-lg group-hover:-translate-x-0.5 transition-transform"} "\u2190"]
          [:span [:span {:class "block text-xs text-gray-400 uppercase tracking-wide"} "Previous"]
                 [:span {:class "font-medium text-gray-700 group-hover:text-blue-600"} (:title prev)]]]
         [:div])
       (if nxt
         [:a {:href (str "/docs/" (:slug nxt))
              :class "group flex items-center gap-2 text-sm text-gray-500 hover:text-blue-600 transition text-right no-underline"}
          [:span [:span {:class "block text-xs text-gray-400 uppercase tracking-wide"} "Next"]
                 [:span {:class "font-medium text-gray-700 group-hover:text-blue-600"} (:title nxt)]]
          [:span {:class "text-lg group-hover:translate-x-0.5 transition-transform"} "\u2192"]]
         [:div])])))

(defn doc-page [{:keys [title chapter part html examples slug nav] :as doc} & [req]]
  (let [user (:user req)
        examples-list (when (seq examples)
                        (mapv first examples))
        examples-html (str (h/html {:mode :html :escape? false}
                             [:div {:class "mt-10 border-t border-gray-200 pt-8"}
                              [:div {:class "flex items-center justify-between mb-4"}
                               [:h2 {:class "text-xl font-bold text-gray-900"}
                                "Community Examples"
                                (when (seq examples-list)
                                  [:span {:class "text-base font-normal text-gray-400 ml-2"}
                                   (str "(" (count examples-list) ")")])]
                               (if user
                                 [:a {:href (str "/examples/new?doc-section=" slug)
                                      :class "text-sm bg-blue-600 text-white px-3 py-1.5 rounded-lg font-medium hover:bg-blue-700"}
                                  "Add Example"]
                                 [:a {:href "/auth/login"
                                      :class "text-sm text-blue-600 hover:underline"}
                                  "Log in to create examples"])]
                              (if (seq examples-list)
                                [:div {:class "space-y-4"}
                                 (for [ex examples-list]
                                   (render-example ex req))]
                                [:p {:class "text-gray-400 text-sm"}
                                 "No examples for this chapter yet."
                                 (when user
                                   [:span " "
                                    [:a {:href (str "/examples/new?doc-section=" slug)
                                         :class "text-blue-600 hover:underline"} "Be the first!"]])])]))
        nav-html (str (h/html {:mode :html :escape? false} (chapter-nav doc)))]
    (base-with-req title req
      [:div {:class "max-w-5xl mx-auto px-6 py-10"}
       ;; Breadcrumb
       [:nav {:class "text-sm mb-6 text-gray-400"}
        [:a {:href "/" :class "hover:text-blue-600 transition"} "Home"]
        [:span {:class "mx-2"} "/"]
        [:a {:href "/docs" :class "hover:text-blue-600 transition"} "Docs"]
        [:span {:class "mx-2"} "/"]
        [:span {:class "text-gray-600"} title]]
       ;; Part label
       (when part
         [:div {:class "text-sm font-medium text-blue-600 uppercase tracking-wide mb-2"} (str "Part " part)])
       ;; Article
       [:article {:class "prose prose-datalevin max-w-none bg-white rounded-xl shadow-sm px-10 py-12 border border-gray-100"}
        (h/raw html)
        (h/raw examples-html)
        (h/raw nav-html)]])))

