(ns electron.window
  (:require ["electron-window-state" :as windowStateKeeper]
            [electron.utils :refer [*win mac? win32? linux? prod? dev? logger open]]
            [electron.configs :as cfgs]
            ["electron" :refer [BrowserWindow app protocol ipcMain dialog Menu MenuItem session] :as electron]
            ["path" :as path]
            [electron.state :as state]
            [clojure.core.async :as async]))

(def MAIN_WINDOW_ENTRY (if dev?
                         ;;"http://localhost:3001"
                         (str "file://" (path/join js/__dirname "index.html"))
                         (str "file://" (path/join js/__dirname "electron.html"))))

(defn create-main-window
  ([]
   (create-main-window MAIN_WINDOW_ENTRY))
  ([url]
   (let [win-state (windowStateKeeper (clj->js {:defaultWidth 980 :defaultHeight 700}))
         win-opts (cond->
                    {:width                (.-width win-state)
                     :height               (.-height win-state)
                     :frame                true
                     :titleBarStyle        "hiddenInset"
                     :trafficLightPosition {:x 16 :y 16}
                     :autoHideMenuBar      (not mac?)
                     :webPreferences
                     {:plugins                 true ; pdf
                      :nodeIntegration         false
                      :nodeIntegrationInWorker false
                      :webSecurity             (not dev?)
                      :contextIsolation        true
                      :spellcheck              ((fnil identity true) (cfgs/get-item :spell-check))
                      ;; Remove OverlayScrollbars and transition `.scrollbar-spacing`
                      ;; to use `scollbar-gutter` after the feature is implemented in browsers.
                      :enableBlinkFeatures     'OverlayScrollbars'
                      :preload                 (path/join js/__dirname "js/preload.js")}}
                    linux?
                    (assoc :icon (path/join js/__dirname "icons/logseq.png")))
         win (BrowserWindow. (clj->js win-opts))]
     (.manage win-state win)
     (.onBeforeSendHeaders (.. session -defaultSession -webRequest)
                           (clj->js {:urls (array "*://*.youtube.com/*")})
                           (fn [^js details callback]
                             (let [url (.-url details)
                                   urlObj (js/URL. url)
                                   origin (.-origin urlObj)
                                   requestHeaders (.-requestHeaders details)]
                               (if (and
                                    (.hasOwnProperty requestHeaders "referer")
                                    (not-empty (.-referer requestHeaders)))
                                 (callback #js {:cancel false
                                                :requestHeaders requestHeaders})
                                 (do
                                   (set! (.-referer requestHeaders) origin)
                                   (callback #js {:cancel false
                                                  :requestHeaders requestHeaders}))))))
     (.loadURL win url)
     ;;(when dev? (.. win -webContents (openDevTools)))
     win)))

(defn destroy-window!
  [^js win]
  (.destroy win))

(defn on-close-save!
  [^js win]
  (.on win "close" (fn [e]
                     (.preventDefault e)
                     (let [web-contents (. win -webContents)]
                       (.send web-contents "persistent-dbs"))
                     (async/go
                       (let [_ (async/<! state/persistent-dbs-chan)]
                         (destroy-window! win))))))
