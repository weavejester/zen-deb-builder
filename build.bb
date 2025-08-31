#!/usr/bin/env bb
(ns build
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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

(defn get-latest-zen-asset []
  (let [{:keys [tag_name assets]} (get-latest-assets "zen-browser/desktop")
        {:keys [browser_download_url]} (re-find-assets re-linux-asset assets)]
    {:version  tag_name
     :url      browser_download_url}))

(def tarball-filename "zen.linux-x86_64.tar.xz")
(def version-filename ".version")

(defn store-version [{:keys [version]}]
  (spit (io/file version-filename) version))

(defn download-asset [{:keys [url]}]
  (io/copy (:body (http/get url {:as :stream}))
           (io/file tarball-filename)))

(defn deb-archicture []
  (str/trim (:out (p/shell {:out :string} "dpkg" "--print-architecture"))))

(def build-dir "target")

(def build-subdirs
  ["DEBIAN"
   "opt/zen"
   "usr/bin"
   "usr/share/applications"
   "usr/share/icons/hicolor/16x16/apps"
   "usr/share/icons/hicolor/32x32/apps"
   "usr/share/icons/hicolor/64x64/apps"
   "usr/share/icons/hicolor/128x128/apps"])

(defn create-build-dirs []
  (doseq [dir build-subdirs]
    (fs/create-dirs (fs/path build-dir dir))))

(defn debian-control-file [version]
  (format "Package: zen-browser
Version: %s
Section: web
Priority: optional
Architecture: %s
Maintainer: James Reeves <jreeves@weavejester.com>
Homepage: https://zen-browser.app
Description: Zen Web Browser (repackaged tarball with desktop entry)
" version (deb-archicture)))

(def desktop-file
  "[Desktop Entry]
Name=Zen Browser
Exec=/opt/zen/zen %u
Icon=zen-browser
Type=Application
MimeType=text/html;text/xml;application/xhtml+xml;x-scheme-handler/http;x-scheme-handler/https;application/x-xpinstall;application/pdf;application/json;
StartupWMClass=zen
Categories=Network;WebBrowser;
StartupNotify=true
Terminal=false
X-MultipleArgs=false
Keywords=Internet;WWW;Browser;Web;Explorer;
Actions=new-window;new-private-window;profilemanager;

[Desktop Action new-window]
Name=Open a New Window
Exec=/opt/zen/zen %u

[Desktop Action new-private-window]
Name=Open a New Private Window
Exec=/opt/zen/zen --private-window %u

[Desktop Action profilemanager]
Name=Open the Profile Manager
Exec=/opt/zen/zen --ProfileManager %u
")
