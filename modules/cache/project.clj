(defproject org.immutant/immutant-cache-module "1.0.3-SNAPSHOT"
  :parent [org.immutant/immutant-modules-parent _ :relative-path "../pom.xml"]
  :plugins [[lein-modules "0.1.0"]
            [org.immutant/build-plugin "0.1.0-SNAPSHOT"]]

  :profiles {:provided
             {:dependencies [[org.immutant/immutant-core-module _]
                             [org.immutant/immutant-common _]
                             [org.projectodd/polyglot-core _]
                             [org.projectodd/polyglot-cache _]
                             [org.infinispan/infinispan-core _]
                             [org.jboss.spec.javax.transaction/jboss-transaction-api_1.1_spec "1.0.1.Final"]]}}

  :dependencies [[org.clojure/core.memoize "0.5.5"]])
