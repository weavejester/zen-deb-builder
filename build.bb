#!/usr/bin/env bb
(ns build
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(def github-url "https://api.github.com")

(def github-headers
  {"Accept" "application/vnd.github+json"
   "X-GitHub-Api-Version" "2022-11-28"})

(defn github-get [path]
  (-> (http/get (str github-url path) {:headers github-headers})
      :body
      (json/parse-string true)))

(defn get-latest-assets [repo]
  (github-get (str "/repos/" repo "/releases/latest")))

(defn re-find-assets [re assets]
  (first (filter #(re-find re (:name %)) assets)))

(def re-linux-asset #"linux.*86[_-]?64.*\.tar\.xz")

(defn latest-zen-asset []
  (let [{:keys [tag_name assets]}
        (get-latest-assets "zen-browser/desktop")
        {:keys [name browser_download_url]}
        (re-find-assets re-linux-asset assets)]
    {:filename name
     :version  tag_name
     :url      browser_download_url}))

(defn download-asset [{:keys [url filename]}]
  (io/copy (:body (http/get url {:as :stream}))
           (io/file filename)))

(prn (doto (latest-zen-asset) download-asset))
