(ns frontend.components.journal
  (:require [rum.core :as rum]
            [frontend.util :as util :refer-macros [profile]]
            [frontend.date :as date]
            [frontend.handler :as handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.repo :as repo-handler]
            [frontend.handler.editor :as editor]
            [frontend.handler.ui :as ui-handler]
            [frontend.db :as db]
            [frontend.state :as state]
            [clojure.string :as string]
            [frontend.ui :as ui]
            [frontend.format :as format]
            [frontend.components.content :as content]
            [frontend.components.hiccup :as hiccup]
            [frontend.components.reference :as reference]
            [frontend.components.page :as page]
            [frontend.components.onboarding :as onboarding]
            [frontend.utf8 :as utf8]
            [goog.object :as gobj]
            [clojure.string :as string]))

(rum/defc blocks-inner < rum/static
  {:did-mount (fn [state]
                (let [[blocks _ page] (:rum/args state)
                      first-title (second (first (:block/title (first blocks))))
                      journal? (and (string? first-title)
                                    (date/valid-journal-title? first-title))]
                  (when (and journal?
                             (= (string/lower-case first-title) (string/lower-case page)))
                    (notification/show!
                     [:div
                      [:p
                       (util/format
                        "It seems that you have multiple journals for the same day \"%s\"."
                        first-title)]
                      (ui/button "Go to files"
                        :href "/all-files"
                        :on-click notification/clear!)]
                     :error
                     false)))
                state)}
  [blocks encoded-page-name page]
  (let [start-level (or (:block/level (first blocks)) 1 )]
    (content/content
     encoded-page-name
     {:hiccup (hiccup/->hiccup blocks
                               {:id encoded-page-name
                                :start-level 2}
                               {})})))

(rum/defc blocks-cp < rum/reactive
  {}
  [repo page encoded-page-name format]
  (let [raw-blocks (db/get-page-blocks repo page)
        blocks (->>
                (db/with-dummy-block raw-blocks format nil true)
                (db/with-block-refs-count repo))]
    (blocks-inner blocks encoded-page-name page)))

(rum/defc journal-cp < rum/reactive
  [[title format]]
  (let [;; Don't edit the journal title
        page (string/lower-case title)
        repo (state/sub :git/current-repo)
        encoded-page-name (util/encode-str page)
        today? (= (string/lower-case title)
                  (string/lower-case (date/journal-name)))]
    [:div.flex-1.journal.page
     (ui/foldable
      [:a.initial-color.title
       {:href (str "/page/" encoded-page-name)
        :on-click (fn [e]
                    (util/stop e)
                    (when (gobj/get e "shiftKey")
                      (when-let [page (db/pull [:page/name title])]
                        (state/sidebar-add-block!
                         (state/get-current-repo)
                         (:db/id page)
                         :page
                         {:page page
                          :journal? true}))))}
       [:h1.title
        (util/capitalize-all title)]]

      (blocks-cp repo page encoded-page-name format))

     (page/today-queries repo today? false)

     (reference/references title false)

     (when (and (not (state/logged?))
                today?)
       (onboarding/intro))]))

(rum/defc journals <
  {:did-mount (fn [state]
                (editor/open-last-block! true)
                state)}
  [latest-journals]
  [:div#journals
   (ui/infinite-list
    (for [[journal-name format] latest-journals]
      [:div.journal.content {:key journal-name}
       (journal-cp [journal-name format])])
    {:on-load (fn []
                (handler/load-more-journals!))})])

(rum/defc all-journals < rum/reactive
  []
  (let [journals-length (state/sub :journals-length)
         latest-journals (db/get-latest-journals (state/get-current-repo) journals-length)]
     (journals latest-journals)))