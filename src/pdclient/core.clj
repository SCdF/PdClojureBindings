(ns pdclient.core
  (:require [clj-http.client])
  (:use [clojure.string :only [join]]
        pdclient.basic-helpers
        ))

(def events-api-endpoint "https://events.pagerduty.com/generic/2010-04-15/create_event.json")

(def basic-auth-credentials nil)

(defn setup-auth [map] (def basic-auth-credentials map))

(defn auth [k]
    (if basic-auth-credentials
      (basic-auth-credentials k)
      (throw (IllegalStateException. "Please call setup-auth with the auth args before using PagerDuty API. Example:
(setup-auth {:subdomain \"your-subdomain\"
  :user \"your-username\"
  :password \"your-password\"})")))
  )

(defn set-params [method req-map params-map]
  (let [extra-key (if (= (keyword method) :get) :query-params :form-params)
        params  (assoc req-map extra-key params-map)
        ]
    (if (auth :token )
      (assoc params :headers {"Authorization" (str "Token token=" (auth :token)) })
      (assoc params :basic-auth [(auth :user) (auth :password)])
      )
   ))

(defn- get-id [arg]
  (if (map? arg) (->> arg :id name) (name arg)))

(defn generic-pdrequest [method url args]
  (:body ((resolve (symbol "clj-http.client" (name method)))
           url
           (set-params method {
              :content-type :json
              :accept :json
              :as :json} (args-to-map args)))))

(defn pdrequest [method path-list args]
  (generic-pdrequest method (str "https://" (auth :subdomain) ".pagerduty.com/api/v1/" (join "/" (map get-id path-list))) args))

(defn simplify-single-result [path-list json]
  (let [penultimate (nth (reverse path-list) 1)
        singular-keyword (singularize-keyword penultimate)]
    (singular-keyword json)))


(defn simplitfy-list [path-list json]
  (->> (last path-list) keyword json  ))

(defn simplify-any [path-list json]
  (if (= (count json) 1)
    (first (vals json))
    (or (simplitfy-list path-list json) json)))


(defn simplitfy-create [path-list json]
  ((->> path-list last singularize-keyword) json))

(def simplify-case
    {'list simplitfy-list
     'show simplify-any
     'create simplitfy-create
     'update simplify-single-result
     'delete (constantly nil)})

(defn- parent-list [route]  (compact [(:parent route) (:element route)]))

(defn- spec-name [routespec]
  (let [spec (:route-spec routespec)]
    (if (symbol? spec)
      nil
      (last spec)))
  )

(defn path-list-of [routespec idlist]
  (let [parents (->> routespec :route parent-list vec)]
    (interleave+ (conj? parents (spec-name routespec)) idlist)))


(defn number-of-arguments [routespec]
  (let [route (:route-spec routespec)
        has-id? (or (#{'show 'update 'delete} route)
                    (and (seq? route) (= (count route) 3)))]
    (count (filter boolean [has-id? (->> routespec :route :parent)]))))

(def base-path-method-map
  {'list 'get
   'show 'get
   'create 'post
   'update 'put
   'delete 'delete})

(def crud-routes (keys base-path-method-map))

(defn- extract-routes [route spec]
  (if (= 'crud spec)
    (map #(args-to-map [:route-spec % :route route]) crud-routes)
    [(args-to-map [:route-spec spec :route route])]
  ))


(defn route-specs [route] (mapcat (partial extract-routes route) (:routes route)))

(defn- get-simplify-function [routespec]
  (if (list? (:route-spec routespec))
    simplify-any
    (simplify-case (:route-spec routespec))))

(defn- get-method-of [routespec]
  (let [spec (:route-spec routespec)]
    (if (symbol? spec)
      (base-path-method-map spec)
      (first spec))))

(defn pd-api [routespec argslist]
  (let [simplify-fn (get-simplify-function routespec)
        method (get-method-of routespec)
        [ids kvs] (split-at (number-of-arguments routespec) argslist )
        path-list (path-list-of routespec ids)
        ]
    (simplify-fn path-list (pdrequest method path-list kvs))))

(defn pd-event-api [type argslist] (generic-pdrequest 'post events-api-endpoint (concat argslist [:event_type type])))

(defn grab
  "Helper from grabing a few keys from json output. Works if json is an array or an object
  Example:
   (grab (users) :name :id :email)
   (grab (user \"PY8J5YX\") :email )
  "
  [json & args]
  (if (map? json)
    (select-keys json args)
    (map #(select-keys % args) json)))

(defn complex? [expr] (some vector? expr))

(defn dsl-node [element parent routes]
  {:element element :parent parent :routes routes})

; Helper function for parsing the dsl above on def pd
(defn linearize
  ([expr] (linearize expr nil) )
  ([expr parent]
    (if (complex? expr)
      (let [[subtress finalexpression] (partition-with vector? expr)
            self (first finalexpression)]
        (cons (dsl-node self parent (rest-vec finalexpression)) (mapcat #(linearize % self) subtress)))
      [(dsl-node (first expr) parent (rest-vec expr) )])))


(defn- symbol-route-to-function-name [routespec]
  (let [base-path-suffix {'list "s"
         'show ""
         'create "-new"
         'update "-update"
         'delete "-delete"}
         base (->> routespec :route :element name singularize)
        suffix (->> routespec :route-spec base-path-suffix)]
    (str base suffix)))

(defn- any-route-to-function-name [routespec]
  (let [base (->> routespec :route :element name singularize)
        suffix (->> routespec :route-spec last)
        plural-str (if (= (->> routespec :route-spec count) 3) "" "s")
        ]
    (str base plural-str "-" suffix)))


(defn- normalize-name [str] (if (= str "log-entrie")  "log-entry" str ))

(defn- route-to-function-name [routespec]
   (->> (if (list? (:route-spec routespec))
    (any-route-to-function-name routespec)
    (symbol-route-to-function-name routespec)) dasherize normalize-name symbol))


(defmacro define-pd-api [routespec]
  `(defn ~(route-to-function-name routespec) [& args#] (pd-api (quote ~routespec) args#)))


; Create the methods
(defmacro defineall [form]
  (cons `do
    (conj (map (fn [routespec] `(define-pd-api ~routespec))
            (mapcat route-specs (mapcat linearize form)))
      `(def pd-routes (quote ~form)))))

(defineall (
             [incidents list update show (get count) (get :id log_entries)
              [notes list create]]
             [escalation_policies crud (get on_call)]
             [alerts list]
             [reports (get alerts_per_time) (get incidents_per_time)]
             [schedules crud (get :id users ) (post preview) (get :id entries)
              [overrides list create delete]]
             [users crud (get :id log_entries) (get :id on_call) (get on_call)
              [contact_methods crud]
              [notification_rules crud]]
             [log_entries list show]
             [services crud (put :id disable) (put :id enable) (post :id regenerate_key)
              [email-filters create update delete]]
             [maintenance_windows crud]
             ))

(defn event-trigger [& args] (pd-event-api :trigger args))
(defn event-ack [& args] (pd-event-api :acknowledge args))
(defn event-resolve [& args] (pd-event-api :resolve args))

;; doc helper.
(defn printroutes []
  (let [vars (mapcat route-specs (mapcat linearize pd-routes))]
    (doseq [x vars]  (println (get-method-of x)) (prn (route-to-function-name x)) (prn x) (newline))))

