(ns recommendation-application.views.layout
  (:require [hiccup.page :refer [html5 include-css]]))

(defn common [& body]
  (html5
    [:head
     [:title "Recommendation application"]
     (include-css "/css/login.css")]
    [:body body]))
