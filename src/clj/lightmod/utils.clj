(ns lightmod.utils
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]
            [nightcode.state :refer [*pref-state *runtime-state]]
            [nightcode.utils :as u]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.file :as file]
            [clojure.tools.namespace.track :as track]))

(defn get-project-dir
  ([] (some-> @*pref-state :selection io/file get-project-dir))
  ([file]
   (loop [f file]
     (when-let [parent (.getParentFile f)]
       (if (= parent (:projects-dir @*runtime-state))
         f
         (recur parent))))))

(defn current-project? [dir]
  (= dir (.getCanonicalPath (get-project-dir))))

(defn sanitize-name [s]
  (as-> s $
        (str/trim $)
        (str/lower-case $)
        (str/replace $ "'" "")
        (str/replace $ #"[^a-z0-9]" " ")
        (str/split $ #" ")
        (remove empty? $)
        (str/join "-" $)))

(defn copy-from-resources! [from to]
  (let [dest (io/file to ".out" from)]
    (when-not (.exists dest)
      (.mkdirs (.getParentFile dest))
      (spit dest (slurp (io/resource from))))
    (str (-> to io/file .getName) "/.out/" from)))

(defn path->ns [path leaf-name]
  (-> path io/file .getName (str/replace #"_" "-") (str "." leaf-name)))

(defn get-files-in-dep-order [dir]
  (let [out-dir (.getCanonicalPath (io/file dir ".out"))
        tracker (->> (file-seq (io/file dir))
                     (remove #(u/parent-path? out-dir (.getCanonicalPath %)))
                     (filter #(file/file-with-extension? % ["clj" "cljc"]))
                     (file/add-files (track/tracker)))
        ns->file (-> tracker
                     :clojure.tools.namespace.file/filemap
                     set/map-invert)]
    (keep ns->file (:clojure.tools.namespace.track/load tracker))))

(defn check-namespaces! [dir server?]
  (let [out-dir (.getCanonicalPath (io/file dir ".out"))
        tracker (->> (file-seq (io/file dir))
                     (remove #(u/parent-path? out-dir (.getCanonicalPath %)))
                     (filter #(file/file-with-extension? % (if server?
                                                             ["clj" "cljc"]
                                                             ["cljs"])))
                     (file/add-files (track/tracker)))
        ns->file (-> tracker
                     :clojure.tools.namespace.file/filemap
                     set/map-invert)
        ns-start (path->ns dir "")]
    (doseq [[ns-name file] ns->file]
      (when-not (.startsWith (name ns-name) ns-start)
        (throw (Exception. (format "The ns name in %s must start with \"%s\""
                             (.getName file) ns-start)))))))

