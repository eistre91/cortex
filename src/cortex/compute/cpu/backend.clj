(ns cortex.compute.cpu.backend
  (:require
    [clojure.core.matrix :as m]
    [clojure.core.matrix.protocols :as mp]
    [clojure.core.matrix.macros :refer [c-for]]
    [think.datatype.core :refer [v-aget v-aset v-alength] :as dtype]
    [cortex.graph :as graph]
    [cortex.nn.layers :as layers]
    [cortex.nn.impl :as impl]
    [cortex.compute.driver :as drv]
    [cortex.compute.math :as math]
    [cortex.compute.cpu.driver :as cpu-drv]
    [cortex.compute.nn.layers :as compute-layers]
    [cortex.compute.nn.protocols :as compute-protocols]
    [cortex.compute.nn.backend :as nn-backend]
    [think.resource.core :as resource]
    [cortex.compute.cpu.tensor-math]
    [think.parallel.core :as parallel])
  (:import
    [java.util Arrays]
    [java.util.concurrent ForkJoinPool Callable Future]
    [java.nio DoubleBuffer FloatBuffer]
    [think.datatype ArrayView DoubleArrayView FloatArrayView]
    [cortex.compute.cpu.driver CPUDriver CPUStream]
    [cortex.compute.math DeviceArray Tensor]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defrecord CPUBackend [type device stream datatype resource-context]
  dtype/PDatatype
  (get-datatype [backend] (.datatype backend))
  drv/PDriverProvider
  (get-driver [backend] (drv/get-driver (.device backend)))
  drv/PDeviceProvider
  (get-device [backend] (.device backend))
  drv/PStreamProvider
  (get-stream [backend] (.stream backend))
  resource/PResource
  (release-resource [backend]
    (drv/unsafe-with-compute-device
     (.device backend)
     (resource/release-resource-context @(get backend :resource-context)))))


(defn backend
  [& {:keys [datatype driver device stream]}]
  (let [datatype (or datatype :double)
        driver (or driver (cpu-drv/driver))
        device (or device (drv/default-device driver))]
    (drv/unsafe-with-compute-device
     device
     (let [stream (or stream (drv/create-stream))
           [backend res-ctx]
           (resource/return-resource-context
            (->CPUBackend :cpu device stream datatype (atom nil)))]
       (reset! (get backend :resource-context) res-ctx)
       (resource/track backend)))))


(defprotocol PCPUNetworkImpl
  "Implementation of various functions based on buffer datatype."
  (cpu-fill [buffer value])
  (cpu-max-pooling-forward [input output conv-config])
  (cpu-max-pooling-backward [input output input-gradient output-gradient conv-config])
  (cpu-avg-pooling-forward [input output conv-config])
  (cpu-avg-pooling-backward [input output input-gradient output-gradient conv-config])
  (cpu-avg-exc-pad-pooling-forward [input output conv-config])
  (cpu-avg-exc-pad-pooling-backward [input output input-gradient output-gradient conv-config])
  (cpu-prepare-bernoulli-dropout [mult-buffer rand-buffer probability])
  (cpu-prepare-gaussian-dropout [mult-buffer rand-buffer])
  (cpu-lrn-forward [input output input-tensor n k alpha beta])
  (cpu-lrn-backward [input output-gradient input-gradient input-tensor n k alpha beta]))



(defmacro cpu-act-forward-impl
  [act-type input output cast-fn]
  `(let [src# (ArrayView/toView ~input)
         dest# (ArrayView/toView ~output)
         n-elems# (.length src#)
         val-0# (~cast-fn 0)
         val-1# (~cast-fn 1)]
     (cond
       (= ~act-type :logistic)
       (c-for [idx# 0 (< idx# n-elems#) (inc idx#)]
              (v-aset dest# idx#
                    (/ val-1#
                       (+ val-1# (Math/exp (- (v-aget src# idx#)))))))
       (= ~act-type :relu)
       (c-for [idx# 0 (< idx# n-elems#) (inc idx#)]
              (v-aset dest# idx#
                    (Math/max (v-aget src# idx#) val-0#)))
       (= ~act-type :tanh)
       (c-for [idx# 0 (< idx# n-elems#) (inc idx#)]
              (v-aset dest# idx#
                    (Math/tanh (v-aget src# idx#)))))))

(defmacro cpu-act-backward-impl
  [act-type input output output-gradient input-gradient cast-fn]
  `(let [src# (ArrayView/toView ~input)
         dest# (ArrayView/toView ~output)
         src-grad# (ArrayView/toView ~input-gradient)
         dest-grad# (ArrayView/toView ~output-gradient)
         n-elems# (.length src#)
         val-1# (~cast-fn 1)
         val-0# (~cast-fn 0)]
     (cond
       (= ~act-type :logistic)
       ;; input gradient = output * (1 - output) * output-gradient
       (c-for [idx# 0 (< idx# n-elems#) (inc idx#)]
              (let [out-val# (v-aget dest# idx#)]
                (v-aset src-grad# idx#
                        (* out-val#
                           (- val-1# out-val#)
                           (v-aget dest-grad# idx#)))))
       (= ~act-type :relu)
       (c-for [idx# 0 (< idx# n-elems#) (inc idx#)]
              (let [mult# (~cast-fn (if (> (v-aget src# idx#)
                                           val-0#)
                                      1
                                      0))]
                (v-aset src-grad# idx#
                      (* mult# (v-aget dest-grad# idx#)))))
       (= ~act-type :tanh)
       (c-for [idx# 0 (< idx# n-elems#) (inc idx#)]
              (let [out-val# (v-aget dest# idx#)]
                (v-aset src-grad# idx#
                      (* (- val-1#
                            (* out-val# out-val#))
                         (v-aget dest-grad# idx#))))))))


(defmacro array-max
  [ary n-items start-idx cast-fn]
  `(loop [idx# 1
          max-val# (v-aget ~ary ~start-idx)]
     (if (< idx# ~n-items)
       (recur (inc idx#)
              (Math/max (~cast-fn max-val#) (v-aget ~ary (+ ~start-idx idx#))))
       max-val#)))


(defmacro array-sum
  [ary n-items start-idx]
  `(loop [idx# 1
          sum-val# (v-aget ~ary ~start-idx)]
     (if (< idx# ~n-items)
       (recur (inc idx#)
              (+ sum-val# (v-aget ~ary (+ ~start-idx idx#))))
       sum-val#)))


(defmacro cpu-max-pooling-forward-impl
  [input output config cast-fn]
  `(let [input-ary# (ArrayView/toView ~input)
         output-ary# (ArrayView/toView ~output)]
     (impl/convolution-outer-kernel
      ~config :pooling
      (impl/convolution-roll-unroll-inner-kernel
       (let [input-val# (~cast-fn (if ~'input-valid?
                                  (v-aget input-ary# ~'input-addr)
                                  0.0))
             output-addr# (+ (* ~'out-y ~'output-width)
                             ~'out-x
                             ~'chan-output-offset)
             k-idx# (+ (* ~'k-y ~'kernel-width) ~'k-x)
             output-val# (v-aget output-ary# output-addr#)]
         (when (or (= 0 k-idx#)
                   (> input-val# output-val#))
           (v-aset output-ary# output-addr# input-val#)))))))


(defmacro cpu-max-pooling-backward-impl
  [input output input-gradient output-gradient config cast-fn]
  `(let [input-ary# (ArrayView/toView ~input)
         output-ary# (ArrayView/toView ~output)
         input-gradient-ary# (ArrayView/toView ~input-gradient)
         output-gradient-ary# (ArrayView/toView ~output-gradient)]
     (impl/convolution-outer-kernel
      ~config :pooling
      (impl/convolution-roll-unroll-inner-kernel
       (let [input-addr# ~'input-addr
             input-val# (~cast-fn (if ~'input-valid?
                                  (v-aget input-ary# input-addr#)
                                  0.0))
             output-addr# (+ (* ~'out-y ~'output-width)
                             ~'out-x
                             ~'chan-output-offset)
             k-idx# (+ (* ~'k-y ~'kernel-width) ~'k-x)
             output-val# (v-aget output-ary# output-addr#)]
         (when (= input-val# output-val#)
           (v-aset input-gradient-ary# input-addr#
                 (+ (v-aget input-gradient-ary# input-addr#)
                    (v-aget output-gradient-ary# output-addr#)))))))))

(defmacro cpu-avg-pooling-forward-impl
  [input output config cast-fn]
  `(let [input-ary# (ArrayView/toView ~input)
         output-ary# (ArrayView/toView ~output)]
     (impl/convolution-outer-kernel
      ~config :pooling
      (impl/convolution-roll-unroll-inner-kernel
       (let [input-val# (~cast-fn (if ~'input-valid?
                                  (v-aget input-ary# ~'input-addr)
                                  0.0))
             output-addr# (+ (* ~'out-y ~'output-width)
                             ~'out-x
                             ~'chan-output-offset)
             k-idx# (+ (* ~'k-y ~'kernel-width) ~'k-x)
             output-val# (~cast-fn (if (= 0 k-idx#)
                                     0
                                     (v-aget output-ary# output-addr#)))]
         (v-aset output-ary# output-addr#
                 (+ output-val#
                    (/ input-val#
                       ~'kernel-num-elems))))))))

(defmacro cpu-avg-pooling-backward-impl
  [input output input-gradient output-gradient config cast-fn]
  `(let [input-ary# (ArrayView/toView ~input)
         output-ary# (ArrayView/toView ~output)
         input-gradient-ary# (ArrayView/toView ~input-gradient)
         output-gradient-ary# (ArrayView/toView ~output-gradient)]
     (impl/convolution-outer-kernel
      ~config :pooling
      (impl/convolution-roll-unroll-inner-kernel
       (when ~'input-valid?
        (let [input-addr# ~'input-addr
              input-val# (v-aget input-ary# input-addr#)
              output-addr# (+ (* ~'out-y ~'output-width)
                              ~'out-x
                              ~'chan-output-offset)
              output-val# (v-aget output-ary# output-addr#)]
          (v-aset input-gradient-ary# input-addr#
                  (+ (v-aget input-gradient-ary# input-addr#)
                     (/ (v-aget output-gradient-ary# output-addr#)
                        ~'kernel-num-elems)))))))))

(defmacro cpu-avg-exc-pad-pooling-forward-impl
  [input output config cast-fn]
  `(let [input-ary# (ArrayView/toView ~input)
         output-ary# (ArrayView/toView ~output)]
     (impl/convolution-outer-kernel
      ~config :pooling
      (impl/convolution-roll-unroll-inner-kernel
       (let [input-val# (~cast-fn (if ~'input-valid?
                                  (v-aget input-ary# ~'input-addr)
                                  0.0))
             output-addr# (+ (* ~'out-y ~'output-width)
                             ~'out-x
                             ~'chan-output-offset)
             output-val# (v-aget output-ary# output-addr#)]
         (v-aset output-ary# output-addr#
                 (+ output-val#
                    (/ input-val#
                       ~'exc-pad-kernel-num-elems))))))))

(defmacro cpu-avg-exc-pad-pooling-backward-impl
  [input output input-gradient output-gradient config cast-fn]
  `(let [input-ary# (ArrayView/toView ~input)
         output-ary# (ArrayView/toView ~output)
         input-gradient-ary# (ArrayView/toView ~input-gradient)
         output-gradient-ary# (ArrayView/toView ~output-gradient)]
     (impl/convolution-outer-kernel
      ~config :pooling
      (impl/convolution-roll-unroll-inner-kernel
       (when ~'input-valid?
        (let [input-addr# ~'input-addr
              input-val# (v-aget input-ary# input-addr#)
              output-addr# (+ (* ~'out-y ~'output-width)
                              ~'out-x
                              ~'chan-output-offset)
              k-idx# (+ (* ~'k-y ~'kernel-width) ~'k-x)
              output-val# (v-aget output-ary# output-addr#)]
          (v-aset input-gradient-ary# input-addr#
                  (+ (v-aget input-gradient-ary# input-addr#)
                     (/ (v-aget output-gradient-ary# output-addr#)
                        ~'exc-pad-kernel-num-elems)))))))))

(defmacro cpu-prepare-bernoulli-impl
  [mult-buffer rand-buffer probability cast-fn]
  `(let [probability# (~cast-fn ~probability)
         scale-val# (~cast-fn (/ 1.0 probability#))
         mult-ary# (ArrayView/toView ~mult-buffer)
         elem-count# (.length mult-ary#)
         rand-ary# (ArrayView/toView ~rand-buffer)]
     (c-for [idx# 0 (< idx# elem-count#) (inc idx#)]
            (v-aset mult-ary# idx#
                    (~cast-fn (if (> (v-aget rand-ary# idx#)
                                     probability#)
                                0.0
                                scale-val#))))))


(defmacro cpu-prepare-gaussian-impl
  [mult-buffer rand-buffer cast-fn]
  `(let [mult-ary# (ArrayView/toView ~mult-buffer)
         elem-count# (.length mult-ary#)
         rand-ary# (ArrayView/toView ~rand-buffer)]
     (c-for [idx# 0 (< idx# elem-count#) (inc idx#)]
            (v-aset mult-ary# idx#
                    (~cast-fn (v-aget rand-ary# idx#))))))



(defmacro sum-double-var
  "Carefully written macro to sum a double variable.  Note that we are careful
to avoid adding the first calculated answer to 0.0 as if that answer is very small
we would introduce roundoff error immediately.  So we need a slightly more complex loop
in order to avoid adding a small number to 0."
  [idx-var num-iters stmt]
  `(double
    (if (= 0 ~num-iters)
        0.0
        (loop [sum-var# (let [~idx-var 0] ~stmt)
               ~idx-var 1]
          (if (< ~idx-var ~num-iters)
            (recur (+ sum-var# ~stmt) (inc ~idx-var))
            sum-var#)))))


(defmacro a-pluseq
  [ary idx data]
  `(aset ~ary ~idx
         (+ (aget ~ary ~idx) ~data)))


(defmacro a-minuseq
  [ary idx data]
  `(aset ~ary ~idx
         (- (aget ~ary ~idx) ~data)))

(defmacro v-aget-squared
  [ary idx]
  `(let [data-val# (v-aget ~ary ~idx)]
     (* data-val# data-val#)))

(defn calculate-look-amounts
  [^double n]
  (let [look-behind (+ 1 (Math/floor (/ (- n 1) 2.0)))
        look-ahead (- n look-behind)]
    {:remove-index look-behind
     :add-index look-ahead}))

(defmacro calc-squared-sums!
  "Calculate a running squared sum placing result in squared-sum-ary.
remove-index and add index are defined as one past the beginning
of the range relative to n and the end of the range (inclusive).
Calculates: (sum(x[i]^2)*alpha + K)"
  [input n-items remove-index add-index squared-sum-view alpha k cast-fn ]
  `(let [input# ~input
         n-items# ~n-items
         remove-index# ~remove-index
         add-index# ~add-index
         squared-sum-view# ~squared-sum-view
         alpha# (~cast-fn ~alpha)
         k# (~cast-fn ~k)]
     (.fill squared-sum-view# 0)
     (let [initial-sum#
           (loop [item-idx# 0
                  initial-sum# 0.0]
             (if (< item-idx# add-index#)
               (recur (inc item-idx#)
                      (+ initial-sum#
                         (v-aget-squared input# item-idx#)))
               initial-sum#))]
       (loop [item-idx# 0
              running-sum# (double initial-sum#)]
         (when (< item-idx# n-items#)
           (let [subtract-item# (- item-idx# remove-index#)
                 add-item# (+ item-idx# add-index#)
                 running-sum# (double running-sum#)
                 running-sum# (double
                               (if (>= subtract-item# 0)
                                 (- running-sum#
                                    (v-aget-squared input# subtract-item#))
                                 running-sum#))
                 running-sum# (double
                               (if (< add-item# n-items#)
                                 (+ running-sum#
                                    (v-aget-squared input# add-item#))
                                 running-sum#))]
             (.set squared-sum-view# item-idx# (+ k#
                                                  (* alpha#
                                                     (~cast-fn running-sum#))))
             (recur (inc item-idx#) running-sum#)))))))


(defn safe-positive-pow
  "Assuming X is supposed to be positive, perform a safe pow where we do not let X go to zero."
  ^double [^double x ^double y]
  (if (< y 0.0)
    (Math/pow (Math/max x 1e-6) y)
    (Math/pow x y)))


(defmacro cpu-lrn-forward-impl
  [input output input-tensor n k alpha beta cast-fn]
  `(let [input# (ArrayView/toView ~input)
         output# (ArrayView/toView ~output)
         input-tensor# ~input-tensor
         n-channels# (long (.channel-count input-tensor#))
         height# (long (.height input-tensor#))
         width# (long (.width input-tensor#))
         n# (~cast-fn (max 1.0 (~cast-fn (min (~cast-fn ~n) n-channels#))))
         k# (~cast-fn ~k)
         alpha# (/ (~cast-fn ~alpha)
                   n#)
         beta# (~cast-fn ~beta)
         neg-beta# (- beta#)
         [batch-size# batch-stride#] (math/batch-shape input-tensor#)
         batch-size# (long batch-size#)
         batch-stride# (long batch-stride#)
         channel-stride# (* width# height#)
         num-pixels# channel-stride#
         ;; Normalization window width in elements.
         ;; LRN layer uses a window [center-lookBehind, center+lookAhead],
         ;; where lookBehind = floor ( (lrnN-1)/2), lookAhead
         ;; = lrnN-lookBehind-1. So for n=10, the window is [k-4...k...k+5] with a total of 10
         ;; samples.
         ;; cudnnCreateLRNDescriptor.
         look-data# (calculate-look-amounts n#)
         ;;We use a running sum so when going to the next 'n'
         ;;we want to remove the item at 'n - remove-index'
         ;;and add the item at 'n + add-index'.  As such remove-index
         ;;is technically one past the beginning of our range while
         ;;add-index is the last possible valid index of our range.
         remove-index# (long (:remove-index look-data#))
         add-index# (long (:add-index look-data#))]
     (c-for
      [batch-idx# 0 (< batch-idx# batch-size#) (inc batch-idx#)]
      (let [start-offset# (* batch-idx# batch-stride# )
            ;;Create a function that will be used to parallelize the computation
            lrn-forward-fn#
            (fn [^long start# ^long len#]
              (try
               (let [end# (+ start# len#)
                     squared-sum-view# (ArrayView/toView (double-array n-channels#))]
                 (c-for
                  [pix-idx# start# (< pix-idx# end#) (inc pix-idx#)]
                  (let [pix-offset# (+ start-offset# pix-idx#)
                        ;;Setup input to stride over image channels at this pixel.
                        input# (.toStridedView input# pix-offset# channel-stride#)
                        output# (.toStridedView output# pix-offset# channel-stride#)]
                    (calc-squared-sums! input# n-channels# remove-index# add-index#
                                        squared-sum-view# alpha# k# ~cast-fn)
                    (c-for
                     [chan-idx# 0 (< chan-idx# n-channels#) (inc chan-idx#)]
                     (let [divisor# (safe-positive-pow
                                     (.get squared-sum-view# chan-idx#)
                                     beta#)]
                       (.set output# chan-idx# (~cast-fn (/ (.get input# chan-idx#)
                                                            divisor#))))))))
               (catch Throwable e# (clojure.pprint/pprint e#))))]
        ;;(pixel-fn# 0 num-pixels#)
        (parallel/launch-parallel-for num-pixels# lrn-forward-fn#)))))


(defmacro cpu-lrn-backward-impl
  "backward pass, calculated using sage:
https://github.com/thinktopic/cortex/blob/local-response-normalization/sage/local-response-normalization.txt"
  [input output-gradient input-gradient
   input-tensor n k alpha beta cast-fn]
  `(let [input# (ArrayView/toView ~input)
         output-gradient# (ArrayView/toView ~output-gradient)
         input-gradient# (ArrayView/toView ~input-gradient)
         input-tensor# ~input-tensor
         n-channels# (long (.channel-count input-tensor#))
         height# (long (.height input-tensor#))
         width# (long (.width input-tensor#))
         n# (~cast-fn (max 1.0 (~cast-fn (min (~cast-fn ~n) n-channels#))))
         k# (~cast-fn ~k)
         alpha# (/ (~cast-fn ~alpha)
                   n#)
         beta# (~cast-fn ~beta)
         neg-beta# (- beta#)
         [batch-size# batch-stride#] (math/batch-shape input-tensor#)
         batch-size# (long batch-size#)
         batch-stride# (long batch-stride#)
         channel-stride# (* width# height#)
         num-pixels# channel-stride#
         look-data# (calculate-look-amounts n#)
         ;;We use a running sum so when going to the next 'n'
         ;;we want to remove the item at 'n - remove-index'
         ;;and add the item at 'n + add-index'.  As such remove-index
         ;;is technically one past the beginning of our range while
         ;;add-index is the last possible valid index of our range.
         remove-index# (long (:remove-index look-data#))
         add-index# (long (:add-index look-data#))]
     (c-for
      [batch-idx# 0 (< batch-idx# batch-size#) (inc batch-idx#)]
      (let [start-offset# (* batch-idx# batch-stride# )
            ;;Create a function that will be used to parallelize the computation
            lrn-backward-fn#
            (fn [^long start# ^long len#]
              (try
               (let [end# (+ start# len#)
                     squared-sum-view# (ArrayView/toView (double-array n-channels#))]
                 (c-for
                  [pix-idx# start# (< pix-idx# end#) (inc pix-idx#)]
                  (let [pix-offset# (+ start-offset# pix-idx#)
                        ;;Setup input to stride over image channels at this pixel.
                        input# (.toStridedView input# pix-offset# channel-stride#)
                        output-gradient# (.toStridedView output-gradient#
                                                         pix-offset# channel-stride#)
                        input-gradient# (.toStridedView input-gradient#
                                                         pix-offset# channel-stride#)]
                    (calc-squared-sums! input# n-channels# remove-index# add-index#
                                       squared-sum-view# alpha# k# ~cast-fn)
                    (c-for
                     [chan-idx# 0 (< chan-idx# n-channels#) (inc chan-idx#)]
                     (let [range-start# (long (max 0 (+ (- chan-idx# remove-index#) 1)))
                           past-range-end# (long (min n-channels# (+ chan-idx# add-index# 1)))]
                       (loop [range-idx# range-start#
                              output-accum# (double 0.0)]
                         (if (< range-idx# past-range-end#)
                           (let [squared-to-beta# (safe-positive-pow
                                                   (.get squared-sum-view#
                                                         range-idx#)
                                                   beta#)
                                 squared-to-beta-minus-one#
                                 (safe-positive-pow
                                  (.get squared-sum-view#
                                        range-idx#)
                                  (- beta# 1.0))


                                 shared-quantity# (/ (* -2.0 squared-to-beta-minus-one#
                                                        (.get input# chan-idx#)
                                                        (.get input# range-idx#)
                                                        alpha# beta#)
                                                     (* squared-to-beta#
                                                        squared-to-beta#))
                                 addend# (double
                                          (if (= range-idx# chan-idx#)
                                            (/ 1.0 squared-to-beta#)
                                            0.0))]
                             (recur (inc range-idx#)
                                    (double
                                     (+ output-accum#
                                        (* (+ shared-quantity# addend#)
                                           (.get output-gradient# range-idx#))))))
                           (.set input-gradient# chan-idx# output-accum#))))))))
               (catch Throwable e# (clojure.pprint/pprint e#))))]
        (parallel/launch-parallel-for num-pixels# lrn-backward-fn#)))))


(extend-type DoubleArrayView
  PCPUNetworkImpl
  (cpu-fill [buffer value]
    (Arrays/fill (.data buffer) (.offset buffer)
                 (+ (.offset buffer) (.length buffer)) (double value)))
  (cpu-max-pooling-forward [input ^DoubleArrayView output conv-config]
    (cpu-max-pooling-forward-impl input output conv-config double))
  (cpu-max-pooling-backward [input ^DoubleArrayView output ^DoubleArrayView input-gradient
                             ^DoubleArrayView output-gradient conv-config]
    (cpu-max-pooling-backward-impl input output input-gradient output-gradient conv-config
                                   double))
  (cpu-avg-pooling-forward [input ^DoubleArrayView output conv-config]
    (cpu-avg-pooling-forward-impl input output conv-config double))
  (cpu-avg-pooling-backward [input ^DoubleArrayView output ^DoubleArrayView input-gradient
                             ^DoubleArrayView output-gradient conv-config]
    (cpu-avg-pooling-backward-impl input output input-gradient output-gradient conv-config
                                   double))
  (cpu-avg-exc-pad-pooling-forward [input ^DoubleArrayView output conv-config]
    (cpu-avg-exc-pad-pooling-forward-impl input output conv-config double))
  (cpu-avg-exc-pad-pooling-backward [input ^DoubleArrayView output ^DoubleArrayView input-gradient
                             ^DoubleArrayView output-gradient conv-config]
    (cpu-avg-exc-pad-pooling-backward-impl input output input-gradient output-gradient conv-config
                                   double))
  (cpu-prepare-bernoulli-dropout [mult-buffer ^FloatArrayView rand-buffer probability]
    (cpu-prepare-bernoulli-impl mult-buffer rand-buffer probability double))
  (cpu-prepare-gaussian-dropout [mult-buffer ^FloatArrayView rand-buffer]
    (cpu-prepare-gaussian-impl mult-buffer rand-buffer double))
  (cpu-lrn-forward [input ^DoubleArrayView output ^Tensor input-tensor n k alpha beta]
    (cpu-lrn-forward-impl input output input-tensor n k alpha beta double))
  (cpu-lrn-backward [input ^DoubleArrayView output-gradient ^DoubleArrayView input-gradient
                     ^Tensor input-tensor n k alpha beta]
    (cpu-lrn-backward-impl input output-gradient input-gradient input-tensor
                           n k alpha beta double)))


(extend-type FloatArrayView
  PCPUNetworkImpl
  (cpu-fill [buffer value]
    (Arrays/fill (.data buffer) (.offset buffer)
                 (+ (.offset buffer) (.length buffer)) (float value)))
  (cpu-max-pooling-forward [input ^FloatArrayView output conv-config]
    (cpu-max-pooling-forward-impl input output conv-config float))
  (cpu-max-pooling-backward [input ^FloatArrayView output ^FloatArrayView input-gradient
                             ^FloatArrayView output-gradient conv-config]
    (cpu-max-pooling-backward-impl input output input-gradient output-gradient conv-config
                                   float))
  (cpu-avg-pooling-forward [input ^FloatArrayView output conv-config]
    (cpu-avg-pooling-forward-impl input output conv-config float))
  (cpu-avg-pooling-backward [input ^FloatArrayView output ^FloatArrayView input-gradient
                             ^FloatArrayView output-gradient conv-config]
    (cpu-avg-pooling-backward-impl input output input-gradient output-gradient conv-config
                                   float))
  (cpu-avg-exc-pad-pooling-forward [input ^FloatArrayView output conv-config]
    (cpu-avg-exc-pad-pooling-forward-impl input output conv-config float))
  (cpu-avg-exc-pad-pooling-backward [input ^FloatArrayView output ^FloatArrayView input-gradient
                                     ^FloatArrayView output-gradient conv-config]
    (cpu-avg-exc-pad-pooling-backward-impl input output input-gradient output-gradient conv-config
                                           float))
  (cpu-prepare-bernoulli-dropout [mult-buffer ^FloatArrayView rand-buffer probability]
    (cpu-prepare-bernoulli-impl mult-buffer rand-buffer probability float))
  (cpu-prepare-gaussian-dropout [mult-buffer ^FloatArrayView rand-buffer]
    (cpu-prepare-gaussian-impl mult-buffer rand-buffer float))
  (cpu-lrn-forward [input ^FloatArrayView output ^Tensor input-tensor n k alpha beta]
    (cpu-lrn-forward-impl input output input-tensor n k alpha beta float))
  (cpu-lrn-backward [input ^FloatArrayView output-gradient ^FloatArrayView input-gradient
                     ^Tensor input-tensor n k alpha beta]
    (cpu-lrn-backward-impl input output-gradient input-gradient input-tensor
                           n k alpha beta float)))

(defn device-array->view
  [dev-ary & [size]]
  (if size
    (dtype/->view (math/device-buffer dev-ary) 0 size)
    (dtype/->view (math/device-buffer dev-ary))))


(defn- first-buffer
  [buffers]
  (device-array->view (compute-layers/first-buffer buffers)))


(defn- first-gradient
  [buffers]
  (device-array->view (compute-layers/first-gradient buffers)))


(defmulti cpu-layer
  "Create a implementation layer for the cpu backend."
  (fn [backend layer batch-size]
    (get layer :type)))


(defn conv-type-layer->conv-config
  "Backwards compatibility function necessary as the node format has changed over time."
  [conv-layer]
  (let [input-dims (first (graph/node->input-dimensions conv-layer))
        output-dims (first (graph/node->output-dimensions conv-layer))]
    (assoc conv-layer
           :input-channels (get input-dims :channels)
           :input-width (get input-dims :width)
           :input-height (get input-dims :height)
           :input-size (graph/dimensions->size input-dims)
           :output-channels (get output-dims :channels)
           :output-width (get output-dims :width)
           :output-height (get output-dims :height)
           :output-size (graph/dimensions->size output-dims))))



(defrecord MaxPooling [backend conv-config]
  compute-protocols/ComputeLayer
  (forward [layer parameters input-buffers output-buffers]
    (let [input (compute-layers/first-buffer input-buffers)
          output (compute-layers/first-buffer output-buffers)
          pool-op (get conv-config :pool-op :max)]
      (cpu-drv/with-stream-dispatch (drv/get-stream (.backend layer))
        (doall (pmap (fn [[input output]]
                       (condp = pool-op
                         :max
                         (cpu-max-pooling-forward (device-array->view input)
                                                  (device-array->view output)
                                                  conv-config)
                         :avg
                         (cpu-avg-pooling-forward (device-array->view input)
                                                  (device-array->view output)
                                                  conv-config)
                         :avg-exc-pad
                         (cpu-avg-exc-pad-pooling-forward (device-array->view input)
                                                          (device-array->view output)
                                                          conv-config)))
                     (math/batched-data-to-per-input-data [input output]))))))

  (backward [layer parameters output-buffers input-buffers]
    (let [input (compute-layers/first-buffer input-buffers)
          output (compute-layers/first-buffer output-buffers)
          input-gradient (compute-layers/first-gradient input-buffers)
          output-gradient (compute-layers/first-gradient output-buffers)
          pool-op (get conv-config :pool-op :avg)]
      (cpu-drv/with-stream-dispatch (drv/get-stream (.backend layer))
        (doall (pmap (fn [[input output input-gradient output-gradient]]
                       (cpu-fill (device-array->view input-gradient) 0)
                       (condp = pool-op
                         :max
                         (cpu-max-pooling-backward (device-array->view input)
                                                   (device-array->view output)
                                                   (device-array->view input-gradient)
                                                   (device-array->view output-gradient)
                                                   conv-config)
                         :avg
                         (cpu-avg-pooling-backward (device-array->view input)
                                                   (device-array->view output)
                                                   (device-array->view input-gradient)
                                                   (device-array->view output-gradient)
                                                   conv-config)
                         :avg-exc-pad
                         (cpu-avg-exc-pad-pooling-backward (device-array->view input)
                                                           (device-array->view output)
                                                           (device-array->view input-gradient)
                                                           (device-array->view output-gradient)
                                                           conv-config)))
                     (math/batched-data-to-per-input-data
                      [input output input-gradient output-gradient])))))))


(defmethod cpu-layer :max-pooling
  [backend layer batch-size]
  (->MaxPooling backend (conv-type-layer->conv-config layer)))


(defn- layer-output->tensor
  [layer batch-size]
  (let [{:keys [channels width height]} (first (graph/node->output-dimensions layer))]
    (math/tensor batch-size channels height width)))


(defrecord LocalResponseNormalization [backend layer batch-size]
  compute-protocols/ComputeLayer
  (forward [this parameters input-buffers output-buffers]
    (let [{:keys [n k alpha beta]} layer]
      (cpu-drv/with-stream-dispatch (drv/get-stream backend)
        (cpu-lrn-forward (first-buffer input-buffers)
                         (first-buffer output-buffers)
                         (layer-output->tensor layer batch-size)
                         n k alpha beta))))
  (backward [this parameters output-buffers input-buffers]
    (let [{:keys [n k alpha beta]} layer]
      (cpu-drv/with-stream-dispatch (drv/get-stream backend)
        (cpu-lrn-backward (first-buffer input-buffers)
                          (first-gradient output-buffers)
                          (first-gradient input-buffers)
                          (layer-output->tensor layer batch-size)
                          n k alpha beta)))))


(defmethod cpu-layer :local-response-normalization
  [backend layer batch-size]
  (->LocalResponseNormalization backend layer batch-size))


(extend-type CPUBackend
  nn-backend/PLayerCreation
  (create [backend layer batch-size]
    (cpu-layer backend layer batch-size))
  nn-backend/PDropout
  (prepare-bernoulli-dropout! [backend probability rand-buffer mult-buffer]
    (cpu-drv/with-stream-dispatch (.stream backend)
      (cpu-prepare-bernoulli-dropout (device-array->view mult-buffer)
                                     (device-array->view rand-buffer) probability)))
  ;;Gaussian distribution copied to mult buffer.
  (prepare-gaussian-dropout! [backend rand-buffer mult-buffer]
    (cpu-drv/with-stream-dispatch (.stream backend)
      (cpu-prepare-gaussian-dropout (device-array->view mult-buffer)
                                    (device-array->view rand-buffer)))))
