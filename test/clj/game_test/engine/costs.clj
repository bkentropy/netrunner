(ns game-test.engine.costs
  (:require [game.core :as core]
            [game-test.core :refer :all]
            [game-test.utils :refer :all]
            [game-test.macros :refer :all]
            [clojure.test :refer :all]))

(deftest merge-costs
  (testing "Non-damage costs"
    (testing "No defaults, already merged"
      (is (= [[:credit 1]] (core/merge-costs [[:credit 1]]))))
    (testing "Costs are already flattened"
      (is (= [[:credit 1] [:click 1]] (core/merge-costs [[:credit 1 :click 1]]))))
    (testing "Passed as a flattened vec"
      (is (= [[:credit 1]] (core/merge-costs [:credit 1]))))
    (testing "Default type is only element"
      (is (= [[:credit 1]] (core/merge-costs [[:credit]]))))
    (testing "Default plus explicit"
      (is (= [[:click 1] [:credit 1]] (core/merge-costs [[:click :credit 1]]))))
    (testing "Costs ending with defaults expand"
      (is (= [[:credit 1] [:click 1]] (core/merge-costs [[:credit 1 :click]]))))
    (testing "Non-damage costs aren't reordered"
      (is (not= [[:credit 1] [:click 1]] (core/merge-costs [[:click 1 :credit 1]]))))
    (testing "Costs with all defaults are expanded"
      (is (= [[:click 1] [:credit 1]] (core/merge-costs [[:click :credit]]))))
    (testing "Non-damage costs are combined"
      (is (= [[:click 4] [:credit 2]]
             (core/merge-costs [[:click 1] [:click 3] [:credit 1] [:credit 1]]))))
    (testing "Deeply nested costs are flattened"
      (is (= [[:click 3]] (core/merge-costs [[[[[:click 1]]] [[[[[:click 1]]]]]] :click 1])))))
  (testing "Damage costs"
    (testing "Damage costs are moved to the end"
      (is (= [[:credit 1] [:net 1]] (core/merge-costs [[:net 1 :credit 1]]))))
    (testing "Damage isn't combined"
      (is (= [[:net 1] [:net 1]] (core/merge-costs [[:net 1 :net 1]]))))
    (testing "Net, meat, and brain damage are recognized"
      (is (= [[:net 1] [:meat 1] [:brain 1]]
             (core/merge-costs [[:net 1] [:meat 1] [:brain 1]]))))))
