(ns repl
  "Talking to a running nREPL from a bb task. Discovers the server via
   the conventional `.nrepl-port` file (written by `bb dev` or an
   editor jack-in) and evals over a raw bencode socket — no client dep,
   bencode ships with bb.

   Port discovery means tasks never hardcode a port: they hit whichever
   REPL is currently advertising itself, whether that's `bb dev` or the
   editor you jacked in to."
  (:require [babashka.fs :as fs]
            [bencode.core :as bencode]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io PushbackInputStream)
           (java.net Socket)))

(def port-file ".nrepl-port")

(defn parse-port
  "Parse the contents of a `.nrepl-port` file to an int, or nil when the
   content is blank, nil, or not a number."
  [s]
  (some-> s str/trim not-empty parse-long))

(defn read-port
  "Read the advertised nREPL port from `.nrepl-port`, or nil when the
   file is absent or unparseable."
  []
  (when (fs/exists? port-file)
    (parse-port (slurp port-file))))

(defn- bytes->str [x] (if (bytes? x) (String. ^bytes x "UTF-8") x))

(defn- coerce [v]
  (cond
    (map? v)    (into {} (for [[k v] v] [(keyword (bytes->str k)) (coerce v)]))
    (vector? v) (mapv coerce v)
    :else       (bytes->str v)))

(defn eval-expr
  "Send `code` to the nREPL on `port`, drain replies until status
   :done, and return `{:value … :err …}`. Throws ConnectException when
   nothing is listening on `port`."
  [port code]
  (with-open [sock (Socket. "127.0.0.1" ^int port)
              out  (io/output-stream sock)
              in   (PushbackInputStream. (io/input-stream sock))]
    (bencode/write-bencode out {"op"   "eval"
                                "code" code
                                "id"   (str (random-uuid))})
    (.flush out)
    (loop [acc {:value nil :err nil}]
      (let [m (coerce (bencode/read-bencode in))]
        (cond
          (some #{"done"} (:status m)) acc
          (:value m) (recur (assoc acc :value (:value m)))
          (:err m)   (recur (update acc :err (fnil str "") (:err m)))
          (:out m)   (do (print (:out m)) (flush) (recur acc))
          :else      (recur acc))))))
