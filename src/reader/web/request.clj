(ns reader.web.request
  "Small ring-request helpers shared across handlers, so the handlers stay
   glue: read input, call the domain, pick a response.")

(defn path-uuid
  "The `:id` path param parsed as a UUID, or nil for a missing or non-uuid value
   (callers answer 404)."
  [req]
  (some-> (get-in req [:path-params :id]) parse-uuid))
