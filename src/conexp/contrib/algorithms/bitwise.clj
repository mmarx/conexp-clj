(ns #^{:doc "Algorithms which work with bits to improve performance."}
  conexp.contrib.algorithms.bitwise
  (:import [java.util BitSet])
  (:use [clojure.contrib.seq-utils :only (indexed)])
  (:use [conexp.fca.contexts :only (objects attributes incidence)]))

;;; Helpers to convert to and from BitSets

(defmacro deep-aget
  "Implements fast, type-hinted aget. From Christophe Grand."
  ([hint array idx]
    `(aget ~(vary-meta array assoc :tag hint) ~idx))
  ([hint array idx & idxs]
    `(let [a# (aget ~(vary-meta array assoc :tag 'objects) ~idx)]
       (deep-aget ~hint a# ~@idxs))))

(defmacro forall-in-bitset
  "Returns true iff body holds for all var in bitset."
  [bitset var & body]
  `(loop [~var (int (.nextSetBit ~bitset 0))]
     (cond
       (== -1 ~var)
       true,
       (not ~@body)
       false,
       :else
       (recur (.nextSetBit ~bitset (inc ~var))))))

(defmacro dobits
  ""
  [[var bitset] & body]
  `(let [#^BitSet bitset# ~bitset]
     (loop [~var (int (.nextSetBit bitset# 0))]
       (if (== -1 ~var)
	 nil
	 (do
	   ~@body
	   (recur (int (.nextSetBit bitset# (inc ~var)))))))))

(defn- to-bitset
  ""
  [element-vector hashset]
  (let [#^BitSet bs (BitSet. (count element-vector))]
    (doseq [pair (indexed element-vector)
	    :when (contains? hashset (second pair))]
      (.set bs (first pair)))
    bs))

(defn- to-hashset
  ""
  [element-vector #^BitSet bitset]
  (loop [pos (.nextSetBit bitset 0)
	 result #{}]
    (if (= -1 pos)
      result
      (recur (.nextSetBit bitset (inc pos))
	     (conj result (nth element-vector pos))))))

(defn- to-binary-matrix
  ""
  [object-vector attribute-vector incidence-relation]
  (let [incidence-matrix (make-array Integer/TYPE (count object-vector) (count attribute-vector))]
    (doseq [obj-idx (range (count object-vector))
	    att-idx (range (count attribute-vector))]
      (aset incidence-matrix obj-idx att-idx
	    (if (contains? incidence-relation [(nth object-vector obj-idx)
					       (nth attribute-vector att-idx)])
	      (int 1)
	      (int 0))))
    incidence-matrix))

(defn- bitwise-object-derivation
  ""
  [incidence-matrix object-count attribute-count #^BitSet bitset]
  (let [#^BitSet derived-attributes (BitSet. attribute-count)]
    (dotimes [att (int attribute-count)]
      (if (forall-in-bitset bitset obj (== 1 (deep-aget ints incidence-matrix obj att)))
	(.set derived-attributes att)))
    derived-attributes))

(defn- bitwise-attribute-derivation
  ""
  [incidence-matrix object-count attribute-count #^BitSet bitset]
  (let [#^BitSet derived-objects (BitSet. object-count)]
    (dotimes [obj (int object-count)]
      (if (forall-in-bitset bitset att (== 1 (deep-aget ints incidence-matrix obj att)))
	(.set derived-objects obj)))
    derived-objects))

;;; Concept Calculation

(defmulti concepts
  "Computes concepts with various algorithms, given as first argument."
  (fn [& args] (first args)))

;; NextClosure with BitSets (:next-closure)

(defn- lectic-<_i
  ""
  [base-count i #^BitSet A #^BitSet B]
  (and (.get B i)
       (not (.get A i))
       (loop [j (int (dec i))]
	 (cond
	   (< j 0)
	   true,
	   (not= (.get A j) (.get B j))
	   false,
	   :else
	   (recur (dec j))))))

(defn- oplus
  ""
  [base-count clop #^BitSet A i]
  (let [#^BitSet bs (BitSet. base-count)]
    (.set bs (int i) true)
    (dotimes [j i]
      (.set bs (int j) (.get A j)))
    (clop bs)))

(defn- next-closed-set
  ""
  [base-count clop #^BitSet A]
  (loop [i (dec (int base-count))]
    (cond
      (== -1 i)
      nil,
      (.get A i)
      (recur (dec i)),
      :else
      (let [A_i (oplus base-count clop A i)]
	(if (lectic-<_i base-count i A A_i)
	  A_i
	  (recur (dec i)))))))

(defmethod concepts :next-closure [_ context]
  (let [object-vector (vec (objects context))
	attribute-vector (vec (attributes context))
	incidence-matrix (to-binary-matrix object-vector attribute-vector (incidence context))

	object-count (count object-vector)
	attribute-count (count attribute-vector)

	attribute-prime (fn [bitset]
			  (bitwise-attribute-derivation incidence-matrix
							object-count
							attribute-count
							bitset))
	object-prime (fn [bitset]
		       (bitwise-object-derivation incidence-matrix
						  object-count
						  attribute-count
						  bitset))
	clop (fn [bitset]
	       (object-prime (attribute-prime bitset)))

	intents (take-while identity
			    (iterate #(next-closed-set attribute-count clop %) (BitSet.)))]
    (map (fn [bitset]
	   [(to-hashset object-vector (attribute-prime bitset))
	    (to-hashset attribute-vector bitset)])
	 intents)))


;; Krajca-Outrata-Vychodil (:vok)

(defn- create-structures-for-vok
  ""
  [context]
  (let [object-vector (vec (objects context))
	attribute-vector (vec (attributes context))

	object-count (count object-vector)
	attribute-count (count attribute-vector)

	incidence-matrix (to-binary-matrix object-vector attribute-vector (incidence context))

	rows (into-array (map (fn [y]
				(let [#^BitSet bs (BitSet.)]
				  (.set bs y)
				  (bitwise-attribute-derivation incidence-matrix
								object-count
								attribute-count
								bs)))
			      (range attribute-count)))]
    [object-vector attribute-vector
     object-count  attribute-count
     incidence-matrix  rows]))

(set! *warn-on-reflection* true)

(defn- compute-closure
  ""
  [object-count attribute-count incidence-matrix rows #^BitSet A #^BitSet B y]
  (let [#^BitSet C (BitSet.)
	#^BitSet D (BitSet.)
	#^BitSet E (BitSet.)
	y (int y)]
    (.set D 0 (int attribute-count))
    (.or E A)
    (.and E (aget #^objects rows y))
    (dobits [i E]
      (.set C i)
      (dotimes [j attribute-count]
	(if (== 0 (deep-aget ints incidence-matrix i j))
	  (.set D j false))))
    [C, D]))

(defn- generate-from
  ""
  [object-count attribute-count incidence-matrix rows #^BitSet A #^BitSet B y]
  (lazy-seq
    (cons [A, B]
	  (if (or (== attribute-count (.cardinality B))
		  (>= y attribute-count))
	    nil
	    (apply concat
		   (for [j (range y attribute-count)]
		     (when (not (.get B j))
		       (let [new-concept (compute-closure object-count
							  attribute-count
							  incidence-matrix
							  rows
							  A B j)
			     #^BitSet C (first new-concept)
			     #^BitSet D (second new-concept)
			     skip (loop [k 0]
				    (cond
				      (== k j)
				      false,
				      (not= (.get D k) (.get B k))
				      true,
				      :else
				      (recur (int (inc k)))))]
			 (when (not skip)
			   (generate-from object-count
					  attribute-count
					  incidence-matrix
					  rows
					  C D (inc j)))))))))))

(defmethod concepts :vok [_ context]
  (let [[object-vector attribute-vector
	 object-count attribute-count
	 incidence-matrix rows]
	(create-structures-for-vok context)

	empty-down (bitwise-attribute-derivation incidence-matrix
						 object-count
						 attribute-count
						 (BitSet.))
	empty-down-up (bitwise-object-derivation incidence-matrix
						 object-count
						 attribute-count
						 empty-down)]
    (map (fn [pair]
	   [(to-hashset object-vector (first pair))
	    (to-hashset attribute-vector (second pair))])
	 (generate-from object-count attribute-count incidence-matrix rows empty-down empty-down-up 0))))

;; In-Close (:in-close)

;;;

nil
