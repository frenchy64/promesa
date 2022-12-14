;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.exec
  "Executors & Schedulers facilities."
  (:refer-clojure :exclude [run! pmap await])
  (:require [promesa.protocols :as pt]
            [promesa.util :as pu]
            #?(:cljs [goog.object :as gobj]))
  #?(:clj
     (:import
      clojure.lang.Var
      java.lang.AutoCloseable
      java.time.Duration
      java.util.concurrent.Callable
      java.util.concurrent.CompletableFuture
      java.util.concurrent.CompletionStage
      java.util.concurrent.CountDownLatch
      java.util.concurrent.Executor
      java.util.concurrent.ExecutorService
      java.util.concurrent.Executors
      java.util.concurrent.ForkJoinPool
      java.util.concurrent.ForkJoinPool$ForkJoinWorkerThreadFactory
      java.util.concurrent.ForkJoinWorkerThread
      java.util.concurrent.Future
      java.util.concurrent.ScheduledExecutorService
      java.util.concurrent.ThreadFactory
      java.util.concurrent.TimeUnit
      java.util.concurrent.TimeoutException
      java.util.concurrent.atomic.AtomicLong
      java.util.function.Supplier)))

#?(:clj (set! *warn-on-reflection* true))

;; --- Globals & Defaults (with CLJS Impl)

(declare scheduled-executor)
(declare current-thread-executor)
(declare ->ScheduledTask)

#?(:clj  (declare cached-executor))
#?(:cljs (declare microtask-executor))

(def ^:dynamic *default-scheduler* nil)
(def ^:dynamic *default-executor* nil)

(def vthreads-supported?
  "A var that indicates if virtual threads are supported or not in the current runtime."
  #?(:clj (and (pu/has-method? Thread "startVirtualThread")
               (try
                 (eval '(Thread/startVirtualThread (constantly nil)))
                 true
                 (catch Throwable cause
                   false)))
     :cljs false))

(def ^{:no-doc true} noop (constantly nil))

#?(:clj
   (defn- get-available-processors
     []
     (.availableProcessors (Runtime/getRuntime))))

(defonce
  ^{:doc "Default scheduled executor instance."}
  default-scheduler
  (delay
    #?(:clj  (scheduled-executor :parallelism (get-available-processors))
       :cljs (scheduled-executor))))

(defonce
  ^{:doc "Default executor instance, ForkJoinPool/commonPool in JVM, MicrotaskExecutor on JS."}
  default-executor
  (delay
    #?(:clj  (ForkJoinPool/commonPool)
       :cljs (microtask-executor))))

;; Executor that executes the task in the calling thread
(def ^{:doc "Default Executor instance that runs the task in the same thread."}
  default-current-thread-executor
  (delay (current-thread-executor)))

(defonce
  ^{:doc "A global, virtual thread per task executor service."
    :no-doc true}
  default-vthread-executor
  #?(:clj  (delay (when vthreads-supported?
                    (eval '(java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor))))
     :cljs default-executor))

(defonce
  ^{:doc "A global, thread per task executor service."
    :no-doc true}
  default-thread-executor
  #?(:clj  (delay (cached-executor))
     :cljs default-executor))

(defn executor?
  "Returns true if `o` is an instane of Executor or satisfies IExecutor protocol."
  [o]
  #?(:clj  (or (instance? Executor o)
               (satisfies? pt/IExecutor o))
     :cljs (satisfies? pt/IExecutor o)))

#?(:clj
(defn shutdown!
  "Shutdowns the executor service."
  [^ExecutorService executor]
  (.shutdown executor)))

#?(:clj
(defn shutdown-now!
  "Shutdowns and interrupts the executor service."
  [^ExecutorService executor]
  (.shutdownNow executor)))

#?(:clj
(defn shutdown?
  "Check if execitor is in shutdown state."
  [^ExecutorService executor]
  (.isShutdown executor)))

(defn resolve-executor
  {:no-doc true}
  ([] (resolve-executor nil))
  ([executor]
   (if (or (nil? executor) (= :default executor))
     @default-executor
     (case executor
       :thread         (pu/maybe-deref default-thread-executor)
       :vthread        (pu/maybe-deref default-vthread-executor)
       :current-thread (pu/maybe-deref default-current-thread-executor)
       (pu/maybe-deref executor)))))

(defn resolve-scheduler
  {:no-doc true}
  ([] (resolve-scheduler nil))
  ([scheduler]
   (if (or (nil? scheduler) (= :default scheduler))
     (pu/maybe-deref default-scheduler)
     (pu/maybe-deref scheduler))))

(defn wrap-bindings
  {:no-doc true}
  [f]
  #?(:cljs f
     :clj
     (let [bindings (get-thread-bindings)]
       (fn
         ([]
          (push-thread-bindings bindings)
          (try
            (f)
            (finally
              (pop-thread-bindings))))
         ([a]
          (push-thread-bindings bindings)
          (try
            (f a)
            (finally
              (pop-thread-bindings))))
         ([a b]
          (push-thread-bindings bindings)
          (try
            (f a b)
            (finally
              (pop-thread-bindings))))
         ([a b c]
          (push-thread-bindings bindings)
          (try
            (f a b c)
            (finally
              (pop-thread-bindings))))
         ([a b c d]
          (push-thread-bindings bindings)
          (try
            (f a b c d)
            (finally
              (pop-thread-bindings))))
         ([a b c d e]
          (push-thread-bindings bindings)
          (try
            (f a b c d e)
            (finally
              (pop-thread-bindings))))
         ([a b c d e & args]
          (push-thread-bindings bindings)
          (try
            (apply f a b c d e args)
            (finally
              (pop-thread-bindings))))))))

;; --- Public API

(defn run!
  "Run the task in the provided executor."
  ([f]
   (pt/-run! (resolve-executor *default-executor*) f))
  ([executor f]
   (pt/-run! (resolve-executor executor) f)))

(defn submit!
  "Submit a task to be executed in a provided executor
  and return a promise that will be completed with
  the return value of a task.

  A task is a plain clojure function."
  ([f]
   (pt/-submit! (resolve-executor *default-executor*) f))
  ([executor f]
   (pt/-submit! (resolve-executor executor) f)))

(defn schedule!
  "Schedule a callable to be executed after the `ms` delay
  is reached.

  In JVM it uses a scheduled executor service and in JS
  it uses the `setTimeout` function."
  ([ms f]
   (pt/-schedule! (resolve-scheduler) ms f))
  ([scheduler ms f]
   (pt/-schedule! (resolve-scheduler scheduler) ms f)))

;; --- Pool & Thread Factories

#?(:clj
   (defn thread-factory?
     "Checks if `o` is an instance of ThreadFactory"
     [o]
     (instance? ThreadFactory o)))

#?(:clj
   (defn- fn->thread-factory
     "Adapt a simple clojure function into a ThreadFactory instance."
     [func]
     (reify ThreadFactory
       (^Thread newThread [_ ^Runnable runnable]
        (func runnable)))))

#?(:clj
(def ^{:no-doc true :dynamic true}
  *default-counter*
  (AtomicLong. 0)))

#?(:clj
(defn get-next
  "Get next value from atomic long counter"
  {:no-doc true}
  ([] (.getAndIncrement ^AtomicLong *default-counter*))
  ([counter] (.getAndIncrement ^AtomicLong counter))))

#?(:clj
(defn thread-factory
  "Returns an instance of promesa default thread factory."
  [& {:keys [name daemon priority]
      :or {daemon true
           priority Thread/NORM_PRIORITY
           name "promesa/thread/%s"}}]
  (let [counter (AtomicLong. 0)]
    (reify ThreadFactory
      (newThread [this runnable]
        (doto (Thread. ^Runnable runnable)
          (.setPriority (int priority))
          (.setDaemon ^Boolean daemon)
          (.setName (format name (get-next counter)))))))))

#?(:clj
(defn forkjoin-thread-factory
  ^ForkJoinPool$ForkJoinWorkerThreadFactory
  [& {:keys [name daemon] :or {name "promesa/forkjoin/%s" daemon true}}]
  (let [counter (AtomicLong. 0)]
    (reify ForkJoinPool$ForkJoinWorkerThreadFactory
      (newThread [_ pool]
        (let [thread (.newThread ForkJoinPool/defaultForkJoinWorkerThreadFactory pool)
              tname  (format name (get-next counter))]
          (.setName ^ForkJoinWorkerThread thread ^String tname)
          (.setDaemon ^ForkJoinWorkerThread thread ^Boolean daemon)
          thread))))))

#?(:clj
(defn- resolve-thread-factory
  {:no-doc true}
  ^ThreadFactory
  [opts]
  (cond
    (thread-factory? opts) opts
    (= :default opts)      (thread-factory)
    (nil? opts)            (thread-factory)
    (map? opts)            (thread-factory opts)
    (fn? opts)             (fn->thread-factory opts)
    :else                  (throw (ex-info "Invalid thread factory" {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; --- DEPRECATED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defn cached-pool
     "A cached thread pool constructor."
     {:deprecated "9.0" :no-doc true}
     ([]
      (Executors/newCachedThreadPool))
     ([opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newCachedThreadPool factory)))))

#?(:clj
   (defn fixed-pool
     "A fixed thread pool constructor."
     {:deprecated "9.0" :no-doc true}
     ([n]
      (Executors/newFixedThreadPool (int n)))
     ([n opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newFixedThreadPool (int n) factory)))))

#?(:clj
   (defn single-pool
     "A single thread pool constructor."
     {:deprecated "9.0" :no-doc true}
     ([]
      (Executors/newSingleThreadExecutor))
     ([opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newSingleThreadExecutor factory)))))

#?(:clj
   (defn scheduled-pool
     "A scheduled thread pool constructor."
     {:deprecated "9.0" :no-doc true}
     ([] (Executors/newScheduledThreadPool (int 0)))
     ([n] (Executors/newScheduledThreadPool (int n)))
     ([n opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newScheduledThreadPool (int n) factory)))))

#?(:clj
   (defn work-stealing-pool
     "Creates a work-stealing thread pool."
     {:deprecated "9.0" :no-doc true}
     ([] (Executors/newWorkStealingPool))
     ([n] (Executors/newWorkStealingPool (int n)))))

#?(:clj
   (defn forkjoin-pool
     {:deprecated "9.0" :no-doc true}
     [{:keys [factory async? parallelism]
       :or {async? true}
       :as opts}]
     (let [parallelism (or parallelism (get-available-processors))
           factory     (cond
                         (instance? ForkJoinPool$ForkJoinWorkerThreadFactory factory) factory
                         (nil? factory) ForkJoinPool/defaultForkJoinWorkerThreadFactory
                         :else (throw (ex-info "Unexpected thread factory" {:factory factory})))]
       (ForkJoinPool. parallelism factory nil async?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; --- END DEPRECATED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defn cached-executor
     "A cached thread executor pool constructor."
     [& {:keys [factory]}]
     (let [factory (or (some-> factory resolve-thread-factory)
                       (thread-factory :name "promesa/cached/%s"))]
       (Executors/newCachedThreadPool factory))))

#?(:clj
   (defn fixed-executor
     "A fixed thread executor pool constructor."
     [& {:keys [parallelism factory]}]
     (let [factory (or (some-> factory resolve-thread-factory)
                       (thread-factory :name "promesa/fixed/%s"))]
       (Executors/newFixedThreadPool (int parallelism) factory))))

#?(:clj
   (defn single-executor
     "A single thread executor pool constructor."
     [& {:keys [factory]}]
     (let [factory (or (some-> factory resolve-thread-factory)
                       (thread-factory :name "promesa/single/%s"))]
       (Executors/newSingleThreadExecutor factory))))

(defn current-thread-executor
  "Creates an executor instance that run tasks in the same thread."
  []
  #?(:clj
     (reify
       Executor
       (^void execute [_ ^Runnable f] (.run f))

       pt/IExecutor
       (-run! [this f]
         (-> (pt/-promise nil)
             (pt/-finally (fn [_ _]
                            (f)))))
       (-submit! [this f]
         (-> (pt/-promise nil)
             (pt/-map (fn [_] (f))))))

     :cljs
     (reify
       pt/IExecutor
       (-run! [this f]
         (try
           (pt/-promise (comp noop f))
           (catch :default cause
             (pt/-promise cause))))

       (-submit! [this f]
         (try
           (pt/-promise (f))
           (catch :default cause
             (pt/-promise cause)))))))

#?(:cljs
   (defn microtask-executor
     "An IExecutor that schedules tasks to be executed in the MicrotasksQueue."
     []
     (reify
       pt/IExecutor
       (-run! [this f]
         (-> (pt/-promise nil)
             (pt/-map (fn [_]
                        (try (f) (catch :default _ nil))))
             (pt/-map noop)))

       (-submit! [this f]
         (-> (pt/-promise nil)
             (pt/-map (fn [_] (f))))))))


(defn scheduled-executor
  "A scheduled thread pool constructor. A ScheduledExecutor (IScheduler
  in CLJS) instance allows execute asynchronous tasks some time later."
  [& {:keys [parallelism factory] :or {parallelism 1}}]
  #?(:clj
     (let [parallelism (or parallelism (get-available-processors))
           factory     (or (some-> factory resolve-thread-factory)
                           (thread-factory :name "promesa/scheduled/%s"))]


       (doto (java.util.concurrent.ScheduledThreadPoolExecutor. (int parallelism) ^ThreadFactory factory)
         (.setRemoveOnCancelPolicy true)))

     :cljs
     (reify pt/IScheduler
       (-schedule! [_ ms f]
         (let [done (volatile! false)
               task #(try
                       (f)
                       (finally
                         (vreset! done true)))
               tid (js/setTimeout task ms)
               cancel #(js/clearTimeout tid)]
           (->ScheduledTask #js {:done done
                                 :cancelled false
                                 :cancel-fn cancel}))))))

#?(:clj
   (when vthreads-supported?
     (eval
      '(defn thread-per-task-executor
         [& {:keys [factory]}]
         (let [factory (or (some-> factory resolve-thread-factory)
                           (thread-factory :name "promesa/thread-per-task/%s"))]
           (Executors/newThreadPerTaskExecutor ^ThreadFactory factory))))))

#?(:clj
   (when vthreads-supported?
     (eval
      '(defn vthread-per-task-executor
         []
         (Executors/newVirtualThreadPerTaskExecutor)))))

#?(:clj
   (defn forkjoin-executor
     [& {:keys [factory async? parallelism] :or {async? true}}]
     (let [parallelism (or parallelism (get-available-processors))
           factory     (cond
                         (instance? ForkJoinPool$ForkJoinWorkerThreadFactory factory) factory
                         (nil? factory) (forkjoin-thread-factory)
                         :else (throw (ex-info "Unexpected thread factory" {:factory factory})))]
       (ForkJoinPool. (int parallelism) factory nil async?))))

#?(:clj
   (defn work-stealing-executor
     "An alias for the `forkjoin-executor`."
     [& params]
     (apply forkjoin-executor params)))

#?(:clj
   (defn configure-default-executor!
     [& params]
     (alter-var-root #'*default-executor*
                     (fn [executor]
                       (when (and (delay? executor) (realized? executor))
                         (.close ^AutoCloseable @executor))
                       (when (instance? AutoCloseable executor)
                         (.close ^AutoCloseable executor))
                       (apply forkjoin-executor params)))))

#?(:clj
   (extend-type Executor
     pt/IExecutor
     (-run! [this f]
       (CompletableFuture/runAsync ^Runnable f ^Executor this))

     (-submit! [this f]
       (CompletableFuture/supplyAsync ^Supplier (pu/->SupplierWrapper f) ^Executor this))))


;; --- Scheduler & ScheduledTask

#?(:clj
   (deftype ScheduledTask [^Future fut]
     clojure.lang.IDeref
     (deref [_] (.get fut))

     clojure.lang.IBlockingDeref
     (deref [_ ms default]
       (try
         (.get fut ms TimeUnit/MILLISECONDS)
         (catch TimeoutException e
           default)))

     clojure.lang.IPending
     (isRealized [_] (and (.isDone fut)
                          (not (.isCancelled fut))))

     pt/ICancellable
     (-cancelled? [_]
       (.isCancelled fut))
     (-cancel! [_]
       (when-not (.isCancelled fut)
         (.cancel fut true)))

     Future
     (get [_] (.get fut))
     (get [_ timeout unit] (.get fut timeout unit))
     (isCancelled [_] (.isCancelled fut))
     (isDone [_] (.isDone fut))
     (cancel [_ interrupt?] (.cancel fut interrupt?)))

   :cljs
   (deftype ScheduledTask [state]
     cljs.core/IPending
     (-realized? [_]
       (let [done-iref (gobj/get state "done")]
         (deref done-iref)))

     pt/ICancellable
     (-cancelled? [_]
       (gobj/get state "cancelled"))
     (-cancel! [self]
       (when-not (pt/-cancelled? self)
         (let [cancel-fn (gobj/get state "cancel-fn")]
           (gobj/set state "cancelled" true)
           (cancel-fn))))))

#?(:clj
   (extend-type ScheduledExecutorService
     pt/IScheduler
     (-schedule! [this ms f]
       (let [ms  (if (instance? Duration ms) (inst-ms ms) ms)
             fut (.schedule this ^Callable f (long ms) TimeUnit/MILLISECONDS)]
         (ScheduledTask. fut)))))

(defmacro with-dispatch
  "Helper macro for dispatch execution of the body to an executor
  service. The returned promise is not cancellable (the body will be
  executed independently of the cancellation)."
  [executor & body]
  `(-> (submit! ~executor (wrap-bindings (^:once fn* [] ~@body)))
       (pt/-bind pt/-promise)))

(defmacro with-executor
  "Binds the *default-executor* var with the provided executor,
  executes the macro body. It also can optionally shutdown or shutdown
  and interrupt on termination if you provide `^:shutdown` and
  `^:interrupt` metadata.

  **EXPERIMENTAL API:** This function should be considered
  EXPERIMENTAL and may be changed or removed in future versions until
  this notification is removed."
  [executor & body]
  (let [interrupt?   (-> executor meta :interrupt)
        shutdown?    (-> executor meta :shutdown)
        executor-sym (gensym "executor")]
    `(let [~executor-sym ~executor
           ~executor-sym (if (fn? ~executor-sym) (~executor-sym) ~executor-sym)]
       (binding [*default-executor* ~executor-sym]
         (try
           ~@body
           (finally
             ~(when (or shutdown? interrupt?)
                (list (if interrupt? 'promesa.exec/shutdown-now! 'promesa.exec/shutdown!) executor-sym))))))))

#?(:clj
(defn pmap
  "Analogous to the `clojure.core/pmap` with the excetion that it allows
  use a custom executor (binded to *default-executor* var) The default
  clojure chunk size (32) is used for evaluation and the real
  parallelism is determined by the provided executor.


  **EXPERIMENTAL API:** This function should be considered
  EXPERIMENTAL and may be changed or removed in future versions until
  this notification is removed."
  {:experimental true}
  ([f coll]
   (let [executor (resolve-executor *default-executor*)
         frame    (Var/cloneThreadBindingFrame)]
     (->> coll
          (map (fn [o] (pt/-submit! executor #(do
                                                (Var/resetThreadBindingFrame frame)
                                                (f o)))))
          (clojure.lang.RT/iter)
          (clojure.lang.RT/chunkIteratorSeq)
          (map (fn [o] (.get ^CompletableFuture o))))))
  ([f coll & colls]
   (let [step-fn (fn step-fn [cs]
                   (lazy-seq
                    (let [ss (map seq cs)]
                      (when (every? identity ss)
                        (cons (map first ss) (step-fn (map rest ss)))))))]
     (pmap #(apply f %) (step-fn (cons coll colls)))))))

#?(:clj
   (defmacro thread
     "A low-level, not-pooled thread constructor."
     [opts & body]
     (let [[opts body] (if (map? opts)
                         [opts body]
                         [nil (cons opts body)])]
       `(let [opts# ~opts
              thr#  (Thread. (^:once fn* [] ~@body))]
          (.setName thr# (str (or (:name opts#) (format "promesa/unpooled-thread/%s" (get-next)))))
          (.setDaemon thr# (boolean (:daemon opts# true)))
          (.setPriority thr# (int (:priority opts# Thread/NORM_PRIORITY)))
          (.start thr#)
          thr#))))

#?(:clj
(defn current-thread
  "Return the current thread."
  []
  (Thread/currentThread)))

#?(:clj
(defn interrupted?
  "Check if the thread has the interrupted flag set.

  There are two special cases:

  Using the `:current` keyword as argument will check the interrupted
  flag on the current thread.

  Using the arity 0 (passing no arguments), then the current thread
  will be checked and **WARNING** the interrupted flag reset to
  `false`."
  ([]
   (Thread/interrupted))
  ([thread]
   (if (= :current thread)
     (.isInterrupted (Thread/currentThread))
     (.isInterrupted ^Thread thread)))))

#?(:clj
(defn thread-id
  "Retrieves the thread ID."
  ([]
   (.getId ^Thread (Thread/currentThread)))
  ([^Thread thread]
   (.getId thread))))

#?(:clj
(defn interrupt!
  "Interrupt a thread."
  ([]
   (.interrupt (Thread/currentThread)))
  ([^Thread thread]
   (.interrupt thread))))

#?(:clj
(defn thread?
  "Check if provided object is a thread instance."
  [t]
  (instance? Thread t)))

#?(:clj
(defn sleep
  "Turn the current thread to sleep accept a number of milliseconds or
  Duration instance."
  [ms]
  (if (instance? Duration ms)
    (Thread/sleep (int (.toMillis ^Duration ms)))
    (Thread/sleep (int ms)))))
