(ns fly
  "Fly.io-specific bb task helpers.

   Helper functions (`default-app-name`, `not-authed`, `app-missing`,
   `already-exists`, `creating`, `done`) return report maps for
   `depends/say!`. The `require-*!` / `init!` functions are thin
   orchestrators that run the necessary IO and pipe results through
   `say!`."
  (:require [babashka.process :as p]
            [depends :as dep]))

(defn- silent
  "Run a command, capturing stdout/stderr; never throws."
  [& args]
  (apply p/shell {:out :string :err :string :continue true} args))

(defn default-app-name
  "Read the app name from a Fly toml file (the single source of truth).
   Defaults to `fly.toml` in the working directory."
  ([] (default-app-name "fly.toml"))
  ([path]
   (second (re-find #"(?m)^app\s*=\s*\"([^\"]+)\""
                    (slurp path)))))

(defn not-authed
  "Result map for a `flyctl auth whoami` check."
  [auth-result]
  (if (zero? (:exit auth-result))
    dep/ok
    (dep/problem "Not authenticated with Fly.io" "Run: flyctl auth login")))

(defn app-missing
  "Result map for a `flyctl status -a <app>` check."
  [status-result app-name hint]
  (if (zero? (:exit status-result))
    dep/ok
    (dep/problem (str "Fly app `" app-name "` not found") hint)))

(defn require-authed!
  "Check that the current user is authenticated with Fly."
  ([] (require-authed! #(silent "flyctl" "auth" "whoami") dep/say!))
  ([whoami-fn say-fn]
   (say-fn (not-authed (whoami-fn)))))

(defn require-app!
  "Check that `app-name` exists on Fly."
  ([app-name hint]
   (require-app! app-name hint
                 #(silent "flyctl" "status" "-a" app-name)
                 dep/say!))
  ([app-name hint status-fn say-fn]
   (say-fn (app-missing (status-fn) app-name hint))))

(def init-hints
  "Known `flyctl apps create` failures → friendlier explanations."
  [{:match   #"Name has already been taken"
    :explain (str "Fly app names are globally unique. Edit `fly.toml`'s "
                  "`app = ...` to something namespaced to you (e.g. "
                  "`<your-handle>-reader`) and run `bb fly:init` again.")}])

(defn already-exists
  "Result map for the no-op `init!` case (the app already exists)."
  [app]
  {:lines [(str "App `" app "` already exists on Fly. Nothing to do.")]
   :exit  0})

(defn creating
  "Result map describing the about-to-create step."
  [app org]
  {:lines [(str "Creating Fly app `" app "` in org `" org "`...")]
   :exit  0})

(def done
  "Result map describing the success-finish step."
  {:lines ["Done. Next: bb deploy"]
   :exit  0})

(defn init!
  "Create the Fly app for this project (one-time setup). The name
   comes from fly.toml's `app = ...`; change it there if the default
   is taken on Fly's global namespace."
  ([] (init! default-app-name
             #(silent "flyctl" "status" "-a" %)
             dep/say! dep/sh! "personal"))
  ([app-name-fn status-fn say-fn sh-fn org]
   (let [app     (app-name-fn)
         exists? (zero? (:exit (status-fn app)))]
     (if exists?
       (say-fn (already-exists app))
       (do (say-fn (creating app org))
           (sh-fn {:hints init-hints}
                  "flyctl" "apps" "create" app "--org" org)
           (say-fn done))))))
