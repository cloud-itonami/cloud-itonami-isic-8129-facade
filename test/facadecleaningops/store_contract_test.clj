(ns facadecleaningops.store-contract-test
  "Contract tests for `facadecleaningops.store/Store` protocol (MemStore)."
  (:require [clojure.test :refer [deftest is testing]]
            [facadecleaningops.store :as store]))

(deftest mem-store-site-lookup
  (testing "MemStore can store and retrieve sites by ID (string keys)"
    (let [sites {"s1" {:site-id "s1" :name "Alice's Facade Cleaning Site" :registered? true :verified? true :owner-consent? true}}
          s (store/mem-store sites)]
      (is (some? (store/site s "s1")))
      (is (nil? (store/site s "s99"))))))

(deftest mem-store-all-sites
  (testing "MemStore returns all sites in sorted order"
    (let [sites {"s2" {:site-id "s2" :name "Bob's Street Frontage"}
                 "s1" {:site-id "s1" :name "Alice's Facade Cleaning Site"}
                 "s3" {:site-id "s3" :name "Carol's Office Tower"}}
          s (store/mem-store sites)
          all-s (store/all-sites s)]
      (is (= 3 (count all-s)))
      (is (= "s1" (:site-id (first all-s))))
      (is (= "s3" (:site-id (last all-s)))))))

(deftest mem-store-zone-lookup
  (testing "MemStore can store and retrieve deployment-zones by ID (string keys), as pure data"
    (let [zones {"z1" {:zone/id "z1" :zone/name "Sample zone" :zone/status :active}}
          s (store/mem-store {} zones)]
      (is (some? (store/zone s "z1")))
      (is (nil? (store/zone s "z99"))))))

(deftest mem-store-all-zones
  (testing "MemStore returns all zones in sorted order"
    (let [zones {"z2" {:zone/id "z2" :zone/name "Second zone"}
                 "z1" {:zone/id "z1" :zone/name "First zone"}}
          s (store/mem-store {} zones)
          all-z (store/all-zones s)]
      (is (= 2 (count all-z)))
      (is (= "z1" (:zone/id (first all-z)))))))

(deftest mem-store-sds-lookup
  (testing "MemStore can store and retrieve SDS records by fluid-id (string keys)"
    (let [sds {"f1" {:fluid-id "f1" :name "Sample fluid" :registered? true :verified? true :verified-surfaces #{"glass"}}}
          s (store/mem-store {} {} sds)]
      (is (some? (store/sds s "f1")))
      (is (nil? (store/sds s "f99"))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-dispatch-log
  (testing "MemStore commit-record! appends to the dispatch/completion log"
    (let [s (store/mem-store {})
          record {:op :cleaning/log-completion :site-id "s1" :zone-id "z1" :value {:evidence ["photo-1"]}}]
      (is (= 0 (count (store/all-dispatch-records s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/all-dispatch-records s))))
      (is (= record (first (store/all-dispatch-records s)))))))

(deftest mem-store-with-sites-zones-sds
  (testing "MemStore with-sites/with-zones/with-sds replace their directories"
    (let [s (store/mem-store {})]
      (is (= 0 (count (store/all-sites s))))
      (store/with-sites s {"s1" {:site-id "s1"}})
      (is (= 1 (count (store/all-sites s))))
      (is (= 0 (count (store/all-zones s))))
      (store/with-zones s {"z1" {:zone/id "z1"}})
      (is (= 1 (count (store/all-zones s))))
      (is (= 0 (count (store/all-sds s))))
      (store/with-sds s {"f1" {:fluid-id "f1"}})
      (is (= 1 (count (store/all-sds s)))))))

(deftest mem-store-set-zone-and-set-site-mutate-in-place
  (testing "set-zone!/set-site! mutate a single record without touching the rest of the directory -- the mechanism actor_test.clj uses to simulate a fact changing during a human-review window"
    (let [s (store/mem-store {"s1" {:site-id "s1" :verified? true}}
                              {"z1" {:zone/id "z1" :zone/status :active}})]
      (store/set-zone! s "z1" (assoc (store/zone s "z1") :zone/status :suspended))
      (is (= :suspended (:zone/status (store/zone s "z1"))))
      (store/set-site! s "s1" (assoc (store/site s "s1") :verified? false))
      (is (false? (:verified? (store/site s "s1")))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo sites/zones/sds, including the motivating Shibuya-ku zone"
    (let [s (store/seed-db)]
      (is (> (count (store/all-sites s)) 0))
      (is (some? (store/site s "site-1")))
      (is (some? (store/site s "site-3")))
      (is (> (count (store/all-zones s)) 0))
      (is (some? (store/zone s "shibuya-ku-row")))
      (is (= :active (:zone/status (store/zone s "shibuya-ku-row"))))
      (is (> (count (store/all-sds s)) 0))
      (is (some? (store/sds s "fluid-1"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for site-id/zone-id/fluid-id"
    (let [demo (store/demo-data)]
      (doseq [[k v] (:sites demo)]
        (is (string? k) "site keys must be strings")
        (is (= k (:site-id v)) "key must match site-id"))
      (doseq [[k v] (:zones demo)]
        (is (string? k) "zone keys must be strings")
        (is (= k (:zone/id v)) "key must match zone/id"))
      (doseq [[k v] (:sds demo)]
        (is (string? k) "sds keys must be strings")
        (is (= k (:fluid-id v)) "key must match fluid-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
