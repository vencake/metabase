(ns metabase.api.persist
  (:require [clojure.tools.logging :as log]
            [clojurewerkz.quartzite.conversion :as qc]
            [medley.core :as m]
            [metabase.api.common :as api]
            [metabase.driver.ddl.concurrent :as ddl.concurrent]
            [metabase.driver.ddl.interface :as ddl.i]
            [metabase.models.card :refer [Card]]
            [metabase.models.database :refer [Database]]
            [metabase.models.persisted-info :as persisted-info :refer [PersistedInfo]]
            [metabase.public-settings :as public-settings]
            [metabase.task :as task]
            [metabase.task.persist-refresh :as task.persist-refresh]
            [metabase.util :as u]
            [metabase.util.i18n :refer [deferred-tru tru]]
            [metabase.util.schema :as su]
            [schema.core :as s]
            [toucan.db :as db]))

(defn- fetch-persisted-info
  "Returns a list of persisted info, annotated with database_name, card_name, and schema_name."
  []
  (let [instance-id-str  (public-settings/site-uuid)
        db-id->fire-time (some->> task.persist-refresh/persistence-job-key
                                  task/job-info
                                  :triggers
                                  (u/key-by (comp #(get % "db-id") qc/from-job-data :data))
                                  (m/map-vals :next-fire-time))]
    (->> (db/query {:select    [:p.id :p.database_id :p.columns :p.card_id
                                :p.active :p.state :p.error
                                :p.refresh_begin :p.refresh_end
                                :p.table_name
                                [:db.name :database_name] [:c.name :card_name]]
                    :from      [[PersistedInfo :p]]
                    :left-join [[Database :db] [:= :db.id :p.database_id]
                                [Card :c] [:= :c.id :p.card_id]]
                    :order-by  [[:p.refresh_begin :asc]]})
         (db/do-post-select PersistedInfo)
         (map (fn [{:keys [database_id] :as pi}]
                (assoc pi
                       :schema_name (ddl.i/schema-name {:id database_id} instance-id-str)
                       :next-fire-time (get db-id->fire-time database_id)))))))

(api/defendpoint GET "/"
  "List the entries of [[PersistedInfo]] in order to show a status page."
  []
  (api/check-superuser)
  (fetch-persisted-info))

(def ^:private HoursInterval
  "Schema representing valid interval hours for refreshing persisted models."
  (su/with-api-error-message
    (s/constrained s/Int #(<= 1 % 24)
                   (deferred-tru "Integer greater than or equal to one and less than or equal to twenty-four"))
    (deferred-tru "Value must be an integer representing hours greater than or equal to one and less than or equal to twenty-four")))

(api/defendpoint POST "/set-interval"
  "Set the interval (in hours) to refresh persisted models. Shape should be JSON like {hours: 4}."
  [:as {{:keys [hours], :as _body} :body}]
  {hours HoursInterval}
  (api/check-superuser)
  (public-settings/persisted-model-refresh-interval-hours hours)
  (task.persist-refresh/reschedule-refresh)
  api/generic-204-no-content)

(api/defendpoint POST "/enable"
  "Enable global setting to allow databases to persist models."
  []
  (api/check-superuser)
  (log/info (tru "Enabling model persistence"))
  (public-settings/enabled-persisted-models true)
  api/generic-204-no-content)

(defn- disable-persisting
  "Disables persistence.
  - update all [[PersistedInfo]] rows to be inactive and deleteable
  - remove `:persist-models-enabled` from relevant [[Database]] options
  - schedule a task to [[metabase.driver.ddl.interface/unpersist]] each table"
  []
  (let [id->db      (u/key-by :id (Database))
        enabled-dbs (filter (comp :persist-models-enabled :options) (vals id->db))]
    (log/info (tru "Disabling model persistence"))
    (doseq [db enabled-dbs]
      (db/update! Database (u/the-id db)
                  :options (not-empty (dissoc (:options db) :persist-models-enabled))))
    (db/update-where! PersistedInfo {}
                      :active false, :state "deleteable")
    (task.persist-refresh/unschedule-all-triggers)
    (ddl.concurrent/submit-task
     (fn []
       (let [to-unpersist (db/select PersistedInfo :state "deleteable")]
         (log/info (tru "Unpersisting all persisted models"))
         (doseq [unpersist to-unpersist
                 :let [database (id->db (:database_id unpersist))]]
           (try (ddl.i/unpersist! (:engine database) database unpersist)
                (catch Exception e
                  (log/info e
                            (tru "Error unpersisting model with card-id {0}"
                                 (:card_id unpersist)))))))))))

(api/defendpoint POST "/disable"
  "Disable global setting to allow databases to persist models. This will remove all tasks to refresh tables, remove
  that option from databases which might have it enabled, and delete all cached tables."
  []
  (api/check-superuser)
  (when (public-settings/enabled-persisted-models)
    (try (public-settings/enabled-persisted-models false)
         (disable-persisting)
         (catch Exception e
           ;; re-enable so can continue to attempt to clean up
           (public-settings/enabled-persisted-models true)
           (throw e))))
  api/generic-204-no-content)

(api/define-routes)