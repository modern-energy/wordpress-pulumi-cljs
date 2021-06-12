(ns wordpress-pulumi-cljs.main
  (:require ["@pulumi/pulumi" :as pulumi]
            ["@pulumi/aws" :as aws]
            [pulumi-cljs.core :as p]
            [clojure.walk :as walk]))

(defn ^:export stack
  "Create the Pulumi stack, returning its outputs"
  []
  (p/resource aws/s3.Bucket "test-bucket" nil
    {}))
