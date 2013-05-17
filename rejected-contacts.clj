;; query for all apps that were not selected in mw21
(def rejapps (sf/query conn exhibit_application__c [id contact__c] [[exhibit__r.slug__c = "mw21"] [status__c != "Selected"]]))
(def rej-contact-ids (map :contact__c rejapps))

;; assuming batches of 200
(def batch1 (take 200 rej-contact-ids))
(def batch2 (drop 200 rej-contact-ids))

;; create update objects
(def update-objects-1 (map #(hash-map :id % :mw21_rejected__c true) batch1))
(def update-objects-2 (map #(hash-map :id % :mw21_rejected__c true) batch2))

;; execute updates
(def results-batch-1 (into [] (sf/update conn Contact update-objects-1)))
(def results-batch-2 (into [] (sf/update conn Contact update-objects-2)))

;; check results
(map #(.getSuccess %) results-batch-1)
(map #(.getSuccess %) results-batch-2)
