(ns recommendation-application.get-data
  (:use [recommendation-application.models.database :only [save-game empty-db get-game-by-name get-all get-all-games get-by-score drop-all-data]])
  (:require [compojure.core :refer :all]
            [hiccup.form :refer [form-to label text-field radio-button password-field submit-button email-field]]
            [hickory.core :as hickory]
            [hiccup.element :refer :all]
            [hickory.select :as s]
            [clojure.string :as string]
            [clj-http.client :as client])
  (:import (java.io InputStream InputStreamReader BufferedReader)
           (java.net URL HttpURLConnection)))

(defn get-page [link]
  "Parse every page with links into map"
  (->
    (client/get link)
    :body hickory/parse hickory/as-hickory))

(defn hickory-parser 
  "For given link and class, get map"
  [link class]
  (->(s/select 
       (s/child 
         (s/class class))
       (get-page link))))  

(defn hickory-parser-desc
  "For given link and class, get map"
  [link sub-class class]
  (->(s/select 
       (s/descendant
         (s/class sub-class)
         (s/class class))
       (get-page link))))  

(def site-tree
  "Parse every page with links into map"
  (for [i (range 10)]
    (str "http://www.metacritic.com/browse/games/release-date/available/pc/metascore?view=condensed&page=" i)))

(def get-link-for-every-game 
  "Get every link from every page"
  (atom
    (pmap (fn [link]
            (let [content (hickory-parser-desc link "body_wrap" "product_title")]
              (map #(str "http://www.metacritic.com" %)    
                   (map :href
                        (map :attrs
                             (map #(get % 1)
                                  (drop-last 6
                                             (map :content content))))))))
          site-tree)))


(defn get-critics-reviews-link 
  [link]
  (let [content (hickory-parser-desc link "nav_critic_reviews" "action")]
    (str "http://www.metacritics.com"
         (first (map :href
                     (map :attrs content))))))

(defn flat [content]
  (flatten (map :content content)))

(defn get-pub-date 
  "Get date when the game is published."
  [link]
  (let [content (hickory-parser-desc link "release_data" "data")]
    (first (flat content))))

(defn get-pub 
  "Game developer."
  [link]
  (let [content (hickory-parser-desc link "developer" "data")]
    (first (flat content))))

(defn get-genre 
  "Game genre."
  [link]
  (let [content (hickory-parser-desc link "product_genre" "data")]
    (first (flat content))))

(defn get-esrb 
  "Ratings for children."
  [link]
  (let [content (hickory-parser-desc link "product_rating" "data")]
    (first (flat content))))

(defn get-all-critics-data 
  "Get all informations about critics"
  [link]
  (let [critic-name (hickory-parser-desc link "main_col" "source")
        critic-score (hickory-parser-desc link "main_col" "indiv")
        critic-body (hickory-parser-desc link "main_col" "review_body")
        critic-date (hickory-parser-desc link "main_col" "date")]   
    (assoc {}
           :name (flat (flat critic-name))
           :score (flat critic-score)
           :body (flat critic-body)
           :date (flat critic-date))))

(defn prepare-critics
  "Get map with critics, and prepare them for saving"
  [map]
  (loop [acc []
         score (:score map)
         name (:name map)
         body (:body map)
         date (:date map)]
    (if (empty? score)
      acc
      (recur (conj acc
                   (assoc {} 
                          :name (first name) 
                          :score (int (/ (read-string (first score)) 20))
                          :body (first body) 
                          :date (first date))) 
             (rest score) (rest name) (rest body) (rest date)))))

(defn get-game-score 
  "Game score."
  [link]
  (let [content (hickory-parser-desc link "metascore_anchor" "xlarge")]
    (read-string
      (string/replace
        (format "%.1f"
                (with-precision 1 
                  (/ (first (map read-string
                                 (get 
                                   (second 
                                     (first 
                                       (map :content content))) :content))) 20.0))) "," "."))))

(defn get-game-name 
  "Name of the game."
  [link]
  (let [content
        (s/select 
          (s/child 
            (s/tag :head)
            (s/tag :title))
          (get-page link))]
    (string/replace (string/replace (first 
                      (first (map :content content))) " for PC Reviews - Metacritic" "") "," "")))

(defn get-picture-link 
  "Game thumbnail."
  [link]
  (let [content (hickory-parser link "large_image")]
    (get (get (second 
                (get (first content) :content)) :attrs) :src)))

(defn get-summary-details 
  "Get details about game."
  [link]
  (let [content (hickory-parser-desc link "product_summary" "blurb_collapsed")
        content1 (hickory-parser-desc link "product_summary" "blurb_expanded")
        content2 (hickory-parser-desc link "product_summary" "data")
        data (first (flat content))
        data1 (first (flat content1))
        data2 (first (get (second (flat content2)) :content))]
    (if (nil? (and data  data1))
      (str data2)
      (str data data1))))

(defn get-game 
  "Retreive game and prepare it for saving"
  [link]
  (let [game  (assoc {} 
                     :name (get-game-name link)
                     :score (get-game-score link)
                     :picture (get-picture-link link)
                     :about (get-summary-details link)
                     :publisher (get-pub link)
                     :genre (get-genre link)
                     :rating (get-esrb link)
                     :release-date (get-pub-date link)
                     :critics (prepare-critics (get-all-critics-data (get-critics-reviews-link link))))]  
    (when-not (string? (some #{(:name game)} (map :name (get-all-games))))
      (save-game game))))

(defn retreive-data
  []
  (for [x (doall @get-link-for-every-game)]
    (dorun (map
             #(let [agent (agent %)]
                (send agent get-game)) x))))

(defn game-critics []
  "Return all critics names along with their rates."
  (apply merge {}
         (for [game (get-all-games)]
           (assoc {} (:name game) 
                  (into {} (for [critic (:critics game)]
                             (assoc {} (:name critic) (:score critic))))))))

(defn shared-critics [data first-game second-game]
  "Find any matching critics for two games."
  (let [first-critic (data first-game)
        second-critic (data second-game)]
    (apply merge {}
           (for [k (keys first-critic)
                 :when (contains? second-critic k)]
             (assoc {} k [(first-critic k) (second-critic k)])))))
