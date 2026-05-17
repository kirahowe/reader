(ns reader.http
  "Manifest for the :reader.http/* integrant keys.
   Required so `ig/load-namespaces` resolves the handler and server
   init-keys, which live in sub-namespaces."
  (:require reader.http.router
            reader.http.server))
