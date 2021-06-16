(ns wordpress-pulumi-cljs.main
  (:require ["@pulumi/pulumi" :as pulumi]
            ["@pulumi/aws" :as aws]
            [pulumi-cljs.core :as p]
            [pulumi-cljs.aws :as aws-utils]
            [pulumi-cljs.aws.ecs :as ecs]
            [clojure.walk :as walk]))

; https://aws.amazon.com/blogs/containers/running-wordpress-amazon-ecs-fargate-ecs/

(defn- efs
  "Construct EFS filesystem & related resources"
  [provider]
  (let [efs (p/resource aws/efs.FileSystem (p/id) provider
              {:encrypted true}
              {:provider provider})
        sg (p/resource aws/ec2.SecurityGroup (p/id "filesystem") efs
             {:vpcId (p/cfg "vpc")
              :description "Filesystem mounts for EFS backing WordPress"})
        ap (p/resource aws/efs.AccessPoint (p/id) efs
             {:fileSystemId (:id efs)
              :posixUser {:uid 1000 :gid 1000}
              :rootDirectory {:creationInfo {:ownerGid 1000
                                             :ownerUid 1000
                                             :permissions "0777"}
                              :path "/bitnami"}})]

    (doseq [subnet (p/cfg-obj "private-subnets")]
      (p/resource aws/efs.MountTarget (p/id subnet) efs
        {:fileSystemId (:id efs)
         :subnetId subnet
         :securityGroups [(:id sg)]}))
    {:filesystem efs
     :security-group sg
     :access-point ap}))

(defn- db
  "Construct RDS instance & related resources"
  [provider]
  (let [sg (p/resource aws/ec2.SecurityGroup (p/id "db") provider
             {:vpcId (p/cfg "vpc")
              :description "RDS database access for WordPress"}
             {:provider provider})
        subnet-group (p/resource aws/rds.SubnetGroup (p/id) sg
                       {:namePrefix (str (p/id) "-")
                        :subnetIds (p/cfg-obj "private-subnets")}
                       {:provider provider})
        pw (aws-utils/ssm-password sg "db-admin")
        cluster (p/resource aws/rds.Cluster (p/id) sg
                  {:clusterIdentifier (str (pulumi/getProject) "-" (pulumi/getStack))
                   :engine "aurora-mysql"
                   :engineMode "serverless"
                   :engineVersion "5.7.mysql_aurora.2.07.1"
                   :masterUsername "admin"
                   :masterPassword (:value pw)
                   :backupRetentionPeriod 35
                   :dbSubnetGroupName (:name subnet-group)
                   :databaseName "wordpress"
                   :port 3306
                   :vpcSecurityGroupIds [(:id sg)]
                   :skipFinalSnapshot (not (p/cfg "protect"))})]
    {:cluster cluster
     :password-param pw
     :security-group sg}))

(defn- ecs
  "Create the ECS cluster, task and associated resources"
  [provider efs db]
  (let [cfg-environment (for [[k v] (p/cfg-obj "wordpress-env" {})]
                          {:name k :value (str v)})
        container-definition {:name (p/id)
                              :image "bitnami/wordpress"
                              :portMappings [{:protocol "tcp" :containerPort 8080}]
                              :essential true
                              :mountPoints [{:containerPath "/bitnami/wordpress"
                                             :sourceVolume "wordpress"}]
                              :environment (concat
                                             cfg-environment
                                             [{:name "MARIADB_HOST" :value (-> db :cluster :endpoint)}
                                              {:name "WORDPRESS_DATABASE_USER" :value (-> db :cluster :masterUsername)}
                                              {:name "WORDPRESS_DATABASE_NAME" :value (-> db :cluster :databaseName)}
                                              {:name "PHP_MEMORY_LIMIT" :value "512M"}
                                              {:name "enabled" :value "false"}
                                              {:name "ALLOW_EMPTY_PASSWORD" :value "yes"}])
                              :secrets [{:name "WORDPRESS_DATABASE_PASSWORD"
                                         :valueFrom (-> db :password-param :arn)}]}
        volume-configuration {:name "wordpress"
                              :efsVolumeConfiguration
                              {:fileSystemId (-> efs :filesystem :id)
                               :transitEncryption "ENABLED"
                               :authorizationConfig {:accessPointId (-> efs :access-point :id)}}}
        cluster (p/resource aws/ecs.Cluster (p/id) provider
                  {:settings [{:name "containerInsights"
                               :value "enabled"}]}
                  {:provider provider})
        iam-stmts [(aws-utils/allow-stmt (-> db :password-param :arn)
                     ["ssm:GetParameters"])]]
    (let [service (ecs/service provider provider (p/id) {:vpc-id (p/cfg "vpc")
                                                         :zone (p/cfg "zone")
                                                         :subdomain (p/cfg "subdomain")
                                                         :certificate-arn (p/cfg "certificate-arn")
                                                         :container-port 8080
                                                         :cluster-id (:id cluster)
                                                         :lb {:ingress-cidrs (p/cfg-obj "ingress-cidrs")
                                                              :subnets (p/cfg-obj "public-subnets")}
                                                         :task {:container-definitions [container-definition]
                                                                :cpu 1024
                                                                :memory 3072
                                                                :volumes [volume-configuration]
                                                                :iam-statements iam-stmts
                                                                :subnets (p/cfg-obj "private-subnets")}})]
      (p/resource aws/ec2.SecurityGroupRule (p/id "fs-ingress") (:security-group efs)
        {:type "ingress"
         :protocol "tcp"
         :fromPort 2049
         :toPort 2049
         :securityGroupId (-> efs :security-group :id)
         :sourceSecurityGroupId (-> service :security-group :id)})
      (p/resource aws/ec2.SecurityGroupRule (p/id "db-ingress") (:security-group db)
        {:type "ingress"
         :protocol "tcp"
         :fromPort 3306
         :toPort 3306
         :securityGroupId (-> db :security-group :id)
         :sourceSecurityGroupId (-> service :security-group :id)})
      service)))

(defn ^:export stack
  "Create the Pulumi stack, returning its outputs"
  []
  (let [provider (p/resource aws/Provider. "provider" nil
                   {:region (.require (p/load-cfg "aws") "region")
                    :defaultTags {:tags {"pulumi:stack" (pulumi/getStack)
                                         "pulumi:project" (pulumi/getProject)}}})
        efs (efs provider)
        db (db provider)
        ]
    (p/prepare-output {:efs efs
                       :db db
                       :ecs (ecs provider efs db)})))
