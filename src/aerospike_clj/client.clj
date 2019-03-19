(ns aerospike-clj.client
  (:refer-clojure :exclude [update])
  (:require [aerospike-clj.policy :as policy]
            [aerospike-clj.utils :as utils]
            [aerospike-clj.metrics :as metrics]
            [manifold.deferred :as d])
  (:import [com.aerospike.client AerospikeClient Host Key Bin Record AerospikeException Operation]
           [com.aerospike.client.async EventLoop NioEventLoops]
           [com.aerospike.client.listener RecordListener WriteListener DeleteListener ExistsListener]
           [com.aerospike.client.policy Policy ClientPolicy RecordExistsAction]
           (clojure.lang IPersistentMap IPersistentVector)))

(def EPOCH
  ^{:doc "The 0 date reference for returned record TTL"}
  (.getEpochSecond (java.time.Instant/parse "2010-01-01T00:00:00Z")))

(def MAX_KEY_LENGTH (dec (bit-shift-left 1 13)))

(def MAX_BIN_NAME_LENGTH 14)

(defprotocol IAerospikeClient
  (get-client [ac] [ac index] "Returns the relevant AerospikeClient object for the specific shard")
  (get-all-clients [_] "Returns a sequence of all AerospikeClient objects."))

(defrecord SimpleAerospikeClient [^AerospikeClient ac
                                  ^NioEventLoops el
                                  ^String dbns
                                  ^String cluster-name
                                  client-events]
  IAerospikeClient
  (get-client ^AerospikeClient [_ _] ac)
  (get-client ^AerospikeClient [_] ac)
  (get-all-clients [_] [ac]))

(defn create-client
  "Returns the Java `AerospikeClient` instance. To build the Clojure `IAerospikeClient` one,
  use `init-simple-aerospike-client`."
  [hosts client-policy]
  (let [hosts-arr (into-array Host (for [h hosts]
                                     ^Host (Host. h 3000)))]
    (AerospikeClient. ^ClientPolicy client-policy ^"[Lcom.aerospike.client.Host;" hosts-arr)))

(defn create-event-loops
  "Called internally to create the event loops of for the client.
  Can also be used to share event loops between several clients."
  [conf]
  (let [elp (policy/map->event-policy conf)]
    (NioEventLoops. elp 1)))

(defn init-simple-aerospike-client
  "hosts should be a seq of known hosts to bootstrap from."
  ([hosts aero-ns]
   (init-simple-aerospike-client hosts aero-ns {}))
  ([hosts aero-ns conf]
   (let [cluster-name (utils/cluster-name hosts)
         event-loops (:event-loops conf (create-event-loops conf))
         client-policy (:client-policy conf (policy/create-client-policy event-loops conf))]
     (println (format ";; Starting aerospike clients for clusters %s with username %s" cluster-name (get conf "username")))
     (map->SimpleAerospikeClient {:ac (create-client hosts client-policy)
                                  :el event-loops
                                  :dbns aero-ns
                                  :cluster-name cluster-name
                                  :client-events (:client-events conf)}))))

(defn stop-aerospike-client
  "gracefully stop a client, waiting until all async operations finish."
  [db]
  (println ";; Stopping aerospike clients")
  (doseq [^AerospikeClient client (get-all-clients db)]
    (.close client))
  (.close (:el db)))

;; listeners
(defprotocol ClientEvents
  "Continuation functions that are registered when an async DB operation is called.
  The DB passed is an `IAerospikeClient` instance.
  The value returned from those function will be the value of the returned deferred from the async operation."
  (on-success [_ op-name op-result index op-start-time db]
              "A continuation function. Registered on the operation future and called when operations succeeds.")
  (on-failure [_ op-name op-ex     index op-start-time db]
              "A continuation function. Registered on the operation future and called when operations fails."))

(defn- register-events [op-future db op-name index op-start-time]
  (if-let [client-events (:client-events db)]
    (-> op-future
        (d/chain' (fn [op-result]
                    (on-success client-events op-name op-result    index op-start-time db)))
        (d/catch' (fn [op-exception]
                    (on-failure client-events op-name op-exception index op-start-time db))))
    op-future))

(defn- ^ExistsListener reify-exists-listener [op-future]
  (reify ExistsListener
    (^void onFailure [this ^AerospikeException ex]
      (d/error! op-future ex))
    (^void onSuccess [this ^Key k ^boolean exists]
      (d/success! op-future exists))))

(defn- ^DeleteListener reify-delete-listener [op-future]
  (reify
    DeleteListener
    (^void onSuccess [this ^Key k ^boolean existed]
      (d/success! op-future existed))
    (^void onFailure [this ^AerospikeException ex]
      (d/error! op-future ex))))

(defn- ^WriteListener reify-write-listener [op-future]
  (reify
    WriteListener
    (^void onSuccess [this ^Key _]
      (d/success! op-future true))
    (^void onFailure [this ^AerospikeException ex]
      (d/error! op-future ex))))

(defn- ^RecordListener reify-record-listener [op-future]
  (reify RecordListener
    (^void onFailure [this ^AerospikeException ex]
      (d/error! op-future ex))
    (^void onSuccess [this ^Key k ^Record record]
      (d/success! op-future record))))

(defn- ^Key create-key [^String aero-namespace ^String set-name ^String k]
  (when (< MAX_KEY_LENGTH (.length k))
    (throw (Exception. (format "key is too long: %s..." (subs k 0 40)))))
  (Key. aero-namespace set-name k))

(defn- ^Bin create-bin [^String bin-name bin-value]
  (when (< MAX_BIN_NAME_LENGTH (.length bin-name))
    (throw (Exception. (format "%s is %s characters. Bin names have to be <= 14 characters..." bin-name (.length bin-name)))))
  (Bin. bin-name bin-value))

(defn- ^Bin set-bin-as-null [^String bin-name]
  (when (< MAX_BIN_NAME_LENGTH (.length bin-name))
    (throw (Exception. (format "%s is %s characters. Bin names have to be <= 14 characters..." bin-name (.length bin-name)))))
  (Bin/asNull bin-name))

;; get
(defrecord AerospikeRecord [payload ^Integer gen ^Integer ttl])

(defn- record->map [^Record record]
  (and record
    (let [bins      (into {} (.bins ^Record record)) ;; converting from java.util.HashMap to a Clojure map
          bin-names (mapv first bins)]
      (->AerospikeRecord
        (if (utils/single-bin? bin-names)
          (utils/desanitize-bin-value (get bins ""))
          (reduce-kv (fn [m k v]
                       (assoc m (keyword k) (utils/desanitize-bin-value v)))
            {}
            bins))
        ^Integer (.generation ^Record record)
        ^Integer (.expiration ^Record record)))))

(defn- map->multiple-bins [^IPersistentMap m]
  (let [bins (for [[k v] m]
               (create-bin (name k) (utils/sanitize-bin-value v)))]
    (into-array Bin bins)))

(defn- data->bins
  "Function to identify whether `data` will be stored as a single or multiple bin record.
  Only Clojure maps will default to multiple bins. Nested data structures are supported."
  [data]
  (if (map? data)
    (map->multiple-bins data)
    (into-array Bin [^Bin (Bin. "" (utils/sanitize-bin-value data))])))

(defn get-single
  "Returns a single record: `(transcoder AerospikeRecord)`. The default transcoder is `identity`.
  Pass a `:policy` in `conf` to use a non-default `ReadPolicy`"
  ([db index set-name] (get-single db index set-name [:all] {}))
  ([db index set-name ^IPersistentVector bin-keys] (get-single db index set-name bin-keys {}))
  ([db index set-name ^IPersistentVector bin-keys conf]
   (let [client (get-client db index)
         op-future (d/deferred)
         start-time (System/nanoTime)
         bin-names (mapv name bin-keys)] ;; bin-names can only be stored as strings in Aerospike
     (.get ^AerospikeClient client
           ^EventLoop (.next ^NioEventLoops (:el db))
           (reify-record-listener op-future)
           ^Policy (:policy conf)
           (create-key (:dbns db) set-name index)
           ^"[Lcom.aerospike.client.Bin;" (if (= [:all] bin-keys)
                                            (if (utils/single-bin? bin-names) bin-names nil)
                                            (into-array String bin-names)))
     (let [d (d/chain' op-future
                       record->map
                       (:transcoder conf identity))]
       (register-events d db "read" index start-time)))))

(defn get-multiple
  "Returns a (future) sequence of AerospikeRecords returned by `get-single`
  with records in corresponding places to the required keys. Indices and sets should be sequences.
  The `conf` map is passed to all `get-single` invocations."
  ([db indices sets]
   (get-multiple db indices sets {}))
  ([db indices sets conf]
   (apply d/zip'
          (map (fn [[index set-name]] (get-single db index set-name [:all] conf))
               (map vector indices sets)))))

(defn exists?
  "Test if an index exists."
  ([db index set-name] (exists? db index set-name {}))
  ([db index set-name conf]
   (let [client (get-client db index)
         op-future (d/deferred)
         start-time (System/nanoTime)]
     (.exists ^AerospikeClient client
              ^EventLoop (.next ^NioEventLoops (:el db))
              (reify-exists-listener op-future)
              ^Policy (:policy conf)
              (create-key (:dbns db) set-name index))
     (register-events op-future db "exists" index start-time))))

(defn get-single-no-meta
  "Shorthand to return a single record payload only."
  ([db index set-name] (get-single db index set-name [:all] {:transcoder :payload}))
  ([db index set-name ^IPersistentVector bin-keys]
    (get-single db index set-name bin-keys {:transcoder :payload})))

;; put
(defn- _put [db index data policy set-name]
  (let [client (get-client db index)
        bins (data->bins data)
        op-future (d/deferred)
        start-time (System/nanoTime)]
    (.put ^AerospikeClient client
          ^EventLoop (.next ^NioEventLoops (:el db))
          ^WriteListener (reify-write-listener op-future)
          ^WritePolicy policy
          (create-key (:dbns db) set-name index)
          ^"[Lcom.aerospike.client.Bin;" bins)
    (register-events op-future db "write" index start-time)))

(defn put
  "Writes `data` into a record with the key `index`, with the ttl of `expiration` seconds.
  `index` should be string. Pass a function in `(:trascoder conf)` to modify `data` before it
  is sent to the DB.
  Pass a `WritePolicy` in `(:policy conf)` to uses the non-default policy.
  When a Clojure map is provided for the `data` argument, a multiple bin record will be created.
  Each key-value pair in the map will be treated as a bin-name, bin-value pair. Bin-values can
  be any nested data structure."
  ([db index set-name data expiration] (put db index set-name data expiration {}))
  ([db index set-name data expiration conf]
   (_put db
         index
         ((:transcoder conf identity) data)
         (:policy conf (policy/write-policy (get-client db) expiration))
         set-name)))

(defn put-multiple
  "Put multiple payloads by invoking `put`. All arguments should be mutually
  corresponding sequences."
  ([db indices set-names payloads expirations]
   (put-multiple db indices set-names payloads expirations {}))
  ([db indices set-names payloads expirations conf]
   (apply d/zip'
          (map (fn [[index set-name payload expiration]]
                 (put db index set-name payload expiration conf))
               (map vector indices set-names payloads expirations)))))

(defn create
  "`put` with a create-only policy"
  ([db index set-name data expiration]
   (create db index set-name data expiration {}))
  ([db index set-name data expiration conf]
   (_put db
         index
         ((:transcoder conf identity) data)
         (policy/create-only-policy (get-client db) expiration)
         set-name)))

(defn replace-only
  "`put` with a replace-only policy"
  ([db index set-name data expiration]
   (replace-only db index set-name data expiration {}))
  ([db index set-name data expiration conf]
   (_put db
         index
         ((:transcoder conf identity) data)
         (policy/replace-only-policy (get-client db) expiration)
         set-name)))

(defn update
  "Writing a new value for the key `index`.
  Generation: the expected modification count of the record (i.e. how many times was it
  modified before my current action). Pass a function in `(:trascoder conf)` to modify
  `data` before it is sent to the DB."
  ([db index set-name new-record generation new-expiration]
   (update db index set-name new-record generation new-expiration {}))
  ([db index set-name new-record generation new-expiration conf]
   (_put db
         index
         ((:transcoder conf identity) new-record)
         (policy/update-policy (get-client db) generation new-expiration)
         set-name)))

(defn add-bins
  "Add bins to an existing record without modifying old data. The `new-data` must be a
  Clojure map."
  ([db index set-name ^IPersistentMap new-data new-expiration]
   (add-bins db index set-name new-data new-expiration {}))
  ([db index set-name ^IPersistentMap new-data new-expiration conf]
   (_put db
         index
         ((:transcoder conf identity) new-data)
         (policy/update-only-policy (get-client db) new-expiration)
         set-name)))

(defn touch
  "Updates the ttl of the record stored under at `index` to `expiration` seconds from now.
  Expects records to exist."
  [db index set-name expiration]
  (let [client (get-client db index)
        op-future (d/deferred)
        start-time (System/nanoTime)]
    (.touch ^AerospikeClient client
            ^EventLoop (.next ^NioEventLoops (:el db))
            ^WriteListener (reify-write-listener op-future)
            ^WritePolicy (policy/write-policy client expiration RecordExistsAction/UPDATE_ONLY)
            (create-key (:dbns db) set-name index))
    (register-events op-future db "touch" index start-time)))

;; delete

(defn delete
  "Delete the record stored for key <index>.
  Returns async true/false for deletion success (hit)."
  ([db index set-name]
   (delete db index set-name {}))
  ([db index set-name conf]
   (let [client (get-client db index)
         op-future (d/deferred)
         start-time (System/nanoTime)]
     (.delete ^AerospikeClient client
              ^EventLoop (.next ^NioEventLoops (:el db))
              ^DeleteListener (reify-delete-listener op-future)
              ^WritePolicy (:policy conf)
              (create-key (:dbns db) set-name index))
     (register-events op-future db "delete" index start-time))))

(defn- _delete-bins [db index bin-keys policy set-name]
  (let [client (get-client db index)
        op-future (d/deferred)
        start-time (System/nanoTime)
        bin-names (mapv name bin-keys)]
    (.put ^AerospikeClient client
          ^EventLoop (.next ^NioEventLoops (:el db))
          ^WriteListener (reify-write-listener op-future)
          ^WritePolicy policy
          (create-key (:dbns db) set-name index)
          ^"[Lcom.aerospike.client.Bin;" (into-array Bin (mapv #(set-bin-as-null %) bin-names)))
    (register-events op-future db "write" index start-time)))

(defn delete-bins
  "Delete bins from an existing record. The `bin-keys` must be a vector of keywords."
  ([db index set-name ^IPersistentVector bin-keys new-expiration]
   (delete-bins db index set-name bin-keys new-expiration {}))
  ([db index set-name ^IPersistentVector bin-keys new-expiration conf]
   (_delete-bins db
                 index
                 ((:transcoder conf identity) bin-keys)
                 (policy/update-only-policy (get-client db) new-expiration)
                 set-name)))

;; operate

(defn operate
  "Asynchronously perform multiple read/write operations on a single key in one batch call.
  This method registers the command with an event loop and returns. The event loop thread
  will process the command and send the results to the listener.
  `commands` is a sequence of Aerospike CDT operations."
  ([db index set-name expiration operations]
   (operate db index set-name expiration operations {}))
  ([db index set-name expiration operations conf]
   (if (empty? operations)
     (d/success-deferred nil)
     (let [client (get-client db index)
           op-future (d/deferred)
           start-time (System/nanoTime)]
       (.operate ^AerospikeClient client
                 ^EventLoop (.next ^NioEventLoops (:el db))
                 ^RecordListener (reify-record-listener op-future)
                 ^WritePolicy (:policy conf (policy/write-policy client expiration RecordExistsAction/UPDATE))
                 (create-key (:dbns db) set-name index)
                 (into-array Operation operations))
       (register-events (d/chain' op-future record->map) db "operate" index start-time)))))

;; metrics
(defn get-cluster-stats
  "For each client, return a vector of [metric-name metric-val] 2-tuples.
  The metric name is a dot separated string that should be convenient for
  reporting to statsd/graphite. All values are gauges."
  [db]
  (let [clients (get-all-clients db)]
    (->> clients
         (mapv #(metrics/construct-cluster-metrics (.getClusterStats ^AerospikeClient %)))
         (mapv metrics/cluster-metrics->dotted))))

;; health

(defn healthy?
  "Returns true iff the cluster is reachable and can take reads and writes.
  Uses __health-check set to avoid data collisions. `operation-timeout-ms` is for total timeout of reads
  (including 2 retries) so an small over estimation is advised to avoid false negatives."
  [db operation-timeout-ms]
  (let [read-policy (let [p (.readPolicyDefault ^AerospikeClient (get-client db ""))]
                      (set! (.totalTimeout p) operation-timeout-ms)
                      p)
        k (str "__health__" (rand-int 1000))
        v 1
        ttl (min 1 (int (/ operation-timeout-ms 1000)))
        set-name "__health-check"]
    (try
      @(create db k set-name v ttl)
      (= v
         @(get-single db k set-name [:all] {:transcoder :payload
                                            :policy read-policy}))
      (catch Exception ex
        false))))

;; etc

(defn expiry-unix
  "Used to convert Aerospike style returned TTLS to standard UNIX EPOCH."
  [ttl]
  (+ ttl EPOCH))
