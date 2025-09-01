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

(defn download-latest-tarball []
  (let [asset (get-latest-zen-asset)]
    (store-version asset)
    (download-asset asset)))

(defn deb-archicture []
  (str/trim (:out (p/shell {:out :string} "dpkg" "--print-architecture"))))

(def build-dir "target")

(def build-subdirs
  ["DEBIAN"
   "etc/apparmor.d"
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
Exec=/opt/zen/zen --new-window %u

[Desktop Action new-private-window]
Name=Open a New Private Window
Exec=/opt/zen/zen --private-window %u

[Desktop Action profilemanager]
Name=Open the Profile Manager
Exec=/opt/zen/zen --ProfileManager %u
")

(def apparmor-file
  "# This profile allows everything and only exists to give the
# application a name instead of having the label \"unconfined\"

abi <abi/4.0>,
include <tunables/global>

profile zen-browser /opt/zen/{zen,zen-bin,updater} flags=(unconfined) {
  userns,

  # Site-specific additions and overrides. See local/README for details.
  include if exists <local/zen-browser>
}
")

(def debian-postinst-file
  "#!/bin/sh
set -e
if command -v apparmor_parser >/dev/null 2>&1; then
  apparmor_parser -r -W -T /etc/apparmor.d/zen-browser || true
fi
exit 0")

(def debian-postrm-file
  "#!/bin/sh
set -e
if [ \"$1\" = \"purge\" ] && command -v apparmor_parser >/dev/null 2>&1; then
  apparmor_parser -R /etc/apparmor.d/zen-browser || true
fi
exit 0")

(defn unzip-tarball [src dest]
  (p/shell "tar" "-C" dest "-x" "--xz" "-f" src))

(def zen-icons-path
  (fs/path build-dir "opt/zen/browser/chrome/icons/default"))

(def usr-icons-path
  (fs/path build-dir "usr/share/icons/hicolor"))

(defn copy-browser-icons []
  (doseq [res [16 32 64 128]]
    (fs/copy
     (fs/path zen-icons-path (str "default" res ".png"))
     (fs/path usr-icons-path (str res "x" res) "apps/zen-browser.png"))))

(defn create-binary-link []
  (fs/create-sym-link (fs/path build-dir "usr/bin/zen")
                      "../../opt/zen/zen"))

(defn create-desktop-file []
  (spit (io/file build-dir "usr/share/applications/zen-browser.desktop")
        desktop-file))

(defn create-apparmor-file []
  (spit (io/file build-dir "etc/apparmor.d/zen-browser")
        apparmor-file))

(defn create-debian-files []
  (let [version (slurp ".version")]
    (spit (io/file build-dir "DEBIAN/control") (debian-control-file version))
    (spit (io/file build-dir "DEBIAN/postinst") debian-postinst-file)
    (spit (io/file build-dir "DEBIAN/postrm") debian-postrm-file)
    (fs/set-posix-file-permissions (fs/path build-dir "DEBIAN/postinst")
                                   "rwxr-xr-x")
    (fs/set-posix-file-permissions (fs/path build-dir "DEBIAN/postrm")
                                   "rwxr-xr-x")))

(defn build-deb-file []
  (let [version (slurp ".version")
        arch    (deb-archicture)]
    (p/shell "dpkg-deb" "--build" "--root-owner-group" build-dir
             (format "zen-browser_%s_%s.deb" version arch))))

(defn delete-build-files []
  (fs/delete-tree build-dir)
  (fs/delete-if-exists version-filename)
  (fs/delete-if-exists tarball-filename)
  (run! fs/delete-if-exists (fs/glob "." "zen-browser_*.deb")))

;(create-build-dirs)
;(unzip-tarball tarball-filename "target/opt")

;(copy-browser-icons)
;(create-binary-link)
;(create-desktop-file)

;(create-debian-files)
;(create-apparmor-file)
;(build-deb-file)
