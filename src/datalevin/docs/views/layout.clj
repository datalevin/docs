(ns datalevin.docs.views.layout
  (:require [hiccup2.core :as h]
            [ring.middleware.anti-forgery :as anti-forgery]))

(defn header [& [req]]
  (let [user (:user req)]
    [:header {:class "sticky top-0 z-50 bg-white border-b shadow-sm"}
     [:div {:class "max-w-5xl mx-auto px-4 py-3 flex items-center justify-between"}
      [:a {:href "/" :class "flex items-center gap-2"}
       [:span {:class "text-2xl"} "📚"]
       [:span {:class "font-bold text-lg"} "Datalevin"]]
      [:nav {:class "hidden md:flex items-center gap-6"}
       [:a {:href "/docs" :class "text-gray-700 hover:text-blue-600"} "Docs"]
       [:a {:href "/search" :class "text-gray-700 hover:text-blue-600"} "Search"]]
      [:div {:class "flex items-center gap-4"}
       (if user
         [:div {:class "flex items-center gap-3"}
          [:span {:class "text-sm text-gray-600"} (:user/username user)]
          [:a {:href "/pdf" :class "text-sm text-blue-600 hover:underline"} "PDF"]
          [:a {:href "/examples/new" :class "text-sm text-blue-600 hover:underline"} "Add Example"]
          [:a {:href "/auth/logout" :class "text-sm text-gray-500 hover:text-gray-700"} "Logout"]]
         [:div {:class "flex items-center gap-3"}
          [:a {:href "/auth/login" :class "text-sm text-gray-600 hover:text-gray-900"} "Log in"]
          [:a {:href "/auth/register" :class "bg-blue-600 text-white px-4 py-1.5 rounded-lg text-sm font-medium hover:bg-blue-700"} "Sign up"]])]]]))

(defn flash-message [flash]
  (when flash
    [:div {:class "fixed top-20 left-1/2 -translate-x-1/2 z-50"}
     (when-let [error (:error flash)]
       [:div {:class "bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded shadow-lg"
              :hx-get "/_flash" :hx-vals {:json (pr-str {:success nil})} :hx-trigger "load delay:3s"}
        error])
     (when-let [success (:success flash)]
       [:div {:class "bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded shadow-lg"
              :hx-get "/_flash" :hx-vals {:json (pr-str {:error nil})} :hx-trigger "load delay:3s"}
        success])]))

(defn base [title & body]
  (str (h/html {:mode :html}
    "<!DOCTYPE html>"
    [:html {:hx-boost "true" :lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:content "width=device-width, initial-scale=1" :name "viewport"}]
      [:title title]
      [:script {:src "/js/htmx.min.js"}]
      [:link {:href "/css/output.css" :rel "stylesheet"}]
      [:style "html { scroll-behavior: smooth; }"]]
     [:body {:class "bg-gray-50"}
      (header)
      body]])))

(defn base-with-req [title req & body]
  (let [session (:session req)
        flash (:flash session)
        token (force anti-forgery/*anti-forgery-token*)]
    (str (h/html {:mode :html}
      "<!DOCTYPE html>"
      [:html {:hx-boost "true" :hx-headers (pr-str {:X-CSRF-Token token}) :lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:content "width=device-width, initial-scale=1" :name "viewport"}]
        [:title title]
        [:script {:src "/js/htmx.min.js"}]
        [:link {:href "/css/output.css" :rel "stylesheet"}]
        [:style "html { scroll-behavior: smooth; }"]]
       [:body {:class "bg-gray-50 min-h-screen"}
        (header req)
        (flash-message flash)
        body]]))))

(defn render-example [example]
  (let [{:keys [example/title example/code example/output example/description author]} example
        username (or (some-> author :user/username) "Anonymous")]
    [:div {:class "border border-gray-200 rounded-lg overflow-hidden bg-white shadow-sm"}
     [:div {:class "bg-gray-50 px-4 py-2 border-b border-gray-200"}
      [:h4 {:class "text-base font-semibold text-gray-900"} title]]
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
      [:span {:class "text-xs text-gray-500"} "by " username]]]))

(defn doc-page [{:keys [title chapter part html examples] :as doc} & [req]]
  (base-with-req title req
    [:div {:class "max-w-5xl mx-auto px-4 py-8"}
     [:nav {:class "text-sm mb-4"}
      [:a {:class "text-blue-600 hover:underline" :href "/"} "Home"]
      [:span {:class "mx-2 text-gray-400"} "/"]
      [:a {:class "text-blue-600 hover:underline" :href "/docs"} "Docs"]
      [:span {:class "mx-2 text-gray-400"} "/"]
      [:span {:class "text-gray-600"} title]]
     (when part [:div {:class "text-sm text-gray-500 mb-2"} part])
     [:article {:class "prose prose-lg max-w-none bg-white rounded-xl shadow-sm p-8"}
      html]
     [:div {:class "mt-12"}
      [:div {:class "flex items-center justify-between mb-6"}
       [:h2 {:class "text-2xl font-bold text-gray-900"} "User Examples"]
       (if (:user req)
         [:a {:class "bg-blue-600 text-white px-4 py-2 rounded-lg font-medium hover:bg-blue-700"
              :href (str "/examples/new?doc-section=" chapter)} "+ Add Example"]
         [:a {:class "text-blue-600 font-medium hover:underline" :href "/auth/login"} "Login to add examples"])]
      (if (seq examples)
        [:div {:class "space-y-4"} (mapv render-example examples)]
        [:div {:class "text-center py-12 bg-white rounded-xl border border-dashed border-gray-300"}
         [:p {:class "text-gray-500 mb-2"} "No examples yet. Be the first to add one!"]
         (if (:user req)
           [:a {:class "text-blue-600 font-medium hover:underline" :href (str "/examples/new?doc-section=" chapter)} "Add an example"]
           [:a {:class "text-blue-600 font-medium hover:underline" :href "/auth/login"} "Login to add an example"])])]
     [:nav {:class "flex justify-between mt-12 pt-6 border-t border-gray-200"}
      [:a {:class "text-gray-600 hover:text-blue-600" :href "/docs"} "← Table of Contents"]]]))
