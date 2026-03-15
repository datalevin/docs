(ns datalevin.docs.views.auth
  (:require [datalevin.docs.views.layout :as layout]
            [ring.middleware.anti-forgery :as anti-forgery]))

(def ^:private glass-card-style
  "rounded-xl p-8")

(def ^:private glass-card-css
  "background:var(--bg-card, rgba(255,255,255,0.06)); backdrop-filter:blur(20px); -webkit-backdrop-filter:blur(20px); border:1px solid var(--border-card, rgba(255,255,255,0.12)); box-shadow:0 8px 32px rgba(0,0,0,0.3);")

(def ^:private input-class
  "w-full px-4 py-2 rounded-lg outline-none")

(def ^:private input-css
  "background:var(--input-bg, rgba(255,255,255,0.05)); border:1px solid var(--input-border, rgba(255,255,255,0.1)); color:var(--text-primary, #e5e7eb);")

(def ^:private submit-class
  "w-full text-white py-2.5 rounded-lg font-medium transition")

(def ^:private submit-css
  "background:linear-gradient(135deg,#06b6d4,#3b82f6);")

(def ^:private error-style
  "background:rgba(220,38,38,0.15); border:1px solid rgba(220,38,38,0.3); color:#fca5a5;")

(def ^:private success-style
  "background:rgba(34,197,94,0.15); border:1px solid rgba(34,197,94,0.3); color:#86efac;")

(defn- message-banner
  [style message]
  (when message
    [:p {:class "px-4 py-3 rounded-lg mb-4 text-sm"
         :style style}
     message]))

(defn- input-field
  [label attrs]
  (let [wrapper-class (or (:wrapper-class attrs) "mb-4")
        input-attrs (-> attrs
                        (dissoc :wrapper-class)
                        (assoc :class input-class
                               :style input-css))]
    [:div {:class wrapper-class}
     [:label {:class "block text-sm font-medium text-gray-300 mb-1"} label]
     [:input input-attrs]]))

(defn- submit-button
  [label]
  [:button {:type "submit"
            :class submit-class
            :style submit-css}
   label])

(defn- auth-shell
  [page-title seo-title heading body]
  (layout/base page-title
               seo-title
               [:div {:class "max-w-md mx-auto py-16 px-4"}
                (into [:div {:class (str "auth-card " glass-card-style)
                             :style glass-card-css}
                       [:h2 {:class "text-2xl font-bold text-white mb-6 text-center"}
                        heading]]
                      (keep identity body))]))

(defn login-page
  [req]
  (let [token (force anti-forgery/*anti-forgery-token*)
        error-msg (get-in req [:session :flash :error])
        github-configured? (seq (:github-client-id req))]
    (auth-shell "Login"
                {:description "Log in to Datalevin Docs."
                 :robots "noindex,nofollow"}
                "Welcome back"
                [(message-banner error-style error-msg)
                 (when github-configured?
                   [:div {:class "mb-6"}
                    [:a {:href "/auth/github"
                         :hx-boost "false"
                         :class "auth-github flex items-center justify-center gap-2 text-white px-4 py-2.5 rounded-lg font-medium transition"
                         :style "background:rgba(255,255,255,0.1); border:1px solid rgba(255,255,255,0.15);"}
                     [:svg {:xmlns "http://www.w3.org/2000/svg"
                            :viewBox "0 0 16 16"
                            :fill "currentColor"
                            :width "20"
                            :height "20"}
                      [:path {:d "M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"}]]
                     "Sign in with GitHub"]
                    [:div {:class "auth-divider mt-6 pb-2 text-center"
                           :style "border-bottom:1px solid rgba(255,255,255,0.1);"}
                     [:span {:class "text-sm text-gray-500"} "or sign in with email"]]])
                 [:form {:method "post"
                         :action "/auth/login"}
                  [:input {:type "hidden"
                           :name "__anti-forgery-token"
                           :value token}]
                  (input-field "Email"
                               {:type "email"
                                :name "email"
                                :required true
                                :autocomplete "email"})
                  (input-field "Password"
                               {:type "password"
                                :name "password"
                                :required true
                                :autocomplete "current-password"
                                :wrapper-class "mb-6"})
                  (submit-button "Log in")]
                 [:div {:class "mt-4 text-center"}
                  [:a {:href "/auth/forgot-password"
                       :class "auth-link text-sm text-cyan-400 hover:text-cyan-300"}
                   "Forgot password?"]]
                 [:p {:class "auth-text mt-6 text-center text-sm text-gray-400"}
                  "Don't have an account? "
                  [:a {:href "/auth/register"
                       :class "auth-link text-cyan-400 hover:text-cyan-300 font-medium"}
                   "Sign up"]]])))

(defn register-page
  [req]
  (let [token (force anti-forgery/*anti-forgery-token*)
        error-msg (get-in req [:session :flash :error])]
    (auth-shell "Sign Up"
                {:description "Create a Datalevin Docs account."
                 :robots "noindex,nofollow"}
                "Create your account"
                [(message-banner error-style error-msg)
                 [:form {:method "post"
                         :action "/auth/register"}
                  [:input {:type "hidden"
                           :name "__anti-forgery-token"
                           :value token}]
                  (input-field "Email"
                               {:type "email"
                                :name "email"
                                :required true
                                :autocomplete "email"})
                  (input-field "Username"
                               {:type "text"
                                :name "username"
                                :required true
                                :autocomplete "username"})
                  (input-field "Password"
                               {:type "password"
                                :name "password"
                                :minLength 8
                                :required true
                                :autocomplete "new-password"})
                  (input-field "Confirm Password"
                               {:type "password"
                                :name "confirm-password"
                                :required true
                                :autocomplete "new-password"
                                :wrapper-class "mb-6"})
                  (submit-button "Create account")]
                 [:p {:class "auth-text mt-6 text-center text-sm text-gray-400"}
                  "Already have an account? "
                  [:a {:href "/auth/login"
                       :class "auth-link text-cyan-400 hover:text-cyan-300 font-medium"}
                   "Log in"]]])))

(defn forgot-password-page
  [req]
  (let [token (force anti-forgery/*anti-forgery-token*)
        flash (get req :session)
        error-msg (get-in flash [:flash :error])
        success-msg (get-in flash [:flash :success])]
    (auth-shell "Forgot Password"
                {:description "Request a Datalevin Docs password reset."
                 :robots "noindex,nofollow"}
                "Reset your password"
                [(message-banner error-style error-msg)
                 (message-banner success-style success-msg)
                 [:form {:method "post"
                         :action "/auth/forgot-password"}
                  [:input {:type "hidden"
                           :name "__anti-forgery-token"
                           :value token}]
                  (input-field "Email"
                               {:type "email"
                                :name "email"
                                :required true
                                :wrapper-class "mb-6"})
                  (submit-button "Send reset link")]
                 [:p {:class "auth-text mt-6 text-center text-sm text-gray-400"}
                  [:a {:href "/auth/login"
                       :class "auth-link text-cyan-400 hover:text-cyan-300"}
                   "Back to login"]]])))

(defn reset-password-page
  [req]
  (let [csrf-token (force anti-forgery/*anti-forgery-token*)
        token-param (get-in req [:params "token"] "")
        error-msg (get-in req [:session :flash :error])]
    (auth-shell "Reset Password"
                {:description "Reset your Datalevin Docs password."
                 :robots "noindex,nofollow"}
                "Set new password"
                [(message-banner error-style error-msg)
                 [:form {:method "post"
                         :action "/auth/reset-password"}
                  [:input {:type "hidden"
                           :name "__anti-forgery-token"
                           :value csrf-token}]
                  [:input {:type "hidden"
                           :name "token"
                           :value token-param}]
                  (input-field "New Password"
                               {:type "password"
                                :name "password"
                                :minLength 8
                                :required true})
                  (input-field "Confirm Password"
                               {:type "password"
                                :name "confirm-password"
                                :required true
                                :wrapper-class "mb-6"})
                  (submit-button "Reset password")]])))
