(ns wordpress-pulumi-cljs.main
  (:require ["@pulumi/pulumi" :as pulumi]
            ["@pulumi/aws" :as aws]
            [pulumi-cljs.core :as p]
            [pulumi-cljs.aws :as aws-utils]
            [clojure.walk :as walk]))

; https://aws.amazon.com/blogs/containers/running-wordpress-amazon-ecs-fargate-ecs/

(defn- efs
  "Construct EFS filesystem & related resources"
  [provider]
  (let [efs (p/resource aws/efs.FileSystem "filesystem" provider
              {:encrypted true
               :throughputMode "provisioned"
               :provisionedThroughputInMibps 1}
              {:provider provider})
        sg (p/resource aws/ec2.SecurityGroup "filesystem" efs
             {:vpcId (p/cfg "vpc")
              :description "Filesystem mounts for EFS backing WordPress"})]
    (p/resource aws/efs.AccessPoint "access-point" efs
      {:fileSystemId (:id efs)
       :posixUser {:uid 1000 :gid 1000}
       :rootDirectory {:creationInfo {:ownerGid 1000
                                      :ownerUid 1000
                                      :permissions "0777"}
                       :path "/bitnami"}})
    (doseq [subnet (p/cfg-obj "private-subnets")]
      (p/resource aws/efs.MountTarget (str "mt-" subnet) efs
        {:fileSystemId (:id efs)
         :subnetId subnet
         :securityGroups [(:id sg)]}))
    {:filesystem efs
     :security-group sg}))

(defn- db
  "Construct RDS instance & related resources"
  [provider]
  (let [sg (p/resource aws/ec2.SecurityGroup "wordpress-db" provider
             {:vpcId (p/cfg "vpc")
              :description "RDS database access for WordPress"}
             {:provider provider})
        subnet-group (p/resource aws/rds.SubnetGroup "subnet-group" sg
                       {:namePrefix "wordpress-"
                        :subnetIds (p/cfg-obj "private-subnets")}
                       {:provider provider})
        pw (aws-utils/ssm-password sg "db-admin")
        rds (p/resource aws/rds.Cluster "wordpress" sg
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
    {:db rds
     :security-group sg}))

(defn- lb
  "Construct load balancer & related resources"
  [provider]
  (let [sg (p/resource aws/ec2.SecurityGroup "wordpress-alb" provider
             {:vpcId (p/cfg "vpc")
              :description "Public access to ALB"
              :ingress [{:description "Public HTTP access"
                         :protocol "tcp"
                         :fromPort 80
                         :toPort 80
                         :cidrBlocks ["0.0.0.0/0"]}]}
             {:provider provider})
        alb (p/resource aws/lb.LoadBalancer "wordpress" sg
              {:loadBalancerType "application"
               :securityGroups [(:id sg)]
               :subnets (p/cfg-obj "public-subnets")})
        target-group (p/resource aws/lb.TargetGroup "wordpress" alb
                       {:namePrefix "wp-"
                        :port 8080
                        :healthCheckPort 8080
                        :protocol "HTTP"
                        :targetType "ip"
                        :vpcId (p/cfg "vpc")})
        listener (p/resource aws/lb.Listener "wordpress" alb
                   {:loadBalancerArn (:arn alb)
                    :port 80
                    :defaultActions [{:type "forward"
                                      :targetGroupArn (:arn target-group)}]})]
    {:alb alb
     :target-group target-group}))

(defn ^:export stack
  "Create the Pulumi stack, returning its outputs"
  []
  (let [provider (p/resource aws/Provider. "provider" nil
                   {:region (.require (p/load-cfg "aws") "region")
                    :defaultTags {:tags {"pulumi:stack" (pulumi/getStack)
                                         "pulumi:project" (pulumi/getProject)}}})]
    (p/prepare-output {:efs (efs provider)
                       :db (db provider)
                       :lb (lb provider)
                       })))
