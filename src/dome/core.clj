(ns dome.core
  (:use clojure.pprint)
  (:require [clojure.string :as str]))

;;; helpers

(defn round [f & [x]] (double (/ (Math/round (* f (or x 1e7))) (or x 1e7))))

(defn map-vals [f m]
  (->> m
       (map (fn [[k v]] [k (f v)]))
       (into {})))

(defn map-keys [f m]
  (->> m
       (map (fn [[k v]] [(f k) v]))
       (into {})))

(defn distinct-by
  "Returns elements of xs which return unique
   values according to f. If multiple elements of xs return the same
   value under f, the first is returned"
  [f xs]
  (let [s (java.util.HashSet.)]
    (for [x xs
          :let [id (f x)]
          :when (not (.contains s id))]
      (do (.add s id)
          x))))

;;; math

(defn sin [x] (Math/sin x))
(defn cos [x] (Math/cos x))
(defn tan [x] (Math/tan x))
(defn atan [x] (Math/atan x))
(defn sq [x] (Math/pow x 2))

(defn mean [coll]
  (let [sum (apply + coll)
        count (count coll)]
    (if (pos? count)
      (/ sum count)
      0)))

(defn ->cartesian
  "theta and phi are in radians"
  [point]
  {:x (* (:radius point) (cos (:theta point)))
   :y (* (:radius point) (sin (:theta point)) (sin (:phi point)))
   :z (* (:radius point) (sin (:theta point)) (cos (:phi point)))})

;;; geometry

(defn distance [point1 point2]
  (Math/sqrt
   (- (+ (sq (:radius point1)) (sq (:radius point2)))
      (* 2 (:radius point1) (:radius point2)
         (+ (* (cos (:theta point1))
               (cos (:theta point2)))
            (* (sin (:theta point1))
               (sin (:theta point2))
               (cos (- (:phi point1) (:phi point2)))))))))

;; radius of an ellipse at theta, with radii r1 r2
(defn ->radius [r1 r2 theta]
  (let [e2 (sq (/ r1 r2))]
    (* r2 (Math/sqrt (/ e2 (+ (* e2 (sq (sin theta))) (sq (cos theta))))))))

;; from the table
(defn ->icosa-triangle2-angles [[a b] freq]
  (let [[p t]
        (case [freq [a b]]
          ([2 [3 1]] [4 [6 2]]) [18. 90.]
          ([2 [3 2]] [4 [6 4]]) [54. 90.]
          ([2 [4 2]] [4 [8 4]]) [36. 116.5650512]
          [3 [4 1]] [11.8185857 80.1168545]
          [3 [4 2]] [36.0 79.1976831]
          [3 [4 3]] [60.1814143 80.1168545]
          [3 [5 2]] [24.1814143 99.8831455]
          [3 [5 3]] [47.8185858 99.8831455]
          [3 [6 3]] [36.0 116.5650512]
          [4 [5 1]] [8.7723551 75.4545635]
          [4 [5 2]] [26.2676986 73.9549430]
          [4 [5 3]] [45.7323015 73.9549430]
          [4 [5 4]] [63.2276449 75.4545635]
          [4 [6 3]] [36. 90.]
          [4 [7 3]] [27.2276449 104.5454366]
          [4 [7 4]] [44.7723551 104.5454366]
          [5 [6 1]] [6.9684505 72.7969533]
          [5 [6 2]] [20.4898913 71.1600132]
          [5 [6 3]] [36. 70.5008455]
          [5 [6 4]] [51.5101087 71.1600132]
          [5 [6 5]] [65.0315495 72.7969533]
          [5 [7 2]] [14.2819224 84.0101625]
          [5 [7 3]] [28.4369994 83.6117970]
          [5 [7 4]] [43.5630006 83.6117970]
          [5 [7 5]] [57.7180776 84.0101625]
          [5 [8 3]] [21.7180776 95.9898376]
          [5 [8 4]] [36. 96.1794105]
          [5 [8 5]] [50.2819224 95.9898376]
          [5 [9 4]] [29.0315495 107.2030468]
          [5 [9 5]] [42.9484505 107.2030468]
          [5 [10 5]] [36. 116.5650512])]
    {:phi (Math/toRadians p) :theta (Math/toRadians t)}))

;;; domes!

(defn points-2d [freq & [icosa-ellipse?]]
  (for [x (range 0 (inc (* (if icosa-ellipse? 2 1) freq)))
        y (range 0 (inc freq))
        :when (and (>= x y) (<= (- x y) freq))]
    [x y]))

(defn neighbors-2d [point points]
  (for [p1 [point]
        p2 points
        :when (and (not= [p1 p2] (sort [p1 p2]))
                   (every? #(<= 0 (- (nth p1 %) (nth p2 %)) 1) [0 1]))]
    p2))

(defn ->3d [[a b] freq]
  (let [x b
        y (- a b)
        z (- freq x y)]
    [x y z]))

(defn ->angles-2d [[a b] freq & [icosa-ellipse?]]
  (if (> a freq)
    (do (assert icosa-ellipse?)
        (->icosa-triangle2-angles [a b] freq))
    (let [[x y z] (->3d [a b] freq)
          x1 (if icosa-ellipse? (* x (sin (Math/toRadians 72))) x)
          y1 (if icosa-ellipse? (+ y (* x (cos (Math/toRadians 72)))) y)
          z1 (if icosa-ellipse? (+ (/ freq 2) (/ z (* 2 (cos (Math/toRadians 36))))) z)]
      {:phi (cond (zero? x1) 0 (zero? y1) (Math/toRadians (if icosa-ellipse? 72 90)) :else (atan (/ x1 y1)))
       :theta (if (zero? z1) (Math/toRadians (if icosa-ellipse? 72 90)) (atan (/ (Math/sqrt (+ (sq x1) (sq y1))) z1)))})))

(defn add-radius [angles r1 r2]
  (assoc angles :radius (->radius r1 r2 (:theta angles))))

(defn project-point [point freq r1 r2 & [icosa-ellipse?]]
  (-> (->angles-2d point freq icosa-ellipse?)
      (add-radius r1 r2)
      (assoc :point-2d point :point-3d (->3d point freq))))

(defn struts [freq r1 r2 & [icosa-ellipse?]]
  (let [points (points-2d freq icosa-ellipse?)]
    (for [point points
          neighbor (neighbors-2d point points)
          :let [p1 (project-point point freq r1 r2 icosa-ellipse?)
                p2 (project-point neighbor freq r1 r2 icosa-ellipse?)]]
      [p1 p2 (distance p1 p2)])))

(defn ->display-format [struts icosa? outfile]
  (spit outfile
        (clojure.string/join "\n"
                             (concat
                              ["model = Sketchup.active_model" "entities = model.active_entities"]
                              (for [[p1 p2] struts
                                    [delta-phi m] #_[[0 1] [71 1] [144 1]]  (if icosa?
                                                                              [[0 1] [72 1] [144 1] [216 1] [288 1] [0 -1] [72 -1] [144 -1] [216 -1] [288 -1]]
                                                                              [[-90 1] [0 1] [90 -1] [180 -1]])
                                    :let [c1 (->cartesian (update-in p1 [:phi] + (Math/toRadians delta-phi)))
                                          c2 (->cartesian (update-in p2 [:phi] + (Math/toRadians delta-phi)))]]
                                (format "entities.add_line [%s, %s, %s], [%s, %s, %s]"
                                        (* m (:x c1)) (:y c1) (* m (:z c1))
                                        (* m (:x c2)) (:y c2) (* m (:z c2))))))))

;; combining struts that are similar in length
(defn within-difference? [margin cluster]
  (let [min-x (apply min cluster)
        max-x (apply max cluster)]
    (< (- max-x min-x) margin)))

(defn consolidate-struts-mapping [margin lengths]
  (let [clusters (loop [clusters (->> lengths
                                      distinct
                                      (map (fn [x] [x])))]
                   (let [[c1 c2] (first (for [c1 clusters
                                              c2 clusters
                                              :when (and (not= c1 c2)
                                                         (within-difference? margin (concat c1 c2)))]
                                          [c1 c2]))]
                     (if c1
                       (recur (->> clusters (remove #{c1 c2}) (cons (concat c1 c2))))
                       clusters)))]
    (into {}
          (for [cluster clusters
                :let [new-strut (round (mean cluster))]
                strut cluster]
            [strut new-strut]))))

;; deciding how to cut conduit
(defn find-best-cuts [all-struts]
  (assert (every? #(<= (:length %) 10) all-struts))
  (let [add (fn [cuts curr]
              (->> curr
                   (map #(dissoc % :count))
                   (conj cuts)))]
    (loop [cuts []
           curr #{}
           remaining all-struts]
      (if-not (seq remaining)
        (if (seq curr) (add cuts curr) cuts)
        (if-let [to-add (->> remaining
                             (filter (fn [{l :length c :count}]
                                       (and (> c 0) (< l (- 10 (apply + 0 (map :length curr)))))))
                             (sort-by :length)
                             last)]
          (recur cuts
                 (conj curr to-add)
                 (keep (fn [strut]
                         (if (= strut to-add)
                           (when (> (:count strut) 1)
                             (update-in strut [:count] dec))
                           strut))
                       remaining))
          (recur (add cuts curr)
                 #{}
                 remaining))))))

;; for 5v icosa

(defn strut-count-dev [[_ [x1 y1] [x2 y2]] freq]
  (let [odd (odd? freq)
        m2 (int (/ freq 2))
        m1 (inc m2)]
    (+ (if (every? #{m1 m2} [y1 y2]) 1 0)
       (if (every? #{m1 m2} [x1 x2]) 1 0)
       (if (every? #{m1 m2} [(- x1 y1) (- x2 y2)]) 1 0))))

(defn dont-count-strut? [[_ [x1 y1] [x2 y2]] freq]
  (let [odd (odd? freq)
        m2 (/ freq 2)
        m1 (inc m2)]
    (or (and (= x1 y1) (= x2 y2))
        (= freq y1 y2))))

(defn strut-count [freq strut]
  (when-not (dont-count-strut? strut freq)
    (+ 5 (strut-count-dev strut freq))))

(defn struts-count [freq struts]
  (apply + (keep (partial strut-count freq) struts)))

(defn consolidate-struts [the-struts consolidated-strut-mapping freq]
  (->> the-struts
       (map (fn [[s1 s2 length]]
              [(-> [(:point-2d s1) (:point-2d s2)] sort (conj (round length)))
               (get consolidated-strut-mapping length)]))
       (group-by second)
       (sort-by first)
       (map-indexed (fn [i [k v]]
                      (let [struts (map first v)]
                        {:id i
                         :length k
                         :count (struts-count freq struts)
                         :struts struts})))))

(defn icosa-struts [freq smaller-radius extra-struts extra-length margin]
  (let [the-struts (struts freq smaller-radius (* 1.618034 smaller-radius) #_smaller-radius true)
        consolidated-strut-mapping (consolidate-struts-mapping margin (map last the-struts))
        consolidated-struts (consolidate-struts the-struts consolidated-strut-mapping freq)
        best-cuts (find-best-cuts (map #(-> %
                                            (dissoc :struts)
                                            (update-in [:length] + extra-length)
                                            (update-in [:count] + extra-struts))
                                       consolidated-struts))]
    {:struts consolidated-struts
     :max-strut-length (->> the-struts (map last) distinct sort last)
     :cuts best-cuts
     :diff-from-old-big-dome (let [old (* 20 20 Math/PI)]
                               (/ (- (* smaller-radius smaller-radius 1.618034 Math/PI) old) old))}))

(defn summarize-options [& [extra-struts extra-length margin freq]]
  (pprint
   (for [i (range 16 17)]
     (let [{:keys [struts cuts diff-from-old-big-dome max-strut-length]} (icosa-struts (or freq 5) i (or extra-struts 1) (or extra-length 0.2) (or margin (/ 0.125 12.0)))
           no-extra-struts (icosa-struts (or freq 5) i 0 (or extra-length 0.3) (or margin 0.0075))
           conduit-length (->> no-extra-struts :cuts (mapcat (partial map :length)) (apply +))]
       {:r i
        :num-conduit-pieces-to-buy (count cuts)
        :num-struts (->> no-extra-struts :cuts (map count) (apply +))
        :num-struts-with-spares (->> cuts (map count) (apply +))
        :num-distinct-lengths (count struts)
        :max-strut-length max-strut-length
        :total-conduit-length-diff (let [old 2400.] (/ (- conduit-length old) old))
        :area-diff diff-from-old-big-dome
        #_#_:struts (sort-by first (map (juxt :length :count) struts))}))))

(defn print-cuts [cuts]
  (->> cuts
       frequencies
       (map #(->> %
                  key
                  (map (fn [strut] (format "%s,%s" (:id strut) (:length strut))))
                  (str/join ",")
                  (format "%s,%s" (val %))))
       (str/join "\n")))

(defn print-struts [struts]
  (->> struts
       (sort-by :id)
       (map #(str/join "," ((juxt :id :length :count) %)))
       (str/join "\n")))

;;; OUR MOTHERFUCKING DOME!!!!!

(defn the-shady-waffle-dome [& [file-base]]
  (let [{:keys [struts cuts]} (icosa-struts 5 16 1 0.2 (/ 0.125 12.0))
        no-extra-struts (icosa-struts 5 16 0 0.3 0.0075)
        conduit-length (->> no-extra-struts :cuts (mapcat (partial map :length)) (apply +))
        cut-filename (format "%s-cuts.csv" file-base)
        strut-filename (format "%s-struts.csv" file-base)]
    (when file-base
      (spit cut-filename (print-cuts cuts))
      (spit strut-filename (print-struts struts)))
    (merge
     {:radius-1 16
      :radius-2 (* 16 1.618034)
      :num-conduit-pieces-to-buy (count cuts)
      :num-struts (->> no-extra-struts :cuts (map count) (apply +))
      :num-spares 1
      :num-distinct-lengths (count struts)
      :struts struts
      :cuts cuts}
     (when file-base
       {:struts-file strut-filename
        :cuts-filen cut-filename}))))
