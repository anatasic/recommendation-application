(ns recommendation-application.routes.home
  (:require [compojure.core :refer :all]
            [recommendation-application.views.layout :as layout]))

(defn home []
  (layout/common [:h1 "Hello Wossrsdahgld!"]))

(defroutes home-routes
  (GET "/" [] (home)))