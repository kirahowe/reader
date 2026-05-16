(ns kira.reader.http
  "Manifest for the :kira.reader.http/* integrant keys.
   Required so that `ig/load-namespaces` resolves the http handler
   and http server init-keys, which live in sub-namespaces."
  (:require kira.reader.http.router
            kira.reader.http.server))
